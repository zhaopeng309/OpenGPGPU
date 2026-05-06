package scheduler

import chisel3._
import chisel3.util._

object InstType {
  val ALU   = 1.U(3.W) // 001
  val TMA   = 2.U(3.W) // 010
  val WGMMA = 4.U(3.W) // 100
  val OTHER = 0.U(3.W)
}

object Opcode {
  val EXIT = 0xFF.U(8.W) // EXIT opcode identifier
}

case class WarpContext(
  valid: Bool,
  ready: Bool,
  stalled: Bool,
  waitKCache: Bool,
  ageCounter: UInt, // 8 bits
  instType: UInt,   // 3 bits
  destReg: UInt,    // 8 bits
  vgprBase: UInt,   // 12 bits
  activeMask: UInt, // 32 bits
  barId: UInt       // 4 bits
) {
  // Empty context for initialization
  def this() = this(
    false.B, false.B, false.B, false.B,
    0.U(8.W), InstType.OTHER, 0.U(8.W),
    0.U(12.W), 0.U(32.W), 0.U(4.W)
  )
}

object WarpContext {
  def empty: WarpContext = {
    WarpContext(
      valid = false.B,
      ready = false.B,
      stalled = false.B,
      waitKCache = false.B,
      ageCounter = 0.U(8.W),
      instType = InstType.OTHER,
      destReg = 0.U(8.W),
      vgprBase = 0.U(12.W),
      activeMask = 0.U(32.W),
      barId = 0.U(4.W)
    )
  }
}

// ──────────────────────────────────────────────
// Phase 1.1: SlotEntry and WarpScoreboard data structures
// for Slot-based Scoreboard architecture
// ──────────────────────────────────────────────

/** A single Scoreboard Slot entry (Chisel Bundle) */
class SlotEntry extends Bundle {
  val valid = Bool()
  val regId = UInt(8.W)
  val pendingCount = UInt(4.W)
}

/** Per-warp Scoreboard state (6 Slots) */
class WarpScoreboard(val numSlots: Int = 6) extends Bundle {
  val slots = Vec(numSlots, new SlotEntry())
}

// ──────────────────────────────────────────────
// Scoreboard IO Bundles
// ──────────────────────────────────────────────

class ScoreboardHazardReq extends Bundle {
  val warpId = UInt(5.W)
  val regId = UInt(8.W)
}

class ScoreboardReleaseReq extends Bundle {
  val warpId = UInt(5.W)
  val regId = UInt(8.W)
}

/** Scoreboard Alloc request bundle */
class ScoreboardAllocReq extends Bundle {
  val warpId = UInt(5.W)
  val regId = UInt(8.W)
}
