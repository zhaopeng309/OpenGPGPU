package opengpgpu.sm

import chisel3._
import chisel3.util._

import blksch.{BlockScheduler, BlockDescriptor, WarpInitBundle, BSConfig}
import opengpgpu.smsp.{SMSP, SMSPConfig, SMInterface}
import opengpgpu.rbmu._
import opengpgpu.lsu.{LSUConfig, LSURequest, LSUResponse}
import opengpgpu.ulm.{ULM, ULMConfig, ULMRequest, ULMResponse, ULMCfgBundle, CarveoutMode}

// ==========================================
// SM 顶层 IO 接口
// ==========================================

/**
 * SM 顶层模块的 IO 接口
 *
 * SM 包含以下子系统:
 * - LSU: 加载/存储执行单元 (每个 SMSP 内嵌)
 * - ROC: Read-Only Cache (L1 I-Cache / K-Cache) 汇聚与 Fill 广播
 * - ULM: Unified Local Memory (Shared Memory)
 *
 * Memory (全局内存控制器) 不属于 SM 层级，由上层 NoC 直接连接。
 */
class SMIO(implicit cfg: SMConfig, ulmCfg: ULMConfig) extends Bundle {
  // === ACE RMU 接口 (BD 接收) ===
  val bd_valid = Input(Bool())
  val bd_ready = Output(Bool())
  val bd_bits  = Input(new BlockDescriptor())

  // === Block Done 通知 (给 ACE) ===
  val block_done_valid = Output(Bool())
  val block_done_block_id = Output(UInt(cfg.blockIdWidth.W))

  // === RBMU 环路口 ===
  val rbmu = new RBMURingIO()

  // === ROC (L1 I-Cache / K-Cache) 接口 ===
  // 每个 SMSP 的 I-Cache 和 K-Cache 请求汇聚到 ROC
  val icache_roc_req_valid = Output(Vec(cfg.numSmsp, Bool()))
  val icache_roc_req_addr  = Output(Vec(cfg.numSmsp, UInt(48.W)))
  val kcache_roc_req_valid = Output(Vec(cfg.numSmsp, Bool()))
  val kcache_roc_req_addr  = Output(Vec(cfg.numSmsp, UInt(48.W)))

  // ROC Fill 广播到所有 SMSP
  val roc_icache_fill_valid = Input(Bool())
  val roc_icache_fill_addr  = Input(UInt(48.W))
  val roc_icache_fill_data  = Input(UInt(512.W))
  val roc_kcache_fill_valid = Input(Bool())
  val roc_kcache_fill_addr  = Input(UInt(48.W))
  val roc_kcache_fill_data  = Input(UInt(512.W))

  // === SMSP LSU 接口 (直通上层 NoC/Memory) ===
  val lsu_req_valid = Output(Vec(cfg.numSmsp, Bool()))
  val lsu_req_ready = Input(Vec(cfg.numSmsp, Bool()))
  val lsu_req_bits  = Output(Vec(cfg.numSmsp, new LSURequest()))
  val lsu_resp_valid = Input(Vec(cfg.numSmsp, Bool()))
  val lsu_resp_ready = Output(Vec(cfg.numSmsp, Bool()))
  val lsu_resp_bits  = Input(Vec(cfg.numSmsp, new LSUResponse()))

  // === ULM (Shared Memory) 接口 ===
  // 每个 SMSP 通过独立端口访问 ULM
  val ulm_req_valid = Output(Vec(cfg.numSmsp, Bool()))
  val ulm_req_ready = Input(Vec(cfg.numSmsp, Bool()))
  val ulm_req_bits  = Output(Vec(cfg.numSmsp, new ULMRequest()))
  val ulm_resp_valid = Input(Vec(cfg.numSmsp, Bool()))
  val ulm_resp_ready = Output(Vec(cfg.numSmsp, Bool()))
  val ulm_resp_bits  = Input(Vec(cfg.numSmsp, new ULMResponse()))

  // === MMA (WGMMA) 结果路由 (Phase 4 预留) ===
  val mma_result_valid = Input(Vec(cfg.numSmsp, Bool()))
  val mma_result_ready = Output(Vec(cfg.numSmsp, Bool()))

  // === mBarrier 唤醒通路 (Phase 4 预留) ===
  val mbarrier_wakeup_valid = Input(Vec(cfg.numSmsp, Bool()))
  val mbarrier_wakeup_warp_id = Input(Vec(cfg.numSmsp, UInt(cfg.warpIdWidth.W)))

  // === 资源状态输出 ===
  val busy = Output(Bool())
  val vgpr_available = Output(UInt(cfg.chunkWidth.W))
  val smem_available = Output(UInt(cfg.chunkWidth.W))
  val active_block_count = Output(UInt(cfg.blockIdWidth.W))
}

// ==========================================
// SM 顶层模块
// ==========================================

/**
 * SM (Streaming Multiprocessor) 顶层模块
 *
 * 对应 SM 开发计划:
 * - Phase 1: 顶层框架与接口标准化
 * - Phase 2: Block Scheduler 核心实装
 * - Phase 3: SMSP 阵列集成与 RBMU 环路
 * - Phase 4: 全局资源总线与后端对接
 */
class SM(implicit cfg: SMConfig) extends Module {
  val io = IO(new SMIO())

  // ==========================================
  // 隐式配置参数
  // ==========================================
  implicit val smspCfg: SMSPConfig = SMSPConfig(
    numWarps = cfg.numWarpsPerSmsp,
    numRegs = cfg.numRegsPerSmsp,
    numScoreboardSlots = cfg.numScoreboardSlotsPerSmsp,
    numCUs = cfg.numCUsPerSmsp,
    numBanks = cfg.numBanksPerSmsp,
    threadPerWarp = cfg.threadPerWarp,
    vGPRWidth = cfg.vGPRWidth,
    rcbEntries = cfg.rcbEntriesPerSmsp
  )

  // ==========================================
  // LSU 隐式配置
  // ==========================================
  implicit val lsuCfg: LSUConfig = LSUConfig(
    numSmsp = cfg.numSmsp,
    numWarps = cfg.numWarpsPerSmsp,
    threadPerWarp = cfg.threadPerWarp,
    vGPRWidth = cfg.vGPRWidth
  )

  // ==========================================
  // ULM 隐式配置
  // ==========================================
  implicit val ulmCfg: ULMConfig = ULMConfig(
    totalSizeKB = cfg.ulmSizeChunks * 8 // ulmSizeChunks=16 → 128KB
  )

  // ==========================================
  // Phase 1 & 2: Block Scheduler 实例化
  // ==========================================
  val blockScheduler = Module(new BlockScheduler())

  // ==========================================
  // Phase 3: 4-SMSP 阵列实例化
  // ==========================================
  val smspArray = Seq.fill(cfg.numSmsp) { Module(new SMSP()) }

  // ==========================================
  // Phase 3: RBMU Ring Stop 级联
  // ==========================================

  // SM 全局寄存器 (Target_ID: 0x0)
  val smGlobalRegs = Module(new SMGlobalRegisters(cfg.smId))

  // 每个 SMSP 的寄存器模块 (Target_ID: 0x1-0x4)
  val smspRegs = Seq.tabulate(cfg.numSmsp) { i =>
    Module(new SMSPRegisters(targetID = RBMUAddr.TARGET_SMSP_0.litValue.toInt + i))
  }

  // Ring Stop 级联: 从 io.rbmu.req_in 开始，依次经过每个 Ring Stop
  //
  // 请求环 (Req Ring) - 正向传播:
  //   io.rbmu.req_in -> SM_Global.req_in -> SM_Global.req_out
  //   -> SMSP_0.req_in -> SMSP_0.req_out -> ... -> SMSP_3.req_out -> io.rbmu.req_out
  //
  // 响应环 (Resp Ring) - 反向传播:
  //   io.rbmu.resp_in -> SMSP_3.resp_in -> SMSP_3.resp_out
  //   -> SMSP_2.resp_in -> ... -> SM_Global.resp_in -> SM_Global.resp_out -> io.rbmu.resp_out
  //
  // 注意: io.rbmu 是外部接口 (Flipped)，与 Ring Stop 内部接口方向相同，
  // 因此不能直接用 <> 连接，需要用 := 逐信号赋值。

  // SM Global Ring Stop (第一个 Ring Stop)
  val smGlobalRingStop = Module(new RBMURingStop(RBMUAddr.TARGET_SM_GLOBAL.litValue.toInt))
  // 请求: 外部输入 -> SM_Global Ring Stop 的 req_in
  smGlobalRingStop.io.ring.req_in.valid := io.rbmu.req_in.valid
  smGlobalRingStop.io.ring.req_in.bits  := io.rbmu.req_in.bits
  io.rbmu.req_in.ready := smGlobalRingStop.io.ring.req_in.ready
  // 响应: SM_Global Ring Stop 的 resp_out -> 外部输出
  io.rbmu.resp_out.valid := smGlobalRingStop.io.ring.resp_out.valid
  io.rbmu.resp_out.bits  := smGlobalRingStop.io.ring.resp_out.bits
  smGlobalRingStop.io.ring.resp_out.ready := io.rbmu.resp_out.ready
  // 目标接口
  smGlobalRingStop.io.target <> smGlobalRegs.io.rb

  // SMSP Ring Stops (级联)
  var prevRing = smGlobalRingStop.io.ring
  for (i <- 0 until cfg.numSmsp) {
    val ringStop = Module(new RBMURingStop(RBMUAddr.TARGET_SMSP_0.litValue.toInt + i))
    // 请求环 (正向): prev.req_out (Output) -> current.req_in (Input)
    ringStop.io.ring.req_in <> prevRing.req_out
    // 响应环 (反向): current.resp_out (Output) -> prev.resp_in (Input)
    prevRing.resp_in <> ringStop.io.ring.resp_out
    // 目标接口
    ringStop.io.target <> smspRegs(i).io.rb
    prevRing = ringStop.io.ring
  }

  // 最后一个 Ring Stop 的输出回到 io.rbmu
  // 请求: 最后一个 Ring Stop 的 req_out -> 外部输出
  io.rbmu.req_out.valid := prevRing.req_out.valid
  io.rbmu.req_out.bits  := prevRing.req_out.bits
  prevRing.req_out.ready := io.rbmu.req_out.ready
  // 响应: 外部输入 -> 最后一个 Ring Stop 的 resp_in
  prevRing.resp_in.valid := io.rbmu.resp_in.valid
  prevRing.resp_in.bits  := io.rbmu.resp_in.bits
  io.rbmu.resp_in.ready := prevRing.resp_in.ready

  // ==========================================
  // Phase 1.3: ACE BD 接口连接
  // ==========================================
  blockScheduler.io.bd_valid := io.bd_valid
  blockScheduler.io.bd_bits  := io.bd_bits
  io.bd_ready := blockScheduler.io.bd_ready

  // ==========================================
  // Phase 3.1: Block Scheduler <-> SMSP 连接
  // ==========================================
  for (i <- 0 until cfg.numSmsp) {
    // Warp Init: BS -> SMSP
    blockScheduler.io.smsp(i).init.ready := smspArray(i).io.warp_init_ready
    smspArray(i).io.warp_init_valid := blockScheduler.io.smsp(i).init.valid
    smspArray(i).io.warp_init_bits  := blockScheduler.io.smsp(i).init.bits

    // Warp Exit: SMSP -> BS
    blockScheduler.io.smsp(i).warp_exit_valid := smspArray(i).io.warp_exit_valid
    blockScheduler.io.smsp(i).warp_exit_block_id := smspArray(i).io.warp_exit_block_id
    blockScheduler.io.smsp(i).warp_exit_warp_id  := smspArray(i).io.warp_exit_id

    // Block Scheduler 上下文信号 (默认值，后续由 BS 驱动)
    smspArray(i).io.blksch_active_mask := 1.U(32.W)  // 默认所有线程活跃
    smspArray(i).io.blksch_bar_id := 0.U(4.W)         // 默认 Barrier ID 0
  }

  // ==========================================
  // Phase 3.3: 生命周期闭环
  // ==========================================
  io.block_done_valid := blockScheduler.io.block_done_valid
  io.block_done_block_id := blockScheduler.io.block_done_block_id

  // ==========================================
  // ROC Fill 广播到所有 SMSP
  // ==========================================
  for (i <- 0 until cfg.numSmsp) {
    smspArray(i).io.roc_icache_fill_valid := io.roc_icache_fill_valid
    smspArray(i).io.roc_icache_fill_addr  := io.roc_icache_fill_addr
    smspArray(i).io.roc_icache_fill_data  := io.roc_icache_fill_data
    smspArray(i).io.roc_kcache_fill_valid := io.roc_kcache_fill_valid
    smspArray(i).io.roc_kcache_fill_addr  := io.roc_kcache_fill_addr
    smspArray(i).io.roc_kcache_fill_data  := io.roc_kcache_fill_data
  }

  // ==========================================
  // ROC 请求汇聚
  // ==========================================
  for (i <- 0 until cfg.numSmsp) {
    io.icache_roc_req_valid(i) := smspArray(i).io.icache_roc_req_valid
    io.icache_roc_req_addr(i)  := smspArray(i).io.icache_roc_req_addr
    io.kcache_roc_req_valid(i) := smspArray(i).io.kcache_roc_req_valid
    io.kcache_roc_req_addr(i)  := smspArray(i).io.kcache_roc_req_addr
  }

  // ==========================================
  // SMSP ULM/LSU 接口连接 (直通上层 NoC/Memory)
  // ==========================================
  // ULM 实例化
  val ulm = Module(new ULM())

  // ULM CSR 配置 (默认: 64KB L1D + 64KB Smem)
  ulm.io.cfg_bundle.carveout_sel := CarveoutMode.SMEM_64KB_L1D_64KB
  ulm.io.cfg_bundle.l1d_enable := true.B
  ulm.io.cfg_bundle.smem_base := 0.U
  ulm.io.cfg_bundle.flush_l1d := false.B

  // SMSP 0 内部 LSU 的 ULM 请求直接连接到本地 ULM 模块
  ulm.io.mem_req_valid := smspArray(0).io.ulm_req.valid
  smspArray(0).io.ulm_req.ready := ulm.io.mem_req_ready
  ulm.io.mem_req_bits := smspArray(0).io.ulm_req.bits

  smspArray(0).io.ulm_resp.valid := ulm.io.mem_resp_valid
  ulm.io.mem_resp_ready := true.B
  smspArray(0).io.ulm_resp.bits := ulm.io.mem_resp_bits

  // 将 SMSP 0 的 ULM 请求/响应信号也透传到 SM 顶层 ULM 接口以便监测和测试
  io.ulm_req_valid(0) := smspArray(0).io.ulm_req.valid
  io.ulm_req_bits(0) := smspArray(0).io.ulm_req.bits
  io.ulm_resp_valid(0) := ulm.io.mem_resp_valid
  io.ulm_resp_bits(0) := ulm.io.mem_resp_bits

  // 其他 SMSP (1 到 3) 的 ULM 接口直通到 SM 顶层，由外部处理
  for (i <- 1 until cfg.numSmsp) {
    io.ulm_req_valid(i) := smspArray(i).io.ulm_req.valid
    smspArray(i).io.ulm_req.ready := io.ulm_req_ready(i)
    io.ulm_req_bits(i) := smspArray(i).io.ulm_req.bits

    smspArray(i).io.ulm_resp.valid := io.ulm_resp_valid(i)
    io.ulm_resp_ready(i) := true.B
    smspArray(i).io.ulm_resp.bits := io.ulm_resp_bits(i)
  }

  // ==========================================
  // ULM L2 Cache Miss (MSHR) 接口直通到 SM 顶层 LSU 接口
  // ==========================================
  io.lsu_req_valid(0) := ulm.io.l2_req.valid
  ulm.io.l2_req.ready := io.lsu_req_ready(0)
  io.lsu_req_bits(0) := ulm.io.l2_req.bits

  ulm.io.l2_resp.valid := io.lsu_resp_valid(0)
  io.lsu_resp_ready(0) := true.B
  ulm.io.l2_resp.bits := io.lsu_resp_bits(0)

  // 其他 SMSP (1 到 3) 的 LSU 接口直通到 SM 顶层，作为后备
  for (i <- 1 until cfg.numSmsp) {
    io.lsu_req_valid(i) := false.B
    io.lsu_req_bits(i) := 0.U.asTypeOf(new LSURequest())
    io.lsu_resp_ready(i) := false.B
  }

  // ==========================================
  // Phase 4: MMA / mBarrier 接口 (预留)
  // ==========================================
  for (i <- 0 until cfg.numSmsp) {
    smspArray(i).io.mma_result_valid := io.mma_result_valid(i)
    io.mma_result_ready(i) := smspArray(i).io.mma_result_ready

    smspArray(i).io.mbarrier_wakeup_valid := io.mbarrier_wakeup_valid(i)
    smspArray(i).io.mbarrier_wakeup_warp_id := io.mbarrier_wakeup_warp_id(i)
  }

  // ==========================================
  // 资源状态输出
  // ==========================================
  io.busy := blockScheduler.io.busy
  io.vgpr_available := blockScheduler.io.vgpr_available
  io.smem_available := blockScheduler.io.smem_available
  io.active_block_count := blockScheduler.io.active_block_count

  // ==========================================
  // SM Global Registers 连接
  // ==========================================
  smGlobalRegs.io.kernel_active := blockScheduler.io.busy
  smGlobalRegs.io.error_ifu_timeout := false.B
  smGlobalRegs.io.error_icache_ecc := false.B
  smGlobalRegs.io.error_decoder_illegal := false.B

  // ==========================================
  // SMSP Registers 连接 (简化: 仅连接基本信号)
  // ==========================================
  for (i <- 0 until cfg.numSmsp) {
    smspRegs(i).io.active_warp_count := 0.U
    smspRegs(i).io.pipeline_stall := false.B
    smspRegs(i).io.ifu_idle := true.B
    smspRegs(i).io.ifu_fetch_active := false.B
    smspRegs(i).io.ifu_icache_miss := false.B
    smspRegs(i).io.pst_valid_mask := 0.U
    smspRegs(i).io.pst_state_bits := 0.U
    smspRegs(i).io.pst_credits_bits := 0.U
    smspRegs(i).io.pst_flush_tag_bits := 0.U
    smspRegs(i).io.pst_inst_id := VecInit(Seq.fill(8)(0.U(64.W)))
    smspRegs(i).io.warp_pc_update := VecInit(Seq.fill(8)(0.U(48.W)))
    smspRegs(i).io.global_sync := false.B
  }
}
