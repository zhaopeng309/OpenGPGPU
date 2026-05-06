package l0icache

import chisel3._
import chisel3.util._

class MSHREntry extends Bundle {
  val valid = Bool()
  val line_addr = UInt((L0ICacheConfig.addrWidth - L0ICacheConfig.offsetBits).W)
  val warp_mask = UInt(L0ICacheConfig.warpNum.W)
  val gen_tag = UInt(2.W)
  val is_preload = Bool()
}

class TagEntry extends Bundle {
  val valid = Bool()
  val tag = UInt(L0ICacheConfig.tagBits.W)
}

class L0ICache extends Module {
  val io = IO(new Bundle {
    val ifu = Flipped(new IFU2Cache)
    val ifu_resp = new Cache2IFU
    val roc_req = new Cache2ROC
    val roc_resp = Flipped(new ROC2Cache)
    val bs_req = Flipped(new BS2Cache)
    val bs_resp = new Cache2BS
  })

  // 1. Cache Arrays
  val tagArray = RegInit(VecInit(Seq.fill(L0ICacheConfig.numSets)(VecInit(Seq.fill(L0ICacheConfig.numWays)(0.U.asTypeOf(new TagEntry))))))
  val dataArray = Reg(Vec(L0ICacheConfig.numSets, Vec(L0ICacheConfig.numWays, UInt((L0ICacheConfig.lineSize * 8).W))))
  val lruBits = RegInit(VecInit(Seq.fill(L0ICacheConfig.numSets)(0.U(log2Ceil(L0ICacheConfig.numWays).W))))

  // 2. MSHR Array
  val mshr = RegInit(VecInit(Seq.fill(L0ICacheConfig.numMSHREntries)(0.U.asTypeOf(new MSHREntry))))
  
  // 3. Address decoding
  val req_line_addr = io.ifu.req_addr(L0ICacheConfig.addrWidth - 1, L0ICacheConfig.offsetBits)
  val req_index = req_line_addr(L0ICacheConfig.indexBits - 1, 0)
  val req_tag = req_line_addr(L0ICacheConfig.addrWidth - L0ICacheConfig.offsetBits - 1, L0ICacheConfig.indexBits)
  
  // 4. Hit logic
  val hit_way = Wire(Vec(L0ICacheConfig.numWays, Bool()))
  for (w <- 0 until L0ICacheConfig.numWays) {
    hit_way(w) := tagArray(req_index)(w).valid && tagArray(req_index)(w).tag === req_tag
  }
  val is_hit = hit_way.asUInt.orR && io.ifu.req_valid
  
  // 5. MSHR Check & Allocate
  val mshr_match = Wire(Vec(L0ICacheConfig.numMSHREntries, Bool()))
  val mshr_free = Wire(Vec(L0ICacheConfig.numMSHREntries, Bool()))
  for (i <- 0 until L0ICacheConfig.numMSHREntries) {
    mshr_match(i) := mshr(i).valid && mshr(i).line_addr === req_line_addr
    mshr_free(i) := !mshr(i).valid
  }
  
  val is_mshr_hit = mshr_match.asUInt.orR && io.ifu.req_valid && !is_hit
  val has_free_mshr = mshr_free.asUInt.orR
  val alloc_idx = PriorityEncoder(mshr_free.asUInt)
  val match_idx = OHToUInt(mshr_match.asUInt)
  
  val mshr_full = !has_free_mshr
  io.ifu_resp.ready := !mshr_full
  
  // ROC Request Queue
  val roc_req_valid = RegInit(false.B)
  val roc_req_addr = Reg(UInt(L0ICacheConfig.addrWidth.W))
  val roc_req_is_preload = RegInit(false.B)
  
  io.roc_req.req_valid := roc_req_valid
  io.roc_req.req_addr := roc_req_addr
  io.roc_req.req_is_preload := roc_req_is_preload

  // Pipeline registers for IFU responses (1 cycle delay)
  val hit_valid_reg = RegInit(false.B)
  val hit_warp_id_reg = Reg(UInt(log2Ceil(L0ICacheConfig.warpNum).W))
  val hit_data_reg = Reg(UInt(L0ICacheConfig.dataWidth.W))
  val hit_gen_tag_reg = Reg(UInt(2.W))

  val miss_valid_reg = RegInit(false.B)
  val miss_warp_id_reg = Reg(UInt(log2Ceil(L0ICacheConfig.warpNum).W))

  io.ifu_resp.hit_valid := hit_valid_reg
  io.ifu_resp.hit_warp_id := hit_warp_id_reg
  io.ifu_resp.hit_data := hit_data_reg
  io.ifu_resp.hit_gen_tag := hit_gen_tag_reg

  io.ifu_resp.miss_valid := miss_valid_reg
  io.ifu_resp.miss_warp_id := miss_warp_id_reg

  hit_valid_reg := false.B
  miss_valid_reg := false.B

  // MSHR and Cache Array Update logic
  // Phase 1: Handle IFU Requests
  when(io.ifu.req_valid) {
    when(is_hit) {
      // Hit path
      hit_valid_reg := true.B
      hit_warp_id_reg := io.ifu.req_warp_id
      hit_gen_tag_reg := io.ifu.req_gen_tag
      
      val way_idx = OHToUInt(hit_way)
      val full_line = dataArray(req_index)(way_idx)
      val word_offset = io.ifu.req_addr(5, 4) // offset bits are [5:0], word is 16 bytes [5:4]
      
      val data_words = Wire(Vec(4, UInt(128.W)))
      for (i <- 0 until 4) {
        data_words(i) := full_line(i*128 + 127, i*128)
      }
      hit_data_reg := data_words(word_offset)
      
    }.elsewhen(is_mshr_hit) {
      // Phase 2: MSHR Merging
      mshr(match_idx).warp_mask := mshr(match_idx).warp_mask | (1.U << io.ifu.req_warp_id)
      miss_valid_reg := true.B
      miss_warp_id_reg := io.ifu.req_warp_id
    }.elsewhen(has_free_mshr) {
      // Allocate MSHR
      mshr(alloc_idx).valid := true.B
      mshr(alloc_idx).line_addr := req_line_addr
      mshr(alloc_idx).warp_mask := (1.U << io.ifu.req_warp_id)
      mshr(alloc_idx).gen_tag := io.ifu.req_gen_tag
      mshr(alloc_idx).is_preload := false.B
      
      miss_valid_reg := true.B
      miss_warp_id_reg := io.ifu.req_warp_id
      
      roc_req_valid := true.B
      roc_req_addr := Cat(req_line_addr, 0.U(L0ICacheConfig.offsetBits.W))
      roc_req_is_preload := false.B
    }
  }

  // Phase 3: Block Scheduler Preload
  io.bs_resp.preload_ack := false.B
  when(io.bs_req.preload_valid && !io.ifu.req_valid) {
    val p_req_line_addr = io.bs_req.preload_addr(L0ICacheConfig.addrWidth - 1, L0ICacheConfig.offsetBits)
    val p_req_index = p_req_line_addr(L0ICacheConfig.indexBits - 1, 0)
    val p_req_tag = p_req_line_addr(L0ICacheConfig.addrWidth - L0ICacheConfig.offsetBits - 1, L0ICacheConfig.indexBits)
    
    val p_hit_way = Wire(Vec(L0ICacheConfig.numWays, Bool()))
    for (w <- 0 until L0ICacheConfig.numWays) {
      p_hit_way(w) := tagArray(p_req_index)(w).valid && tagArray(p_req_index)(w).tag === p_req_tag
    }
    val p_is_hit = p_hit_way.asUInt.orR
    
    val p_mshr_match = Wire(Vec(L0ICacheConfig.numMSHREntries, Bool()))
    for (i <- 0 until L0ICacheConfig.numMSHREntries) {
      p_mshr_match(i) := mshr(i).valid && mshr(i).line_addr === p_req_line_addr
    }
    val p_is_mshr_hit = p_mshr_match.asUInt.orR
    
    when(p_is_hit) {
      io.bs_resp.preload_ack := true.B
    }.elsewhen(p_is_mshr_hit) {
      // Already fetching
    }.elsewhen(has_free_mshr) {
      mshr(alloc_idx).valid := true.B
      mshr(alloc_idx).line_addr := p_req_line_addr
      mshr(alloc_idx).warp_mask := 0.U
      mshr(alloc_idx).gen_tag := 0.U
      mshr(alloc_idx).is_preload := true.B
      
      roc_req_valid := true.B
      roc_req_addr := Cat(p_req_line_addr, 0.U(L0ICacheConfig.offsetBits.W))
      roc_req_is_preload := true.B
    }
  }

  when(roc_req_valid) {
    roc_req_valid := false.B
  }

  // Fill & Wakeup Logic
  io.ifu_resp.wakeup_valid := false.B
  io.ifu_resp.wakeup_warp_mask := 0.U
  io.ifu_resp.wakeup_data := 0.U
  io.ifu_resp.wakeup_gen_tag := 0.U

  when(io.roc_resp.fill_valid) {
    val fill_line_addr = io.roc_resp.fill_addr(L0ICacheConfig.addrWidth - 1, L0ICacheConfig.offsetBits)
    val fill_index = fill_line_addr(L0ICacheConfig.indexBits - 1, 0)
    val fill_tag = fill_line_addr(L0ICacheConfig.addrWidth - L0ICacheConfig.offsetBits - 1, L0ICacheConfig.indexBits)
    
    val victim_way = lruBits(fill_index)
    lruBits(fill_index) := victim_way + 1.U

    tagArray(fill_index)(victim_way).valid := true.B
    tagArray(fill_index)(victim_way).tag := fill_tag
    dataArray(fill_index)(victim_way) := io.roc_resp.fill_data

    for (i <- 0 until L0ICacheConfig.numMSHREntries) {
      when(mshr(i).valid && mshr(i).line_addr === fill_line_addr) {
        mshr(i).valid := false.B
        
        when(mshr(i).is_preload) {
          io.bs_resp.preload_ack := true.B
        }.otherwise {
          io.ifu_resp.wakeup_valid := true.B
          io.ifu_resp.wakeup_warp_mask := mshr(i).warp_mask
          io.ifu_resp.wakeup_gen_tag := mshr(i).gen_tag
          // IFU can extract correct word if it wants, we broadcast word 0 for now as wakeup_data is 128-bit
          // Or we can modify wakeup_data to be 512 bits in Types.scala
          io.ifu_resp.wakeup_data := io.roc_resp.fill_data(127, 0)
        }
      }
    }
  }
}
