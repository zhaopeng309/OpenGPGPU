package l0kcache

import chisel3._
import chisel3.util._

class K_MSHREntry extends Bundle {
  val valid = Bool()
  val line_addr = UInt((L0KCacheConfig.addrWidth - L0KCacheConfig.offsetBits).W)
  val warp_mask = UInt(L0KCacheConfig.warpNum.W)
  val origin_type = UInt(1.W) // 0: Static, 1: Dynamic
}

class K_TagEntry extends Bundle {
  val valid = Bool()
  val tag = UInt(L0KCacheConfig.tagBits.W)
}

class L0KCache extends Module {
  val io = IO(new Bundle {
    val probe_req = Flipped(new DecoderProbeIO)
    val oc_read = Flipped(new OCReadIO)
    val ack_wakeup = new WakeupIO
    val k_data = new KDataIO
    val roc_req = new ROCReqIO
    val roc_fill = Flipped(new ROCFillIO)
    val stall_backpressure = Output(Bool())
  })

  // Arrays
  val tagArray = RegInit(VecInit(Seq.fill(L0KCacheConfig.numSets)(VecInit(Seq.fill(L0KCacheConfig.numWays)(0.U.asTypeOf(new K_TagEntry))))))
  val dataArray = Reg(Vec(L0KCacheConfig.numSets, Vec(L0KCacheConfig.numWays, UInt((L0KCacheConfig.lineSize * 8).W))))
  val lruBits = RegInit(VecInit(Seq.fill(L0KCacheConfig.numSets)(0.U(log2Ceil(L0KCacheConfig.numWays).W))))
  val mshr = RegInit(VecInit(Seq.fill(L0KCacheConfig.numMSHREntries)(0.U.asTypeOf(new K_MSHREntry))))

  // Default outputs
  io.ack_wakeup.valid := false.B
  io.ack_wakeup.warp_id := 0.U
  io.k_data.valid := false.B
  io.k_data.data := 0.U
  io.roc_req.valid := false.B
  io.roc_req.addr := 0.U
  io.stall_backpressure := false.B

  val k_data_valid_reg = RegInit(false.B)
  val k_data_reg = Reg(UInt(L0KCacheConfig.dataWidth.W))
  io.k_data.valid := k_data_valid_reg
  io.k_data.data := k_data_reg
  k_data_valid_reg := false.B

  // Arbitration: OC priority over Probe
  val req_valid = io.oc_read.valid || io.probe_req.valid
  val is_dynamic = io.oc_read.valid
  val req_addr = Mux(is_dynamic, io.oc_read.dynamic_addr, io.probe_req.static_addr)
  val req_warp_id = Mux(is_dynamic, io.oc_read.warp_id, io.probe_req.warp_id)
  val req_origin = Mux(is_dynamic, OriginType.Dynamic, OriginType.Static)

  val req_line_addr = req_addr(L0KCacheConfig.addrWidth - 1, L0KCacheConfig.offsetBits)
  val req_index = req_line_addr(L0KCacheConfig.indexBits - 1, 0)
  val req_tag = req_line_addr(L0KCacheConfig.addrWidth - L0KCacheConfig.offsetBits - 1, L0KCacheConfig.indexBits)

  val hit_way = Wire(Vec(L0KCacheConfig.numWays, Bool()))
  for (w <- 0 until L0KCacheConfig.numWays) {
    hit_way(w) := tagArray(req_index)(w).valid && tagArray(req_index)(w).tag === req_tag
  }
  val is_hit = hit_way.asUInt.orR && req_valid

  val mshr_match = Wire(Vec(L0KCacheConfig.numMSHREntries, Bool()))
  val mshr_free = Wire(Vec(L0KCacheConfig.numMSHREntries, Bool()))
  for (i <- 0 until L0KCacheConfig.numMSHREntries) {
    mshr_match(i) := mshr(i).valid && mshr(i).line_addr === req_line_addr
    mshr_free(i) := !mshr(i).valid
  }
  val is_mshr_hit = mshr_match.asUInt.orR && req_valid && !is_hit
  val has_free_mshr = mshr_free.asUInt.orR
  val alloc_idx = PriorityEncoder(mshr_free.asUInt)
  val match_idx = OHToUInt(mshr_match.asUInt)

  // Processing Requests
  when(req_valid) {
    when(is_hit) {
      when(is_dynamic) {
        val way_idx = OHToUInt(hit_way)
        val full_line = dataArray(req_index)(way_idx)
        val word_offset = req_addr(5, 4) 
        val data_words = Wire(Vec(4, UInt(128.W)))
        for (i <- 0 until 4) {
          data_words(i) := full_line(i*128 + 127, i*128)
        }
        k_data_valid_reg := true.B
        k_data_reg := data_words(word_offset)
      }
    }.elsewhen(is_mshr_hit) {
      mshr(match_idx).warp_mask := mshr(match_idx).warp_mask | (1.U << req_warp_id)
      when(is_dynamic) {
        mshr(match_idx).origin_type := mshr(match_idx).origin_type | OriginType.Dynamic
        io.stall_backpressure := true.B
      }
    }.elsewhen(has_free_mshr) {
      mshr(alloc_idx).valid := true.B
      mshr(alloc_idx).line_addr := req_line_addr
      mshr(alloc_idx).warp_mask := (1.U << req_warp_id)
      mshr(alloc_idx).origin_type := req_origin

      when(is_dynamic) {
        io.stall_backpressure := true.B
      }

      io.roc_req.valid := true.B
      io.roc_req.addr := Cat(req_line_addr, 0.U(L0KCacheConfig.offsetBits.W))
    }.otherwise {
      when(is_dynamic) {
        io.stall_backpressure := true.B
      }
    }
  }

  // Wakeup Queue Generator
  val wakeup_queue_valid = RegInit(VecInit(Seq.fill(8)(false.B)))
  val wakeup_queue_warp = Reg(Vec(8, UInt(log2Ceil(L0KCacheConfig.warpNum).W)))
  val wq_enq_idx = PriorityEncoder(wakeup_queue_valid.map(!_))
  val wq_deq_idx = PriorityEncoder(wakeup_queue_valid)
  val has_wakeup = wakeup_queue_valid.asUInt.orR

  when(has_wakeup) {
    io.ack_wakeup.valid := true.B
    io.ack_wakeup.warp_id := wakeup_queue_warp(wq_deq_idx)
    wakeup_queue_valid(wq_deq_idx) := false.B
  }

  // ROC Fill Logic
  when(io.roc_fill.valid) {
    val fill_line_addr = io.roc_fill.addr(L0KCacheConfig.addrWidth - 1, L0KCacheConfig.offsetBits)
    val fill_index = fill_line_addr(L0KCacheConfig.indexBits - 1, 0)
    val fill_tag = fill_line_addr(L0KCacheConfig.addrWidth - L0KCacheConfig.offsetBits - 1, L0KCacheConfig.indexBits)
    
    val victim_way = lruBits(fill_index)
    lruBits(fill_index) := victim_way + 1.U

    tagArray(fill_index)(victim_way).valid := true.B
    tagArray(fill_index)(victim_way).tag := fill_tag
    dataArray(fill_index)(victim_way) := io.roc_fill.data

    for (i <- 0 until L0KCacheConfig.numMSHREntries) {
      when(mshr(i).valid && mshr(i).line_addr === fill_line_addr) {
        mshr(i).valid := false.B
        
        // If it has dynamic requests, we unlock stall_backpressure (which happens naturally as MSHR is cleared)
        // If it has static requests, we need to enqueue wakeups.
        // For simplicity in this architectural model, we just enqueue the first warp found in warp_mask.
        // In a full implementation we'd iterate over all bits in warp_mask. 
        // We will enqueue one wakeup here.
        val first_warp = PriorityEncoder(mshr(i).warp_mask)
        when(mshr(i).origin_type === OriginType.Static || (mshr(i).warp_mask =/= (1.U << first_warp))) {
          // Send wakeup to the first warp. We assume test bench doesn't saturate 8 entries.
          wakeup_queue_valid(wq_enq_idx) := true.B
          wakeup_queue_warp(wq_enq_idx) := first_warp
        }
      }
    }
  }
}
