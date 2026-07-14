package tensorcore

import chisel3._
import chisel3.util._

class TC_Epilogue extends Module {
  val io = IO(new Bundle {
    val epi_start           = Input(Bool())
    val epi_done            = Output(Bool())
    val epi_row_counter     = Input(UInt(5.W))
    val precision           = Input(UInt(2.W))

    // Command info
    val mbar_id             = Input(UInt(6.W))
    val base_addr_c         = Input(UInt(16.W))

    // MAC Array interface
    val mac_row_out         = Input(Vec(16, UInt(32.W)))
    val epilogue_row_sel    = Output(UInt(4.W))

    // Smem write interface
    val tc_smem_write_valid = Output(Bool())
    val tc_smem_write_addr  = Output(UInt(16.W))
    val tc_smem_write_data  = Output(Vec(16, UInt(16.W)))

    // mBarrier Interface
    val epilogue_arrive_val = Output(Bool())
    val epilogue_mbar_id    = Output(UInt(6.W))
    val epilogue_tx_bytes   = Output(UInt(14.W))

    // A2V interfaces
    val a2v_en              = Input(Bool())
    val a2v_row_sel         = Input(UInt(4.W))
    val a2v_data_out        = Output(Vec(16, UInt(32.W)))
  })

  // Select row for MAC Array
  io.epilogue_row_sel := Mux(io.a2v_en, io.a2v_row_sel, io.epi_row_counter(3, 0))

  // A2S: ReLU and FP16 downconversion (truncation)
  val processed_row = Wire(Vec(16, UInt(16.W)))
  for (col <- 0 until 16) {
    val raw_val = io.mac_row_out(col)
    val is_negative = raw_val(31)
    processed_row(col) := Mux(is_negative, 0.U(16.W), raw_val(15, 0))
  }

  io.tc_smem_write_valid := io.epi_start
  io.tc_smem_write_addr  := io.base_addr_c + io.epi_row_counter * 32.U
  io.tc_smem_write_data  := processed_row

  // mBarrier
  io.epilogue_arrive_val := io.epi_start && (io.epi_row_counter === 15.U)
  io.epilogue_mbar_id    := io.mbar_id
  io.epilogue_tx_bytes   := 512.U

  // Done signal
  io.epi_done := io.epi_start && (io.epi_row_counter === 15.U)

  // A2V flight packing
  val beat = RegInit(0.U(1.W))
  when (io.a2v_en) {
    beat := beat + 1.U
  } .otherwise {
    beat := 0.U
  }

  // Convert FP32 to FP8 (lower 8 bits, clip to 0 if negative)
  def f32_to_fp8(x: UInt): UInt = {
    Mux(x(31), 0.U(8.W), x(7, 0))
  }

  val fp8_packed = Wire(Vec(16, UInt(32.W)))
  for (i <- 0 until 16) {
    fp8_packed(i) := 0.U
  }

  when (beat === 0.U) {
    fp8_packed(0) := Cat(f32_to_fp8(io.mac_row_out(3)), f32_to_fp8(io.mac_row_out(2)), f32_to_fp8(io.mac_row_out(1)), f32_to_fp8(io.mac_row_out(0)))
    fp8_packed(1) := Cat(f32_to_fp8(io.mac_row_out(7)), f32_to_fp8(io.mac_row_out(6)), f32_to_fp8(io.mac_row_out(5)), f32_to_fp8(io.mac_row_out(4)))
  } .otherwise {
    fp8_packed(0) := Cat(f32_to_fp8(io.mac_row_out(11)), f32_to_fp8(io.mac_row_out(10)), f32_to_fp8(io.mac_row_out(9)), f32_to_fp8(io.mac_row_out(8)))
    fp8_packed(1) := Cat(f32_to_fp8(io.mac_row_out(15)), f32_to_fp8(io.mac_row_out(14)), f32_to_fp8(io.mac_row_out(13)), f32_to_fp8(io.mac_row_out(12)))
  }

  io.a2v_data_out := Mux(io.precision === TCPrecision.FP8, fp8_packed, io.mac_row_out)
}
