package memory

import scala.collection.mutable

class PageAllocator(val pageSize: Long = 4096) {
  // map physical page address to data array
  private val pages = mutable.HashMap[BigInt, Array[Byte]]()
  
  // keep track of allocated regions (start, size)
  private val allocations = mutable.TreeMap[BigInt, BigInt]()
  
  private var nextAddr: BigInt = BigInt("100000000", 16) // Start allocating above 4GB to test 64-bit

  def allocate(size: BigInt, alignment: BigInt): Option[BigInt] = {
    var addr = (nextAddr + alignment - 1) / alignment * alignment
    allocations.put(addr, size)
    nextAddr = addr + size
    Some(addr)
  }

  def free(addr: BigInt): Boolean = {
    allocations.remove(addr).isDefined
  }

  def getPage(pageAddr: BigInt): Array[Byte] = {
    pages.getOrElseUpdate(pageAddr, new Array[Byte](pageSize.toInt))
  }

  def read(addr: BigInt, size: Int): Array[Byte] = {
    val result = new Array[Byte](size)
    var remaining = size
    var currentAddr = addr
    var destOffset = 0
    
    while (remaining > 0) {
      val pageAddr = currentAddr - (currentAddr % pageSize)
      val offsetInPage = (currentAddr % pageSize).toInt
      val bytesInPage = math.min(pageSize - offsetInPage, remaining).toInt
      
      val page = pages.get(pageAddr)
      if (page.isDefined) {
        System.arraycopy(page.get, offsetInPage, result, destOffset, bytesInPage)
      } else {
        // Uninitialized memory is zeros
      }
      
      currentAddr += bytesInPage
      destOffset += bytesInPage
      remaining -= bytesInPage
    }
    result
  }

  def write(addr: BigInt, data: Array[Byte], mask: Array[Boolean]): Unit = {
    var remaining = data.length
    var currentAddr = addr
    var srcOffset = 0
    
    while (remaining > 0) {
      val pageAddr = currentAddr - (currentAddr % pageSize)
      val offsetInPage = (currentAddr % pageSize).toInt
      val bytesInPage = math.min(pageSize - offsetInPage, remaining).toInt
      
      val page = getPage(pageAddr)
      for (i <- 0 until bytesInPage) {
        if (mask == null || mask(srcOffset + i)) {
          page(offsetInPage + i) = data(srcOffset + i)
        }
      }
      
      currentAddr += bytesInPage
      srcOffset += bytesInPage
      remaining -= bytesInPage
    }
  }

  def getMemoryUsage: (BigInt, BigInt, BigInt) = {
    val allocatedPages = BigInt(pages.size)
    val totalPages = BigInt("18446744073709551616") / pageSize // 2^64 / pageSize
    val memoryUsed = allocatedPages * pageSize
    (allocatedPages, totalPages, memoryUsed)
  }
}
