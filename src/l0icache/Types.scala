package l0icache

import chisel3._
import chisel3.util._

object L0ICacheConfig {
  val addrWidth = 48
  val dataWidth = 128
  val lineSize = 64 // bytes
  val numWays = 4
  val cacheSize = 4096 // 4KB
  val numSets = cacheSize / (numWays * lineSize) // 4096 / (4 * 64) = 16
  val numMSHREntries = 4
  val warpNum = 8
  
  val offsetBits = log2Ceil(lineSize) // 6
  val indexBits = log2Ceil(numSets)   // 4
  val tagBits = addrWidth - indexBits - offsetBits // 48 - 4 - 6 = 38
}

class IFU2Cache extends Bundle {
  val req_valid = Bool()
  val req_warp_id = UInt(log2Ceil(L0ICacheConfig.warpNum).W)
  val req_addr = UInt(L0ICacheConfig.addrWidth.W)
  val req_gen_tag = UInt(2.W)
}

class Cache2IFU extends Bundle {
  val ready = Bool() // If false, MSHR is full, IFU should not send requests

  // Hit response (1 cycle delay)
  val hit_valid = Bool()
  val hit_warp_id = UInt(log2Ceil(L0ICacheConfig.warpNum).W)
  val hit_data = UInt(L0ICacheConfig.dataWidth.W)
  val hit_gen_tag = UInt(2.W)

  // Miss response (1 cycle delay)
  val miss_valid = Bool()
  val miss_warp_id = UInt(log2Ceil(L0ICacheConfig.warpNum).W)

  // Wakeup response (Async)
  val wakeup_valid = Bool()
  val wakeup_warp_mask = UInt(L0ICacheConfig.warpNum.W)
  val wakeup_data = UInt(L0ICacheConfig.dataWidth.W)
  val wakeup_gen_tag = UInt(2.W)
}

class Cache2ROC extends Bundle {
  val req_valid = Bool()
  val req_addr = UInt(L0ICacheConfig.addrWidth.W) // Block aligned address
  val req_is_preload = Bool()
}

class ROC2Cache extends Bundle {
  val fill_valid = Bool()
  val fill_addr = UInt(L0ICacheConfig.addrWidth.W) // Block aligned address
  val fill_data = UInt((L0ICacheConfig.lineSize * 8).W) // 512-bit
}

class BS2Cache extends Bundle {
  val preload_valid = Bool()
  val preload_addr = UInt(L0ICacheConfig.addrWidth.W)
}

class Cache2BS extends Bundle {
  val preload_ack = Bool()
}
