package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

/**
 * MRQ 条目
 * 跟踪一个 Cache Line 请求的完整生命周期
 */
class MRQEntry(implicit config: CollectorConfig) extends Bundle {
  val valid = Bool()
  val op = new OperandBundle()
  val op_type = UInt(3.W)
  val addr = UInt(64.W)
  val cache_line_addr = UInt(64.W) // 128B 对齐的 Cache Line 地址
  val active_mask = UInt(config.threadPerWarp.W)
  val byte_offset = UInt(7.W) // 在 Cache Line 内的偏移
  val tag = UInt(8.W) // 唯一标签
  val sent = Bool() // 是否已发送到内存
  val resp_received = Bool() // 是否已收到响应
}

/**
 * MRQ 配置
 */
case class MRQConfig(
  numEntries: Int = 8, // MRQ 条目数
  tagWidth: Int = 8
)

/**
 * Memory Request Queue (MRQ)
 *
 * 管理访存长延迟，跟踪发送的多个 Cache Line 的返回状态。
 * 在 ACU 和内存请求端之间插入，支持:
 * - 多个未完成的 Cache Line 请求跟踪
 * - 每个请求的唯一 Tag 分配
 * - 响应到达时根据 Tag 匹配原始请求上下文
 * - 合并写回（所有请求都返回后才通知 DRU）
 *
 * 注: 此模块在 LSU 中扮演延迟隐藏队列的角色，而非 Cache 中的 MSHR。
 *     MSHR (Miss Status Holding Register) 是 Cache 的概念，
 *     而 LSU 不是 Cache，因此命名为 MRQ (Memory Request Queue) 更准确。
 *     此命名与架构文档 LSU_MAS.md 保持一致。
 */
class MRQ(implicit config: CollectorConfig, lsuCfg: LSUConfig) extends Module {
  val io = IO(new Bundle {
    // 从 ACU 接收合并后的请求
    val acu_resp = Flipped(Decoupled(new ACUResp()))

    // 向内存发送请求
    val mem_req = Decoupled(new LSURequest())

    // 从内存接收响应
    val mem_resp = Flipped(Valid(new LSUResponse()))

    // 向 DRU 发送完成的通知
    val dru_notify = Decoupled(new DRUReq())

    // 状态输出
    val busy = Output(Bool())
    val pending_count = Output(UInt(log2Ceil(MRQConfig().numEntries + 1).W))
    val resp_addr = Output(UInt(64.W))
  })

  val mrqCfg = MRQConfig()

  // ── MRQ 条目阵列 ──
  val entries = Reg(Vec(mrqCfg.numEntries, new MRQEntry()))

  // ── Tag 管理 ──
  val next_tag = RegInit(0.U(mrqCfg.tagWidth.W))
  val tag_in_use = RegInit(0.U(mrqCfg.numEntries.W))

  // ── 状态 ──
  val pending_count = RegInit(0.U(log2Ceil(mrqCfg.numEntries + 1).W))
  val total_req_count = RegInit(0.U(6.W)) // ACU 报告的请求总数
  val completed_count = RegInit(0.U(6.W))

  // ── 查找空闲条目 ──
  val free_entries = Wire(UInt(mrqCfg.numEntries.W))
  free_entries := ~Cat(entries.map(_.valid).reverse)

  val first_free = PriorityEncoder(free_entries)
  val has_free = free_entries.orR

  // ── ACU 请求处理 ──
  // ACU 可能产生多个请求，需要逐个分配 MRQ 条目
  val acu_req_idx = RegInit(0.U(6.W))
  val acu_in_flight = RegInit(false.B)
  val acu_op_reg = Reg(new OperandBundle())
  val acu_op_type_reg = Reg(UInt(3.W))

  io.acu_resp.ready := has_free && !acu_in_flight

  // 开始处理 ACU 响应
  when(io.acu_resp.valid && io.acu_resp.ready) {
    acu_in_flight := true.B
    acu_req_idx := 0.U
    acu_op_reg := io.acu_resp.bits.op
    acu_op_type_reg := io.acu_resp.bits.op_type
    total_req_count := io.acu_resp.bits.req_count
    completed_count := 0.U
  }

  // ── 逐个分配 MRQ 条目并发送内存请求 ──
  io.mem_req.valid := false.B
  io.mem_req.bits := DontCare

  when(acu_in_flight) {
    val current_req = acu_req_idx
    val current_addr = Wire(UInt(64.W))
    val current_mask = Wire(UInt(config.threadPerWarp.W))

    // 从 ACUResp 中读取当前请求
    current_addr := 0.U
    current_mask := 0.U
    for (i <- 0 until 32) {
      when(current_req === i.U) {
        current_addr := io.acu_resp.bits.coalesced_addr(i)
        current_mask := io.acu_resp.bits.coalesced_mask(i)
      }
    }

    val has_pending = current_req < total_req_count

    when(has_pending && has_free) {
      // 分配 MRQ 条目
      val entry_idx = first_free
      entries(entry_idx).valid := true.B
      entries(entry_idx).op := acu_op_reg
      entries(entry_idx).op_type := acu_op_type_reg
      entries(entry_idx).addr := current_addr
      entries(entry_idx).cache_line_addr := (current_addr >> 7) << 7
      entries(entry_idx).active_mask := current_mask
      entries(entry_idx).byte_offset := current_addr(6, 0)
      entries(entry_idx).tag := next_tag
      entries(entry_idx).sent := false.B
      entries(entry_idx).resp_received := false.B

      // 更新 Tag
      next_tag := next_tag + 1.U
      pending_count := pending_count + 1.U

      // 发送内存请求
      io.mem_req.valid := true.B
      io.mem_req.bits.valid := true.B
      io.mem_req.bits.op_type := acu_op_type_reg
      io.mem_req.bits.warp_id := acu_op_reg.wid
      io.mem_req.bits.addr := current_addr
      io.mem_req.bits.data := acu_op_reg.src1Data.asUInt
      io.mem_req.bits.rd_index := acu_op_reg.rd
      io.mem_req.bits.byte_mask := current_mask(15, 0)
      io.mem_req.bits.active_mask := current_mask
      io.mem_req.bits.barrier_id := 0.U

      when(io.mem_req.ready) {
        entries(entry_idx).sent := true.B
        acu_req_idx := acu_req_idx + 1.U
      }
    }

    when(!has_pending) {
      acu_in_flight := false.B
    }
  }

  // ── 响应处理 ──
  // 根据 Tag 查找对应的 MRQ 条目
  val resp_tag = io.mem_resp.bits.rd_index // 复用 rd_index 字段传递 Tag
  val resp_match = Wire(Vec(mrqCfg.numEntries, Bool()))
  val resp_match_idx = Wire(UInt(log2Ceil(mrqCfg.numEntries).W))
  val resp_found = Wire(Bool())

  for (i <- 0 until mrqCfg.numEntries) {
    resp_match(i) := entries(i).valid && !entries(i).resp_received &&
                     entries(i).tag === resp_tag
  }

  resp_found := resp_match.reduce(_ || _)
  resp_match_idx := PriorityEncoder(Cat(resp_match.reverse))

  when(io.mem_resp.valid && resp_found) {
    entries(resp_match_idx).resp_received := true.B
    pending_count := pending_count - 1.U
    completed_count := completed_count + 1.U
  }

  // ── DRU 通知 ──
  // 当所有请求都完成时，通知 DRU
  val all_completed = completed_count >= total_req_count && total_req_count > 0.U

  io.dru_notify.valid := all_completed && !acu_in_flight
  io.dru_notify.bits.op := acu_op_reg
  io.dru_notify.bits.op_type := acu_op_type_reg

  when(io.dru_notify.valid && io.dru_notify.ready) {
    // 清除所有已完成的条目
    for (i <- 0 until mrqCfg.numEntries) {
      when(entries(i).valid && entries(i).resp_received) {
        entries(i).valid := false.B
      }
    }
    total_req_count := 0.U
    completed_count := 0.U
  }

  // ── 状态输出 ──
  io.busy := pending_count > 0.U || acu_in_flight
  io.pending_count := pending_count
  io.resp_addr := Mux(io.mem_resp.valid && resp_found, entries(resp_match_idx).addr, 0.U)
}
