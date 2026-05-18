package opengpgpu.ulm

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

/**
 * XOR Swizzler
 *
 * 实现 XOR 置换逻辑，用于消除 Shared Memory 访问中的 Bank 冲突。
 * 公式: Physical_Bank_ID = (Logic_Addr[6:2]) ^ (Logic_Addr[13:9])
 *
 * 当 32 个线程访问一个 32x32 矩阵的同一列时，地址通过异或变换后，
 * 会被伪随机地散列到 32 个不同的 Bank 中，实现单周期读取。
 */
class XORSwizzler(implicit cfg: ULMConfig) extends Module {
  val io = IO(new Bundle {
    val logic_addr = Input(UInt(64.W))
    val phys_bank_id = Output(UInt(cfg.bankIdWidth.W))
    val phys_row_addr = Output(UInt(cfg.rowIdWidth.W))
  })

  // XOR Swizzle 公式: Physical_Bank_ID = (Logic_Addr[6:2]) ^ (Logic_Addr[13:9])
  // Logic_Addr[6:2] 是 5-bit 的 Bank 内偏移 (32 banks, 4 bytes each = 128 bytes)
  // Logic_Addr[13:9] 是 5-bit 的行地址低位
  val bank_offset = io.logic_addr(6, 2)  // 5-bit
  val row_low     = io.logic_addr(13, 9) // 5-bit

  io.phys_bank_id := bank_offset ^ row_low

  // 物理行地址 = 逻辑地址的高位 (除去 Bank 选择位)
  io.phys_row_addr := io.logic_addr >> (log2Ceil(cfg.numBanks) + log2Ceil(cfg.bankBytes)).U
}

/**
 * Smem Controller (行为级共享内存控制器)
 *
 * 负责处理 Shared Memory 的读写请求。
 * 核心功能:
 * 1. XOR Swizzle 地址变换 (Bank 冲突避免)
 * 2. 将逻辑地址映射到物理 Bank 和 Row
 * 3. 通过 Physical Arbiter 访问物理存储阵列
 * 4. 支持 TMA 块写入和 WGMMA 盲读
 *
 * 参考 ULM_MAS.md 第 6 章: Shared Memory 与数据置换 (Swizzling)
 */
class SmemController(implicit cfg: ULMConfig) extends Module {
  val io = IO(new Bundle {
    // 来自 ULM 顶层的请求 (LSU Smem 访问)
    val in = Flipped(Decoupled(new ULMRequest()))

    // TMA 块写入接口 (高优先级)
    val tma_in = Flipped(Decoupled(new ULMRequest()))

    // 发往 Physical Arbiter 的物理请求
    val phys_req = Decoupled(new PhysReq())
    val phys_resp = Flipped(Valid(new PhysResp()))

    // 响应返回给 ULM 顶层
    val out = Decoupled(new ULMResponse())

    // CSR 配置
    val cfg_bundle = Input(new ULMCfgBundle())

    // 状态
    val busy = Output(Bool())
  })

  // ── 内部状态 ──
  object State extends ChiselEnum {
    val sIdle, sRead, sWrite, sTMAWrite, sRespWait, sOutput = Value
  }
  import State._

  val state = RegInit(sIdle)
  val req_reg = Reg(new ULMRequest())
  val req_valid = RegInit(false.B)

  // ── XOR Swizzler 实例 ──
  val swizzler = Module(new XORSwizzler())

  // ── 输入仲裁: LSU 请求 vs TMA 请求 ──
  // TMA 具有更高优先级 (参考 ULM_MAS.md 7.1 仲裁优先级)
  val arb_in_valid = Wire(Bool())
  val arb_in_bits = Wire(new ULMRequest())
  val arb_in_ready = Wire(Bool())

  // 简单优先级仲裁
  val tma_pending = RegInit(false.B)
  val tma_reg = Reg(new ULMRequest())

  // TMA 输入处理
  io.tma_in.ready := !tma_pending || (state === sIdle && !req_valid)

  when(io.tma_in.valid && io.tma_in.ready) {
    tma_pending := true.B
    tma_reg := io.tma_in.bits
  }

  when(state === sOutput && req_reg.req_type === ULMReqType.TMA_WRITE) {
    tma_pending := false.B
  }

  // 仲裁选择: TMA 优先
  val use_tma = tma_pending && state === sIdle && !req_valid
  arb_in_valid := Mux(use_tma, tma_pending, io.in.valid)
  arb_in_bits := Mux(use_tma, tma_reg, io.in.bits)
  io.in.ready := !use_tma && state === sIdle && !req_valid

  // ── 输入握手 ──
  when(arb_in_valid && arb_in_ready) {
    req_valid := true.B
    req_reg := arb_in_bits

    // 通过 XOR Swizzler 计算物理 Bank 和 Row
    swizzler.io.logic_addr := arb_in_bits.addr

    when(arb_in_bits.req_type === ULMReqType.SMEM_READ) {
      state := sRead
    }.elsewhen(arb_in_bits.req_type === ULMReqType.SMEM_WRITE) {
      state := sWrite
    }.elsewhen(arb_in_bits.req_type === ULMReqType.TMA_WRITE) {
      state := sTMAWrite
    }.otherwise {
      state := sOutput
    }
  }

  // ── 读操作 ──
  when(state === sRead) {
    // 使用 Swizzler 输出的物理地址
    io.phys_req.valid := true.B
    io.phys_req.bits.valid := true.B
    io.phys_req.bits.bank_id := swizzler.io.phys_bank_id
    io.phys_req.bits.row_addr := swizzler.io.phys_row_addr
    io.phys_req.bits.write := false.B
    io.phys_req.bits.data := 0.U
    io.phys_req.bits.byte_en := 0.U
    io.phys_req.bits.source_id := 1.U // 1 = Smem

    when(io.phys_req.ready) {
      state := sRespWait
    }
  }

  // ── 写操作 ──
  when(state === sWrite) {
    io.phys_req.valid := true.B
    io.phys_req.bits.valid := true.B
    io.phys_req.bits.bank_id := swizzler.io.phys_bank_id
    io.phys_req.bits.row_addr := swizzler.io.phys_row_addr
    io.phys_req.bits.write := true.B
    io.phys_req.bits.data := req_reg.data(cfg.bankWidth - 1, 0)
    io.phys_req.bits.byte_en := req_reg.byte_mask((cfg.bankWidth / 8) - 1, 0)
    io.phys_req.bits.source_id := 1.U

    when(io.phys_req.ready) {
      state := sOutput
    }
  }

  // ── TMA 块写入 ──
  // TMA 写入时，AGU 已经执行了 Swizzle 编码，所以直接使用逻辑地址作为物理地址
  when(state === sTMAWrite) {
    // TMA 写入使用 1024-bit 宽总线，分多次写入 32 个 Bank
    // 这里简化处理: 每次写入一个 Bank
    val tma_bank_counter = RegInit(0.U(log2Ceil(cfg.numBanks).W))

    io.phys_req.valid := true.B
    io.phys_req.bits.valid := true.B
    io.phys_req.bits.bank_id := tma_bank_counter
    io.phys_req.bits.row_addr := req_reg.addr >> (log2Ceil(cfg.numBanks) + log2Ceil(cfg.bankBytes)).U
    io.phys_req.bits.write := true.B
    io.phys_req.bits.data := req_reg.data(cfg.bankWidth - 1, 0)
    io.phys_req.bits.byte_en := ~0.U((cfg.bankWidth / 8).W)
    io.phys_req.bits.source_id := 2.U // 2 = TMA

    when(io.phys_req.ready) {
      when(tma_bank_counter === (cfg.numBanks - 1).U) {
        tma_bank_counter := 0.U
        state := sOutput
      }.otherwise {
        tma_bank_counter := tma_bank_counter + 1.U
      }
    }
  }

  // ── 等待物理响应 ──
  when(state === sRespWait) {
    io.phys_req.valid := false.B

    when(io.phys_resp.valid) {
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

  // ── 输出阶段 ──
  when(state === sOutput) {
    // 写操作完成，返回成功响应
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
  }

  // ── 默认输出 ──
  when(state =/= sRespWait && state =/= sOutput) {
    io.out.valid := false.B
    io.out.bits := DontCare
  }

  when(state =/= sRead && state =/= sWrite && state =/= sTMAWrite && state =/= sRespWait) {
    io.phys_req.valid := false.B
    io.phys_req.bits := DontCare
  }

  // ── 状态输出 ──
  io.busy := req_valid || state =/= sIdle
}
