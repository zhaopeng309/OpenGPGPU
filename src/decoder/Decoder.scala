package decoder

import chisel3._
import chisel3.util._
import isa.Registry

class Decoder extends Module {
  val io = IO(new DecoderIO)

  // Default outputs
  io.microOpOut := 0.U.asTypeOf(new MicroOp())
  io.microOpOut.valid := false.B

  io.earlyProbeReq.valid := false.B
  io.earlyProbeReq.bits := 0.U.asTypeOf(new EarlyProbeReq())

  io.branchRedirect.valid := false.B
  io.branchRedirect.targetPc := 0.U

  io.illegalInst := false.B

  if (Registry.instructions.isEmpty) {
    io.illegalInst := io.validIn
  } else {
    val hitWire = WireDefault(false.B)
    
    for ((inst, idx) <- Registry.instructions.zipWithIndex) {
      val isMatch = io.instIn === inst.getBitPat
      when (io.validIn && isMatch) {
        hitWire := true.B
        io.microOpOut.valid := true.B
        io.microOpOut.opcode := idx.U
        
        // Extract common scheduling fields
        if (inst.getFields.contains("Stall_Count")) io.microOpOut.stallCount := inst.stallCount.extract(io.instIn)
        if (inst.getFields.contains("Yield")) io.microOpOut.yieldFlag := inst.yieldFlag.extract(io.instIn) =/= 0.U
        if (inst.getFields.contains("Write_Barrier_ID")) io.microOpOut.writeBarrierId := inst.writeBarrierId.extract(io.instIn)
        if (inst.getFields.contains("Read_Barrier_ID")) io.microOpOut.readBarrierId := inst.readBarrierId.extract(io.instIn)
        
        // Extract operands
        if (inst.getFields.contains("Rd")) io.microOpOut.rd := inst.rd.extract(io.instIn)
        if (inst.getFields.contains("Rs1")) io.microOpOut.rs1 := inst.rs1.extract(io.instIn)
        if (inst.getFields.contains("Rs2")) io.microOpOut.rs2 := inst.rs2.extract(io.instIn)
        if (inst.getFields.contains("Rs3")) io.microOpOut.rs3 := inst.rs3.extract(io.instIn)
        if (inst.getFields.contains("OT")) io.microOpOut.ot := inst.ot.extract(io.instIn)
        if (inst.getFields.contains("Imm32")) io.microOpOut.imm := inst.imm32.extract(io.instIn)
        
        // Phase 3 Features
        if (inst.hasConstantSnoop) {
          val isStatic = inst.isStaticSnoop(io.instIn)
          when(isStatic) {
            io.earlyProbeReq.valid := true.B
            io.earlyProbeReq.bits.warpId := io.warpIdIn
            io.earlyProbeReq.bits.addr := inst.imm32.extract(io.instIn)
            io.microOpOut.waitKAck := true.B
          } .otherwise {
            io.microOpOut.waitKAck := false.B
          }
        } else {
          io.microOpOut.waitKAck := false.B
        }
        
        if (inst.hasBranchRedirect) {
          io.branchRedirect.valid := true.B
          io.branchRedirect.targetPc := inst.branchTarget(io.instIn, io.pcIn)
        }
      }
    }
    
    when (io.validIn && !hitWire) {
      io.illegalInst := true.B
    }
  }
}
