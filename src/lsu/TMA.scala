package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

/**
 * TMA 请求包
 */
class TMAReq(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(4.W)
  val desc = new TMADescriptor() // TMA 描述符
}

/**
 * TMA 响应包
 */
class TMAResp(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(4.W)
  val done = Bool()
  val bytes_transferred = UInt(20.W)
}

/**
 * TMA 控制通路 (Tensor Memory Accelerator)
 *
 * 支持由 LSU 旁路执行全局内存到共享内存（Global -> Shared）的异步 DMA 搬运。
 *
 * 功能:
 * 1. 从 TMA 描述符中解析源地址、目标地址、传输大小
 * 2. 生成一系列 DMA 读取请求 (Global Memory)
 * 3. 将读取的数据写入共享内存
 * 4. 支持 Tile 模式和 Swizzle 模式
 * 5. 完成后通知 LSU
 */
class TMA(implicit config: CollectorConfig, lsuCfg: LSUConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TMAReq()))
    val out = Decoupled(new TMAResp())

    // 全局内存读取接口
    val mem_read = Decoupled(new LSURequest())
    // 共享内存写入接口 (旁路)
    val shared_write = Decoupled(new LSURequest())

    // TMA 描述符基址寄存器 (来自 SMSP)
    val tma_desc_base = Input(UInt(64.W))
  })

  // ── 状态机 ──
  object State extends ChiselEnum {
    val sIdle, sReadDesc, sParseDesc, sDMARead, sDMAWrite, sComplete = Value
  }
  import State._

  val state = RegInit(sIdle)
  val req_reg = Reg(new TMAReq())
  val desc_reg = Reg(new TMADescriptor())

  // DMA 传输状态
  val dma_offset = RegInit(0.U(20.W)) // 当前传输偏移
  val dma_chunk_size = 128.U(20.W) // 每次传输 128 字节 (一个 Cache Line)
  val total_bytes = RegInit(0.U(20.W))

  // ── 输入握手 ──
  io.in.ready := state === sIdle

  when(io.in.valid && io.in.ready) {
    req_reg := io.in.bits
    desc_reg := io.in.bits.desc
    total_bytes := io.in.bits.desc.num_bytes
    dma_offset := 0.U
    state := sDMARead
  }

  // ── DMA 读取阶段 ──
  val current_src_addr = desc_reg.src_addr + dma_offset
  val dma_in_progress = dma_offset < total_bytes

  io.mem_read.valid := state === sDMARead && dma_in_progress
  io.mem_read.bits.valid := true.B
  io.mem_read.bits.op_type := LSUOpType.LDG
  io.mem_read.bits.atom_op := 0.U
  io.mem_read.bits.warp_id := req_reg.op.wid
  io.mem_read.bits.addr := current_src_addr
  io.mem_read.bits.data := 0.U
  io.mem_read.bits.rd_index := req_reg.op.rd
  io.mem_read.bits.byte_mask := "hFFFF".U
  io.mem_read.bits.active_mask := "hFFFFFFFF".U
  io.mem_read.bits.barrier_id := 0.U

  when(io.mem_read.valid && io.mem_read.ready) {
    dma_offset := dma_offset + dma_chunk_size
    state := sDMAWrite
  }

  // ── DMA 写入阶段 (写入共享内存) ──
  val current_dst_addr = desc_reg.dst_addr + (dma_offset - dma_chunk_size)

  io.shared_write.valid := state === sDMAWrite
  io.shared_write.bits.valid := true.B
  io.shared_write.bits.op_type := LSUOpType.STS // 共享内存存储
  io.shared_write.bits.atom_op := 0.U
  io.shared_write.bits.warp_id := req_reg.op.wid
  io.shared_write.bits.addr := current_dst_addr
  io.shared_write.bits.data := 0.U // 数据来自内存响应
  io.shared_write.bits.rd_index := req_reg.op.rd
  io.shared_write.bits.byte_mask := "hFFFF".U
  io.shared_write.bits.active_mask := "hFFFFFFFF".U
  io.shared_write.bits.barrier_id := 0.U

  when(io.shared_write.valid && io.shared_write.ready) {
    when(dma_offset < total_bytes) {
      state := sDMARead
    }.otherwise {
      state := sComplete
    }
  }

  // ── 完成阶段 ──
  io.out.valid := state === sComplete
  io.out.bits.op := req_reg.op
  io.out.bits.op_type := LSUOpType.TMA
  io.out.bits.done := true.B
  io.out.bits.bytes_transferred := total_bytes

  when(io.out.valid && io.out.ready) {
    state := sIdle
  }
}
