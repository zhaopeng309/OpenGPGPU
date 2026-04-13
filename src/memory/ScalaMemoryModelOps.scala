package memory

trait ScalaMemoryModelOps {
  // 基本读写操作 - 支持64位地址
  def read(addr: BigInt): Array[Byte]
  def write(addr: BigInt, data: Array[Byte], mask: Array[Boolean]): Unit
  
  // 突发传输操作 - 支持128B burst
  def burstRead(startAddr: BigInt, length: Int): Array[Byte]
  def burstWrite(startAddr: BigInt, data: Array[Byte], mask: Array[Boolean]): Unit
  
  // 内存分配操作 - 64位地址空间
  def allocate(size: BigInt, alignment: BigInt): Option[BigInt]
  def free(addr: BigInt): Boolean
  
  // 状态查询
  def getMemoryUsage: (BigInt, BigInt, BigInt) // allocatedPages, totalPages, memoryUsed
}
