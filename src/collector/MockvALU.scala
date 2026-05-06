package opengpgpu.collector

import chisel3._
import chisel3.util._
import scheduler.ScoreboardReleaseReq

class MockvALU(delay: Int = 3)(implicit config: CollectorConfig) extends Module {
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(new OperandBundle()))
    val release = Valid(new ScoreboardReleaseReq())
  })

  // Simple shift register to simulate execution delay
  val validShift = RegInit(0.U(delay.W))
  val dataShift = Reg(Vec(delay, new OperandBundle()))
  
  io.issue.ready := true.B // Always ready to accept
  
  when(io.issue.valid) {
    validShift := Cat(validShift(delay - 2, 0), 1.U(1.W))
    dataShift(0) := io.issue.bits
  }.otherwise {
    validShift := Cat(validShift(delay - 2, 0), 0.U(1.W))
  }
  
  for (i <- 1 until delay) {
    dataShift(i) := dataShift(i-1)
  }
  
  val outValid = validShift(delay - 1)
  val outData = dataShift(delay - 1)
  
  io.release.valid := outValid
  io.release.bits.warpId := outData.wid
  io.release.bits.regId := outData.rd
}
