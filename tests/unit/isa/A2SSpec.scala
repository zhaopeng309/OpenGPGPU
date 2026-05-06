package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class A2SSpec extends AnyFlatSpec {
  "A2S_F32TO16_RELU" should "be correctly defined" in {
    val inst = new A2S_F32TO16_RELU
    assert(inst.name == "A2S_F32TO16_RELU")
  }
}
