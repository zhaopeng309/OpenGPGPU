package tensorcore

import chisel3._
import chisel3.util._

class MACArray extends Module {
  val io = IO(new Bundle {
    val a_broadcast = Input(Vec(16, UInt(32.W))) // 16 horizontal rows
    val b_broadcast = Input(Vec(16, UInt(32.W))) // 16 vertical cols
    val clear_acc   = Input(Bool())
    val mac_en      = Input(Bool())
    val depth_sel   = Input(UInt(2.W))
    val precision   = Input(UInt(2.W))
    
    val epilogue_row_sel = Input(UInt(4.W))
    val mac_row_out      = Output(Vec(16, UInt(32.W))) // output of selected Y row
  })

  // Instantiate 16x16 MACTile array
  val tiles = Seq.fill(16)(Seq.fill(16)(Module(new MACTile())))

  for (row <- 0 until 16) {
    for (col <- 0 until 16) {
      tiles(row)(col).io.a_val     := io.a_broadcast(row)
      tiles(row)(col).io.b_val     := io.b_broadcast(col)
      tiles(row)(col).io.clear_acc := io.clear_acc
      tiles(row)(col).io.mac_en    := io.mac_en
      tiles(row)(col).io.depth_sel := io.depth_sel
      tiles(row)(col).io.precision := io.precision
    }
  }

  // Row selection for Epilogue / A2S readout
  val row_out = Wire(Vec(16, UInt(32.W)))
  for (col <- 0 until 16) {
    row_out(col) := MuxLookup(io.epilogue_row_sel, 0.U)(
      (0 until 16).map(r => r.U -> tiles(r)(col).io.acc_out)
    )
  }

  io.mac_row_out := row_out
}
