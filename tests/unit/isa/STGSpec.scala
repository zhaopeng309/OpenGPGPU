package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class STGSpec extends AnyFlatSpec {
  "STG_F32" should "be correctly defined" in {
    val inst = new STG_F32
    assert(inst.name == "STG_F32")
  }
}
