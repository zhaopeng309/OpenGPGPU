package opengpgpu.ulm

import chisel3._
import chisel3.util._

/**
 * ULM 配置参数
 *
 * @param numBanks     物理 Bank 数量（默认 32）
 * @param bankWidth    每个 Bank 位宽（默认 32-bit）
 * @param totalSizeKB  ULM 总容量（KB，默认 128KB）
 * @param numWays      L1D 组相联路数（默认 4）
 * @param mshrDepth    MSHR 深度（默认 8）
 */
case class ULMConfig(
  numBanks: Int = 32,
  bankWidth: Int = 32,
  totalSizeKB: Int = 128,
  numWays: Int = 4,
  mshrDepth: Int = 8
) {
  val totalBytes = totalSizeKB * 1024
  val totalBits = totalBytes * 8
  val bankBytes = bankWidth / 8
  val cacheLineBytes = 128
  val cacheLineBits = cacheLineBytes * 8
  val numCacheLines = totalBytes / cacheLineBytes
  val numSets = numCacheLines / numWays
  val setIdxWidth = log2Ceil(numSets)
  val tagWidth = 64 - setIdxWidth - log2Ceil(cacheLineBytes)
  val bankIdWidth = log2Ceil(numBanks)
  val rowIdWidth = log2Ceil(totalBytes / (numBanks * bankBytes))
}

/**
 * ULM 请求类型
 */
object ULMReqType {
  val L1D_READ  = 0.U(3.W)
  val L1D_WRITE = 1.U(3.W)
  val SMEM_READ = 2.U(3.W)
  val SMEM_WRITE = 3.U(3.W)
  val TC_READ   = 4.U(3.W)  // TensorCore 读取
  val TMA_WRITE = 5.U(3.W)  // TMA 写入
}

/**
 * ULM 请求 Bundle
 * 由 LSU/TMA/TensorCore 发送到 ULM
 */
class ULMRequest(implicit cfg: ULMConfig) extends Bundle {
  val valid      = Bool()
  val req_type   = UInt(3.W)     // ULMReqType
  val addr       = UInt(64.W)    // 目标地址
  val data       = UInt(cfg.cacheLineBits.W) // 写入数据 (128B)
  val byte_mask  = UInt(cfg.cacheLineBytes.W) // 字节使能掩码
  val source_id  = UInt(4.W)     // 源 ID (LSU/TMA/TC)
  val warp_id    = UInt(5.W)
  val rd_index   = UInt(8.W)
  val active_mask = UInt(32.W)
  val barrier_id = UInt(12.W)
}

/**
 * ULM 响应 Bundle
 * 由 ULM 返回到 LSU/TMA/TensorCore
 */
class ULMResponse(implicit cfg: ULMConfig) extends Bundle {
  val valid      = Bool()
  val data       = UInt(cfg.cacheLineBits.W) // 读出数据 (128B)
  val source_id  = UInt(4.W)
  val warp_id    = UInt(5.W)
  val rd_index   = UInt(8.W)
  val active_mask = UInt(32.W)
  val barrier_id = UInt(12.W)
  val error      = Bool()
}

/**
 * ULM CSR 配置 Bundle
 * 对应 SM_ULM_CFG 寄存器
 */
class ULMCfgBundle extends Bundle {
  val carveout_sel = UInt(3.W)  // 切分模式选择
  val smem_base    = UInt(32.W) // 共享内存基址
  val l1d_enable   = Bool()     // L1D 使能
  val flush_l1d    = Bool()     // L1D 刷新请求
}

/**
 * ULM 切分模式
 * 定义 L1D 与 Smem 的容量分配
 */
object CarveoutMode {
  val SMEM_128KB_L1D_0KB   = 0.U(3.W)  // 000: 128KB Smem / 0KB L1D
  val SMEM_96KB_L1D_32KB   = 1.U(3.W)  // 001: 96KB Smem / 32KB L1D
  val SMEM_64KB_L1D_64KB   = 2.U(3.W)  // 010: 64KB Smem / 64KB L1D (平衡)
  val SMEM_32KB_L1D_96KB   = 3.U(3.W)  // 011: 32KB Smem / 96KB L1D
  val SMEM_16KB_L1D_112KB  = 4.U(3.W)  // 100: 16KB Smem / 112KB L1D (强缓存)
}

/**
 * 物理 Bank 访问请求
 * 由 Physical Arbiter 发送到 Data Core
 */
class BankAccessReq(implicit cfg: ULMConfig) extends Bundle {
  val bank_id  = UInt(cfg.bankIdWidth.W)
  val row_addr = UInt(cfg.rowIdWidth.W)
  val write    = Bool()
  val data     = UInt(cfg.bankWidth.W)
  val byte_en  = UInt((cfg.bankWidth / 8).W)
}

/**
 * 物理 Bank 访问响应
 */
class BankAccessResp(implicit cfg: ULMConfig) extends Bundle {
  val data = UInt(cfg.bankWidth.W)
}

/**
 * 物理仲裁器请求
 * 来自 L1D/Smem/TensorCore 的物理层请求
 */
class PhysReq(implicit cfg: ULMConfig) extends Bundle {
  val valid     = Bool()
  val bank_id   = UInt(cfg.bankIdWidth.W)
  val row_addr  = UInt(cfg.rowIdWidth.W)
  val write     = Bool()
  val data      = UInt(cfg.bankWidth.W)
  val byte_en   = UInt((cfg.bankWidth / 8).W)
  val source_id = UInt(2.W) // 0=L1D, 1=Smem, 2=TC
}

/**
 * 物理仲裁器响应
 */
class PhysResp(implicit cfg: ULMConfig) extends Bundle {
  val valid = Bool()
  val data  = UInt(cfg.bankWidth.W)
  val source_id = UInt(2.W)
}
