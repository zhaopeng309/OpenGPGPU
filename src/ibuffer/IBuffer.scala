package ibuffer

import chisel3._
import chisel3.util._
import decoder.MicroOp

class IBuffer extends Module {
  val io = IO(new IBufferIO())

  // Create 8 Queue channels, each with a depth of 4
  // We use flushable queue to handle branch flush.
  val queues = Seq.fill(8)(Module(new Queue(new MicroOp(), 4, flow = false, hasFlush = true)))

  // Default values
  io.dec.ready := false.B
  io.ifu.slotReleasedEn := false.B
  io.ifu.releasedWarpId := 0.U

  // Decoder Write Routing (Block 2 & Block 1 part 1)
  for (i <- 0 until 8) {
    // Write port
    queues(i).io.enq.valid := io.dec.valid && (io.dec.warpId === i.U)
    queues(i).io.enq.bits := io.dec.microOp
    
    if (i == 0) {
      when(io.dec.warpId === 0.U) { io.dec.ready := queues(0).io.enq.ready }
    } else {
      when(io.dec.warpId === i.U) { io.dec.ready := queues(i).io.enq.ready }
    }
  }

  // Scheduler Read Routing (Block 2 & Block 1 part 2)
  val emptyMask = Wire(Vec(8, Bool()))
  for (i <- 0 until 8) {
    // Read port state
    emptyMask(i) := !queues(i).io.deq.valid
    
    // Output head element, if empty output a default 0 valid element
    io.sched.headMicroOps(i) := queues(i).io.deq.bits
    when(!queues(i).io.deq.valid) {
      io.sched.headMicroOps(i).valid := false.B
    }

    // Default pop valid is false
    queues(i).io.deq.ready := false.B
  }
  io.sched.emptyMask := emptyMask.asUInt

  // Pop handling & Credit Feedback (Block 3)
  when(io.sched.popEn) {
    val popWarp = io.sched.popWarpId
    for (i <- 0 until 8) {
      when(popWarp === i.U) {
        queues(i).io.deq.ready := true.B
        // Only return credit if it was a valid pop (queue was not empty)
        when(queues(i).io.deq.valid) {
          io.ifu.slotReleasedEn := true.B
          io.ifu.releasedWarpId := popWarp
        }
      }
    }
  }

  // Flush Controller (Block 4)
  for (i <- 0 until 8) {
    queues(i).io.flush.get := false.B
    when(io.flush.flushEn && (io.flush.flushWarpId === i.U)) {
      queues(i).io.flush.get := true.B
    }
  }
}
