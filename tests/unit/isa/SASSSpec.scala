package isa

import chisel3._
import chisel3.util.BitPat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DummyInst1 extends SASS_Instruction("Dummy1") {
  op(15, 0, "1111000011110000") // 16-bit opcode
  val customField = field("Custom", 31, 16)
}

class DummyInst2 extends SASS_Instruction("Dummy2") {
  op(15, 0, "1111000011110001")
}

class SASSSpec extends AnyFlatSpec with Matchers {
  
  "Feature 1" should "correctly return BitPat and field boundaries" in {
    val inst = new DummyInst1
    val bitPat = inst.getBitPat
    
    // Check if bitPat mask and value are correct for the 16 LSBs
    val mask = bitPat.mask
    val value = bitPat.value
    
    // The lowest 16 bits should be masked (1111111111111111 in binary)
    (mask & BigInt(0xFFFF)) should be (BigInt(0xFFFF))
    (value & BigInt(0xFFFF)) should be (BigInt("F0F0", 16))
    
    // All other bits should be ? (mask = 0)
    (mask >> 16) should be (BigInt(0))
    
    // Check fields
    inst.customField.msb should be (31)
    inst.customField.lsb should be (16)
    inst.customField.width should be (16)

    // Check disassemble
    val testMachineCode = BigInt("000000000000000000000000ABCD0000", 16) // customField (31..16) = ABCD
    val disasmStr = inst.disassemble(testMachineCode)
    disasmStr should include("Dummy1")
    disasmStr should include("Custom=0xabcd")
  }
  
  "Feature 2" should "detect opcode conflicts and successfully register instructions" in {
    Registry.clear()
    
    val inst1 = new DummyInst1
    val inst2 = new DummyInst2
    
    Registry.register(inst1)
    Registry.register(inst2)
    
    Registry.instructions.size should be (2)
    
    // Create a conflicting instruction
    class ConflictingInst extends SASS_Instruction("Conflict") {
      op(7, 0, "11110000") // This overlaps with Dummy1 and Dummy2's LSBs, but since higher bits are '?', it matches
    }
    
    an [IllegalArgumentException] should be thrownBy {
      Registry.register(new ConflictingInst)
    }
    
    // But an instruction with specific bits that differ should not conflict
    class NonConflictingInst extends SASS_Instruction("NonConflict") {
      op(15, 0, "0000111100001111")
    }
    Registry.register(new NonConflictingInst)
    Registry.instructions.size should be (3)
  }
}
