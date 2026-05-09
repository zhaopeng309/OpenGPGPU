package opengpgpu.smsp

import chisel3._
import chisel3.util._

import ifu.{IFU, IFUConfig}
import decoder.Decoder
import ibuffer.IBuffer
import l0icache.L0ICache
import l0kcache.L0KCache
import scheduler.{WarpScheduler, Scoreboard, SchedulerLogic}
import opengpgpu.collector.{OperandCollector, CollectorConfig}
import opengpgpu.register.{vGPR_Top, pGPR, uGPR, RegisterFileConfig}
import opengpgpu.valu.vALU
import opengpgpu.RCB.{RCB, RCBConfig}

// ==========================================
// SMSP 配置参数
// ==========================================
case class SMSPConfig(
  numWarps: Int = 8,
  numRegs: Int = 256,
  numScoreboardSlots: Int = 6,
  numCUs: Int = 8,
  numBanks: Int = 4,
  threadPerWarp: Int = 32,
  vGPRWidth: Int = 32,
  rcbEntries: Int = 12
)

// ==========================================
// SM 级接口定义
// SMSP 通过此接口与 SM 全局资源通信
// ==========================================
class SMInterface(implicit cfg: SMSPConfig) extends Bundle {
  // === Block Scheduler 接口 ===
  val warp_init_valid = Input(Bool())
  val warp_init_id = Input(UInt(log2Ceil(cfg.numWarps).W))
  val warp_init_pc = Input(UInt(48.W))
  val warp_exit_valid = Output(Bool())
  val warp_exit_id = Output(UInt(log2Ceil(cfg.numWarps).W))
  val blksch_active_mask = Input(UInt(32.W))
  val blksch_bar_id = Input(UInt(4.W))
  val blksch_block_done = Output(Bool())

  // === L0 I-Cache Fill (来自 ROC) ===
  val roc_icache_fill_valid = Input(Bool())
  val roc_icache_fill_addr = Input(UInt(48.W))
  val roc_icache_fill_data = Input(UInt(512.W))

  // === L0 K-Cache Fill (来自 ROC) ===
  val roc_kcache_fill_valid = Input(Bool())
  val roc_kcache_fill_addr = Input(UInt(48.W))
  val roc_kcache_fill_data = Input(UInt(512.W))

  // === L0 I-Cache Request to ROC ===
  val icache_roc_req_valid = Output(Bool())
  val icache_roc_req_addr = Output(UInt(48.W))

  // === L0 K-Cache Request to ROC ===
  val kcache_roc_req_valid = Output(Bool())
  val kcache_roc_req_addr = Output(UInt(48.W))

  // === LSU_Hub 接口 (Phase 3 预留) ===
  val lsu_req_valid = Output(Bool())
  val lsu_req_ready = Input(Bool())
  val lsu_resp_valid = Input(Bool())
  val lsu_resp_ready = Output(Bool())

  // === mBarrier 唤醒通路 (Phase 3 预留) ===
  val mbarrier_wakeup_valid = Input(Bool())
  val mbarrier_wakeup_warp_id = Input(UInt(log2Ceil(cfg.numWarps).W))

  // === MMA (WGMMA) 结果路由 (Phase 3 预留) ===
  val mma_result_valid = Input(Bool())
  val mma_result_ready = Output(Bool())

  // === K-Cache Miss/Wakeup 状态 (给 Block Scheduler) ===
  val kcache_miss_wait_mask = Output(UInt(cfg.numWarps.W))
  val kcache_fill_ack_mask = Output(UInt(cfg.numWarps.W))
}

// ==========================================
// SMSP (Sub-Core) 主模块
// 封装所有私有资源，提供标准化 SM 级接口
// ==========================================
class SMSP(implicit cfg: SMSPConfig) extends Module {
  val io = IO(new SMInterface())

  // ==========================================
  // 隐式配置参数
  // ==========================================
  implicit val collConfig: CollectorConfig = CollectorConfig(
    numCUs = cfg.numCUs,
    numBanks = cfg.numBanks,
    threadPerWarp = cfg.threadPerWarp,
    vGPRWidth = cfg.vGPRWidth
  )

  implicit val rcbConfig: RCBConfig = RCBConfig(
    numWarps = cfg.numWarps,
    numEntries = cfg.rcbEntries,
    numBanks = cfg.numBanks,
    threadPerWarp = cfg.threadPerWarp,
    vGPRWidth = cfg.vGPRWidth
  )

  val rfConfig = RegisterFileConfig(
    numWarps = cfg.numWarps,
    numRegsPerWarp = cfg.numRegs,
    threadPerWarp = cfg.threadPerWarp,
    vGPRWidth = cfg.vGPRWidth,
    vGPRBanks = cfg.numBanks
  )

  // ==========================================
  // 实例化所有子模块
  // ==========================================

  // --- 前端: IFU + L0ICache ---
  val ifu = Module(new IFU)
  val icache = Module(new L0ICache)

  // --- 译码: Decoder + L0KCache ---
  val decoder = Module(new Decoder)
  val kcache = Module(new L0KCache)

  // --- 指令缓冲: IBuffer ---
  val ibuffer = Module(new IBuffer)

  // --- 调度: WarpScheduler + Scoreboard ---
  val scheduler = Module(new WarpScheduler(
    numWarps = cfg.numWarps,
    numRegs = cfg.numRegs,
    numScoreboardSlots = cfg.numScoreboardSlots
  ))
  val scoreboard = Module(new Scoreboard(
    numWarps = cfg.numWarps,
    numSlots = cfg.numScoreboardSlots
  ))

  // --- 操作数收集: OperandCollector ---
  val collector = Module(new OperandCollector)

  // --- 寄存器堆: vGPR, pGPR, uGPR ---
  val vgpr = Module(new vGPR_Top(rfConfig))
  val pgpr = Module(new pGPR(rfConfig))
  val ugpr = Module(new uGPR(rfConfig))

  // --- 执行: vALU ---
  val valu = Module(new vALU())

  // --- 写回: RCB ---
  val rcb = Module(new RCB())

  // ==========================================
  // 连接逻辑
  // ==========================================

  // ── 1. Block Scheduler -> IFU (Warp Init) ──
  ifu.io.warp_init_valid := io.warp_init_valid
  ifu.io.warp_init_id := io.warp_init_id
  ifu.io.warp_init_pc := io.warp_init_pc
  ifu.io.warp_exit_valid := false.B
  ifu.io.warp_exit_id := 0.U
  ifu.io.flush_valid := false.B
  ifu.io.flush_warp_id := 0.U
  ifu.io.flush_target_pc := 0.U

  // ── 2. IFU <-> IBuffer (Credit Return) ──
  ifu.io.credit_return_valid := ibuffer.io.ifu.slotReleasedEn
  ifu.io.credit_return_warp_id := ibuffer.io.ifu.releasedWarpId

  // ── 3. IFU <-> L0ICache ──
  icache.io.ifu.req_valid := ifu.io.icache_req.req_valid
  icache.io.ifu.req_warp_id := ifu.io.icache_req.req_warp_id
  icache.io.ifu.req_addr := ifu.io.icache_req.req_pc_va
  icache.io.ifu.req_gen_tag := ifu.io.icache_req.req_gen_tag

  val is_hit = icache.io.ifu_resp.hit_valid
  val is_miss = icache.io.ifu_resp.miss_valid
  ifu.io.icache_rsp.rsp_valid := is_hit || is_miss
  ifu.io.icache_rsp.rsp_hit := is_hit
  ifu.io.icache_rsp.rsp_warp_id := Mux(is_hit,
    icache.io.ifu_resp.hit_warp_id,
    icache.io.ifu_resp.miss_warp_id)
  ifu.io.icache_rsp.rsp_inst_data := icache.io.ifu_resp.hit_data
  ifu.io.icache_rsp.rsp_gen_tag := icache.io.ifu_resp.hit_gen_tag

  ifu.io.icache_wakeup.wakeup_valid := icache.io.ifu_resp.wakeup_valid
  ifu.io.icache_wakeup.wakeup_warp_id := PriorityEncoder(icache.io.ifu_resp.wakeup_warp_mask)
  ifu.io.icache_wakeup.wakeup_gen_tag := icache.io.ifu_resp.wakeup_gen_tag

  // ── 4. IFU -> Decoder ──
  decoder.io.validIn := ifu.io.decoder_out.valid
  decoder.io.instIn := ifu.io.decoder_out.inst_data
  decoder.io.warpIdIn := ifu.io.decoder_out.warp_id
  decoder.io.pcIn := 0.U

  // ── 5. Decoder <-> L0KCache (K-Sniffer: 常量地址提前探针) ──
  decoder.io.constantProbeReq.ready := true.B
  kcache.io.probe_req.valid := decoder.io.constantProbeReq.valid
  kcache.io.probe_req.static_addr := decoder.io.constantProbeReq.bits.addr
  kcache.io.probe_req.warp_id := decoder.io.constantProbeReq.bits.warpId

  // OC Read (动态地址) 暂未连接，Phase 3 由 LSU_Hub 提供
  kcache.io.oc_read.valid := false.B
  kcache.io.oc_read.dynamic_addr := 0.U
  kcache.io.oc_read.warp_id := 0.U

  // ── 6. Decoder -> IBuffer ──
  ibuffer.io.dec.valid := decoder.io.microOpOut.valid
  ibuffer.io.dec.warpId := decoder.io.warpIdIn
  ibuffer.io.dec.microOp := decoder.io.microOpOut

  // ── 7. IBuffer <-> WarpScheduler ──
  scheduler.io.ibEmptyMask := ibuffer.io.sched.emptyMask
  scheduler.io.ibHeadMicroOps := ibuffer.io.sched.headMicroOps
  ibuffer.io.sched.popEn := scheduler.io.wsIbPopReq
  ibuffer.io.sched.popWarpId := scheduler.io.wsIbPopId
  ibuffer.io.flush.flushEn := false.B
  ibuffer.io.flush.flushWarpId := 0.U

  // ── 8. WarpScheduler 控制输入 ──
  scheduler.io.allocReq := io.warp_init_valid
  scheduler.io.allocWarpId := io.warp_init_id
  scheduler.io.blkschActiveMask := io.blksch_active_mask
  scheduler.io.blkschBarId := io.blksch_bar_id
  scheduler.io.kcacheMissWaitMask := io.kcache_miss_wait_mask
  scheduler.io.kcacheFillAckMask := io.kcache_fill_ack_mask

  // ── 9. Scoreboard 集成 ──
  // Hazard 查询: 由 WarpScheduler 内部完成 (Slot-based 逻辑已内嵌)
  // Scoreboard 模块作为独立硬件，提供分配/释放/查询接口
  scoreboard.io.alloc_req := scheduler.io.dispatch.valid && scheduler.io.dispatch.ready
  scoreboard.io.alloc_warp_id := scheduler.io.dispatch.bits.warpId
  scoreboard.io.alloc_reg_id := scheduler.io.dispatch.bits.microOp.rd

  // Scoreboard release 来自 RCB
  scoreboard.io.release_req := rcb.io.o_bar_rel.valid
  scoreboard.io.release_warp_id := rcb.io.o_bar_rel.bits.warp_id
  scoreboard.io.release_reg_id := rcb.io.o_bar_rel.bits.rd_index

  // Hazard 查询 (简化: 使用当前调度 warp)
  scoreboard.io.hazard_query_warp_id := scheduler.io.dispatch.bits.warpId
  scoreboard.io.hazard_query_rs1 := scheduler.io.dispatch.bits.microOp.rs1
  scoreboard.io.hazard_query_rs2 := scheduler.io.dispatch.bits.microOp.rs2
  scoreboard.io.hazard_query_rd := scheduler.io.dispatch.bits.microOp.rd

  // Feature 3.2: TID 释放端口 (暂时禁用，使用传统 release 路径)
  scoreboard.io.release_tid := 0.U
  scoreboard.io.release_tid_valid := false.B

  // Scheduler release 也连接 RCB
  scheduler.io.releaseReq.valid := rcb.io.o_bar_rel.valid
  scheduler.io.releaseReq.bits.warpId := rcb.io.o_bar_rel.bits.warp_id
  scheduler.io.releaseReq.bits.regId := rcb.io.o_bar_rel.bits.rd_index

  // ── 10. WarpScheduler -> OperandCollector ──
  collector.io.dispatch <> scheduler.io.dispatch
  collector.io.issue.ready := true.B

  // ── 11. Collector <-> vGPR ──
  for (i <- 0 until cfg.numBanks) {
    // Read requests
    vgpr.io.readReqs(i).valid := collector.io.rfReadReq(i).valid
    vgpr.io.readReqs(i).bits.wid := collector.io.rfReadReq(i).bits.wid
    vgpr.io.readReqs(i).bits.regId := collector.io.rfReadReq(i).bits.regId

    collector.io.rfReadResp(i).valid := RegNext(collector.io.rfReadReq(i).valid)
    collector.io.rfReadResp(i).bits.data := vgpr.io.readData(i)

    // Write requests (from RCB)
    vgpr.io.writeReqs(i).valid := rcb.io.o_bk_write(i).valid
    vgpr.io.writeReqs(i).bits.wid := rcb.io.o_bk_write(i).bits.wid
    vgpr.io.writeReqs(i).bits.regId := rcb.io.o_bk_write(i).bits.regId
    vgpr.io.writeReqs(i).bits.data := rcb.io.o_bk_write(i).bits.data
    vgpr.io.writeReqs(i).bits.mask := rcb.io.o_bk_write(i).bits.mask.asBools

    rcb.io.o_bk_write(i).ready := true.B
  }

  // ── 12. Collector -> vALU -> RCB ──
  valu.io.in <> collector.io.issue
  rcb.io.i_valu_res <> valu.io.out

  // RCB 未使用的输入
  rcb.io.i_lsu_res.valid := false.B
  rcb.io.i_lsu_res.bits := DontCare
  rcb.io.i_a2v_res.valid := false.B
  rcb.io.i_a2v_res.bits := DontCare
  rcb.io.bypass_query.valid := false.B
  rcb.io.bypass_query.bits := DontCare

  // ── 13. L0ICache Fill (来自 ROC) ──
  icache.io.roc_resp.fill_valid := io.roc_icache_fill_valid
  icache.io.roc_resp.fill_addr := io.roc_icache_fill_addr
  icache.io.roc_resp.fill_data := io.roc_icache_fill_data
  icache.io.bs_req.preload_valid := false.B
  icache.io.bs_req.preload_addr := 0.U

  // ── 14. L0KCache Fill (来自 ROC) ──
  kcache.io.roc_fill.valid := io.roc_kcache_fill_valid
  kcache.io.roc_fill.addr := io.roc_kcache_fill_addr
  kcache.io.roc_fill.data := io.roc_kcache_fill_data

  // ── 15. pGPR / uGPR (暂未使用) ──
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

  // ── 16. SM 级接口输出 ──
  // Warp Exit / Block Done
  io.warp_exit_valid := scheduler.io.wsWarpExitValid
  io.warp_exit_id := scheduler.io.wsWarpExitId
  io.blksch_block_done := scheduler.io.wsBlockDone

  // L0 I-Cache ROC Request
  io.icache_roc_req_valid := icache.io.roc_req.req_valid
  io.icache_roc_req_addr := icache.io.roc_req.req_addr

  // L0 K-Cache ROC Request
  io.kcache_roc_req_valid := kcache.io.roc_req.valid
  io.kcache_roc_req_addr := kcache.io.roc_req.addr

  // LSU_Hub 接口 (Phase 3 预留, 当前置零)
  io.lsu_req_valid := false.B
  io.lsu_resp_ready := false.B

  // MMA 结果路由 (Phase 3 预留, 当前置零)
  io.mma_result_ready := false.B

  // K-Cache Miss/Wakeup 状态 (由外部 Block Scheduler 管理)
  // 当前简化: 无 K-Cache miss
  io.kcache_miss_wait_mask := 0.U
  io.kcache_fill_ack_mask := 0.U
}
