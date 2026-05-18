package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

/**
 * Ingress 信用仲裁器
 *
 * 负责将 4 个 SMSP 阵列传来的请求通过 Credit（信用）机制平滑仲裁并喂给全局共享的 ARU。
 * 同时为 vALU 开辟 bypass 通道，将其 STG/STS 地址计算结果旁路 AGU 直接提供给 ACU 及 SDQ。
 *
 * 功能:
 * 1. 每个 SMSP 维护一个信用计数器
 * 2. 使用 Round-Robin 仲裁选择请求
 * 3. 信用不足时暂停发送
 * 4. 当 ARU 处理完一个请求后返还信用
 * 5. vALU bypass 通道直达 ACU 和 SDQ
 */
class Ingress(implicit config: CollectorConfig, lsuCfg: LSUConfig) extends Module {
  val io = IO(new Bundle {
    // === SMSP 接口 (请求) ===
    val smsp_req_valid = Input(Vec(lsuCfg.numSmsp, Bool()))
    val smsp_req_ready = Output(Vec(lsuCfg.numSmsp, Bool()))
    val smsp_req_bits  = Input(Vec(lsuCfg.numSmsp, new OperandBundle()))

    // === vALU Bypass 接口 (用于 STG/STS) ===
    val valu_bypass_valid = Input(Vec(lsuCfg.numSmsp, Bool()))
    val valu_bypass_ready = Output(Vec(lsuCfg.numSmsp, Bool()))
    val valu_bypass_bits  = Input(Vec(lsuCfg.numSmsp, new OperandBundle()))

    // === 输出到 ARU ===
    val out = Decoupled(new OperandBundle())

    // === 输出到 Bypass 目标 (ACU, SDQ) ===
    val bypass_out = Decoupled(new OperandBundle())

    // === 信用返还 (来自 ARU/流水线完成) ===
    val credit_return = Input(Vec(lsuCfg.numSmsp, Bool()))

    // === 状态 ===
    val busy = Output(Bool())
  })

  // ── 信用计数器 ──
  // 每个 SMSP 最多 4 个未完成请求
  val MAX_CREDIT = 4
  val credits = RegInit(VecInit(Seq.fill(lsuCfg.numSmsp)(MAX_CREDIT.U(3.W))))

  // ── 请求队列 (正常 Load) ──
  val smspQueues = Seq.tabulate(lsuCfg.numSmsp) { i =>
    Module(new Queue(new OperandBundle(), 4))
  }

  // ── 请求队列 (Bypass Store) ──
  val bypassQueues = Seq.tabulate(lsuCfg.numSmsp) { i =>
    Module(new Queue(new OperandBundle(), 4))
  }

  // 连接 SMSP 请求到队列
  for (i <- 0 until lsuCfg.numSmsp) {
    smspQueues(i).io.enq.valid := io.smsp_req_valid(i)
    smspQueues(i).io.enq.bits  := io.smsp_req_bits(i)
    io.smsp_req_ready(i) := smspQueues(i).io.enq.ready && credits(i) > 0.U

    bypassQueues(i).io.enq.valid := io.valu_bypass_valid(i)
    bypassQueues(i).io.enq.bits  := io.valu_bypass_bits(i)
    io.valu_bypass_ready(i) := bypassQueues(i).io.enq.ready && credits(i) > 0.U
  }

  // ── 信用管理 ──
  for (i <- 0 until lsuCfg.numSmsp) {
    // 发送请求时消耗信用 (可以是 normal 或 bypass，二选一发送)
    val sent_normal = smspQueues(i).io.deq.valid && smspQueues(i).io.deq.ready
    val sent_bypass = bypassQueues(i).io.deq.valid && bypassQueues(i).io.deq.ready
    val sent = sent_normal || sent_bypass
    
    // 返还信用
    val returned = io.credit_return(i)

    when(sent && !returned) {
      credits(i) := credits(i) - 1.U
    }.elsewhen(!sent && returned) {
      credits(i) := credits(i) + 1.U
    }.elsewhen(sent && returned) {
      // 同时发送和返还，信用不变
    }
  }

  // ── Round-Robin 仲裁 (正常 Load) ──
  val arb_round = RegInit(0.U(log2Ceil(lsuCfg.numSmsp).W))
  val queueValids = VecInit(smspQueues.map(_.io.deq.valid))
  val anyValid = queueValids.reduce(_ || _)

  val arbMask = Wire(Vec(lsuCfg.numSmsp, Bool()))
  for (i <- 0 until lsuCfg.numSmsp) {
    val idx = (arb_round + i.U) % lsuCfg.numSmsp.U
    arbMask(i) := queueValids(idx)
  }

  val selIdx = PriorityEncoder(arbMask)
  val selSmsp = (arb_round + selIdx) % lsuCfg.numSmsp.U

  io.out.valid := anyValid
  val defaultBits = smspQueues(0).io.deq.bits
  io.out.bits := MuxCase(defaultBits, smspQueues.zipWithIndex.map { case (q, i) =>
    (selSmsp === i.U) -> q.io.deq.bits
  })

  for (i <- 0 until lsuCfg.numSmsp) {
    smspQueues(i).io.deq.ready := io.out.ready && selSmsp === i.U
  }

  when(io.out.valid && io.out.ready) {
    arb_round := (selSmsp + 1.U) % lsuCfg.numSmsp.U
  }

  // ── Round-Robin 仲裁 (Bypass Store) ──
  val bypass_arb_round = RegInit(0.U(log2Ceil(lsuCfg.numSmsp).W))
  val bypassValids = VecInit(bypassQueues.map(_.io.deq.valid))
  val anyBypassValid = bypassValids.reduce(_ || _)

  val bypassArbMask = Wire(Vec(lsuCfg.numSmsp, Bool()))
  for (i <- 0 until lsuCfg.numSmsp) {
    val idx = (bypass_arb_round + i.U) % lsuCfg.numSmsp.U
    bypassArbMask(i) := bypassValids(idx)
  }

  val selBypassIdx = PriorityEncoder(bypassArbMask)
  val selBypassSmsp = (bypass_arb_round + selBypassIdx) % lsuCfg.numSmsp.U

  io.bypass_out.valid := anyBypassValid
  val defaultBypassBits = bypassQueues(0).io.deq.bits
  io.bypass_out.bits := MuxCase(defaultBypassBits, bypassQueues.zipWithIndex.map { case (q, i) =>
    (selBypassSmsp === i.U) -> q.io.deq.bits
  })

  for (i <- 0 until lsuCfg.numSmsp) {
    bypassQueues(i).io.deq.ready := io.bypass_out.ready && selBypassSmsp === i.U
  }

  when(io.bypass_out.valid && io.bypass_out.ready) {
    bypass_arb_round := (selBypassSmsp + 1.U) % lsuCfg.numSmsp.U
  }

  // ── 状态输出 ──
  io.busy := anyValid || anyBypassValid
}

