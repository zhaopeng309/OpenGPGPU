package memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ErrorHandlingTest extends AnyFlatSpec with Matchers {
  "PageAllocator" should "handle invalid frees gracefully" in {
    val allocator = new PageAllocator(4096)
    
    val freed = allocator.free(BigInt("999999999"))
    freed shouldBe false
  }

  it should "return zeros for uninitialized memory reads" in {
    val allocator = new PageAllocator(4096)
    val addr = BigInt("8000000000", 16)
    
    val data = allocator.read(addr, 16)
    data should equal (Array.fill[Byte](16)(0))
  }
}
