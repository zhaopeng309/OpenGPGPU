package ibuffer

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import decoder.MicroOp

class IBufferSpec extends AnyFlatSpec with ChiselScalatestTester {

  behavior of "IBuffer"

  it should "implement Feature 1: Single Channel Enqueue & Backpressure" in {
    test(new IBuffer).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Initial state
      dut.io.dec.valid.poke(false.B)
      dut.io.sched.popEn.poke(false.B)
      dut.io.flush.flushEn.poke(false.B)
      dut.clock.step(1)

      // Write 4 ops to Warp 0
      dut.io.dec.warpId.poke(0.U)
      dut.io.dec.valid.poke(true.B)
      dut.io.dec.microOp.opcode.poke(1.U)

      for (i <- 0 until 4) {
        dut.io.dec.ready.expect(true.B)
        dut.clock.step(1)
        dut.io.dec.microOp.opcode.poke((i + 2).U)
      }

      // 5th write should be blocked
      dut.io.dec.ready.expect(false.B)
      dut.io.dec.valid.poke(false.B)
      dut.clock.step(1)
    }
  }

  it should "implement Feature 2: Scheduler Pop & Head Export" in {
    test(new IBuffer).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.io.dec.valid.poke(false.B)
      dut.io.sched.popEn.poke(false.B)
      dut.io.flush.flushEn.poke(false.B)

      // Write different ops to Warp 0, 2, 7
      dut.io.dec.valid.poke(true.B)
      dut.io.dec.warpId.poke(0.U)
      dut.io.dec.microOp.opcode.poke(10.U)
      dut.clock.step(1)

      dut.io.dec.warpId.poke(2.U)
      dut.io.dec.microOp.opcode.poke(20.U)
      dut.clock.step(1)

      dut.io.dec.warpId.poke(7.U)
      dut.io.dec.microOp.opcode.poke(70.U)
      dut.clock.step(1)

      dut.io.dec.valid.poke(false.B)

      // Check empty mask and head micro-ops
      // emptyMask: bit i is 1 if empty. We wrote to 0, 2, 7, so they should be 0.
      // Mask for 0, 2, 7 being 0:
      // Binary: 0111 1010 = 0x7A  (Wait, bit 7 is 0, bit 2 is 0, bit 0 is 0)
      // Binary: 01111010
      dut.io.sched.emptyMask.expect("b01111010".U)
      dut.io.sched.headMicroOps(0).opcode.expect(10.U)
      dut.io.sched.headMicroOps(2).opcode.expect(20.U)
      dut.io.sched.headMicroOps(7).opcode.expect(70.U)

      // Trigger Pop(2)
      dut.io.sched.popEn.poke(true.B)
      dut.io.sched.popWarpId.poke(2.U)
      dut.clock.step(1)

      // Warp 2 should now be empty
      dut.io.sched.popEn.poke(false.B)
      // emptyMask bit 2 should be 1
      dut.io.sched.emptyMask.expect("b01111110".U)
    }
  }

  it should "implement Feature 3: Credit-Based Feedback Loop" in {
    test(new IBuffer).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.io.dec.valid.poke(false.B)
      dut.io.sched.popEn.poke(false.B)
      dut.io.flush.flushEn.poke(false.B)

      // Write op to Warp 3
      dut.io.dec.valid.poke(true.B)
      dut.io.dec.warpId.poke(3.U)
      dut.io.dec.microOp.opcode.poke(30.U)
      dut.clock.step(1)
      dut.io.dec.valid.poke(false.B)

      // Pop from Warp 3
      dut.io.sched.popEn.poke(true.B)
      dut.io.sched.popWarpId.poke(3.U)
      
      // Credit should be released in the same cycle or next?
      // Based on our implementation, it's combinational with popEn.
      dut.io.ifu.slotReleasedEn.expect(true.B)
      dut.io.ifu.releasedWarpId.expect(3.U)
      dut.clock.step(1)
      
      // Turn off Pop
      dut.io.sched.popEn.poke(false.B)
      dut.io.ifu.slotReleasedEn.expect(false.B)

      // Try illegal Pop from empty channel (Warp 3 is now empty)
      dut.io.sched.popEn.poke(true.B)
      dut.io.sched.popWarpId.poke(3.U)
      dut.io.ifu.slotReleasedEn.expect(false.B)
      dut.clock.step(1)
    }
  }

  it should "implement Feature 4: Branch Flush Instability" in {
    test(new IBuffer).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.io.dec.valid.poke(false.B)
      dut.io.sched.popEn.poke(false.B)
      dut.io.flush.flushEn.poke(false.B)

      // Write 3 ops to Warp 5
      dut.io.dec.valid.poke(true.B)
      dut.io.dec.warpId.poke(5.U)
      for (i <- 0 until 3) {
        dut.io.dec.microOp.opcode.poke((50 + i).U)
        dut.clock.step(1)
      }
      dut.io.dec.valid.poke(false.B)

      // Check empty mask for Warp 5 (bit 5 should be 0)
      // Mask expected: 1101 1111 = 0xDF
      dut.io.sched.emptyMask.expect("b11011111".U)

      // Trigger Flush(5)
      dut.io.flush.flushEn.poke(true.B)
      dut.io.flush.flushWarpId.poke(5.U)
      dut.clock.step(1)
      dut.io.flush.flushEn.poke(false.B)

      // Check empty mask for Warp 5 (bit 5 should be 1)
      dut.io.sched.emptyMask.expect("b11111111".U)
      
      // Next write should be successful
      dut.io.dec.valid.poke(true.B)
      dut.io.dec.warpId.poke(5.U)
      dut.io.dec.ready.expect(true.B)
    }
  }

}
