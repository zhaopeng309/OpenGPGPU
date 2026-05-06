package isa
import chisel3._
class FADD_F32 extends SASS_Instruction("FADD_F32") {
  op(59, 52, "01100001") // 0x61
}
