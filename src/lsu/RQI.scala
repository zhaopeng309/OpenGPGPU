package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}
import opengpgpu.ulm.{ULMRequest, ULMConfig}

/**
 * Request Queue Issue (RQI)
 * 发射器，将 MRQ/SDQ 中的请求转换为 ULMRequest 并发送到 ULM
 */
class RQI(implicit config: CollectorConfig, lsuCfg: LSUConfig, ulmCfg: ULMConfig) extends Module {
  val io = IO(new Bundle {
    // 从 MRQ 接收准备好的请求
    val mrq_req = Flipped(Decoupled(new LSURequest()))

    // 从 SDQ 接收对齐好的数据 (Store)
    val sdq_data = Flipped(Decoupled(new GlobalSDQDeq()))

    // 向 ULM 发送的请求
    val ulm_req = Decoupled(new ULMRequest())
  })

  // 默认不发送
  io.ulm_req.valid := false.B
  io.ulm_req.bits := DontCare
  io.mrq_req.ready := false.B
  io.sdq_data.ready := false.B

  val is_store = io.mrq_req.bits.op_type === LSUOpType.STG || io.mrq_req.bits.op_type === LSUOpType.STS

  when(io.mrq_req.valid) {
    when(is_store) {
      // 需要等待 SDQ 数据
      when(io.sdq_data.valid) {
        io.ulm_req.valid := true.B
        io.ulm_req.bits.valid := true.B
        io.ulm_req.bits.req_type := Mux(io.mrq_req.bits.op_type === LSUOpType.STG, opengpgpu.ulm.ULMReqType.L1D_WRITE, opengpgpu.ulm.ULMReqType.SMEM_WRITE)
        io.ulm_req.bits.addr := io.mrq_req.bits.addr
        io.ulm_req.bits.data := io.sdq_data.bits.data
        io.ulm_req.bits.byte_mask := io.sdq_data.bits.byte_enable
        io.ulm_req.bits.source_id := 0.U // LSU Source ID (0)
        io.ulm_req.bits.warp_id := io.mrq_req.bits.warp_id
        io.ulm_req.bits.rd_index := io.mrq_req.bits.rd_index
        io.ulm_req.bits.active_mask := io.mrq_req.bits.active_mask
        io.ulm_req.bits.barrier_id := io.mrq_req.bits.barrier_id

        when(io.ulm_req.ready) {
          io.mrq_req.ready := true.B
          io.sdq_data.ready := true.B
        }
      }
    }.otherwise {
      // 只需要 MRQ 的信息 (Load)
      io.ulm_req.valid := true.B
      io.ulm_req.bits.valid := true.B
      io.ulm_req.bits.req_type := Mux(io.mrq_req.bits.op_type === LSUOpType.LDG, opengpgpu.ulm.ULMReqType.L1D_READ, opengpgpu.ulm.ULMReqType.SMEM_READ)
      io.ulm_req.bits.addr := io.mrq_req.bits.addr
      io.ulm_req.bits.data := 0.U // Load 没有数据
      io.ulm_req.bits.byte_mask := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U
      io.ulm_req.bits.source_id := 0.U // LSU Source ID (0)
      io.ulm_req.bits.warp_id := io.mrq_req.bits.warp_id
      io.ulm_req.bits.rd_index := io.mrq_req.bits.rd_index
      io.ulm_req.bits.active_mask := io.mrq_req.bits.active_mask
      io.ulm_req.bits.barrier_id := io.mrq_req.bits.barrier_id

      when(io.ulm_req.ready) {
        io.mrq_req.ready := true.B
      }
    }
  }
}
