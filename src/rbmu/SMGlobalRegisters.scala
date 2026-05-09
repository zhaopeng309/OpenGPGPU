package opengpgpu.rbmu

import chisel3._
import chisel3.util._

/**
 * SM 全局寄存器模块 (Target_ID: 0x0)
 *
 * 根据 SFR_Spec.md v2.0 架构变更，SR_GRID_DIM_X/Y/Z 已从 SM 全局寄存器空间中移除。
 * Grid 维度信息现由 Block Scheduler 在 Kernel Launch 时直接写入每个 Warp 的 uGPR[3:5]，
 * 替代原有的 SFR + S2R 读取方案。
 *
 * 新增寄存器 (v2.1):
 *
 * | Offset  | 名称                  | 属性 | 描述                                    |
 * |---------|-----------------------|------|----------------------------------------|
 * | 0x000   | SM_ID                 | RO   | SM 物理 ID (由硬件 strapping 决定)       |
 * | 0x004   | SM_STATUS             | RO   | SM 运行状态                             |
 * | 0x010   | SM_IFU_GLOBAL_CTRL    | RW   | IFU 全局控制 (使能/暂停/单步)            |
 * | 0x020   | SM_KERNEL_LAUNCH      | WO   | Kernel 启动触发寄存器                    |
 * | 0x030   | SM_KERNEL_ACTIVE      | RO   | 当前是否有 Kernel 正在执行               |
 * | 0x040   | SM_ERROR_STATUS       | RO   | 异常状态汇总                             |
 * | 0x050   | SM_ERROR_CLEAR        | WO   | 异常状态清除寄存器                       |
 */
class SMGlobalRegisters(smId: Int = 0) extends Module {
  val io = IO(new Bundle {
    // RBMU 寄存器访问接口
    val rb = new RBMUTargetInterface()

    // === 外部更新接口 ===

    // SM_IFU_GLOBAL_CTRL 输出
    val ifu_global_enable = Output(Bool())
    val ifu_global_halt   = Output(Bool())
    val ifu_single_step   = Output(Bool())

    // SM_KERNEL_LAUNCH 触发
    val kernel_launch_trigger = Output(Bool())

    // SM_KERNEL_ACTIVE 更新
    val kernel_active = Input(Bool())

    // SM_ERROR_STATUS 更新
    val error_ifu_timeout      = Input(Bool())
    val error_icache_ecc       = Input(Bool())
    val error_decoder_illegal  = Input(Bool())
  })

  // ==========================================
  // 寄存器定义
  // ==========================================

  // --- RO 寄存器 ---
  // SM_ID (Offset 0x000): SM 物理 ID, 由硬件 strapping 决定
  val sm_id = Wire(UInt(32.W))
  sm_id := smId.U

  // SM_STATUS (Offset 0x004): [0] SM 使能, [1] 时钟门控, [2] 异常标志
  val sm_status = Wire(UInt(32.W))
  val sm_enabled     = RegInit(true.B)   // SM 默认使能
  val sm_clock_gated = RegInit(false.B)  // 时钟门控
  val sm_error_flag  = RegInit(false.B)  // 异常标志 (由 error_status 的 OR 驱动)
  sm_status := Cat(
    0.U(29.W),
    sm_error_flag,     // bit 2: 异常标志
    sm_clock_gated,    // bit 1: 时钟门控
    sm_enabled         // bit 0: SM 使能
  )

  // --- RW 寄存器 ---
  // SM_IFU_GLOBAL_CTRL (Offset 0x010): [0] IFU 全局使能, [1] IFU 暂停, [2] IFU 单步模式
  val ifu_global_ctrl_reg = RegInit(0.U(32.W))

  // --- WO 寄存器 ---
  // SM_KERNEL_LAUNCH (Offset 0x020): 写入任意值触发 Kernel Launch 序列
  val kernel_launch_pulse = Wire(Bool())
  val kernel_launch_reg = RegInit(false.B)

  // --- RO 状态寄存器 ---
  // SM_KERNEL_ACTIVE (Offset 0x030)
  val kernel_active_reg = RegInit(false.B)

  // --- 异常状态 ---
  // SM_ERROR_STATUS (Offset 0x040): [0] IFU 超时, [1] I-Cache ECC 错误, [2] Decoder 非法指令
  val error_status_reg = RegInit(0.U(3.W))

  // SM_ERROR_CLEAR (Offset 0x050): 写入 1 清除对应位
  val error_clear_pulse = Wire(Bool())

  // ==========================================
  // 写入逻辑
  // ==========================================
  when(io.rb.wr_valid) {
    switch(io.rb.wr_offset) {
      is(0x010.U) { ifu_global_ctrl_reg := io.rb.wr_data }
      is(0x020.U) { kernel_launch_reg := true.B }  // 写入触发脉冲
      is(0x050.U) {
        // 清除异常状态: 写入 1 清除对应位
        when(io.rb.wr_data(0)) { error_status_reg := error_status_reg & ~(1.U(3.W)) }
        .elsewhen(io.rb.wr_data(1)) { error_status_reg := error_status_reg & ~(2.U(3.W)) }
        .elsewhen(io.rb.wr_data(2)) { error_status_reg := error_status_reg & ~(4.U(3.W)) }
      }
    }
  }

  // Kernel Launch 脉冲: 在写入后的下一个周期清除
  kernel_launch_pulse := kernel_launch_reg
  when(kernel_launch_reg) {
    kernel_launch_reg := false.B
  }

  // ==========================================
  // 状态更新
  // ==========================================
  kernel_active_reg := io.kernel_active

  // 异常状态: 上升沿锁存 (一旦出错, 保持直到清除)
  when(io.error_ifu_timeout)     { error_status_reg(0) := true.B }
  when(io.error_icache_ecc)      { error_status_reg(1) := true.B }
  when(io.error_decoder_illegal) { error_status_reg(2) := true.B }

  // 异常标志 = 任意错误位有效
  sm_error_flag := error_status_reg.orR

  // ==========================================
  // 读取逻辑 (组合逻辑)
  // ==========================================
  io.rb.rd_data := 0.U
  when(io.rb.rd_valid) {
    switch(io.rb.rd_offset) {
      is(0x000.U) { io.rb.rd_data := sm_id }
      is(0x004.U) { io.rb.rd_data := sm_status }
      is(0x010.U) { io.rb.rd_data := ifu_global_ctrl_reg }
      is(0x020.U) { io.rb.rd_data := 0.U }  // WO 寄存器, 读返回 0
      is(0x030.U) { io.rb.rd_data := Cat(0.U(31.W), kernel_active_reg) }
      is(0x040.U) { io.rb.rd_data := Cat(0.U(29.W), error_status_reg) }
      is(0x050.U) { io.rb.rd_data := 0.U }  // WO 寄存器, 读返回 0
    }
  }

  // ==========================================
  // 输出连接
  // ==========================================
  io.ifu_global_enable  := ifu_global_ctrl_reg(0)
  io.ifu_global_halt    := ifu_global_ctrl_reg(1)
  io.ifu_single_step    := ifu_global_ctrl_reg(2)
  io.kernel_launch_trigger := kernel_launch_pulse
}

/**
 * SMGlobalRegisters 伴生对象
 */
object SMGlobalRegisters {
  val regDescriptors: Seq[RegDescriptor] = Seq(
    RegDescriptor("SM_ID",              0x000, RegAttr.RO, "SM 物理 ID"),
    RegDescriptor("SM_STATUS",          0x004, RegAttr.RO, "SM 运行状态"),
    RegDescriptor("SM_IFU_GLOBAL_CTRL", 0x010, RegAttr.RW, "IFU 全局控制 (使能/暂停/单步)"),
    RegDescriptor("SM_KERNEL_LAUNCH",   0x020, RegAttr.WO, "Kernel 启动触发"),
    RegDescriptor("SM_KERNEL_ACTIVE",   0x030, RegAttr.RO, "Kernel 活跃标志"),
    RegDescriptor("SM_ERROR_STATUS",    0x040, RegAttr.RO, "异常状态汇总"),
    RegDescriptor("SM_ERROR_CLEAR",     0x050, RegAttr.WO, "异常状态清除"),
  )
}
