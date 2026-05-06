package scheduler

import chisel3._
import chisel3.util._
import decoder.MicroOp

class DispatchBundle extends Bundle {
  val warpId = UInt(5.W)
  val microOp = new MicroOp()
  val vgprBase = UInt(12.W)
}

class WarpSchedulerIO(val numWarps: Int = 32) extends Bundle {
  // Upstream: IBuffer interface
  val ibEmptyMask = Input(UInt(numWarps.W))
  val ibHeadMicroOps = Input(Vec(numWarps, new MicroOp()))
  val wsIbPopReq = Output(Bool())
  val wsIbPopId = Output(UInt(log2Ceil(numWarps).W))

  // Downstream: Operand Collector / Dispatch
  val dispatch = Decoupled(new DispatchBundle())

  // Control: WST updates
  val allocReq = Input(Bool())
  val allocWarpId = Input(UInt(log2Ceil(numWarps).W))

  // Scoreboard release (from execution unit writeback)
  val releaseReq = Flipped(Valid(new ScoreboardReleaseReq))

  // ── Phase 1.1: New IO for WST field alignment ──
  // Block Scheduler interface (32-bit active mask per MAS spec)
  val blkschActiveMask = Input(UInt(32.W))
  val blkschBarId = Input(UInt(4.W))

  // K-Cache interface
  val kcacheMissWaitMask = Input(UInt(numWarps.W))
  val kcacheFillAckMask = Input(UInt(numWarps.W))

  // EXIT / Block Done interface
  val wsWarpExitValid = Output(Bool())
  val wsWarpExitId = Output(UInt(log2Ceil(numWarps).W))
  val wsBlockDone = Output(Bool())
}

class WarpScheduler(val numWarps: Int = 32, val numRegs: Int = 256,
                    val numScoreboardSlots: Int = 6) extends Module {
  val io = IO(new WarpSchedulerIO(numWarps))

  // ==========================================
  // Feature 1: Warp State Table (WST)
  // ==========================================
  val wstValid = RegInit(VecInit(Seq.fill(numWarps)(false.B)))
  val wstReady = RegInit(VecInit(Seq.fill(numWarps)(false.B)))
  val wstStalled = RegInit(VecInit(Seq.fill(numWarps)(false.B)))
  val wstAgeCounter = RegInit(VecInit(Seq.fill(numWarps)(0.U(8.W))))
  val wstInstTypeBuf = RegInit(VecInit(Seq.fill(numWarps)(InstType.OTHER)))
  val wstDestRegBuf = RegInit(VecInit(Seq.fill(numWarps)(0.U(8.W))))
  val wstVgprBase = RegInit(VecInit(Seq.fill(numWarps)(0.U(12.W))))

  // ── Phase 1.1: New WST fields ──
  val wstWaitKCache = RegInit(VecInit(Seq.fill(numWarps)(false.B)))
  val wstActiveMask = RegInit(VecInit(Seq.fill(numWarps)(0.U(32.W))))
  val wstBarId = RegInit(VecInit(Seq.fill(numWarps)(0.U(4.W))))

  // ── Phase 1.1: EXIT tracking ──
  val blockDone = RegInit(false.B)
  val warpExitValid = RegInit(false.B)
  val warpExitId = RegInit(0.U(log2Ceil(numWarps).W))

  // ── Phase 1.1: Scoreboard (Slot-based) ──
  // Slot Matrix: numWarps x numScoreboardSlots
  // Each slot: valid (1 bit), regId (8 bits), pendingCount (4 bits)
  val sbSlotValid = RegInit(VecInit(Seq.fill(numWarps)(VecInit(Seq.fill(numScoreboardSlots)(false.B)))))
  val sbSlotRegId = RegInit(VecInit(Seq.fill(numWarps)(VecInit(Seq.fill(numScoreboardSlots)(0.U(8.W))))))
  val sbSlotPending = RegInit(VecInit(Seq.fill(numWarps)(VecInit(Seq.fill(numScoreboardSlots)(0.U(4.W))))))

  // ==========================================
  // Allocation (Warp creation by Block Scheduler)
  // ==========================================
  when(io.allocReq && !wstValid(io.allocWarpId)) {
    wstValid(io.allocWarpId) := true.B
    wstReady(io.allocWarpId) := true.B
    wstStalled(io.allocWarpId) := false.B
    wstAgeCounter(io.allocWarpId) := 0.U
    // Phase 1.1: Write Active_Mask and Bar_ID from Block Scheduler
    wstActiveMask(io.allocWarpId) := io.blkschActiveMask
    wstBarId(io.allocWarpId) := io.blkschBarId
    wstWaitKCache(io.allocWarpId) := false.B
  }

  // ==========================================
  // Peek IBuffer to update InstTypeBuf and DestRegBuf
  // ==========================================
  for (w <- 0 until numWarps) {
    val ibEmpty = io.ibEmptyMask(w)
    val headOp = io.ibHeadMicroOps(w)

    // Simple heuristic for inst type
    val instType = Mux(headOp.opcode === 0x10.U, InstType.TMA,
                   Mux(headOp.opcode === 0x20.U, InstType.WGMMA,
                   InstType.ALU))

    when(wstValid(w)) {
      wstInstTypeBuf(w) := instType
      wstDestRegBuf(w) := headOp.rd

      // If IBuffer is empty, warp cannot be ready
      when(ibEmpty) {
        wstReady(w) := false.B
      }.elsewhen(!wstStalled(w) && !wstWaitKCache(w)) {
        wstReady(w) := true.B
      }
    }
  }

  // ==========================================
  // Phase 1.1: Wait_KCache logic
  // ==========================================
  for (w <- 0 until numWarps) {
    when(io.kcacheMissWaitMask(w)) {
      wstWaitKCache(w) := true.B
      wstReady(w) := false.B
    }
    when(io.kcacheFillAckMask(w)) {
      wstWaitKCache(w) := false.B
      when(!wstStalled(w)) {
        wstReady(w) := true.B
      }
    }
  }

  // ==========================================
  // Phase 1.1: Age counter (only when Active_Mask != 0)
  // ==========================================
  for (w <- 0 until numWarps) {
    when(wstValid(w) && wstActiveMask(w).orR) {
      wstAgeCounter(w) := wstAgeCounter(w) + 1.U
    }
  }

  // ==========================================
  // Pack WST into Seq[WarpContext] for generator
  // ==========================================
  val readyWarps = Seq.tabulate(numWarps) { w =>
    WarpContext(
      valid = wstValid(w),
      ready = wstReady(w),
      stalled = wstStalled(w),
      waitKCache = wstWaitKCache(w),
      ageCounter = wstAgeCounter(w),
      instType = wstInstTypeBuf(w),
      destReg = wstDestRegBuf(w),
      vgprBase = wstVgprBase(w),
      activeMask = wstActiveMask(w),
      barId = wstBarId(w)
    )
  }

  // ==========================================
  // Phase 1.1: Scoreboard (Slot-based) Logic
  // ==========================================

  // ── Scoreboard Release ──
  when(io.releaseReq.valid) {
    val wId = io.releaseReq.bits.warpId(log2Ceil(numWarps) - 1, 0)
    val rId = io.releaseReq.bits.regId

    when(rId =/= 0.U) {
      // Find the slot tracking this register
      val slotMatch = Wire(Vec(numScoreboardSlots, Bool()))
      for (s <- 0 until numScoreboardSlots) {
        slotMatch(s) := sbSlotValid(wId)(s) && sbSlotRegId(wId)(s) === rId
      }
      val anyMatch = slotMatch.reduce(_ || _)
      val matchIdx = PriorityEncoder(slotMatch)

      when(anyMatch) {
        when(sbSlotPending(wId)(matchIdx) <= 1.U) {
          // pendingCount reaches 0 → clear slot
          sbSlotValid(wId)(matchIdx) := false.B
          sbSlotRegId(wId)(matchIdx) := 0.U
          sbSlotPending(wId)(matchIdx) := 0.U
        }.otherwise {
          sbSlotPending(wId)(matchIdx) := sbSlotPending(wId)(matchIdx) - 1.U
        }
      }
      // If register not found, silently ignore
    }
  }

  // ── Hazard Detection (Combinational) ──
  val hazardMask = Wire(Vec(numWarps, Bool()))
  val slotFullMask = Wire(Vec(numWarps, Bool()))

  for (w <- 0 until numWarps) {
    val headOp = io.ibHeadMicroOps(w)
    val rs1 = headOp.rs1
    val rs2 = headOp.rs2
    val rd = headOp.rd

    // Check if any valid slot matches the queried register
    // Zero register (0) is always ignored
    val rs1Hazard = (rs1 =/= 0.U) && (0 until numScoreboardSlots).map { s =>
      sbSlotValid(w)(s) && sbSlotRegId(w)(s) === rs1
    }.reduce(_ || _)

    val rs2Hazard = (rs2 =/= 0.U) && (0 until numScoreboardSlots).map { s =>
      sbSlotValid(w)(s) && sbSlotRegId(w)(s) === rs2
    }.reduce(_ || _)

    val rdHazard = (rd =/= 0.U) && (0 until numScoreboardSlots).map { s =>
      sbSlotValid(w)(s) && sbSlotRegId(w)(s) === rd
    }.reduce(_ || _)

    hazardMask(w) := rs1Hazard || rs2Hazard || rdHazard

    // Slot Full detection
    slotFullMask(w) := (0 until numScoreboardSlots).map(s => sbSlotValid(w)(s)).reduce(_ && _)

    // Update WST if hazard detected
    when(wstValid(w) && wstReady(w) && hazardMask(w)) {
      wstReady(w) := false.B
      wstStalled(w) := true.B
    }

    // Wakeup logic: if stalled and hazard is cleared, wake up
    when(wstValid(w) && wstStalled(w) && !hazardMask(w)) {
      wstStalled(w) := false.B
      wstReady(w) := true.B
    }
  }

  // Override readyWarps ready bit with hazard/empty info for current cycle scheduling
  val schedReadyWarps = readyWarps.zipWithIndex.map { case (ctx, w) =>
    val hasHazard = hazardMask(w)
    val isIbEmpty = io.ibEmptyMask(w)
    val isSlotFull = slotFullMask(w)
    // A warp is schedulable only if: ready, no hazard, IBuffer not empty, and not Slot Full
    ctx.copy(ready = ctx.ready && !hasHazard && !isIbEmpty && !isSlotFull)
  }

  // ==========================================
  // Feature 3: GTO Arbiter
  // ==========================================
  val lastWarpId = RegInit(0.U(log2Ceil(numWarps).W))

  val (anyReady, winnerId) = SchedulerLogic.scheduleGTO(schedReadyWarps, lastWarpId)

  // ==========================================
  // Feature 4: Dispatcher
  // ==========================================
  io.dispatch.valid := anyReady
  io.dispatch.bits.warpId := winnerId
  io.dispatch.bits.microOp := io.ibHeadMicroOps(winnerId)
  io.dispatch.bits.vgprBase := wstVgprBase(winnerId)

  io.wsIbPopReq := false.B
  io.wsIbPopId := winnerId

  // ── Phase 1.1: EXIT / Block Done signals ──
  // Default: clear EXIT pulse after one cycle
  warpExitValid := false.B
  io.wsWarpExitValid := warpExitValid
  io.wsWarpExitId := warpExitId
  io.wsBlockDone := blockDone

  // Issue transaction
  when(io.dispatch.valid && io.dispatch.ready) {
    // Record last scheduled warp for GTO
    lastWarpId := winnerId

    // Pop from IBuffer
    io.wsIbPopReq := true.B

    // Reset age counter
    wstAgeCounter(winnerId) := 0.U

    // ── Phase 1.1: Detect EXIT instruction ──
    val isExitInst = io.ibHeadMicroOps(winnerId).opcode === Opcode.EXIT

    when(isExitInst) {
      // Warp lifecycle: READY → EXITING → FREE
      wstValid(winnerId) := false.B
      wstReady(winnerId) := false.B
      wstStalled(winnerId) := false.B
      wstWaitKCache(winnerId) := false.B
      wstActiveMask(winnerId) := 0.U
      wstBarId(winnerId) := 0.U

      // Clear scoreboard slots for this warp
      for (s <- 0 until numScoreboardSlots) {
        sbSlotValid(winnerId)(s) := false.B
        sbSlotRegId(winnerId)(s) := 0.U
        sbSlotPending(winnerId)(s) := 0.U
      }

      // Register EXIT signal for one cycle
      // Default clear (false.B) is overridden by this when block
      warpExitValid := true.B
      warpExitId := winnerId

      // Check if all warps have exited → Block Done
      blockDone := !wstValid.asUInt.orR
    }.otherwise {
      // ── Phase 1.1: Scoreboard allocation (Slot-based) ──
      val destReg = io.ibHeadMicroOps(winnerId).rd

      when(destReg =/= 0.U) {
        // Check if register is already tracked in any slot of this warp
        val slotHit = Wire(Vec(numScoreboardSlots, Bool()))
        for (s <- 0 until numScoreboardSlots) {
          slotHit(s) := sbSlotValid(winnerId)(s) && sbSlotRegId(winnerId)(s) === destReg
        }
        val anyHit = slotHit.reduce(_ || _)
        val hitIdx = PriorityEncoder(slotHit)

        when(anyHit) {
          // Increment pending_count (saturate at 15)
          when(sbSlotPending(winnerId)(hitIdx) < 15.U) {
            sbSlotPending(winnerId)(hitIdx) := sbSlotPending(winnerId)(hitIdx) + 1.U
          }
        }.otherwise {
          // Find first free slot
          val freeSlotIdx = PriorityEncoder(VecInit(sbSlotValid(winnerId).map(v => !v)))
          when(freeSlotIdx < numScoreboardSlots.U) {
            sbSlotValid(winnerId)(freeSlotIdx) := true.B
            sbSlotRegId(winnerId)(freeSlotIdx) := destReg
            sbSlotPending(winnerId)(freeSlotIdx) := 1.U
          }
          // If no free slot, allocation is silently dropped (Slot Full)
          // The scheduler already checks slotFullMask before issuing
        }
      }
    }
  }
}
