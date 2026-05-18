package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

/**
 * ATOM 请求包
 */
class ATOMReq(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(4.W)
  val atom_op = UInt(4.W) // AtomOp 子类型
  val addr = UInt(64.W)
  val src_data = UInt(32.W) // 源操作数 (来自线程 0)
  val cmp_data = UInt(32.W) // 比较数据 (CAS 时有效)
}

/**
 * ATOM 响应包
 */
class ATOMResp(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(4.W)
  val result = UInt(32.W) // 原子操作结果
  val old_data = UInt(32.W) // 旧数据 (用于写回)
}

/**
 * 原子操作执行单元 (ATOM)
 *
 * 在 LSU 内增加读-改-写（Read-Modify-Write）的 ALU 计算单元支撑全局原子操作。
 *
 * 支持的原子操作:
 * - ADD:  内存值 + 源值
 * - SUB:  内存值 - 源值
 * - EXCH: 交换 (直接写入源值)
 * - CAS:  比较并交换 (如果内存值 == 比较值, 则写入源值)
 * - AND:  内存值 & 源值
 * - OR:   内存值 | 源值
 * - XOR:  内存值 ^ 源值
 * - MIN:  内存值 < 源值 ? 内存值 : 源值 (有符号)
 * - MAX:  内存值 > 源值 ? 内存值 : 源值 (有符号)
 * - INC:  内存值 + 1 (如果内存值 < 源值)
 * - DEC:  内存值 - 1 (如果内存值 > 0 && 内存值 != 源值)
 */
class ATOM(implicit config: CollectorConfig, lsuCfg: LSUConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ATOMReq()))
    val out = Decoupled(new ATOMResp())

    // 内存读接口 (读取旧值)
    val mem_read = Decoupled(new LSURequest())
    // 内存写接口 (写入新值)
    val mem_write = Decoupled(new LSURequest())
  })

  // ── 状态机 ──
  object State extends ChiselEnum {
    val sIdle, sRead, sCompute, sWrite, sComplete = Value
  }
  import State._

  val state = RegInit(sIdle)
  val req_reg = Reg(new ATOMReq())
  val old_data_reg = Reg(UInt(32.W))
  val result_reg = Reg(UInt(32.W))

  // ── 输入握手 ──
  io.in.ready := state === sIdle

  when(io.in.valid && io.in.ready) {
    req_reg := io.in.bits
    state := sRead
  }

  // ── 读取旧值 ──
  io.mem_read.valid := state === sRead
  io.mem_read.bits.valid := true.B
  io.mem_read.bits.op_type := LSUOpType.LDG
  io.mem_read.bits.atom_op := req_reg.atom_op
  io.mem_read.bits.warp_id := req_reg.op.wid
  io.mem_read.bits.addr := req_reg.addr
  io.mem_read.bits.data := 0.U
  io.mem_read.bits.rd_index := req_reg.op.rd
  io.mem_read.bits.byte_mask := "h000F".U // 只读低 4 字节
  io.mem_read.bits.active_mask := 1.U // 只有线程 0
  io.mem_read.bits.barrier_id := 0.U

  when(io.mem_read.valid && io.mem_read.ready) {
    state := sCompute
  }

  // ── 计算阶段 ──
  // 注意: 在实际实现中，old_data_reg 应由内存响应提供
  // 这里简化: 使用组合逻辑计算
  val mem_val = old_data_reg
  val src_val = req_reg.src_data
  val cmp_val = req_reg.cmp_data

  result_reg := MuxLookup(req_reg.atom_op, mem_val)(Seq(
    AtomOp.ADD  -> (mem_val + src_val),
    AtomOp.SUB  -> (mem_val - src_val),
    AtomOp.EXCH -> src_val,
    AtomOp.CAS  -> Mux(mem_val === cmp_val, src_val, mem_val),
    AtomOp.AND  -> (mem_val & src_val),
    AtomOp.OR   -> (mem_val | src_val),
    AtomOp.XOR  -> (mem_val ^ src_val),
    AtomOp.MIN  -> Mux(mem_val.asSInt < src_val.asSInt, mem_val, src_val),
    AtomOp.MAX  -> Mux(mem_val.asSInt > src_val.asSInt, mem_val, src_val),
    AtomOp.INC  -> Mux(mem_val < src_val, mem_val + 1.U, 0.U),
    AtomOp.DEC  -> Mux(mem_val > 0.U && mem_val =/= src_val, mem_val - 1.U, mem_val)
  ))

  when(state === sCompute) {
    state := sWrite
  }

  // ── 写入新值 ──
  io.mem_write.valid := state === sWrite
  io.mem_write.bits.valid := true.B
  io.mem_write.bits.op_type := LSUOpType.STG
  io.mem_write.bits.atom_op := req_reg.atom_op
  io.mem_write.bits.warp_id := req_reg.op.wid
  io.mem_write.bits.addr := req_reg.addr
  io.mem_write.bits.data := Cat(0.U(96.W), result_reg) // 只写低 32-bit
  io.mem_write.bits.rd_index := req_reg.op.rd
  io.mem_write.bits.byte_mask := "h000F".U
  io.mem_write.bits.active_mask := 1.U
  io.mem_write.bits.barrier_id := 0.U

  when(io.mem_write.valid && io.mem_write.ready) {
    state := sComplete
  }

  // ── 完成阶段 ──
  io.out.valid := state === sComplete
  io.out.bits.op := req_reg.op
  io.out.bits.op_type := LSUOpType.ATOM
  io.out.bits.result := result_reg
  io.out.bits.old_data := old_data_reg

  when(io.out.valid && io.out.ready) {
    state := sIdle
  }
}
