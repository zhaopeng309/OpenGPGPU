package utils

import chisel3._
import scala.collection.mutable.LinkedHashMap
import opengpgpu.rbmu.RegDescriptor

/**
 * RBMU 寄存器管理器单例 (MVP 简化版)
 * 采用"单例注册 + 自动生成"模式，实现去中心化的可编址寄存器开发。
 *
 * 根据 RBMU 开发计划:
 * - Feature 1.1: 使用 mutable.Map 存储 (Target_ID, Offset, Name) -> Data 的引用
 * - 实现 register(targetID, name, offset, reg) 接口
 * - 实现 forceWrite 调试接口
 */
object RBMUManager {
  // 使用 LinkedHashMap 保持注册顺序，便于生成逻辑调试
  // Key: globalKey (TargetID_Offset), Value: (Name, Data)
  private val regMap = LinkedHashMap[String, (String, Data)]()

  // 寄存器描述符列表 (用于文档生成和验证)
  private val regDescMap = LinkedHashMap[String, RegDescriptor]()

  /**
   * 模块内部调用：注册一个寄存器到 RBMU
   * @param targetID 目标单元 ID (如 0x1 对应 SMSP_0, 0x5 对应 LSU)
   * @param name     寄存器别名 (仅用于调试/显示)
   * @param offset   目标单元内部的偏移量 (20-bit)
   * @param reg      寄存器的 Chisel 引用
   */
  def register(targetID: Int, name: String, offset: Int, reg: Data): Unit = {
    val globalKey = f"${targetID}%X_${offset}%05X"
    if (regMap.contains(globalKey)) {
      println(s"[RBMU WARNING] Duplicate registration for key: $globalKey (Name: $name)")
    }
    regMap(globalKey) = (name, reg)
    println(s"[RBMU INFO] Registered SFR: Target=0x${targetID}%X, Offset=0x${offset}%05X, Name=$name")
  }

  /**
   * 批量注册一组寄存器描述符
   * @param targetID 目标单元 ID
   * @param descs    寄存器描述符列表
   */
  def registerDescriptors(targetID: Int, descs: Seq[RegDescriptor]): Unit = {
    descs.foreach { desc =>
      regDescMap(f"${targetID}%X_${desc.offset}%05X") = desc
      println(s"[RBMU INFO] Registered descriptor: Target=0x${targetID}%X, Offset=0x${desc.offset}%05X, Name=${desc.name}")
    }
  }

  /**
   * 获取属于特定目标单元的所有寄存器
   */
  def getRegsForTarget(targetID: Int): Seq[(Int, String, Data)] = {
    val prefix = f"${targetID}%X_"
    regMap.filter(_._1.startsWith(prefix)).toSeq.map { case (key, (name, reg)) =>
      val offset = Integer.parseInt(key.split("_")(1), 16)
      (offset, name, reg)
    }
  }

  /**
   * 获取属于特定目标单元的所有寄存器描述符
   */
  def getDescriptorsForTarget(targetID: Int): Seq[RegDescriptor] = {
    val prefix = f"${targetID}%X_"
    regDescMap.filter(_._1.startsWith(prefix)).toSeq.map(_._2)
  }

  /**
   * 获取所有已注册的寄存器 (按地址排序)
   */
  def getAllRegs: Seq[(Int, Int, String, Data)] = {
    regMap.toSeq.sortBy(_._1).map { case (key, (name, reg)) =>
      val parts = key.split("_")
      val tid = Integer.parseInt(parts(0), 16)
      val off = Integer.parseInt(parts(1), 16)
      (tid, off, name, reg)
    }
  }

  /**
   * 调试接口：打印当前所有已注册的寄存器映射表
   */
  def dumpMap(): Unit = {
    println("\n=== OpenGPGPU RBMU Global Address Map ===")
    println(f"${"Addr (Hex)"}%-15s | ${"Target"}%-10s | ${"Offset"}%-10s | ${"Name"}")
    println("-" * 60)
    regMap.toSeq.sortBy(_._1).foreach { case (key, (name, _)) =>
      val parts = key.split("_")
      val tid = parts(0)
      val off = parts(1)
      println(f"${tid + off}%-15s | 0x${tid}%-8s | 0x${off}%-8s | $name")
    }
    println("==========================================\n")
  }

  /**
   * 打印完整的地址映射表 (含描述)
   */
  def dumpFullMap(): Unit = {
    println("\n=== OpenGPGPU RBMU Full Address Map (with Descriptions) ===")
    println(f"${"Target_ID"}%-10s | ${"Offset"}%-10s | ${"Name"}%-20s | ${"Attr"}%-12s | ${"Description"}")
    println("-" * 100)
    regDescMap.toSeq.sortBy(_._1).foreach { case (key, desc) =>
      val parts = key.split("_")
      val tid = parts(0)
      val off = parts(1)
      println(f"0x${tid}%-8s | 0x${off}%-8s | ${desc.name}%-20s | ${desc.attr}%-12s | ${desc.description}")
    }
    println("==========================================\n")
  }

  /**
   * 仿真调试接口：直接向寄存器强制写入值 (仅限仿真环境)
   * 在 ChiselTest 环境下可以使用 .poke
   */
  def forceWrite(targetID: Int, offset: Int, value: UInt): Unit = {
    val key = f"${targetID}%X_${offset}%05X"
    regMap.get(key) match {
      case Some((name, reg)) =>
        println(s"[RBMU DEBUG] Force Writing $value to $name")
      case None =>
        println(s"[RBMU ERROR] Register not found at 0x${targetID}%X:0x${offset}%05X")
    }
  }

  /**
   * 清除所有注册 (用于测试隔离)
   */
  def clear(): Unit = {
    regMap.clear()
    regDescMap.clear()
  }
}
