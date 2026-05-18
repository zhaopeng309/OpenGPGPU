package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

/**
 * ACU 请求包
 * 来自 AGU 的每个线程独立地址
 */
class ACUReq(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(3.W)
  val addr = Vec(config.threadPerWarp, UInt(64.W))
  val valid_mask = UInt(config.threadPerWarp.W)
}

/**
 * ACU 响应包
 * 合并后的 Cache Line 请求列表
 */
class ACUResp(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(3.W)
  // 合并后的内存请求 (最多 32 个请求，但实际通常 <= 4)
  val coalesced_addr = Vec(32, UInt(64.W))
  val coalesced_mask = Vec(32, UInt(config.threadPerWarp.W))
  // 每个请求对应的 byte 偏移 (用于后续数据路由)
  val coalesced_offset = Vec(32, UInt(7.W))
  val req_count = UInt(6.W)
}

/**
 * PRT (Partition & Routing) 地址合并算法
 *
 * 将 32 个线程的散乱虚拟地址按 Cache Line 边界分组合并。
 * 支持 128B 和 32B 两种 Cache Line 粒度。
 *
 * 算法步骤:
 * 1. 对每个有效线程的地址，计算其 Cache Line 基地址 (addr & ~(lineSize-1))
 * 2. 按 Cache Line 基地址分组
 * 3. 每组生成一个合并请求，包含该 Cache Line 内所有线程的 active mask
 * 4. 输出合并后的请求列表 (req_count 个)
 */
class ACU(implicit config: CollectorConfig, lsuCfg: LSUConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ACUReq()))
    val out = Decoupled(new ACUResp())
  })

  // Cache Line 大小配置: 128B (7-bit offset) 或 32B (5-bit offset)
  val LINE_SIZE_BYTES = 128
  val LINE_OFFSET_BITS = log2Ceil(LINE_SIZE_BYTES) // 7

  // ── 状态机 ──
  object State extends ChiselEnum {
    val sIdle, sCoalesce, sOutput = Value
  }
  import State._

  val state = RegInit(sIdle)
  val req_reg = Reg(new ACUReq())

  // 合并结果寄存器
  val coalesced_addrs = Reg(Vec(32, UInt(64.W)))
  val coalesced_masks = Reg(Vec(32, UInt(config.threadPerWarp.W)))
  val coalesced_offsets = Reg(Vec(32, UInt(7.W)))
  val req_count = RegInit(0.U(6.W))
  val output_idx = RegInit(0.U(6.W))

  // ── 输入握手 ──
  io.in.ready := state === sIdle

  when(io.in.valid && io.in.ready) {
    req_reg := io.in.bits
    state := sCoalesce
  }

  // ── 合并阶段 (组合逻辑) ──
  // 对每个有效线程，计算 Cache Line 基地址
  val line_addrs = Wire(Vec(config.threadPerWarp, UInt(64.W)))
  val line_offsets = Wire(Vec(config.threadPerWarp, UInt(7.W)))
  for (i <- 0 until config.threadPerWarp) {
    line_addrs(i) := (io.in.bits.addr(i) >> LINE_OFFSET_BITS.U) << LINE_OFFSET_BITS.U
    line_offsets(i) := io.in.bits.addr(i)(LINE_OFFSET_BITS - 1, 0)
  }

  // 当进入合并阶段时，执行一次合并
  when(state === sCoalesce) {
    // 使用临时 Wire 进行合并
    val temp_addrs = Wire(Vec(32, UInt(64.W)))
    val temp_masks = Wire(Vec(32, UInt(config.threadPerWarp.W)))
    val temp_offsets = Wire(Vec(32, UInt(7.W)))
    var temp_count = 0

    // 初始化所有临时变量为 0
    for (i <- 0 until 32) {
      temp_addrs(i) := 0.U
      temp_masks(i) := 0.U
      temp_offsets(i) := 0.U
    }

    // 遍历每个线程，按 Cache Line 基地址分组
    val assigned = Wire(Vec(config.threadPerWarp, Bool()))
    for (i <- 0 until config.threadPerWarp) {
      assigned(i) := false.B
    }

    // 外层循环: 查找新的 Cache Line 组
    for (g <- 0 until 32) {
      // 找到第一个未分配的线程
      val first_unassigned = Wire(UInt(6.W))
      // 使用 PriorityEncoder 风格的查找
      val unassigned_vec = Wire(UInt(config.threadPerWarp.W))
      unassigned_vec := 0.U
      for (i <- 0 until config.threadPerWarp) {
        when(io.in.bits.valid_mask(i) && !assigned(i)) {
          unassigned_vec := unassigned_vec.bitSet(i.U, true.B)
        }
      }
      first_unassigned := PriorityEncoder(unassigned_vec)

      val has_unassigned = unassigned_vec.orR
      val group_line_addr = Mux(has_unassigned, line_addrs(first_unassigned), 0.U)

      // 收集该组的所有线程
      var group_mask = 0.U(config.threadPerWarp.W)
      for (i <- 0 until config.threadPerWarp) {
        val in_group = io.in.bits.valid_mask(i) && !assigned(i) &&
                       line_addrs(i) === group_line_addr
        when(in_group) {
          assigned(i) := true.B
        }
        group_mask = group_mask | (io.in.bits.valid_mask(i) && line_addrs(i) === group_line_addr).asUInt << i.U
      }

      // 写入合并结果
      temp_addrs(g) := group_line_addr
      temp_masks(g) := group_mask
      // offset 取组内第一个线程的 offset
      temp_offsets(g) := Mux(has_unassigned, line_offsets(first_unassigned), 0.U)

      // 计数
      when(has_unassigned) {
        temp_count = g + 1
      }
    }

    // 将临时结果写入寄存器
    coalesced_addrs := temp_addrs
    coalesced_masks := temp_masks
    coalesced_offsets := temp_offsets
    req_count := temp_count.U
    output_idx := 0.U
    state := sOutput
  }

  // ── 输出阶段 ──
  io.out.valid := state === sOutput && output_idx < req_count

  when(io.out.valid && io.out.ready) {
    output_idx := output_idx + 1.U
    when(output_idx + 1.U >= req_count) {
      state := sIdle
    }
  }

  // 输出当前请求
  io.out.bits.op := req_reg.op
  io.out.bits.op_type := req_reg.op_type
  io.out.bits.req_count := req_count

  for (i <- 0 until 32) {
    io.out.bits.coalesced_addr(i) := coalesced_addrs(i)
    io.out.bits.coalesced_mask(i) := coalesced_masks(i)
    io.out.bits.coalesced_offset(i) := coalesced_offsets(i)
  }
}
