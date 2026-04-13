package memory

object PagedMemoryModel extends ScalaMemoryModelOps {
  private val allocator = new PageAllocator(4096) // 4KB default
  
  override def read(addr: BigInt): Array[Byte] = {
    allocator.read(addr, 16) // Default 16 bytes (128-bit)
  }
  
  override def write(addr: BigInt, data: Array[Byte], mask: Array[Boolean]): Unit = {
    allocator.write(addr, data, mask)
  }
  
  override def burstRead(startAddr: BigInt, length: Int): Array[Byte] = {
    allocator.read(startAddr, length * 16)
  }
  
  override def burstWrite(startAddr: BigInt, data: Array[Byte], mask: Array[Boolean]): Unit = {
    allocator.write(startAddr, data, mask)
  }
  
  override def allocate(size: BigInt, alignment: BigInt): Option[BigInt] = {
    allocator.allocate(size, alignment)
  }
  
  override def free(addr: BigInt): Boolean = {
    allocator.free(addr)
  }
  
  override def getMemoryUsage: (BigInt, BigInt, BigInt) = {
    allocator.getMemoryUsage
  }
}
