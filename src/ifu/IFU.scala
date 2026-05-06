package ifu

import chisel3._
import chisel3.util._

class IFU extends Module {
  val io = IO(new Bundle {
    // From Block Scheduler
    val warp_init_valid = Input(Bool())
    val warp_init_id = Input(UInt(IFUConfig.warpIdWidth.W))
    val warp_init_pc = Input(UInt(IFUConfig.pcWidth.W))

    // From I-Buffer
    val credit_return_valid = Input(Bool())
    val credit_return_warp_id = Input(UInt(IFUConfig.warpIdWidth.W))

    // From BRU
    val flush_valid = Input(Bool())
    val flush_warp_id = Input(UInt(IFUConfig.warpIdWidth.W))
    val flush_target_pc = Input(UInt(IFUConfig.pcWidth.W))
    
    // From Warp Scheduler (Exit)
    val warp_exit_valid = Input(Bool())
    val warp_exit_id = Input(UInt(IFUConfig.warpIdWidth.W))

    // To/From L0 I-Cache
    val icache_req = new ICacheReqIO()
    val icache_rsp = new ICacheRspIO()
    val icache_wakeup = new ICacheWakeupIO()

    // To Decoder
    val decoder_out = new DecoderInstIO()
  })

  val pst = Module(new IFU_PST)
  val arbiter = Module(new IFU_Arbiter)
  val controller = Module(new IFU_Controller)

  // 1. PST <-> Arbiter
  arbiter.io.pst_entries := pst.io.entries

  // 2. Arbiter <-> Controller
  controller.io.grant_valid := arbiter.io.grant_valid
  controller.io.grant_warp_id := arbiter.io.grant_warp_id
  controller.io.grant_pc_va := arbiter.io.grant_pc_va
  controller.io.pst_entries := pst.io.entries

  // 3. Controller <-> Cache & Decoder
  io.icache_req <> controller.io.icache_req
  controller.io.icache_rsp <> io.icache_rsp
  controller.io.icache_wakeup <> io.icache_wakeup
  io.decoder_out <> controller.io.decoder_out

  // 4. External / Controller -> PST Updates
  pst.io.ctrl.warp_init_valid := io.warp_init_valid
  pst.io.ctrl.warp_init_id := io.warp_init_id
  pst.io.ctrl.warp_init_pc := io.warp_init_pc
  
  pst.io.ctrl.warp_exit_valid := io.warp_exit_valid
  pst.io.ctrl.warp_exit_id := io.warp_exit_id

  pst.io.ctrl.credit_return_valid := io.credit_return_valid
  pst.io.ctrl.credit_return_warp_id := io.credit_return_warp_id

  pst.io.ctrl.flush_valid := io.flush_valid
  pst.io.ctrl.flush_warp_id := io.flush_warp_id
  pst.io.ctrl.flush_target_pc := io.flush_target_pc

  // Controller driven PST updates
  pst.io.ctrl.arbiter_grant_valid := controller.io.pst_ctrl.arbiter_grant_valid
  pst.io.ctrl.arbiter_grant_id := controller.io.pst_ctrl.arbiter_grant_id
  
  pst.io.ctrl.cache_rsp_valid := controller.io.pst_ctrl.cache_rsp_valid
  pst.io.ctrl.cache_rsp_hit := controller.io.pst_ctrl.cache_rsp_hit
  pst.io.ctrl.cache_rsp_warp_id := controller.io.pst_ctrl.cache_rsp_warp_id
  
  pst.io.ctrl.cache_wakeup_valid := controller.io.pst_ctrl.cache_wakeup_valid
  pst.io.ctrl.cache_wakeup_warp_id := controller.io.pst_ctrl.cache_wakeup_warp_id
}
