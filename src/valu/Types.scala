package opengpgpu.valu

import chisel3._
import chisel3.util._
import opengpgpu.collector.CollectorConfig

// vALU Opcodes (Phase 1: Basic Integer Math)
object vALUOpcode {
  val ADD = 0.U(8.W)
  val SUB = 1.U(8.W)
  val AND = 2.U(8.W)
  val OR  = 3.U(8.W)
  val XOR = 4.U(8.W)
}

// Result Commit Buffer interface
// 字段命名与 opengpgpu.pipeline.ResultPacket 对齐（RCB 微架构规范）
// 迁移说明：
//   wid       → warp_id   (UInt(5.W) → UInt(log2Ceil(numWarps).W)，Phase 2 参数化)
//   rd        → rd_index  (UInt(8.W) 不变)
//   新增      → barrier_id  (Phase 1 置 0)
//   新增      → source_type (Phase 1 置 0=vALU)
//   新增      → dest_type   (Phase 1 置 0=vGPR)
class ResultPacket(implicit config: CollectorConfig) extends Bundle {
  val warp_id     = UInt(5.W)
  val rd_index    = UInt(8.W)
  val data        = Vec(config.threadPerWarp, UInt(config.vGPRWidth.W))
  val write_mask  = UInt(config.threadPerWarp.W) // active_mask & pred_mask
  val barrier_id  = UInt(12.W)   // 关联屏障 ID（Phase 1 置 0）
  val source_type = UInt(2.W)    // 0:vALU/SFU, 1:LSU, 2:A2V（Phase 1 置 0）
  val dest_type   = UInt(2.W)    // 0:vGPR, 1:uGPR, 2:aGPR（Phase 1 置 0）
}

// We will also use OperandBundle from collector.
// Let's create a local bundle that wraps OperandBundle to ensure masks are present,
// or we can just extend OperandBundle in collector.
