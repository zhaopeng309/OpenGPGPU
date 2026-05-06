package isa
import chisel3._
class WBRA_U extends SASS_Instruction("WBRA_U") {
  op(59, 52, "00000001") // 0x01
  hasBranchRedirect = true
}
