package ibuffer

import chisel3._
import chisel3.util._
import decoder.MicroOp

class DecToIBufferIO extends Bundle {
  val valid = Input(Bool())
  val warpId = Input(UInt(3.W)) // Assuming 8 Warps (3 bits)
  val microOp = Input(new MicroOp())
  val ready = Output(Bool())
}

class IBufferToSchedIO extends Bundle {
  val emptyMask = Output(UInt(8.W)) // 1 = Empty
  val headMicroOps = Output(Vec(8, new MicroOp())) // 8 channels
  
  val popEn = Input(Bool())
  val popWarpId = Input(UInt(3.W))
}

class IBufferToIfuIO extends Bundle {
  val slotReleasedEn = Output(Bool())
  val releasedWarpId = Output(UInt(3.W))
}

class IBufferFlushIO extends Bundle {
  val flushEn = Input(Bool())
  val flushWarpId = Input(UInt(3.W))
}

class IBufferIO extends Bundle {
  val dec = new DecToIBufferIO()
  val sched = new IBufferToSchedIO()
  val ifu = new IBufferToIfuIO()
  val flush = new IBufferFlushIO()
}
