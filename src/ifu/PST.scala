package ifu

import chisel3._
import chisel3.util._

class PSTUpdateIO extends Bundle {
  // From Block Scheduler
  val warp_init_valid = Input(Bool())
  val warp_init_id = Input(UInt(IFUConfig.warpIdWidth.W))
  val warp_init_pc = Input(UInt(IFUConfig.pcWidth.W))

  // From I-Buffer
  val credit_return_valid = Input(Bool())
  val credit_return_warp_id = Input(UInt(IFUConfig.warpIdWidth.W))

  // From BRU (Flush)
  val flush_valid = Input(Bool())
  val flush_warp_id = Input(UInt(IFUConfig.warpIdWidth.W))
  val flush_target_pc = Input(UInt(IFUConfig.pcWidth.W))

  // From Arbiter
  val arbiter_grant_valid = Input(Bool())
  val arbiter_grant_id = Input(UInt(IFUConfig.warpIdWidth.W))

  // From Cache/Controller
  val cache_rsp_valid = Input(Bool())
  val cache_rsp_hit = Input(Bool())
  val cache_rsp_warp_id = Input(UInt(IFUConfig.warpIdWidth.W))

  val cache_wakeup_valid = Input(Bool())
  val cache_wakeup_warp_id = Input(UInt(IFUConfig.warpIdWidth.W))
  
  // From Warp Scheduler
  val warp_exit_valid = Input(Bool())
  val warp_exit_id = Input(UInt(IFUConfig.warpIdWidth.W))
}

class IFU_PST extends Module {
  val io = IO(new Bundle {
    val ctrl = new PSTUpdateIO()
    val entries = Output(Vec(IFUConfig.numWarps, new PSTEntry()))
  })

  val pst = RegInit(VecInit(Seq.fill(IFUConfig.numWarps) {
    val entry = Wire(new PSTEntry())
    entry.valid := false.B
    entry.pc_va := 0.U
    entry.state := WarpState.s_IDLE
    entry.credits := 0.U
    entry.flush_gen_tag := 0.U
    entry.inst_id := 0.U
    entry
  }))

  io.entries := pst

  for (i <- 0 until IFUConfig.numWarps) {
    val is_init = io.ctrl.warp_init_valid && io.ctrl.warp_init_id === i.U
    val is_flush = io.ctrl.flush_valid && io.ctrl.flush_warp_id === i.U
    val is_exit = io.ctrl.warp_exit_valid && io.ctrl.warp_exit_id === i.U
    val is_credit_ret = io.ctrl.credit_return_valid && io.ctrl.credit_return_warp_id === i.U
    val is_arbiter_grant = io.ctrl.arbiter_grant_valid && io.ctrl.arbiter_grant_id === i.U
    val is_cache_rsp = io.ctrl.cache_rsp_valid && io.ctrl.cache_rsp_warp_id === i.U
    val is_cache_wakeup = io.ctrl.cache_wakeup_valid && io.ctrl.cache_wakeup_warp_id === i.U

    val entry = pst(i)
    
    val next_credits = WireDefault(entry.credits)
    when (is_arbiter_grant && is_credit_ret) {
      next_credits := entry.credits
    } .elsewhen (is_arbiter_grant) {
      next_credits := entry.credits - 1.U
    } .elsewhen (is_credit_ret) {
      next_credits := entry.credits + 1.U
    }

    when (is_flush) {
      entry.pc_va := io.ctrl.flush_target_pc
      entry.state := WarpState.s_READY
      entry.credits := IFUConfig.initialCredits.U
      entry.flush_gen_tag := entry.flush_gen_tag + 1.U
    } .elsewhen (is_init) {
      entry.valid := true.B
      entry.pc_va := io.ctrl.warp_init_pc
      entry.credits := IFUConfig.initialCredits.U
      entry.state := WarpState.s_READY
      entry.flush_gen_tag := 0.U
      entry.inst_id := 0.U
    } .elsewhen (is_exit) {
      entry.valid := false.B
      entry.state := WarpState.s_IDLE
    } .otherwise {
      entry.credits := next_credits
      
      when (is_cache_rsp && io.ctrl.cache_rsp_hit) {
        entry.pc_va := entry.pc_va + 16.U
        entry.inst_id := entry.inst_id + 1.U
      }
      
      switch (entry.state) {
        is (WarpState.s_READY) {
          when (is_arbiter_grant) {
            entry.state := WarpState.s_FETCH_REQ
          }
        }
        is (WarpState.s_FETCH_REQ) {
          when (is_cache_rsp) {
            when (io.ctrl.cache_rsp_hit) {
              when (next_credits === 0.U) {
                entry.state := WarpState.s_STALL_CREDIT
              } .otherwise {
                entry.state := WarpState.s_READY
              }
            } .otherwise {
              entry.state := WarpState.s_WAIT_CACHE
            }
          }
        }
        is (WarpState.s_WAIT_CACHE) {
          when (is_cache_wakeup) {
            when (next_credits === 0.U) {
              entry.state := WarpState.s_STALL_CREDIT
            } .otherwise {
              entry.state := WarpState.s_READY
            }
          }
        }
        is (WarpState.s_STALL_CREDIT) {
          when (next_credits > 0.U) {
            entry.state := WarpState.s_READY
          }
        }
      }
    }
  }
}
