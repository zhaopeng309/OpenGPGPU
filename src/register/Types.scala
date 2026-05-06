package opengpgpu.register

import chisel3._
import chisel3.util._

case class RegisterFileConfig(
  numWarps: Int = 16,
  numRegsPerWarp: Int = 256,
  numPredRegs: Int = 8,
  threadPerWarp: Int = 32,
  vGPRWidth: Int = 32,
  uGPRWidth: Int = 32,
  vGPRBanks: Int = 4
) {
  def warpIdWidth = log2Ceil(numWarps)
  def regIdWidth = log2Ceil(numRegsPerWarp)
  def predIdWidth = log2Ceil(numPredRegs)
  def bankIdWidth = log2Ceil(vGPRBanks)
  def uGPRDepth = numWarps * numRegsPerWarp
  def vGPRDepthPerBank = (numWarps * numRegsPerWarp) / vGPRBanks
}
