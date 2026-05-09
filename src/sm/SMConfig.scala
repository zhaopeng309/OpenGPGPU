package opengpgpu.sm

import chisel3._
import chisel3.util._

/**
 * SM (Streaming Multiprocessor) 配置参数
 *
 * 对应 SM 开发计划 Feature 1.2:
 * - 支持可配置的 SMSP 数量（默认 4）
 * - Warp 总数（默认 32）
 * - ULM 存储容量
 */
case class SMConfig(
  // SMSP 配置
  numSmsp: Int = 4,
  numWarpsPerSmsp: Int = 8,
  numRegsPerSmsp: Int = 256,
  numScoreboardSlotsPerSmsp: Int = 6,
  numCUsPerSmsp: Int = 8,
  numBanksPerSmsp: Int = 4,
  threadPerWarp: Int = 32,
  vGPRWidth: Int = 32,
  rcbEntriesPerSmsp: Int = 12,

  // SM 全局配置
  maxWarps: Int = 32,
  maxBlocks: Int = 8,
  vgprChunks: Int = 16,
  smemChunks: Int = 16,
  barPoolSize: Int = 16,
  freeFifoDepth: Int = 4,
  exitFifoDepth: Int = 4,

  // ULM (Shared Memory) 容量 (单位: 128B chunks)
  ulmSizeChunks: Int = 16,

  // RBMU 环路口配置
  smId: Int = 0
) {
  // 派生宽度
  val warpIdWidth = log2Ceil(maxWarps)
  val blockIdWidth = log2Ceil(maxBlocks)
  val smspIdWidth = log2Ceil(numSmsp)
  val chunkWidth = log2Ceil(vgprChunks + 1)
}
