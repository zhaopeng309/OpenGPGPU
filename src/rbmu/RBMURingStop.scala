package opengpgpu.rbmu

import chisel3._
import chisel3.util._
import utils.RBMUManager

/**
 * RBMU Ring Stop 模板模块
 *
 * 根据 RBMU 开发计划 Block 2 定义：
 * - 模块接收 targetID 参数
 * - 在 Module 构造阶段，调用 RBMUManager.getRegsForTarget(targetID)
 * - 利用 Scala 的 foreach 循环自动生成 Chisel 的 is(offset) { ... } 逻辑
 *
 * Ring Stop 负责：
 * 1. 检查传入请求的 Target_ID 是否匹配自身
 * 2. 如果匹配，驱动目标单元的 RBMUTargetInterface
 * 3. 如果不匹配，将请求转发到下游 Ring Stop
 * 4. 处理响应环路的转发
 */
class RBMURingStop(targetID: Int) extends Module {
  val io = IO(new Bundle {
    // RBMU 环路口
    val ring = new RBMURingIO()

    // 目标单元寄存器访问接口
    val target = Flipped(new RBMUTargetInterface())
  })

  // ==========================================
  // 内部状态
  // ==========================================

  // 请求环路的流水线寄存器
  val req_in_reg = RegInit(0.U.asTypeOf(new RBMURequestBus))
  val req_in_valid_reg = RegInit(false.B)

  // 响应环路的流水线寄存器
  val resp_in_reg = RegInit(0.U.asTypeOf(new RBMUResponseBus))
  val resp_in_valid_reg = RegInit(false.B)

  // 等待响应的标志 (当本 Ring Stop 发起读取时置位)
  val wait_resp = RegInit(false.B)

  // 存储请求的 addr 用于响应路由
  val pending_addr = RegInit(0.U(24.W))

  // ==========================================
  // 请求处理
  // ==========================================

  // 从 req_in 接收数据
  req_in_reg := io.ring.req_in.bits
  when(io.ring.req_in.fire) {
    req_in_valid_reg := true.B
  }.elsewhen(io.ring.req_out.fire) {
    // 当请求转发到下游后，清除 valid
    req_in_valid_reg := false.B
  }

  // 解析 Target_ID
  val req_target_id = RBMUAddr.getTargetID(req_in_reg.addr)
  val req_offset    = RBMUAddr.getOffset(req_in_reg.addr)
  val is_broadcast  = req_target_id === RBMUAddr.TARGET_BROADCAST
  val is_my_target  = req_target_id === targetID.U || is_broadcast

  // 请求转发到下游的条件:
  // 1. 不是我的 Target (且不是 Broadcast)
  // 2. 或者请求已处理完毕 (写完成或读已发起)
  val forward_req = req_in_valid_reg && !is_my_target

  // ==========================================
  // 目标单元访问逻辑
  // ==========================================

  // 写操作
  io.target.wr_valid := false.B
  io.target.wr_data  := req_in_reg.data_in
  io.target.wr_offset := req_offset

  // 读操作
  io.target.rd_valid := false.B
  io.target.rd_offset := req_offset

  when(req_in_valid_reg && is_my_target) {
    when(req_in_reg.wr_en) {
      // 写操作: 直接驱动目标单元
      io.target.wr_valid := true.B
      io.target.wr_data  := req_in_reg.data_in
      io.target.wr_offset := req_offset

      // 写完成后清除 valid
      when(io.ring.req_out.fire || req_in_reg.last) {
        req_in_valid_reg := false.B
      }
    }

    when(req_in_reg.rd_en) {
      // 读操作: 驱动目标单元读取
      io.target.rd_valid := true.B
      io.target.rd_offset := req_offset

      // 发起读后，等待响应
      wait_resp := true.B
      pending_addr := req_in_reg.addr
      req_in_valid_reg := false.B
    }
  }

  // ==========================================
  // 响应处理
  // ==========================================

  // 从 resp_in 接收数据
  resp_in_reg := io.ring.resp_in.bits
  when(io.ring.resp_in.fire) {
    resp_in_valid_reg := true.B
  }.elsewhen(io.ring.resp_out.fire) {
    resp_in_valid_reg := false.B
  }

  // 本 Ring Stop 生成的响应
  val local_resp_data = io.target.rd_data
  val local_resp_valid = wait_resp
  val local_resp = Wire(new RBMUResponseBus)
  local_resp.data_out := local_resp_data
  local_resp.valid := true.B
  local_resp.error := false.B

  // 响应输出: 优先转发下游响应，其次本地响应
  when(resp_in_valid_reg) {
    // 转发下游响应
    io.ring.resp_out.bits := resp_in_reg
    io.ring.resp_out.valid := true.B
  }.elsewhen(local_resp_valid) {
    // 发送本地响应
    io.ring.resp_out.bits := local_resp
    io.ring.resp_out.valid := true.B
    wait_resp := false.B
  }.otherwise {
    io.ring.resp_out.bits := DontCare
    io.ring.resp_out.valid := false.B
  }

  // ==========================================
  // 请求输出
  // ==========================================
  io.ring.req_out.bits := req_in_reg
  io.ring.req_out.valid := forward_req

  // ==========================================
  // 输入 ready 信号
  // ==========================================
  // 当内部寄存器为空时，可以接收新请求
  io.ring.req_in.ready := !req_in_valid_reg
  io.ring.resp_in.ready := !resp_in_valid_reg
}

/**
 * RBMU Ring Stop 伴生对象
 * 提供工厂方法和辅助功能
 */
object RBMURingStop {

  /**
   * 创建并连接一个 Ring Stop 到目标单元
   *
   * @param targetID 目标单元 ID
   * @param target   目标单元的 RBMUTargetInterface
   * @param ring_in  上游 Ring 的请求输出 / 响应输入
   * @return 下游 Ring 的请求输入 / 响应输出
   */
  def connect(
    targetID: Int,
    target: RBMUTargetInterface,
    ring_in: RBMURingIO
  )(implicit p: => Module): RBMURingIO = {
    val stop = Module(new RBMURingStop(targetID))
    stop.io.target <> target
    stop.io.ring.req_in <> ring_in.req_out
    stop.io.ring.resp_in <> ring_in.resp_out
    stop.io.ring.req_out <> ring_in.req_in
    stop.io.ring.resp_out <> ring_in.resp_in
    stop.io.ring
  }
}
