package opengpgpu.collector

import chisel3._
import chisel3.util._
import scheduler.{DispatchBundle, ScoreboardReleaseReq}

class OperandCollectorIO(implicit config: CollectorConfig) extends Bundle {
  // Upstream: Dispatch from Warp Scheduler
  val dispatch = Flipped(Decoupled(new DispatchBundle()))
  
  // Downstream: Execution engines (vALU/MMA)
  // We use Decoupled for backpressure from execution engines
  val issue = Decoupled(new OperandBundle())
  
  // Register File Interface
  val rfReadReq = Vec(config.numBanks, Valid(new BankReadReq()))
  val rfReadResp = Vec(config.numBanks, Flipped(Valid(new BankReadResp())))
  
  // Release signal from execution units to Scoreboard
  val releaseReq = Valid(new ScoreboardReleaseReq())
}

class OperandCollector(implicit config: CollectorConfig) extends Module {
  val io = IO(new OperandCollectorIO())
  
  // Instantiate CUs
  val cus = Seq.fill(config.numCUs)(Module(new CollectorUnit()))
  for (i <- 0 until config.numCUs) {
    cus(i).io.cuId := i.U
  }
  
  // Instantiate Bank Arbiter
  val arbiter = Module(new BankArbiter())
  
  // Connect Arbiter to Register File
  io.rfReadReq := arbiter.io.bankReadReqs
  arbiter.io.bankReadResps := io.rfReadResp
  
  // Connect CUs and Arbiter
  for (i <- 0 until config.numCUs) {
    arbiter.io.reqs(i)(0) := cus(i).io.readReq1
    arbiter.io.reqs(i)(1) := cus(i).io.readReq2
    arbiter.io.reqs(i)(2) := cus(i).io.readReq3
    
    cus(i).io.dataIn1 := arbiter.io.resps(i)(0)
    cus(i).io.dataIn2 := arbiter.io.resps(i)(1)
    cus(i).io.dataIn3 := arbiter.io.resps(i)(2)
  }
  
  // Dispatch Logic (Allocation)
  // Find first free CU
  val freeMask = VecInit(cus.map(_.io.isFree))
  val hasFree = freeMask.reduce(_ || _)
  val freeIdx = PriorityEncoder(freeMask)
  
  io.dispatch.ready := hasFree
  
  for (i <- 0 until config.numCUs) {
    cus(i).io.alloc.valid := io.dispatch.valid && io.dispatch.ready && freeIdx === i.U
    cus(i).io.alloc.bits := io.dispatch.bits
  }
  
  // Issue Logic (Deallocation is managed externally, or by issue handshake)
  // Which CU is ready to issue?
  val readyMask = VecInit(cus.map(_.io.readyToIssue))
  val hasReady = readyMask.reduce(_ || _)
  val readyIdx = PriorityEncoder(readyMask) // Arbitrate issue requests
  
  io.issue.valid := hasReady
  
  // Select the bundle from the winning CU
  val issueBundleVec = VecInit(cus.map(_.io.issueBundle))
  io.issue.bits := issueBundleVec(readyIdx)
  
  // When issue is accepted by downstream, we deallocate the corresponding CU
  val issueAccepted = io.issue.valid && io.issue.ready
  for (i <- 0 until config.numCUs) {
    // If the mock vALU deallocates it later, we don't deallocate here.
    // Wait, the MAS says "Mock vALU ... 向 OC 发送 Deallocate 释放信号".
    // So the vALU needs to send dealloc back?
    // Let's check `Operand_Collector_MAS.md`. It says:
    // "接收 OC 发射的 Operand Bundle，经过预设的 N 个计算周期后，向 OC 发送 Deallocate 释放信号"
    // Wait, if it takes N cycles, and there are many CUs, does Mock vALU send dealloc with CU ID?
    // Actually, normally the CU is deallocated upon Issue. "指令进入执行级后，CU 槽位清空"
    // So it's deallocated upon Issue!
    cus(i).io.dealloc := issueAccepted && readyIdx === i.U
  }
  
  // Mock vALU generates Scoreboard release, but we can pass it from issue?
  // Wait, the Mock vALU module will take `issue` and generate `releaseReq` after N cycles.
  // The `OperandCollectorIO` has `releaseReq` but maybe that comes from Mock vALU if it's integrated?
  // We'll leave `releaseReq` disconnected here, it should be connected in the Top Integration.
  io.releaseReq.valid := false.B
  io.releaseReq.bits := DontCare
}
