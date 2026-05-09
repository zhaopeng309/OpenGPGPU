package opengpgpu.rbmu

import chisel3._
import chisel3.util._

/**
 * LSU 寄存器模块 (Target_ID: 0x5)
 *
 * 根据 SFR_Spec.md 3.3 节定义:
 *
 * | Offset  | 名称                  | 属性 | 描述                                    |
 * |---------|-----------------------|------|----------------------------------------|
 * | 0x000   | LSU_ADDR_REGION_0     | RW   | 影子寄存器。低 56 位中 Host Memory 区间配置 |
 * | 0x008   | LSU_ADDR_REGION_1     | RW   | 影子寄存器。Local Device Memory 区间配置   |
 * | 0x040   | LSU_PRT_CONFIG        | RW   | Page Residency Table 使能与物理基址       |
 * | 0x100   | LSU_BARRIER_TIMEOUT   | RW   | mBarrier 等待超时阈值寄存器               |
 * | 0x200   | PM_LSU_RD_REQ         | RO   | 发起的 Load 请求总数 (32-bit)             |
 * | 0x210   | PM_LSU_WR_REQ         | RO   | 发起的 Store 请求总数 (32-bit)            |
 * | 0x280   | PM_L1D_HIT_CNT        | RO   | L1 Data Cache 命中次数 (32-bit)          |
 */
class LSURegisters extends Module {
  val io = IO(new Bundle {
    val rb = new RBMUTargetInterface()

    // 外部更新接口
    val addr_region_0     = Output(UInt(56.W))
    val addr_region_1     = Output(UInt(56.W))
    val prt_config        = Output(UInt(32.W))
    val barrier_timeout   = Output(UInt(32.W))

    // 性能计数器更新
    val pm_rd_req_inc     = Input(Bool())
    val pm_wr_req_inc     = Input(Bool())
    val pm_l1d_hit_inc    = Input(Bool())

    // 全局同步
    val global_sync       = Input(Bool())
  })

  // ==========================================
  // RW 影子寄存器
  // ==========================================
  val addr_region_0_shadow = RegInit(0.U(56.W))
  val addr_region_0_reg    = RegInit(0.U(56.W))

  val addr_region_1_shadow = RegInit(0.U(56.W))
  val addr_region_1_reg    = RegInit(0.U(56.W))

  val prt_config_shadow    = RegInit(0.U(32.W))
  val prt_config_reg       = RegInit(0.U(32.W))

  val barrier_timeout_shadow = RegInit(0.U(32.W))
  val barrier_timeout_reg    = RegInit(0.U(32.W))

  // ==========================================
  // RO 性能计数器
  // ==========================================
  val pm_rd_req_cnt  = RegInit(0.U(32.W))
  val pm_wr_req_cnt  = RegInit(0.U(32.W))
  val pm_l1d_hit_cnt = RegInit(0.U(32.W))

  // ==========================================
  // 写入逻辑 (RBMU -> Shadow Latch)
  // ==========================================
  when(io.rb.wr_valid) {
    switch(io.rb.wr_offset) {
      is(0x000.U) { addr_region_0_shadow := io.rb.wr_data }
      is(0x008.U) { addr_region_1_shadow := io.rb.wr_data }
      is(0x040.U) { prt_config_shadow    := io.rb.wr_data }
      is(0x100.U) { barrier_timeout_shadow := io.rb.wr_data }
    }
  }

  // ==========================================
  // 全局同步: Shadow -> Active Register
  // ==========================================
  when(io.global_sync) {
    addr_region_0_reg  := addr_region_0_shadow
    addr_region_1_reg  := addr_region_1_shadow
    prt_config_reg     := prt_config_shadow
    barrier_timeout_reg := barrier_timeout_shadow
  }

  // ==========================================
  // 性能计数器更新
  // ==========================================
  when(io.pm_rd_req_inc)  { pm_rd_req_cnt  := pm_rd_req_cnt + 1.U }
  when(io.pm_wr_req_inc)  { pm_wr_req_cnt  := pm_wr_req_cnt + 1.U }
  when(io.pm_l1d_hit_inc) { pm_l1d_hit_cnt := pm_l1d_hit_cnt + 1.U }

  // ==========================================
  // 读取逻辑 (组合逻辑)
  // ==========================================
  io.rb.rd_data := 0.U
  when(io.rb.rd_valid) {
    switch(io.rb.rd_offset) {
      is(0x000.U) { io.rb.rd_data := addr_region_0_reg(31, 0) }
      is(0x004.U) { io.rb.rd_data := Cat(0.U(8.W), addr_region_0_reg(55, 32)) }
      is(0x008.U) { io.rb.rd_data := addr_region_1_reg(31, 0) }
      is(0x00C.U) { io.rb.rd_data := Cat(0.U(8.W), addr_region_1_reg(55, 32)) }
      is(0x040.U) { io.rb.rd_data := prt_config_reg }
      is(0x100.U) { io.rb.rd_data := barrier_timeout_reg }
      is(0x200.U) { io.rb.rd_data := pm_rd_req_cnt }
      is(0x210.U) { io.rb.rd_data := pm_wr_req_cnt }
      is(0x280.U) { io.rb.rd_data := pm_l1d_hit_cnt }
    }
  }

  // ==========================================
  // 输出连接
  // ==========================================
  io.addr_region_0   := addr_region_0_reg
  io.addr_region_1   := addr_region_1_reg
  io.prt_config      := prt_config_reg
  io.barrier_timeout := barrier_timeout_reg
}

object LSURegisters {
  val regDescriptors: Seq[RegDescriptor] = Seq(
    RegDescriptor("LSU_ADDR_REGION_0",   0x000, RegAttr.RW_Shadow, "Host Memory 区间配置 (低 32 位)"),
    RegDescriptor("LSU_ADDR_REGION_0_HI",0x004, RegAttr.RW_Shadow, "Host Memory 区间配置 (高 24 位)"),
    RegDescriptor("LSU_ADDR_REGION_1",   0x008, RegAttr.RW_Shadow, "Local Device Memory 区间配置 (低 32 位)"),
    RegDescriptor("LSU_ADDR_REGION_1_HI",0x00C, RegAttr.RW_Shadow, "Local Device Memory 区间配置 (高 24 位)"),
    RegDescriptor("LSU_PRT_CONFIG",      0x040, RegAttr.RW, "Page Residency Table 使能与物理基址"),
    RegDescriptor("LSU_BARRIER_TIMEOUT", 0x100, RegAttr.RW, "mBarrier 等待超时阈值寄存器"),
    RegDescriptor("PM_LSU_RD_REQ",       0x200, RegAttr.RO, "发起的 Load 请求总数"),
    RegDescriptor("PM_LSU_WR_REQ",       0x210, RegAttr.RO, "发起的 Store 请求总数"),
    RegDescriptor("PM_L1D_HIT_CNT",      0x280, RegAttr.RO, "L1 Data Cache 命中次数"),
  )
}
