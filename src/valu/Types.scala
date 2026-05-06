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
class ResultPacket(implicit config: CollectorConfig) extends Bundle {
  val wid = UInt(5.W)
  val rd  = UInt(8.W)
  val data = Vec(config.threadPerWarp, UInt(config.vGPRWidth.W))
  val write_mask = UInt(config.threadPerWarp.W) // active_mask & pred_mask
}

// We will also use OperandBundle from collector.
// Let's create a local bundle that wraps OperandBundle to ensure masks are present,
// or we can just extend OperandBundle in collector.
