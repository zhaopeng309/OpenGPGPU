package memory

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BasicMemoryOpsTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "MemoryController"

  it should "perform basic read and write operations" in {
    test(new MemoryController(MemoryControllerParams())) { c =>
      // Setup Config
      c.io.config.pageSize.poke(0.U)
      c.io.config.burstEnabled.poke(true.B)
      c.io.config.latency.poke(1.U)

      // Initialize
      c.io.req.valid.poke(false.B)
      c.io.resp.ready.poke(true.B)
      c.clock.step(2)

      // Send Write Request
      c.io.req.valid.poke(true.B)
      c.io.req.bits.addr.poke("h1000".U)
      c.io.req.bits.data.poke("hDEADBEEF".U)
      c.io.req.bits.size.poke(0.U)
      c.io.req.bits.isWrite.poke(true.B)
      c.io.req.bits.mask.poke("hFFFF".U)

      while (!c.io.req.ready.peek().litToBoolean) {
        c.clock.step(1)
      }
      c.clock.step(1)
      c.io.req.valid.poke(false.B)

      // Wait for simIO request and provide response
      while (!c.simIO.reqValid.peek().litToBoolean) {
        c.clock.step(1)
      }
      c.simIO.respValid.poke(true.B)
      c.simIO.respData.poke(0.U)

      while (!c.io.resp.valid.peek().litToBoolean) {
        c.clock.step(1)
      }
      c.clock.step(1)
      c.simIO.respValid.poke(false.B)

      // Send Read Request
      c.io.req.valid.poke(true.B)
      c.io.req.bits.addr.poke("h1000".U)
      c.io.req.bits.data.poke(0.U)
      c.io.req.bits.size.poke(0.U)
      c.io.req.bits.isWrite.poke(false.B)
      
      while (!c.io.req.ready.peek().litToBoolean) {
        c.clock.step(1)
      }
      c.clock.step(1)
      c.io.req.valid.poke(false.B)

      // Simulate the backend memory responding
      while (!c.simIO.reqValid.peek().litToBoolean) {
        c.clock.step(1)
      }
      c.simIO.respValid.poke(true.B)
      c.simIO.respData.poke("hDEADBEEF".U)

      while (!c.io.resp.valid.peek().litToBoolean) {
        c.clock.step(1)
      }
      c.io.resp.bits.data.expect("hDEADBEEF".U)
    }
  }
}
