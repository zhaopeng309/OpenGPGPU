package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

/**
 * Address Request Unit (ARU)
 * 作为 LSU 的前端入口，接收 Ingress 转发的请求，进行初步译码
 */
class ARU(implicit config: CollectorConfig, lsuCfg: LSUConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new OperandBundle()))
    val out = Decoupled(new AGUReq())
  })

  val valid_reg = RegInit(false.B)
  val op_reg = Reg(new OperandBundle())
  val op_type_reg = Reg(UInt(3.W))

  io.in.ready := !valid_reg || io.out.ready

  when(io.in.valid && io.in.ready) {
    valid_reg := true.B
    op_reg := io.in.bits
    
    // 译码操作类型
    val opcode = io.in.bits.opcode
    val is_stg = opcode === 0x0E.U
    val is_ldc = opcode === 0x0C.U
    
    op_type_reg := Mux(is_stg, LSUOpType.STG,
                   Mux(is_ldc, LSUOpType.LDC, LSUOpType.LDG))
  }.elsewhen(io.out.ready) {
    valid_reg := false.B
  }

  io.out.valid := valid_reg
  io.out.bits.op := op_reg
  io.out.bits.op_type := op_type_reg
}
