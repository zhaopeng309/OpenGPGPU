package isa
import chisel3._
class IMAD_U32 extends SASS_Instruction("IMAD_U32") {
  op(59, 52, "00100100") // 0x24
  op(51, 40, "000000000000") // regular
}
class IMAD_U32_IMM extends SASS_Instruction("IMAD.U32.IMM") {
  op(59, 52, "00100100") // 0x24
  op(51, 40, "000000000001") // IMM flag
}
