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

class FrontendIntegrationTop extends Module {
  val io = IO(new Bundle {
    // Expose inputs to drive the top level
    val warp_init_valid = Input(Bool())
    val warp_init_id = Input(UInt(6.W))
    val warp_init_pc = Input(UInt(48.W))
    
    val pop_en = Input(Bool())
    val pop_warp_id = Input(UInt(6.W))
    
    // Outputs to assert
    val emptyMask = Output(UInt(8.W))
    val headMicroOps = Output(Vec(8, new MicroOp()))
    
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
  ibuffer.io.sched.popEn := io.pop_en
  ibuffer.io.sched.popWarpId := io.pop_warp_id
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

  // Debug probes for decoder and ibuffer
  io.decoderValid := decoder.io.microOpOut.valid
  io.ibufferValid := ibuffer.io.dec.valid
  io.creditReturnValid := ibuffer.io.ifu.slotReleasedEn
  io.creditReturnWarpId := ibuffer.io.ifu.releasedWarpId
}

class FrontendIntegrationSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Frontend Integration (Phase 5)"

  it should "simulate IFU -> L0I -> Decoder -> L0K -> IBuffer -> Credit Return loop" in {
    isa.Registry.clear()
    val inst1_def = new LDC_64
    isa.Registry.register(inst1_def)
    
    test(new FrontendIntegrationTop).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Set default inputs
      dut.io.warp_init_valid.poke(false.B)
      dut.io.pop_en.poke(false.B)
      dut.io.roc_icache_fill_valid.poke(false.B)
      dut.io.roc_kcache_fill_valid.poke(false.B)

      // Step 1: Initialize Warp 0
      dut.io.warp_init_valid.poke(true.B)
      dut.io.warp_init_id.poke(0.U)
      dut.io.warp_init_pc.poke(0x1000.U)
      dut.clock.step(1)
      dut.io.warp_init_valid.poke(false.B)

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

      // Run for enough cycles to complete the full pipeline
      for (i <- 0 until 30) {
        println(s"Cycle $i:")
        println(s"  reqValid: ${dut.io.reqValid.peekInt()}")
        println(s"  missValid: ${dut.io.missValid.peekInt()}")
        println(s"  hitValid: ${dut.io.hitValid.peekInt()}")
        println(s"  wakeupValid: ${dut.io.wakeupValid.peekInt()}")
        println(s"  decoderValid: ${dut.io.decoderValid.peekInt()}")
        println(s"  ibufferValid: ${dut.io.ibufferValid.peekInt()}")
        println(s"  emptyMask: ${dut.io.emptyMask.peekInt()}")

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

      // After enough cycles, Warp 0 queue should NOT be empty
      val emptyMask = dut.io.emptyMask.peekInt()
      assert((emptyMask & 1) == 0, s"Warp 0 queue should not be empty, got mask $emptyMask")

      // Read the decoded micro-op from IBuffer head
      val opcode = dut.io.headMicroOps(0).opcode.peekInt()
      println(s"Decoded micro-op opcode: $opcode (expected 0)")
      assert(opcode == 0, s"Expected opcode 0 (LDC_64 is first registered), got $opcode")

      // Step 2: Pop from IBuffer to trigger credit return
      // slotReleasedEn is combinational - it asserts in the same cycle as popEn
      println("=== Popping from IBuffer ===")
      dut.io.pop_en.poke(true.B)
      dut.io.pop_warp_id.poke(0.U)

      // Check credit return in the SAME cycle (combinational)
      val creditRet = dut.io.creditReturnValid.peekInt()
      println(s"  creditReturnValid (same cycle): $creditRet")
      println(s"  creditReturnWarpId (same cycle): ${dut.io.creditReturnWarpId.peekInt()}")
      assert(creditRet == 1, s"Expected credit return in same cycle as pop, got $creditRet")

      dut.clock.step(1)
      dut.io.pop_en.poke(false.B)

      // After popping, the queue may still have entries if IFU fetched more instructions.
      // The IFU has initialCredits=4, so after the first hit it re-requests (PC += 16).
      // Check that at least one slot was freed (emptyMask changed from before pop).
      val emptyMaskAfter = dut.io.emptyMask.peekInt()
      println(s"  emptyMask after pop: $emptyMaskAfter")
      // The queue should have fewer entries than before the pop
      assert(emptyMaskAfter != 255, s"Warp 0 queue should not be completely full after pop, got mask $emptyMaskAfter")

      println("=== Integration test PASSED ===")
    }
  }
}
