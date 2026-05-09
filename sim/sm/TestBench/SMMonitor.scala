package sim.sm.TestBench

import chisel3._
import chiseltest._
import sim.sm.SMTop
import sim.smsp.SMSPConfig
import utils.Logger

/**
 * SM UVM-Style Monitor
 *
 * 负责监视 SM 的输出信号，收集事务级信息：
 *   - Block Scheduler 状态 (busy, vgpr/smem available, active blocks)
 *   - Block Done 通知
 *   - SMSP 状态 (warp init/exit, cache requests)
 *   - RBMU 状态
 *
 * 对应 SM 开发计划 Feature 5.1: 模拟 ROC/L2 返回指令和数据
 */
class SMMonitor(dut: SMTop) {

  // ==========================================
  // 事务数据结构
  // ==========================================
  case class BlockSchedulerTransaction(
    cycle: Int,
    busy: Boolean,
    vgprAvail: Int,
    smemAvail: Int,
    activeBlocks: Int
  )

  case class BlockDoneTransaction(
    cycle: Int,
    valid: Boolean,
    blockId: Int
  )

  case class SMSPTransaction(
    cycle: Int,
    warpInitValid: Boolean,
    warpInitReady: Boolean,
    warpExitValid: Boolean,
    icacheReq: Boolean,
    kcacheReq: Boolean
  )

  // ==========================================
  // 事件回调类型
  // ==========================================
  type BSCallback = BlockSchedulerTransaction => Unit
  type BlockDoneCallback = BlockDoneTransaction => Unit
  type SMSPCallback = SMSPTransaction => Unit

  private var bsCallbacks: List[BSCallback] = Nil
  private var blockDoneCallbacks: List[BlockDoneCallback] = Nil
  private var smspCallbacks: List[SMSPCallback] = Nil

  // ==========================================
  // 注册回调
  // ==========================================
  def onBSEvent(cb: BSCallback): Unit = { bsCallbacks ::= cb }
  def onBlockDoneEvent(cb: BlockDoneCallback): Unit = { blockDoneCallbacks ::= cb }
  def onSMSPEvent(cb: SMSPCallback): Unit = { smspCallbacks ::= cb }

  // ==========================================
  // 采样所有探针信号
  // ==========================================
  def sample(cycle: Int): Unit = {
    // 更新仿真时间
    SMSPConfig.currentSimTimeNs = (cycle * SMSPConfig.clockPeriodNs).toLong

    // --- Block Scheduler 状态 ---
    val busy        = dut.io.busy.peek().litToBoolean
    val vgprAvail   = dut.io.vgpr_available.peek().litValue.toInt
    val smemAvail   = dut.io.smem_available.peek().litValue.toInt
    val activeBlks  = dut.io.active_block_count.peek().litValue.toInt

    val bsTx = BlockSchedulerTransaction(cycle, busy, vgprAvail, smemAvail, activeBlks)
    bsCallbacks.foreach(_(bsTx))

    // --- Block Done ---
    val bdValid = dut.io.block_done_valid.peek().litToBoolean
    val bdId    = dut.io.block_done_block_id.peek().litValue.toInt

    val bdTx = BlockDoneTransaction(cycle, bdValid, bdId)
    blockDoneCallbacks.foreach(_(bdTx))

    // --- SMSP 探针 ---
    val smsp0WarpInitV  = dut.io.smsp0_warp_init_valid.peek().litToBoolean
    val smsp0WarpInitR  = dut.io.smsp0_warp_init_ready.peek().litToBoolean
    val smsp0WarpExitV  = dut.io.smsp0_warp_exit_valid.peek().litToBoolean
    val smsp0ICacheReq  = dut.io.smsp0_icache_req_valid.peek().litToBoolean
    val smsp0KCacheReq  = dut.io.smsp0_kcache_req_valid.peek().litToBoolean

    val smspTx = SMSPTransaction(cycle, smsp0WarpInitV, smsp0WarpInitR,
      smsp0WarpExitV, smsp0ICacheReq, smsp0KCacheReq)
    smspCallbacks.foreach(_(smspTx))
  }

  // ==========================================
  // 默认日志回调
  // ==========================================
  def enableLogging(): Unit = {
    onBSEvent { tx =>
      Logger.info("MON_BS",
        s"cycle=${tx.cycle} busy=${tx.busy} vgprAvail=${tx.vgprAvail} " +
        s"smemAvail=${tx.smemAvail} activeBlocks=${tx.activeBlocks}")
    }
    onBlockDoneEvent { tx =>
      if (tx.valid) {
        Logger.info("MON_BD",
          s"cycle=${tx.cycle} BLOCK_DONE blockId=${tx.blockId}")
      }
    }
    onSMSPEvent { tx =>
      if (tx.warpInitValid || tx.warpExitValid) {
        Logger.info("MON_SMSP",
          s"cycle=${tx.cycle} warpInitV=${tx.warpInitValid} warpInitR=${tx.warpInitReady} " +
          s"warpExitV=${tx.warpExitValid} icacheReq=${tx.icacheReq} kcacheReq=${tx.kcacheReq}")
      }
    }
  }
}
