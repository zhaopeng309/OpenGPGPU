package opengpgpu.register

import chisel3._
import chisel3.util._

class vGPR_Bank(config: RegisterFileConfig = RegisterFileConfig()) extends Module {
  val depth = config.vGPRDepthPerBank
  val io = IO(new Bundle {
    val readAddr = Input(UInt(log2Ceil(depth).W))
    val readEn   = Input(Bool())
    val readData = Output(Vec(config.threadPerWarp, UInt(config.vGPRWidth.W)))
    
    val writeAddr = Input(UInt(log2Ceil(depth).W))
    val writeEn   = Input(Bool())
    val writeData = Input(Vec(config.threadPerWarp, UInt(config.vGPRWidth.W)))
    val writeMask = Input(Vec(config.threadPerWarp, Bool()))
  })

  // 1R1W SyncReadMem with write mask support
  val bankMem = SyncReadMem(depth, Vec(config.threadPerWarp, UInt(config.vGPRWidth.W)))

  io.readData := DontCare
  when(io.readEn) {
    io.readData := bankMem.read(io.readAddr)
  }

  when(io.writeEn) {
    bankMem.write(io.writeAddr, io.writeData, io.writeMask)
  }
}

class vGPR_Top(config: RegisterFileConfig = RegisterFileConfig()) extends Module {
  val io = IO(new Bundle {
    // We provide 4 parallel read ports for 4 banks so OC can potentially read from 4 different banks
    // Here we provide a simplified interface where OC/RCB provides explicit bank routed requests,
    // or we just take N requests and route them.
    // "仲裁分发（本阶段实现 1R1W 的路由直连即可）"
    // To implement "模 4 路由直连", we'll provide 1 read request port and 1 write request port per bank?
    // Wait, the MAS says: "OC 每周期最多发起多个读请求... 实例化 4 个 vGPR_Bank，对来自 OC (Read) 和 RCB (Write) 的请求进行地址拆解和路由分发"
    // Let's provide a single aggregated read/write port for each bank externally, OR 
    // a single Read Req, and Top routes it to the corresponding bank.
    // If we only provide 1 read request externally, it doesn't demonstrate parallel 4 bank.
    // Let's provide 4 read request channels and 4 write request channels from OC/RCB,
    // and route them. Actually, simpler: provide array of 4 read reqs.
    
    // Read ports (Up to 4 concurrent read requests, from different instructions/operands)
    val readReqs = Vec(4, Flipped(Valid(new Bundle {
      val wid   = UInt(config.warpIdWidth.W)
      val regId = UInt(config.regIdWidth.W)
    })))
    val readData = Output(Vec(4, Vec(config.threadPerWarp, UInt(config.vGPRWidth.W))))
    
    // Write ports (Up to 4 concurrent write requests)
    val writeReqs = Vec(4, Flipped(Valid(new Bundle {
      val wid   = UInt(config.warpIdWidth.W)
      val regId = UInt(config.regIdWidth.W)
      val data  = Vec(config.threadPerWarp, UInt(config.vGPRWidth.W))
      val mask  = Vec(config.threadPerWarp, Bool())
    })))
  })

  // Instantiate 4 Banks
  val banksSeq = Seq.fill(config.vGPRBanks)(Module(new vGPR_Bank(config)))

  // Default bank inputs
  banksSeq.foreach { bank =>
    bank.io.readEn := false.B
    bank.io.readAddr := 0.U
    bank.io.writeEn := false.B
    bank.io.writeAddr := 0.U
    bank.io.writeData := DontCare
    bank.io.writeMask := DontCare
  }
  
  io.readData := DontCare

  // Route Read Requests
  for (i <- 0 until 4) {
    val req = io.readReqs(i)
    val bankIdx = req.bits.regId(config.bankIdWidth - 1, 0)
    val internalAddr = Cat(req.bits.wid, req.bits.regId(config.regIdWidth - 1, config.bankIdWidth))
    
    when(req.valid) {
      for (b <- 0 until config.vGPRBanks) {
        when(bankIdx === b.U) {
          banksSeq(b).io.readEn := true.B
          banksSeq(b).io.readAddr := internalAddr
        }
      }
    }
    
    val bankIdxDelay = RegNext(bankIdx)
    val readDataVec = VecInit(banksSeq.map(_.io.readData))
    io.readData(i) := readDataVec(bankIdxDelay)
  }

  // Route Write Requests
  for (i <- 0 until 4) {
    val req = io.writeReqs(i)
    val bankIdx = req.bits.regId(config.bankIdWidth - 1, 0)
    val internalAddr = Cat(req.bits.wid, req.bits.regId(config.regIdWidth - 1, config.bankIdWidth))
    
    when(req.valid) {
      for (b <- 0 until config.vGPRBanks) {
        when(bankIdx === b.U) {
          banksSeq(b).io.writeEn := true.B
          banksSeq(b).io.writeAddr := internalAddr
          banksSeq(b).io.writeData := req.bits.data
          banksSeq(b).io.writeMask := req.bits.mask
        }
      }
    }
  }
}
