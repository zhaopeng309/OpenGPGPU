package tensorcore

import chisel3._
import chisel3.util._

class TC_Sequencer extends Module {
  val io = IO(new Bundle {
    // Command interface
    val cmd_in    = Input(new WGMMACommand())
    val cmd_ready = Output(Bool())

    // Control and status
    val state_out = Output(UInt(2.W))
    
    // Smem request interface
    val tc_smem_req_valid  = Output(Bool())
    val tc_smem_req_addr_A = Output(UInt(16.W))
    val tc_smem_req_addr_B = Output(UInt(16.W))
    val smem_tc_resp_valid = Input(Bool())

    // MAC control interface
    val mac_clear_acc = Output(Bool())
    val mac_en        = Output(Bool())
    val mac_depth_sel = Output(UInt(2.W))
    val mac_precision = Output(UInt(2.W))

    // OperandBuffer selection signals
    val write_buffer_sel = Output(Bool())
    val read_buffer_sel  = Output(Bool())
    val op_buf_clear     = Output(Bool())

    // Epilogue control interface
    val epi_start        = Output(Bool())
    val epi_done         = Input(Bool())
    val epi_row_counter  = Output(UInt(5.W))
  })

  // States
  val s_IDLE :: s_KLOOP :: s_EPILOGUE :: Nil = Enum(3)
  val state = RegInit(s_IDLE)
  io.state_out := state

  // Instruction registers
  val active_cmd = Reg(new WGMMACommand())
  val k_counter  = RegInit(0.U(8.W))
  val fetch_k_counter = RegInit(0.U(8.W))
  val m_counter  = RegInit(0.U(8.W))
  val epi_row_reg = RegInit(0.U(5.W))

  io.cmd_ready := (state === s_IDLE)

  // Precision dependent k_step
  val k_step = Wire(UInt(8.W))
  k_step := MuxLookup(active_cmd.precision, 1.U(8.W))(Seq(
    TCPrecision.FP32 -> 1.U(8.W),
    TCPrecision.BF16 -> 2.U(8.W),
    TCPrecision.FP8  -> 4.U(8.W)
  ))

  // Swizzle AGU function
  def swizzleAddr(base: UInt, row: UInt, col: UInt): UInt = {
    val swizzled_bank = col(3, 0) ^ row(3, 0)
    base + (row * 16.U) + swizzled_bank
  }

  // Double buffering select signals
  val write_buffer_sel_raw = Wire(Bool())
  val read_buffer_sel_raw  = Wire(Bool())

  // Dynamic bit selection for buffer toggling based on precision
  write_buffer_sel_raw := MuxLookup(active_cmd.precision, fetch_k_counter(0))(Seq(
    TCPrecision.FP32 -> fetch_k_counter(0),
    TCPrecision.BF16 -> fetch_k_counter(1),
    TCPrecision.FP8  -> fetch_k_counter(2)
  ))

  read_buffer_sel_raw := MuxLookup(active_cmd.precision, k_counter(0))(Seq(
    TCPrecision.FP32 -> k_counter(0),
    TCPrecision.BF16 -> k_counter(1),
    TCPrecision.FP8  -> k_counter(2)
  ))

  io.write_buffer_sel := write_buffer_sel_raw
  io.read_buffer_sel  := read_buffer_sel_raw
  io.op_buf_clear     := (state === s_IDLE) && io.cmd_in.valid

  // Default control outputs
  io.tc_smem_req_valid  := false.B
  io.tc_smem_req_addr_A := 0.U
  io.tc_smem_req_addr_B := 0.U

  io.mac_clear_acc := (state === s_IDLE) && io.cmd_in.valid
  io.mac_en        := (state === s_KLOOP) && io.smem_tc_resp_valid
  io.mac_depth_sel := m_counter(1, 0)
  io.mac_precision := active_cmd.precision

  io.epi_start       := false.B
  io.epi_row_counter := epi_row_reg

  switch (state) {
    is (s_IDLE) {
      when (io.cmd_in.valid) {
        active_cmd      := io.cmd_in
        k_counter       := 0.U
        fetch_k_counter := 0.U
        m_counter       := 0.U
        epi_row_reg     := 0.U
        state           := s_KLOOP
      }
    }

    is (s_KLOOP) {
      // Request next data from Smem if we haven't fetched all K for current Pass
      when (fetch_k_counter < active_cmd.k_size) {
        io.tc_smem_req_valid  := true.B
        io.tc_smem_req_addr_A := swizzleAddr(active_cmd.base_addr_a + m_counter * 256.U, fetch_k_counter, 0.U)
        io.tc_smem_req_addr_B := swizzleAddr(active_cmd.base_addr_b, fetch_k_counter, 0.U)
      }

      when (io.smem_tc_resp_valid) {
        when (fetch_k_counter < active_cmd.k_size) {
          fetch_k_counter := fetch_k_counter + k_step
        }

        // Increment k_counter (execution)
        val next_k = k_counter + k_step
        when (next_k >= active_cmd.k_size) {
          k_counter       := 0.U
          fetch_k_counter := 0.U
          val next_m = m_counter + 1.U
          when (next_m >= (active_cmd.m_size / 16.U)) {
            state       := s_EPILOGUE
            epi_row_reg := 0.U
          } .otherwise {
            m_counter := next_m
          }
        } .otherwise {
          k_counter := next_k
        }
      }
    }

    is (s_EPILOGUE) {
      io.epi_start := true.B
      when (io.epi_done) {
        state := s_IDLE
      } .otherwise {
        epi_row_reg := epi_row_reg + 1.U
      }
    }
  }
}
