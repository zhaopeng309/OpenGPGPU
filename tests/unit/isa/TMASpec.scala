package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class TMASpec extends AnyFlatSpec {
  "TMA_LOAD" should "be correctly defined" in {
    val inst = new TMA_LOAD
    assert(inst.name == "TMA_LOAD")
  }
}
