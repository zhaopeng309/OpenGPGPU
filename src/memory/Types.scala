package memory

import chisel3._
import chisel3.util._

object Types {
  // 64位地址类型别名
  def UInt64 = UInt(64.W)
  
  // 128位数据类型别名（用于128B burst，由于Chisel/FIRRTL中大宽度的支持情况，这里可能指128-bit，即16字节。
  // 但是按说明，128B 是 1024-bit。原计划写的是 `val data = UInt128` 和 `// 128位数据（支持128B burst）`
  // 这说明一个数据节拍是128-bit(16 Byte), 而burst支持1-16个节拍，16 * 16B = 256B, 满足最大128B的burst(其实可以配置为8个节拍=128B)
  def UInt128 = UInt(128.W)
}

import Types._

// 1. 请求与响应包定义（64位地址版本）
class MemoryRequest extends Bundle {
  val addr    = UInt64          // 64位物理地址
  val data    = UInt128         // 128位数据（16字节）
  val size    = UInt(4.W)       // 传输大小（0-15表示1-16个块）
  val isWrite = Bool()          // 写操作标志
  val mask    = UInt(16.W)      // 16字节掩码
}

class MemoryResponse extends Bundle {
  val data  = UInt128           // 128位响应数据
  val error = Bool()            // 错误标志
}

// 内存配置参数
class MemoryConfig extends Bundle {
  val pageSize     = UInt(2.W)  // 页面大小配置
  val burstEnabled = Bool()     // 突发传输使能
  val latency      = UInt(8.W)  // 访问延迟配置
}

class MemoryUsageStats extends Bundle {
  val allocatedPages = UInt(32.W)
  val totalPages     = UInt(32.W)
  val memoryUsed     = UInt64
}
