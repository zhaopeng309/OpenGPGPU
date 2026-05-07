package sim.smsp.TestBench

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import utils.Logger
import isa.Registry
import isa.LDC_64
import sim.smsp.{SMSPConfig, SMSPTop}

import isa.SASS_Instruction

class ADD_Inst extends SASS_Instruction("ADD") {
  op(59, 52, "00000000") // Opcode 0x00 for vALU ADD (matches Types.scala vALUOpcode)
}

class SMSPTestbench extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SMSP System Simulation"

  it should "run cycle-accurate simulation and print logs" in {
    // 仅当开启了日志配置时才执行，或者无论如何执行但配置决定是否打印
    SMSPConfig.initLogger()

    // 注册指令以便 Decoder 正常工作
    Registry.clear()
    Registry.register(new LDC_64)
    Registry.register(new ADD_Inst)

    test(new SMSPTop).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Set a longer timeout
      dut.clock.setTimeout(200)

      // 初始化输入
      dut.io.warp_init_valid.poke(false.B)
      dut.io.warp_init_id.poke(0.U)
      dut.io.warp_init_pc.poke(0.U)
      dut.io.roc_icache_fill_valid.poke(false.B)
      dut.io.roc_icache_fill_addr.poke(0.U)
      dut.io.roc_icache_fill_data.poke(0.U)
      dut.io.roc_kcache_fill_valid.poke(false.B)
      dut.io.roc_kcache_fill_addr.poke(0.U)
      dut.io.roc_kcache_fill_data.poke(0.U)

      // 启动一个 Warp
      dut.io.warp_init_valid.poke(true.B)
      dut.io.warp_init_id.poke(0.U)
      dut.io.warp_init_pc.poke(0x1000.U)
      logCycle(dut, 0)
      dut.clock.step(1)
      dut.io.warp_init_valid.poke(false.B)

      // 构造一条指令数据
      // Let's use vALU ADD instead of LDC, as vALU is integrated.
      // Opcode for ADD is 0. 
      // FADD is 0x1E, IMAD is 0x24. Wait, Decoder uses Registry to match.
      // Let's register a basic ADD instruction in the testbench.
      val add_opcode = BigInt("00", 16) // vALU ADD
      val inst1_val = (add_opcode << 52) | (BigInt(3) << 32) | (BigInt(1) << 24) | (BigInt(2) << 16) // Rd=3, Rs1=1, Rs2=2
      val fillData = inst1_val // 放在 0x1000 对应的 word0

      // 运行 100 个时钟周期
      for (cycle <- 1 to 100) {
        // 在第三个周期提供 Cache Fill 响应
        if (cycle == 3) {
          dut.io.roc_icache_fill_valid.poke(true.B)
          dut.io.roc_icache_fill_addr.poke(0x1000.U)
          dut.io.roc_icache_fill_data.poke(fillData.U)
        } else {
          dut.io.roc_icache_fill_valid.poke(false.B)
        }

    // 记录状态并打印
    val wsDisp = dut.io.ws_dispatch_valid.peek().litToBoolean
    if (wsDisp) {
      Logger.info("TEST", s"Instruction dispatched to OC")
    }
    
    val sbRelease = dut.io.sb_release_req.peek().litToBoolean
    if (sbRelease) {
      Logger.info("TEST", s"Scoreboard release requested (Execution Complete)")
    }
    
    logCycle(dut, cycle)
        dut.clock.step(1)
      }
    }
  }

  def logCycle(dut: SMSPTop, cycle: Int): Unit = {
    if (!SMSPConfig.enableSimLog) return

    // 更新仿真时间（纳秒）= cycle × 时钟周期
    SMSPConfig.currentSimTimeNs = (cycle * SMSPConfig.clockPeriodNs).toLong

    Logger.info("SIM", s"================ Cycle $cycle ================")

    // --- IFU 日志 ---
    val ifuReq = dut.io.ifu_icache_req_valid.peek().litToBoolean
    val ifuRsp = dut.io.ifu_icache_rsp_valid.peek().litToBoolean
    val ifuHit = dut.io.ifu_icache_rsp_hit.peek().litToBoolean
    val ifuWake = dut.io.ifu_icache_wakeup.peek().litToBoolean
    val ifuDec = dut.io.ifu_decoder_out_valid.peek().litToBoolean
    Logger.info("IFU", s"req=$ifuReq rsp=$ifuRsp hit=$ifuHit wake=$ifuWake dec_out=$ifuDec")

    // --- Decoder 日志 ---
    val decValidIn = dut.io.dec_validIn.peek().litToBoolean
    val decOpValid = dut.io.dec_microOpOut_valid.peek().litToBoolean
    val decOpcode = dut.io.dec_microOpOut_opcode.peek().litValue.toInt
    val decIll = dut.io.dec_illegalInst.peek().litToBoolean
    Logger.info("DEC", s"validIn=$decValidIn uOpValid=$decOpValid opcode=0x${decOpcode.toHexString} illegal=$decIll")

    // --- IBuffer 日志 ---
    val ibEmpty = dut.io.ibuf_emptyMask.peek().litValue.toInt
    val ibPopEn = dut.io.ibuf_popEn.peek().litToBoolean
    val ibPopWarp = dut.io.ibuf_popWarpId.peek().litValue.toInt
    val ibCredit = dut.io.ibuf_creditReturnValid.peek().litToBoolean
    Logger.info("IBUF", s"emptyMask=0b${ibEmpty.toBinaryString} popEn=$ibPopEn popWarp=$ibPopWarp creditRet=$ibCredit")

    // --- Caches 日志 ---
    val icReq = dut.io.icache_req_valid.peek().litToBoolean
    val icMiss = dut.io.icache_miss_valid.peek().litToBoolean
    val icHit = dut.io.icache_hit_valid.peek().litToBoolean
    Logger.info("ICACHE", s"req=$icReq hit=$icHit miss=$icMiss")

    val kcProbe = dut.io.kcache_probe_valid.peek().litToBoolean
    val kcRead = dut.io.kcache_oc_read_valid.peek().litToBoolean
    Logger.info("KCACHE", s"probe=$kcProbe oc_read=$kcRead")

    // --- Scheduler & Scoreboard 日志 ---
    val wsDisp = dut.io.ws_dispatch_valid.peek().litToBoolean
    val wsDispWarp = dut.io.ws_dispatch_warpId.peek().litValue.toInt
    val sbFull = dut.io.sb_slot_full_mask.peek().litValue.toInt
    val sbAlloc = dut.io.sb_alloc_req.peek().litToBoolean
    Logger.info("SCHED", s"dispatch=$wsDisp dispWarp=$wsDispWarp slotFull=0b${sbFull.toBinaryString} sbAlloc=$sbAlloc")

    // --- Operand Collector 日志 ---
    val ocIssue = dut.io.oc_issue_valid.peek().litToBoolean
    Logger.info("OC", s"issue=$ocIssue")

    // --- Register File (VGPR) 日志 ---
    val vgprRdCount = (0 until 4).count(i => dut.io.vgpr_read_reqs(i).peek().litToBoolean)
    val vgprWrCount = (0 until 4).count(i => dut.io.vgpr_write_reqs(i).peek().litToBoolean)
    Logger.info("VGPR", s"active_read_ports=$vgprRdCount active_write_ports=$vgprWrCount")
  }
}
