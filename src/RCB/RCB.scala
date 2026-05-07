package opengpgpu.RCB

import chisel3._
import chisel3.util._

// Entry 状态位
class RCBEntryCtrl extends Bundle {
  val valid = Bool()   // Entry 被占用
  val dirty = Bool()   // 数据待物理写入 vGPR（写入后清零）
  val releaseReq = Bool() // 待发送释放信号
}

class RCB(implicit val p: RCBConfig) extends Module {
  val io = IO(new RCB_IO())

  // ── 内部状态 ──
  // Entry 池: 12 个槽位
  private val ENTRIES = p.numEntries
  
  // 使用 Reg 实现 Entry
  val entriesData = Reg(Vec(ENTRIES, new RCBEntryData()))
  val entriesCtrl = RegInit(VecInit(Seq.fill(ENTRIES)(0.U.asTypeOf(new RCBEntryCtrl()))))

  // ── Feature 2: Free List Management ──
  val freeList = Wire(Vec(ENTRIES, Bool()))
  for (i <- 0 until ENTRIES) {
    freeList(i) := !entriesCtrl(i).valid
  }
  val freeCount = PopCount(freeList)

  // ── Feature 2: Result Aggregator ──
  io.i_lsu_res.ready := false.B  // Phase 3 预留
  io.i_a2v_res.ready := false.B  // Phase 3 预留

  // vALU 路径：预留 2 个槽位给 LSU（防死锁高低水位控制）
  io.i_valu_res.ready := freeCount > 2.U

  when(io.i_valu_res.fire) {
    val allocIdx = PriorityEncoder(freeList)
    entriesCtrl(allocIdx).valid := true.B
    entriesCtrl(allocIdx).dirty := true.B
    entriesCtrl(allocIdx).releaseReq := false.B
    entriesData(allocIdx).data        := io.i_valu_res.bits.data
    entriesData(allocIdx).rd_index    := io.i_valu_res.bits.rd_index
    entriesData(allocIdx).warp_id     := io.i_valu_res.bits.warp_id
    entriesData(allocIdx).write_mask  := io.i_valu_res.bits.write_mask
    entriesData(allocIdx).barrier_id  := io.i_valu_res.bits.barrier_id
    entriesData(allocIdx).source_type := io.i_valu_res.bits.source_type
    entriesData(allocIdx).dest_type   := io.i_valu_res.bits.dest_type
  }

  // ── Feature 3: Bank Write Arbiter ──
  for (b <- 0 until p.numBanks) {
    // 收集映射到本 Bank 的所有 valid & dirty Entry
    val bankMatch = Wire(Vec(ENTRIES, Bool()))
    for (i <- 0 until ENTRIES) {
      bankMatch(i) := entriesCtrl(i).valid && entriesCtrl(i).dirty && entriesData(i).rd_index(1,0) === b.U
    }
    val anyMatch = bankMatch.reduce(_ || _)

    io.o_bk_write(b).valid := false.B
    io.o_bk_write(b).bits := DontCare

    when(anyMatch) {
      val matchIdx = PriorityEncoder(bankMatch)
      io.o_bk_write(b).valid := true.B
      io.o_bk_write(b).bits.wid   := entriesData(matchIdx).warp_id
      io.o_bk_write(b).bits.regId := entriesData(matchIdx).rd_index
      io.o_bk_write(b).bits.data  := entriesData(matchIdx).data
      io.o_bk_write(b).bits.mask  := entriesData(matchIdx).write_mask

      when(io.o_bk_write(b).fire) {
        entriesCtrl(matchIdx).dirty := false.B
        when(entriesData(matchIdx).source_type === 0.U) {
          entriesCtrl(matchIdx).releaseReq := true.B
        } .otherwise {
          entriesCtrl(matchIdx).valid := false.B
        }
      }
    }
  }

  // ── 释放 Entry 控制 ──
  // 当 dirty 变为 0 时，Entry 可以被释放 (valid = 0)
  // 此逻辑已合并到 bank fire 中以保证同一周期拉低
  
  // ── Feature 4: Barrier Release (Late Path) ──
  val releaseCandidates = Wire(Vec(ENTRIES, Bool()))
  for (i <- 0 until ENTRIES) {
    releaseCandidates(i) := entriesCtrl(i).valid && entriesCtrl(i).releaseReq
  }

  io.o_bar_rel.valid := false.B
  io.o_bar_rel.bits := DontCare

  val anyRelease = releaseCandidates.reduce(_ || _)
  io.o_bar_rel.valid := anyRelease
  when(anyRelease) {
    val relIdx = PriorityEncoder(releaseCandidates)
    io.o_bar_rel.bits.warp_id  := entriesData(relIdx).warp_id
    io.o_bar_rel.bits.rd_index := entriesData(relIdx).rd_index
    entriesCtrl(relIdx).releaseReq := false.B
    entriesCtrl(relIdx).valid := false.B
  }

  // ── Unused Bypass ──
  io.bypass_resp.hit := false.B
  io.bypass_resp.data := DontCare
}
