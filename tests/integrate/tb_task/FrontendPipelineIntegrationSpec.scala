package integrate.tb_task

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ifu.IFU
import decoder._
import ibuffer.IBuffer
import l0icache.L0ICache
import l0kcache.L0KCache

import isa._
import scheduler._

class FrontendPipelineIntegrationTop extends Module {
  val io = IO(new Bundle {
    // Expose inputs to drive the top level
    val warp_init_valid = Input(Bool())
    val warp_init_id = Input(UInt(6.W))
    val warp_init_pc = Input(UInt(48.W))
    
    // Warp Scheduler control
    val ws_alloc_req = Input(Bool())
    val ws_alloc_warp_id = Input(UInt(5.W))
    val ws_dispatch_ready = Input(Bool())
    
    // Outputs to assert
    val emptyMask = Output(UInt(8.W))
    val headMicroOps = Output(Vec(8, new MicroOp()))
    
    // Dispatch outputs
    val dispatch_valid = Output(Bool())
    val dispatch_warp_id = Output(UInt(5.W))
    val dispatch_opcode = Output(UInt(8.W))
    
    // Memory interfaces
    val roc_icache_fill_valid = Input(Bool())
    val roc_icache_fill_addr = Input(UInt(48.W))
    val roc_icache_fill_data = Input(UInt(512.W))
    val roc_kcache_fill_valid = Input(Bool())
    val roc_kcache_fill_addr = Input(UInt(48.W))
    val roc_kcache_fill_data = Input(UInt(512.W))
    // Debug probes
    val reqValid = Output(Bool())
    val hitValid = Output(Bool())
    val missValid = Output(Bool())
    val wakeupValid = Output(Bool())
    val decoderValid = Output(Bool())
    val ibufferValid = Output(Bool())
    val creditReturnValid = Output(Bool())
    val creditReturnWarpId = Output(UInt(6.W))
  })

  val ifu = Module(new IFU)
  val icache = Module(new l0icache.L0ICache)
  val kcache = Module(new l0kcache.L0KCache)
  val decoder = Module(new Decoder)
  val ibuffer = Module(new IBuffer)
  val warpScheduler = Module(new WarpScheduler(numWarps = 8))

  // IFU inputs
  ifu.io.warp_init_valid := io.warp_init_valid
  ifu.io.warp_init_id := io.warp_init_id
  ifu.io.warp_init_pc := io.warp_init_pc
  ifu.io.warp_exit_valid := false.B
  ifu.io.warp_exit_id := 0.U
  ifu.io.flush_valid := false.B
  ifu.io.flush_warp_id := 0.U
  ifu.io.flush_target_pc := 0.U

  // Credit Return from IBuffer to IFU
  ifu.io.credit_return_valid := ibuffer.io.ifu.slotReleasedEn
  ifu.io.credit_return_warp_id := ibuffer.io.ifu.releasedWarpId

  // IFU <-> L0ICache Adapter
  icache.io.ifu.req_valid := ifu.io.icache_req.req_valid
  icache.io.ifu.req_warp_id := ifu.io.icache_req.req_warp_id
  icache.io.ifu.req_addr := ifu.io.icache_req.req_pc_va
  icache.io.ifu.req_gen_tag := ifu.io.icache_req.req_gen_tag

  // Cache response goes to IFU
  val is_hit = icache.io.ifu_resp.hit_valid
  val is_miss = icache.io.ifu_resp.miss_valid
  
  ifu.io.icache_rsp.rsp_valid := is_hit || is_miss
  ifu.io.icache_rsp.rsp_hit := is_hit
  ifu.io.icache_rsp.rsp_warp_id := Mux(is_hit, icache.io.ifu_resp.hit_warp_id, icache.io.ifu_resp.miss_warp_id)
  ifu.io.icache_rsp.rsp_inst_data := icache.io.ifu_resp.hit_data
  ifu.io.icache_rsp.rsp_gen_tag := icache.io.ifu_resp.hit_gen_tag

  // Cache wakeup to IFU
  ifu.io.icache_wakeup.wakeup_valid := icache.io.ifu_resp.wakeup_valid
  ifu.io.icache_wakeup.wakeup_warp_id := PriorityEncoder(icache.io.ifu_resp.wakeup_warp_mask)
  ifu.io.icache_wakeup.wakeup_gen_tag := icache.io.ifu_resp.wakeup_gen_tag

  // Debug probes
  io.reqValid := ifu.io.icache_req.req_valid
  io.hitValid := is_hit
  io.missValid := is_miss
  io.wakeupValid := icache.io.ifu_resp.wakeup_valid

  // IFU Decoder Out -> Decoder In
  decoder.io.validIn := ifu.io.decoder_out.valid
  decoder.io.instIn := ifu.io.decoder_out.inst_data
  decoder.io.warpIdIn := ifu.io.decoder_out.warp_id
  decoder.io.pcIn := 0.U

  // Decoder -> L0KCache
  decoder.io.earlyProbeReq.ready := true.B
  kcache.io.probe_req.valid := decoder.io.earlyProbeReq.valid
  kcache.io.probe_req.static_addr := decoder.io.earlyProbeReq.bits.addr
  kcache.io.probe_req.warp_id := decoder.io.earlyProbeReq.bits.warpId

  // L0KCache OCRead - not used in this test
  kcache.io.oc_read.valid := false.B
  kcache.io.oc_read.dynamic_addr := 0.U
  kcache.io.oc_read.warp_id := 0.U

  // Decoder -> IBuffer
  ibuffer.io.dec.valid := decoder.io.microOpOut.valid
  ibuffer.io.dec.warpId := decoder.io.warpIdIn
  ibuffer.io.dec.microOp := decoder.io.microOpOut

  // IBuffer Scheduler
  ibuffer.io.sched.popEn := warpScheduler.io.wsIbPopReq
  ibuffer.io.sched.popWarpId := warpScheduler.io.wsIbPopId
  io.emptyMask := ibuffer.io.sched.emptyMask
  io.headMicroOps := ibuffer.io.sched.headMicroOps

  // IBuffer Flush
  ibuffer.io.flush.flushEn := false.B
  ibuffer.io.flush.flushWarpId := 0.U

  // External memory fills
  icache.io.roc_resp.fill_valid := io.roc_icache_fill_valid
  icache.io.roc_resp.fill_addr := io.roc_icache_fill_addr
  icache.io.roc_resp.fill_data := io.roc_icache_fill_data

  kcache.io.roc_fill.valid := io.roc_kcache_fill_valid
  kcache.io.roc_fill.addr := io.roc_kcache_fill_addr
  kcache.io.roc_fill.data := io.roc_kcache_fill_data

  // BS preloads
  icache.io.bs_req.preload_valid := false.B
  icache.io.bs_req.preload_addr := 0.U

  // WarpScheduler Connections
  warpScheduler.io.ibEmptyMask := ibuffer.io.sched.emptyMask
  warpScheduler.io.ibHeadMicroOps := ibuffer.io.sched.headMicroOps
  warpScheduler.io.allocReq := io.ws_alloc_req
  warpScheduler.io.allocWarpId := io.ws_alloc_warp_id
  warpScheduler.io.blkschActiveMask := 0xFFFFFFFFL.U // Dummy active mask
  warpScheduler.io.blkschBarId := 0.U
  warpScheduler.io.kcacheMissWaitMask := 0.U
  warpScheduler.io.kcacheFillAckMask := 0.U
  warpScheduler.io.releaseReq.valid := false.B
  warpScheduler.io.releaseReq.bits.warpId := 0.U
  warpScheduler.io.releaseReq.bits.regId := 0.U
  
  warpScheduler.io.dispatch.ready := io.ws_dispatch_ready

  // Debug probes for decoder and ibuffer
  io.decoderValid := decoder.io.microOpOut.valid
  io.ibufferValid := ibuffer.io.dec.valid
  io.creditReturnValid := ibuffer.io.ifu.slotReleasedEn
  io.creditReturnWarpId := ibuffer.io.ifu.releasedWarpId

  // Dispatch outputs
  io.dispatch_valid := warpScheduler.io.dispatch.valid
  io.dispatch_warp_id := warpScheduler.io.dispatch.bits.warpId
  io.dispatch_opcode := warpScheduler.io.dispatch.bits.microOp.opcode
}

class FrontendPipelineIntegrationSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Frontend Pipeline Integration"

  it should "simulate IFU -> L0I -> Decoder -> L0K -> IBuffer -> Warp Scheduler pipeline" in {
    isa.Registry.clear()
    val inst1_def = new LDC_64
    isa.Registry.register(inst1_def)
    
    test(new FrontendPipelineIntegrationTop).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Set default inputs
      dut.io.warp_init_valid.poke(false.B)
      dut.io.ws_alloc_req.poke(false.B)
      dut.io.ws_dispatch_ready.poke(true.B) // assume downstream is ready
      dut.io.roc_icache_fill_valid.poke(false.B)
      dut.io.roc_kcache_fill_valid.poke(false.B)

      // Step 1: Initialize Warp 0
      dut.io.warp_init_valid.poke(true.B)
      dut.io.warp_init_id.poke(0.U)
      dut.io.warp_init_pc.poke(0x1000.U)
      
      // Also notify Warp Scheduler about allocation
      dut.io.ws_alloc_req.poke(true.B)
      dut.io.ws_alloc_warp_id.poke(0.U)
      
      dut.clock.step(1)
      dut.io.warp_init_valid.poke(false.B)
      dut.io.ws_alloc_req.poke(false.B)

      // Construct a valid LDC_64 instruction:
      // LDC_64 defines:
      //   op(59, 52, "00001100")  -> bits [59:52] = 0x0C
      //   op(51, 40, "000001000000") -> bits [51:40] = 0x40 (modifiers for 64-bit)
      val ldc64_opcode = BigInt("0C", 16)  // 0x0C at bits [59:52]
      val ldc64_mod   = BigInt("40", 16)   // 0x40 at bits [51:40]
      val inst1_val = (ldc64_opcode << 52) | (ldc64_mod << 40)
      println(s"LDC_64 instruction value: 0x${inst1_val.toString(16)}")

      // Fill the cache line with two instructions (inst1 at word 0, inst2 at word 1)
      val inst2_val = BigInt(0)  // second instruction (unused, just padding)
      val fillData = (inst2_val << 128) | inst1_val
      println(s"Fill data (512-bit): 0x${fillData.toString(16)}")

      var dispatchSeen = false
      var dispatchCount = 0
      // Run for enough cycles to complete the full pipeline
      for (i <- 0 until 40) {
        println(s"Cycle $i:")
        println(s"  emptyMask: ${dut.io.emptyMask.peekInt()}")
        println(s"  dispatchValid: ${dut.io.dispatch_valid.peekInt()}")
        
        if (dut.io.dispatch_valid.peek().litToBoolean) {
            dispatchSeen = true
            dispatchCount += 1
            println(s"  DISPATCH WARP ${dut.io.dispatch_warp_id.peekInt()} OPCODE ${dut.io.dispatch_opcode.peekInt()}")
            assert(dut.io.dispatch_warp_id.peekInt() == 0, "Dispatched warp ID should be 0")
        }

        // Apply ROC fill at cycle 3 (after miss is detected)
        if (i == 3) {
          dut.io.roc_icache_fill_valid.poke(true.B)
          dut.io.roc_icache_fill_addr.poke(0x1000.U)
          dut.io.roc_icache_fill_data.poke(fillData.U)
        } else {
          dut.io.roc_icache_fill_valid.poke(false.B)
        }
        dut.clock.step(1)
      }

      assert(dispatchSeen, "Warp Scheduler should dispatch an instruction")

      println("=== Pipeline Integration test PASSED ===")
    }
  }
}
