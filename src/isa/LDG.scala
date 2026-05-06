package isa
import chisel3._
class LDG_F32 extends SASS_Instruction("LDG_F32") {
  op(59, 52, "00001101") // 0x0D
}
