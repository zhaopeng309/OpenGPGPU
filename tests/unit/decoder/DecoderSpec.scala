package decoder

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import isa.{SASS_Instruction, Registry}

class Phase2Inst extends SASS_Instruction("Phase2Inst") {
  op(59, 52, "10101010") // Opcode 0xAA
}

class Phase3SnoopInst extends SASS_Instruction("SnoopInst") {
  op(59, 52, "00001111") // Opcode 0x0F
  hasConstantSnoop = true
  
  override def isStaticSnoop(inst: chisel3.UInt): chisel3.Bool = {
    ot.extract(inst)(1) === 1.U
  }
}

class Phase3BranchInst extends SASS_Instruction("BranchInst") {
  op(59, 52, "11110000") // Opcode 0xF0
  hasBranchRedirect = true
  override def branchTarget(inst: chisel3.UInt, pc: chisel3.UInt): chisel3.UInt = {
    targetPc.extract(inst) + pc
  }
}

class DecoderSpec extends AnyFlatSpec with ChiselScalatestTester {
  "Feature 3 & 4" should "auto-decode and extract fields based on Registry" in {
    Registry.clear()
    val inst = new Phase2Inst
    Registry.register(inst)

    test(new Decoder) { dut =>
      dut.io.validIn.poke(true.B)
      
      val opCode = BigInt("AA", 16) << 52
      val rdVal = BigInt("11", 16) << 32
      val stallVal = BigInt("5", 16) << 124
      
      val instVal = stallVal | rdVal | opCode
      println(s"inst.getBitPat: ${inst.getBitPat}")
      println(s"instVal: ${instVal.toString(16)}")
      
      dut.io.instIn.poke(instVal.U)
      dut.io.warpIdIn.poke(0.U)
      dut.clock.step(1)
      
      dut.io.microOpOut.valid.expect(true.B)
      dut.io.microOpOut.opcode.expect(0.U) // First registered
      dut.io.microOpOut.stallCount.expect(5.U)
      dut.io.microOpOut.rd.expect(0x11.U)
      dut.io.illegalInst.expect(false.B)
      
      // Test illegal
      val illegalVal = stallVal | rdVal | (BigInt("BB", 16) << 52)
      dut.io.instIn.poke(illegalVal.U)
      dut.clock.step(1)
      
      dut.io.microOpOut.valid.expect(false.B)
      dut.io.illegalInst.expect(true.B)
    }
  }

  "Feature 5 & 6" should "handle K-Cache static snoop and branch redirect" in {
    Registry.clear()
    Registry.register(new Phase3SnoopInst)
    Registry.register(new Phase3BranchInst)

    test(new Decoder) { dut =>
      dut.io.validIn.poke(true.B)
      dut.io.pcIn.poke(0x1000.U)
      dut.io.warpIdIn.poke(3.U)

      // Test Snoop Inst with Static
      val snoopOp = BigInt("0F", 16) << 52
      val staticOT = BigInt("02", 16) << 44 // OT bit 1 is 1
      val immVal = BigInt("00000040", 16) << 64 // Imm32 = 0x40
      val instVal1 = snoopOp | staticOT | immVal
      println(s"snoop instVal1: ${instVal1.toString(16)}")
      
      dut.io.instIn.poke(instVal1.U)
      dut.clock.step(1)
      
      dut.io.earlyProbeReq.valid.expect(true.B)
      dut.io.earlyProbeReq.bits.addr.expect(0x40.U)
      dut.io.earlyProbeReq.bits.warpId.expect(3.U)
      dut.io.microOpOut.waitKAck.expect(true.B)

      // Test Snoop Inst without Static
      val dynamicOT = BigInt("00", 16) << 44 // OT bit 1 is 0
      dut.io.instIn.poke((snoopOp | dynamicOT | immVal).U)
      dut.clock.step(1)
      
      dut.io.earlyProbeReq.valid.expect(false.B)
      dut.io.microOpOut.waitKAck.expect(false.B)

      // Test Branch Inst
      val branchOp = BigInt("F0", 16) << 52
      val targetOffset = BigInt("00000010", 16) << 64
      dut.io.instIn.poke((branchOp | targetOffset).U)
      dut.clock.step(1)
      
      dut.io.branchRedirect.valid.expect(true.B)
      dut.io.branchRedirect.targetPc.expect(0x1010.U) // PC(0x1000) + Offset(0x10)
    }
  }
}
