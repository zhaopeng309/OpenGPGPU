package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class ISETPSpec extends AnyFlatSpec {
  "ISETP_GE_U32" should "be correctly defined" in {
    val inst = new ISETP_GE_U32
    assert(inst.name == "ISETP_GE_U32")
  }
}
