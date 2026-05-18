package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

// ----- Interfaces -----

class GlobalSDQInput(implicit config: CollectorConfig) extends Bundle {
  val data = Vec(config.threadPerWarp, UInt(32.W)) // Store data from threads
  val ea_offsets = Vec(config.threadPerWarp, UInt(5.W)) // Word offsets (addr[6:2]) within the Cache Line
  val hit_mask = UInt(config.threadPerWarp.W) // From ACU: which threads hit the current Cache Line
}

class SharedSDQInput(implicit config: CollectorConfig) extends Bundle {
  val data = Vec(config.threadPerWarp, UInt(32.W))
  val bank_ids = Vec(config.threadPerWarp, UInt(5.W)) // Bank routing (e.g., from XOR mapping)
  val active_mask = UInt(config.threadPerWarp.W) // Which threads are active in this phase
}

class GlobalSDQOutput(implicit config: CollectorConfig) extends Bundle {
  val data = UInt(1024.W) // 32 x 32-bit compressed per Cache Line
  val byte_enable = UInt(128.W)
}

class SharedSDQOutput(implicit config: CollectorConfig) extends Bundle {
  val bank_data = Vec(32, UInt(32.W)) // Scattered data to 32 Banks
  val bank_we = UInt(32.W)            // Write enables per Bank
}

// ----- Global SDQ -----

class GlobalSDQ(val entries: Int = 16)(implicit config: CollectorConfig) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new GlobalSDQInput()))
    val deq = Decoupled(new GlobalSDQOutput())
  })

  // Basic queue for now
  class GSDQEntry extends Bundle {
    val data = UInt(1024.W)
    val be = UInt(128.W)
  }

  val q = Module(new Queue(new GSDQEntry(), entries))
  
  io.enq.ready := q.io.enq.ready
  q.io.enq.valid := io.enq.valid

  // 32x32 Crossbar for Global SDQ:
  // Routes thread data to the correct 32-bit slot in the 1024-bit Cache Line
  // ONLY for threads that are part of the current hit_mask from ACU
  val crossbar_data = Wire(Vec(32, UInt(32.W)))
  val crossbar_be = Wire(Vec(32, UInt(4.W)))
  
  for (i <- 0 until 32) {
    crossbar_data(i) := 0.U
    crossbar_be(i) := 0.U
  }

  // If multiple threads write to the same offset (collision), higher lane wins
  for (lane <- 0 until config.threadPerWarp) {
    when(io.enq.bits.hit_mask(lane)) {
      val target_slot = io.enq.bits.ea_offsets(lane)
      crossbar_data(target_slot) := io.enq.bits.data(lane)
      crossbar_be(target_slot) := "b1111".U
    }
  }

  q.io.enq.bits.data := crossbar_data.asUInt
  q.io.enq.bits.be := crossbar_be.asUInt

  io.deq.valid := q.io.deq.valid
  q.io.deq.ready := io.deq.ready
  io.deq.bits.data := q.io.deq.bits.data
  io.deq.bits.byte_enable := q.io.deq.bits.be
}

// ----- Shared SDQ -----

class SharedSDQ(val entries: Int = 16)(implicit config: CollectorConfig) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new SharedSDQInput()))
    val deq = Decoupled(new SharedSDQOutput())
  })

  class SSDQEntry extends Bundle {
    val bank_data = Vec(32, UInt(32.W))
    val bank_we = UInt(32.W)
  }

  val q = Module(new Queue(new SSDQEntry(), entries))

  io.enq.ready := q.io.enq.ready
  q.io.enq.valid := io.enq.valid

  val bank_data = Wire(Vec(32, UInt(32.W)))
  val bank_we = Wire(Vec(32, Bool()))

  for (i <- 0 until 32) {
    bank_data(i) := 0.U
    bank_we(i) := false.B
  }

  // XOR Swizzle Routing: routing Lane Data to Bank based on bank_ids
  for (lane <- 0 until config.threadPerWarp) {
    when(io.enq.bits.active_mask(lane)) {
      val target_bank = io.enq.bits.bank_ids(lane)
      bank_data(target_bank) := io.enq.bits.data(lane)
      bank_we(target_bank) := true.B
    }
  }

  q.io.enq.bits.bank_data := bank_data
  q.io.enq.bits.bank_we := bank_we.asUInt

  io.deq.valid := q.io.deq.valid
  q.io.deq.ready := io.deq.ready
  io.deq.bits.bank_data := q.io.deq.bits.bank_data
  io.deq.bits.bank_we := q.io.deq.bits.bank_we
}
