package l0kcache

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class L0KCacheSpec extends AnyFlatSpec with ChiselScalatestTester {

  behavior of "L0KCache"

  it should "Feature 1: Dynamic OC Read Hit" in {
    test(new L0KCache).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Initialize inputs
      dut.io.oc_read.valid.poke(false.B)
      dut.io.probe_req.valid.poke(false.B)
      dut.io.roc_fill.valid.poke(false.B)
      dut.clock.step(1)

      // First, simulate a miss and fill to put data into the cache
      dut.io.oc_read.valid.poke(true.B)
      dut.io.oc_read.dynamic_addr.poke(0x1000.U)
      dut.io.oc_read.warp_id.poke(1.U)
      // Expect stall backpressure and roc_req combinatorially
      dut.io.stall_backpressure.expect(true.B)
      dut.io.roc_req.valid.expect(true.B)
      dut.io.roc_req.addr.expect(0x1000.U)

      dut.clock.step(1)
      
      dut.io.oc_read.valid.poke(false.B)
      dut.clock.step(2)

      // Fill data
      dut.io.roc_fill.valid.poke(true.B)
      dut.io.roc_fill.addr.poke(0x1000.U)
      // We will fill 512 bits with a known pattern. 
      // A simple 128-bit value repeated 4 times: 0xDeadBeef...
      val testData = "h_DEADBEEF_00000000_11111111_22222222_DEADBEEF_00000000_11111111_22222222_DEADBEEF_00000000_11111111_22222222_DEADBEEF_00000000_11111111_22222222".U(512.W)
      dut.io.roc_fill.data.poke(testData)
      dut.clock.step(1)
      dut.io.roc_fill.valid.poke(false.B)
      dut.clock.step(1)

      // Now do a dynamic read hit
      dut.io.oc_read.valid.poke(true.B)
      dut.io.oc_read.dynamic_addr.poke(0x1010.U) // Word offset 1 in the 64-byte line
      dut.io.oc_read.warp_id.poke(1.U)
      dut.io.stall_backpressure.expect(false.B) // Hit! No backpressure
      dut.clock.step(1)
      
      dut.io.oc_read.valid.poke(false.B)
      dut.io.k_data.valid.expect(true.B)
      // The expected data from word offset 1
      dut.io.k_data.data.expect("h_DEADBEEF_00000000_11111111_22222222".U(128.W))
      dut.clock.step(1)
    }
  }

  it should "Feature 2: Dynamic Read Miss & Backpressure" in {
    test(new L0KCache) { dut =>
      dut.io.oc_read.valid.poke(true.B)
      dut.io.oc_read.dynamic_addr.poke(0x2000.U)
      dut.io.oc_read.warp_id.poke(2.U)
      
      dut.io.stall_backpressure.expect(true.B)
      dut.io.roc_req.valid.expect(true.B)
      dut.io.roc_req.addr.expect(0x2000.U)
      dut.clock.step(1)
    }
  }

  it should "Feature 3: Decoder Early Probe Hit" in {
    test(new L0KCache) { dut =>
      // Pre-fill
      dut.io.roc_fill.valid.poke(true.B)
      dut.io.roc_fill.addr.poke(0x3000.U)
      dut.io.roc_fill.data.poke(0.U)
      dut.clock.step(1)
      dut.io.roc_fill.valid.poke(false.B)
      dut.clock.step(1)

      // Probe hit
      dut.io.probe_req.valid.poke(true.B)
      dut.io.probe_req.static_addr.poke(0x3000.U)
      dut.io.probe_req.warp_id.poke(3.U)
      
      dut.io.roc_req.valid.expect(false.B)
      dut.io.stall_backpressure.expect(false.B)
      dut.clock.step(1)
      
      dut.io.probe_req.valid.poke(false.B)
      dut.io.ack_wakeup.valid.expect(false.B) // No wakeup needed for hit
    }
  }

  it should "Feature 4: Probe Miss & Async Wakeup" in {
    test(new L0KCache) { dut =>
      // Probe miss
      dut.io.probe_req.valid.poke(true.B)
      dut.io.probe_req.static_addr.poke(0x4000.U)
      dut.io.probe_req.warp_id.poke(3.U)
      
      dut.io.roc_req.valid.expect(true.B)
      dut.clock.step(1)
      dut.io.probe_req.valid.poke(false.B)
      dut.clock.step(2)
      
      // Fill
      dut.io.roc_fill.valid.poke(true.B)
      dut.io.roc_fill.addr.poke(0x4000.U)
      dut.io.roc_fill.data.poke(0.U)
      dut.clock.step(1)
      dut.io.roc_fill.valid.poke(false.B)
      
      // Check wakeup
      dut.io.ack_wakeup.valid.expect(true.B)
      dut.io.ack_wakeup.warp_id.expect(3.U)
      dut.clock.step(1)
    }
  }

  it should "Feature 5: MSHR Hybrid Merging" in {
    test(new L0KCache) { dut =>
      // Probe miss Warp 0
      dut.io.probe_req.valid.poke(true.B)
      dut.io.probe_req.static_addr.poke(0x5000.U)
      dut.io.probe_req.warp_id.poke(0.U)
      dut.io.roc_req.valid.expect(true.B)
      dut.clock.step(1)
      dut.io.probe_req.valid.poke(false.B)
      
      // Dynamic miss Warp 1 on same address
      dut.io.oc_read.valid.poke(true.B)
      dut.io.oc_read.dynamic_addr.poke(0x5010.U)
      dut.io.oc_read.warp_id.poke(1.U)
      dut.io.stall_backpressure.expect(true.B)
      dut.io.roc_req.valid.expect(false.B) // Already requested!
      dut.clock.step(1)
      dut.io.oc_read.valid.poke(false.B)
      dut.clock.step(1)
      
      // Fill
      dut.io.roc_fill.valid.poke(true.B)
      dut.io.roc_fill.addr.poke(0x5000.U)
      dut.io.roc_fill.data.poke(0.U)
      dut.clock.step(1)
      dut.io.roc_fill.valid.poke(false.B)
      
      // Wakeup should be sent to Warp 0
      dut.io.ack_wakeup.valid.expect(true.B)
      dut.io.ack_wakeup.warp_id.expect(0.U)
      dut.clock.step(1)
    }
  }
}
