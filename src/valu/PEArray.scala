package opengpgpu.valu

import chisel3._
import chisel3.util._
import opengpgpu.collector.CollectorConfig

class PEArrayIO(implicit config: CollectorConfig) extends Bundle {
  val opcode = Input(UInt(8.W))
  val src1   = Input(Vec(8, UInt(config.vGPRWidth.W)))
  val src2   = Input(Vec(8, UInt(config.vGPRWidth.W)))
  val res    = Output(Vec(8, UInt(config.vGPRWidth.W)))
}

class PEArray(implicit config: CollectorConfig) extends Module {
  val io = IO(new PEArrayIO())
  
  for (i <- 0 until 8) {
    val s1 = io.src1(i)
    val s2 = io.src2(i)
    val res = WireInit(0.U(config.vGPRWidth.W))
    switch (io.opcode) {
      is (vALUOpcode.ADD) { res := s1 + s2 }
      is (vALUOpcode.SUB) { res := s1 - s2 }
      is (vALUOpcode.AND) { res := s1 & s2 }
      is (vALUOpcode.OR)  { res := s1 | s2 }
      is (vALUOpcode.XOR) { res := s1 ^ s2 }
    }
    io.res(i) := res
  }
}
