package opengpgpu.collector

import chisel3._
import chisel3.util._
import scheduler.DispatchBundle

class CollectorUnitIO(implicit config: CollectorConfig) extends Bundle {
  val cuId = Input(UInt(log2Ceil(config.numCUs).W))
  
  // Allocate
  val alloc = Flipped(Valid(new DispatchBundle()))
  
  // Arbiter interface for reading banks
  // We have up to 3 source operands. Each needs to be requested.
  val readReq1 = Valid(new BankReadReq())
  val readReq2 = Valid(new BankReadReq())
  val readReq3 = Valid(new BankReadReq())
  
  // Arbiter response. We need to know which operand is being delivered.
  val dataIn1 = Flipped(Valid(new CUDeliverData()))
  val dataIn2 = Flipped(Valid(new CUDeliverData()))
  val dataIn3 = Flipped(Valid(new CUDeliverData()))
  
  // Issue
  val readyToIssue = Output(Bool())
  val issueBundle = Output(new OperandBundle())
  
  // Deallocate
  val dealloc = Input(Bool())
  
  // Status
  val isFree = Output(Bool())
}

class CollectorUnit(implicit config: CollectorConfig) extends Module {
  val io = IO(new CollectorUnitIO())
  
  val stateFree :: stateCollect :: stateReady :: Nil = Enum(3)
  val state = RegInit(stateFree)
  
  // Metadata
  val wid = RegInit(0.U(5.W))
  val opcode = RegInit(0.U(8.W))
  val rd = RegInit(0.U(8.W))
  
  val rs1 = RegInit(0.U(8.W))
  val rs2 = RegInit(0.U(8.W))
  val rs3 = RegInit(0.U(16.W))
  
  val hasSrc1 = RegInit(false.B)
  val hasSrc2 = RegInit(false.B)
  val hasSrc3 = RegInit(false.B)
  
  val valid1 = RegInit(false.B)
  val valid2 = RegInit(false.B)
  val valid3 = RegInit(false.B)
  
  // Data buffers
  val src1Data = Reg(Vec(config.threadPerWarp, UInt(config.vGPRWidth.W)))
  val src2Data = Reg(Vec(config.threadPerWarp, UInt(config.vGPRWidth.W)))
  val src3Data = Reg(Vec(config.threadPerWarp, UInt(config.vGPRWidth.W)))
  
  io.isFree := (state === stateFree)
  
  // Allocate logic
  when(state === stateFree && io.alloc.valid) {
    state := stateCollect
    val op = io.alloc.bits.microOp
    wid := io.alloc.bits.warpId
    opcode := op.opcode
    rd := op.rd
    
    rs1 := op.rs1
    rs2 := op.rs2
    rs3 := op.rs3
    
    // Simplification: if rsX != 0, it needs a read. (Assuming R0 is zero or just invalid)
    hasSrc1 := (op.rs1 =/= 0.U)
    hasSrc2 := (op.rs2 =/= 0.U)
    hasSrc3 := (op.rs3 =/= 0.U)
    
    valid1 := (op.rs1 === 0.U)
    valid2 := (op.rs2 === 0.U)
    valid3 := (op.rs3 === 0.U)
  }
  
  // Request logic: continuously send read requests until valid is true
  io.readReq1.valid := (state === stateCollect) && !valid1
  io.readReq1.bits.wid := wid
  io.readReq1.bits.regId := rs1
  
  io.readReq2.valid := (state === stateCollect) && !valid2
  io.readReq2.bits.wid := wid
  io.readReq2.bits.regId := rs2
  
  io.readReq3.valid := (state === stateCollect) && !valid3
  io.readReq3.bits.wid := wid
  io.readReq3.bits.regId := rs3(7,0) // Assuming rs3 uses lower 8 bits for vGPR
  
  // Receive data
  when(state === stateCollect) {
    when(io.dataIn1.valid) {
      valid1 := true.B
      src1Data := io.dataIn1.bits.data
    }
    when(io.dataIn2.valid) {
      valid2 := true.B
      src2Data := io.dataIn2.bits.data
    }
    when(io.dataIn3.valid) {
      valid3 := true.B
      src3Data := io.dataIn3.bits.data
    }
    
    when(valid1 && valid2 && valid3) {
      state := stateReady
    }
  }
  
  // Issue logic
  io.readyToIssue := (state === stateReady) || (state === stateCollect && valid1 && valid2 && valid3)
  
  io.issueBundle.wid := wid
  io.issueBundle.opcode := opcode
  io.issueBundle.rd := rd
  io.issueBundle.src1Data := src1Data
  io.issueBundle.src2Data := src2Data
  io.issueBundle.src3Data := src3Data
  io.issueBundle.hasSrc1 := hasSrc1
  io.issueBundle.hasSrc2 := hasSrc2
  io.issueBundle.hasSrc3 := hasSrc3
  io.issueBundle.cuId := io.cuId
  
  // Deallocate logic
  when(io.dealloc) {
    state := stateFree
  }
}
