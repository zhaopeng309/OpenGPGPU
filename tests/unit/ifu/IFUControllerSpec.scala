package ifu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IFUControllerSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  behavior of "IFU_Controller"

  it should "pass grant to icache request" in {
    test(new IFU_Controller).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Mock PST entries with Gen_Tag = 1 for warp 2
      for (i <- 0 until IFUConfig.numWarps) {
        dut.io.pst_entries(i).flush_gen_tag.poke(if (i == 2) 1.U else 0.U)
      }
      
      dut.io.grant_valid.poke(true.B)
      dut.io.grant_warp_id.poke(2.U)
      dut.io.grant_pc_va.poke(0x100.U)
      
      dut.io.icache_req.req_valid.expect(true.B)
      dut.io.icache_req.req_warp_id.expect(2.U)
      dut.io.icache_req.req_pc_va.expect(0x100.U)
      dut.io.icache_req.req_gen_tag.expect(1.U)
      
      dut.io.pst_ctrl.arbiter_grant_valid.expect(true.B)
      dut.io.pst_ctrl.arbiter_grant_id.expect(2.U)
    }
  }

  it should "handle cache hit and forward to decoder" in {
    test(new IFU_Controller).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.io.pst_entries(3).flush_gen_tag.poke(0.U)
      
      dut.io.icache_rsp.rsp_valid.poke(true.B)
      dut.io.icache_rsp.rsp_hit.poke(true.B)
      dut.io.icache_rsp.rsp_warp_id.poke(3.U)
      dut.io.icache_rsp.rsp_inst_data.poke("hABCD".U)
      dut.io.icache_rsp.rsp_gen_tag.poke(0.U)
      
      dut.io.decoder_out.valid.expect(true.B)
      dut.io.decoder_out.warp_id.expect(3.U)
      dut.io.decoder_out.inst_data.expect("hABCD".U)
      
      dut.io.pst_ctrl.cache_rsp_valid.expect(true.B)
      dut.io.pst_ctrl.cache_rsp_hit.expect(true.B)
    }
  }

  it should "drop stale cache responses due to gen tag mismatch" in {
    test(new IFU_Controller).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.io.pst_entries(4).flush_gen_tag.poke(2.U) // Current tag is 2
      
      // Old response comes with tag 1
      dut.io.icache_rsp.rsp_valid.poke(true.B)
      dut.io.icache_rsp.rsp_hit.poke(true.B)
      dut.io.icache_rsp.rsp_warp_id.poke(4.U)
      dut.io.icache_rsp.rsp_inst_data.poke("hFFFF".U)
      dut.io.icache_rsp.rsp_gen_tag.poke(1.U)
      
      dut.io.decoder_out.valid.expect(false.B) // Dropped
      dut.io.pst_ctrl.cache_rsp_valid.expect(false.B) // Do not update PST
    }
  }

  it should "forward wakeup correctly" in {
    test(new IFU_Controller).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.io.icache_wakeup.wakeup_valid.poke(true.B)
      dut.io.icache_wakeup.wakeup_warp_id.poke(5.U)
      
      dut.io.pst_ctrl.cache_wakeup_valid.expect(true.B)
      dut.io.pst_ctrl.cache_wakeup_warp_id.expect(5.U)
    }
  }
}
