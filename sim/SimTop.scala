package sim

import chisel3._
import chisel3.util._

import ifu.IFU
import decoder.Decoder
import ibuffer.IBuffer
import l0icache.L0ICache
import l0kcache.L0KCache
import scheduler.{WarpScheduler, Scoreboard}
import opengpgpu.collector.{OperandCollector, CollectorConfig}
import opengpgpu.register.{vGPR_Top, pGPR, uGPR, RegisterFileConfig}

class SimTop extends Module {
  // 定义所有的探针(Probe)接口，将子模块的状态暴露出来供 Testbench 捕获
  val io = IO(new Bundle {
    // === 测试激励输入 ===
    val warp_init_valid = Input(Bool())
    val warp_init_id = Input(UInt(6.W))
    val warp_init_pc = Input(UInt(48.W))
    
    // ICache / KCache fill 激励
    val roc_icache_fill_valid = Input(Bool())
    val roc_icache_fill_addr = Input(UInt(48.W))
    val roc_icache_fill_data = Input(UInt(512.W))
    
    val roc_kcache_fill_valid = Input(Bool())
    val roc_kcache_fill_addr = Input(UInt(48.W))
    val roc_kcache_fill_data = Input(UInt(512.W))

    // === IFU 探针 ===
    val ifu_icache_req_valid = Output(Bool())
    val ifu_icache_rsp_valid = Output(Bool())
    val ifu_icache_rsp_hit   = Output(Bool())
    val ifu_icache_wakeup    = Output(Bool())
    val ifu_decoder_out_valid = Output(Bool())

    // === Decoder 探针 ===
    val dec_validIn = Output(Bool())
    val dec_microOpOut_valid = Output(Bool())
    val dec_microOpOut_opcode = Output(UInt(8.W))
    val dec_illegalInst = Output(Bool())

    // === IBuffer 探针 ===
    val ibuf_emptyMask = Output(UInt(8.W))
    val ibuf_popEn = Output(Bool())
    val ibuf_popWarpId = Output(UInt(3.W))
    val ibuf_creditReturnValid = Output(Bool())

    // === L0ICache 探针 ===
    val icache_req_valid = Output(Bool())
    val icache_miss_valid = Output(Bool())
    val icache_hit_valid = Output(Bool())

    // === L0KCache 探针 ===
    val kcache_probe_valid = Output(Bool())
    val kcache_oc_read_valid = Output(Bool())

    // === Warp Scheduler & Scoreboard 探针 ===
    val ws_dispatch_valid = Output(Bool())
    val ws_dispatch_warpId = Output(UInt(5.W))
    val sb_slot_full_mask = Output(UInt(8.W)) // 假设 8 Warps
    val sb_alloc_req = Output(Bool())
    val sb_release_req = Output(Bool())

    // === Operand Collector 探针 ===
    val oc_issue_valid = Output(Bool())
    
    // === VGPR 探针 ===
    val vgpr_read_reqs = Output(Vec(4, Bool()))
    val vgpr_write_reqs = Output(Vec(4, Bool()))
  })

  // === 实例化所有子模块 ===
  val ifu = Module(new IFU)
  val icache = Module(new L0ICache)
  val kcache = Module(new L0KCache)
  val decoder = Module(new Decoder)
  val ibuffer = Module(new IBuffer)
  
  val scheduler = Module(new WarpScheduler(numWarps = 8, numRegs = 256, numScoreboardSlots = 6))
  val scoreboard = Module(new Scoreboard(numWarps = 8, numSlots = 6))
  
  implicit val collConfig: CollectorConfig = CollectorConfig()
  val collector = Module(new OperandCollector)
  
  val rfConfig = RegisterFileConfig()
  val vgpr = Module(new vGPR_Top(rfConfig))
  val pgpr = Module(new pGPR(rfConfig))
  val ugpr = Module(new uGPR(rfConfig))

  // ==========================================
  // 连接逻辑
  // ==========================================
  
  // IFU Inputs
  ifu.io.warp_init_valid := io.warp_init_valid
  ifu.io.warp_init_id := io.warp_init_id
  ifu.io.warp_init_pc := io.warp_init_pc
  ifu.io.warp_exit_valid := false.B
  ifu.io.warp_exit_id := 0.U
  ifu.io.flush_valid := false.B
  ifu.io.flush_warp_id := 0.U
  ifu.io.flush_target_pc := 0.U
  
  // IFU <-> IBuffer Credit Return
  ifu.io.credit_return_valid := ibuffer.io.ifu.slotReleasedEn
  ifu.io.credit_return_warp_id := ibuffer.io.ifu.releasedWarpId

  // IFU <-> L0ICache
  icache.io.ifu.req_valid := ifu.io.icache_req.req_valid
  icache.io.ifu.req_warp_id := ifu.io.icache_req.req_warp_id
  icache.io.ifu.req_addr := ifu.io.icache_req.req_pc_va
  icache.io.ifu.req_gen_tag := ifu.io.icache_req.req_gen_tag

  val is_hit = icache.io.ifu_resp.hit_valid
  val is_miss = icache.io.ifu_resp.miss_valid
  ifu.io.icache_rsp.rsp_valid := is_hit || is_miss
  ifu.io.icache_rsp.rsp_hit := is_hit
  ifu.io.icache_rsp.rsp_warp_id := Mux(is_hit, icache.io.ifu_resp.hit_warp_id, icache.io.ifu_resp.miss_warp_id)
  ifu.io.icache_rsp.rsp_inst_data := icache.io.ifu_resp.hit_data
  ifu.io.icache_rsp.rsp_gen_tag := icache.io.ifu_resp.hit_gen_tag
  
  ifu.io.icache_wakeup.wakeup_valid := icache.io.ifu_resp.wakeup_valid
  ifu.io.icache_wakeup.wakeup_warp_id := PriorityEncoder(icache.io.ifu_resp.wakeup_warp_mask)
  ifu.io.icache_wakeup.wakeup_gen_tag := icache.io.ifu_resp.wakeup_gen_tag

  // Decoder
  decoder.io.validIn := ifu.io.decoder_out.valid
  decoder.io.instIn := ifu.io.decoder_out.inst_data
  decoder.io.warpIdIn := ifu.io.decoder_out.warp_id
  decoder.io.pcIn := 0.U
  
  // Decoder <-> L0KCache
  decoder.io.earlyProbeReq.ready := true.B
  kcache.io.probe_req.valid := decoder.io.earlyProbeReq.valid
  kcache.io.probe_req.static_addr := decoder.io.earlyProbeReq.bits.addr
  kcache.io.probe_req.warp_id := decoder.io.earlyProbeReq.bits.warpId
  
  kcache.io.oc_read.valid := false.B
  kcache.io.oc_read.dynamic_addr := 0.U
  kcache.io.oc_read.warp_id := 0.U

  // Decoder <-> IBuffer
  ibuffer.io.dec.valid := decoder.io.microOpOut.valid
  ibuffer.io.dec.warpId := decoder.io.warpIdIn
  ibuffer.io.dec.microOp := decoder.io.microOpOut

  // IBuffer <-> WarpScheduler
  scheduler.io.ibEmptyMask := ibuffer.io.sched.emptyMask
  scheduler.io.ibHeadMicroOps := ibuffer.io.sched.headMicroOps
  ibuffer.io.sched.popEn := scheduler.io.wsIbPopReq
  ibuffer.io.sched.popWarpId := scheduler.io.wsIbPopId
  ibuffer.io.flush.flushEn := false.B
  ibuffer.io.flush.flushWarpId := 0.U

  // Scheduler Inputs
  scheduler.io.allocReq := io.warp_init_valid // Simplify alloc
  scheduler.io.allocWarpId := io.warp_init_id
  scheduler.io.blkschActiveMask := 1.U // Always active
  scheduler.io.blkschBarId := 0.U
  scheduler.io.kcacheMissWaitMask := 0.U
  scheduler.io.kcacheFillAckMask := 0.U
  
  // Scoreboard Integration with Scheduler
  scoreboard.io.hazard_query_warp_id := PriorityEncoder(~scheduler.io.ibEmptyMask) // Simplify for sim
  scoreboard.io.hazard_query_rs1 := 0.U
  scoreboard.io.hazard_query_rs2 := 0.U
  scoreboard.io.hazard_query_rd := 0.U
  
  val dispatching = scheduler.io.dispatch.valid && scheduler.io.dispatch.ready
  scoreboard.io.alloc_req := dispatching
  scoreboard.io.alloc_warp_id := scheduler.io.dispatch.bits.warpId
  scoreboard.io.alloc_reg_id := scheduler.io.dispatch.bits.microOp.rd
  
  scoreboard.io.release_req := false.B // Driven by mock vALU normally
  scoreboard.io.release_warp_id := 0.U
  scoreboard.io.release_reg_id := 0.U
  
  scheduler.io.releaseReq.valid := false.B
  scheduler.io.releaseReq.bits := DontCare

  // Scheduler <-> Operand Collector
  collector.io.dispatch <> scheduler.io.dispatch
  collector.io.issue.ready := true.B // Always ready to issue for sim
  
  // Collector <-> Register File
  for(i <- 0 until 4) {
    vgpr.io.readReqs(i).valid := collector.io.rfReadReq(i).valid
    vgpr.io.readReqs(i).bits.wid := collector.io.rfReadReq(i).bits.wid
    vgpr.io.readReqs(i).bits.regId := collector.io.rfReadReq(i).bits.regId
    
    collector.io.rfReadResp(i).valid := RegNext(collector.io.rfReadReq(i).valid)
    collector.io.rfReadResp(i).bits.data := vgpr.io.readData(i)

    vgpr.io.writeReqs(i).valid := false.B
    vgpr.io.writeReqs(i).bits := DontCare
  }
  
  // external Cache fills
  icache.io.roc_resp.fill_valid := io.roc_icache_fill_valid
  icache.io.roc_resp.fill_addr := io.roc_icache_fill_addr
  icache.io.roc_resp.fill_data := io.roc_icache_fill_data
  icache.io.bs_req.preload_valid := false.B
  icache.io.bs_req.preload_addr := 0.U

  kcache.io.roc_fill.valid := io.roc_kcache_fill_valid
  kcache.io.roc_fill.addr := io.roc_kcache_fill_addr
  kcache.io.roc_fill.data := io.roc_kcache_fill_data

  pgpr.io.read_wid := 0.U
  pgpr.io.read_pid := 0.U
  pgpr.io.write_en := false.B
  pgpr.io.write_wid := 0.U
  pgpr.io.write_pid := 0.U
  pgpr.io.write_data := 0.U
  pgpr.io.write_mask := 0.U

  ugpr.io.readAddr := 0.U
  ugpr.io.readEn := false.B
  ugpr.io.writeAddr := 0.U
  ugpr.io.writeEn := false.B
  ugpr.io.writeData := 0.U

  // ==========================================
  // 输出探针连接
  // ==========================================
  io.ifu_icache_req_valid := ifu.io.icache_req.req_valid
  io.ifu_icache_rsp_valid := ifu.io.icache_rsp.rsp_valid
  io.ifu_icache_rsp_hit   := ifu.io.icache_rsp.rsp_hit
  io.ifu_icache_wakeup    := ifu.io.icache_wakeup.wakeup_valid
  io.ifu_decoder_out_valid:= ifu.io.decoder_out.valid

  io.dec_validIn := decoder.io.validIn
  io.dec_microOpOut_valid := decoder.io.microOpOut.valid
  io.dec_microOpOut_opcode := decoder.io.microOpOut.opcode
  io.dec_illegalInst := decoder.io.illegalInst

  io.ibuf_emptyMask := ibuffer.io.sched.emptyMask
  io.ibuf_popEn := ibuffer.io.sched.popEn
  io.ibuf_popWarpId := ibuffer.io.sched.popWarpId
  io.ibuf_creditReturnValid := ibuffer.io.ifu.slotReleasedEn

  io.icache_req_valid := icache.io.ifu.req_valid
  io.icache_miss_valid := icache.io.ifu_resp.miss_valid
  io.icache_hit_valid := icache.io.ifu_resp.hit_valid

  io.kcache_probe_valid := kcache.io.probe_req.valid
  io.kcache_oc_read_valid := kcache.io.oc_read.valid

  io.ws_dispatch_valid := scheduler.io.dispatch.valid
  io.ws_dispatch_warpId := scheduler.io.dispatch.bits.warpId
  io.sb_slot_full_mask := scoreboard.io.slot_full_mask
  io.sb_alloc_req := scoreboard.io.alloc_req
  io.sb_release_req := scoreboard.io.release_req

  io.oc_issue_valid := collector.io.issue.valid

  for(i <- 0 until 4) {
    io.vgpr_read_reqs(i) := vgpr.io.readReqs(i).valid
    io.vgpr_write_reqs(i) := vgpr.io.writeReqs(i).valid
  }
}
