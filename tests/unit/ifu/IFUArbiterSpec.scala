package ifu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IFUArbiterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  behavior of "IFU_Arbiter"

  it should "arbitrate fairly using round-robin" in {
    test(new IFU_Arbiter).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Set all warps to eligible initially
      for (i <- 0 until IFUConfig.numWarps) {
        dut.io.pst_entries(i).valid.poke(true.B)
        dut.io.pst_entries(i).state.poke(WarpState.s_READY)
        dut.io.pst_entries(i).credits.poke(4.U)
        dut.io.pst_entries(i).pc_va.poke((i * 0x100).U)
      }
      
      dut.clock.step(1) // Pipeline bubble
      
      // Check RR sequence
      for (i <- 0 until IFUConfig.numWarps) {
        dut.io.grant_valid.expect(true.B)
        dut.io.grant_warp_id.expect(i.U)
        dut.io.grant_pc_va.expect((i * 0x100).U)
        dut.clock.step(1)
      }
      
      // Should wrap around
      dut.io.grant_valid.expect(true.B)
      dut.io.grant_warp_id.expect(0.U)
      dut.io.grant_pc_va.expect(0x0.U)
    }
  }

  it should "skip ineligible warps" in {
    test(new IFU_Arbiter).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Make only warp 1 and 5 eligible
      for (i <- 0 until IFUConfig.numWarps) {
        if (i == 1 || i == 5) {
          dut.io.pst_entries(i).valid.poke(true.B)
          dut.io.pst_entries(i).state.poke(WarpState.s_READY)
          dut.io.pst_entries(i).credits.poke(4.U)
        } else {
          dut.io.pst_entries(i).valid.poke(false.B)
        }
      }
      
      dut.clock.step(1) // Pipeline register
      
      dut.io.grant_valid.expect(true.B)
      dut.io.grant_warp_id.expect(1.U)
      
      dut.clock.step(1)
      
      dut.io.grant_valid.expect(true.B)
      dut.io.grant_warp_id.expect(5.U)
      
      dut.clock.step(1)
      
      dut.io.grant_valid.expect(true.B)
      dut.io.grant_warp_id.expect(1.U) // Wrap around to 1
    }
  }

  it should "handle no eligible warps" in {
    test(new IFU_Arbiter).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Set all warps to ineligible
      for (i <- 0 until IFUConfig.numWarps) {
        dut.io.pst_entries(i).valid.poke(false.B)
      }
      
      dut.clock.step(1)
      dut.io.grant_valid.expect(false.B)
    }
  }
}
