package opengpgpu.register

import chisel3._
import chisel3.util._

class uGPR(config: RegisterFileConfig = RegisterFileConfig()) extends Module {
  val io = IO(new Bundle {
    val readAddr = Input(UInt(log2Ceil(config.uGPRDepth).W))
    val readEn   = Input(Bool())
    val readData = Output(UInt(config.uGPRWidth.W))
    
    val writeAddr = Input(UInt(log2Ceil(config.uGPRDepth).W))
    val writeEn   = Input(Bool())
    val writeData = Input(UInt(config.uGPRWidth.W))
  })

  // 1R1W SyncReadMem
  val mem = SyncReadMem(config.uGPRDepth, UInt(config.uGPRWidth.W))

  io.readData := DontCare
  when(io.readEn) {
    io.readData := mem.read(io.readAddr)
  }

  when(io.writeEn) {
    mem.write(io.writeAddr, io.writeData)
  }
}
