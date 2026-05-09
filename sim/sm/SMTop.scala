package sim.sm

import chisel3._
import chisel3.util._

import opengpgpu.sm.{SM, SMConfig, SMIO}
import blksch.{BlockDescriptor, BSConfig}

/**
 * SM 仿真顶层模块
 *
 * 将 SM 模块包装为仿真友好的接口，暴露所有探针信号供 Testbench 捕获。
 * 对应 SM 开发计划 Feature 5.1: 创建 SMTestbench
 */
class SMTop(implicit cfg: SMConfig) extends Module {
  val io = IO(new Bundle {
    // === ACE BD 接口 ===
    val bd_valid = Input(Bool())
    val bd_ready = Output(Bool())
    val bd_bits  = Input(new BlockDescriptor())

    // === Block Done 通知 ===
    val block_done_valid = Output(Bool())
    val block_done_block_id = Output(UInt(cfg.blockIdWidth.W))

    // === ROC Fill 接口 ===
    val roc_icache_fill_valid = Input(Bool())
    val roc_icache_fill_addr  = Input(UInt(48.W))
    val roc_icache_fill_data  = Input(UInt(512.W))
    val roc_kcache_fill_valid = Input(Bool())
    val roc_kcache_fill_addr  = Input(UInt(48.W))
    val roc_kcache_fill_data  = Input(UInt(512.W))

    // === 资源状态探针 ===
    val busy = Output(Bool())
    val vgpr_available = Output(UInt(cfg.chunkWidth.W))
    val smem_available = Output(UInt(cfg.chunkWidth.W))
    val active_block_count = Output(UInt(cfg.blockIdWidth.W))

    // === SMSP 探针 (简化: 仅暴露第一个 SMSP 的关键信号) ===
    val smsp0_warp_init_valid = Output(Bool())
    val smsp0_warp_init_ready = Output(Bool())
    val smsp0_warp_exit_valid = Output(Bool())
    val smsp0_warp_exit_id    = Output(UInt(cfg.warpIdWidth.W))
    val smsp0_icache_req_valid = Output(Bool())
    val smsp0_kcache_req_valid = Output(Bool())

    // === RBMU 环路口 (简化: 仅暴露请求/响应) ===
    val rbmu_req_valid = Output(Bool())
    val rbmu_resp_valid = Output(Bool())
  })

  // ==========================================
  // 实例化 SM 模块
  // ==========================================
  val sm = Module(new SM())

  // ==========================================
  // ACE BD 接口连接
  // ==========================================
  sm.io.bd_valid := io.bd_valid
  sm.io.bd_bits  := io.bd_bits
  io.bd_ready := sm.io.bd_ready

  // ==========================================
  // Block Done 通知
  // ==========================================
  io.block_done_valid := sm.io.block_done_valid
  io.block_done_block_id := sm.io.block_done_block_id

  // ==========================================
  // ROC Fill 接口
  // ==========================================
  sm.io.roc_icache_fill_valid := io.roc_icache_fill_valid
  sm.io.roc_icache_fill_addr  := io.roc_icache_fill_addr
  sm.io.roc_icache_fill_data  := io.roc_icache_fill_data
  sm.io.roc_kcache_fill_valid := io.roc_kcache_fill_valid
  sm.io.roc_kcache_fill_addr  := io.roc_kcache_fill_addr
  sm.io.roc_kcache_fill_data  := io.roc_kcache_fill_data

  // ==========================================
  // LSU / MMA / mBarrier 接口 (仿真中置零)
  // ==========================================
  for (i <- 0 until cfg.numSmsp) {
    sm.io.lsu_req_ready(i) := false.B
    sm.io.lsu_resp_valid(i) := false.B
    sm.io.mma_result_valid(i) := false.B
    sm.io.mbarrier_wakeup_valid(i) := false.B
    sm.io.mbarrier_wakeup_warp_id(i) := 0.U
  }

  // ==========================================
  // RBMU 环路口 (仿真中置零)
  // ==========================================
  // 请求环输入: 外部驱动 (仿真中无请求)
  sm.io.rbmu.req_in.valid := false.B
  sm.io.rbmu.req_in.bits  := DontCare
  // 响应环输入: 外部驱动 (仿真中无响应)
  sm.io.rbmu.resp_in.valid := false.B
  sm.io.rbmu.resp_in.bits  := DontCare
  // 请求环输出 ready: 外部准备接收 (仿真中始终 ready)
  sm.io.rbmu.req_out.ready := true.B
  // 响应环输出 ready: 外部准备接收 (仿真中始终 ready)
  sm.io.rbmu.resp_out.ready := true.B

  // ==========================================
  // 资源状态探针
  // ==========================================
  io.busy := sm.io.busy
  io.vgpr_available := sm.io.vgpr_available
  io.smem_available := sm.io.smem_available
  io.active_block_count := sm.io.active_block_count

  // ==========================================
  // SMSP 探针 (第一个 SMSP)
  // ==========================================
  io.smsp0_warp_init_valid := sm.io.icache_roc_req_valid(0)  // 复用为探针
  io.smsp0_warp_init_ready := sm.io.kcache_roc_req_valid(0)
  io.smsp0_warp_exit_valid := sm.io.lsu_req_valid(0)
  io.smsp0_warp_exit_id    := 0.U
  io.smsp0_icache_req_valid := sm.io.icache_roc_req_valid(0)
  io.smsp0_kcache_req_valid := sm.io.kcache_roc_req_valid(0)

  // ==========================================
  // RBMU 探针
  // ==========================================
  io.rbmu_req_valid := sm.io.rbmu.req_out.valid
  io.rbmu_resp_valid := sm.io.rbmu.resp_in.valid
}
