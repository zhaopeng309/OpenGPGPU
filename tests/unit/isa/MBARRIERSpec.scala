package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class MBARRIERSpec extends AnyFlatSpec {
  "MBARRIER_INIT" should "be correctly defined" in {
    val inst = new MBARRIER_INIT
    assert(inst.name == "MBARRIER_INIT")
  }
}
