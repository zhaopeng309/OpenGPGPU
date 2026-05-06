package scheduler

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Pure Scala Unit Tests for ScoreboardLogic
 *
 * Covers Features 1-3 from the Scoreboard Development Plan:
 *   Feature 1: Slot initialization and configuration validation
 *   Feature 2: RAW/WAW hazard detection (including zero-register ignore)
 *   Feature 3: Pending count reuse, Slot Full blocking, release and slot recycling
 */
class ScoreboardLogicSpec extends AnyFlatSpec with Matchers {

  // ──────────────────────────────────────────────
  // Feature 1: Slot Initialization & Configuration
  // ──────────────────────────────────────────────

  behavior of "ScoreboardLogic (Feature 1: Slot-based Model)"

  it should "initialize with 8 warps and 6 empty slots per warp" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    for (w <- 0 until 8) {
      val slots = sb.getSlots(w)
      slots should have length 6
      slots.foreach(_ shouldBe None)
      sb.occupiedSlotCount(w) shouldBe 0
      sb.isSlotFull(w) shouldBe false
    }
  }

  it should "support configurable warp and slot counts" in {
    val sb = new ScoreboardLogic(numWarps = 4, numSlots = 3)

    for (w <- 0 until 4) {
      sb.getSlots(w) should have length 3
    }
  }

  it should "report occupied slot count correctly after allocation" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 5)
    sb.occupiedSlotCount(0) shouldBe 1
    sb.occupiedSlotCount(1) shouldBe 0 // Other warp unaffected

    sb.allocate(0, 10)
    sb.occupiedSlotCount(0) shouldBe 2
  }

  // ──────────────────────────────────────────────
  // Feature 2: Hazard Detection
  // ──────────────────────────────────────────────

  behavior of "ScoreboardLogic (Feature 2: Hazard Detection)"

  it should "detect RAW hazard when source register is busy" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 5) // Warp 0 is writing to R5

    // Reading R5 should cause RAW hazard
    sb.checkHazard(0, Seq(5), 10) shouldBe true
    // Reading R3 should NOT cause hazard
    sb.checkHazard(0, Seq(3), 10) shouldBe false
  }

  it should "detect WAW hazard when destination register is busy" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 5) // Warp 0 is writing to R5

    // Writing R5 again should cause WAW hazard
    sb.checkHazard(0, Seq(1), 5) shouldBe true
    // Writing R3 should NOT cause hazard
    sb.checkHazard(0, Seq(1), 3) shouldBe false
  }

  it should "ignore zero register (R0) for hazard detection" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    // Even if R0 is somehow tracked (shouldn't happen normally)
    sb.allocate(0, 0) // Allocate R0 (should be a no-op)

    // Reading or writing R0 should never cause hazard
    sb.checkHazard(0, Seq(0), 5) shouldBe false
    sb.checkHazard(0, Seq(1), 0) shouldBe false
    sb.checkHazard(0, Seq(0), 0) shouldBe false
  }

  it should "detect hazard with multiple source registers" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 5) // R5 is busy
    sb.allocate(0, 7) // R7 is busy

    // Hazard on rs1
    sb.checkHazard(0, Seq(5, 3), 10) shouldBe true
    // Hazard on rs2
    sb.checkHazard(0, Seq(3, 7), 10) shouldBe true
    // No hazard
    sb.checkHazard(0, Seq(3, 4), 10) shouldBe false
  }

  it should "not detect hazard across different warps" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 5) // Warp 0 writing to R5

    // Warp 1 should NOT see hazard on R5
    sb.checkHazard(1, Seq(5), 10) shouldBe false
    sb.checkHazard(1, Seq(1), 5) shouldBe false
  }

  it should "detect hazard with simplified two-source API" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 5)

    sb.checkHazard(0, 5, 0, 10) shouldBe true  // RAW on rs1
    sb.checkHazard(0, 0, 5, 10) shouldBe true  // RAW on rs2
    sb.checkHazard(0, 0, 0, 5) shouldBe true   // WAW on rd
    sb.checkHazard(0, 3, 4, 10) shouldBe false // No hazard
  }

  // ──────────────────────────────────────────────
  // Feature 3: Slot Allocation & Release
  // ──────────────────────────────────────────────

  behavior of "ScoreboardLogic (Feature 3: Slot Management)"

  it should "allocate a slot and make the register busy" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 5) shouldBe true
    sb.isRegBusy(0, 5) shouldBe true
    sb.isRegBusy(0, 3) shouldBe false
  }

  it should "release a slot and clear the register busy state" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 5)
    sb.isRegBusy(0, 5) shouldBe true

    sb.release(0, 5)
    sb.isRegBusy(0, 5) shouldBe false
  }

  it should "support pending count reuse (multiple allocs to same register)" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    // Allocate R10 twice
    sb.allocate(0, 10) shouldBe true
    sb.occupiedSlotCount(0) shouldBe 1
    sb.getSlots(0).count(_.exists(_.regId == 10)) shouldBe 1

    sb.allocate(0, 10) shouldBe true // Same register, pending_count++
    sb.occupiedSlotCount(0) shouldBe 1 // Still only 1 slot

    // First release: pending_count goes from 2 to 1, slot still valid
    sb.release(0, 10)
    sb.isRegBusy(0, 10) shouldBe true

    // Second release: pending_count goes to 0, slot cleared
    sb.release(0, 10)
    sb.isRegBusy(0, 10) shouldBe false
    sb.occupiedSlotCount(0) shouldBe 0
  }

  it should "handle Slot Full condition correctly" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 3) // 3 slots for faster test

    // Fill all 3 slots
    sb.allocate(0, 10) shouldBe true
    sb.allocate(0, 11) shouldBe true
    sb.allocate(0, 12) shouldBe true

    sb.isSlotFull(0) shouldBe true

    // 4th allocation should fail (Slot Full)
    sb.allocate(0, 13) shouldBe false
    sb.isRegBusy(0, 13) shouldBe false

    // Other warp should still have free slots
    sb.isSlotFull(1) shouldBe false
    sb.allocate(1, 20) shouldBe true
  }

  it should "recycle slots after release" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 3)

    sb.allocate(0, 10)
    sb.allocate(0, 11)
    sb.allocate(0, 12)
    sb.isSlotFull(0) shouldBe true

    // Release one slot
    sb.release(0, 11)
    sb.isSlotFull(0) shouldBe false
    sb.occupiedSlotCount(0) shouldBe 2

    // Now we can allocate again
    sb.allocate(0, 13) shouldBe true
    sb.occupiedSlotCount(0) shouldBe 3
    sb.isSlotFull(0) shouldBe true
  }

  it should "silently ignore release of non-tracked register" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 5)
    sb.occupiedSlotCount(0) shouldBe 1

    // Release a register that was never allocated
    sb.release(0, 99)
    sb.occupiedSlotCount(0) shouldBe 1 // Unchanged
    sb.isRegBusy(0, 5) shouldBe true   // R5 still tracked
  }

  it should "not allocate zero register" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 0) shouldBe true // Returns true (no-op)
    sb.occupiedSlotCount(0) shouldBe 0 // No slot consumed
    sb.isRegBusy(0, 0) shouldBe false // R0 never busy
  }

  it should "not release zero register" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 5)
    sb.release(0, 0) // Should be no-op
    sb.isRegBusy(0, 5) shouldBe true // R5 still tracked
  }

  it should "support multiple warps independently" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    // Allocate different registers on different warps
    sb.allocate(0, 10)
    sb.allocate(1, 20)
    sb.allocate(2, 30)

    sb.isRegBusy(0, 10) shouldBe true
    sb.isRegBusy(0, 20) shouldBe false // Warp 1's register

    sb.isRegBusy(1, 20) shouldBe true
    sb.isRegBusy(1, 10) shouldBe false // Warp 0's register

    sb.isRegBusy(2, 30) shouldBe true

    // Release on one warp doesn't affect others
    sb.release(0, 10)
    sb.isRegBusy(0, 10) shouldBe false
    sb.isRegBusy(1, 20) shouldBe true
    sb.isRegBusy(2, 30) shouldBe true
  }

  it should "handle pending count saturation at 15" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 5) shouldBe true

    // Allocate 14 more times to reach pending_count = 15
    for (_ <- 0 until 14) {
      sb.allocate(0, 5) shouldBe true
    }

    // Get the slot entry to verify pending count
    val slot = sb.getSlots(0).find(_.exists(_.regId == 5)).flatten
    slot shouldBe defined
    slot.get.pendingCount shouldBe 15

    // One more allocation should still work (saturated at 15)
    sb.allocate(0, 5) shouldBe true
    slot.get.pendingCount shouldBe 15 // Still 15 (saturated)
  }

  it should "reset all slots correctly" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.allocate(0, 10)
    sb.allocate(1, 20)
    sb.allocate(2, 30)

    sb.reset()

    for (w <- 0 until 8) {
      sb.occupiedSlotCount(w) shouldBe 0
      sb.isSlotFull(w) shouldBe false
    }
  }

  it should "provide correct summary string" in {
    val sb = new ScoreboardLogic(numWarps = 8, numSlots = 6)

    sb.summary should include("All slots empty")

    sb.allocate(0, 10)
    sb.summary should include("Warp 0")
    sb.summary should include("regId=10")
    sb.summary should include("pending=1")
  }
}
