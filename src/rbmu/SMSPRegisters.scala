package opengpgpu.rbmu

import chisel3._
import chisel3.util._

/**
 * SMSP 寄存器模块
 *
 * 根据 SFR_Spec.md 3.2 节定义，每个 SMSP 内部包含以下寄存器：
 *
 * === 控制与状态寄存器 ===
 * | Offset  | 名称                  | 属性 | 描述                                    |
 * |---------|-----------------------|------|----------------------------------------|
 * | 0x000   | SMSP_STATUS           | RO   | 当前活跃 Warp 计数, 流水线挂起标志       |
 * | 0x004   | SMSP_IFU_CTRL         | RW   | IFU 子分区控制 (使能/暂停/Flush)         |
 * | 0x008   | SMSP_IFU_STATUS       | RO   | IFU 状态 (空闲/取指/Miss)               |
 * | 0x010   | SMSP_IB_CREDIT        | RW   | 影子寄存器。I-Buffer 信用额度配置        |
 * | 0x020   | SMSP_WARP_INIT_PC     | RW   | Warp 初始化默认 PC                      |
 * | 0x030   | SMSP_VGPR_WARP_OFFSET | RW   | vGPR Warp 偏移量配置                    |
 * | 0x040   | SMSP_KERNEL_CTX       | RW   | Kernel 上下文配置                       |
 *
 * === PST 表状态寄存器 ===
 * | Offset  | 名称                  | 属性 | 描述                                    |
 * |---------|-----------------------|------|----------------------------------------|
 * | 0x080   | SMSP_PST_VALID        | RO   | 8 个 Warp 的 valid 位掩码               |
 * | 0x084   | SMSP_PST_STATE        | RO   | 8 个 Warp 的状态编码                    |
 * | 0x088   | SMSP_PST_CREDITS      | RO   | 8 个 Warp 的 I-Buffer 信用计数          |
 * | 0x08C   | SMSP_PST_FLUSH_TAG    | RO   | 8 个 Warp 的 flush_gen_tag              |
 * | 0x090   | SMSP_PST_INST_ID_LO   | RO   | Warp[0:3] 的 inst_id (低 32 位)         |
 * | 0x094   | SMSP_PST_INST_ID_HI   | RO   | Warp[4:7] 的 inst_id (低 32 位)         |
 * | 0x100-0x11C | SMSP_W_PC[0:7]    | RO   | 8 个 Warp 的当前流水线 PC (用于 Debug)   |
 *
 * === L0I/K 缓存策略寄存器 ===
 * | Offset  | 名称                  | 属性 | 描述                                    |
 * |---------|-----------------------|------|----------------------------------------|
 * | 0x200   | SMSP_K_PROBE_EN       | RW   | Decoder 联动。控制 L0K 常量嗅探使能      |
 * | 0x204   | SMSP_L0I_PREFETCH     | RW   | L0 I-Cache 预取深度配置                 |
 * | 0x208   | SMSP_L0K_PREFETCH     | RW   | L0 K-Cache 预取深度配置                 |
 *
 * === 寄存器文件基址配置 ===
 * | Offset  | 名称                  | 属性 | 描述                                    |
 * |---------|-----------------------|------|----------------------------------------|
 * | 0x300   | SMSP_GPR_BASE         | RW   | 影子寄存器。vGPR 物理起始地址            |
 * | 0x304   | SMSP_UGPR_BASE        | RW   | 影子寄存器。uGPR 物理起始地址            |
 * | 0x308   | SMSP_PGPR_BASE        | RW   | 影子寄存器。pGPR 物理起始地址            |
 *
 * 架构变更说明 (v2.0):
 * SR_CTA_ID_X/Y/Z、SR_WARP_ID、SR_LANE_ID 已从 SFR 空间中移除。
 * Block ID (X, Y, Z) 由 Block Scheduler 在分发 Warp 时直接写入 uGPR[0:2]。
 * Lane ID (0~31) 由 Issue Stage 在首次激活 Warp 时直接写入 vGPR[0]。
 * 详见 SFR_Spec.md 第 4.2 节。
 */
class SMSPRegisters(targetID: Int) extends Module {
  val io = IO(new Bundle {
    // RBMU 寄存器访问接口 (由 Ring Stop 驱动)
    val rb = new RBMUTargetInterface()

    // === 外部更新接口 (由 SMSP 内部逻辑驱动) ===

    // SMSP_STATUS 更新
    val active_warp_count = Input(UInt(5.W))
    val pipeline_stall    = Input(Bool())

    // SMSP_IFU_CTRL 输出
    val ifu_enable        = Output(Bool())
    val ifu_halt          = Output(Bool())
    val ifu_flush_all     = Output(Bool())

    // SMSP_IFU_STATUS 更新
    val ifu_idle          = Input(Bool())
    val ifu_fetch_active  = Input(Bool())
    val ifu_icache_miss   = Input(Bool())

    // SMSP_IB_CREDIT 影子寄存器
    val ib_credit_shadow  = Output(UInt(32.W))

    // SMSP_WARP_INIT_PC 输出
    val warp_init_pc      = Output(UInt(48.W))

    // SMSP_VGPR_WARP_OFFSET 输出
    val vgpr_warp_offset  = Output(UInt(32.W))

    // SMSP_KERNEL_CTX 输出
    val kernel_id         = Output(UInt(8.W))
    val kernel_priority   = Output(UInt(8.W))

    // PST 表状态更新 (来自 IFU/PST)
    val pst_valid_mask    = Input(UInt(8.W))
    val pst_state_bits    = Input(UInt(24.W))   // 8 warps × 3-bit state
    val pst_credits_bits  = Input(UInt(24.W))   // 8 warps × 3-bit credits
    val pst_flush_tag_bits = Input(UInt(16.W))  // 8 warps × 2-bit flush_gen_tag
    val pst_inst_id       = Input(Vec(8, UInt(64.W))) // 8 warps × 64-bit inst_id

    // SMSP_W_PC 更新 (来自 IFU/PST)
    val warp_pc_update    = Input(Vec(8, UInt(48.W)))

    // SMSP_K_PROBE_EN
    val k_probe_en        = Output(Bool())

    // SMSP_L0I_PREFETCH / SMSP_L0K_PREFETCH 输出
    val l0i_prefetch_depth = Output(UInt(8.W))
    val l0k_prefetch_depth = Output(UInt(8.W))

    // SMSP_GPR_BASE / UGPR_BASE / PGPR_BASE (影子寄存器)
    val gpr_base_shadow   = Output(UInt(32.W))
    val ugpr_base_shadow  = Output(UInt(32.W))
    val pgpr_base_shadow  = Output(UInt(32.W))

    // 全局同步信号 (来自 SM 顶层)
    val global_sync       = Input(Bool())
  })

  // ==========================================
  // 寄存器定义
  // ==========================================

  // --- RO 寄存器 (组合逻辑读取) ---
  // SMSP_STATUS: [4:0] active_warp_count, [5] pipeline_stall
  val smsp_status = Wire(UInt(32.W))
  smsp_status := Cat(
    0.U(26.W),                    // 保留位
    io.pipeline_stall,            // bit 5: 流水线挂起标志
    io.active_warp_count          // bit 4:0: 活跃 Warp 计数
  )

  // SMSP_IFU_STATUS: [0] ifu_idle, [1] ifu_fetch_active, [2] ifu_icache_miss
  val smsp_ifu_status = Wire(UInt(32.W))
  smsp_ifu_status := Cat(
    0.U(29.W),
    io.ifu_icache_miss,           // bit 2: I-Cache Miss 等待中
    io.ifu_fetch_active,          // bit 1: 有 Warp 正在取指
    io.ifu_idle                   // bit 0: IFU 空闲
  )

  // --- RW 寄存器 (直接写入, 无影子) ---
  // SMSP_IFU_CTRL (Offset 0x004)
  val ifu_ctrl_reg = RegInit(0.U(32.W))

  // SMSP_WARP_INIT_PC (Offset 0x020)
  val warp_init_pc_reg = RegInit(0.U(48.W))

  // SMSP_VGPR_WARP_OFFSET (Offset 0x030)
  val vgpr_warp_offset_reg = RegInit(0.U(32.W))

  // SMSP_KERNEL_CTX (Offset 0x040)
  val kernel_ctx_reg = RegInit(0.U(32.W))

  // SMSP_L0I_PREFETCH (Offset 0x204)
  val l0i_prefetch_reg = RegInit(0.U(8.W))

  // SMSP_L0K_PREFETCH (Offset 0x208)
  val l0k_prefetch_reg = RegInit(0.U(8.W))

  // --- RW 影子寄存器 ---
  // SMSP_IB_CREDIT (Offset 0x010)
  val ib_credit_reg = RegInit(0.U(32.W))
  val ib_credit_shadow = RegInit(0.U(32.W))

  // SMSP_K_PROBE_EN (Offset 0x200)
  val k_probe_en_reg = RegInit(false.B)
  val k_probe_en_shadow = RegInit(false.B)

  // SMSP_GPR_BASE (Offset 0x300)
  val gpr_base_reg = RegInit(0.U(32.W))
  val gpr_base_shadow = RegInit(0.U(32.W))

  // SMSP_UGPR_BASE (Offset 0x304)
  val ugpr_base_reg = RegInit(0.U(32.W))
  val ugpr_base_shadow = RegInit(0.U(32.W))

  // SMSP_PGPR_BASE (Offset 0x308)
  val pgpr_base_reg = RegInit(0.U(32.W))
  val pgpr_base_shadow = RegInit(0.U(32.W))

  // --- PST 表状态寄存器 (RO, 由外部更新) ---
  val pst_valid_reg    = RegInit(0.U(8.W))
  val pst_state_reg    = RegInit(0.U(24.W))
  val pst_credits_reg  = RegInit(0.U(24.W))
  val pst_flush_tag_reg = RegInit(0.U(16.W))
  val pst_inst_id_reg  = RegInit(VecInit(Seq.fill(8)(0.U(64.W))))

  // --- SMSP_W_PC[0:7] (RO, 由外部更新) ---
  val warp_pc_regs = RegInit(VecInit(Seq.fill(8)(0.U(48.W))))

  // ==========================================
  // 写入逻辑
  // ==========================================

  // 直接写入寄存器 (无影子)
  when(io.rb.wr_valid) {
    switch(io.rb.wr_offset) {
      is(0x004.U) { ifu_ctrl_reg := io.rb.wr_data(31, 0) }
      is(0x020.U) { warp_init_pc_reg := io.rb.wr_data(47, 0) }
      is(0x024.U) { warp_init_pc_reg := Cat(io.rb.wr_data(15, 0), warp_init_pc_reg(31, 0)) }  // 高 16 位写入
      is(0x030.U) { vgpr_warp_offset_reg := io.rb.wr_data(31, 0) }
      is(0x040.U) { kernel_ctx_reg := io.rb.wr_data(31, 0) }
      is(0x204.U) { l0i_prefetch_reg := io.rb.wr_data(7, 0) }
      is(0x208.U) { l0k_prefetch_reg := io.rb.wr_data(7, 0) }
    }
  }

  // 影子寄存器写入逻辑
  // RBMU 写入 -> Shadow Latch
  when(io.rb.wr_valid) {
    switch(io.rb.wr_offset) {
      is(0x010.U) { ib_credit_shadow := io.rb.wr_data }
      is(0x200.U) { k_probe_en_shadow := io.rb.wr_data(0) }
      is(0x300.U) { gpr_base_shadow := io.rb.wr_data }
      is(0x304.U) { ugpr_base_shadow := io.rb.wr_data }
      is(0x308.U) { pgpr_base_shadow := io.rb.wr_data }
    }
  }

  // ==========================================
  // 全局同步: Shadow -> Active Register
  // ==========================================
  when(io.global_sync) {
    ib_credit_reg   := ib_credit_shadow
    k_probe_en_reg  := k_probe_en_shadow
    gpr_base_reg    := gpr_base_shadow
    ugpr_base_reg   := ugpr_base_shadow
    pgpr_base_reg   := pgpr_base_shadow
  }

  // ==========================================
  // PST 表状态更新 (来自 IFU/PST)
  // ==========================================
  pst_valid_reg     := io.pst_valid_mask
  pst_state_reg     := io.pst_state_bits
  pst_credits_reg   := io.pst_credits_bits
  pst_flush_tag_reg := io.pst_flush_tag_bits
  for (i <- 0 until 8) {
    pst_inst_id_reg(i) := io.pst_inst_id(i)
  }

  // ==========================================
  // Warp PC 更新 (来自 IFU/PST)
  // ==========================================
  for (i <- 0 until 8) {
    warp_pc_regs(i) := io.warp_pc_update(i)
  }

  // ==========================================
  // 读取逻辑 (组合逻辑)
  // ==========================================
  io.rb.rd_data := 0.U(64.W)
  when(io.rb.rd_valid) {
    switch(io.rb.rd_offset) {
      // --- 控制与状态寄存器 ---
      is(0x000.U) { io.rb.rd_data := Cat(0.U(32.W), smsp_status) }
      is(0x004.U) { io.rb.rd_data := Cat(0.U(32.W), ifu_ctrl_reg) }
      is(0x008.U) { io.rb.rd_data := Cat(0.U(32.W), smsp_ifu_status) }
      is(0x010.U) { io.rb.rd_data := Cat(0.U(32.W), ib_credit_reg) }
      is(0x020.U) { io.rb.rd_data := Cat(0.U(16.W), warp_init_pc_reg) }  // 48-bit PC 对齐到 64-bit
      is(0x024.U) { io.rb.rd_data := Cat(0.U(48.W), warp_init_pc_reg(47, 32)) }  // 高 16 位
      is(0x030.U) { io.rb.rd_data := Cat(0.U(32.W), vgpr_warp_offset_reg) }
      is(0x040.U) { io.rb.rd_data := Cat(0.U(32.W), kernel_ctx_reg) }

      // --- PST 表状态寄存器 ---
      is(0x080.U) { io.rb.rd_data := Cat(0.U(56.W), pst_valid_reg) }
      is(0x084.U) { io.rb.rd_data := Cat(0.U(40.W), pst_state_reg) }
      is(0x088.U) { io.rb.rd_data := Cat(0.U(40.W), pst_credits_reg) }
      is(0x08C.U) { io.rb.rd_data := Cat(0.U(48.W), pst_flush_tag_reg) }
      is(0x090.U) {
        // Warp[0:3] inst_id 低 32 位打包
        io.rb.rd_data := Cat(
          pst_inst_id_reg(3)(31, 0),
          pst_inst_id_reg(2)(31, 0),
          pst_inst_id_reg(1)(31, 0),
          pst_inst_id_reg(0)(31, 0)
        )
      }
      is(0x094.U) {
        // Warp[4:7] inst_id 低 32 位打包
        io.rb.rd_data := Cat(
          pst_inst_id_reg(7)(31, 0),
          pst_inst_id_reg(6)(31, 0),
          pst_inst_id_reg(5)(31, 0),
          pst_inst_id_reg(4)(31, 0)
        )
      }

      // --- Warp PC (Debug) ---
      is(0x100.U) { io.rb.rd_data := Cat(0.U(32.W), warp_pc_regs(0)(31, 0)) }
      is(0x104.U) { io.rb.rd_data := Cat(0.U(48.W), warp_pc_regs(0)(47, 32)) }
      is(0x108.U) { io.rb.rd_data := Cat(0.U(32.W), warp_pc_regs(1)(31, 0)) }
      is(0x10C.U) { io.rb.rd_data := Cat(0.U(48.W), warp_pc_regs(1)(47, 32)) }
      is(0x110.U) { io.rb.rd_data := Cat(0.U(32.W), warp_pc_regs(2)(31, 0)) }
      is(0x114.U) { io.rb.rd_data := Cat(0.U(48.W), warp_pc_regs(2)(47, 32)) }
      is(0x118.U) { io.rb.rd_data := Cat(0.U(32.W), warp_pc_regs(3)(31, 0)) }
      is(0x11C.U) { io.rb.rd_data := Cat(0.U(48.W), warp_pc_regs(3)(47, 32)) }
      is(0x120.U) { io.rb.rd_data := Cat(0.U(32.W), warp_pc_regs(4)(31, 0)) }
      is(0x124.U) { io.rb.rd_data := Cat(0.U(48.W), warp_pc_regs(4)(47, 32)) }
      is(0x128.U) { io.rb.rd_data := Cat(0.U(32.W), warp_pc_regs(5)(31, 0)) }
      is(0x12C.U) { io.rb.rd_data := Cat(0.U(48.W), warp_pc_regs(5)(47, 32)) }
      is(0x130.U) { io.rb.rd_data := Cat(0.U(32.W), warp_pc_regs(6)(31, 0)) }
      is(0x134.U) { io.rb.rd_data := Cat(0.U(48.W), warp_pc_regs(6)(47, 32)) }
      is(0x138.U) { io.rb.rd_data := Cat(0.U(32.W), warp_pc_regs(7)(31, 0)) }
      is(0x13C.U) { io.rb.rd_data := Cat(0.U(48.W), warp_pc_regs(7)(47, 32)) }

      // --- L0I/K 缓存策略 ---
      is(0x200.U) { io.rb.rd_data := Cat(0.U(63.W), k_probe_en_reg) }
      is(0x204.U) { io.rb.rd_data := Cat(0.U(56.W), l0i_prefetch_reg) }
      is(0x208.U) { io.rb.rd_data := Cat(0.U(56.W), l0k_prefetch_reg) }

      // --- 寄存器文件基址 ---
      is(0x300.U) { io.rb.rd_data := Cat(0.U(32.W), gpr_base_reg) }
      is(0x304.U) { io.rb.rd_data := Cat(0.U(32.W), ugpr_base_reg) }
      is(0x308.U) { io.rb.rd_data := Cat(0.U(32.W), pgpr_base_reg) }
    }
  }

  // ==========================================
  // 输出连接
  // ==========================================
  io.ifu_enable       := ifu_ctrl_reg(0)
  io.ifu_halt         := ifu_ctrl_reg(1)
  io.ifu_flush_all    := ifu_ctrl_reg(2)
  io.ib_credit_shadow := ib_credit_reg
  io.warp_init_pc     := warp_init_pc_reg
  io.vgpr_warp_offset := vgpr_warp_offset_reg
  io.kernel_id        := kernel_ctx_reg(7, 0)
  io.kernel_priority  := kernel_ctx_reg(15, 8)
  io.k_probe_en       := k_probe_en_reg
  io.l0i_prefetch_depth := l0i_prefetch_reg
  io.l0k_prefetch_depth := l0k_prefetch_reg
  io.gpr_base_shadow  := gpr_base_reg
  io.ugpr_base_shadow := ugpr_base_reg
  io.pgpr_base_shadow := pgpr_base_reg
}

/**
 * SMSPRegisters 伴生对象
 * 提供寄存器描述列表，用于 RBMUManager 注册和文档生成
 */
object SMSPRegisters {
  val regDescriptors: Seq[RegDescriptor] = Seq(
    // 控制与状态寄存器
    RegDescriptor("SMSP_STATUS",          0x000, RegAttr.RO, "当前活跃 Warp 计数, 流水线挂起标志"),
    RegDescriptor("SMSP_IFU_CTRL",        0x004, RegAttr.RW, "IFU 子分区控制 (使能/暂停/Flush)"),
    RegDescriptor("SMSP_IFU_STATUS",      0x008, RegAttr.RO, "IFU 状态 (空闲/取指/Miss)"),
    RegDescriptor("SMSP_IB_CREDIT",       0x010, RegAttr.RW_Shadow, "I-Buffer 信用额度配置"),
    RegDescriptor("SMSP_WARP_INIT_PC",    0x020, RegAttr.RW, "Warp 初始化默认 PC (低 32 位)"),
    RegDescriptor("SMSP_WARP_INIT_PC_HI", 0x024, RegAttr.RW, "Warp 初始化默认 PC (高 16 位)"),
    RegDescriptor("SMSP_VGPR_WARP_OFFSET",0x030, RegAttr.RW, "vGPR Warp 偏移量配置"),
    RegDescriptor("SMSP_KERNEL_CTX",      0x040, RegAttr.RW, "Kernel 上下文配置"),

    // PST 表状态寄存器
    RegDescriptor("SMSP_PST_VALID",       0x080, RegAttr.RO, "PST Warp valid 位掩码"),
    RegDescriptor("SMSP_PST_STATE",       0x084, RegAttr.RO, "PST Warp 状态编码"),
    RegDescriptor("SMSP_PST_CREDITS",     0x088, RegAttr.RO, "PST I-Buffer 信用计数"),
    RegDescriptor("SMSP_PST_FLUSH_TAG",   0x08C, RegAttr.RO, "PST Flush 代标签"),
    RegDescriptor("SMSP_PST_INST_ID_LO",  0x090, RegAttr.RO, "PST Warp[0:3] 指令计数"),
    RegDescriptor("SMSP_PST_INST_ID_HI",  0x094, RegAttr.RO, "PST Warp[4:7] 指令计数"),

    // Warp PC (Debug)
    RegDescriptor("SMSP_W_PC[0]",         0x100, RegAttr.RO, "Warp 0 当前流水线 PC (低 32 位)"),
    RegDescriptor("SMSP_W_PC[0]_HI",      0x104, RegAttr.RO, "Warp 0 当前流水线 PC (高 16 位)"),
    RegDescriptor("SMSP_W_PC[1]",         0x108, RegAttr.RO, "Warp 1 当前流水线 PC (低 32 位)"),
    RegDescriptor("SMSP_W_PC[1]_HI",      0x10C, RegAttr.RO, "Warp 1 当前流水线 PC (高 16 位)"),
    RegDescriptor("SMSP_W_PC[2]",         0x110, RegAttr.RO, "Warp 2 当前流水线 PC (低 32 位)"),
    RegDescriptor("SMSP_W_PC[2]_HI",      0x114, RegAttr.RO, "Warp 2 当前流水线 PC (高 16 位)"),
    RegDescriptor("SMSP_W_PC[3]",         0x118, RegAttr.RO, "Warp 3 当前流水线 PC (低 32 位)"),
    RegDescriptor("SMSP_W_PC[3]_HI",      0x11C, RegAttr.RO, "Warp 3 当前流水线 PC (高 16 位)"),
    RegDescriptor("SMSP_W_PC[4]",         0x120, RegAttr.RO, "Warp 4 当前流水线 PC (低 32 位)"),
    RegDescriptor("SMSP_W_PC[4]_HI",      0x124, RegAttr.RO, "Warp 4 当前流水线 PC (高 16 位)"),
    RegDescriptor("SMSP_W_PC[5]",         0x128, RegAttr.RO, "Warp 5 当前流水线 PC (低 32 位)"),
    RegDescriptor("SMSP_W_PC[5]_HI",      0x12C, RegAttr.RO, "Warp 5 当前流水线 PC (高 16 位)"),
    RegDescriptor("SMSP_W_PC[6]",         0x130, RegAttr.RO, "Warp 6 当前流水线 PC (低 32 位)"),
    RegDescriptor("SMSP_W_PC[6]_HI",      0x134, RegAttr.RO, "Warp 6 当前流水线 PC (高 16 位)"),
    RegDescriptor("SMSP_W_PC[7]",         0x138, RegAttr.RO, "Warp 7 当前流水线 PC (低 32 位)"),
    RegDescriptor("SMSP_W_PC[7]_HI",      0x13C, RegAttr.RO, "Warp 7 当前流水线 PC (高 16 位)"),

    // L0I/K 缓存策略
    RegDescriptor("SMSP_K_PROBE_EN",      0x200, RegAttr.RW, "L0K 常量嗅探使能"),
    RegDescriptor("SMSP_L0I_PREFETCH",    0x204, RegAttr.RW, "L0 I-Cache 预取深度"),
    RegDescriptor("SMSP_L0K_PREFETCH",    0x208, RegAttr.RW, "L0 K-Cache 预取深度"),

    // 寄存器文件基址
    RegDescriptor("SMSP_GPR_BASE",        0x300, RegAttr.RW_Shadow, "vGPR 物理起始地址"),
    RegDescriptor("SMSP_UGPR_BASE",       0x304, RegAttr.RW_Shadow, "uGPR 物理起始地址"),
    RegDescriptor("SMSP_PGPR_BASE",       0x308, RegAttr.RW_Shadow, "pGPR 物理起始地址"),
  )
}
