package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class EXITSpec extends AnyFlatSpec {
  "EXIT" should "be correctly defined" in {
    val inst = new EXIT
    assert(inst.name == "EXIT")
  }
}
