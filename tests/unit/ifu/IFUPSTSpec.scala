package ifu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IFUPSTSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  behavior of "IFU_PST"

  it should "initialize a warp correctly" in {
    test(new IFU_PST).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.io.ctrl.warp_init_valid.poke(true.B)
      dut.io.ctrl.warp_init_id.poke(0.U)
      dut.io.ctrl.warp_init_pc.poke(0x100.U)
      
      dut.clock.step(1)
      
      dut.io.ctrl.warp_init_valid.poke(false.B)
      
      dut.io.entries(0).valid.expect(true.B)
      dut.io.entries(0).state.expect(WarpState.s_READY)
      dut.io.entries(0).pc_va.expect(0x100.U)
      dut.io.entries(0).credits.expect(IFUConfig.initialCredits.U)
    }
  }

  it should "transition states and consume/return credits" in {
    test(new IFU_PST).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // 1. Init
      dut.io.ctrl.warp_init_valid.poke(true.B)
      dut.io.ctrl.warp_init_id.poke(1.U)
      dut.io.ctrl.warp_init_pc.poke(0x200.U)
      dut.clock.step(1)
      dut.io.ctrl.warp_init_valid.poke(false.B)
      
      // 2. Grant by arbiter
      dut.io.ctrl.arbiter_grant_valid.poke(true.B)
      dut.io.ctrl.arbiter_grant_id.poke(1.U)
      dut.clock.step(1)
      dut.io.ctrl.arbiter_grant_valid.poke(false.B)
      
      dut.io.entries(1).state.expect(WarpState.s_FETCH_REQ)
      dut.io.entries(1).credits.expect(3.U)
      
      // 3. Cache Miss
      dut.io.ctrl.cache_rsp_valid.poke(true.B)
      dut.io.ctrl.cache_rsp_hit.poke(false.B)
      dut.io.ctrl.cache_rsp_warp_id.poke(1.U)
      dut.clock.step(1)
      dut.io.ctrl.cache_rsp_valid.poke(false.B)
      
      dut.io.entries(1).state.expect(WarpState.s_WAIT_CACHE)
      dut.io.entries(1).pc_va.expect(0x200.U) // PC unchanged
      
      // 4. Cache Wakeup
      dut.io.ctrl.cache_wakeup_valid.poke(true.B)
      dut.io.ctrl.cache_wakeup_warp_id.poke(1.U)
      dut.clock.step(1)
      dut.io.ctrl.cache_wakeup_valid.poke(false.B)
      
      dut.io.entries(1).state.expect(WarpState.s_READY)
      
      // 5. Grant again
      dut.io.ctrl.arbiter_grant_valid.poke(true.B)
      dut.io.ctrl.arbiter_grant_id.poke(1.U)
      dut.clock.step(1)
      dut.io.ctrl.arbiter_grant_valid.poke(false.B)
      
      // 6. Cache Hit
      dut.io.ctrl.cache_rsp_valid.poke(true.B)
      dut.io.ctrl.cache_rsp_hit.poke(true.B)
      dut.io.ctrl.cache_rsp_warp_id.poke(1.U)
      dut.clock.step(1)
      dut.io.ctrl.cache_rsp_valid.poke(false.B)
      
      dut.io.entries(1).state.expect(WarpState.s_READY)
      dut.io.entries(1).pc_va.expect(0x210.U) // 0x200 + 16
      dut.io.entries(1).credits.expect(2.U)
      
      // 7. Credit Return
      dut.io.ctrl.credit_return_valid.poke(true.B)
      dut.io.ctrl.credit_return_warp_id.poke(1.U)
      dut.clock.step(1)
      dut.io.ctrl.credit_return_valid.poke(false.B)
      
      dut.io.entries(1).credits.expect(3.U)
      
      // 8. Flush
      dut.io.ctrl.flush_valid.poke(true.B)
      dut.io.ctrl.flush_warp_id.poke(1.U)
      dut.io.ctrl.flush_target_pc.poke(0x500.U)
      dut.clock.step(1)
      dut.io.ctrl.flush_valid.poke(false.B)
      
      dut.io.entries(1).pc_va.expect(0x500.U)
      dut.io.entries(1).credits.expect(4.U)
      dut.io.entries(1).flush_gen_tag.expect(1.U)
      dut.io.entries(1).state.expect(WarpState.s_READY)
    }
  }

  it should "handle STALL_CREDIT correctly" in {
    test(new IFU_PST).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.io.ctrl.warp_init_valid.poke(true.B)
      dut.io.ctrl.warp_init_id.poke(2.U)
      dut.io.ctrl.warp_init_pc.poke(0x100.U)
      dut.clock.step(1)
      dut.io.ctrl.warp_init_valid.poke(false.B)
      
      // Consume all 4 credits
      for (i <- 0 until 4) {
        dut.io.ctrl.arbiter_grant_valid.poke(true.B)
        dut.io.ctrl.arbiter_grant_id.poke(2.U)
        dut.clock.step(1)
        dut.io.ctrl.arbiter_grant_valid.poke(false.B)
        
        dut.io.ctrl.cache_rsp_valid.poke(true.B)
        dut.io.ctrl.cache_rsp_hit.poke(true.B)
        dut.io.ctrl.cache_rsp_warp_id.poke(2.U)
        dut.clock.step(1)
        dut.io.ctrl.cache_rsp_valid.poke(false.B)
      }
      
      dut.io.entries(2).credits.expect(0.U)
      dut.io.entries(2).state.expect(WarpState.s_STALL_CREDIT)
      
      // Return 1 credit
      dut.io.ctrl.credit_return_valid.poke(true.B)
      dut.io.ctrl.credit_return_warp_id.poke(2.U)
      dut.clock.step(1)
      dut.io.ctrl.credit_return_valid.poke(false.B)
      
      dut.io.entries(2).credits.expect(1.U)
      dut.io.entries(2).state.expect(WarpState.s_READY)
    }
  }
}
