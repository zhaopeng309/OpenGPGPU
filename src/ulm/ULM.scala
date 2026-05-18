package opengpgpu.ulm

import chisel3._
import chisel3.util._
import opengpgpu.lsu.{LSURequest, LSUResponse, LSUOpType}

/**
 * ULM (Unified Local Memory) 顶层模块
 *
 * 统一局部存储器，将 L1D Cache 和 Shared Memory 合并到同一个 32-Bank SRAM 阵列中。
 * 通过 CSR 寄存器动态切分 L1D 和 Smem 的容量。
 *
 * 核心功能:
 * 1. 地址空间判别 (Space Decoder): 根据地址范围判断访问 L1D 还是 Smem
 * 2. 动态容量切分: 通过 SM_ULM_CFG CSR 控制 L1D/Smem 容量分配
 * 3. 多主设备仲裁: LSU (SIMT)、TMA (块搬运)、TensorCore (矩阵读)
 * 4. 内部路由: 将请求分发到 L1D Controller 或 Smem Controller
 * 5. 物理层仲裁: 3 级优先级仲裁访问 32-Bank SRAM 阵列
 *
 * 接口:
 * - 与 LSU 的 mem_req/mem_resp 接口连接 (LSURequest/LSUResponse)
 * - LSU 是 ULM 的下游，ULM 接收 LSU 的访存请求并返回数据
 *
 * 参考:
 * - ULM_MAS.md 第 3 章: 软件可控的动态切分
 * - ULM_MAS.md 第 7 章: ULM 微架构顶层数据通路图
 */
class ULM(implicit cfg: ULMConfig) extends Module {
  val io = IO(new Bundle {
    // === LSU 接口 (mem_req/mem_resp) ===
    // LSU 是 ULM 的上游，ULM 是 LSU 的下游存储后端
    val mem_req_valid = Input(Bool())
    val mem_req_ready = Output(Bool())
    val mem_req_bits  = Input(new LSURequest())

    val mem_resp_valid = Output(Bool())
    val mem_resp_ready = Input(Bool())
    val mem_resp_bits  = Output(new LSUResponse())

    // === TMA 接口 (块搬运) ===
    val tma_req = Flipped(Decoupled(new ULMRequest()))
    val tma_resp = Decoupled(new ULMResponse())

    // === TensorCore 接口 (WGMMA 盲读) ===
    val tc_req = Flipped(Decoupled(new ULMRequest()))
    val tc_resp = Decoupled(new ULMResponse())

    // === CSR 配置接口 ===
    val cfg_bundle = Input(new ULMCfgBundle())

    // === L2/MemoryController 接口 (MSHR 未命中转发) ===
    val l2_req = Decoupled(new LSURequest())
    val l2_resp = Flipped(Valid(new LSUResponse()))

    // === 状态 ===
    val busy = Output(Bool())
  })

  // ── 地址空间判别 ──
  // 根据地址范围和 CSR 配置判断访问目标
  // Global Memory 地址 -> L1D Cache
  // Shared Memory 地址 -> Smem
  val is_global_addr = Wire(Bool())
  val is_shared_addr = Wire(Bool())

  // 简化的地址空间判别:
  // Global Memory: 地址 >= SMEM_BASE (由 CSR 配置)
  // Shared Memory: 地址 < SMEM_BASE
  is_shared_addr := io.mem_req_bits.addr < io.cfg_bundle.smem_base
  is_global_addr := io.mem_req_bits.addr >= io.cfg_bundle.smem_base

  // ── 将 LSURequest 转换为 ULMRequest ──
  val lsu_to_ulm_req = Wire(new ULMRequest())
  lsu_to_ulm_req.valid := io.mem_req_bits.valid
  lsu_to_ulm_req.addr := io.mem_req_bits.addr
  lsu_to_ulm_req.data := io.mem_req_bits.data
  lsu_to_ulm_req.byte_mask := io.mem_req_bits.byte_mask
  lsu_to_ulm_req.source_id := 0.U // LSU
  lsu_to_ulm_req.warp_id := io.mem_req_bits.warp_id
  lsu_to_ulm_req.rd_index := io.mem_req_bits.rd_index
  lsu_to_ulm_req.active_mask := io.mem_req_bits.active_mask
  lsu_to_ulm_req.barrier_id := io.mem_req_bits.barrier_id

  // 根据 LSU op_type 设置 ULM req_type
  lsu_to_ulm_req.req_type := MuxLookup(io.mem_req_bits.op_type, ULMReqType.L1D_READ)(
    Seq(
      LSUOpType.LDG  -> ULMReqType.L1D_READ,
      LSUOpType.STG  -> ULMReqType.L1D_WRITE,
      LSUOpType.LDS  -> ULMReqType.SMEM_READ,
      LSUOpType.STS  -> ULMReqType.SMEM_WRITE,
      LSUOpType.LDC  -> ULMReqType.L1D_READ,
      LSUOpType.ATOM -> ULMReqType.L1D_READ
    )
  )

  // ── 内部模块例化 ──
  val l1d_ctrl = Module(new L1DController())
  val smem_ctrl = Module(new SmemController())
  val phys_arb = Module(new PhysicalArbiter())
  val data_core = Module(new ULMDataCore())

  // ── 请求分发 (Space Decoder) ──
  // 根据地址空间将请求路由到 L1D Controller 或 Smem Controller

  // L1D Controller 连接
  // 只有 Global 地址且 L1D 使能时才转发到 L1D
  val l1d_enabled = io.cfg_bundle.l1d_enable
  val route_to_l1d = is_global_addr && l1d_enabled && io.mem_req_bits.valid
  val route_to_smem = is_shared_addr && io.mem_req_bits.valid

  l1d_ctrl.io.in.valid := route_to_l1d
  l1d_ctrl.io.in.bits := lsu_to_ulm_req
  l1d_ctrl.io.cfg_bundle := io.cfg_bundle

  // Smem Controller 连接 (LSU 请求)
  smem_ctrl.io.in.valid := route_to_smem
  smem_ctrl.io.in.bits := lsu_to_ulm_req
  smem_ctrl.io.cfg_bundle := io.cfg_bundle

  // TMA 请求直接连接到 Smem Controller
  smem_ctrl.io.tma_in <> io.tma_req

  // ── LSU 请求就绪信号 ──
  // 当 L1D 或 Smem 可以接收请求时，LSU 的 mem_req_ready 有效
  io.mem_req_ready := Mux(route_to_l1d, l1d_ctrl.io.in.ready,
                      Mux(route_to_smem, smem_ctrl.io.in.ready, false.B))

  // ── Physical Arbiter 连接 ──
  // L1D Controller -> Physical Arbiter
  phys_arb.io.l1d_req <> l1d_ctrl.io.phys_req
  l1d_ctrl.io.phys_resp <> phys_arb.io.l1d_resp

  // Smem Controller -> Physical Arbiter
  phys_arb.io.smem_req <> smem_ctrl.io.phys_req
  smem_ctrl.io.phys_resp <> phys_arb.io.smem_resp

  // TensorCore -> Physical Arbiter
  phys_arb.io.tc_req <> io.tc_req

  // Physical Arbiter -> Data Core
  data_core.io.in <> phys_arb.io.phys_req
  phys_arb.io.phys_resp <> data_core.io.out

  // ── 响应聚合 ──
  // 将 L1D 和 Smem 的响应合并为 LSUResponse

  // L1D 响应 -> LSU
  val l1d_out_valid = l1d_ctrl.io.out.valid
  val l1d_out_bits = l1d_ctrl.io.out.bits

  // Smem 响应 -> LSU
  val smem_out_valid = smem_ctrl.io.out.valid
  val smem_out_bits = smem_ctrl.io.out.bits

  // 响应仲裁: L1D 和 Smem 不会同时有响应 (因为请求是互斥的)
  io.mem_resp_valid := l1d_out_valid || smem_out_valid

  // 将 ULMResponse 转换为 LSUResponse
  val ulm_resp = Mux(l1d_out_valid, l1d_out_bits, smem_out_bits)
  io.mem_resp_bits.valid := ulm_resp.valid
  io.mem_resp_bits.warp_id := ulm_resp.warp_id
  io.mem_resp_bits.data := ulm_resp.data
  io.mem_resp_bits.addr := Mux(l1d_out_valid, l1d_ctrl.io.in.bits.addr,
                            Mux(smem_out_valid, smem_ctrl.io.in.bits.addr, 0.U))
  io.mem_resp_bits.rd_index := ulm_resp.rd_index
  io.mem_resp_bits.barrier_id := ulm_resp.barrier_id
  io.mem_resp_bits.error := ulm_resp.error

  // 响应就绪信号
  l1d_ctrl.io.out.ready := io.mem_resp_ready && l1d_out_valid
  smem_ctrl.io.out.ready := io.mem_resp_ready && smem_out_valid

  // ── TMA 响应 ──
  io.tma_resp <> smem_ctrl.io.out

  // ── TensorCore 响应 ──
  io.tc_resp.valid := phys_arb.io.tc_resp.valid
  io.tc_resp.bits.valid := phys_arb.io.tc_resp.bits.valid
  io.tc_resp.bits.data := phys_arb.io.tc_resp.bits.data
  io.tc_resp.bits.source_id := phys_arb.io.tc_resp.bits.source_id
  io.tc_resp.bits.warp_id := 0.U
  io.tc_resp.bits.rd_index := 0.U
  io.tc_resp.bits.active_mask := 0.U
  io.tc_resp.bits.barrier_id := 0.U
  io.tc_resp.bits.error := false.B

  // ── L2 接口 (MSHR 未命中转发) ──
  // L1D Controller 的 MSHR 请求转发到 L2
  io.l2_req.valid := l1d_ctrl.io.mshr_req.valid
  io.l2_req.bits.valid := l1d_ctrl.io.mshr_req.bits.valid
  io.l2_req.bits.op_type := l1d_ctrl.io.mshr_req.bits.req_type
  io.l2_req.bits.warp_id := l1d_ctrl.io.mshr_req.bits.warp_id
  io.l2_req.bits.addr := l1d_ctrl.io.mshr_req.bits.addr
  io.l2_req.bits.data := l1d_ctrl.io.mshr_req.bits.data
  io.l2_req.bits.rd_index := l1d_ctrl.io.mshr_req.bits.rd_index
  io.l2_req.bits.byte_mask := l1d_ctrl.io.mshr_req.bits.byte_mask
  io.l2_req.bits.active_mask := l1d_ctrl.io.mshr_req.bits.active_mask
  io.l2_req.bits.barrier_id := l1d_ctrl.io.mshr_req.bits.barrier_id
  l1d_ctrl.io.mshr_req.ready := io.l2_req.ready

  // L2 响应 -> L1D MSHR
  // 将 LSUResponse 转换为 ULMResponse
  val l2_resp_as_ulm = Wire(new ULMResponse())
  l2_resp_as_ulm.valid := io.l2_resp.bits.valid
  l2_resp_as_ulm.data := io.l2_resp.bits.data
  l2_resp_as_ulm.source_id := 0.U
  l2_resp_as_ulm.warp_id := io.l2_resp.bits.warp_id
  l2_resp_as_ulm.rd_index := io.l2_resp.bits.rd_index
  l2_resp_as_ulm.active_mask := 0.U
  l2_resp_as_ulm.barrier_id := io.l2_resp.bits.barrier_id
  l2_resp_as_ulm.error := io.l2_resp.bits.error

  l1d_ctrl.io.mshr_resp.valid := io.l2_resp.valid
  l1d_ctrl.io.mshr_resp.bits := l2_resp_as_ulm

  // ── 状态输出 ──
  io.busy := l1d_ctrl.io.busy || smem_ctrl.io.busy || phys_arb.io.busy || data_core.io.busy
}
