package opengpgpu.collector

import chisel3._
import chisel3.util._

class BankArbiterIO(implicit config: CollectorConfig) extends Bundle {
  // Requests from all CUs
  // Size: numCUs * 3 (up to 3 operands per CU)
  val reqs = Vec(config.numCUs, Vec(3, Flipped(Valid(new BankReadReq()))))
  
  // Output to 4 Banks
  val bankReadReqs = Vec(config.numBanks, Valid(new BankReadReq()))
  // Input from 4 Banks
  val bankReadResps = Vec(config.numBanks, Flipped(Valid(new BankReadResp())))
  
  // Responses routed back to CUs
  val resps = Vec(config.numCUs, Vec(3, Valid(new CUDeliverData())))
}

class BankArbiter(implicit config: CollectorConfig) extends Module {
  val io = IO(new BankArbiterIO())
  
  // Initialize defaults
  for (b <- 0 until config.numBanks) {
    io.bankReadReqs(b).valid := false.B
    io.bankReadReqs(b).bits := DontCare
  }
  
  for (c <- 0 until config.numCUs) {
    for (op <- 0 until 3) {
      io.resps(c)(op).valid := false.B
      io.resps(c)(op).bits.data := DontCare
    }
  }
  
  // Round-robin or fixed priority arbiter for each bank.
  // Using fixed priority for simplicity (CU 0 > CU 1 ... > CU N)
  // Or age-based if lower CU ID means older instruction. We'll use CU ID as priority.
  // First, map requests to their target banks.
  
  // For pipelining, Arbiter takes 1 cycle to request, and next cycle response arrives.
  // We need to record which CU and operand won the arbitration for each bank
  // so we can route the response back in the next cycle.
  
  val winnerCuId = Reg(Vec(config.numBanks, UInt(log2Ceil(config.numCUs).W)))
  val winnerOpIdx = Reg(Vec(config.numBanks, UInt(2.W)))
  val winnerValid = RegInit(VecInit(Seq.fill(config.numBanks)(false.B)))
  
  // Arbitration
  for (b <- 0 until config.numBanks) {
    val bankReqFound = WireDefault(false.B)
    val selCuId = WireDefault(0.U(log2Ceil(config.numCUs).W))
    val selOpIdx = WireDefault(0.U(2.W))
    val selReqBits = WireDefault(0.U.asTypeOf(new BankReadReq()))
    
    // We iterate backwards to give higher priority to smaller CU index (older)
    for (c <- config.numCUs - 1 to 0 by -1) {
      for (op <- 2 to 0 by -1) {
        val req = io.reqs(c)(op)
        val bankId = req.bits.regId(log2Ceil(config.numBanks)-1, 0)
        when(req.valid && bankId === b.U) {
          bankReqFound := true.B
          selCuId := c.U
          selOpIdx := op.U
          selReqBits := req.bits
        }
      }
    }
    
    winnerValid(b) := bankReqFound
    winnerCuId(b) := selCuId
    winnerOpIdx(b) := selOpIdx
    
    when(bankReqFound) {
      io.bankReadReqs(b).valid := true.B
      io.bankReadReqs(b).bits := selReqBits
    }
  }
  
  // Routing responses
  for (b <- 0 until config.numBanks) {
    when(winnerValid(b) && io.bankReadResps(b).valid) {
      val cu = winnerCuId(b)
      val op = winnerOpIdx(b)
      // Since we can't index dynamically into LHS Vec with variable easily without switch,
      // we can generate demux logic.
      for (c <- 0 until config.numCUs) {
        for (o <- 0 until 3) {
          when(cu === c.U && op === o.U) {
            io.resps(c)(o).valid := true.B
            io.resps(c)(o).bits.data := io.bankReadResps(b).bits.data
          }
        }
      }
    }
  }
}
