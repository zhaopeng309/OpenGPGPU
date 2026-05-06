package scheduler

import chisel3._
import chisel3.util._

/**
 * Scoreboard Chisel Module (Phase 1 — Simplified Query Interface)
 *
 * Wraps the pure Scala ScoreboardLogic into a Chisel Module with hardware
 * register arrays and IO ports for integration with the Warp Scheduler.
 *
 * Feature 4 from the Scoreboard Development Plan:
 * - alloc_req / alloc_warp_id / alloc_reg_id: Allocation request
 * - release_req / release_warp_id / release_reg_id: Release request
 * - hazard_query_warp_id / hazard_query_reg_ids: Hazard query
 * - hazard_result: Query result
 * - slot_full_mask: Per-warp Slot Full flag
 *
 * Phase 1 wakeup mechanism: Warp Scheduler actively queries hazard state
 * each cycle (no event-driven push).
 */
class Scoreboard(val numWarps: Int = 8, val numSlots: Int = 6) extends Module {
  val io = IO(new Bundle {
    // Allocation request (from Warp Scheduler on instruction issue)
    val alloc_req = Input(Bool())
    val alloc_warp_id = Input(UInt(log2Ceil(numWarps).W))
    val alloc_reg_id = Input(UInt(8.W))

    // Release request (from execution unit writeback)
    val release_req = Input(Bool())
    val release_warp_id = Input(UInt(log2Ceil(numWarps).W))
    val release_reg_id = Input(UInt(8.W))

    // Hazard query (from Warp Scheduler each cycle)
    val hazard_query_warp_id = Input(UInt(log2Ceil(numWarps).W))
    val hazard_query_rs1 = Input(UInt(8.W))
    val hazard_query_rs2 = Input(UInt(8.W))
    val hazard_query_rd = Input(UInt(8.W))
    val hazard_result = Output(Bool())

    // Slot Full mask (one bit per warp)
    val slot_full_mask = Output(UInt(numWarps.W))
  })

  // ──────────────────────────────────────────────
  // Register Array: Slot Matrix
  // Each slot stores: valid (1 bit), regId (8 bits), pendingCount (4 bits)
  // Total: numWarps × numSlots × (1 + 8 + 4) bits
  // ──────────────────────────────────────────────

  // Valid bits: true if slot is occupied
  val slotValid = RegInit(VecInit(Seq.fill(numWarps)(VecInit(Seq.fill(numSlots)(false.B)))))
  // Register ID stored in each slot
  val slotRegId = RegInit(VecInit(Seq.fill(numWarps)(VecInit(Seq.fill(numSlots)(0.U(8.W))))))
  // Pending count (4-bit, supports up to 15 outstanding refs per slot)
  val slotPending = RegInit(VecInit(Seq.fill(numWarps)(VecInit(Seq.fill(numSlots)(0.U(4.W))))))

  // ──────────────────────────────────────────────
  // Allocation Logic
  // ──────────────────────────────────────────────

  when(io.alloc_req) {
    val wId = io.alloc_warp_id
    val rId = io.alloc_reg_id

    // Skip allocation for zero register
    when(rId =/= 0.U) {
      // Check if register is already tracked in any slot of this warp
      val slotHit = Wire(Vec(numSlots, Bool()))
      val hitIdx = Wire(UInt(log2Ceil(numSlots).W))
      val anyHit = slotHit.reduce(_ || _)

      for (s <- 0 until numSlots) {
        slotHit(s) := slotValid(wId)(s) && slotRegId(wId)(s) === rId
      }

      // Priority encoder for hit index
      hitIdx := PriorityEncoder(slotHit)

      when(anyHit) {
        // Rule 1: Increment pending_count for existing slot
        // Saturate at 15 (0xF)
        when(slotPending(wId)(hitIdx) < 15.U) {
          slotPending(wId)(hitIdx) := slotPending(wId)(hitIdx) + 1.U
        }
      }.otherwise {
        // Rule 2: Find first free slot and allocate
        val freeSlotIdx = PriorityEncoder(VecInit(slotValid(wId).map(v => !v)))
        // Only allocate if there's a free slot (not Slot Full)
        when(freeSlotIdx < numSlots.U) {
          slotValid(wId)(freeSlotIdx) := true.B
          slotRegId(wId)(freeSlotIdx) := rId
          slotPending(wId)(freeSlotIdx) := 1.U
        }
        // If no free slot, allocation is silently dropped (Slot Full)
        // The Warp Scheduler should check slot_full_mask before issuing
      }
    }
  }

  // ──────────────────────────────────────────────
  // Release Logic
  // ──────────────────────────────────────────────

  when(io.release_req) {
    val wId = io.release_warp_id
    val rId = io.release_reg_id

    when(rId =/= 0.U) {
      // Find the slot tracking this register
      val slotMatch = Wire(Vec(numSlots, Bool()))
      val matchIdx = Wire(UInt(log2Ceil(numSlots).W))
      val anyMatch = slotMatch.reduce(_ || _)

      for (s <- 0 until numSlots) {
        slotMatch(s) := slotValid(wId)(s) && slotRegId(wId)(s) === rId
      }

      matchIdx := PriorityEncoder(slotMatch)

      when(anyMatch) {
        // Decrement pending_count
        when(slotPending(wId)(matchIdx) <= 1.U) {
          // Count reaches 0 → clear slot
          slotValid(wId)(matchIdx) := false.B
          slotRegId(wId)(matchIdx) := 0.U
          slotPending(wId)(matchIdx) := 0.U
        }.otherwise {
          slotPending(wId)(matchIdx) := slotPending(wId)(matchIdx) - 1.U
        }
      }
      // If register not found, silently ignore
    }
  }

  // ──────────────────────────────────────────────
  // Hazard Detection Logic (Combinational)
  // ──────────────────────────────────────────────

  val hazardWId = io.hazard_query_warp_id
  val queryRs1 = io.hazard_query_rs1
  val queryRs2 = io.hazard_query_rs2
  val queryRd = io.hazard_query_rd

  // Check if any valid slot matches the queried register
  // Zero register (0) is always ignored
  val rs1Hazard = Wire(Bool())
  val rs2Hazard = Wire(Bool())
  val rdHazard = Wire(Bool())

  rs1Hazard := (queryRs1 =/= 0.U) && (0 until numSlots).map { s =>
    slotValid(hazardWId)(s) && slotRegId(hazardWId)(s) === queryRs1
  }.reduce(_ || _)

  rs2Hazard := (queryRs2 =/= 0.U) && (0 until numSlots).map { s =>
    slotValid(hazardWId)(s) && slotRegId(hazardWId)(s) === queryRs2
  }.reduce(_ || _)

  rdHazard := (queryRd =/= 0.U) && (0 until numSlots).map { s =>
    slotValid(hazardWId)(s) && slotRegId(hazardWId)(s) === queryRd
  }.reduce(_ || _)

  io.hazard_result := rs1Hazard || rs2Hazard || rdHazard

  // ──────────────────────────────────────────────
  // Slot Full Detection (Combinational)
  // ──────────────────────────────────────────────

  val fullMask = Wire(Vec(numWarps, Bool()))
  for (w <- 0 until numWarps) {
    fullMask(w) := (0 until numSlots).map(s => slotValid(w)(s)).reduce(_ && _)
  }
  io.slot_full_mask := fullMask.asUInt
}
