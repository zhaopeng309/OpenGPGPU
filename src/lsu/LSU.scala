package opengpgpu.lsu

import chisel3._
import chisel3.util._
import opengpgpu.collector.{OperandBundle, CollectorConfig}
import opengpgpu.RCB.{ResultPacket, RCBConfig}
import opengpgpu.ulm.{ULMRequest, ULMConfig, ULMResponse}

/**
 * 完整的 LSU 微架构顶层集成
 * 包含了 Ingress -> ARU -> AGU -> Space Decoder / Bypass -> ACU -> RQI -> 内存请求 -> LDQ -> DRU 写回
 */
class LSU(implicit config: CollectorConfig, lsuCfg: LSUConfig, rcbCfg: RCBConfig, ulmCfg: ULMConfig) extends Module {
  val io = IO(new Bundle {
    // 4个 SMSP 正常的 LSU 请求 (Load)
    val in = Flipped(Vec(lsuCfg.numSmsp, Decoupled(new OperandBundle())))
    
    // vALU 旁路输入的 STG/STS 指令 (4个 SMSP)
    val valu_bypass = Flipped(Vec(lsuCfg.numSmsp, Decoupled(new OperandBundle())))

    val out = Decoupled(new ResultPacket())
    
    // ULM 接口
    val ulm_req = Decoupled(new ULMRequest())
    val ulm_resp = Flipped(Valid(new ULMResponse()))
  })

  // 1. Ingress 信用仲裁器 (带 Bypass 通道)
  val ingress = Module(new Ingress())
  for (i <- 0 until lsuCfg.numSmsp) {
    ingress.io.smsp_req_valid(i) := io.in(i).valid
    ingress.io.smsp_req_bits(i)  := io.in(i).bits
    io.in(i).ready := ingress.io.smsp_req_ready(i)
    
    ingress.io.valu_bypass_valid(i) := io.valu_bypass(i).valid
    ingress.io.valu_bypass_bits(i)  := io.valu_bypass(i).bits
    io.valu_bypass(i).ready := ingress.io.valu_bypass_ready(i)
    
    // 信用先默认返回
    ingress.io.credit_return(i) := true.B
  }

  // 2. 例化各级子模块
  val aru = Module(new ARU())
  val agu = Module(new AGU())
  val acu = Module(new ACU())
  val mrq = Module(new MRQ())
  val rqi = Module(new RQI())
  val ldq = Module(new LDQ())
  val dru = Module(new DRU())
  val sdq = Module(new GlobalSDQ(16))
  
  // 3. 正常请求 -> ARU -> AGU
  aru.io.in <> ingress.io.out
  agu.io.in <> aru.io.out
  
  // 4. Bypass 通道 (vALU 送来的 STG/STS)
  val acuArb = Module(new Arbiter(new ACUReq(), 2))
  
  // bypass 包装成 ACUReq
  val bypass_req = Wire(new ACUReq())
  bypass_req.op := ingress.io.bypass_out.bits
  bypass_req.op_type := LSUOpType.STG
  for (i <- 0 until config.threadPerWarp) {
    bypass_req.addr(i) := ingress.io.bypass_out.bits.src1Data(i) + ingress.io.bypass_out.bits.src2Data(i)
  }
  bypass_req.valid_mask := ingress.io.bypass_out.bits.activeMask
  
  acuArb.io.in(0).valid := ingress.io.bypass_out.valid
  acuArb.io.in(0).bits := bypass_req
  // Ready 逻辑: 需要 ACU 和 SDQ 都 ready 才能接收 bypass
  ingress.io.bypass_out.ready := acuArb.io.in(0).ready && sdq.io.enq.ready
  
  // 将 bypass 的数据导入 SDQ 入口缓冲
  sdq.io.enq.valid := ingress.io.bypass_out.valid && acuArb.io.in(0).ready
  for (i <- 0 until config.threadPerWarp) {
    sdq.io.enq.bits.data(i) := ingress.io.bypass_out.bits.src3Data(i)
    sdq.io.enq.bits.ea_offsets(i) := bypass_req.addr(i)(6, 2)
  }
  sdq.io.enq.bits.hit_mask := bypass_req.valid_mask

  // 正常路径 -> ACU
  val normal_req = Wire(new ACUReq())
  normal_req.op := agu.io.out.bits.op
  normal_req.op_type := agu.io.out.bits.op_type
  normal_req.addr := agu.io.out.bits.addr
  normal_req.valid_mask := agu.io.out.bits.op.activeMask
  
  acuArb.io.in(1).valid := agu.io.out.valid
  acuArb.io.in(1).bits := normal_req
  agu.io.out.ready := acuArb.io.in(1).ready
  
  acu.io.in <> acuArb.io.out

  // 5. ACU 产生访存请求 -> MRQ
  mrq.io.acu_resp <> acu.io.out

  // 6. MRQ + SDQ -> RQI -> ULM
  rqi.io.mrq_req <> mrq.io.mem_req
  rqi.io.sdq_data <> sdq.io.deq
  io.ulm_req <> rqi.io.ulm_req

  // 将 ULM 的响应映射回 LSUResponse 格式给 LDQ 和 MRQ
  val lsu_resp_from_ulm = Wire(Valid(new LSUResponse()))
  lsu_resp_from_ulm.valid := io.ulm_resp.valid
  lsu_resp_from_ulm.bits.valid := io.ulm_resp.valid
  lsu_resp_from_ulm.bits.warp_id := io.ulm_resp.bits.warp_id
  lsu_resp_from_ulm.bits.data := io.ulm_resp.bits.data
  lsu_resp_from_ulm.bits.addr := mrq.io.resp_addr
  lsu_resp_from_ulm.bits.rd_index := io.ulm_resp.bits.rd_index
  lsu_resp_from_ulm.bits.barrier_id := io.ulm_resp.bits.barrier_id
  lsu_resp_from_ulm.bits.error := io.ulm_resp.bits.error

  mrq.io.mem_resp := lsu_resp_from_ulm
  ldq.io.mem_resp := lsu_resp_from_ulm

  // 7. MRQ 响应完成 -> DRU -> RCB 写回
  dru.io.ldq_deq <> ldq.io.deq
  dru.io.mrq_notify <> mrq.io.dru_notify

  io.out <> dru.io.out
}
