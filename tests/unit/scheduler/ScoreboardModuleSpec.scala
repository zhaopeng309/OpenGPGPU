package scheduler

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/**
 * Chisel Hardware Timing Tests for Scoreboard
 *
 * Covers Feature 4 from the Scoreboard Development Plan:
 * - Allocation and query timing correctness
 * - Release and query timing correctness
 * - Slot Full signal timing correctness
 * - Multi-warp concurrent operation isolation
 * - Same-cycle alloc + release bypass behavior
 */
class ScoreboardModuleSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Scoreboard"

  // Helper to create a test with default config
  def withScoreboard(testFn: Scoreboard => Unit): Unit = {
    test(new Scoreboard(numWarps = 4, numSlots = 3)) { dut => testFn(dut) }
  }

  it should "Feature 4 - 场景 1: allocate a register and detect hazard on next cycle" in {
    withScoreboard { dut =>
      // Initial state: no hazard
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_query_rs2.poke(0.U)
      dut.io.hazard_query_rd.poke(0.U)
      dut.io.hazard_result.expect(false.B)

      // Allocate R5 for Warp 0
      dut.io.alloc_req.poke(true.B)
      dut.io.alloc_warp_id.poke(0.U)
      dut.io.alloc_reg_id.poke(5.U)
      dut.clock.step(1)
      dut.io.alloc_req.poke(false.B)

      // After allocation, query R5 should show hazard
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(true.B)

      // Query R3 should NOT show hazard
      dut.io.hazard_query_rs1.poke(3.U)
      dut.io.hazard_result.expect(false.B)
    }
  }

  it should "Feature 4 - 场景 2: release a register and clear hazard on next cycle" in {
    withScoreboard { dut =>
      // Allocate R5
      dut.io.alloc_req.poke(true.B)
      dut.io.alloc_warp_id.poke(0.U)
      dut.io.alloc_reg_id.poke(5.U)
      dut.clock.step(1)
      dut.io.alloc_req.poke(false.B)

      // Verify hazard
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(true.B)

      // Release R5
      dut.io.release_req.poke(true.B)
      dut.io.release_warp_id.poke(0.U)
      dut.io.release_reg_id.poke(5.U)
      dut.clock.step(1)
      dut.io.release_req.poke(false.B)

      // After release, hazard should be cleared
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(false.B)
    }
  }

  it should "Feature 4 - 场景 3: Slot Full signal works correctly" in {
    test(new Scoreboard(numWarps = 4, numSlots = 3)) { dut =>
      // Initially no slot full
      dut.io.slot_full_mask.expect(0.U)

      // Fill all 3 slots for Warp 0
      for (regId <- Seq(10, 11, 12)) {
        dut.io.alloc_req.poke(true.B)
        dut.io.alloc_warp_id.poke(0.U)
        dut.io.alloc_reg_id.poke(regId.U)
        dut.clock.step(1)
        dut.io.alloc_req.poke(false.B)
      }

      // Warp 0 should be Slot Full
      val fullMask = dut.io.slot_full_mask.peek().litValue
      assert((fullMask & 0x1) != 0, s"Warp 0 should be Slot Full, mask = 0x${fullMask.toString(16)}")

      // Other warps should NOT be Slot Full
      assert((fullMask & 0xE) == 0, s"Other warps should not be Slot Full, mask = 0x${fullMask.toString(16)}")

      // Release one slot from Warp 0
      dut.io.release_req.poke(true.B)
      dut.io.release_warp_id.poke(0.U)
      dut.io.release_reg_id.poke(10.U)
      dut.clock.step(1)
      dut.io.release_req.poke(false.B)

      // Warp 0 should no longer be Slot Full
      val fullMask2 = dut.io.slot_full_mask.peek().litValue
      assert((fullMask2 & 0x1) == 0, s"Warp 0 should no longer be Slot Full, mask = 0x${fullMask2.toString(16)}")
    }
  }

  it should "Feature 4 - 场景 4: multi-warp concurrent operation isolation" in {
    withScoreboard { dut =>
      // Allocate R5 for Warp 0 and R10 for Warp 1 simultaneously
      dut.io.alloc_req.poke(true.B)
      dut.io.alloc_warp_id.poke(0.U)
      dut.io.alloc_reg_id.poke(5.U)
      dut.clock.step(1)

      dut.io.alloc_warp_id.poke(1.U)
      dut.io.alloc_reg_id.poke(10.U)
      dut.clock.step(1)
      dut.io.alloc_req.poke(false.B)

      // Warp 0 should see hazard on R5
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(true.B)

      // Warp 1 should see hazard on R10
      dut.io.hazard_query_warp_id.poke(1.U)
      dut.io.hazard_query_rs1.poke(10.U)
      dut.io.hazard_result.expect(true.B)

      // Warp 0 should NOT see hazard on R10 (different warp)
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(10.U)
      dut.io.hazard_result.expect(false.B)

      // Warp 1 should NOT see hazard on R5 (different warp)
      dut.io.hazard_query_warp_id.poke(1.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(false.B)
    }
  }

  it should "Feature 4 - 场景 5: zero register is ignored for allocation and hazard" in {
    withScoreboard { dut =>
      // Allocate R0 (should be no-op)
      dut.io.alloc_req.poke(true.B)
      dut.io.alloc_warp_id.poke(0.U)
      dut.io.alloc_reg_id.poke(0.U)
      dut.clock.step(1)
      dut.io.alloc_req.poke(false.B)

      // Query R0 should never show hazard
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(0.U)
      dut.io.hazard_result.expect(false.B)

      // Slot should not be consumed
      dut.io.slot_full_mask.expect(0.U)
    }
  }

  it should "Feature 4 - 场景 6: pending count reuse in hardware" in {
    withScoreboard { dut =>
      // Allocate R5 twice (pending_count = 2)
      dut.io.alloc_req.poke(true.B)
      dut.io.alloc_warp_id.poke(0.U)
      dut.io.alloc_reg_id.poke(5.U)
      dut.clock.step(1)

      dut.io.alloc_reg_id.poke(5.U) // Same register
      dut.clock.step(1)
      dut.io.alloc_req.poke(false.B)

      // Hazard should still be present
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(true.B)

      // First release: pending_count goes to 1, hazard still present
      dut.io.release_req.poke(true.B)
      dut.io.release_warp_id.poke(0.U)
      dut.io.release_reg_id.poke(5.U)
      dut.clock.step(1)
      dut.io.release_req.poke(false.B)

      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(true.B)

      // Second release: pending_count goes to 0, hazard cleared
      dut.io.release_req.poke(true.B)
      dut.io.release_warp_id.poke(0.U)
      dut.io.release_reg_id.poke(5.U)
      dut.clock.step(1)
      dut.io.release_req.poke(false.B)

      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(false.B)
    }
  }

  it should "Feature 4 - 场景 7: RAW and WAW hazard detection" in {
    withScoreboard { dut =>
      // Allocate R5
      dut.io.alloc_req.poke(true.B)
      dut.io.alloc_warp_id.poke(0.U)
      dut.io.alloc_reg_id.poke(5.U)
      dut.clock.step(1)
      dut.io.alloc_req.poke(false.B)

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

  it should "Feature 4 - 场景 8: release non-existent register is silently ignored" in {
    withScoreboard { dut =>
      // Allocate R5
      dut.io.alloc_req.poke(true.B)
      dut.io.alloc_warp_id.poke(0.U)
      dut.io.alloc_reg_id.poke(5.U)
      dut.clock.step(1)
      dut.io.alloc_req.poke(false.B)

      // Release non-existent R99
      dut.io.release_req.poke(true.B)
      dut.io.release_warp_id.poke(0.U)
      dut.io.release_reg_id.poke(99.U)
      dut.clock.step(1)
      dut.io.release_req.poke(false.B)

      // R5 should still be busy
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(true.B)
    }
  }

  it should "Feature 4 - 场景 9: same-cycle alloc and release bypass" in {
    withScoreboard { dut =>
      // Allocate R5
      dut.io.alloc_req.poke(true.B)
      dut.io.alloc_warp_id.poke(0.U)
      dut.io.alloc_reg_id.poke(5.U)
      dut.clock.step(1)
      dut.io.alloc_req.poke(false.B)

      // In the same cycle: release R5 AND query R5
      // Release happens at clock edge, query is combinational
      // So before release takes effect, hazard should still be true
      dut.io.release_req.poke(true.B)
      dut.io.release_warp_id.poke(0.U)
      dut.io.release_reg_id.poke(5.U)
      dut.io.hazard_query_warp_id.poke(0.U)
      dut.io.hazard_query_rs1.poke(5.U)
      dut.io.hazard_result.expect(true.B) // Still hazard (release not yet committed)

      dut.clock.step(1)
      dut.io.release_req.poke(false.B)

      // After release committed, hazard cleared
      dut.io.hazard_result.expect(false.B)
    }
  }
}
