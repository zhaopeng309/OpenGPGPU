package sim

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import utils.Logger
import isa.Registry
import isa.LDC_64

class SimTestbench extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "System Simulation"

  it should "run cycle-accurate simulation and print logs" in {
    // 仅当开启了日志配置时才执行，或者无论如何执行但配置决定是否打印
    SimConfig.initLogger()

    // 注册指令以便 Decoder 正常工作
    Registry.clear()
    Registry.register(new LDC_64)

    test(new SimTop).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
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
      val ldc64_opcode = BigInt("0C", 16)
      val ldc64_mod   = BigInt("40", 16)
      val inst1_val = (ldc64_opcode << 52) | (ldc64_mod << 40)
      val fillData = inst1_val // 放在 0x1000 对应的 word0

      // 运行 30 个时钟周期
      for (cycle <- 1 to 30) {
        // 在第三个周期提供 Cache Fill 响应
        if (cycle == 3) {
          dut.io.roc_icache_fill_valid.poke(true.B)
          dut.io.roc_icache_fill_addr.poke(0x1000.U)
          dut.io.roc_icache_fill_data.poke(fillData.U)
        } else {
          dut.io.roc_icache_fill_valid.poke(false.B)
        }

        // 记录状态并打印
        logCycle(dut, cycle)
        dut.clock.step(1)
      }
    }
  }

  def logCycle(dut: SimTop, cycle: Int): Unit = {
    if (!SimConfig.enableSimLog) return

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
