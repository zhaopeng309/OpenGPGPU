package scheduler

/**
 * Pure Scala Scoreboard Logic Model (Slot-based)
 *
 * Implements Features 1-3 from the Scoreboard Development Plan:
 *   Feature 1: Slot-based dependency tracking with pending_count
 *   Feature 2: Hazard detection (RAW/WAW) with zero-register ignore
 *   Feature 3: Slot allocation/release management with Slot Full detection
 *
 * Design:
 * - 8 Warps × 6 Slots (Phase 1), expandable to 32 Warps
 * - Each slot tracks a register ID (8-bit) and pending_count (4-bit)
 * - Zero register (regId == 0) is always ignored for hazard detection
 */
class ScoreboardLogic(val numWarps: Int = 8, val numSlots: Int = 6) {

  /** A single scoreboard slot entry */
  case class SlotEntry(regId: Int, pendingCount: Int) {
    /** Decrement pending count; if it reaches 0, the slot is released */
    def release: Option[SlotEntry] = {
      val newCount = pendingCount - 1
      if (newCount <= 0) None else Some(this.copy(pendingCount = newCount))
    }
  }

  /** Slot matrix: Array[Warp][Slot] = Option[SlotEntry] */
  private val slots: Array[Array[Option[SlotEntry]]] =
    Array.fill(numWarps)(Array.fill(numSlots)(None))

  // ──────────────────────────────────────────────
  // Feature 1: Query Methods
  // ──────────────────────────────────────────────

  /** Get the raw slot state for a given warp (for inspection/debug) */
  def getSlots(warpId: Int): Seq[Option[SlotEntry]] = {
    require(warpId >= 0 && warpId < numWarps, s"warpId $warpId out of range [0, $numWarps)")
    slots(warpId).toSeq
  }

  /** Check if a specific register is busy (pending) for a given warp */
  def isRegBusy(warpId: Int, regId: Int): Boolean = {
    require(warpId >= 0 && warpId < numWarps, s"warpId $warpId out of range")
    if (regId == 0) return false // Zero register is never busy
    slots(warpId).exists {
      case Some(entry) => entry.regId == regId
      case None        => false
    }
  }

  /** Count how many slots are occupied for a given warp */
  def occupiedSlotCount(warpId: Int): Int = {
    require(warpId >= 0 && warpId < numWarps, s"warpId $warpId out of range")
    slots(warpId).count(_.isDefined)
  }

  /** Check if all slots for a warp are occupied (Slot Full) */
  def isSlotFull(warpId: Int): Boolean = {
    require(warpId >= 0 && warpId < numWarps, s"warpId $warpId out of range")
    slots(warpId).forall(_.isDefined)
  }

  // ──────────────────────────────────────────────
  // Feature 2: Hazard Detection
  // ──────────────────────────────────────────────

  /**
   * Check for hazards between a new instruction's source/destination registers
   * and the currently tracked registers in the scoreboard.
   *
   * @param warpId The warp issuing the instruction
   * @param srcRegs Source register IDs (for RAW detection)
   * @param dstReg Destination register ID (for WAW detection)
   * @return true if any hazard is detected (instruction should stall)
   */
  def checkHazard(warpId: Int, srcRegs: Seq[Int], dstReg: Int): Boolean = {
    require(warpId >= 0 && warpId < numWarps, s"warpId $warpId out of range")

    // Check all source registers for RAW hazards (zero register ignored)
    val rawHazard = srcRegs.exists { regId =>
      regId != 0 && isRegBusy(warpId, regId)
    }

    // Check destination register for WAW hazards (zero register ignored)
    val wawHazard = dstReg != 0 && isRegBusy(warpId, dstReg)

    rawHazard || wawHazard
  }

  /**
   * Simplified hazard check for single source register (common case).
   */
  def checkHazard(warpId: Int, rs1: Int, rs2: Int, rd: Int): Boolean = {
    checkHazard(warpId, Seq(rs1, rs2), rd)
  }

  // ──────────────────────────────────────────────
  // Feature 3: Slot Allocation & Release
  // ──────────────────────────────────────────────

  /**
   * Allocate a scoreboard slot for a destination register.
   *
   * Allocation rules:
   * 1. If the register is already tracked in a slot, increment pending_count.
   * 2. Otherwise, find the first free slot and set pending_count = 1.
   * 3. If all slots are full and no match found, return false (Slot Full).
   *
   * @param warpId The warp allocating the slot
   * @param dstReg The destination register being written
   * @return true if allocation succeeded, false if Slot Full
   */
  def allocate(warpId: Int, dstReg: Int): Boolean = {
    require(warpId >= 0 && warpId < numWarps, s"warpId $warpId out of range")
    require(dstReg >= 0 && dstReg < 256, s"dstReg $dstReg out of range [0, 256)")

    // Zero register does not need scoreboard tracking
    if (dstReg == 0) return true

    val warpSlots = slots(warpId)

    // Rule 1: Check if already tracked → increment pending_count
    warpSlots.zipWithIndex.foreach { case (slot, idx) =>
      slot match {
        case Some(entry) if entry.regId == dstReg =>
          warpSlots(idx) = Some(entry.copy(pendingCount = entry.pendingCount + 1))
          return true
        case _ => // continue
      }
    }

    // Rule 2: Find first free slot
    warpSlots.zipWithIndex.foreach { case (slot, idx) =>
      if (slot.isEmpty) {
        warpSlots(idx) = Some(SlotEntry(dstReg, pendingCount = 1))
        return true
      }
    }

    // Rule 3: All slots full → Slot Full
    false
  }

  /**
   * Release a scoreboard slot (decrement pending_count).
   *
   * Release rules:
   * 1. Find the slot tracking the given register.
   * 2. Decrement pending_count.
   * 3. If pending_count reaches 0, clear the slot (set to None).
   * 4. If the register is not tracked, silently ignore.
   *
   * @param warpId The warp releasing the slot
   * @param regId The register being written back
   */
  def release(warpId: Int, regId: Int): Unit = {
    require(warpId >= 0 && warpId < numWarps, s"warpId $warpId out of range")

    // Zero register is never tracked
    if (regId == 0) return

    val warpSlots = slots(warpId)

    warpSlots.zipWithIndex.foreach { case (slot, idx) =>
      slot match {
        case Some(entry) if entry.regId == regId =>
          warpSlots(idx) = entry.release
          return
        case _ => // continue
      }
    }
    // Silently ignore if register not found
  }

  /**
   * Reset all slots for all warps (for testing).
   */
  def reset(): Unit = {
    for (w <- 0 until numWarps) {
      for (s <- 0 until numSlots) {
        slots(w)(s) = None
      }
    }
  }

  /**
   * Get a summary of all tracked registers per warp (for debugging).
   */
  def summary: String = {
    val sb = new StringBuilder
    for (w <- 0 until numWarps) {
      val occupied = slots(w).zipWithIndex.flatMap {
        case (Some(entry), idx) => Some(s"  Slot[$idx]: regId=${entry.regId}, pending=${entry.pendingCount}")
        case (None, _)          => None
      }
      if (occupied.nonEmpty) {
        sb.append(s"Warp $w:\n")
        occupied.foreach(s => sb.append(s).append("\n"))
      }
    }
    if (sb.isEmpty) "All slots empty" else sb.toString().trim
  }
}
