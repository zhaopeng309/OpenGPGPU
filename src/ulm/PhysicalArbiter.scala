package opengpgpu.ulm

import chisel3._
import chisel3.util._

/**
 * Physical Arbiter (物理端口仲裁器)
 *
 * 实现 3 级优先级仲裁，仲裁来自 L1D、Smem、TensorCore 的物理 Bank 访问请求。
 * 优先级顺序 (从高到低):
 *   1. WGMMA (TensorCore) - 最高优先级，矩阵计算不可停顿
 *   2. LSU (L1D/Smem)     - 中等优先级，SIMT 访存
 *   3. TMA                 - 最低优先级，块搬运可延迟
 *
 * 参考 ULM_MAS.md 第 2.2 节: 多主设备端口仲裁
 * 和 ULM_MAS.md 第 7 章: ULM 微架构顶层数据通路图
 */
class PhysicalArbiter(implicit cfg: ULMConfig) extends Module {
  val io = IO(new Bundle {
    // 来自 L1D Controller 的物理请求
    val l1d_req = Flipped(Decoupled(new PhysReq()))
    val l1d_resp = Valid(new PhysResp())

    // 来自 Smem Controller 的物理请求
    val smem_req = Flipped(Decoupled(new PhysReq()))
    val smem_resp = Valid(new PhysResp())

    // 来自 TensorCore 的物理请求 (WGMMA)
    val tc_req = Flipped(Decoupled(new PhysReq()))
    val tc_resp = Valid(new PhysResp())

    // 发往 ULM Data Core (32-Bank SRAM) 的物理请求
    val phys_req = Decoupled(new BankAccessReq())
    val phys_resp = Flipped(Valid(new BankAccessResp()))

    // 状态
    val busy = Output(Bool())
    val selected_source = Output(UInt(2.W))
  })

  // ── 仲裁状态 ──
  object ArbState extends ChiselEnum {
    val sIdle, sWaitResp = Value
  }
  import ArbState._

  val state = RegInit(sIdle)

  // ── 请求寄存器 (保存当前服务的请求) ──
  val active_req = Reg(new PhysReq())
  val active_source = Reg(UInt(2.W)) // 0=L1D, 1=Smem, 2=TC

  // ── 优先级编码 ──
  // 优先级: TC (WGMMA) > L1D (LSU) > Smem (TMA)
  // 使用固定优先级仲裁器
  val tc_valid = io.tc_req.valid
  val l1d_valid = io.l1d_req.valid
  val smem_valid = io.smem_req.valid

  // 仲裁选择逻辑
  val grant_tc  = tc_valid
  val grant_l1d = !tc_valid && l1d_valid
  val grant_smem = !tc_valid && !l1d_valid && smem_valid

  val any_request = tc_valid || l1d_valid || smem_valid

  // ── 选中的请求 ──
  val selected_req = Wire(new PhysReq())
  val selected_src = Wire(UInt(2.W))

  when(grant_tc) {
    selected_req := io.tc_req.bits
    selected_src := 2.U
  }.elsewhen(grant_l1d) {
    selected_req := io.l1d_req.bits
    selected_src := 0.U
  }.otherwise {
    selected_req := io.smem_req.bits
    selected_src := 1.U
  }

  // ── 默认输出值 (避免未初始化错误) ──
  io.phys_req.valid := false.B
  io.phys_req.bits.bank_id := DontCare
  io.phys_req.bits.row_addr := DontCare
  io.phys_req.bits.write := DontCare
  io.phys_req.bits.data := DontCare
  io.phys_req.bits.byte_en := DontCare

  // ── 状态机 ──
  switch(state) {
    is(sIdle) {
      when(any_request) {
        // 锁存选中的请求
        active_req := selected_req
        active_source := selected_src

        // 发送到物理 Bank
        io.phys_req.valid := true.B
        io.phys_req.bits.bank_id := selected_req.bank_id
        io.phys_req.bits.row_addr := selected_req.row_addr
        io.phys_req.bits.write := selected_req.write
        io.phys_req.bits.data := selected_req.data
        io.phys_req.bits.byte_en := selected_req.byte_en

        when(io.phys_req.ready) {
          // 读操作需要等待响应
          when(!selected_req.write) {
            state := sWaitResp
          }.otherwise {
            // 写操作立即完成
            state := sIdle
          }
        }
      }
    }

    is(sWaitResp) {
      when(io.phys_resp.valid) {
        // 将响应路由回对应的源
        state := sIdle
      }
    }
  }

  // ── 响应路由 ──
  // 将物理 Bank 的响应路由回对应的请求源
  io.l1d_resp.valid := false.B
  io.l1d_resp.bits := DontCare
  io.smem_resp.valid := false.B
  io.smem_resp.bits := DontCare
  io.tc_resp.valid := false.B
  io.tc_resp.bits := DontCare

  when(state === sWaitResp && io.phys_resp.valid) {
    switch(active_source) {
      is(0.U) {
        io.l1d_resp.valid := true.B
        io.l1d_resp.bits.valid := true.B
        io.l1d_resp.bits.data := io.phys_resp.bits.data
        io.l1d_resp.bits.source_id := 0.U
      }
      is(1.U) {
        io.smem_resp.valid := true.B
        io.smem_resp.bits.valid := true.B
        io.smem_resp.bits.data := io.phys_resp.bits.data
        io.smem_resp.bits.source_id := 1.U
      }
      is(2.U) {
        io.tc_resp.valid := true.B
        io.tc_resp.bits.valid := true.B
        io.tc_resp.bits.data := io.phys_resp.bits.data
        io.tc_resp.bits.source_id := 2.U
      }
    }
  }

  // ── 输入就绪信号 ──
  // 只有在 Idle 状态且没有正在处理的请求时才接收新请求
  io.tc_req.ready := state === sIdle && !any_request
  io.l1d_req.ready := state === sIdle && !any_request
  io.smem_req.ready := state === sIdle && !any_request

  // ── 状态输出 ──
  io.busy := state =/= sIdle
  io.selected_source := active_source
}
