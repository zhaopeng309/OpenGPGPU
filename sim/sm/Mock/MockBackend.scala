package sim.sm.Mock

import chisel3._
import chisel3.util._
import opengpgpu.sm.SMConfig
import opengpgpu.lsu.{LSURequest, LSUResponse}

/**
 * SM 后端 Mock 模块
 *
 * 对应 SM 开发计划 Phase 4 中未实现的组件：
 *   - LSU Hub 仲裁器 (Mock: 始终 ready, 无响应)
 *   - MMA (WGMMA) 结果路由 (Mock: 始终无结果)
 *   - mBarrier 唤醒通路 (Mock: 始终无唤醒)
 *
 * 此模块用于 SMTestbench 中替代真实后端，使测试可以独立验证
 * Block Scheduler 和 SMSP 阵列的功能。
 */
class MockBackend(implicit cfg: SMConfig) extends Module {
  val io = IO(new Bundle {
    // === LSU Hub 接口 (来自 SMSP) ===
    val lsu_req_valid = Input(Vec(cfg.numSmsp, Bool()))
    val lsu_req_ready = Output(Vec(cfg.numSmsp, Bool()))
    val lsu_req_bits  = Input(Vec(cfg.numSmsp, new LSURequest()))
    val lsu_resp_valid = Output(Vec(cfg.numSmsp, Bool()))
    val lsu_resp_ready = Input(Vec(cfg.numSmsp, Bool()))
    val lsu_resp_bits  = Output(Vec(cfg.numSmsp, new LSUResponse()))

    // === MMA 结果路由 ===
    val mma_result_valid = Output(Vec(cfg.numSmsp, Bool()))
    val mma_result_ready = Input(Vec(cfg.numSmsp, Bool()))

    // === mBarrier 唤醒通路 ===
    val mbarrier_wakeup_valid = Output(Vec(cfg.numSmsp, Bool()))
    val mbarrier_wakeup_warp_id = Output(Vec(cfg.numSmsp, UInt(cfg.warpIdWidth.W)))
  })

  // ==========================================
  // LSU Hub Mock
  // - 始终 ready 接收请求
  // - 从不返回响应 (模拟长延迟 LSU)
  // ==========================================
  for (i <- 0 until cfg.numSmsp) {
    io.lsu_req_ready(i) := true.B
    io.lsu_resp_valid(i) := false.B
  }

  // ==========================================
  // MMA Mock
  // - 从不产生结果
  // ==========================================
  for (i <- 0 until cfg.numSmsp) {
    io.mma_result_valid(i) := false.B
  }

  // ==========================================
  // mBarrier Mock
  // - 从不产生唤醒
  // ==========================================
  for (i <- 0 until cfg.numSmsp) {
    io.mbarrier_wakeup_valid(i) := false.B
    io.mbarrier_wakeup_warp_id(i) := 0.U
  }
}

/**
 * LSU Hub Mock (独立模块)
 *
 * 模拟 LSU Hub 的行为：
 * - 始终 ready 接收来自 SMSP 的 LSU 请求
 * - 从不返回响应 (模拟 L2/内存延迟)
 * - 可配置在指定周期后返回响应 (用于高级测试)
 */
class MockLSUHub(implicit cfg: SMConfig) extends Module {
  val io = IO(new Bundle {
    val lsu_req_valid = Input(Vec(cfg.numSmsp, Bool()))
    val lsu_req_ready = Output(Vec(cfg.numSmsp, Bool()))
    val lsu_req_bits  = Input(Vec(cfg.numSmsp, new LSURequest()))
    val lsu_resp_valid = Output(Vec(cfg.numSmsp, Bool()))
    val lsu_resp_ready = Input(Vec(cfg.numSmsp, Bool()))
    val lsu_resp_bits  = Output(Vec(cfg.numSmsp, new LSUResponse()))
  })

  // 始终 ready
  for (i <- 0 until cfg.numSmsp) {
    io.lsu_req_ready(i) := true.B
    io.lsu_resp_valid(i) := false.B
    io.lsu_resp_bits(i) := DontCare
  }
}

/**
 * MMA Mock (独立模块)
 *
 * 模拟 MMA (WGMMA) 结果路由的行为：
 * - 从不产生结果
 * - 始终 ready 接收 MMA 请求
 */
class MockMMA(implicit cfg: SMConfig) extends Module {
  val io = IO(new Bundle {
    val mma_result_valid = Output(Vec(cfg.numSmsp, Bool()))
    val mma_result_ready = Input(Vec(cfg.numSmsp, Bool()))
  })

  for (i <- 0 until cfg.numSmsp) {
    io.mma_result_valid(i) := false.B
  }
}

/**
 * mBarrier Mock (独立模块)
 *
 * 模拟 mBarrier 唤醒通路的行为：
 * - 从不产生唤醒
 */
class MockMBarrier(implicit cfg: SMConfig) extends Module {
  val io = IO(new Bundle {
    val mbarrier_wakeup_valid = Output(Vec(cfg.numSmsp, Bool()))
    val mbarrier_wakeup_warp_id = Output(Vec(cfg.numSmsp, UInt(cfg.warpIdWidth.W)))
  })

  for (i <- 0 until cfg.numSmsp) {
    io.mbarrier_wakeup_valid(i) := false.B
    io.mbarrier_wakeup_warp_id(i) := 0.U
  }
}
