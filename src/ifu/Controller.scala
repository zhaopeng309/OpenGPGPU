package ifu

import chisel3._
import chisel3.util._

// Interfaces defined based on MAS 4.1

class ICacheReqIO extends Bundle {
  val req_valid = Output(Bool())
  val req_pc_va = Output(UInt(IFUConfig.pcWidth.W))
  val req_warp_id = Output(UInt(IFUConfig.warpIdWidth.W))
  val req_gen_tag = Output(UInt(2.W)) // Added for phase 4
}

class ICacheRspIO extends Bundle {
  val rsp_valid = Input(Bool())
  val rsp_hit = Input(Bool())
  val rsp_inst_data = Input(UInt(IFUConfig.instWidth.W))
  val rsp_warp_id = Input(UInt(IFUConfig.warpIdWidth.W))
  val rsp_gen_tag = Input(UInt(2.W)) // Added for phase 4
}

class ICacheWakeupIO extends Bundle {
  val wakeup_valid = Input(Bool())
  val wakeup_warp_id = Input(UInt(IFUConfig.warpIdWidth.W))
  val wakeup_gen_tag = Input(UInt(2.W)) // Added for phase 4 (MAS says wakeup sends back data too, or at least Wakeup ID is used to re-request)
  // Actually, MAS 4.1: wakeup_valid, wakeup_warp_id
}

class DecoderInstIO extends Bundle {
  val valid = Output(Bool())
  val inst_data = Output(UInt(IFUConfig.instWidth.W))
  val warp_id = Output(UInt(IFUConfig.warpIdWidth.W))
}

class IFU_Controller extends Module {
  val io = IO(new Bundle {
    // From Arbiter
    val grant_valid = Input(Bool())
    val grant_warp_id = Input(UInt(IFUConfig.warpIdWidth.W))
    val grant_pc_va = Input(UInt(IFUConfig.pcWidth.W))
    val pst_entries = Input(Vec(IFUConfig.numWarps, new PSTEntry())) // Need to read Gen Tag

    // To L0 I-Cache
    val icache_req = new ICacheReqIO()
    val icache_rsp = new ICacheRspIO()
    val icache_wakeup = new ICacheWakeupIO()

    // To Decoder
    val decoder_out = new DecoderInstIO()

    // To PST Update
    val pst_ctrl = Output(new PSTUpdateIO())
  })

  // Feature 3.1: Req to Cache
  io.icache_req.req_valid := io.grant_valid
  io.icache_req.req_pc_va := io.grant_pc_va
  io.icache_req.req_warp_id := io.grant_warp_id
  io.icache_req.req_gen_tag := io.pst_entries(io.grant_warp_id).flush_gen_tag

  // Feature 3.3 & 4.3: Rsp from Cache and Tag Check
  val rsp_is_valid_and_current = io.icache_rsp.rsp_valid && 
    (io.icache_rsp.rsp_gen_tag === io.pst_entries(io.icache_rsp.rsp_warp_id).flush_gen_tag)

  val hit_success = rsp_is_valid_and_current && io.icache_rsp.rsp_hit

  io.decoder_out.valid := hit_success
  io.decoder_out.inst_data := io.icache_rsp.rsp_inst_data
  io.decoder_out.warp_id := io.icache_rsp.rsp_warp_id

  // Default PST controls to 0
  val pst_ctrl = Wire(new PSTUpdateIO())
  pst_ctrl := 0.U.asTypeOf(new PSTUpdateIO())

  // Connect Arbiter grant to PST
  pst_ctrl.arbiter_grant_valid := io.grant_valid
  pst_ctrl.arbiter_grant_id := io.grant_warp_id

  // Connect Cache Rsp to PST
  pst_ctrl.cache_rsp_valid := rsp_is_valid_and_current
  pst_ctrl.cache_rsp_hit := io.icache_rsp.rsp_hit
  pst_ctrl.cache_rsp_warp_id := io.icache_rsp.rsp_warp_id

  // Connect Wakeup to PST
  // MAS 4.1: Wakeup restores state to s_READY. No Gen_tag check mentioned for Wakeup, but we could do it if needed.
  // We'll pass it to PST.
  pst_ctrl.cache_wakeup_valid := io.icache_wakeup.wakeup_valid
  pst_ctrl.cache_wakeup_warp_id := io.icache_wakeup.wakeup_warp_id

  io.pst_ctrl := pst_ctrl
}
