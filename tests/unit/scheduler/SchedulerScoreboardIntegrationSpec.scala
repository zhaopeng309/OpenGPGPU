package scheduler

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import decoder.MicroOp

/**
 * Integration Test: Warp Scheduler + Scoreboard
 *
 * Tests the interaction between WarpScheduler and Scoreboard.
 *
 * Phase 1 scenarios from the Scoreboard Development Plan:
 * - Scoreboard hazard detection can drive Warp Scheduler stall/ready state
 * - Long-latency instructions filling all slots cause Slot Full
 * - Release from execution unit clears hazard state
 *
 * Note: In Phase 1.1, the WarpScheduler has its own Slot-based Scoreboard
 * integrated directly (replacing the old busyMatrix). The standalone Scoreboard
 * module is a separate, more sophisticated implementation for future use.
 * This test verifies the integrated Scoreboard works correctly.
 */
class SchedulerScoreboardIntegrationSpec extends AnyFlatSpec with ChiselScalatestTester {

  /**
   * Combined module: WarpScheduler with integrated Slot-based Scoreboard.
   *
   * In Phase 1.1, the WarpScheduler's internal Scoreboard (Slot-based)
   * replaces the old busyMatrix. The standalone Scoreboard module is
   * tested separately in ScoreboardModuleSpec.
   */
  class SchedulerWithScoreboard(val numWarps: Int = 4, val numRegs: Int = 32, val numSlots: Int = 3)
      extends Module {

    val io = IO(new Bundle {
      // WarpScheduler IO
      val ibEmptyMask = Input(UInt(numWarps.W))
      val ibHeadMicroOps = Input(Vec(numWarps, new MicroOp()))
      val dispatch = Decoupled(new DispatchBundle())
      val allocReq = Input(Bool())
      val allocWarpId = Input(UInt(log2Ceil(numWarps).W))

      // Scoreboard release (from execution unit writeback)
      val releaseReq = Input(Bool())
      val releaseWarpId = Input(UInt(log2Ceil(numWarps).W))
      val releaseRegId = Input(UInt(8.W))

      // Phase 1.1: WST field inputs
      val blkschActiveMask = Input(UInt(numWarps.W))
      val blkschBarId = Input(UInt(4.W))
      val kcacheMissWaitMask = Input(UInt(numWarps.W))
      val kcacheFillAckMask = Input(UInt(numWarps.W))

      // Scoreboard direct observation
      val slot_full_mask = Output(UInt(numWarps.W))
      val hazard_result = Output(Bool())
      val hazard_query_warp_id = Input(UInt(log2Ceil(numWarps).W))
      val hazard_query_rs1 = Input(UInt(8.W))
      val hazard_query_rs2 = Input(UInt(8.W))
      val hazard_query_rd = Input(UInt(8.W))

      // Scoreboard alloc observation
      val sb_alloc_done = Output(Bool())

      // EXIT / Block Done
      val wsWarpExitValid = Output(Bool())
      val wsWarpExitId = Output(UInt(log2Ceil(numWarps).W))
      val wsBlockDone = Output(Bool())
    })

    // Instantiate WarpScheduler (with integrated Slot-based Scoreboard)
    val ws = Module(new WarpScheduler(numWarps, numRegs, numSlots))
    ws.io.ibEmptyMask := io.ibEmptyMask
    ws.io.ibHeadMicroOps := io.ibHeadMicroOps
    ws.io.allocReq := io.allocReq
    ws.io.allocWarpId := io.allocWarpId
    ws.io.releaseReq.valid := io.releaseReq
    ws.io.releaseReq.bits.warpId := io.releaseWarpId
    ws.io.releaseReq.bits.regId := io.releaseRegId
    ws.io.blkschActiveMask := io.blkschActiveMask
    ws.io.blkschBarId := io.blkschBarId
    ws.io.kcacheMissWaitMask := io.kcacheMissWaitMask
    ws.io.kcacheFillAckMask := io.kcacheFillAckMask
    io.dispatch <> ws.io.dispatch
    io.wsWarpExitValid := ws.io.wsWarpExitValid
    io.wsWarpExitId := ws.io.wsWarpExitId
    io.wsBlockDone := ws.io.wsBlockDone

    // Instantiate standalone Scoreboard for observation
    val sb = Module(new Scoreboard(numWarps, numSlots))
    sb.io.alloc_req := io.allocReq
    sb.io.alloc_warp_id := io.allocWarpId
    sb.io.alloc_reg_id := io.ibHeadMicroOps(io.allocWarpId).rd
    sb.io.release_req := io.releaseReq
    sb.io.release_warp_id := io.releaseWarpId
    sb.io.release_reg_id := io.releaseRegId
    sb.io.hazard_query_warp_id := io.hazard_query_warp_id
    sb.io.hazard_query_rs1 := io.hazard_query_rs1
    sb.io.hazard_query_rs2 := io.hazard_query_rs2
    sb.io.hazard_query_rd := io.hazard_query_rd

    io.slot_full_mask := sb.io.slot_full_mask
    io.hazard_result := sb.io.hazard_result

    // Scoreboard alloc done is just a passthrough of alloc_req for observation
    io.sb_alloc_done := io.allocReq
  }

  behavior of "Scheduler + Scoreboard Integration"

  it should "Phase 1 - 场景 1: Scoreboard tracks register allocation and hazard" in {
    test(new SchedulerWithScoreboard(numWarps = 4, numRegs = 32, numSlots = 3)) { dut =>
      // Initialize
      dut.io.allocReq.poke(true.B)
      dut.io.allocWarpId.poke(0.U)
      dut.io.dispatch.ready.poke(true.B)
      dut.io.ibEmptyMask.poke("b1110".U) // Warp 0 has instructions
      dut.io.releaseReq.poke(false.B)
      dut.io.blkschActiveMask.poke("b0001".U)
      dut.io.blkschBarId.poke(0.U)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibHeadMicroOps(0).valid.poke(true.B)
      dut.io.ibHeadMicroOps(0).opcode.poke(0x01.U)
      dut.io.ibHeadMicroOps(0).rd.poke(5.U)
      dut.io.ibHeadMicroOps(0).rs1.poke(0.U)
      dut.io.ibHeadMicroOps(0).rs2.poke(0.U)

      // Cycle 1: Alloc + dispatch
      dut.clock.step(1)
      dut.io.allocReq.poke(false.B)

      // After allocation, Scoreboard should show R5 as busy
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_query_rs2.poke(0.U)
      dut.io.hazard_query_rd.poke(0.U)
      dut.io.hazard_result.expect(true.B)

      // Query R3 should NOT show hazard
      dut.io.hazard_query_rs1.poke(3.U)
      dut.io.hazard_result.expect(false.B)

      // Release R5
      dut.io.releaseReq.poke(true.B)
      dut.io.releaseWarpId.poke(0.U)
      dut.io.releaseRegId.poke(5.U)
      dut.clock.step(1)
      dut.io.releaseReq.poke(false.B)

      // After release, hazard cleared
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(false.B)
    }
  }

  it should "Phase 1 - 场景 2: Scoreboard Slot Full detection" in {
    test(new SchedulerWithScoreboard(numWarps = 4, numRegs = 32, numSlots = 3)) { dut =>
      // Initialize Warp 0
      dut.io.allocReq.poke(true.B)
      dut.io.allocWarpId.poke(0.U)
      dut.io.dispatch.ready.poke(true.B)
      dut.io.ibEmptyMask.poke("b1110".U)
      dut.io.releaseReq.poke(false.B)
      dut.io.blkschActiveMask.poke("b0001".U)
      dut.io.blkschBarId.poke(0.U)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibHeadMicroOps(0).valid.poke(true.B)
      dut.io.ibHeadMicroOps(0).opcode.poke(0x01.U)
      dut.io.ibHeadMicroOps(0).rs1.poke(0.U)
      dut.io.ibHeadMicroOps(0).rs2.poke(0.U)

      // Fill all 3 slots for Warp 0
      for (regId <- Seq(10, 11, 12)) {
        dut.io.ibHeadMicroOps(0).rd.poke(regId.U)
        dut.clock.step(1)
        dut.io.allocReq.poke(false.B)
        // Dispatch happens
        dut.clock.step(1)
        dut.io.allocReq.poke(true.B)
      }
      dut.io.allocReq.poke(false.B)

      // Warp 0 should be Slot Full
      val fullMask = dut.io.slot_full_mask.peek().litValue
      assert((fullMask & 0x1) != 0,
        s"Warp 0 should be Slot Full, mask = ${fullMask}")

      // Other warps should NOT be Slot Full
      assert((fullMask & 0xE) == 0,
        s"Other warps should not be Slot Full, mask = ${fullMask}")

      // Release one slot from Warp 0
      dut.io.releaseReq.poke(true.B)
      dut.io.releaseWarpId.poke(0.U)
      dut.io.releaseRegId.poke(10.U)
      dut.clock.step(1)
      dut.io.releaseReq.poke(false.B)

      // Warp 0 should no longer be Slot Full
      val fullMask2 = dut.io.slot_full_mask.peek().litValue
      assert((fullMask2 & 0x1) == 0,
        s"Warp 0 should no longer be Slot Full, mask = ${fullMask2}")
    }
  }

  it should "Phase 1 - 场景 3: Scoreboard RAW/WAW hazard detection" in {
    test(new SchedulerWithScoreboard(numWarps = 4, numRegs = 32, numSlots = 3)) { dut =>
      // Initialize
      dut.io.allocReq.poke(true.B)
      dut.io.allocWarpId.poke(0.U)
      dut.io.dispatch.ready.poke(true.B)
      dut.io.ibEmptyMask.poke("b1110".U)
      dut.io.releaseReq.poke(false.B)
      dut.io.blkschActiveMask.poke("b0001".U)
      dut.io.blkschBarId.poke(0.U)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibHeadMicroOps(0).valid.poke(true.B)
      dut.io.ibHeadMicroOps(0).opcode.poke(0x01.U)
      dut.io.ibHeadMicroOps(0).rd.poke(5.U)
      dut.io.ibHeadMicroOps(0).rs1.poke(0.U)
      dut.io.ibHeadMicroOps(0).rs2.poke(0.U)

      // Alloc R5
      dut.clock.step(1)
      dut.io.allocReq.poke(false.B)

      // RAW: reading R5 as source
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_query_rs2.poke(0.U)
      dut.io.hazard_query_rd.poke(0.U)
      dut.io.hazard_result.expect(true.B)

      // RAW: reading R5 as rs2
      dut.io.hazard_query_rs1.poke(0.U)
      dut.io.hazard_query_rs2.poke(5.U)
      dut.io.hazard_result.expect(true.B)

      // WAW: writing R5 as destination
      dut.io.hazard_query_rs1.poke(0.U)
      dut.io.hazard_query_rs2.poke(0.U)
      dut.io.hazard_query_rd.poke(5.U)
      dut.io.hazard_result.expect(true.B)

      // No hazard: unrelated registers
      dut.io.hazard_query_rs1.poke(3.U)
      dut.io.hazard_query_rs2.poke(4.U)
      dut.io.hazard_query_rd.poke(6.U)
      dut.io.hazard_result.expect(false.B)
    }
  }

  it should "Phase 1 - 场景 4: Scoreboard maintains per-warp isolation" in {
    test(new SchedulerWithScoreboard(numWarps = 4, numRegs = 32, numSlots = 3)) { dut =>
      // Initialize Warp 0 and Warp 1
      dut.io.allocReq.poke(true.B)
      dut.io.allocWarpId.poke(0.U)
      dut.io.dispatch.ready.poke(true.B)
      dut.io.ibEmptyMask.poke("b1100".U) // Warp 0 and Warp 1 have instructions
      dut.io.releaseReq.poke(false.B)
      dut.io.blkschActiveMask.poke("b0011".U)
      dut.io.blkschBarId.poke(0.U)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibHeadMicroOps(0).valid.poke(true.B)
      dut.io.ibHeadMicroOps(0).opcode.poke(0x01.U)
      dut.io.ibHeadMicroOps(0).rd.poke(5.U)
      dut.io.ibHeadMicroOps(0).rs1.poke(0.U)
      dut.io.ibHeadMicroOps(0).rs2.poke(0.U)

      dut.io.ibHeadMicroOps(1).valid.poke(true.B)
      dut.io.ibHeadMicroOps(1).opcode.poke(0x01.U)
      dut.io.ibHeadMicroOps(1).rd.poke(10.U)
      dut.io.ibHeadMicroOps(1).rs1.poke(0.U)
      dut.io.ibHeadMicroOps(1).rs2.poke(0.U)

      // Alloc R5 for Warp 0
      dut.clock.step(1)
      dut.io.allocReq.poke(false.B)
      dut.clock.step(1)

      // Alloc R10 for Warp 1
      dut.io.allocReq.poke(true.B)
      dut.io.allocWarpId.poke(1.U)
      dut.clock.step(1)
      dut.io.allocReq.poke(false.B)

      // Warp 0: R5 is busy, R10 is not
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(true.B)

      dut.io.hazard_query_rs1.poke(10.U)
      dut.io.hazard_result.expect(false.B)

      // Warp 1: R10 is busy, R5 is not
      dut.io.hazard_query_warp_id.poke(1.U)
      dut.io.hazard_query_rs1.poke(10.U)
      dut.io.hazard_result.expect(true.B)

      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(false.B)
    }
  }

  it should "Phase 1 - 场景 5: Scoreboard pending count reuse in hardware" in {
    test(new SchedulerWithScoreboard(numWarps = 4, numRegs = 32, numSlots = 3)) { dut =>
      // Initialize
      dut.io.allocReq.poke(true.B)
      dut.io.allocWarpId.poke(0.U)
      dut.io.dispatch.ready.poke(true.B)
      dut.io.ibEmptyMask.poke("b1110".U)
      dut.io.releaseReq.poke(false.B)
      dut.io.blkschActiveMask.poke("b0001".U)
      dut.io.blkschBarId.poke(0.U)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibHeadMicroOps(0).valid.poke(true.B)
      dut.io.ibHeadMicroOps(0).opcode.poke(0x01.U)
      dut.io.ibHeadMicroOps(0).rs1.poke(0.U)
      dut.io.ibHeadMicroOps(0).rs2.poke(0.U)

      // Allocate R5 twice (pending_count = 2)
      dut.io.ibHeadMicroOps(0).rd.poke(5.U)
      dut.clock.step(1)
      dut.io.allocReq.poke(false.B)
      dut.clock.step(1)

      dut.io.allocReq.poke(true.B)
      dut.io.ibHeadMicroOps(0).rd.poke(5.U) // Same register
      dut.clock.step(1)
      dut.io.allocReq.poke(false.B)

      // Hazard should still be present
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(true.B)

      // First release: pending_count goes to 1, hazard still present
      dut.io.releaseReq.poke(true.B)
      dut.io.releaseWarpId.poke(0.U)
      dut.io.releaseRegId.poke(5.U)
      dut.clock.step(1)
      dut.io.releaseReq.poke(false.B)

      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(true.B)

      // Second release: pending_count goes to 0, hazard cleared
      dut.io.releaseReq.poke(true.B)
      dut.io.releaseWarpId.poke(0.U)
      dut.io.releaseRegId.poke(5.U)
      dut.clock.step(1)
      dut.io.releaseReq.poke(false.B)

      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(false.B)
    }
  }

  it should "Phase 1 - 场景 6: Scoreboard zero register is ignored" in {
    test(new SchedulerWithScoreboard(numWarps = 4, numRegs = 32, numSlots = 3)) { dut =>
      // Initialize
      dut.io.allocReq.poke(true.B)
      dut.io.allocWarpId.poke(0.U)
      dut.io.dispatch.ready.poke(true.B)
      dut.io.ibEmptyMask.poke("b1110".U)
      dut.io.releaseReq.poke(false.B)
      dut.io.blkschActiveMask.poke("b0001".U)
      dut.io.blkschBarId.poke(0.U)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibHeadMicroOps(0).valid.poke(true.B)
      dut.io.ibHeadMicroOps(0).opcode.poke(0x01.U)
      dut.io.ibHeadMicroOps(0).rd.poke(0.U) // R0
      dut.io.ibHeadMicroOps(0).rs1.poke(0.U)
      dut.io.ibHeadMicroOps(0).rs2.poke(0.U)

      // Alloc R0 (should be no-op)
      dut.clock.step(1)
      dut.io.allocReq.poke(false.B)

      // Query R0 should never show hazard
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(0.U)
      dut.io.hazard_result.expect(false.B)

      // Slot should not be consumed
      dut.io.slot_full_mask.expect(0.U)
    }
  }
}
