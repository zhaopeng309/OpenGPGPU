package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class LDCSpec extends AnyFlatSpec {
  "LDC_64" should "be correctly defined" in {
    val inst = new LDC_64
    assert(inst.name == "LDC_64")
  }
}
