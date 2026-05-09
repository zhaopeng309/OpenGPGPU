package sim.smsp.TestBench

import chisel3._
import chiseltest._
import sim.smsp.{SMSPConfig, SMSPTop}
import utils.Logger
import isa.{Registry, SASS_Instruction}
import isa.LDC_64

/**
 * UVM-Style Sequence
 *
 * 定义测试序列（指令序列），由 Test 调用。
 * 每个 Sequence 是一个独立的测试场景。
 */
object SMSPSequences {

  /**
   * 注册 ADD 指令到 Registry
   */
  class ADD_Inst extends SASS_Instruction("ADD") {
    op(59, 52, "00000000") // Opcode 0x00 for vALU ADD
  }

  /**
   * 基础序列：初始化 Warp 0，填充一条 ADD 指令到 I-Cache
   * 签名符合 (SMSPDriver, SMSPMonitor) => Unit，可直接传给 runSequence
   */
  val basicAddSequence: (SMSPDriver, SMSPMonitor) => Unit = { (driver, monitor) =>
    val cycles = 100

    // 注册指令
    Registry.clear()
    Registry.register(new LDC_64)
    Registry.register(new ADD_Inst)

    // 初始化驱动
    driver.init()

    // 启动 Warp 0 @ PC=0x1000
    driver.initWarp(warpId = 0, pc = 0x1000L)

    // 构造 ADD 指令: Rd=3, Rs1=1, Rs2=2
    val addOpcode = BigInt("00", 16)
    val instVal = (addOpcode << 52) | (BigInt(3) << 32) | (BigInt(1) << 24) | (BigInt(2) << 16)

    // 运行指定周期数，在第 3 周期填充 I-Cache
    for (cycle <- 1 to cycles) {
      if (cycle == 3) {
        driver.fillICache(addr = 0x1000L, data = instVal)
      }

      // Monitor 采样
      monitor.sample(cycle)

      // 步进
      driver.step()
    }
  }

  /**
   * 多指令序列：连续发送多条指令
   */
  val multiInstSequence: (SMSPDriver, SMSPMonitor) => Unit = { (driver, monitor) =>
    val cycles = 150

    Registry.clear()
    Registry.register(new LDC_64)
    Registry.register(new ADD_Inst)

    driver.init()

    // 启动 Warp 0
    driver.initWarp(warpId = 0, pc = 0x1000L)

    // 构造多条指令
    val insts = Seq(
      (0x1000L, (BigInt("00", 16) << 52) | (BigInt(3) << 32) | (BigInt(1) << 24) | (BigInt(2) << 16)),
      (0x1008L, (BigInt("00", 16) << 52) | (BigInt(7) << 32) | (BigInt(4) << 24) | (BigInt(5) << 16)),
      (0x1010L, (BigInt("00", 16) << 52) | (BigInt(1) << 32) | (BigInt(2) << 24) | (BigInt(3) << 16))
    )

    var fillIdx = 0
    for (cycle <- 1 to cycles) {
      // 每 10 个周期填充一条指令
      if (cycle % 10 == 3 && fillIdx < insts.length) {
        val (addr, data) = insts(fillIdx)
        driver.fillICache(addr, data)
        fillIdx += 1
      }

      monitor.sample(cycle)
      driver.step()
    }
  }
}

/**
 * UVM-Style Test Environment
 *
 * 组合 Driver、Monitor 和 Sequence，提供完整的测试环境。
 * 类似于 UVM 的 env + test 概念。
 */
class SMSPTestEnv {
  private var driver: SMSPDriver = null
  private var monitor: SMSPMonitor = null

  /**
   * 构建测试环境
   */
  def build(dut: SMSPTop): Unit = {
    driver = new SMSPDriver(dut)
    monitor = new SMSPMonitor(dut)
    monitor.enableLogging()
  }

  def getDriver: SMSPDriver = driver
  def getMonitor: SMSPMonitor = monitor

  /**
   * 运行指定序列
   */
  def runSequence(seq: (SMSPDriver, SMSPMonitor) => Unit): Unit = {
    seq(driver, monitor)
  }
}
