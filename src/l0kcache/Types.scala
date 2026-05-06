package l0kcache

import chisel3._
import chisel3.util._

object L0KCacheConfig {
  val addrWidth = 48
  val dataWidth = 128
  val lineSize = 64 // bytes
  val numWays = 2
  val cacheSize = 2048 // 2KB
  val numSets = cacheSize / (numWays * lineSize) // 2048 / (2 * 64) = 16
  val numMSHREntries = 4
  val warpNum = 32

  val offsetBits = log2Ceil(lineSize) // 6
  val indexBits = log2Ceil(numSets)   // 4
  val tagBits = addrWidth - indexBits - offsetBits // 48 - 4 - 6 = 38
}

object OriginType {
  val Static = 0.U(1.W)
  val Dynamic = 1.U(1.W)
}

class DecoderProbeIO extends Bundle {
  val valid = Bool()
  val static_addr = UInt(L0KCacheConfig.addrWidth.W)
  val warp_id = UInt(log2Ceil(L0KCacheConfig.warpNum).W)
}

class OCReadIO extends Bundle {
  val valid = Bool()
  val dynamic_addr = UInt(L0KCacheConfig.addrWidth.W)
  val warp_id = UInt(log2Ceil(L0KCacheConfig.warpNum).W)
}

class WakeupIO extends Bundle {
  val valid = Bool()
  val warp_id = UInt(log2Ceil(L0KCacheConfig.warpNum).W)
}

class KDataIO extends Bundle {
  val valid = Bool()
  val data = UInt(L0KCacheConfig.dataWidth.W)
}

class ROCReqIO extends Bundle {
  val valid = Bool()
  val addr = UInt(L0KCacheConfig.addrWidth.W) // Block aligned address
}

class ROCFillIO extends Bundle {
  val valid = Bool()
  val addr = UInt(L0KCacheConfig.addrWidth.W)
  val data = UInt((L0KCacheConfig.lineSize * 8).W) // 512-bit
}
