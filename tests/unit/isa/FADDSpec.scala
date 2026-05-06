package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class FADDSpec extends AnyFlatSpec {
  "FADD_F32" should "be correctly defined" in {
    val inst = new FADD_F32
    assert(inst.name == "FADD_F32")
  }
}
