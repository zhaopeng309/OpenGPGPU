package isa
import chisel3._
class EXIT extends SASS_Instruction("EXIT") {
  op(59, 52, "00000100") // 0x04
}
