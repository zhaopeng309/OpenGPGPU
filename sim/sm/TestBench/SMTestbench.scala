package sim.sm.TestBench

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import utils.Logger
import sim.sm.SMTop
import sim.smsp.SMSPConfig
import opengpgpu.sm.SMConfig

/**
 * SM 系统级仿真 Testbench
 *
 * 使用 UVM-Style 架构：
 *   - Driver: 驱动 DUT 输入信号 (模拟 ACE 发送 BD)
 *   - Monitor: 监视 DUT 输出信号
 *   - Sequence: 定义测试序列 (BD 序列)
 *   - Test: 组合 Driver/Monitor/Sequence 并运行
 *
 * 对应 SM 开发计划 Feature 5.1 & 5.2:
 *   - 模拟 ACE 行为发送 BD 包
 *   - 模拟 ROC/L2 返回指令和数据
 *   - 端到端功能验证
 */
class SMTestbench extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SM System Simulation (UVM-Style)"

  // SM 配置 (与默认值一致)
  val cfg: SMConfig = SMConfig()

  // ==========================================
  // Test 1: 基础 BD 发送与状态转换
  // ==========================================
  it should "process basic BD and transition through BS states" in {
    SMSPConfig.initLogger()
    implicit val implicitCfg: SMConfig = cfg

    test(new SMTop()).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.clock.setTimeout(200)

      val env = new SMTestEnv()
      env.build(dut)

      env.runSequence(SMSequences.basicBDSequence)
    }
  }

  // ==========================================
  // Test 2: 多 Block 序列
  // ==========================================
  it should "process multiple BDs with resource allocation" in {
    SMSPConfig.initLogger()
    implicit val implicitCfg: SMConfig = cfg

    test(new SMTop()).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.clock.setTimeout(300)

      val env = new SMTestEnv()
      env.build(dut)

      env.runSequence(SMSequences.multiBlockSequence)
    }
  }

  // ==========================================
  // Test 3: 资源耗尽测试
  // ==========================================
  it should "handle resource exhaustion and wait for free resources" in {
    SMSPConfig.initLogger()
    implicit val implicitCfg: SMConfig = cfg

    test(new SMTop()).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.clock.setTimeout(400)

      val env = new SMTestEnv()
      env.build(dut)

      env.runSequence(SMSequences.resourceExhaustionSequence)
    }
  }
}
