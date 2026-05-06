package scheduler

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class SchedulerLogicWrapper(val numWarps: Int) extends Module {
  val io = IO(new Bundle {
    val warps = Input(Vec(numWarps, new Bundle {
      val valid = Bool()
      val ready = Bool()
      val ageCounter = UInt(8.W)
      val instType = UInt(3.W)
    }))
    val lastWarpId = Input(UInt(log2Ceil(numWarps).W))
    val anyReady = Output(Bool())
    val winnerId = Output(UInt(log2Ceil(numWarps).W))
  })

  val readyWarps = io.warps.map { w =>
    WarpContext(
      valid = w.valid,
      ready = w.ready,
      stalled = false.B,
      waitKCache = false.B,
      ageCounter = w.ageCounter,
      instType = w.instType,
      destReg = 0.U,
      vgprBase = 0.U,
      activeMask = 0.U,
      barId = 0.U
    )
  }

  val (any, winner) = SchedulerLogic.scheduleGTO(readyWarps, io.lastWarpId)
  io.anyReady := any
  io.winnerId := winner
}

class SchedulerLogicSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SchedulerLogic"

  it should "Feature 3 - 场景 1 (类型优先级): Select TMA over ALU even if ALU is older" in {
    test(new SchedulerLogicWrapper(4)) { dut =>
      // Warp 0: ALU, Age 100
      dut.io.warps(0).valid.poke(true.B)
      dut.io.warps(0).ready.poke(true.B)
      dut.io.warps(0).instType.poke(InstType.ALU)
      dut.io.warps(0).ageCounter.poke(100.U)

      // Warp 1: TMA, Age 10
      dut.io.warps(1).valid.poke(true.B)
      dut.io.warps(1).ready.poke(true.B)
      dut.io.warps(1).instType.poke(InstType.TMA)
      dut.io.warps(1).ageCounter.poke(10.U)
      
      dut.io.lastWarpId.poke(3.U) // some inactive warp

      dut.io.anyReady.expect(true.B)
      dut.io.winnerId.expect(1.U) // TMA wins
    }
  }

  it should "Feature 3 - 场景 2 (Greedy 连发): Select same warp if still ready and same priority" in {
    test(new SchedulerLogicWrapper(4)) { dut =>
      // Warp 0: ALU, Age 5
      dut.io.warps(0).valid.poke(true.B)
      dut.io.warps(0).ready.poke(true.B)
      dut.io.warps(0).instType.poke(InstType.ALU)
      dut.io.warps(0).ageCounter.poke(5.U)

      // Warp 1: ALU, Age 20 (older)
      dut.io.warps(1).valid.poke(true.B)
      dut.io.warps(1).ready.poke(true.B)
      dut.io.warps(1).instType.poke(InstType.ALU)
      dut.io.warps(1).ageCounter.poke(20.U)
      
      dut.io.lastWarpId.poke(0.U) // last scheduled was Warp 0

      // Even though Warp 1 is older, Greedy picks Warp 0
      dut.io.anyReady.expect(true.B)
      dut.io.winnerId.expect(0.U)
    }
  }

  it should "Feature 3 - 场景 3 (Oldest 切换): Switch to oldest when greedy warp is stalled" in {
    test(new SchedulerLogicWrapper(4)) { dut =>
      // Warp 0: ALU, NOT ready (stalled)
      dut.io.warps(0).valid.poke(true.B)
      dut.io.warps(0).ready.poke(false.B)
      dut.io.warps(0).instType.poke(InstType.ALU)
      dut.io.warps(0).ageCounter.poke(5.U)

      // Warp 1: ALU, Age 10
      dut.io.warps(1).valid.poke(true.B)
      dut.io.warps(1).ready.poke(true.B)
      dut.io.warps(1).instType.poke(InstType.ALU)
      dut.io.warps(1).ageCounter.poke(10.U)
      
      // Warp 2: ALU, Age 20
      dut.io.warps(2).valid.poke(true.B)
      dut.io.warps(2).ready.poke(true.B)
      dut.io.warps(2).instType.poke(InstType.ALU)
      dut.io.warps(2).ageCounter.poke(20.U)
      
      dut.io.lastWarpId.poke(0.U) // last scheduled was Warp 0, but it's stalled

      // Should pick Oldest among ready (Warp 2)
      dut.io.anyReady.expect(true.B)
      dut.io.winnerId.expect(2.U)
    }
  }
}
