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
import opengpgpu.lsu.{LSU, LSUConfig}
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
class SMInterface(implicit cfg: SMSPConfig, ulmCfg: opengpgpu.ulm.ULMConfig) extends Bundle {
  // === Block Scheduler 接口 (Decoupled WarpInitBundle) ===
  val warp_init_valid = Input(Bool())
  val warp_init_ready = Output(Bool())
  val warp_init_bits  = Input(new blksch.WarpInitBundle())
  val warp_exit_valid = Output(Bool())
  val warp_exit_id = Output(UInt(log2Ceil(cfg.numWarps).W))
  val warp_exit_block_id = Output(UInt(8.W))
  val blksch_active_mask = Input(UInt(32.W))
  val blksch_bar_id = Input(UInt(4.W))
  val blksch_block_done = Output(Bool())
  // v2.0: 新增字段，来自 Block Scheduler 的 WarpInitBundle (已包含在 WarpInitBundle 中)

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

  // === ULM 接口 ===
  val ulm_req = Decoupled(new opengpgpu.ulm.ULMRequest())
  val ulm_resp = Flipped(Valid(new opengpgpu.ulm.ULMResponse()))

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
  // ==========================================
  // 隐式配置参数
  // ==========================================
  implicit val ulmCfg: opengpgpu.ulm.ULMConfig = opengpgpu.ulm.ULMConfig()

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

  implicit val lsuCfg: LSUConfig = LSUConfig(
    numSmsp = 1,  // 每个 SMSP 内部只有一个 LSU 执行单元
    numWarps = cfg.numWarps,
    threadPerWarp = cfg.threadPerWarp,
    vGPRWidth = cfg.vGPRWidth
  )

  val io = IO(new SMInterface())

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

  // --- 执行: LSU (宏流水线: ARU -> AGU -> ACU -> MRQ -> MOU -> DRU) ---
  val lsu = Module(new LSU())

  // --- 写回: RCB ---
  val rcb = Module(new RCB())

  // ==========================================
  // 连接逻辑
  // ==========================================

  // ── 1. Block Scheduler -> IFU (Warp Init via WarpInitBundle) ──
  // 从 WarpInitBundle 中提取 warp_init_id 和 pc
  val warp_init_bits = io.warp_init_bits
  ifu.io.warp_init_valid := io.warp_init_valid
  ifu.io.warp_init_id := warp_init_bits.warp_id_in_sm
  ifu.io.warp_init_pc := Cat(warp_init_bits.pc, 0.U(4.W))  // 恢复完整 PC (压缩 PC << 4)
  ifu.io.warp_exit_valid := false.B
  ifu.io.warp_exit_id := 0.U
  ifu.io.flush_valid := false.B
  ifu.io.flush_warp_id := 0.U
  ifu.io.flush_target_pc := 0.U

  // warp_init_ready: IFU 始终可以接收新 Warp (简化)
  io.warp_init_ready := true.B

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
  scheduler.io.allocWarpId := warp_init_bits.warp_id_in_sm
  scheduler.io.blkschActiveMask := io.blksch_active_mask
  scheduler.io.blkschBarId := io.blksch_bar_id
  // v2.0: 从 WarpInitBundle 传递 Mode_Register 和 TMA_Descriptor_Base
  scheduler.io.blkschModeRegister := warp_init_bits.mode_register
  scheduler.io.blkschTmaDescBase  := warp_init_bits.tma_desc_base
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

  // ── 12. Collector -> vALU/LSU (Opcode-based Routing) ──
  // 根据 opcode 将指令路由到 vALU 或 LSU:
  //   LDG=0x0D, STG=0x0E, LDC=0x0C, ATOM, TMA → LSU
  //   其他 → vALU
  val isLsuOp = (collector.io.issue.bits.opcode === 0x0D.U) ||
                (collector.io.issue.bits.opcode === 0x0C.U) // STG (0x0E) 现在先去 vALU，再由 vALU 送给 LSU bypass

  val isLsuStore = (collector.io.issue.bits.opcode === 0x0E.U)

  // vALU 接收非 LSU 指令，以及 STG/STS 等需要预计算地址的 LSU store 指令
  valu.io.in.valid := collector.io.issue.valid && (!isLsuOp || isLsuStore)
  valu.io.in.bits  := collector.io.issue.bits

  // LSU 接收普通 LSU 指令 (Load)
  // lsu.io.in 是 Vec(lsuCfg.numSmsp, ...)，numSmsp=1 所以用索引 (0)
  lsu.io.in(0).valid := collector.io.issue.valid && isLsuOp && !isLsuStore
  lsu.io.in(0).bits  := collector.io.issue.bits

  // vALU 的 STG bypass 路由到 LSU 的 valu_bypass
  lsu.io.valu_bypass(0) <> valu.io.lsu_out

  // collector.io.issue.ready: 当 vALU 或 LSU 任一可接收时
  collector.io.issue.ready := Mux(isLsuOp && !isLsuStore, lsu.io.in(0).ready, valu.io.in.ready)

  // ── 13. vALU -> RCB ──
  rcb.io.i_valu_res <> valu.io.out

  // ── 14. LSU -> RCB ──
  rcb.io.i_lsu_res <> lsu.io.out
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
  // warp_exit_block_id 来自 WarpInitBundle 中保存的 block_id (通过 uGPR 或寄存器)
  // 简化: 使用 warp_init_bits 中的 block_id_x 作为 block_id
  io.warp_exit_block_id := warp_init_bits.block_id_x(7, 0)
  io.blksch_block_done := scheduler.io.wsBlockDone

  // L0 I-Cache ROC Request
  io.icache_roc_req_valid := icache.io.roc_req.req_valid
  io.icache_roc_req_addr := icache.io.roc_req.req_addr

  // L0 K-Cache ROC Request
  io.kcache_roc_req_valid := kcache.io.roc_req.valid
  io.kcache_roc_req_addr := kcache.io.roc_req.addr

  // ── 17. ULM 接口 ──
  io.ulm_req <> lsu.io.ulm_req
  lsu.io.ulm_resp <> io.ulm_resp

  // MMA 结果路由 (Phase 3 预留, 当前置零)
  io.mma_result_ready := false.B

  // K-Cache Miss/Wakeup 状态 (由外部 Block Scheduler 管理)
  // 当前简化: 无 K-Cache miss
  io.kcache_miss_wait_mask := 0.U
  io.kcache_fill_ack_mask := 0.U
}
