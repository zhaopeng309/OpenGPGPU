package opengpgpu.register

import chisel3._
import chisel3.util._

class pGPR(config: RegisterFileConfig = RegisterFileConfig()) extends Module {
  val io = IO(new Bundle {
    // Read port
    val read_wid    = Input(UInt(config.warpIdWidth.W))
    val read_pid    = Input(UInt(config.predIdWidth.W))
    val read_mask   = Output(UInt(config.threadPerWarp.W))
    
    // Write port
    val write_en    = Input(Bool())
    val write_wid   = Input(UInt(config.warpIdWidth.W))
    val write_pid   = Input(UInt(config.predIdWidth.W))
    val write_data  = Input(UInt(config.threadPerWarp.W))
    val write_mask  = Input(UInt(config.threadPerWarp.W))
  })

  // 16 Warps, each with 8 predicate registers, each is 32-bit (Vec of 32 Bools for bit-level mask)
  val prf = RegInit(VecInit(Seq.fill(config.numWarps)(
    VecInit(Seq.fill(config.numPredRegs)(
      VecInit(Seq.fill(config.threadPerWarp)(false.B))
    ))
  )))

  // Read logic: P7 is hardwired to all 1s
  val readData = Wire(Vec(config.threadPerWarp, Bool()))
  when(io.read_pid === 7.U) {
    for (i <- 0 until config.threadPerWarp) {
      readData(i) := true.B
    }
  } .otherwise {
    readData := prf(io.read_wid)(io.read_pid)
  }
  io.read_mask := readData.asUInt

  // Write logic: Bit-level update based on write_mask, P7 is read-only
  when(io.write_en && io.write_pid =/= 7.U) {
    for (i <- 0 until config.threadPerWarp) {
      when(io.write_mask(i)) {
        prf(io.write_wid)(io.write_pid)(i) := io.write_data(i)
      }
    }
  }
}
