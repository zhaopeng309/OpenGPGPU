package memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PagedMemoryTest extends AnyFlatSpec with Matchers {
  "PageAllocator" should "allocate and free memory correctly in 64-bit space" in {
    val allocator = new PageAllocator(4096)
    
    // Allocate 16KB
    val addr1 = allocator.allocate(16384, 4096)
    addr1 shouldBe defined
    
    // Write data
    val testData = Array[Byte](1, 2, 3, 4)
    allocator.write(addr1.get, testData, null)
    
    // Read data
    val readData = allocator.read(addr1.get, 4)
    readData should equal (testData)
    
    // Check usage
    val stats = allocator.getMemoryUsage
    stats._1 should be > BigInt(0) // allocated pages
    
    // Free
    val freed = allocator.free(addr1.get)
    freed shouldBe true
  }

  "PagedMemoryModel" should "support large allocations" in {
    val addr = PagedMemoryModel.allocate(1024 * 1024, 4096) // 1MB
    addr shouldBe defined
    
    val testData = Array.fill[Byte](16)(0xAA.toByte)
    PagedMemoryModel.write(addr.get + 4096, testData, null)
    
    val readData = PagedMemoryModel.read(addr.get + 4096)
    readData should equal (testData)
  }
}
