package ifu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IFUSystemSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  behavior of "IFU System (Integration)"

  it should "handle a full fetch cycle from init to decode" in {
    test(new IFU).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // 1. Block Scheduler Inits Warp 0
      dut.io.warp_init_valid.poke(true.B)
      dut.io.warp_init_id.poke(0.U)
      dut.io.warp_init_pc.poke(0x100.U)
      dut.clock.step(1)
      dut.io.warp_init_valid.poke(false.B)
      
      // 2. Arbiter takes 1 cycle (pipeline) to grant
      dut.clock.step(1)
      
      // 3. Controller sends Request to L0 I-Cache
      dut.io.icache_req.req_valid.expect(true.B)
      dut.io.icache_req.req_warp_id.expect(0.U)
      dut.io.icache_req.req_pc_va.expect(0x100.U)
      dut.io.icache_req.req_gen_tag.expect(0.U)
      
      // 4. Cache Returns Hit in next cycle
      dut.io.icache_rsp.rsp_valid.poke(true.B)
      dut.io.icache_rsp.rsp_hit.poke(true.B)
      dut.io.icache_rsp.rsp_warp_id.poke(0.U)
      dut.io.icache_rsp.rsp_inst_data.poke("h12345678".U)
      dut.io.icache_rsp.rsp_gen_tag.poke(0.U)
      
      // 5. Decoder should see the data
      dut.io.decoder_out.valid.expect(true.B)
      dut.io.decoder_out.warp_id.expect(0.U)
      dut.io.decoder_out.inst_data.expect("h12345678".U)
      dut.clock.step(1)
      dut.io.icache_rsp.rsp_valid.poke(false.B)
    }
  }

  it should "handle branch flush and generation tag drop (Feature 4.3)" in {
    test(new IFU).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Setup: Warp 1 is fetching 0x200
      dut.io.warp_init_valid.poke(true.B)
      dut.io.warp_init_id.poke(1.U)
      dut.io.warp_init_pc.poke(0x200.U)
      dut.clock.step(1)
      dut.io.warp_init_valid.poke(false.B)
      
      dut.clock.step(1) // Arbiter grant
      
      // Request goes out for 0x200 with tag 0
      dut.io.icache_req.req_valid.expect(true.B)
      dut.io.icache_req.req_pc_va.expect(0x200.U)
      dut.io.icache_req.req_gen_tag.expect(0.U)
      
      // Cache MISS
      dut.io.icache_rsp.rsp_valid.poke(true.B)
      dut.io.icache_rsp.rsp_hit.poke(false.B)
      dut.io.icache_rsp.rsp_warp_id.poke(1.U)
      dut.io.icache_rsp.rsp_gen_tag.poke(0.U)
      dut.clock.step(1)
      dut.io.icache_rsp.rsp_valid.poke(false.B)
      
      // At this point, Warp 1 is WAIT_CACHE
      
      // FLUSH happens from BRU! Target 0x500
      dut.io.flush_valid.poke(true.B)
      dut.io.flush_warp_id.poke(1.U)
      dut.io.flush_target_pc.poke(0x500.U)
      dut.clock.step(1)
      dut.io.flush_valid.poke(false.B)
      
      // Warp 1 is back to READY. In the next cycle Arbiter should grant 0x500
      dut.clock.step(1) // Arbiter pipeline
      dut.io.icache_req.req_valid.expect(true.B)
      dut.io.icache_req.req_pc_va.expect(0x500.U)
      dut.io.icache_req.req_gen_tag.expect(1.U) // Tag incremented!
      
      // Now, L0 Cache finally wakes up for the OLD miss (Tag = 0)
      dut.io.icache_wakeup.wakeup_valid.poke(true.B)
      dut.io.icache_wakeup.wakeup_warp_id.poke(1.U)
      dut.clock.step(1)
      dut.io.icache_wakeup.wakeup_valid.poke(false.B)
      
      // And the old response arrives
      dut.io.icache_rsp.rsp_valid.poke(true.B)
      dut.io.icache_rsp.rsp_hit.poke(true.B)
      dut.io.icache_rsp.rsp_warp_id.poke(1.U)
      dut.io.icache_rsp.rsp_inst_data.poke("hBADBAD".U)
      dut.io.icache_rsp.rsp_gen_tag.poke(0.U) // Old tag!
      
      // The controller MUST drop it!
      dut.io.decoder_out.valid.expect(false.B)
      dut.clock.step(1)
      dut.io.icache_rsp.rsp_valid.poke(false.B)
      
      // Now the NEW response arrives
      dut.io.icache_rsp.rsp_valid.poke(true.B)
      dut.io.icache_rsp.rsp_hit.poke(true.B)
      dut.io.icache_rsp.rsp_warp_id.poke(1.U)
      dut.io.icache_rsp.rsp_inst_data.poke("hABCD".U)
      dut.io.icache_rsp.rsp_gen_tag.poke(1.U) // Correct tag
      
      dut.io.decoder_out.valid.expect(true.B)
      dut.io.decoder_out.inst_data.expect("hABCD".U)
      dut.clock.step(1)
    }
  }

  it should "terminate warp on S_ENDPGM" in {
    test(new IFU).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.io.warp_init_valid.poke(true.B)
      dut.io.warp_init_id.poke(2.U)
      dut.io.warp_init_pc.poke(0x100.U)
      dut.clock.step(1)
      dut.io.warp_init_valid.poke(false.B)
      
      dut.clock.step(1) // Arbiter
      dut.io.icache_req.req_valid.expect(true.B)
      
      // Send Warp Exit
      dut.io.warp_exit_valid.poke(true.B)
      dut.io.warp_exit_id.poke(2.U)
      dut.clock.step(1)
      dut.io.warp_exit_valid.poke(false.B)
      
      dut.clock.step(1)
      dut.io.icache_req.req_valid.expect(false.B) // No more requests
    }
  }
}
