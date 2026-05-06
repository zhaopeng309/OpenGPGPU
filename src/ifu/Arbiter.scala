package ifu

import chisel3._
import chisel3.util._

class IFU_Arbiter extends Module {
  val io = IO(new Bundle {
    val pst_entries = Input(Vec(IFUConfig.numWarps, new PSTEntry()))
    
    // Output grant (pipelined)
    val grant_valid = Output(Bool())
    val grant_warp_id = Output(UInt(IFUConfig.warpIdWidth.W))
    val grant_pc_va = Output(UInt(IFUConfig.pcWidth.W))
  })

  // Feature 2.1: Eligibility Mask
  val eligible_mask = Wire(Vec(IFUConfig.numWarps, Bool()))
  for (i <- 0 until IFUConfig.numWarps) {
    val entry = io.pst_entries(i)
    eligible_mask(i) := entry.valid && (entry.state === WarpState.s_READY) && (entry.credits > 0.U)
  }

  // Feature 2.2: Round-Robin Priority Encoder
  val token_ptr = RegInit(0.U(IFUConfig.warpIdWidth.W))
  
  // Double the mask for easy wrapping logic
  val mask_double = Cat(eligible_mask.asUInt, eligible_mask.asUInt)
  
  // Shift the mask so that token_ptr is at position 0
  val shifted_mask = mask_double >> token_ptr
  
  // Find the first eligible warp
  val first_eligible_shifted = PriorityEncoder(shifted_mask(IFUConfig.numWarps - 1, 0))
  val any_eligible = shifted_mask(IFUConfig.numWarps - 1, 0).orR
  
  val selected_warp_id = Wire(UInt(IFUConfig.warpIdWidth.W))
  selected_warp_id := token_ptr + first_eligible_shifted

  when(any_eligible) {
    token_ptr := selected_warp_id + 1.U
  }

  // Feature 2.3: Timing Closure (Pipeline Register)
  val grant_valid_reg = RegInit(false.B)
  val grant_warp_id_reg = RegInit(0.U(IFUConfig.warpIdWidth.W))
  val grant_pc_va_reg = RegInit(0.U(IFUConfig.pcWidth.W))

  grant_valid_reg := any_eligible
  when(any_eligible) {
    grant_warp_id_reg := selected_warp_id
    grant_pc_va_reg := io.pst_entries(selected_warp_id).pc_va
  }

  io.grant_valid := grant_valid_reg
  io.grant_warp_id := grant_warp_id_reg
  io.grant_pc_va := grant_pc_va_reg
}
