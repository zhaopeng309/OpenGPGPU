package tensorcore

import chisel3._
import chisel3.util._

// WGMMA Command Bundle
class WGMMACommand extends Bundle {
  val valid        = Bool()
  val mbar_id      = UInt(6.W)
  val precision    = UInt(2.W)
  val k_size       = UInt(8.W)
  val m_size       = UInt(8.W)
  val n_size       = UInt(8.W)
  val base_addr_a  = UInt(16.W)
  val base_addr_b  = UInt(16.W)
  val base_addr_c  = UInt(16.W) // For A2S destination
}

class TensorCore extends Module {
  val io = IO(new Bundle {
    // Warp Scheduler / Instruction issue interface
    val cmd_in = Input(new WGMMACommand())
    val cmd_ready = Output(Bool())

    // Smem memory interface
    val tc_smem_req_valid   = Output(Bool())
    val tc_smem_req_addr_A  = Output(UInt(16.W))
    val tc_smem_req_addr_B  = Output(UInt(16.W))
    val smem_tc_resp_valid  = Input(Bool())
    val smem_tc_data_A      = Input(Vec(16, UInt(32.W))) // 16 elements (horizontal rows/cols depending on packing)
    val smem_tc_data_B      = Input(Vec(16, UInt(32.W)))

    // A2S Smem write interface
    val tc_smem_write_valid = Output(Bool())
    val tc_smem_write_addr  = Output(UInt(16.W))
    val tc_smem_write_data  = Output(Vec(16, UInt(16.W))) // converted to FP16

    // mBarrier Interface
    val epilogue_arrive_val = Output(Bool())
    val epilogue_mbar_id    = Output(UInt(6.W))
    val epilogue_tx_bytes   = Output(UInt(14.W))

    // V2A and A2V Vector register direct access interfaces for testing/gPR transfers
    val v2a_en        = Input(Bool())
    val v2a_depth_sel = Input(UInt(2.W))
    val v2a_row_sel   = Input(UInt(4.W))
    val v2a_data      = Input(Vec(16, UInt(32.W)))

    val a2v_en        = Input(Bool())
    val a2v_depth_sel = Input(UInt(2.W))
    val a2v_row_sel   = Input(UInt(4.W))
    val a2v_data      = Output(Vec(16, UInt(32.W))) // FP32 or packed
  })

  // Command parameter register to decode parameter routing independent of Sequencer FSM
  val active_cmd = Reg(new WGMMACommand())
  when (io.cmd_in.valid && io.cmd_ready) {
    active_cmd := io.cmd_in
  }

  // 1. Instantiate TC_Sequencer
  val u_seq = Module(new TC_Sequencer())
  u_seq.io.cmd_in := io.cmd_in
  io.cmd_ready    := u_seq.io.cmd_ready
  
  io.tc_smem_req_valid  := u_seq.io.tc_smem_req_valid
  io.tc_smem_req_addr_A := u_seq.io.tc_smem_req_addr_A
  io.tc_smem_req_addr_B := u_seq.io.tc_smem_req_addr_B
  u_seq.io.smem_tc_resp_valid := io.smem_tc_resp_valid

  // 2. Instantiate TC_OperandBuffer A
  val u_buf_a = Module(new TC_OperandBuffer())
  u_buf_a.io.smem_data        := io.smem_tc_data_A
  u_buf_a.io.smem_valid       := io.smem_tc_resp_valid
  u_buf_a.io.precision        := Mux(io.v2a_en, TCPrecision.FP32, u_seq.io.mac_precision)
  u_buf_a.io.is_A             := true.B
  u_buf_a.io.write_buffer_sel := u_seq.io.write_buffer_sel
  u_buf_a.io.read_buffer_sel  := u_seq.io.read_buffer_sel
  u_buf_a.io.clear            := u_seq.io.op_buf_clear

  // 3. Instantiate TC_OperandBuffer B
  val u_buf_b = Module(new TC_OperandBuffer())
  u_buf_b.io.smem_data        := io.smem_tc_data_B
  u_buf_b.io.smem_valid       := io.smem_tc_resp_valid
  u_buf_b.io.precision        := Mux(io.v2a_en, TCPrecision.FP32, u_seq.io.mac_precision)
  u_buf_b.io.is_A             := false.B
  u_buf_b.io.write_buffer_sel := u_seq.io.write_buffer_sel
  u_buf_b.io.read_buffer_sel  := u_seq.io.read_buffer_sel
  u_buf_b.io.clear            := u_seq.io.op_buf_clear

  // 4. Instantiate MACArray
  val u_mac_array = Module(new MACArray())
  u_mac_array.io.clear_acc := Mux(io.v2a_en, false.B, u_seq.io.mac_clear_acc)
  u_mac_array.io.mac_en    := Mux(io.v2a_en, true.B, u_seq.io.mac_en)
  u_mac_array.io.depth_sel := Mux(io.v2a_en, io.v2a_depth_sel, Mux(io.a2v_en, io.a2v_depth_sel, u_seq.io.mac_depth_sel))
  u_mac_array.io.precision := Mux(io.v2a_en, TCPrecision.FP32, u_seq.io.mac_precision)

  u_mac_array.io.a_broadcast := Mux(io.v2a_en, io.v2a_data, u_buf_a.io.bcast_out)
  u_mac_array.io.b_broadcast := Mux(io.v2a_en, VecInit(Seq.fill(16)(1.U(32.W))), u_buf_b.io.bcast_out)

  // 5. Instantiate TC_Epilogue
  val u_epi = Module(new TC_Epilogue())
  u_epi.io.epi_start       := u_seq.io.epi_start
  u_seq.io.epi_done        := u_epi.io.epi_done
  u_epi.io.epi_row_counter := u_seq.io.epi_row_counter
  u_epi.io.precision       := Mux(io.v2a_en, TCPrecision.FP32, Mux(io.a2v_en, active_cmd.precision, u_seq.io.mac_precision))
  
  u_epi.io.mbar_id         := active_cmd.mbar_id
  u_epi.io.base_addr_c     := active_cmd.base_addr_c

  u_epi.io.mac_row_out            := u_mac_array.io.mac_row_out
  u_mac_array.io.epilogue_row_sel := u_epi.io.epilogue_row_sel

  io.tc_smem_write_valid := u_epi.io.tc_smem_write_valid
  io.tc_smem_write_addr  := u_epi.io.tc_smem_write_addr
  io.tc_smem_write_data  := u_epi.io.tc_smem_write_data

  io.epilogue_arrive_val := u_epi.io.epilogue_arrive_val
  io.epilogue_mbar_id    := u_epi.io.epilogue_mbar_id
  io.epilogue_tx_bytes   := u_epi.io.epilogue_tx_bytes

  u_epi.io.a2v_en      := io.a2v_en
  u_epi.io.a2v_row_sel := io.a2v_row_sel
  io.a2v_data          := u_epi.io.a2v_data_out
}
