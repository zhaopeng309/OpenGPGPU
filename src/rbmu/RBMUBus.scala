package opengpgpu.rbmu

import chisel3._
import chisel3.util._

/**
 * RBMU 硬件总线协议定义 (EWN - Electronic Wired Network)
 *
 * 根据 RBMU MAS 协议，SM 内部所有非时序关键的配置均通过 EWN 总线进行。
 *
 * 地址格式: Addr[23:0]
 *   - Addr[23:20]: Target_ID (用于 Ring Stop 路由)
 *   - Addr[19:0]:  Reg_Offset (目标单元内部的偏移量)
 *
 * 支持 Broadcast 指令 (Target_ID=0xF)，实现一键同步 4 个 SMSP 的基址。
 */

// ==========================================
// RBMU 地址常量
// ==========================================
object RBMUAddr {
  val ADDR_WIDTH = 24
  val TARGET_ID_WIDTH = 4
  val OFFSET_WIDTH = 20
  val DATA_WIDTH = 64

  // Target_ID 分配
  val TARGET_SM_GLOBAL   = 0x0.U(TARGET_ID_WIDTH.W)
  val TARGET_SMSP_0      = 0x1.U(TARGET_ID_WIDTH.W)
  val TARGET_SMSP_1      = 0x2.U(TARGET_ID_WIDTH.W)
  val TARGET_SMSP_2      = 0x3.U(TARGET_ID_WIDTH.W)
  val TARGET_SMSP_3      = 0x4.U(TARGET_ID_WIDTH.W)
  val TARGET_LSU_HUB     = 0x5.U(TARGET_ID_WIDTH.W)
  val TARGET_GMMU        = 0x6.U(TARGET_ID_WIDTH.W)
  val TARGET_ROC         = 0x7.U(TARGET_ID_WIDTH.W)
  val TARGET_MMA_TMA     = 0x8.U(TARGET_ID_WIDTH.W)
  val TARGET_VALU        = 0x9.U(TARGET_ID_WIDTH.W)
  val TARGET_BROADCAST   = 0xF.U(TARGET_ID_WIDTH.W)

  // 从完整地址中提取 Target_ID 和 Offset
  def getTargetID(addr: UInt): UInt = addr(23, 20)
  def getOffset(addr: UInt): UInt = addr(19, 0)
  def makeAddr(targetID: UInt, offset: UInt): UInt = {
    Cat(targetID, offset)
  }
}

// ==========================================
// RBMU 总线 Bundle (Ring 接口)
// ==========================================

/**
 * RBMU 请求总线 (Request Ring)
 * 从 Host/Testbench 或上游 Ring Stop 发往下游
 */
class RBMURequestBus extends Bundle {
  val addr    = UInt(24.W)    // Addr[23:0]: Target_ID[23:20] + Offset[19:0]
  val data_in = UInt(64.W)    // 写入数据 (写操作时有效)
  val wr_en   = Bool()        // 写使能
  val rd_en   = Bool()        // 读使能
  val last    = Bool()        // 是否为环路的最后一个请求 (用于环路终结)
}

/**
 * RBMU 响应总线 (Response Ring)
 * 从目标 Ring Stop 发往 Host/Testbench
 */
class RBMUResponseBus extends Bundle {
  val data_out = UInt(64.W)   // 读取数据 (读操作时有效)
  val valid    = Bool()       // 响应有效
  val error    = Bool()       // 错误标志 (无效地址等)
}

/**
 * Ring Stop 的对外接口
 * 包含请求输入/输出和响应输入/输出
 */
class RBMURingIO extends Bundle {
  // 请求环 (Request Ring) - 从上游来，往下游去
  val req_in  = Flipped(Decoupled(new RBMURequestBus))
  val req_out = Decoupled(new RBMURequestBus)

  // 响应环 (Response Ring) - 从下游来，往上游去
  val resp_in  = Flipped(Decoupled(new RBMUResponseBus))
  val resp_out = Decoupled(new RBMUResponseBus)
}

// ==========================================
// RBMU 寄存器接口 (目标单元内部)
// ==========================================

/**
 * 目标单元 (如 SMSP, LSU) 暴露给 Ring Stop 的寄存器访问接口
 * Ring Stop 解析地址后，通过此接口读写目标单元内部的寄存器
 */
class RBMUTargetInterface extends Bundle {
  // 解码后的寄存器访问
  val rd_data  = Output(UInt(64.W))   // 读取数据 (组合逻辑)
  val wr_valid = Input(Bool())        // 写有效
  val wr_data  = Input(UInt(64.W))    // 写数据
  val wr_offset = Input(UInt(20.W))   // 写偏移地址
  val rd_valid = Input(Bool())        // 读有效
  val rd_offset = Input(UInt(20.W))   // 读偏移地址
}

// ==========================================
// RBMU 寄存器属性
// ==========================================
object RegAttr extends Enumeration {
  type RegAttr = Value
  val RO = Value("RO")         // 只读
  val WO = Value("WO")         // 只写
  val RW = Value("RW")         // 读写 (直接读写, 无影子)
  val RW_Shadow = Value("RW_Shadow") // 读写 + 影子同步
}

import RegAttr._

/**
 * 单个寄存器的描述信息
 * 用于 RBMUManager 注册和 Ring Stop 自动生成
 */
case class RegDescriptor(
  name: String,
  offset: Int,
  attr: RegAttr,
  description: String = ""
)
