package isa

import chisel3._
import chisel3.util.BitPat

case class Field(name: String, msb: Int, lsb: Int) {
  val width = msb - lsb + 1
  def extract(inst: chisel3.UInt): chisel3.UInt = inst(msb, lsb)
}

abstract class SASS_Instruction(val name: String) {
  protected var matchBits: Seq[((Int, Int), String)] = Seq()
  protected var fields: Map[String, Field] = Map()

  // Instruction properties for decode
  var hasConstantSnoop: Boolean = false
  var hasBranchRedirect: Boolean = false
  
  // Custom logic for Phase 3
  def isStaticSnoop(inst: chisel3.UInt): chisel3.Bool = true.B
  def branchTarget(inst: chisel3.UInt, pc: chisel3.UInt): chisel3.UInt = targetPc.extract(inst)

  // Define an opcode or fixed bits
  def op(msb: Int, lsb: Int, binaryPattern: String): Unit = {
    require(binaryPattern.length == msb - lsb + 1, s"Pattern $binaryPattern length does not match width ${msb - lsb + 1}")
    require(binaryPattern.forall(c => c == '0' || c == '1'), s"Pattern $binaryPattern contains invalid characters")
    matchBits = matchBits :+ ((msb, lsb) -> binaryPattern)
  }

  // Define an extracted field
  def field(fieldName: String, msb: Int, lsb: Int): Field = {
    val f = Field(fieldName, msb, lsb)
    fields = fields + (fieldName -> f)
    f
  }

  // Get the combined BitPat for the 128-bit instruction
  def getBitPat: BitPat = {
    val chars = Array.fill(128)('?')
    for (((msb, lsb), pattern) <- matchBits) {
      for (i <- 0 until pattern.length) {
        val bitPos = lsb + pattern.length - 1 - i
        chars(127 - bitPos) = pattern(i)
      }
    }
    BitPat("b" + chars.mkString)
  }

  def getFields: Map[String, Field] = fields

  // Standard fields based on OpenGPGPU MVP ISA ISA.md
  val stallCount = field("Stall_Count", 127, 124)
  val readBarrierId = field("Read_Barrier_ID", 123, 118)
  val writeBarrierId = field("Write_Barrier_ID", 117, 112)
  val yieldFlag = field("Yield", 111, 111)
  
  val extendedModifiers = field("Extended_Modifiers", 103, 64)
  val predicate = field("Predicate", 63, 60)
  val opcode = field("Opcode", 59, 52)
  val modifiers = field("Modifiers", 51, 40)
  
  val ot = field("OT", 51, 44) // 8-bit OT inside modifiers or elsewhere
  val imm32 = field("Imm32", 95, 64)

  // Default register slots, can be overridden or defined by specific instructions
  val rd = field("Rd", 39, 32)
  val rs1 = field("Rs1", 31, 24)
  val rs2 = field("Rs2", 23, 16)
  val rs3 = field("Rs3", 15, 0) // or Imm16
  
  // For branch target
  val targetPc = field("TargetPC", 103, 64)

  // Disassemble the given 128-bit machine code into a readable SASS assembly string
  def disassemble(inst: BigInt): String = {
    val fieldStrs = fields.values.toSeq.sortBy(_.lsb).reverse.map { f =>
      val mask = (BigInt(1) << f.width) - 1
      val value = (inst >> f.lsb) & mask
      s"${f.name}=0x${value.toString(16)}"
    }
    s"$name ${fieldStrs.mkString(" ")}"
  }
}

object Registry {
  var instructions: Seq[SASS_Instruction] = Seq()

  def clear(): Unit = {
    instructions = Seq()
  }

  def register(inst: SASS_Instruction): Unit = {
    // Check for conflicts
    val newPat = inst.getBitPat
    for (existing <- instructions) {
      val existingPat = existing.getBitPat
      if (conflicts(existingPat, newPat)) {
        throw new IllegalArgumentException(s"Instruction ${inst.name} conflicts with ${existing.name}")
      }
    }
    instructions = instructions :+ inst
  }

  private def conflicts(bp1: BitPat, bp2: BitPat): Boolean = {
    val mask1 = bp1.mask
    val value1 = bp1.value
    val mask2 = bp2.mask
    val value2 = bp2.value
    (mask1 & mask2 & (value1 ^ value2)) == 0
  }
}
