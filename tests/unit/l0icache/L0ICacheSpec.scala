package l0icache

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class L0ICacheSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "L0ICache"

  it should "Feature 1: Basic Fetch Hit" in {
    test(new L0ICache).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      // Initialize inputs
      c.io.ifu.req_valid.poke(false.B)
      c.io.roc_resp.fill_valid.poke(false.B)
      c.io.bs_req.preload_valid.poke(false.B)
      c.clock.step(1)

      // Step 1: Pre-inject data via ROC Fill
      val testAddr = "h1000".U(48.W)
      val testData = "hDEADBEEF_CAFEBABE_12345678_87654321".U(128.W)
      val fullLineData = "hDEADBEEF_CAFEBABE_12345678_87654321_DEADBEEF_CAFEBABE_12345678_87654321_DEADBEEF_CAFEBABE_12345678_87654321_DEADBEEF_CAFEBABE_12345678_87654321".U(512.W)

      c.io.roc_resp.fill_valid.poke(true.B)
      c.io.roc_resp.fill_addr.poke(testAddr)
      c.io.roc_resp.fill_data.poke(fullLineData)
      c.clock.step(1)
      c.io.roc_resp.fill_valid.poke(false.B)
      c.clock.step(1) // Data is now in Cache Array

      // Step 2: IFU Fetch Hit
      c.io.ifu.req_valid.poke(true.B)
      c.io.ifu.req_addr.poke(testAddr)
      c.io.ifu.req_warp_id.poke(0.U)
      c.io.ifu.req_gen_tag.poke(1.U)

      // Next cycle
      c.clock.step(1)
      c.io.ifu.req_valid.poke(false.B)

      // Assert Hit
      c.io.ifu_resp.hit_valid.expect(true.B)
      c.io.ifu_resp.hit_warp_id.expect(0.U)
      c.io.ifu_resp.hit_gen_tag.expect(1.U)
      c.io.ifu_resp.hit_data.expect(testData)
    }
  }

  it should "Feature 2: Cache Miss & Allocate" in {
    test(new L0ICache) { c =>
      c.io.ifu.req_valid.poke(false.B)
      c.io.roc_resp.fill_valid.poke(false.B)
      c.io.bs_req.preload_valid.poke(false.B)
      c.clock.step(1)

      val missAddr = "h2000".U(48.W)

      c.io.ifu.req_valid.poke(true.B)
      c.io.ifu.req_addr.poke(missAddr)
      c.io.ifu.req_warp_id.poke(2.U)
      c.io.ifu.req_gen_tag.poke(0.U)
      
      c.clock.step(1)
      c.io.ifu.req_valid.poke(false.B)

      // IFU should get miss_valid
      c.io.ifu_resp.miss_valid.expect(true.B)
      c.io.ifu_resp.miss_warp_id.expect(2.U)

      // ROC should get request
      c.io.roc_req.req_valid.expect(true.B)
      c.io.roc_req.req_addr.expect(missAddr)
    }
  }

  it should "Feature 3: MSHR Backpressure" in {
    test(new L0ICache) { c =>
      c.io.ifu.req_valid.poke(false.B)
      c.io.roc_resp.fill_valid.poke(false.B)
      c.io.bs_req.preload_valid.poke(false.B)
      c.clock.step(1)

      // Fill 4 MSHR entries
      for (i <- 0 until 4) {
        c.io.ifu.req_valid.poke(true.B)
        c.io.ifu.req_addr.poke(((i + 1) * 0x1000).U(48.W))
        c.io.ifu.req_warp_id.poke(i.U)
        c.clock.step(1)
      }
      
      c.io.ifu.req_valid.poke(false.B)
      
      // Now MSHR should be full
      c.io.ifu_resp.ready.expect(false.B)
    }
  }

  it should "Feature 4: MSHR Request Merging" in {
    test(new L0ICache) { c =>
      c.io.ifu.req_valid.poke(false.B)
      c.io.roc_resp.fill_valid.poke(false.B)
      c.io.bs_req.preload_valid.poke(false.B)
      c.clock.step(1)

      val sharedAddr = "h3000".U(48.W)

      // Warp 1 misses on sharedAddr
      c.io.ifu.req_valid.poke(true.B)
      c.io.ifu.req_addr.poke(sharedAddr)
      c.io.ifu.req_warp_id.poke(1.U)
      c.clock.step(1)
      c.io.roc_req.req_valid.expect(true.B) // First miss sends to ROC

      // Warp 3 misses on sharedAddr
      c.io.ifu.req_addr.poke(sharedAddr)
      c.io.ifu.req_warp_id.poke(3.U)
      c.clock.step(1)
      c.io.roc_req.req_valid.expect(false.B) // Second miss merged, no new ROC req
      
      c.io.ifu.req_valid.poke(false.B)
      
      // Provide fill and check wakeup mask
      c.io.roc_resp.fill_valid.poke(true.B)
      c.io.roc_resp.fill_addr.poke(sharedAddr)
      c.io.roc_resp.fill_data.poke("hFFFF".U)
      
      // Wakeup mask should contain Warp 1 (bit 1) and Warp 3 (bit 3) -> 0x0A
      c.io.ifu_resp.wakeup_valid.expect(true.B)
      c.io.ifu_resp.wakeup_warp_mask.expect(10.U) // (1 << 1) | (1 << 3) = 2 + 8 = 10

      c.clock.step(1)
      c.io.roc_resp.fill_valid.poke(false.B)
    }
  }

  it should "Feature 5: Async Wakeup & Fill" in {
    test(new L0ICache) { c =>
      c.io.ifu.req_valid.poke(false.B)
      c.io.roc_resp.fill_valid.poke(false.B)
      c.io.bs_req.preload_valid.poke(false.B)
      c.clock.step(1)

      val missAddr = "h4000".U(48.W)

      // Request causing miss
      c.io.ifu.req_valid.poke(true.B)
      c.io.ifu.req_addr.poke(missAddr)
      c.io.ifu.req_warp_id.poke(5.U)
      c.clock.step(1)
      c.io.ifu.req_valid.poke(false.B)
      c.clock.step(2) // Wait a bit

      // Fill
      c.io.roc_resp.fill_valid.poke(true.B)
      c.io.roc_resp.fill_addr.poke(missAddr)
      c.io.roc_resp.fill_data.poke("h1234".U)

      // Check Wakeup
      c.io.ifu_resp.wakeup_valid.expect(true.B)
      c.io.ifu_resp.wakeup_warp_mask.expect((1 << 5).U)

      c.clock.step(1)
      c.io.roc_resp.fill_valid.poke(false.B)
    }
  }

  it should "Feature 6: Generation Tag / Flush Collision" in {
    test(new L0ICache) { c =>
      c.io.ifu.req_valid.poke(false.B)
      c.io.roc_resp.fill_valid.poke(false.B)
      c.io.bs_req.preload_valid.poke(false.B)
      c.clock.step(1)

      val missAddr = "h5000".U(48.W)

      c.io.ifu.req_valid.poke(true.B)
      c.io.ifu.req_addr.poke(missAddr)
      c.io.ifu.req_warp_id.poke(2.U)
      c.io.ifu.req_gen_tag.poke(2.U) // Gen_Tag = 2
      c.clock.step(1)
      c.io.ifu.req_valid.poke(false.B)

      c.io.roc_resp.fill_valid.poke(true.B)
      c.io.roc_resp.fill_addr.poke(missAddr)
      c.io.roc_resp.fill_data.poke("h0".U)

      c.io.ifu_resp.wakeup_valid.expect(true.B)
      c.io.ifu_resp.wakeup_gen_tag.expect(2.U)

      c.clock.step(1)
      c.io.roc_resp.fill_valid.poke(false.B)
    }
  }

  it should "Feature 7: Block Scheduler Preload" in {
    test(new L0ICache) { c =>
      c.io.ifu.req_valid.poke(false.B)
      c.io.roc_resp.fill_valid.poke(false.B)
      c.io.bs_req.preload_valid.poke(false.B)
      c.clock.step(1)

      val preloadAddr = "h6000".U(48.W)

      // Send Preload Probe
      c.io.bs_req.preload_valid.poke(true.B)
      c.io.bs_req.preload_addr.poke(preloadAddr)
      c.clock.step(1)
      c.io.bs_req.preload_valid.poke(false.B)

      // ROC should get preload request
      c.io.roc_req.req_valid.expect(true.B)
      c.io.roc_req.req_is_preload.expect(true.B)
      
      c.clock.step(1)

      // Fill returns
      c.io.roc_resp.fill_valid.poke(true.B)
      c.io.roc_resp.fill_addr.poke(preloadAddr)
      c.io.roc_resp.fill_data.poke("hBEEF".U)

      // Should output Preload ACK, but NO Wakeup
      c.io.bs_resp.preload_ack.expect(true.B)
      c.io.ifu_resp.wakeup_valid.expect(false.B)

      c.clock.step(1)
      c.io.roc_resp.fill_valid.poke(false.B)
    }
  }
}
