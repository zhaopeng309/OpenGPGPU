package sim.smsp.TestBench

import chisel3._
import chiseltest._
import sim.smsp.SMSPTop
import utils.Logger

/**
 * UVM-Style Driver
 * 
 * 负责驱动 DUT 的输入信号，提供高层 API：
 *   - initWarp(): 初始化一个 Warp
 *   - fillICache(): 填充 I-Cache
 *   - fillKCache(): 填充 K-Cache
 *   - step(): 时钟步进
 */
class SMSPDriver(dut: SMSPTop) {

  // ==========================================
  // 初始化所有输入为默认值
  // ==========================================
  def init(): Unit = {
    dut.io.warp_init_valid.poke(false.B)
    dut.io.warp_init_id.poke(0.U)
    dut.io.warp_init_pc.poke(0.U)
    dut.io.roc_icache_fill_valid.poke(false.B)
    dut.io.roc_icache_fill_addr.poke(0.U)
    dut.io.roc_icache_fill_data.poke(0.U)
    dut.io.roc_kcache_fill_valid.poke(false.B)
    dut.io.roc_kcache_fill_addr.poke(0.U)
    dut.io.roc_kcache_fill_data.poke(0.U)
  }

  // ==========================================
  // Warp 初始化
  // ==========================================
  def initWarp(warpId: Int, pc: Long): Unit = {
    Logger.info("DRV", s"Init warp $warpId @ PC=0x${pc.toHexString}")
    dut.io.warp_init_valid.poke(true.B)
    dut.io.warp_init_id.poke(warpId.U)
    dut.io.warp_init_pc.poke(pc.U)
    step(1)
    dut.io.warp_init_valid.poke(false.B)
  }

  // ==========================================
  // I-Cache Fill
  // ==========================================
  def fillICache(addr: Long, data: BigInt): Unit = {
    Logger.info("DRV", s"ICache fill @ 0x${addr.toHexString} = 0x${data.toString(16)}")
    dut.io.roc_icache_fill_valid.poke(true.B)
    dut.io.roc_icache_fill_addr.poke(addr.U)
    dut.io.roc_icache_fill_data.poke(data.U)
    step(1)
    dut.io.roc_icache_fill_valid.poke(false.B)
  }

  // ==========================================
  // K-Cache Fill
  // ==========================================
  def fillKCache(addr: Long, data: BigInt): Unit = {
    Logger.info("DRV", s"KCache fill @ 0x${addr.toHexString} = 0x${data.toString(16)}")
    dut.io.roc_kcache_fill_valid.poke(true.B)
    dut.io.roc_kcache_fill_addr.poke(addr.U)
    dut.io.roc_kcache_fill_data.poke(data.U)
    step(1)
    dut.io.roc_kcache_fill_valid.poke(false.B)
  }

  // ==========================================
  // 时钟步进
  // ==========================================
  def step(n: Int = 1): Unit = {
    dut.clock.step(n)
  }
}
