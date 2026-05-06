package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class WBRASpec extends AnyFlatSpec {
  "WBRA_U" should "be correctly defined" in {
    val inst = new WBRA_U
    assert(inst.name == "WBRA_U")
  }
}
