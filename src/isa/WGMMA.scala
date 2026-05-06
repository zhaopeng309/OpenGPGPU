package isa
import chisel3._
class WGMMA_M16N16K16_F32_F16_F16 extends SASS_Instruction("WGMMA_M16N16K16_F32_F16_F16") {
  op(59, 52, "01010000") // 0x50
}
