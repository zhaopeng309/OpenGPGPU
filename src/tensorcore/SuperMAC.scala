package tensorcore

import chisel3._
import chisel3.util._

// TCDecoder Definitions
object TCPrecision {
  val FP32 = 0.U(2.W)
  val BF16 = 1.U(2.W)
  val FP8  = 2.U(2.W)
  val INT8 = 3.U(2.W)
}

class SuperMAC extends Module {
  val io = IO(new Bundle {
    val a_operand = Input(UInt(32.W)) // From horizontal broadcast
    val b_operand = Input(UInt(32.W)) // From vertical broadcast
    val acc_old   = Input(UInt(32.W)) // From aGPR
    val precision = Input(UInt(2.W))  // Control the dynamic splitting
    
    val acc_new   = Output(UInt(32.W)) // To aGPR
  })

  // 1. Dynamic Multiplier Tree (Mockup for infinite precision sum)
  // For MVP we just do simple fixed point mockup logic or dummy fp32 add
  // Since chisel stdlib lacks FP adders, we simulate behavior with UInt/SInt for now
  // Assuming a simplified model where we just sum them as integer for structural pass
  // Note: A real FP implementation would use hardfloat or similar library.
  
  val prod0 = Wire(UInt(32.W))
  val prod1 = Wire(UInt(32.W))
  val prod2 = Wire(UInt(32.W))
  val prod3 = Wire(UInt(32.W))
  
  when (io.precision === TCPrecision.FP32) {
    prod0 := io.a_operand * io.b_operand
    prod1 := 0.U
    prod2 := 0.U
    prod3 := 0.U
  } .elsewhen (io.precision === TCPrecision.BF16) {
    prod0 := io.a_operand(15, 0) * io.b_operand(15, 0)
    prod1 := io.a_operand(31, 16) * io.b_operand(31, 16)
    prod2 := 0.U
    prod3 := 0.U
  } .otherwise { // FP8 / INT8
    prod0 := io.a_operand(7, 0) * io.b_operand(7, 0)
    prod1 := io.a_operand(15, 8) * io.b_operand(15, 8)
    prod2 := io.a_operand(23, 16) * io.b_operand(23, 16)
    prod3 := io.a_operand(31, 24) * io.b_operand(31, 24)
  }

  // 2. Fused Adder Tree
  // In reality this is an infinite precision adder with RNE
  val sum = io.acc_old + prod0 + prod1 + prod2 + prod3
  
  io.acc_new := sum
}

class aGPR extends Module {
  val io = IO(new Bundle {
    val write_data  = Input(UInt(32.W))
    val write_en    = Input(Bool())
    val depth_sel   = Input(UInt(2.W)) // 4 Warp Depths
    val read_data   = Output(UInt(32.W))
    val clear       = Input(Bool())
  })

  // Depth 4 distributed stationary accumulation registers
  val regs = RegInit(VecInit(Seq.fill(4)(0.U(32.W))))

  when (io.clear) {
    for (i <- 0 until 4) {
      regs(i) := 0.U
    }
  } .elsewhen (io.write_en) {
    regs(io.depth_sel) := io.write_data
  }

  io.read_data := regs(io.depth_sel)
}

class MACTile extends Module {
  val io = IO(new Bundle {
    val a_val      = Input(UInt(32.W))
    val b_val      = Input(UInt(32.W))
    val clear_acc  = Input(Bool())
    val mac_en     = Input(Bool())
    val depth_sel  = Input(UInt(2.W))
    val precision  = Input(UInt(2.W))
    
    val acc_out    = Output(UInt(32.W))
  })

  val u_mac = Module(new SuperMAC())
  val u_agpr = Module(new aGPR())

  u_mac.io.a_operand := io.a_val
  u_mac.io.b_operand := io.b_val
  u_mac.io.precision := io.precision
  u_mac.io.acc_old   := u_agpr.io.read_data

  u_agpr.io.write_data := u_mac.io.acc_new
  u_agpr.io.write_en   := io.mac_en
  u_agpr.io.depth_sel  := io.depth_sel
  u_agpr.io.clear      := io.clear_acc

  io.acc_out := u_agpr.io.read_data
}
