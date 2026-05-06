package isa
import chisel3._
class LDC_64 extends SASS_Instruction("LDC_64") {
  op(59, 52, "00001100") // 0x0C
  op(51, 40, "000001000000") // 64
}
class LDC_32 extends SASS_Instruction("LDC.32") {
  op(59, 52, "00001100") // 0x0C
  op(51, 40, "000000100000") // 32
}
