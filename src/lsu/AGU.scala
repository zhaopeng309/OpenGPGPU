package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

class AGUReq(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(3.W) // LSUOpType
}

class AGUResp(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(3.W)
  // 每个线程生成的物理/虚拟地址
  val addr = Vec(config.threadPerWarp, UInt(64.W))
  val valid_mask = UInt(config.threadPerWarp.W)
}

/**
 * Address Generation Unit (AGU)
 * 负责为 32 个线程生成独立的访存地址
 */
class AGU(implicit config: CollectorConfig, lsuCfg: LSUConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new AGUReq()))
    val out = Decoupled(new AGUResp())
  })

  // 简单的流水线寄存器
  val valid_reg = RegInit(false.B)
  val req_reg = Reg(new AGUReq())
  val addr_reg = Reg(Vec(config.threadPerWarp, UInt(64.W)))
  
  io.in.ready := !valid_reg || io.out.ready

  when(io.in.valid && io.in.ready) {
    valid_reg := true.B
    req_reg := io.in.bits
    
    // 生成每个线程的地址 (Base + Offset)
    // 简化实现: src1Data 为 Base (32-bit), src2Data 为 Offset (32-bit)
    for (i <- 0 until config.threadPerWarp) {
      val base = io.in.bits.op.src1Data(i)
      val offset = io.in.bits.op.src2Data(i)
      addr_reg(i) := base + offset
    }
  }.elsewhen(io.out.ready) {
    valid_reg := false.B
  }

  io.out.valid := valid_reg
  io.out.bits.op := req_reg.op
  io.out.bits.op_type := req_reg.op_type
  io.out.bits.addr := addr_reg
  io.out.bits.valid_mask := req_reg.op.activeMask
}
