package opengpgpu.lsu

import chisel3._
import chisel3.util._

/**
 * LSU 配置参数
 *
 * @param numSmsp      SMSP 数量（默认 4）
 * @param numWarps     每个 SMSP 的 Warp 数量（默认 8）
 * @param threadPerWarp 每个 Warp 的线程数（默认 32）
 * @param vGPRWidth    vGPR 数据位宽（默认 32）
 * @param addrWidth    地址位宽（默认 64）
 * @param dataWidth    数据位宽（默认 128）
 * @param lsuQueueDepth LSU 请求队列深度（默认 8）
 */
case class LSUConfig(
  numSmsp: Int = 4,
  numWarps: Int = 8,
  threadPerWarp: Int = 32,
  vGPRWidth: Int = 32,
  addrWidth: Int = 64,
  dataWidth: Int = 128,
  lsuQueueDepth: Int = 8
)

/**
 * LSU 请求类型枚举
 *
 * LDG   = 全局加载 (Global Load)
 * STG   = 全局存储 (Global Store)
 * LDC   = 常量加载 (Constant Load)
 * LDG_S = 全局加载 (标量)
 * ATOM  = 原子操作 (Atomic Read-Modify-Write)
 * TMA   = 张量内存加速器 (Tensor Memory Accelerator)
 * LDS   = 共享内存加载 (Shared Memory Load)
 * STS   = 共享内存存储 (Shared Memory Store)
 */
object LSUOpType {
  val LDG  = 0.U(4.W)  // 全局加载
  val STG  = 1.U(4.W)  // 全局存储
  val LDC  = 2.U(4.W)  // 常量加载
  val LDG_S = 3.U(4.W) // 全局加载 (标量)
  val ATOM = 4.U(4.W)  // 原子操作
  val TMA  = 5.U(4.W)  // 张量内存加速器
  val LDS  = 6.U(4.W)  // 共享内存加载
  val STS  = 7.U(4.W)  // 共享内存存储
}

/**
 * 原子操作类型
 */
object AtomOp {
  val ADD  = 0.U(4.W)  // 原子加
  val SUB  = 1.U(4.W)  // 原子减
  val EXCH = 2.U(4.W)  // 原子交换
  val CAS  = 3.U(4.W)  // 原子比较并交换
  val AND  = 4.U(4.W)  // 原子与
  val OR   = 5.U(4.W)  // 原子或
  val XOR  = 6.U(4.W)  // 原子异或
  val MIN  = 7.U(4.W)  // 原子最小值
  val MAX  = 8.U(4.W)  // 原子最大值
  val INC  = 9.U(4.W)  // 原子递增
  val DEC  = 10.U(4.W) // 原子递减
}

/**
 * LSU 请求包
 * 由 SMSP 发送到 LSU Hub
 */
class LSURequest extends Bundle {
  val valid       = Bool()
  val op_type     = UInt(4.W)      // LSUOpType
  val atom_op     = UInt(4.W)      // 原子操作子类型 (ATOM 时有效)
  val warp_id     = UInt(5.W)      // Warp ID
  val addr        = UInt(64.W)     // 目标地址
  val data        = UInt(128.W)    // 写入数据 (Store 时有效)
  val rd_index    = UInt(8.W)      // 目标寄存器索引
  val byte_mask   = UInt(16.W)     // 16 字节掩码
  val active_mask = UInt(32.W)     // 线程活跃掩码
  val barrier_id  = UInt(12.W)     // 屏障 ID
}

/**
 * LSU 响应包
 * 由 LSU Hub 返回给 SMSP
 */
class LSUResponse extends Bundle {
  val valid       = Bool()
  val warp_id     = UInt(5.W)
  val data        = UInt(128.W)    // 加载数据
  val addr        = UInt(64.W)     // 请求地址 (用于 MRQ Tag 匹配)
  val rd_index    = UInt(8.W)
  val barrier_id  = UInt(12.W)
  val error       = Bool()
}

/**
 * LSU Hub 仲裁器配置
 */
object LSUHubConstants {
  // 每个 SMSP 的 LSU 请求队列深度
  val SMSP_QUEUE_DEPTH = 4
  // 仲裁轮次计数宽度
  val ARB_ROUND_WIDTH = 2
}

/**
 * TMA (Tensor Memory Accelerator) 描述符
 */
class TMADescriptor extends Bundle {
  val src_addr      = UInt(64.W)   // 源地址 (Global Memory)
  val dst_addr      = UInt(64.W)   // 目标地址 (Shared Memory)
  val num_bytes     = UInt(20.W)   // 传输字节数
  val src_stride    = UInt(20.W)   // 源 stride
  val dst_stride    = UInt(20.W)   // 目标 stride
  val num_rows      = UInt(16.W)   // 行数
  val tile_width    = UInt(16.W)   // Tile 宽度
  val tile_height   = UInt(16.W)   // Tile 高度
  val swizzle_mode  = UInt(4.W)    // Swizzle 模式
  val reduce_op     = UInt(4.W)    // 归约操作类型
  val valid         = Bool()
}
