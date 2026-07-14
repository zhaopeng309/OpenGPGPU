package tensorcore

import chisel3._
import chisel3.util._

class TC_OperandBuffer extends Module {
  val io = IO(new Bundle {
    val smem_data        = Input(Vec(16, UInt(32.W)))
    val smem_valid       = Input(Bool())
    val precision        = Input(UInt(2.W))
    val is_A             = Input(Bool())
    
    val write_buffer_sel = Input(Bool())
    val read_buffer_sel  = Input(Bool())
    val clear            = Input(Bool())
    
    val bcast_out        = Output(Vec(16, UInt(32.W)))
    val bcast_valid      = Output(Bool())
  })

  // Two buffers (Double Buffering)
  val buffer_0 = RegInit(VecInit(Seq.fill(16)(0.U(32.W))))
  val buffer_1 = RegInit(VecInit(Seq.fill(16)(0.U(32.W))))
  val valid_0  = RegInit(false.B)
  val valid_1  = RegInit(false.B)

  // Write logic
  when (io.clear) {
    valid_0 := false.B
    valid_1 := false.B
  } .elsewhen (io.smem_valid) {
    when (!io.write_buffer_sel) {
      buffer_0 := io.smem_data
      valid_0  := true.B
    } .otherwise {
      buffer_1 := io.smem_data
      valid_1  := true.B
    }
  }

  // Read/Bypass logic
  val read_data = Mux(io.read_buffer_sel, buffer_1, buffer_0)
  val read_valid = Mux(io.read_buffer_sel, valid_1, valid_0)

  // If read and write point to the same buffer, we bypass combinationally to match the original single-buffered behavior
  val is_same_buffer = (io.read_buffer_sel === io.write_buffer_sel)
  
  io.bcast_out := Mux(is_same_buffer && io.smem_valid, io.smem_data, read_data)
  io.bcast_valid := Mux(is_same_buffer && io.smem_valid, true.B, read_valid)
}
