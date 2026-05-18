package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

/**
 * 内存屏障作用域
 */
object MemScope {
  val CTA = 0.U(2.W) // .cta: 线程块内可见
  val GPU = 1.U(2.W) // .gpu: 整个 GPU 可见
  val SYS = 2.U(2.W) // .sys: 系统级可见
}

/**
 * MOU 请求包
 */
class MOUReq(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(3.W)
  val scope = UInt(2.W) // MemScope
  val is_barrier = Bool() // 是否为屏障指令
  val barrier_id = UInt(12.W)
}

/**
 * MOU 响应包
 */
class MOUResp(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(3.W)
  val barrier_passed = Bool()
}

/**
 * Memory Ordering Unit (MOU)
 *
 * 按照 .cta / .gpu / .sys 不同作用域（Scope）建立内存屏障和排序等待逻辑。
 *
 * 功能:
 * 1. 跟踪所有未完成的 LSU 请求
 * 2. 当遇到内存屏障指令时，根据作用域等待所有相关请求完成
 * 3. 确保屏障前的所有访存操作在屏障后的访存操作之前完成
 */
class MOU(implicit config: CollectorConfig, lsuCfg: LSUConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new MOUReq()))
    val out = Decoupled(new MOUResp())

    // 来自 MRQ 的待处理请求计数
    val mrq_pending = Input(UInt(log2Ceil(MRQConfig().numEntries + 1).W))
    val mrq_busy = Input(Bool())

    // 屏障完成通知（给 Warp Scheduler）
    val barrier_done = Output(Bool())
    val barrier_warp_id = Output(UInt(5.W))
  })

  // ── 状态机 ──
  object State extends ChiselEnum {
    val sIdle, sWaitDrain, sBarrierComplete = Value
  }
  import State._

  val state = RegInit(sIdle)
  val req_reg = Reg(new MOUReq())

  // 屏障跟踪
  val barrier_active = RegInit(false.B)
  val barrier_scope = RegInit(0.U(2.W))
  val barrier_id_reg = RegInit(0.U(12.W))
  val barrier_warp = RegInit(0.U(5.W))

  // 不同作用域的等待条件
  val cta_drain_complete = !io.mrq_busy && io.mrq_pending === 0.U
  val gpu_drain_complete = !io.mrq_busy && io.mrq_pending === 0.U // GPU 级需要更复杂的全局跟踪
  val sys_drain_complete = !io.mrq_busy && io.mrq_pending === 0.U // 系统级需要全局 fence

  val drain_complete = MuxLookup(barrier_scope, cta_drain_complete)(Seq(
    MemScope.CTA -> cta_drain_complete,
    MemScope.GPU -> gpu_drain_complete,
    MemScope.SYS -> sys_drain_complete
  ))

  // ── 输入握手 ──
  io.in.ready := state === sIdle && !barrier_active

  when(io.in.valid && io.in.ready) {
    req_reg := io.in.bits

    when(io.in.bits.is_barrier) {
      // 内存屏障指令：等待所有未完成请求完成
      barrier_active := true.B
      barrier_scope := io.in.bits.scope
      barrier_id_reg := io.in.bits.barrier_id
      barrier_warp := io.in.bits.op.wid
      state := sWaitDrain
    }.otherwise {
      // 普通 LSU 请求：直接通过
      state := sBarrierComplete
    }
  }

  // ── 等待排空 ──
  when(state === sWaitDrain) {
    when(drain_complete) {
      state := sBarrierComplete
    }
  }

  // ── 屏障完成 ──
  io.out.valid := state === sBarrierComplete
  io.out.bits.op := req_reg.op
  io.out.bits.op_type := req_reg.op_type
  io.out.bits.barrier_passed := barrier_active

  when(io.out.valid && io.out.ready) {
    barrier_active := false.B
    state := sIdle
  }

  // ── 屏障完成通知 ──
  io.barrier_done := state === sBarrierComplete && io.out.ready
  io.barrier_warp_id := barrier_warp
}
