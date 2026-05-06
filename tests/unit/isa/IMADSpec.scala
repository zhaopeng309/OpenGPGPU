package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class IMADSpec extends AnyFlatSpec {
  "IMAD_U32" should "be correctly defined" in {
    val inst = new IMAD_U32
    assert(inst.name == "IMAD_U32")
  }
}
