package opengpgpu.RCB

import chisel3._
import chisel3.util._

// ── Data Types moved here ──
class RCBEntryData(implicit p: RCBConfig) extends Bundle {
  val data        = Vec(p.threadPerWarp, UInt(p.vGPRWidth.W))
  val rd_index    = UInt(8.W)
  val warp_id     = UInt(p.warpIdWidth.W)
  val write_mask  = UInt(p.threadPerWarp.W)
  val barrier_id  = UInt(12.W)
  val source_type = UInt(2.W)
  val dest_type   = UInt(2.W)
}

// ── 配置参数 ──
case class RCBConfig(
  numWarps: Int = 32,
  numEntries: Int = 12,
  numBanks: Int = 4,
  threadPerWarp: Int = 32,
  vGPRWidth: Int = 32
) {
  def warpIdWidth = log2Ceil(numWarps)
}

// ── 统一结果写回包 ──
class ResultPacket(implicit val p: RCBConfig) extends Bundle {
  val data        = Vec(p.threadPerWarp, UInt(p.vGPRWidth.W))
  val rd_index    = UInt(8.W)
  val warp_id     = UInt(p.warpIdWidth.W)
  val write_mask  = UInt(p.threadPerWarp.W)
  val barrier_id  = UInt(12.W)
  val source_type = UInt(2.W)
  val dest_type   = UInt(2.W)
}

// ── Bank 写请求（wid+regId 格式，与现有 vGPR writeReqs 兼容）──
class BankWriteReq(implicit p: RCBConfig) extends Bundle {
  val wid   = UInt(p.warpIdWidth.W)
  val regId = UInt(8.W)
  val data  = Vec(p.threadPerWarp, UInt(p.vGPRWidth.W))
  val mask  = UInt(p.threadPerWarp.W)
}

// ── 屏障释放 ──
class BarrierRelease(implicit p: RCBConfig) extends Bundle {
  val warp_id  = UInt(5.W)
  val rd_index = UInt(8.W)
}

// ── Bypass 查询（Phase 2 实现，Phase 1 预留端口） ──
class BypassQueryIO(implicit p: RCBConfig) extends Bundle {
  val rs_index = UInt(8.W)
  val warp_id  = UInt(5.W)
}

class BypassResp(implicit p: RCBConfig) extends Bundle {
  val hit  = Bool()
  val data = Vec(p.threadPerWarp, UInt(p.vGPRWidth.W))
}

// ── RCB 顶层 IO ──
class RCB_IO(implicit val p: RCBConfig) extends Bundle {
  val i_valu_res = Flipped(Decoupled(new ResultPacket()))
  val i_lsu_res  = Flipped(Decoupled(new ResultPacket()))
  val i_a2v_res  = Flipped(Decoupled(new ResultPacket()))
  val bypass_query = Flipped(Valid(new BypassQueryIO()))
  val bypass_resp  = Output(new BypassResp())
  val o_bk_write = Vec(p.numBanks, Decoupled(new BankWriteReq()))
  val o_bar_rel  = Valid(new BarrierRelease())
}
