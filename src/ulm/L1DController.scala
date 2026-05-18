package opengpgpu.ulm

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

/**
 * L1D Cache Tag 条目
 */
class TagEntry(implicit cfg: ULMConfig) extends Bundle {
  val valid = Bool()
  val tag   = UInt(cfg.tagWidth.W)
}

/**
 * L1D Controller (行为级缓存控制器)
 *
 * 实现 4-way 组相联 L1D Cache 的行为模型。
 * 负责 Tag 管理、Hit/Miss 检测、MSHR 请求合并。
 * 不直接管理物理 RAM，通过 Physical Arbiter 访问存储阵列。
 *
 * 一致性策略:
 * - Write-Through (直写): 所有写操作同步发往 L2
 * - Write-Evict (写失效): 写命中时直接置为无效
 * - 无脏数据: L1 永不持有脏数据
 */
class L1DController(implicit cfg: ULMConfig) extends Module {
  val io = IO(new Bundle {
    // 来自 ULM 顶层的请求
    val in = Flipped(Decoupled(new ULMRequest()))

    // 发往 Physical Arbiter 的物理请求
    val phys_req = Decoupled(new PhysReq())
    val phys_resp = Flipped(Valid(new PhysResp()))

    // MSHR 接口 (发往 L2/MemoryController)
    val mshr_req = Decoupled(new ULMRequest())
    val mshr_resp = Flipped(Valid(new ULMResponse()))

    // 响应返回给 ULM 顶层
    val out = Decoupled(new ULMResponse())

    // CSR 配置
    val cfg_bundle = Input(new ULMCfgBundle())

    // 状态
    val busy = Output(Bool())
    val hit_count = Output(UInt(32.W))
    val miss_count = Output(UInt(32.W))
  })

  // ── Tag 阵列: 4-way 组相联 ──
  val numWays = cfg.numWays
  val numSets = cfg.numSets
  val setIdxBits = cfg.setIdxWidth

  val tag_array = Reg(Vec(numSets, Vec(numWays, new TagEntry())))
  val lru_array = Reg(Vec(numSets, UInt(numWays.W))) // LRU 状态 (每个 way 1 bit)

  // ── 请求寄存器 ──
  val req_valid = RegInit(false.B)
  val req_reg = Reg(new ULMRequest())
  val req_set_idx = Reg(UInt(setIdxBits.W))
  val req_tag = Reg(UInt(cfg.tagWidth.W))

  // ── 状态机 ──
  object State extends ChiselEnum {
    val sIdle, sTagCheck, sHitProcess, sMissProcess, sRespWait, sOutput = Value
  }
  import State._

  val state = RegInit(sIdle)

  // ── 统计计数器 ──
  val hit_counter = RegInit(0.U(32.W))
  val miss_counter = RegInit(0.U(32.W))

  // ── MSHR 状态 ──
  val mshr_depth = cfg.mshrDepth
  val mshr_valid = RegInit(VecInit(Seq.fill(mshr_depth)(false.B)))
  val mshr_addr  = Reg(Vec(mshr_depth, UInt(64.W)))
  val mshr_tag   = Reg(Vec(mshr_depth, UInt(cfg.tagWidth.W)))
  val mshr_set   = Reg(Vec(mshr_depth, UInt(setIdxBits.W)))
  val mshr_way   = Reg(Vec(mshr_depth, UInt(log2Ceil(numWays).W)))
  val mshr_pending = RegInit(0.U(log2Ceil(mshr_depth + 1).W))

  // ── 输入握手 ──
  io.in.ready := state === sIdle && !req_valid

  when(io.in.valid && io.in.ready) {
    req_valid := true.B
    req_reg := io.in.bits

    // 计算 Set Index 和 Tag
    val addr = io.in.bits.addr
    val cache_line_addr = (addr >> log2Ceil(cfg.cacheLineBytes).U) << log2Ceil(cfg.cacheLineBytes).U
    val set_idx = cache_line_addr(log2Ceil(cfg.cacheLineBytes) + setIdxBits - 1, log2Ceil(cfg.cacheLineBytes))
    val tag = cache_line_addr >> (log2Ceil(cfg.cacheLineBytes) + setIdxBits).U

    req_set_idx := set_idx
    req_tag := tag

    state := sTagCheck
  }

  // ── Tag 检查阶段 ──
  val hit_way = Wire(UInt(log2Ceil(numWays).W))
  val is_hit = Wire(Bool())
  val hit_vec = Wire(Vec(numWays, Bool()))

  for (w <- 0 until numWays) {
    hit_vec(w) := tag_array(req_set_idx)(w).valid &&
                  tag_array(req_set_idx)(w).tag === req_tag
  }

  is_hit := hit_vec.reduce(_ || _)
  hit_way := PriorityEncoder(Cat(hit_vec.reverse))

  when(state === sTagCheck) {
    when(is_hit) {
      hit_counter := hit_counter + 1.U
      state := sHitProcess
    }.otherwise {
      miss_counter := miss_counter + 1.U
      state := sMissProcess
    }
  }

  // ── Hit 处理 (L1D 命中) ──
  // Write-Through + Write-Evict 策略
  when(state === sHitProcess) {
    when(req_reg.req_type === ULMReqType.L1D_READ) {
      // 读命中: 从物理 Bank 读取数据
      // 计算 Bank ID 和 Row Address
      val bank_id = req_reg.addr(log2Ceil(cfg.bankBytes) + cfg.bankIdWidth - 1, log2Ceil(cfg.bankBytes))
      val row_addr = req_reg.addr >> (log2Ceil(cfg.bankBytes) + cfg.bankIdWidth).U

      io.phys_req.valid := true.B
      io.phys_req.bits.valid := true.B
      io.phys_req.bits.bank_id := bank_id
      io.phys_req.bits.row_addr := row_addr
      io.phys_req.bits.write := false.B
      io.phys_req.bits.data := 0.U
      io.phys_req.bits.byte_en := 0.U
      io.phys_req.bits.source_id := 0.U

      when(io.phys_req.ready) {
        state := sRespWait
      }
    }.elsewhen(req_reg.req_type === ULMReqType.L1D_WRITE) {
      // 写命中 (Write-Evict): 写入数据并使该行无效
      val bank_id = req_reg.addr(log2Ceil(cfg.bankBytes) + cfg.bankIdWidth - 1, log2Ceil(cfg.bankBytes))
      val row_addr = req_reg.addr >> (log2Ceil(cfg.bankBytes) + cfg.bankIdWidth).U

      // 写入物理 Bank
      io.phys_req.valid := true.B
      io.phys_req.bits.valid := true.B
      io.phys_req.bits.bank_id := bank_id
      io.phys_req.bits.row_addr := row_addr
      io.phys_req.bits.write := true.B
      io.phys_req.bits.data := req_reg.data(cfg.bankWidth - 1, 0)
      io.phys_req.bits.byte_en := req_reg.byte_mask((cfg.bankWidth / 8) - 1, 0)
      io.phys_req.bits.source_id := 0.U

      when(io.phys_req.ready) {
        // Write-Evict: 使该行无效
        tag_array(req_set_idx)(hit_way).valid := false.B
        state := sOutput
      }
    }.otherwise {
      state := sOutput
    }
  }

  // ── Miss 处理 (L1D 未命中) ──
  // 分配 MSHR 条目，向 L2 发起请求
  val free_mshr = PriorityEncoder(Cat(mshr_valid.map(v => !v).reverse))
  val has_free_mshr = !mshr_valid.reduce(_ || _) // 是否有空闲 MSHR

  when(state === sMissProcess) {
    // 查找空闲 MSHR 条目
    val mshr_avail = Wire(Bool())
    val mshr_idx = Wire(UInt(log2Ceil(mshr_depth).W))
    mshr_avail := false.B
    mshr_idx := 0.U

    for (i <- 0 until mshr_depth) {
      when(!mshr_valid(i)) {
        mshr_avail := true.B
        mshr_idx := i.U
      }
    }

    when(mshr_avail) {
      // 分配 MSHR 条目
      mshr_valid(mshr_idx) := true.B
      mshr_addr(mshr_idx) := req_reg.addr
      mshr_tag(mshr_idx) := req_tag
      mshr_set(mshr_idx) := req_set_idx
      mshr_way(mshr_idx) := PriorityEncoder(~Cat(tag_array(req_set_idx).map(_.valid).reverse))

      // 向 L2 发送 Miss 请求
      io.mshr_req.valid := true.B
      io.mshr_req.bits := req_reg

      when(io.mshr_req.ready) {
        mshr_pending := mshr_pending + 1.U
        state := sOutput
      }
    }.otherwise {
      // MSHR 满，反压
      io.mshr_req.valid := false.B
    }
  }

  // ── 等待物理响应 ──
  when(state === sRespWait) {
    io.phys_req.valid := false.B

    when(io.phys_resp.valid) {
      // 组装响应
      io.out.valid := true.B
      io.out.bits.valid := true.B
      io.out.bits.data := io.phys_resp.bits.data
      io.out.bits.source_id := req_reg.source_id
      io.out.bits.warp_id := req_reg.warp_id
      io.out.bits.rd_index := req_reg.rd_index
      io.out.bits.active_mask := req_reg.active_mask
      io.out.bits.barrier_id := req_reg.barrier_id
      io.out.bits.error := false.B

      when(io.out.ready) {
        req_valid := false.B
        state := sIdle
      }
    }
  }

  // ── MSHR 响应处理 (L2 数据返回) ──
  when(io.mshr_resp.valid) {
    val resp_addr = io.mshr_resp.bits.data // 简化: 使用 data 字段传递地址
    val resp_set = Wire(UInt(setIdxBits.W))
    val resp_tag = Wire(UInt(cfg.tagWidth.W))

    // 查找匹配的 MSHR 条目
    for (i <- 0 until mshr_depth) {
      when(mshr_valid(i)) {
        // 将返回数据填入 Cache (分配新行)
        val alloc_way = mshr_way(i)
        tag_array(mshr_set(i))(alloc_way).valid := true.B
        tag_array(mshr_set(i))(alloc_way).tag := mshr_tag(i)

        // 更新 LRU
        lru_array(mshr_set(i)) := (1.U << alloc_way)

        // 清除 MSHR
        mshr_valid(i) := false.B
        mshr_pending := mshr_pending - 1.U
      }
    }
  }

  // ── 输出阶段 (写操作完成) ──
  when(state === sOutput) {
    // 对于写操作，直接返回成功
    when(req_reg.req_type === ULMReqType.L1D_WRITE) {
      io.out.valid := true.B
      io.out.bits.valid := true.B
      io.out.bits.data := 0.U
      io.out.bits.source_id := req_reg.source_id
      io.out.bits.warp_id := req_reg.warp_id
      io.out.bits.rd_index := req_reg.rd_index
      io.out.bits.active_mask := req_reg.active_mask
      io.out.bits.barrier_id := req_reg.barrier_id
      io.out.bits.error := false.B

      when(io.out.ready) {
        req_valid := false.B
        state := sIdle
      }
    }.elsewhen(req_reg.req_type === ULMReqType.L1D_READ) {
      // 读 Miss 的情况，等待 MSHR 返回
      // 这里简化处理，实际需要等待 MSHR 响应
      io.out.valid := false.B
      req_valid := false.B
      state := sIdle
    }.otherwise {
      req_valid := false.B
      state := sIdle
    }
  }

  // ── 默认输出 ──
  when(state =/= sRespWait && state =/= sOutput) {
    io.out.valid := false.B
    io.out.bits := DontCare
  }

  when(state =/= sHitProcess && state =/= sRespWait) {
    io.phys_req.valid := false.B
    io.phys_req.bits := DontCare
  }

  when(state =/= sMissProcess) {
    io.mshr_req.valid := false.B
    io.mshr_req.bits := DontCare
  }

  // ── 状态输出 ──
  io.busy := req_valid || state =/= sIdle
  io.hit_count := hit_counter
  io.miss_count := miss_counter
}
