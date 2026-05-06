package scheduler

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import decoder.MicroOp

class WarpSchedulerTopSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "WarpScheduler"

  // Helper to create a WarpScheduler test with default connections
  def withScheduler(testFn: WarpScheduler => Unit): Unit = {
    test(new WarpScheduler(numWarps = 4, numRegs = 32, numScoreboardSlots = 3))
      .withAnnotations(Seq(WriteVcdAnnotation)) { dut => testFn(dut) }
  }

  def initWarp(dut: WarpScheduler, warpId: Int, activeMask: Int = 0x1): Unit = {
    dut.io.allocReq.poke(true.B)
    dut.io.allocWarpId.poke(warpId.U)
    dut.io.blkschActiveMask.poke(activeMask.U(32.W))
    dut.io.blkschBarId.poke(0.U)
    dut.clock.step(1)
    dut.io.allocReq.poke(false.B)
  }

  def provideInst(dut: WarpScheduler, warpId: Int, opcode: Int, rd: Int, rs1: Int = 0, rs2: Int = 0): Unit = {
    dut.io.ibHeadMicroOps(warpId).valid.poke(true.B)
    dut.io.ibHeadMicroOps(warpId).opcode.poke(opcode.U)
    dut.io.ibHeadMicroOps(warpId).rd.poke(rd.U)
    dut.io.ibHeadMicroOps(warpId).rs1.poke(rs1.U)
    dut.io.ibHeadMicroOps(warpId).rs2.poke(rs2.U)
  }

  // ──────────────────────────────────────────────
  // Phase 1 原有测试 (已适配新接口)
  // ──────────────────────────────────────────────

  it should "Feature 1 & 4 - Initialize warp, push to valid, and dispatch when ready" in {
    withScheduler { dut =>
      dut.io.allocReq.poke(false.B)
      dut.io.ibEmptyMask.poke("b1111".U)
      dut.io.dispatch.ready.poke(true.B)
      dut.io.releaseReq.valid.poke(false.B)
      dut.io.blkschActiveMask.poke(0.U)
      dut.io.blkschBarId.poke(0.U)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.clock.step(1)

      dut.io.ibEmptyMask.poke("b1101".U)
      provideInst(dut, warpId = 1, opcode = 0x01, rd = 5)

      initWarp(dut, warpId = 1, activeMask = 0x1)

      dut.io.dispatch.valid.expect(true.B)
      dut.io.dispatch.bits.warpId.expect(1.U)
      dut.io.wsIbPopReq.expect(true.B)
      dut.io.wsIbPopId.expect(1.U)

      dut.clock.step(1)
    }
  }

  it should "Feature 2 - 场景 1 & 2 (RAW Detection and Release)" in {
    withScheduler { dut =>
      dut.io.dispatch.ready.poke(true.B)
      dut.io.releaseReq.valid.poke(false.B)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibEmptyMask.poke("b1110".U)
      provideInst(dut, warpId = 0, opcode = 0x01, rd = 5)

      initWarp(dut, warpId = 0, activeMask = 0x1)

      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)

      provideInst(dut, warpId = 0, opcode = 0x01, rd = 6, rs1 = 5)
      dut.clock.step(1)

      dut.io.dispatch.valid.expect(false.B)

      dut.io.releaseReq.valid.poke(true.B)
      dut.io.releaseReq.bits.warpId.poke(0.U)
      dut.io.releaseReq.bits.regId.poke(5.U)
      dut.clock.step(1)

      dut.io.releaseReq.valid.poke(false.B)

      dut.clock.step(1)

      dut.io.dispatch.valid.expect(true.B)
      dut.io.wsIbPopId.expect(0.U)
    }
  }

  it should "Feature 4 - 场景 1 (后端背压)" in {
    withScheduler { dut =>
      dut.io.blkschActiveMask.poke(0.U)
      dut.io.blkschBarId.poke(0.U)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibEmptyMask.poke("b1011".U)
      provideInst(dut, warpId = 2, opcode = 0x01, rd = 10)

      initWarp(dut, warpId = 2, activeMask = 0x1)

      dut.io.dispatch.ready.poke(false.B)
      dut.clock.step(1)

      dut.io.wsIbPopReq.expect(false.B)
      dut.io.dispatch.valid.expect(true.B)

      dut.io.dispatch.ready.poke(true.B)
      dut.io.wsIbPopReq.expect(true.B)

      dut.clock.step(1)
    }
  }

  // ──────────────────────────────────────────────
  // Phase 1.1 新增测试用例
  // ──────────────────────────────────────────────

  it should "Phase 1.1 - 场景 1: Active_Mask 对接" in {
    withScheduler { dut =>
      dut.io.dispatch.ready.poke(true.B)
      dut.io.releaseReq.valid.poke(false.B)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibEmptyMask.poke("b1110".U)
      provideInst(dut, warpId = 0, opcode = 0x01, rd = 5)

      initWarp(dut, warpId = 0, activeMask = 0x0F0F0F0F)

      dut.io.dispatch.valid.expect(true.B)
    }
  }

  it should "Phase 1.1 - 场景 2: Age_Counter 条件递增" in {
    withScheduler { dut =>
      dut.io.dispatch.ready.poke(true.B)
      dut.io.releaseReq.valid.poke(false.B)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibEmptyMask.poke("b1110".U)
      provideInst(dut, warpId = 0, opcode = 0x01, rd = 5)

      initWarp(dut, warpId = 0, activeMask = 0x0)

      dut.io.dispatch.valid.expect(true.B)
    }
  }

  // Debug: Check if second dispatch works with NO hazard (like Wait_KCache scenario)
  it should "DEBUG: Second dispatch after first (no hazard)" in {
    withScheduler { dut =>
      dut.io.dispatch.ready.poke(true.B)
      dut.io.releaseReq.valid.poke(false.B)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibEmptyMask.poke("b1110".U)
      provideInst(dut, warpId = 0, opcode = 0x01, rd = 5)

      initWarp(dut, warpId = 0, activeMask = 0x1)

      println("=== After initWarp ===")
      println(s"  dispatch.valid = ${dut.io.dispatch.valid.peek().litValue}")

      // First dispatch
      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)

      println("=== After 1st dispatch ===")
      println(s"  dispatch.valid = ${dut.io.dispatch.valid.peek().litValue}")
      println(s"  ibEmptyMask(0) = ${dut.io.ibEmptyMask.peek().litValue}")

      // Provide new instruction (NO hazard)
      provideInst(dut, warpId = 0, opcode = 0x01, rd = 6)
      
      println("=== After provideInst (before clock) ===")
      println(s"  dispatch.valid = ${dut.io.dispatch.valid.peek().litValue}")
      println(s"  ibHeadMicroOps(0).rd = ${dut.io.ibHeadMicroOps(0).rd.peek().litValue}")
      
      // This should be true (no hazard) BEFORE clock step consumes it
      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)

      println("=== After clock step ===")
      println(s"  dispatch.valid = ${dut.io.dispatch.valid.peek().litValue}")
      println(s"  ibEmptyMask(0) = ${dut.io.ibEmptyMask.peek().litValue}")
    }
  }

  it should "Phase 1.1 - 场景 3: Wait_KCache 阻塞与恢复" in {
    withScheduler { dut =>
      dut.io.dispatch.ready.poke(true.B)
      dut.io.releaseReq.valid.poke(false.B)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibEmptyMask.poke("b1110".U)
      provideInst(dut, warpId = 0, opcode = 0x01, rd = 5)

      initWarp(dut, warpId = 0, activeMask = 0x1)

      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)

      provideInst(dut, warpId = 0, opcode = 0x01, rd = 6)
      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)

      // Provide next instruction IMMEDIATELY to prevent phantom hazard from rd=6
      provideInst(dut, warpId = 0, opcode = 0x01, rd = 7)

      dut.io.kcacheMissWaitMask.poke("b0001".U)
      dut.clock.step(1)
      dut.io.kcacheMissWaitMask.poke(0.U)

      // Since it is stalled, it should not dispatch
      dut.io.dispatch.valid.expect(false.B)
      dut.clock.step(1)

      // Prevent phantom hazard from rd=7 when it wakes up
      provideInst(dut, warpId = 0, opcode = 0x01, rd = 8)
      
      dut.io.kcacheFillAckMask.poke("b0001".U)
      // release a slot so it doesn't stay full
      dut.io.releaseReq.valid.poke(true.B)
      dut.io.releaseReq.bits.warpId.poke(0.U)
      dut.io.releaseReq.bits.regId.poke(5.U)
      dut.clock.step(1)
      dut.io.kcacheFillAckMask.poke(0.U)
      dut.io.releaseReq.valid.poke(false.B)

      // Wait for wakeup
      var cycles = 0
      while(!dut.io.dispatch.valid.peek().litToBoolean && cycles < 5) {
        dut.clock.step(1)
        cycles += 1
      }
      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)
    }
  }

  it should "Phase 1.1 - 场景 4: Slot Full 反压" in {
    test(new WarpScheduler(numWarps = 4, numRegs = 32, numScoreboardSlots = 3))
      .withAnnotations(Seq(WriteVcdAnnotation)) { dut =>

      dut.io.dispatch.ready.poke(true.B)
      dut.io.releaseReq.valid.poke(false.B)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibEmptyMask.poke("b1110".U)

      initWarp(dut, warpId = 0, activeMask = 0x1)

      for (regId <- Seq(10, 11, 12)) {
        provideInst(dut, warpId = 0, opcode = 0x01, rd = regId)
        dut.io.dispatch.valid.expect(true.B)
        dut.clock.step(1)
      }

      provideInst(dut, warpId = 0, opcode = 0x01, rd = 13)
      dut.io.dispatch.valid.expect(false.B)
      dut.clock.step(1)

      dut.io.releaseReq.valid.poke(true.B)
      dut.io.releaseReq.bits.warpId.poke(0.U)
      dut.io.releaseReq.bits.regId.poke(10.U)
      dut.clock.step(1)
      dut.io.releaseReq.valid.poke(false.B)

      // Slot is free, so it can dispatch NOW
      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)
    }
  }

  it should "Phase 1.1 - 场景 5: EXIT 生命周期" in {
    withScheduler { dut =>
      dut.io.dispatch.ready.poke(true.B)
      dut.io.releaseReq.valid.poke(false.B)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibEmptyMask.poke("b1110".U)
      provideInst(dut, warpId = 0, opcode = 0xFF, rd = 0)

      initWarp(dut, warpId = 0, activeMask = 0x1)

      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)

      dut.io.wsWarpExitValid.expect(true.B)
      dut.io.wsWarpExitId.expect(0.U)

      dut.clock.step(1)
      dut.io.dispatch.valid.expect(false.B)
    }
  }

  it should "Phase 1.1 - 场景 6: 零寄存器忽略" in {
    withScheduler { dut =>
      dut.io.dispatch.ready.poke(true.B)
      dut.io.releaseReq.valid.poke(false.B)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibEmptyMask.poke("b1110".U)
      provideInst(dut, warpId = 0, opcode = 0x01, rd = 5)

      initWarp(dut, warpId = 0, activeMask = 0x1)

      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)

      provideInst(dut, warpId = 0, opcode = 0x01, rd = 6, rs1 = 0, rs2 = 0)
      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)
    }
  }

  it should "Phase 1.1 - 场景 7: Pending 复用与释放" in {
    withScheduler { dut =>
      dut.io.dispatch.ready.poke(true.B)
      dut.io.releaseReq.valid.poke(false.B)
      dut.io.kcacheMissWaitMask.poke(0.U)
      dut.io.kcacheFillAckMask.poke(0.U)

      dut.io.ibEmptyMask.poke("b1110".U)
      provideInst(dut, warpId = 0, opcode = 0x01, rd = 5)

      initWarp(dut, warpId = 0, activeMask = 0x1)

      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)

      provideInst(dut, warpId = 0, opcode = 0x01, rd = 5)
      // WAW hazard, so it stalls
      dut.io.dispatch.valid.expect(false.B)
      dut.clock.step(1)

      // Release the first write
      dut.io.releaseReq.valid.poke(true.B)
      dut.io.releaseReq.bits.warpId.poke(0.U)
      dut.io.releaseReq.bits.regId.poke(5.U)
      dut.clock.step(1)
      dut.io.releaseReq.valid.poke(false.B)

      // Wait for wakeup without extra stepping after it's valid
      var cycles = 0
      while(!dut.io.dispatch.valid.peek().litToBoolean && cycles < 5) {
        dut.clock.step(1)
        cycles += 1
      }
      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)

      // Now provide a RAW hazard
      provideInst(dut, warpId = 0, opcode = 0x01, rd = 6, rs1 = 5)
      dut.io.dispatch.valid.expect(false.B)
      dut.clock.step(1)

      // Release the second write
      dut.io.releaseReq.valid.poke(true.B)
      dut.io.releaseReq.bits.warpId.poke(0.U)
      dut.io.releaseReq.bits.regId.poke(5.U)
      dut.clock.step(1)
      dut.io.releaseReq.valid.poke(false.B)

      cycles = 0
      while(!dut.io.dispatch.valid.peek().litToBoolean && cycles < 5) {
        // change rd to prevent phantom WAW hazard after dispatch
        provideInst(dut, warpId = 0, opcode = 0x01, rd = 10)
        dut.clock.step(1)
        cycles += 1
      }
      dut.io.dispatch.valid.expect(true.B)
      dut.clock.step(1)
    }
  }
}
