package memory

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BurstTransferTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "MemoryController Burst Transfer"

  it should "perform 128B burst read operations" in {
    test(new MemoryController(MemoryControllerParams())) { c =>
      // Setup Config
      c.io.config.pageSize.poke(0.U)
      c.io.config.burstEnabled.poke(true.B)
      c.io.config.latency.poke(1.U)

      // Initialize
      c.io.req.valid.poke(false.B)
      c.io.resp.ready.poke(true.B)
      c.clock.step(2)

      // Send Burst Read Request (size = 3, meaning 4 beats of 16 bytes = 64 bytes)
      c.io.req.valid.poke(true.B)
      c.io.req.bits.addr.poke("h2000".U)
      c.io.req.bits.size.poke(3.U)
      c.io.req.bits.isWrite.poke(false.B)
      
      while (!c.io.req.ready.peek().litToBoolean) {
        c.clock.step(1)
      }
      c.clock.step(1)
      c.io.req.valid.poke(false.B)

      // Simulate the backend memory responding to 4 beats
      for (i <- 0 until 4) {
        while (!c.simIO.reqValid.peek().litToBoolean) {
          c.clock.step(1)
        }
        
        val expectedAddr = "h2000".U.litValue + i * 16
        c.simIO.reqAddr.expect(expectedAddr.U)
        
        c.simIO.respValid.poke(true.B)
        c.simIO.respData.poke((0x1000 + i).U)

        while (!c.io.resp.valid.peek().litToBoolean) {
          c.clock.step(1)
        }
        c.io.resp.bits.data.expect((0x1000 + i).U)
        
        c.clock.step(1)
        c.simIO.respValid.poke(false.B)
      }
      
      c.clock.step(2)
      c.io.resp.valid.expect(false.B)
    }
  }
}
