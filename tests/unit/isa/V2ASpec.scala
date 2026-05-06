package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class V2ASpec extends AnyFlatSpec {
  "V2A" should "be correctly defined" in {
    val inst = new V2A
    assert(inst.name == "V2A")
  }
}
