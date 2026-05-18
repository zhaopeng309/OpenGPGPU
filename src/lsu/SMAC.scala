package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

/**
 * SMAC 请求包
 * 来自 AGU 的共享内存地址
 */
class SMACReq(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(3.W) // LDS (加载共享内存) 或 STS (存储共享内存)
  val addr = Vec(config.threadPerWarp, UInt(64.W))
  val valid_mask = UInt(config.threadPerWarp.W)
  val data = Vec(config.threadPerWarp, UInt(config.vGPRWidth.W)) // 写入数据 (STS)
}

/**
 * SMAC 响应包
 * 经过 Bank 冲突检测和重放调度后的请求
 */
class SMACResp(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(3.W)
  // 重放后的请求序列
  val replay_addr = Vec(32, UInt(64.W))
  val replay_mask = Vec(32, UInt(config.threadPerWarp.W))
  val replay_data = Vec(32, UInt(config.vGPRWidth.W))
  val replay_count = UInt(6.W)
  val replay_cycles = UInt(6.W) // 需要的总周期数
}

/**
 * Shared Memory Access Controller (SMAC)
 *
 * 针对 LDS/STS 共享内存指令，进行 32 bank 冲突检测并生成多周期重放（Replay）请求。
 *
 * 共享内存架构:
 * - 32 个 Bank，每个 Bank 宽度 32-bit (4 字节)
 * - 地址到 Bank 的映射: bank_id = (addr / 4) % 32
 * - 同一 Bank 内最多一个访问，否则发生 Bank 冲突
 *
 * 冲突处理:
 * - 检测到 Bank 冲突后，将冲突的访问分散到多个周期
 * - 每个周期只允许每个 Bank 最多一个访问
 */
class SMAC(implicit config: CollectorConfig, lsuCfg: LSUConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SMACReq()))
    val out = Decoupled(new SMACResp())
  })

  val NUM_BANKS = 32
  val BANK_WIDTH = 4 // 每个 Bank 4 字节

  // ── 状态机 ──
  object State extends ChiselEnum {
    val sIdle, sDetect, sSchedule, sOutput = Value
  }
  import State._

  val state = RegInit(sIdle)
  val req_reg = Reg(new SMACReq())

  // 重放调度结果
  val replay_addrs = Reg(Vec(32, UInt(64.W)))
  val replay_masks = Reg(Vec(32, UInt(config.threadPerWarp.W)))
  val replay_datas = Reg(Vec(32, UInt(config.vGPRWidth.W)))
  val replay_count = RegInit(0.U(6.W))
  val replay_cycles = RegInit(0.U(6.W))
  val output_idx = RegInit(0.U(6.W))

  // ── 输入握手 ──
  io.in.ready := state === sIdle

  when(io.in.valid && io.in.ready) {
    req_reg := io.in.bits
    state := sDetect
  }

  // ── Bank 冲突检测 (组合逻辑) ──
  // 计算每个线程的 Bank ID
  val bank_ids = Wire(Vec(config.threadPerWarp, UInt(log2Ceil(NUM_BANKS).W)))
  val word_addrs = Wire(Vec(config.threadPerWarp, UInt(64.W)))
  for (i <- 0 until config.threadPerWarp) {
    word_addrs(i) := io.in.bits.addr(i) >> log2Ceil(BANK_WIDTH).U
    bank_ids(i) := word_addrs(i)(log2Ceil(NUM_BANKS) - 1, 0)
  }

  // ── 冲突检测阶段 ──
  when(state === sDetect) {
    // 构建 Bank 占用表: 每个 Bank 上当前有哪些线程访问
    val bank_occupancy = Wire(Vec(NUM_BANKS, UInt(config.threadPerWarp.W)))
    for (b <- 0 until NUM_BANKS) {
      var mask = 0.U(config.threadPerWarp.W)
      for (t <- 0 until config.threadPerWarp) {
        when(io.in.bits.valid_mask(t) && bank_ids(t) === b.U) {
          mask = mask | (1.U << t.U)
        }
      }
      bank_occupancy(b) := mask
    }

    // 检测冲突: 每个 Bank 上是否有超过 1 个线程访问
    val bank_conflict = Wire(Vec(NUM_BANKS, Bool()))
    val bank_conflict_count = Wire(Vec(NUM_BANKS, UInt(6.W)))
    for (b <- 0 until NUM_BANKS) {
      bank_conflict_count(b) := PopCount(bank_occupancy(b))
      bank_conflict(b) := bank_conflict_count(b) > 1.U
    }

    val has_conflict = bank_conflict.reduce(_ || _)

    // ── 调度阶段 ──
    // 如果没有冲突，所有线程在一个周期内完成
    // 如果有冲突，需要多周期重放

    // 初始化所有重放槽位
    for (i <- 0 until 32) {
      replay_addrs(i) := 0.U
      replay_masks(i) := 0.U
      replay_datas(i) := 0.U
    }

    var total_cycles = 0
    var slot_idx = 0

    // 贪心调度: 每个周期尽可能多地安排不冲突的访问
    val remaining = RegInit(0.U(config.threadPerWarp.W))
    remaining := io.in.bits.valid_mask

    // 简化实现: 使用循环调度
    // 最多 32 个周期（最坏情况：32 个线程全部冲突）
    for (cycle <- 0 until 32) {
      // 当前周期已占用的 Bank
      val cycle_banks = Wire(Vec(NUM_BANKS, Bool()))
      for (b <- 0 until NUM_BANKS) {
        cycle_banks(b) := false.B
      }

      var cycle_mask = 0.U(config.threadPerWarp.W)

      // 遍历所有线程，安排未冲突的访问
      for (t <- 0 until config.threadPerWarp) {
        val thread_remaining = remaining(t) && !cycle_mask(t)
        val bank_free = !cycle_banks(bank_ids(t))
        val can_schedule = thread_remaining && bank_free

        when(can_schedule) {
          cycle_banks(bank_ids(t)) := true.B
          cycle_mask = cycle_mask | (1.U << t.U)
        }
      }

      // 记录该周期的调度结果
      when(cycle_mask.orR) {
        // 取该周期第一个线程的地址和数据
        val first_in_cycle = PriorityEncoder(cycle_mask)
        replay_addrs(slot_idx) := io.in.bits.addr(first_in_cycle)
        replay_masks(slot_idx) := cycle_mask
        replay_datas(slot_idx) := io.in.bits.data(first_in_cycle)
        slot_idx = slot_idx + 1
        total_cycles = cycle + 1
      }

      // 更新 remaining
      remaining := remaining & ~cycle_mask
    }

    replay_count := slot_idx.U
    replay_cycles := total_cycles.U
    state := sOutput
  }

  // ── 输出阶段 ──
  io.out.valid := state === sOutput && output_idx < replay_count

  when(io.out.valid && io.out.ready) {
    output_idx := output_idx + 1.U
    when(output_idx + 1.U >= replay_count) {
      state := sIdle
    }
  }

  io.out.bits.op := req_reg.op
  io.out.bits.op_type := req_reg.op_type
  io.out.bits.replay_count := replay_count
  io.out.bits.replay_cycles := replay_cycles

  for (i <- 0 until 32) {
    io.out.bits.replay_addr(i) := replay_addrs(i)
    io.out.bits.replay_mask(i) := replay_masks(i)
    io.out.bits.replay_data(i) := replay_datas(i)
  }
}
