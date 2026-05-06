package isa
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
class WGMMASpec extends AnyFlatSpec {
  "WGMMA_M16N16K16_F32_F16_F16" should "be correctly defined" in {
    val inst = new WGMMA_M16N16K16_F32_F16_F16
    assert(inst.name == "WGMMA_M16N16K16_F32_F16_F16")
  }
}
