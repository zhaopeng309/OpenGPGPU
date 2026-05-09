package sim.sm.TestBench

import chisel3._
import chiseltest._
import sim.sm.SMTop
import blksch.{BlockDescriptor, BSConfig}
import utils.Logger

/**
 * SM UVM-Style Driver
 *
 * 负责驱动 SM 的输入信号，提供高层 API：
 *   - sendBD(): 发送 Block Descriptor (模拟 ACE 行为)
 *   - fillICache(): 填充 I-Cache
 *   - fillKCache(): 填充 K-Cache
 *   - step(): 时钟步进
 *
 * 对应 SM 开发计划 Feature 5.1: 模拟 ACE 行为发送 BD 包
 */
class SMDriver(dut: SMTop) {

  // ==========================================
  // 初始化所有输入为默认值
  // ==========================================
  def init(): Unit = {
    dut.io.bd_valid.poke(false.B)
    // 使用 Lit 创建零值 BlockDescriptor 字面量
    import chisel3.experimental.BundleLiterals._
    dut.io.bd_bits.poke((new BlockDescriptor).Lit(
      _.kernel_pc -> 0.U,
      _.grid_dim_x -> 0.U,
      _.grid_dim_y -> 0.U,
      _.grid_dim_z -> 0.U,
      _.block_id_x -> 0.U,
      _.block_id_y -> 0.U,
      _.block_id_z -> 0.U,
      _.thread_dim_x -> 0.U,
      _.thread_dim_y -> 0.U,
      _.thread_dim_z -> 0.U,
      _.vgpr_per_thread -> 0.U,
      _.ugpr_per_warp -> 0.U,
      _.smem_bytes -> 0.U,
      _.barrier_req -> 0.U,
      _.kcache_ptr -> 0.U,
      _.mode_register -> 0.U,
      _.tma_desc_base -> 0.U
    ))
    dut.io.roc_icache_fill_valid.poke(false.B)
    dut.io.roc_icache_fill_addr.poke(0.U)
    dut.io.roc_icache_fill_data.poke(0.U)
    dut.io.roc_kcache_fill_valid.poke(false.B)
    dut.io.roc_kcache_fill_addr.poke(0.U)
    dut.io.roc_kcache_fill_data.poke(0.U)
  }

  // ==========================================
  // 发送 Block Descriptor (模拟 ACE RMU)
  // ==========================================
  def sendBD(bd: BlockDescriptor): Unit = {
    Logger.info("SM_DRV", s"Sending BD: kernel_pc=0x${bd.kernel_pc.litValue.toString(16)}, " +
      s"grid=(${bd.grid_dim_x.litValue},${bd.grid_dim_y.litValue},${bd.grid_dim_z.litValue}), " +
      s"block=(${bd.block_id_x.litValue},${bd.block_id_y.litValue},${bd.block_id_z.litValue}), " +
      s"threads=(${bd.thread_dim_x.litValue},${bd.thread_dim_y.litValue},${bd.thread_dim_z.litValue})")

    dut.io.bd_valid.poke(true.B)
    dut.io.bd_bits.poke(bd)
    step(1)
    dut.io.bd_valid.poke(false.B)
  }

  // ==========================================
  // 发送 BD 并等待 ready (Decoupled 握手)
  // ==========================================
  def sendBDHandshake(bd: BlockDescriptor): Unit = {
    Logger.info("SM_DRV", s"Sending BD with handshake...")
    dut.io.bd_valid.poke(true.B)
    dut.io.bd_bits.poke(bd)

    var cycles = 0
    while (!dut.io.bd_ready.peek().litToBoolean && cycles < 100) {
      step(1)
      cycles += 1
    }
    step(1)
    dut.io.bd_valid.poke(false.B)
    Logger.info("SM_DRV", s"BD accepted after $cycles cycles")
  }

  // ==========================================
  // I-Cache Fill
  // ==========================================
  def fillICache(addr: Long, data: BigInt): Unit = {
    Logger.info("SM_DRV", s"ICache fill @ 0x${addr.toHexString} = 0x${data.toString(16)}")
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
    Logger.info("SM_DRV", s"KCache fill @ 0x${addr.toHexString} = 0x${data.toString(16)}")
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
