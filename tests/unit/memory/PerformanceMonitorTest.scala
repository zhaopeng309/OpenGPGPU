package memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PerformanceMonitorTest extends AnyFlatSpec with Matchers {
  "PagedMemoryModel" should "track memory usage accurately" in {
    val initialStats = PagedMemoryModel.getMemoryUsage
    val initialPages = initialStats._1
    
    // Allocate 10 pages worth of memory (assuming 4KB pages, 40KB)
    val addr = PagedMemoryModel.allocate(40960, 4096)
    addr shouldBe defined
    
    // Touch memory to force page creation
    val testData = Array[Byte](1)
    for (i <- 0 until 10) {
      PagedMemoryModel.write(addr.get + i * 4096, testData, null)
    }
    
    val newStats = PagedMemoryModel.getMemoryUsage
    val newPages = newStats._1
    
    (newPages - initialPages) should be >= BigInt(10)
    newStats._3 should be >= BigInt(40960) // memoryUsed
  }
}
