package sim.sm.TestBench

import chisel3._
import chiseltest._
import sim.sm.SMTop
import sim.smsp.SMSPConfig
import blksch.{BlockDescriptor, BSConfig}
import utils.Logger

/**
 * SM UVM-Style Sequence 和 Test Environment
 *
 * 定义测试序列（BD 序列），由 Test 调用。
 * 每个 Sequence 是一个独立的测试场景。
 *
 * 对应 SM 开发计划 Feature 5.2: 端到端功能验证
 */
object SMSequences {

  /**
   * 基础序列：发送一个简单的 BD，验证 Block Scheduler 的状态转换
   * BD: 1D Grid, 1 Block, 32 threads (1 Warp)
   */
  val basicBDSequence: (SMDriver, SMMonitor) => Unit = { (driver, monitor) =>
    val cycles = 100

    driver.init()

    // 构造一个简单的 BD: 1 Block, 32 threads (1 Warp)
    import chisel3.experimental.BundleLiterals._
    val bd = (new BlockDescriptor).Lit(
      _.kernel_pc -> 0x1000L.U(64.W),
      _.grid_dim_x -> 1.U,
      _.grid_dim_y -> 1.U,
      _.grid_dim_z -> 1.U,
      _.block_id_x -> 0.U,
      _.block_id_y -> 0.U,
      _.block_id_z -> 0.U,
      _.thread_dim_x -> 32.U,
      _.thread_dim_y -> 1.U,
      _.thread_dim_z -> 1.U,
      _.vgpr_per_thread -> 8.U,
      _.ugpr_per_warp -> 0.U,
      _.smem_bytes -> 0.U,
      _.barrier_req -> 0.U,
      _.kcache_ptr -> 0.U,
      _.mode_register -> 0.U,
      _.tma_desc_base -> 0.U
    )

    // 在第 3 周期发送 BD
    for (cycle <- 1 to cycles) {
      if (cycle == 3) {
        // 使用 peek/poke 方式发送 BD
        driver.sendBD(bd)
      }

      monitor.sample(cycle)
      driver.step()
    }
  }

  /**
   * 多 Block 序列：发送多个 BD，验证资源分配和生命周期管理
   */
  val multiBlockSequence: (SMDriver, SMMonitor) => Unit = { (driver, monitor) =>
    val cycles = 200

    driver.init()

    // 构造多个 BD
    import chisel3.experimental.BundleLiterals._
    val bds = Seq.tabulate(3) { i =>
      (new BlockDescriptor).Lit(
        _.kernel_pc -> (0x1000L + i * 0x100L).U(64.W),
        _.grid_dim_x -> 1.U,
        _.grid_dim_y -> 1.U,
        _.grid_dim_z -> 1.U,
        _.block_id_x -> i.U,
        _.block_id_y -> 0.U,
        _.block_id_z -> 0.U,
        _.thread_dim_x -> 64.U,  // 2 Warps
        _.thread_dim_y -> 1.U,
        _.thread_dim_z -> 1.U,
        _.vgpr_per_thread -> 8.U,
        _.ugpr_per_warp -> 0.U,
        _.smem_bytes -> 0.U,
        _.barrier_req -> 0.U,
        _.kcache_ptr -> 0.U,
        _.mode_register -> 0.U,
        _.tma_desc_base -> 0.U
      )
    }

    var bdIdx = 0
    for (cycle <- 1 to cycles) {
      // 每 30 个周期发送一个 BD
      if (cycle % 30 == 3 && bdIdx < bds.length) {
        driver.sendBD(bds(bdIdx))
        bdIdx += 1
      }

      monitor.sample(cycle)
      driver.step()
    }
  }

  /**
   * 资源耗尽测试：发送超过资源容量的 BD，验证资源等待逻辑
   */
  val resourceExhaustionSequence: (SMDriver, SMMonitor) => Unit = { (driver, monitor) =>
    val cycles = 300

    driver.init()

    // 构造大量 BD (每个消耗 1 个 vGPR Chunk)
    import chisel3.experimental.BundleLiterals._
    val numBDs = 10  // 超过 vgprChunks (16) 但测试分配逻辑
    val bds = Seq.tabulate(numBDs) { i =>
      (new BlockDescriptor).Lit(
        _.kernel_pc -> (0x1000L + i * 0x100L).U(64.W),
        _.grid_dim_x -> 1.U,
        _.grid_dim_y -> 1.U,
        _.grid_dim_z -> 1.U,
        _.block_id_x -> i.U,
        _.block_id_y -> 0.U,
        _.block_id_z -> 0.U,
        _.thread_dim_x -> 32.U,  // 1 Warp
        _.thread_dim_y -> 1.U,
        _.thread_dim_z -> 1.U,
        _.vgpr_per_thread -> 8.U,
        _.ugpr_per_warp -> 0.U,
        _.smem_bytes -> 0.U,
        _.barrier_req -> 0.U,
        _.kcache_ptr -> 0.U,
        _.mode_register -> 0.U,
        _.tma_desc_base -> 0.U
      )
    }

    var bdIdx = 0
    for (cycle <- 1 to cycles) {
      // 每 15 个周期发送一个 BD
      if (cycle % 15 == 3 && bdIdx < bds.length) {
        driver.sendBD(bds(bdIdx))
        bdIdx += 1
      }

      monitor.sample(cycle)
      driver.step()
    }
  }
}

/**
 * SM UVM-Style Test Environment
 *
 * 组合 Driver、Monitor 和 Sequence，提供完整的测试环境。
 */
class SMTestEnv {
  private var driver: SMDriver = null
  private var monitor: SMMonitor = null

  /**
   * 构建测试环境
   */
  def build(dut: SMTop): Unit = {
    driver = new SMDriver(dut)
    monitor = new SMMonitor(dut)
    monitor.enableLogging()
  }

  def getDriver: SMDriver = driver
  def getMonitor: SMMonitor = monitor

  /**
   * 运行指定序列
   */
  def runSequence(seq: (SMDriver, SMMonitor) => Unit): Unit = {
    seq(driver, monitor)
  }
}
