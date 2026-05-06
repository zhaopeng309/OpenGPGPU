package isa
import chisel3._
class MBARRIER_INIT extends SASS_Instruction("MBARRIER_INIT") {
  op(59, 52, "00110000") // 0x30
}
class MBARRIER_WAIT extends SASS_Instruction("MBARRIER.WAIT") {
  op(59, 52, "00110010") // 0x32
}
