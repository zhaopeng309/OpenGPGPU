package isa
import chisel3._
class V2A extends SASS_Instruction("V2A") {
  op(59, 52, "01000000") // 0x40
}
