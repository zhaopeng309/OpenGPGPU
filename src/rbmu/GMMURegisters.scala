package opengpgpu.rbmu

import chisel3._
import chisel3.util._

/**
 * GMMU / VMID 配置寄存器模块 (Target_ID: 0x6)
 *
 * 根据 SFR_Spec.md 3.4 节定义:
 *
 * | Offset      | 名称                  | 属性 | 描述                                    |
 * |-------------|-----------------------|------|----------------------------------------|
 * | 0x000-0x01C | VMID_PASID_MAP[8]     | RW   | 将 3-bit VMID 映射到系统级 PASID        |
 * | 0x100       | PTW_ROOT_PTR          | RW   | 当前活跃任务的四级页表根指针             |
 *
 * 支持 8 个进程的虚拟空间切换。
 */
class GMMURegisters extends Module {
  val io = IO(new Bundle {
    val rb = new RBMUTargetInterface()

    // 外部接口
    val vmid_pasid_map  = Output(Vec(8, UInt(20.W)))
    val ptw_root_ptr    = Output(UInt(64.W))

    // 全局同步
    val global_sync     = Input(Bool())
  })

  // ==========================================
  // VMID_PASID_MAP[8] (Offset 0x000-0x01C)
  // ==========================================
  val vmid_pasid_map_shadow = RegInit(VecInit(Seq.fill(8)(0.U(20.W))))
  val vmid_pasid_map_reg    = RegInit(VecInit(Seq.fill(8)(0.U(20.W))))

  // ==========================================
  // PTW_ROOT_PTR (Offset 0x100)
  // ==========================================
  val ptw_root_ptr_shadow = RegInit(0.U(64.W))
  val ptw_root_ptr_reg    = RegInit(0.U(64.W))

  // ==========================================
  // 写入逻辑 (RBMU -> Shadow Latch)
  // ==========================================
  when(io.rb.wr_valid) {
    switch(io.rb.wr_offset) {
      is(0x000.U) { vmid_pasid_map_shadow(0) := io.rb.wr_data(19, 0) }
      is(0x004.U) { vmid_pasid_map_shadow(1) := io.rb.wr_data(19, 0) }
      is(0x008.U) { vmid_pasid_map_shadow(2) := io.rb.wr_data(19, 0) }
      is(0x00C.U) { vmid_pasid_map_shadow(3) := io.rb.wr_data(19, 0) }
      is(0x010.U) { vmid_pasid_map_shadow(4) := io.rb.wr_data(19, 0) }
      is(0x014.U) { vmid_pasid_map_shadow(5) := io.rb.wr_data(19, 0) }
      is(0x018.U) { vmid_pasid_map_shadow(6) := io.rb.wr_data(19, 0) }
      is(0x01C.U) { vmid_pasid_map_shadow(7) := io.rb.wr_data(19, 0) }
      is(0x100.U) { ptw_root_ptr_shadow := io.rb.wr_data }
    }
  }

  // ==========================================
  // 全局同步: Shadow -> Active Register
  // ==========================================
  when(io.global_sync) {
    for (i <- 0 until 8) {
      vmid_pasid_map_reg(i) := vmid_pasid_map_shadow(i)
    }
    ptw_root_ptr_reg := ptw_root_ptr_shadow
  }

  // ==========================================
  // 读取逻辑 (组合逻辑)
  // ==========================================
  io.rb.rd_data := 0.U(64.W)
  when(io.rb.rd_valid) {
    switch(io.rb.rd_offset) {
      is(0x000.U) { io.rb.rd_data := Cat(0.U(44.W), vmid_pasid_map_reg(0)) }
      is(0x004.U) { io.rb.rd_data := Cat(0.U(44.W), vmid_pasid_map_reg(1)) }
      is(0x008.U) { io.rb.rd_data := Cat(0.U(44.W), vmid_pasid_map_reg(2)) }
      is(0x00C.U) { io.rb.rd_data := Cat(0.U(44.W), vmid_pasid_map_reg(3)) }
      is(0x010.U) { io.rb.rd_data := Cat(0.U(44.W), vmid_pasid_map_reg(4)) }
      is(0x014.U) { io.rb.rd_data := Cat(0.U(44.W), vmid_pasid_map_reg(5)) }
      is(0x018.U) { io.rb.rd_data := Cat(0.U(44.W), vmid_pasid_map_reg(6)) }
      is(0x01C.U) { io.rb.rd_data := Cat(0.U(44.W), vmid_pasid_map_reg(7)) }
      is(0x100.U) { io.rb.rd_data := Cat(0.U(32.W), ptw_root_ptr_reg(31, 0)) }
      is(0x104.U) { io.rb.rd_data := Cat(0.U(32.W), ptw_root_ptr_reg(63, 32)) }
    }
  }

  // ==========================================
  // 输出连接
  // ==========================================
  for (i <- 0 until 8) {
    io.vmid_pasid_map(i) := vmid_pasid_map_reg(i)
  }
  io.ptw_root_ptr := ptw_root_ptr_reg
}

object GMMURegisters {
  val regDescriptors: Seq[RegDescriptor] = Seq(
    RegDescriptor("VMID_PASID_MAP[0]", 0x000, RegAttr.RW_Shadow, "VMID 0 到 PASID 映射"),
    RegDescriptor("VMID_PASID_MAP[1]", 0x004, RegAttr.RW_Shadow, "VMID 1 到 PASID 映射"),
    RegDescriptor("VMID_PASID_MAP[2]", 0x008, RegAttr.RW_Shadow, "VMID 2 到 PASID 映射"),
    RegDescriptor("VMID_PASID_MAP[3]", 0x00C, RegAttr.RW_Shadow, "VMID 3 到 PASID 映射"),
    RegDescriptor("VMID_PASID_MAP[4]", 0x010, RegAttr.RW_Shadow, "VMID 4 到 PASID 映射"),
    RegDescriptor("VMID_PASID_MAP[5]", 0x014, RegAttr.RW_Shadow, "VMID 5 到 PASID 映射"),
    RegDescriptor("VMID_PASID_MAP[6]", 0x018, RegAttr.RW_Shadow, "VMID 6 到 PASID 映射"),
    RegDescriptor("VMID_PASID_MAP[7]", 0x01C, RegAttr.RW_Shadow, "VMID 7 到 PASID 映射"),
    RegDescriptor("PTW_ROOT_PTR",      0x100, RegAttr.RW_Shadow, "四级页表根指针 (低 32 位)"),
    RegDescriptor("PTW_ROOT_PTR_HI",   0x104, RegAttr.RW_Shadow, "四级页表根指针 (高 32 位)"),
  )
}
