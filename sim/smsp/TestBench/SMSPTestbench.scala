package sim.smsp.TestBench

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import utils.Logger
import sim.smsp.{SMSPConfig, SMSPTop}

/**
 * SMSP 系统级仿真 Testbench
 * 
 * 使用 UVM-Style 架构：
 *   - Driver: 驱动 DUT 输入信号
 *   - Monitor: 监视 DUT 输出信号
 *   - Sequence: 定义测试序列（指令序列）
 *   - Test: 组合 Driver/Monitor/Sequence 并运行
 * 
 * 此 Testbench 可以直接替换 DUT 为其他模块（如 SMST），
 * 只需修改 DUT 类型和对应的 Driver/Monitor 即可。
 */
class SMSPTestbench extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SMSP System Simulation (UVM-Style)"

  // ==========================================
  // Test 1: 基础 ADD 指令仿真
  // ==========================================
  it should "run basic ADD instruction with UVM-style driver/monitor" in {
    SMSPConfig.initLogger()

    test(new SMSPTop).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.clock.setTimeout(200)

      // 构建 UVM-Style 测试环境
      val env = new SMSPTestEnv()
      env.build(dut)

      // 运行基础序列
      env.runSequence(SMSPSequences.basicAddSequence)
    }
  }

  // ==========================================
  // Test 2: 多指令序列仿真
  // ==========================================
  it should "run multiple instructions with UVM-style sequence" in {
    SMSPConfig.initLogger()

    test(new SMSPTop).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.clock.setTimeout(300)

      val env = new SMSPTestEnv()
      env.build(dut)

      env.runSequence(SMSPSequences.multiInstSequence)
    }
  }

  // ==========================================
  // Test 3: 自定义序列（内联）
  // ==========================================
  it should "run custom inline sequence with UVM-style components" in {
    SMSPConfig.initLogger()

    test(new SMSPTop).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.clock.setTimeout(200)

      // 也可以直接使用 Driver 和 Monitor，不经过 Env
      val driver = new SMSPDriver(dut)
      val monitor = new SMSPMonitor(dut)
      monitor.enableLogging()

      // 自定义序列
      driver.init()
      driver.initWarp(warpId = 1, pc = 0x2000L)

      // 构造 FADD 指令 (opcode=0x1E)
      val faddOpcode = BigInt("1E", 16)
      val instVal = (faddOpcode << 52) | (BigInt(5) << 32) | (BigInt(2) << 24) | (BigInt(3) << 16)

      for (cycle <- 1 to 80) {
        if (cycle == 3) {
          driver.fillICache(addr = 0x2000L, data = instVal)
        }

        monitor.sample(cycle)
        driver.step()
      }
    }
  }
}
