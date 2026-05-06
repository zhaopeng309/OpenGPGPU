package opengpgpu.valu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}

class vALUIO(implicit config: CollectorConfig) extends Bundle {
  val in = Flipped(Decoupled(new OperandBundle()))
  val out = Decoupled(new ResultPacket())
}

class vALU(implicit config: CollectorConfig) extends Module {
  val io = IO(new vALUIO())
  
  val numPEs = 8
  val numBeats = config.threadPerWarp / numPEs
  
  object State extends ChiselEnum {
    val sIdle, sBeat0, sBeat1, sBeat2, sBeat3 = Value
  }
  import State._
  
  val state = RegInit(sIdle)
  val stall = WireInit(false.B)
  
  val latchedOp = Reg(new OperandBundle())
  
  when (state === sIdle) {
    when (io.in.valid && !stall) {
      latchedOp := io.in.bits
      state := sBeat0
    }
  } .elsewhen (!stall) {
    switch(state) {
      is(sBeat0) { state := sBeat1 }
      is(sBeat1) { state := sBeat2 }
      is(sBeat2) { state := sBeat3 }
      is(sBeat3) { state := sIdle }
    }
  }
  
  // Ready to accept new instruction if idle and not stalling
  io.in.ready := (state === sIdle) && !stall
  
  // EX1
  val ex1_valid = RegInit(false.B)
  val ex1_beat  = RegInit(0.U(2.W))
  val ex1_op    = Reg(new OperandBundle())
  val ex1_src1  = Reg(Vec(numPEs, UInt(config.vGPRWidth.W)))
  val ex1_src2  = Reg(Vec(numPEs, UInt(config.vGPRWidth.W)))
  
  val currentBeat = WireInit(0.U(2.W))
  switch(state) {
    is(sBeat0) { currentBeat := 0.U }
    is(sBeat1) { currentBeat := 1.U }
    is(sBeat2) { currentBeat := 2.U }
    is(sBeat3) { currentBeat := 3.U }
  }
  
  when (!stall) {
    ex1_valid := (state =/= sIdle)
    ex1_beat := currentBeat
    ex1_op := latchedOp
    
    for (i <- 0 until numPEs) {
      ex1_src1(i) := latchedOp.src1Data(currentBeat * numPEs.U + i.U)
      ex1_src2(i) := latchedOp.src2Data(currentBeat * numPEs.U + i.U)
    }
  }
  
  // EX2
  val ex2_valid = RegInit(false.B)
  val ex2_beat  = RegInit(0.U(2.W))
  val ex2_op    = Reg(new OperandBundle())
  val ex2_res   = Reg(Vec(numPEs, UInt(config.vGPRWidth.W)))
  
  when (!stall) {
    ex2_valid := ex1_valid
    ex2_beat := ex1_beat
    ex2_op := ex1_op
    
    for (i <- 0 until numPEs) {
      val s1 = ex1_src1(i)
      val s2 = ex1_src2(i)
      val res = WireInit(0.U(config.vGPRWidth.W))
      switch (ex1_op.opcode) {
        is (vALUOpcode.ADD) { res := s1 + s2 }
        is (vALUOpcode.SUB) { res := s1 - s2 }
        is (vALUOpcode.AND) { res := s1 & s2 }
        is (vALUOpcode.OR)  { res := s1 | s2 }
        is (vALUOpcode.XOR) { res := s1 ^ s2 }
      }
      ex2_res(i) := res
    }
  }
  
  // EX3
  val ex3_valid = RegInit(false.B)
  val ex3_beat  = RegInit(0.U(2.W))
  val ex3_op    = Reg(new OperandBundle())
  val ex3_res   = Reg(Vec(numPEs, UInt(config.vGPRWidth.W)))
  
  when (!stall) {
    ex3_valid := ex2_valid
    ex3_beat := ex2_beat
    ex3_op := ex2_op
    ex3_res := ex2_res
  }
  
  // EX4
  val accumBuffer = Reg(Vec(config.threadPerWarp, UInt(config.vGPRWidth.W)))
  val ex4_out_valid = RegInit(false.B)
  val outPacket = Reg(new ResultPacket())
  
  val isBeat3 = (ex3_beat === 3.U) && ex3_valid
  
  when (!stall) {
    when (ex3_valid) {
      for (i <- 0 until numPEs) {
        accumBuffer(ex3_beat * numPEs.U + i.U) := ex3_res(i)
      }
    }
    
    ex4_out_valid := isBeat3
    when (isBeat3) {
      outPacket.wid := ex3_op.wid
      outPacket.rd := ex3_op.rd
      outPacket.write_mask := ex3_op.activeMask & ex3_op.predMask
      // Reassemble
      for (b <- 0 until 3) {
        for (i <- 0 until numPEs) {
          outPacket.data(b * numPEs + i) := accumBuffer(b * numPEs + i)
        }
      }
      for (i <- 0 until numPEs) {
        outPacket.data(3 * numPEs + i) := ex3_res(i)
      }
    }
  }
  
  stall := ex4_out_valid && !io.out.ready
  
  io.out.valid := ex4_out_valid
  io.out.bits := outPacket
}
