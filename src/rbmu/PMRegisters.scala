package opengpgpu.rbmu

import chisel3._
import chisel3.util._

/**
 * 性能计数器寄存器模块 (Performance Profiling SFRs)
 *
 * 根据 SFR_Spec.md 3.5 节定义:
 *
 * MMA/TMA 单元 (Target_ID: 0x8):
 * | Offset  | 名称              | 属性 | 描述                                    |
 * |---------|-------------------|------|----------------------------------------|
 * | 0x400   | PM_INST_RET_LO    | RO   | 已提交指令总数 (低 32 位)               |
 * | 0x404   | PM_INST_RET_HI    | RO   | 已提交指令总数 (高 32 位)               |
 *
 * vALU 单元 (Target_ID: 0x9):
 * | Offset  | 名称              | 属性 | 描述                                    |
 * |---------|-------------------|------|----------------------------------------|
 * | 0x400   | PM_VALU_STALL_LO  | RO   | vALU 因 Bank 冲突挂起的周期数 (低 32 位) |
 * | 0x404   | PM_VALU_STALL_HI  | RO   | vALU 因 Bank 冲突挂起的周期数 (高 32 位) |
 *
 * 支持 Snapshot 机制: 当读取 LO 时，自动锁定 HI 到快照寄存器，
 * 确保 64 位数据的一致性，解决进位撕裂问题。
 */

/**
 * MMA/TMA 性能计数器 (Target_ID: 0x8)
 */
class MMAPMRegisters extends Module {
  val io = IO(new Bundle {
    val rb = new RBMUTargetInterface()

    // 外部更新接口
    val inst_ret_inc = Input(Bool())  // 指令提交计数递增
  })

  // ==========================================
  // 64-bit 性能计数器
  // ==========================================
  val inst_ret_counter = RegInit(0.U(64.W))

  // Snapshot 寄存器 (用于解决进位撕裂)
  val snapshot_hi = RegInit(0.U(32.W))
  val snapshot_valid = RegInit(false.B)

  // ==========================================
  // 计数器更新
  // ==========================================
  when(io.inst_ret_inc) {
    inst_ret_counter := inst_ret_counter + 1.U
  }

  // ==========================================
  // 读取逻辑 (含 Snapshot 机制)
  // ==========================================
  io.rb.rd_data := 0.U(64.W)
  when(io.rb.rd_valid) {
    switch(io.rb.rd_offset) {
      is(0x400.U) {
        // 读取 LO: 自动锁定 HI 到快照寄存器
        snapshot_hi := inst_ret_counter(63, 32)
        snapshot_valid := true.B
        io.rb.rd_data := Cat(0.U(32.W), inst_ret_counter(31, 0))
      }
      is(0x404.U) {
        // 读取 HI: 从快照中取值
        io.rb.rd_data := Cat(0.U(32.W), Mux(snapshot_valid, snapshot_hi, inst_ret_counter(63, 32)))
        snapshot_valid := false.B
      }
    }
  }
}

object MMAPMRegisters {
  val regDescriptors: Seq[RegDescriptor] = Seq(
    RegDescriptor("PM_INST_RET_LO", 0x400, RegAttr.RO, "已提交指令总数 (低 32 位)"),
    RegDescriptor("PM_INST_RET_HI", 0x404, RegAttr.RO, "已提交指令总数 (高 32 位)"),
  )
}

/**
 * vALU 性能计数器 (Target_ID: 0x9)
 */
class VALUPMRegisters extends Module {
  val io = IO(new Bundle {
    val rb = new RBMUTargetInterface()

    // 外部更新接口
    val stall_inc = Input(Bool())  // Bank 冲突挂起计数递增
  })

  // ==========================================
  // 64-bit 性能计数器
  // ==========================================
  val stall_counter = RegInit(0.U(64.W))

  // Snapshot 寄存器
  val snapshot_hi = RegInit(0.U(32.W))
  val snapshot_valid = RegInit(false.B)

  // ==========================================
  // 计数器更新
  // ==========================================
  when(io.stall_inc) {
    stall_counter := stall_counter + 1.U
  }

  // ==========================================
  // 读取逻辑 (含 Snapshot 机制)
  // ==========================================
  io.rb.rd_data := 0.U(64.W)
  when(io.rb.rd_valid) {
    switch(io.rb.rd_offset) {
      is(0x400.U) {
        // 读取 LO: 自动锁定 HI 到快照寄存器
        snapshot_hi := stall_counter(63, 32)
        snapshot_valid := true.B
        io.rb.rd_data := Cat(0.U(32.W), stall_counter(31, 0))
      }
      is(0x404.U) {
        // 读取 HI: 从快照中取值
        io.rb.rd_data := Cat(0.U(32.W), Mux(snapshot_valid, snapshot_hi, stall_counter(63, 32)))
        snapshot_valid := false.B
      }
    }
  }
}

object VALUPMRegisters {
  val regDescriptors: Seq[RegDescriptor] = Seq(
    RegDescriptor("PM_VALU_STALL_LO", 0x400, RegAttr.RO, "vALU 因 Bank 冲突挂起的周期数 (低 32 位)"),
    RegDescriptor("PM_VALU_STALL_HI", 0x404, RegAttr.RO, "vALU 因 Bank 冲突挂起的周期数 (高 32 位)"),
  )
}
