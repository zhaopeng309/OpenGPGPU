package decoder

import chisel3._
import chisel3.util._

// Types for Decoder to I-Buffer
class MicroOp extends Bundle {
  val valid = Bool()
  val opcode = UInt(8.W)     // Internal Opcode ID mapped from Registry
  
  // Operands
  val rd = UInt(8.W)
  val rs1 = UInt(8.W)
  val rs2 = UInt(8.W)
  val rs3 = UInt(16.W)
  val ot = UInt(8.W)         // Operand Type masks
  val imm = UInt(32.W)       // Extracted immediate if any
  
  // Scheduling Domain
  val stallCount = UInt(4.W)
  val yieldFlag = Bool()
  val writeBarrierId = UInt(6.W)
  val readBarrierId = UInt(6.W)
  
  // Hardware Locks
  val waitKAck = Bool()
}

class EarlyProbeReq extends Bundle {
  val warpId = UInt(6.W)
  val addr = UInt(32.W)
}

class BranchRedirect extends Bundle {
  val valid = Bool()
  val targetPc = UInt(32.W)
}

class DecoderIO extends Bundle {
  // Input
  val instIn = Input(UInt(128.W))
  val warpIdIn = Input(UInt(6.W))
  val pcIn = Input(UInt(32.W))
  val validIn = Input(Bool())
  
  // Output
  val microOpOut = Output(new MicroOp())
  val earlyProbeReq = Decoupled(new EarlyProbeReq())
  val branchRedirect = Output(new BranchRedirect())
  
  // Exceptions
  val illegalInst = Output(Bool())
}
