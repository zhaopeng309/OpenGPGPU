package blksch

import chisel3._
import chisel3.util._

// ==========================================
// Block Scheduler 类型定义
// ==========================================

/**
 * Block Descriptor (BD) — ACE RMU 下发的任务描述符
 * 对应 MAS §2.1 的 Block Descriptor 数据包格式
 */
class BlockDescriptor extends Bundle {
  val kernel_pc         = UInt(64.W)   // Kernel 第一条指令的虚拟地址
  val grid_dim_x        = UInt(16.W)   // Grid X 维度 Block 数量
  val grid_dim_y        = UInt(16.W)   // Grid Y 维度 Block 数量
  val grid_dim_z        = UInt(16.W)   // Grid Z 维度 Block 数量
  val block_id_x        = UInt(16.W)   // 当前 Block X 坐标
  val block_id_y        = UInt(16.W)   // 当前 Block Y 坐标
  val block_id_z        = UInt(16.W)   // 当前 Block Z 坐标
  val thread_dim_x      = UInt(10.W)   // Block X 维度线程数
  val thread_dim_y      = UInt(10.W)   // Block Y 维度线程数
  val thread_dim_z      = UInt(10.W)   // Block Z 维度线程数
  val vgpr_per_thread   = UInt(8.W)    // 每线程 vGPR 数量
  val ugpr_per_warp     = UInt(8.W)    // 每 Warp uGPR 数量
  val smem_bytes        = UInt(16.W)   // 共享内存需求 (单位: 128B)
  val barrier_req       = UInt(4.W)    // 硬件屏障需求数量 (0-16)
  val kcache_ptr        = UInt(32.W)   // L0K 常量数据段起始偏移
  val mode_register     = UInt(8.W)    // 硬件特性控制寄存器 (v2.0)
  val tma_desc_base     = UInt(32.W)   // TMA 描述符表基地址 (v2.0)
}

/**
 * WarpInitBundle — BS 下发给 SMSP 的 Warp 初始化包
 * 对应 MAS §5.2 的数据结构 (v2.0)
 */
class WarpInitBundle extends Bundle {
  val pc              = UInt(60.W)   // 压缩 PC (PC[63:4])
  val warp_id_in_sm   = UInt(5.W)    // 在 SM 内的唯一 ID (0-31)
  val vgpr_base       = UInt(12.W)   // 物理寄存器堆起始地址
  val ugpr_base       = UInt(12.W)   // 标量寄存器堆起始地址
  val smem_base       = UInt(16.W)   // 共享内存基地址
  val barrier_id      = UInt(4.W)    // 硬件屏障 ID
  val active_mask     = UInt(32.W)   // 线程活跃掩码
  val block_id_x      = UInt(16.W)   // Block X 坐标
  val block_id_y      = UInt(16.W)   // Block Y 坐标
  val block_id_z      = UInt(16.W)   // Block Z 坐标
  val grid_dim_x      = UInt(16.W)   // Grid X 维度
  val grid_dim_y      = UInt(16.W)   // Grid Y 维度
  val grid_dim_z      = UInt(16.W)   // Grid Z 维度
  val mode_register   = UInt(8.W)    // 硬件特性控制寄存器 (v2.0)
  val tma_desc_base   = UInt(32.W)   // TMA 描述符表基地址 (v2.0)
}

/**
 * WarpDone — SMSP 发送给 BS 的 Warp 完成信号
 */
class WarpDone extends Bundle {
  val block_id = UInt(8.W)   // Block ID (ABT 索引)
  val warp_id  = UInt(5.W)   // Warp ID (0-31)
}

/**
 * FreeRequest — 异步释放 FIFO 条目
 * 对应 MAS §3.3.3 的异步释放 FIFO
 */
class FreeRequest extends Bundle {
  val vgpr_chunks = UInt(16.W)   // vGPR Chunk 位图
  val smem_chunks = UInt(16.W)   // SMem Chunk 位图
  val bar_id      = UInt(4.W)    // Barrier ID
  val bar_count   = UInt(4.W)    // Barrier 数量
}

/**
 * ABTEntry — Active Block Table 条目
 * 对应 MAS §6.1 的引用计数器
 */
class ABTEntry extends Bundle {
  val valid            = Bool()          // 条目有效
  val warp_remaining   = UInt(6.W)       // 剩余 Warp 数 (最大 32)
  val total_warps      = UInt(6.W)       // 总 Warp 数
  val vgpr_base        = UInt(12.W)      // vGPR 基地址
  val vgpr_chunks      = UInt(16.W)      // vGPR Chunk 位图
  val smem_base        = UInt(16.W)      // SMem 基地址
  val smem_chunks      = UInt(16.W)      // SMem Chunk 位图
  val bar_id           = UInt(4.W)       // Barrier ID
  val bar_count        = UInt(4.W)       // Barrier 数量
  val kernel_pc        = UInt(64.W)      // Kernel PC
}

// ==========================================
// 配置参数
// ==========================================

object BSConfig {
  val numSmsp        = 4          // SMSP 数量
  val maxWarps       = 32         // 最大 Warp 数
  val maxBlocks      = 8          // 最大并发 Block 数
  val vgprChunks     = 16         // vGPR Chunk 总数
  val smemChunks     = 16         // SMem Chunk 总数
  val barPoolSize    = 16         // Barrier Pool 大小
  val freeFifoDepth  = 4          // 异步释放 FIFO 深度
  val exitFifoDepth  = 4          // 每 SMSP 完成 FIFO 深度
  val threadsPerWarp = 32         // 每 Warp 线程数
  val warpIdWidth    = log2Ceil(maxWarps)
  val blockIdWidth   = log2Ceil(maxBlocks)
  val smspIdWidth    = log2Ceil(numSmsp)
  // chunkWidth 需要能表示 vgprChunks (最大值 16), 所以用 log2Ceil(vgprChunks + 1)
  val chunkWidth     = log2Ceil(vgprChunks + 1)
}
