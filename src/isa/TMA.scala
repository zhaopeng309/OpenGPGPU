package isa
import chisel3._
class TMA_LOAD extends SASS_Instruction("TMA_LOAD") {
  op(59, 52, "00011010") // 0x1A
}
class TMA_STORE extends SASS_Instruction("TMA.STORE") {
  op(59, 52, "00011011") // 0x1B
}
