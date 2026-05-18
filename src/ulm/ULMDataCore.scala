package opengpgpu.ulm

import chisel3._
import chisel3.util._

/**
 * ULM Data Core (32-Bank SRAM 阵列)
 *
 * 实现 32 个物理 Bank 的 SRAM 存储阵列，每个 Bank 32-bit 宽。
 * 支持单周期读写操作。
 *
 * 存储排布:
 * - 32 Banks × 32-bit = 1024-bit 总带宽
 * - 4-byte 交错 (Interleaving): 连续 4 字节映射到同一个 Bank
 * - 128B Cache Line 分布在 32 个 Bank 上 (每个 Bank 4 字节)
 *
 * 参考 ULM_MAS.md 第 2 章: 物理资源规划：32-Bank SRAM 阵列
 */
class ULMDataCore(implicit cfg: ULMConfig) extends Module {
  val io = IO(new Bundle {
    // 来自 Physical Arbiter 的访问请求
    val in = Flipped(Decoupled(new BankAccessReq()))
    val out = Valid(new BankAccessResp())

    // 状态
    val busy = Output(Bool())
  })

  // ── 32-Bank SRAM 阵列 ──
  // 每个 Bank 是同步单端口 SRAM (行为级模型)
  // 地址深度 = 总容量 / (32 Banks × 4 Bytes)
  val bankDepth = cfg.totalBytes / (cfg.numBanks * cfg.bankBytes)

  // 使用 Vec 的 Mem 来模拟 32 个 Bank
  val banks = Seq.fill(cfg.numBanks) {
    SyncReadMem(bankDepth, UInt(cfg.bankWidth.W))
  }

  // ── 流水线寄存器 ──
  val req_valid = RegInit(false.B)
  val req_bank_id = Reg(UInt(cfg.bankIdWidth.W))
  val req_row_addr = Reg(UInt(cfg.rowIdWidth.W))
  val req_write = Reg(Bool())
  val req_data = Reg(UInt(cfg.bankWidth.W))
  val req_byte_en = Reg(UInt((cfg.bankWidth / 8).W))

  // ── 输入握手 ──
  io.in.ready := !req_valid

  when(io.in.valid && io.in.ready) {
    req_valid := true.B
    req_bank_id := io.in.bits.bank_id
    req_row_addr := io.in.bits.row_addr
    req_write := io.in.bits.write
    req_data := io.in.bits.data
    req_byte_en := io.in.bits.byte_en
  }

  // ── Bank 访问 ──
  // 读数据 (组合逻辑从 SyncReadMem 读出)
  val read_data = Wire(Vec(cfg.numBanks, UInt(cfg.bankWidth.W)))

  for (b <- 0 until cfg.numBanks) {
    read_data(b) := banks(b).read(req_row_addr, req_valid && !req_write && req_bank_id === b.U)
  }

  // 写操作 (在时钟上升沿写入)
  for (b <- 0 until cfg.numBanks) {
    when(req_valid && req_write && req_bank_id === b.U) {
      // 字节使能写入: 使用 Mux 逐字节合并
      val bank_data = banks(b).read(req_row_addr, true.B) // 先读后写
      val new_data = Wire(UInt(cfg.bankWidth.W))

      // 使用 Cat 和 Mux 逐字节选择，避免直接位赋值
      val byteSel = (0 until (cfg.bankWidth / 8)).map { i =>
        Mux(req_byte_en(i), req_data(i * 8 + 7, i * 8), bank_data(i * 8 + 7, i * 8))
      }
      new_data := byteSel.reverse.reduce((a, b) => Cat(a, b))

      banks(b).write(req_row_addr, new_data)
    }
  }

  // ── 响应输出 ──
  // 读操作: 在下一个周期输出数据
  val resp_data = Reg(UInt(cfg.bankWidth.W))
  val resp_valid = RegInit(false.B)

  when(req_valid && !req_write) {
    // 读操作: 从选中的 Bank 读取数据
    resp_data := read_data(req_bank_id)
    resp_valid := true.B
  }.elsewhen(req_valid && req_write) {
    // 写操作: 下一个周期完成
    resp_valid := true.B
    resp_data := 0.U
  }.otherwise {
    when(resp_valid) {
      resp_valid := false.B
    }
  }

  io.out.valid := resp_valid
  io.out.bits.data := resp_data

  // ── 状态输出 ──
  io.busy := req_valid
}
