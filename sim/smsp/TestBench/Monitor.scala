package sim.smsp.TestBench

import chisel3._
import chiseltest._
import sim.smsp.{SMSPConfig, SMSPTop}
import utils.Logger

/**
 * UVM-Style Monitor
 * 
 * 负责监视 DUT 的输出信号，收集事务级信息：
 *   - IFU 状态 (icache req/rsp, decoder out)
 *   - Decoder 状态 (microOp valid, opcode, illegal)
 *   - IBuffer 状态 (emptyMask, pop)
 *   - Cache 状态 (icache hit/miss, kcache probe)
 *   - Scheduler & Scoreboard 状态 (dispatch, slot full)
 *   - Operand Collector 状态 (issue)
 *   - VGPR 状态 (read/write ports)
 * 
 * 提供回调机制，允许 Test 注册事件处理器。
 */
class SMSPMonitor(dut: SMSPTop) {

  // ==========================================
  // 事务数据结构
  // ==========================================
  case class IfuTransaction(
    cycle: Int,
    icacheReq: Boolean,
    icacheRsp: Boolean,
    icacheHit: Boolean,
    icacheWakeup: Boolean,
    decoderOut: Boolean
  )

  case class DecoderTransaction(
    cycle: Int,
    validIn: Boolean,
    uOpValid: Boolean,
    opcode: Int,
    illegal: Boolean
  )

  case class SchedulerTransaction(
    cycle: Int,
    dispatchValid: Boolean,
    dispatchWarpId: Int,
    slotFullMask: Int,
    sbAlloc: Boolean,
    sbRelease: Boolean
  )

  case class CacheTransaction(
    cycle: Int,
    icacheReq: Boolean,
    icacheHit: Boolean,
    icacheMiss: Boolean,
    kcacheProbe: Boolean,
    kcacheRead: Boolean
  )

  // ==========================================
  // 事件回调类型
  // ==========================================
  type IfuCallback = IfuTransaction => Unit
  type DecoderCallback = DecoderTransaction => Unit
  type SchedulerCallback = SchedulerTransaction => Unit
  type CacheCallback = CacheTransaction => Unit

  private var ifuCallbacks: List[IfuCallback] = Nil
  private var decoderCallbacks: List[DecoderCallback] = Nil
  private var schedulerCallbacks: List[SchedulerCallback] = Nil
  private var cacheCallbacks: List[CacheCallback] = Nil

  // ==========================================
  // 注册回调
  // ==========================================
  def onIfuEvent(cb: IfuCallback): Unit = { ifuCallbacks ::= cb }
  def onDecoderEvent(cb: DecoderCallback): Unit = { decoderCallbacks ::= cb }
  def onSchedulerEvent(cb: SchedulerCallback): Unit = { schedulerCallbacks ::= cb }
  def onCacheEvent(cb: CacheCallback): Unit = { cacheCallbacks ::= cb }

  // ==========================================
  // 采样所有探针信号
  // ==========================================
  def sample(cycle: Int): Unit = {
    // 更新仿真时间，使 Logger 时间戳正确显示
    SMSPConfig.currentSimTimeNs = (cycle * SMSPConfig.clockPeriodNs).toLong

    // --- IFU ---
    val ifuReq   = dut.io.ifu_icache_req_valid.peek().litToBoolean
    val ifuRsp   = dut.io.ifu_icache_rsp_valid.peek().litToBoolean
    val ifuHit   = dut.io.ifu_icache_rsp_hit.peek().litToBoolean
    val ifuWake  = dut.io.ifu_icache_wakeup.peek().litToBoolean
    val ifuDec   = dut.io.ifu_decoder_out_valid.peek().litToBoolean

    val ifuTx = IfuTransaction(cycle, ifuReq, ifuRsp, ifuHit, ifuWake, ifuDec)
    ifuCallbacks.foreach(_(ifuTx))

    // --- Decoder ---
    val decValidIn = dut.io.dec_validIn.peek().litToBoolean
    val decOpValid = dut.io.dec_microOpOut_valid.peek().litToBoolean
    val decOpcode  = dut.io.dec_microOpOut_opcode.peek().litValue.toInt
    val decIll     = dut.io.dec_illegalInst.peek().litToBoolean

    val decTx = DecoderTransaction(cycle, decValidIn, decOpValid, decOpcode, decIll)
    decoderCallbacks.foreach(_(decTx))

    // --- Scheduler & Scoreboard ---
    val wsDisp    = dut.io.ws_dispatch_valid.peek().litToBoolean
    val wsDispWid = dut.io.ws_dispatch_warpId.peek().litValue.toInt
    val sbFull    = dut.io.sb_slot_full_mask.peek().litValue.toInt
    val sbAlloc   = dut.io.sb_alloc_req.peek().litToBoolean
    val sbRelease = dut.io.sb_release_req.peek().litToBoolean

    val schedTx = SchedulerTransaction(cycle, wsDisp, wsDispWid, sbFull, sbAlloc, sbRelease)
    schedulerCallbacks.foreach(_(schedTx))

    // --- Cache ---
    val icReq   = dut.io.icache_req_valid.peek().litToBoolean
    val icHit   = dut.io.icache_hit_valid.peek().litToBoolean
    val icMiss  = dut.io.icache_miss_valid.peek().litToBoolean
    val kcProbe = dut.io.kcache_probe_valid.peek().litToBoolean
    val kcRead  = dut.io.kcache_oc_read_valid.peek().litToBoolean

    val cacheTx = CacheTransaction(cycle, icReq, icHit, icMiss, kcProbe, kcRead)
    cacheCallbacks.foreach(_(cacheTx))
  }

  // ==========================================
  // 默认日志回调（打印到 Logger）
  // ==========================================
  def enableLogging(): Unit = {
    onIfuEvent { tx =>
      Logger.info("MON_IFU",
        s"cycle=${tx.cycle} req=${tx.icacheReq} rsp=${tx.icacheRsp} " +
        s"hit=${tx.icacheHit} wake=${tx.icacheWakeup} dec_out=${tx.decoderOut}")
    }
    onDecoderEvent { tx =>
      Logger.info("MON_DEC",
        s"cycle=${tx.cycle} validIn=${tx.validIn} uOpValid=${tx.uOpValid} " +
        s"opcode=0x${tx.opcode.toHexString} illegal=${tx.illegal}")
    }
    onSchedulerEvent { tx =>
      Logger.info("MON_SCHED",
        s"cycle=${tx.cycle} dispatch=${tx.dispatchValid} " +
        s"dispWarp=${tx.dispatchWarpId} slotFull=0b${tx.slotFullMask.toBinaryString} " +
        s"sbAlloc=${tx.sbAlloc} sbRelease=${tx.sbRelease}")
    }
    onCacheEvent { tx =>
      Logger.info("MON_CACHE",
        s"cycle=${tx.cycle} icacheReq=${tx.icacheReq} hit=${tx.icacheHit} " +
        s"miss=${tx.icacheMiss} kcacheProbe=${tx.kcacheProbe} kcacheRead=${tx.kcacheRead}")
    }
  }
}
