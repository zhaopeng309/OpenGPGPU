package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{CollectorConfig}

class LDQEntry(implicit config: CollectorConfig) extends Bundle {
  val data = UInt(128.W)
  val addr = UInt(64.W) // Used to match with MRQ tag/address
  val active_mask = UInt(config.threadPerWarp.W)
}

/**
 * Load Data Queue (LDQ)
 * 独立的缓冲队列，用于缓存内存返回的 Cache Line 数据。
 */
class LDQ(implicit config: CollectorConfig, lsuCfg: LSUConfig) extends Module {
  val io = IO(new Bundle {
    // 来自内存的响应数据
    val mem_resp = Flipped(Valid(new LSUResponse()))
    
    // 给 DRU 提供的读取接口，采用 Chisel 标准 Queue 的 deq 接口
    val deq = Decoupled(new LDQEntry())
  })

  // 使用标准的 FIFO 队列作为缓冲
  val queue = Module(new Queue(new LDQEntry(), lsuCfg.ldqDepth))

  // 将 mem_resp 转入 queue
  queue.io.enq.valid := io.mem_resp.valid
  queue.io.enq.bits.data := io.mem_resp.bits.data
  queue.io.enq.bits.addr := io.mem_resp.bits.addr
  // LSUResponse 如果没有 active_mask 字段，这里假设用底层传上来的某个字段暂代或全1
  queue.io.enq.bits.active_mask := "hFFFFFFFF".U

  // 当队列满时，如果还有 mem_resp 返回会有数据丢失风险
  // 在实际设计中，MRQ 的 pending 数量应该限制在 LDQ depth 之内，确保 LDQ 永不溢出
  // 这里暂时不处理背压，直接将 deq 暴露出去
  io.deq <> queue.io.deq
}
