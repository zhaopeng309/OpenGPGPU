package opengpgpu.collector

import chisel3._
import chisel3.util._
import scheduler.DispatchBundle

case class CollectorConfig(
  numCUs: Int = 8,
  numBanks: Int = 4,
  threadPerWarp: Int = 32,
  vGPRWidth: Int = 32
)

class BankReadReq extends Bundle {
  val wid = UInt(5.W)
  val regId = UInt(8.W)
}

class BankReadResp(implicit config: CollectorConfig) extends Bundle {
  val data = Vec(config.threadPerWarp, UInt(config.vGPRWidth.W))
}

class OperandBundle(implicit config: CollectorConfig) extends Bundle {
  val wid = UInt(5.W)
  val opcode = UInt(8.W)
  val rd = UInt(8.W)
  val src1Data = Vec(config.threadPerWarp, UInt(config.vGPRWidth.W))
  val src2Data = Vec(config.threadPerWarp, UInt(config.vGPRWidth.W))
  val src3Data = Vec(config.threadPerWarp, UInt(config.vGPRWidth.W))
  val hasSrc1 = Bool()
  val hasSrc2 = Bool()
  val hasSrc3 = Bool()
  val activeMask = UInt(config.threadPerWarp.W)
  val predMask = UInt(config.threadPerWarp.W)
  val cuId = UInt(log2Ceil(config.numCUs).W)
}

class CUDeliverData(implicit config: CollectorConfig) extends Bundle {
  val data = Vec(config.threadPerWarp, UInt(config.vGPRWidth.W))
}
