package memory

import chisel3._
import chisel3.util._
import Types._

case class MemoryControllerParams(
  dataWidth: Int = 128,
  addrWidth: Int = 64
)

class MemoryController(params: MemoryControllerParams) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new MemoryRequest))
    val resp = Decoupled(new MemoryResponse)
    val config = Input(new MemoryConfig)
  })
  
  // Internal queue to hold requests for processing
  val reqQueue = Module(new Queue(new MemoryRequest, 16))
  io.req <> reqQueue.io.enq
  
  // To handle burst and delay, we implement a simple state machine
  val sIdle :: sProcess :: sRespond :: Nil = Enum(3)
  val state = RegInit(sIdle)
  
  val currentReq = Reg(new MemoryRequest)
  val burstCounter = RegInit(0.U(4.W))
  val delayCounter = RegInit(0.U(8.W))
  
  // Backdoor interface for simulation (TestHarness will read this and provide data)
  // In a real DPI setup, this would be a BlackBox
  val simIO = IO(new Bundle {
    val reqValid = Output(Bool())
    val reqAddr = Output(UInt(64.W))
    val reqData = Output(UInt(128.W))
    val reqSize = Output(UInt(4.W))
    val reqIsWrite = Output(Bool())
    val reqMask = Output(UInt(16.W))
    
    val respValid = Input(Bool())
    val respData = Input(UInt(128.W))
  })
  
  simIO.reqValid := false.B
  simIO.reqAddr := currentReq.addr
  simIO.reqData := currentReq.data
  simIO.reqSize := currentReq.size
  simIO.reqIsWrite := currentReq.isWrite
  simIO.reqMask := currentReq.mask
  
  reqQueue.io.deq.ready := false.B
  io.resp.valid := false.B
  io.resp.bits.data := simIO.respData
  io.resp.bits.error := false.B
  
  switch(state) {
    is(sIdle) {
      when(reqQueue.io.deq.valid) {
        reqQueue.io.deq.ready := true.B
        currentReq := reqQueue.io.deq.bits
        burstCounter := reqQueue.io.deq.bits.size
        delayCounter := io.config.latency
        state := sProcess
      }
    }
    is(sProcess) {
      when(delayCounter > 0.U) {
        delayCounter := delayCounter - 1.U
      }.otherwise {
        simIO.reqValid := true.B
        when(simIO.respValid || currentReq.isWrite) {
          state := sRespond
        }
      }
    }
    is(sRespond) {
      io.resp.valid := true.B
      // Keep data from simIO, assume testbench holds it
      io.resp.bits.data := simIO.respData
      io.resp.bits.error := false.B
      
      when(io.resp.ready) {
        when(burstCounter > 0.U) {
          burstCounter := burstCounter - 1.U
          // Advance address by 16 bytes for next burst
          currentReq.addr := currentReq.addr + 16.U
          delayCounter := 0.U // Subsequent bursts could be faster, or 0 delay
          state := sProcess
        }.otherwise {
          state := sIdle
        }
      }
    }
  }
}
