package sim.sm

import chisel3._
import chisel3.util._

import opengpgpu.sm.{SM, SMConfig, SMIO}
import opengpgpu.lsu.{LSURequest, LSUResponse}
import memory.{MemoryRequest, MemoryResponse}
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

    // === MemoryController 接口 ===
    val mem_req  = Decoupled(new MemoryRequest)
    val mem_resp = Flipped(Decoupled(new MemoryResponse))

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
    val smsp0_lsu_req_valid    = Output(Bool())
    val smsp0_lsu_req_addr     = Output(UInt(64.W))

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
  // MemoryController 接口 (通过 LSU 接口直通)
  // ==========================================
  // SM 的 LSU 请求通过 Vec 接口暴露，numSmsp=1 时取第一个
  // 需要将 LSURequest 转换为 MemoryRequest (字段不同)
  io.mem_req.valid := sm.io.lsu_req_valid(0)
  sm.io.lsu_req_ready(0) := io.mem_req.ready
  io.mem_req.bits.addr    := sm.io.lsu_req_bits(0).addr
  io.mem_req.bits.data    := sm.io.lsu_req_bits(0).data
  io.mem_req.bits.size    := 4.U  // 16 bytes
  io.mem_req.bits.isWrite := sm.io.lsu_req_bits(0).op_type === 1.U  // STG
  io.mem_req.bits.mask    := sm.io.lsu_req_bits(0).byte_mask

  // MemoryResponse -> LSUResponse 转换
  sm.io.lsu_resp_valid(0) := io.mem_resp.valid
  io.mem_resp.ready := sm.io.lsu_resp_ready(0)
  sm.io.lsu_resp_bits(0).valid      := io.mem_resp.valid
  sm.io.lsu_resp_bits(0).warp_id    := 0.U
  sm.io.lsu_resp_bits(0).data       := io.mem_resp.bits.data
  sm.io.lsu_resp_bits(0).addr       := 0.U
  sm.io.lsu_resp_bits(0).rd_index   := 0.U
  sm.io.lsu_resp_bits(0).barrier_id := 0.U
  sm.io.lsu_resp_bits(0).error      := io.mem_resp.bits.error

  // ==========================================
  // MMA / mBarrier 接口 (仿真中置零)
  // ==========================================
  for (i <- 0 until cfg.numSmsp) {
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
  io.smsp0_lsu_req_valid    := sm.io.lsu_req_valid(0)
  io.smsp0_lsu_req_addr     := sm.io.lsu_req_bits(0).addr

  // ==========================================
  // RBMU 探针
  // ==========================================
  io.rbmu_req_valid := sm.io.rbmu.req_out.valid
  io.rbmu_resp_valid := sm.io.rbmu.resp_in.valid
}
