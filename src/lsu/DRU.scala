package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}
import opengpgpu.RCB.{ResultPacket, RCBConfig}

/**
 * DRU 请求包
 * 来自 MRQ 的完成通知
 */
class DRUReq(implicit config: CollectorConfig) extends Bundle {
  val op = new OperandBundle()
  val op_type = UInt(3.W)
}

/**
 * Data Routing Unit (DRU)
 *
 * 负责:
 * 1. 接收 MRQ 的完成通知
 * 2. 从 LDQ 提取数据
 * 3. 逆向路由回各个线程对应的目标寄存器位置
 * 4. 组装成 RCB 支持的 ResultPacket 进行写回
 */
class DRU(implicit config: CollectorConfig, lsuCfg: LSUConfig, rcbCfg: RCBConfig) extends Module {
  val io = IO(new Bundle {
    // 来自 MRQ 的完成通知
    val mrq_notify = Flipped(Decoupled(new DRUReq()))

    // 从 LDQ 读取数据的接口
    val ldq_deq = Flipped(Decoupled(new LDQEntry()))

    // 输出到 RCB
    val out = Decoupled(new ResultPacket())
  })

  // 状态机
  object State extends ChiselEnum {
    val sIdle, sExtractData, sOutput = Value
  }
  import State._

  val state = RegInit(sIdle)
  val current_op = Reg(new OperandBundle())
  val current_op_type = Reg(UInt(3.W))
  val current_data = Reg(Vec(config.threadPerWarp, UInt(config.vGPRWidth.W)))
  val current_write_mask = Reg(UInt(config.threadPerWarp.W))

  // 请求数（Cache Lines），假设这里由 MRQ 传递或暂时假设每次只处理 1 个 LDQ 响应作为演示
  // 完整设计中 MRQ notify 需要带 req_count 以便 DRU 知道从 LDQ 读多少次
  val expected_lines = RegInit(1.U(3.W))
  val lines_collected = RegInit(0.U(3.W))

  io.mrq_notify.ready := state === sIdle
  io.ldq_deq.ready := state === sExtractData && lines_collected < expected_lines

  when(io.mrq_notify.valid && io.mrq_notify.ready) {
    current_op := io.mrq_notify.bits.op
    current_op_type := io.mrq_notify.bits.op_type
    current_write_mask := io.mrq_notify.bits.op.activeMask
    lines_collected := 0.U
    // TODO: 从 MRQ 接收 expected_lines
    expected_lines := 1.U
    state := sExtractData
  }

  when(state === sExtractData) {
    // 初始化数据
    when(lines_collected === 0.U) {
      for (i <- 0 until config.threadPerWarp) {
        current_data(i) := 0.U
      }
    }

    when(io.ldq_deq.valid && io.ldq_deq.ready) {
      val resp_data = io.ldq_deq.bits.data
      val base_offset = io.ldq_deq.bits.byte_offset

      // 将 128-bit 数据按字节偏移分发到各个线程
      for (t <- 0 until config.threadPerWarp) {
        // 简化提取逻辑
        when(io.ldq_deq.bits.active_mask(t)) {
          val thread_byte_offset = base_offset + (t * 4).U(7.W)
          val data_idx = thread_byte_offset(4, 2)
          current_data(t) := (resp_data >> (data_idx * 32.U))(31, 0)
        }
      }

      lines_collected := lines_collected + 1.U
      when(lines_collected + 1.U === expected_lines) {
        state := sOutput
      }
    }
  }

  io.out.valid := state === sOutput
  io.out.bits.warp_id := current_op.wid
  io.out.bits.rd_index := current_op.rd
  io.out.bits.write_mask := current_write_mask
  io.out.bits.barrier_id := 0.U
  io.out.bits.source_type := 1.U // 1 = LSU
  io.out.bits.dest_type := 0.U // 0 = vGPR

  for (i <- 0 until config.threadPerWarp) {
    io.out.bits.data(i) := current_data(i)
  }

  when(io.out.valid && io.out.ready) {
    state := sIdle
  }
}
