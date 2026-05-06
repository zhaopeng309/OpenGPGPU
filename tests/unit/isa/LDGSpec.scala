package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class LDGSpec extends AnyFlatSpec {
  "LDG_F32" should "be correctly defined" in {
    val inst = new LDG_F32
    assert(inst.name == "LDG_F32")
  }
}
