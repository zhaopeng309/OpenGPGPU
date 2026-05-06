package ifu

import chisel3._
import chisel3.util._

object WarpState extends ChiselEnum {
  val s_IDLE, s_READY, s_FETCH_REQ, s_WAIT_CACHE, s_STALL_CREDIT = Value
}

class PSTEntry extends Bundle {
  val valid = Bool()
  val pc_va = UInt(48.W)
  val state = WarpState()
  val credits = UInt(3.W)
  val flush_gen_tag = UInt(2.W)
  val inst_id = UInt(64.W)
}

object IFUConfig {
  val numWarps = 8
  val warpIdWidth = log2Ceil(numWarps)
  val initialCredits = 4
  val pcWidth = 48
  val instWidth = 128
}
