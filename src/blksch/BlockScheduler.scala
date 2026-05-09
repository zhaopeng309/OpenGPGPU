package blksch

import chisel3._
import chisel3.util._

// ==========================================
// Block Scheduler IO 接口定义
// ==========================================

class BlockSchedulerIO extends Bundle {
  // === ACE RMU 接口 (BD 接收) ===
  val bd_valid = Input(Bool())
  val bd_ready = Output(Bool())
  val bd_bits  = Input(new BlockDescriptor())

  // === SMSP 接口 (4 个 SMSP, Decoupled 握手) ===
  val smsp = Vec(BSConfig.numSmsp, new Bundle {
    val init = Decoupled(new WarpInitBundle())
    // Warp 完成信号 (每 SMSP 独立)
    val warp_exit_valid = Input(Bool())
    val warp_exit_block_id = Input(UInt(BSConfig.blockIdWidth.W))
    val warp_exit_warp_id  = Input(UInt(BSConfig.warpIdWidth.W))
  })

  // === 资源状态输出 (给 RBMU) ===
  val busy = Output(Bool())                          // BS 忙状态
  val vgpr_available = Output(UInt(BSConfig.chunkWidth.W))  // 可用 vGPR Chunk 数
  val smem_available = Output(UInt(BSConfig.chunkWidth.W))  // 可用 SMem Chunk 数
  val active_block_count = Output(UInt(BSConfig.blockIdWidth.W)) // 活跃 Block 数

  // === Block Done 通知 (给 ACE) ===
  val block_done_valid = Output(Bool())
  val block_done_block_id = Output(UInt(BSConfig.blockIdWidth.W))

  // === L1 预取触发 (给 L0 ICache / L0 KCache) ===
  val preload_icache_valid = Output(Bool())
  val preload_icache_addr  = Output(UInt(64.W))
  val preload_kcache_valid = Output(Bool())
  val preload_kcache_addr  = Output(UInt(64.W))
}

// ==========================================
// Block Scheduler 主模块
// ==========================================

/**
 * Block Scheduler (BS)
 *
 * 对应 MAS 微架构设计规格书 v2.0
 *
 * 功能:
 * 1. 从 ACE RMU 接收 Block Descriptor (BD)
 * 2. 资源分配 (vGPR/SMem/Barrier) — 全拿或全不拿
 * 3. Warp 裂变与 Round-Robin 分发到 4 个 SMSP
 * 4. 生命周期管理 (ABT) 与异步资源释放
 * 5. L1 预取触发
 */
class BlockScheduler extends Module {
  val io = IO(new BlockSchedulerIO())

  // ==========================================
  // 内部寄存器与状态定义
  // ==========================================

  // ── 主状态机 ──
  object BSState extends ChiselEnum {
    val S_IDLE, S_ALLOC, S_DISPATCH, S_WAIT_DONE, S_CLEANUP = Value
  }
  val state = RegInit(BSState.S_IDLE)

  // ── BD 缓冲 (接收后锁存) ──
  val bd_buffer = Reg(new BlockDescriptor())
  val bd_valid_reg = RegInit(false.B)

  // ── 资源 Bitmap ──
  val vgpr_bitmap = RegInit(VecInit(Seq.fill(BSConfig.vgprChunks)(false.B))) // true=已分配
  val smem_bitmap = RegInit(VecInit(Seq.fill(BSConfig.smemChunks)(false.B))) // true=已分配
  val bar_pool    = RegInit(VecInit(Seq.fill(BSConfig.barPoolSize)(false.B))) // true=已分配

  // ── 分配结果 ──
  val alloc_vgpr_base  = RegInit(0.U(12.W))
  val alloc_vgpr_chunks = RegInit(0.U(16.W))
  val alloc_smem_base  = RegInit(0.U(16.W))
  val alloc_smem_chunks = RegInit(0.U(16.W))
  val alloc_bar_id     = RegInit(0.U(4.W))
  val alloc_bar_count  = RegInit(0.U(4.W))
  val alloc_done       = RegInit(false.B)

  // ── Warp 裂变状态 ──
  val total_threads  = RegInit(0.U(30.W))
  val total_warps    = RegInit(0.U(6.W))
  val warp_index     = RegInit(0.U(6.W))
  val smsp_rr_ptr    = RegInit(0.U(BSConfig.smspIdWidth.W)) // Round-Robin 指针

  // ── Active Block Table (ABT) ──
  val abt_table = RegInit(VecInit(Seq.fill(BSConfig.maxBlocks) {
    val entry = Wire(new ABTEntry())
    entry.valid := false.B
    entry.warp_remaining := 0.U
    entry.total_warps := 0.U
    entry.vgpr_base := 0.U
    entry.vgpr_chunks := 0.U
    entry.smem_base := 0.U
    entry.smem_chunks := 0.U
    entry.bar_id := 0.U
    entry.bar_count := 0.U
    entry.kernel_pc := 0.U
    entry
  }))
  val abt_alloc_ptr = RegInit(0.U(BSConfig.blockIdWidth.W)) // 下一个可用的 ABT 条目

  // ── 异步释放 FIFO (对应 MAS §3.3.3) ──
  val free_fifo = Module(new Queue(new FreeRequest(), entries = BSConfig.freeFifoDepth))

  // ── 每 SMSP 独立完成 FIFO (对应 MAS §6.3.2) ──
  val exit_fifos = Seq.fill(BSConfig.numSmsp) {
    Module(new Queue(new WarpDone(), entries = BSConfig.exitFifoDepth))
  }

  // ── 完成轮询器 ──
  val rr_exit_ptr = RegInit(0.U(BSConfig.smspIdWidth.W))

  // ── Block Done 脉冲 ──
  val block_done_pulse = RegInit(false.B)
  val block_done_id    = RegInit(0.U(BSConfig.blockIdWidth.W))

  // ── 预取脉冲 (保持直到被消费或下一个 BD 到达) ──
  val preload_icache = RegInit(false.B)
  val preload_kcache = RegInit(false.B)
  val preload_pc     = RegInit(0.U(64.W))
  val preload_kptr   = RegInit(0.U(64.W))

  // ==========================================
  // 辅助函数
  // ==========================================

  /** 计算总线程数 */
  def calcTotalThreads(bd: BlockDescriptor): UInt = {
    bd.thread_dim_x * bd.thread_dim_y * bd.thread_dim_z
  }

  /** 计算 Warp 数量: ceil(Total_Threads / 32) */
  def calcNumWarps(totalThreads: UInt): UInt = {
    val divided = totalThreads >> 5  // /32
    val remainder = totalThreads(4, 0).orR
    Mux(remainder, divided + 1.U, divided)
  }

  /** 计算尾部 Warp 的 Active Mask */
  def calcTailMask(totalThreads: UInt, numWarps: UInt): UInt = {
    val tailThreads = totalThreads(4, 0)
    // 使用 Mux 链代替动态移位，避免 DshlTooBig 错误
    val tailMask = WireDefault("hFFFFFFFF".U(32.W))
    // 当 tailThreads 在 1-31 范围内时生成掩码
    for (i <- 1 until 32) {
      when(tailThreads === i.U) {
        tailMask := ((BigInt(1) << i) - 1).U
      }
    }
    Mux(warp_index === numWarps - 1.U && tailThreads =/= 0.U,
      tailMask,
      "hFFFFFFFF".U(32.W))
  }

  /** 计算 vGPR 基址: Block_Base + (i * vGPR_Per_Thread * 32 * 4) */
  def calcVgprBase(blockBase: UInt, warpIdx: UInt, vgprPerThread: UInt): UInt = {
    blockBase + (warpIdx * vgprPerThread * 32.U * 4.U)
  }

  /** PriorityEncoder 查找空闲位 */
  def findFreeSlot(bitmap: Seq[Bool]): (Bool, UInt) = {
    val vec = VecInit(bitmap.map(b => !b))
    val anyFree = vec.asUInt.orR
    val idx = PriorityEncoder(vec)
    (anyFree, idx)
  }

  /** 分配连续的 Chunk 块 (简化实现: 每次只分配 1 个 Chunk) */
  def allocChunks(bitmap: Seq[Bool], numChunks: UInt): (Bool, UInt, UInt) = {
    val n = bitmap.length
    val found = Wire(Bool())
    val base  = Wire(UInt(log2Ceil(n).W))
    val mask  = Wire(UInt(n.W))

    found := false.B
    base  := 0.U
    mask  := 0.U

    val bitmapUInt = VecInit(bitmap).asUInt
    val freeMask = ~bitmapUInt

    // 当 numChunks == 0 时，不需要分配任何资源，视为成功
    when(numChunks === 0.U) {
      found := true.B
      base  := 0.U
      mask  := 0.U
    }.elsewhen(numChunks >= 1.U) {
      // 简化: 只分配 1 个 Chunk (对于 vGPR, 每个 Warp 需要 1 个 Chunk)
      // 对于 SMem, 同样简化为 1 个 Chunk
      // 实际硬件中应使用 Tree-based Allocator (MAS §3.3.2)
      // 查找第一个空闲位
      val firstFree = PriorityEncoder(VecInit(bitmap.map(b => !b)))
      val firstFreeValid = freeMask.orR
      when(firstFreeValid) {
        found := true.B
        base  := firstFree
        // 使用 Mux 链生成掩码，避免动态移位
        mask := 0.U
        for (i <- 0 until n) {
          when(firstFree === i.U) {
            mask := (BigInt(1) << i).U
          }
        }
      }
    }

    (found, base, mask)
  }

  /** 分配 Barrier ID (简化: 只分配 1 个 Barrier) */
  def allocBarriers(count: UInt): (Bool, UInt) = {
    val barFree = VecInit(bar_pool.map(b => !b)).asUInt
    val anyFree = barFree.orR
    val idx = PriorityEncoder(barFree)
    // 简化: 只要有空闲 Barrier 就分配
    // 当 count == 0 时，不需要分配 Barrier，视为成功
    val enough = Wire(Bool())
    enough := Mux(count === 0.U, true.B, count >= 1.U && anyFree)
    (enough, idx)
  }

  // ==========================================
  // 默认信号赋值 (防止 Chisel RefNotInitializedException)
  // 所有输出信号必须在所有执行路径中都有赋值
  // ==========================================

  // ── SMSP init 接口默认值 ──
  for (smspId <- 0 until BSConfig.numSmsp) {
    io.smsp(smspId).init.valid := false.B
    io.smsp(smspId).init.bits  := DontCare
  }

  // ── Exit FIFO 默认值 ──
  for (smspId <- 0 until BSConfig.numSmsp) {
    exit_fifos(smspId).io.enq.valid := false.B
    exit_fifos(smspId).io.enq.bits  := DontCare
    exit_fifos(smspId).io.deq.ready := false.B
  }

  // ── Free FIFO 默认值 ──
  free_fifo.io.enq.valid := false.B
  free_fifo.io.enq.bits  := DontCare
  free_fifo.io.deq.ready := false.B

  // ==========================================
  // 主状态机 (对应 MAS §7)
  // ==========================================

  // ── BD Ready 信号 ──
  // 当 BS 处于 IDLE 状态且 bd_buffer 为空时，可以接收新 BD
  io.bd_ready := state === BSState.S_IDLE && !bd_valid_reg

  // ── BD 接收 ──
  when(io.bd_ready && io.bd_valid) {
    bd_buffer := io.bd_bits
    bd_valid_reg := true.B
    // 延迟一个周期进入 S_ALLOC，等待 bd_buffer 寄存器稳定
    state := BSState.S_ALLOC
  }

  // ── S_ALLOC: 资源分配 ──
  // 对应 MAS §3 和 §7.1 的原子分配原则
  when(state === BSState.S_ALLOC) {
    // 使用 bd_buffer (已在上一周期锁存)
    val bd = bd_buffer

    // 计算资源需求
    val threads = calcTotalThreads(bd)
    val nWarps  = calcNumWarps(threads)
    val vgprChunksNeeded = nWarps  // 每个 Warp 需要一个 Chunk
    val smemChunksNeeded = (bd.smem_bytes >> 7.U) + Mux(bd.smem_bytes(6, 0).orR, 1.U, 0.U) // ceil(smem_bytes/128)

    // 分配 vGPR
    val (vgprOk, vgprBase, vgprMask) = allocChunks(vgpr_bitmap, vgprChunksNeeded)

    // 分配 SMem
    val (smemOk, smemBase, smemMask) = allocChunks(smem_bitmap, smemChunksNeeded)

    // 分配 Barrier
    val (barOk, barId) = allocBarriers(bd.barrier_req)

    val allOk = vgprOk && smemOk && barOk

    when(allOk) {
      // 锁定资源
      // vGPR
      for (i <- 0 until BSConfig.vgprChunks) {
        when(vgprMask(i)) {
          vgpr_bitmap(i) := true.B
        }
      }
      // SMem
      for (i <- 0 until BSConfig.smemChunks) {
        when(smemMask(i)) {
          smem_bitmap(i) := true.B
        }
      }
      // Barrier
      for (i <- 0 until BSConfig.barPoolSize) {
        when(i.U >= barId && i.U < barId + bd.barrier_req) {
          bar_pool(i) := true.B
        }
      }

      // 保存分配结果
      alloc_vgpr_base  := vgprBase
      alloc_vgpr_chunks := vgprMask
      alloc_smem_base  := smemBase
      alloc_smem_chunks := smemMask
      alloc_bar_id     := barId
      alloc_bar_count  := bd.barrier_req

      // 初始化 Warp 裂变
      total_threads := threads
      total_warps   := nWarps
      warp_index    := 0.U
      smsp_rr_ptr   := 0.U

      // 写入 ABT
      val abtIdx = abt_alloc_ptr
      abt_table(abtIdx).valid := true.B
      abt_table(abtIdx).warp_remaining := nWarps
      abt_table(abtIdx).total_warps := nWarps
      abt_table(abtIdx).vgpr_base := vgprBase
      abt_table(abtIdx).vgpr_chunks := vgprMask
      abt_table(abtIdx).smem_base := smemBase
      abt_table(abtIdx).smem_chunks := smemMask
      abt_table(abtIdx).bar_id := barId
      abt_table(abtIdx).bar_count := bd.barrier_req
      abt_table(abtIdx).kernel_pc := bd.kernel_pc

      // 更新 ABT 分配指针 (Round-Robin)
      abt_alloc_ptr := Mux(abt_alloc_ptr === (BSConfig.maxBlocks - 1).U, 0.U, abt_alloc_ptr + 1.U)

      // 触发预取 (Feature 4.1)
      preload_icache := true.B
      preload_kcache := true.B
      preload_pc     := bd.kernel_pc
      preload_kptr   := bd.kcache_ptr

      // 进入分发状态
      state := BSState.S_DISPATCH
    }.otherwise {
      // 资源不足，保持 S_ALLOC 等待释放
      state := BSState.S_ALLOC
    }
  }

  // ── S_DISPATCH: Warp 裂变与分发 ──
  // 对应 MAS §5 的 Dispatcher FSM
  when(state === BSState.S_DISPATCH) {
    val bd = bd_buffer
    val currentWarp = warp_index
    val targetSmsp = smsp_rr_ptr

    // 计算当前 Warp 的属性
    val tailMask = calcTailMask(total_threads, total_warps)
    val vgprBase = calcVgprBase(alloc_vgpr_base, currentWarp, bd.vgpr_per_thread)
    val activeMask = Mux(currentWarp === total_warps - 1.U, tailMask, "hFFFFFFFF".U(32.W))

    // 构造 WarpInitBundle
    val warpBundle = Wire(new WarpInitBundle())
    warpBundle.pc            := bd.kernel_pc(63, 4)  // 压缩 PC
    warpBundle.warp_id_in_sm := currentWarp(4, 0)
    warpBundle.vgpr_base     := vgprBase(11, 0)
    warpBundle.ugpr_base     := 0.U  // uGPR 暂未实现
    warpBundle.smem_base     := alloc_smem_base
    warpBundle.barrier_id    := alloc_bar_id
    warpBundle.active_mask   := activeMask
    warpBundle.block_id_x    := bd.block_id_x
    warpBundle.block_id_y    := bd.block_id_y
    warpBundle.block_id_z    := bd.block_id_z
    warpBundle.grid_dim_x    := bd.grid_dim_x
    warpBundle.grid_dim_y    := bd.grid_dim_y
    warpBundle.grid_dim_z    := bd.grid_dim_z
    warpBundle.mode_register := bd.mode_register
    warpBundle.tma_desc_base := bd.tma_desc_base

    // Decoupled 握手发送到目标 SMSP
    io.smsp(targetSmsp).init.valid := true.B
    io.smsp(targetSmsp).init.bits  := warpBundle

    when(io.smsp(targetSmsp).init.ready) {
      // 派发成功
      val lastWarp = currentWarp === total_warps - 1.U
      warp_index := currentWarp + 1.U
      smsp_rr_ptr := Mux(targetSmsp === (BSConfig.numSmsp - 1).U, 0.U, targetSmsp + 1.U)

      when(lastWarp) {
        // 所有 Warp 派发完毕
        bd_valid_reg := false.B
        state := BSState.S_WAIT_DONE
      }
    }
  }

  // ── Warp 完成信号入队 (始终有效，不限于 S_WAIT_DONE) ──
  // 对应 MAS §6 的生命周期管理
  // 需要在所有状态下捕获 warp_exit，防止在 S_DISPATCH 阶段丢失退出信号
  for (smspId <- 0 until BSConfig.numSmsp) {
    exit_fifos(smspId).io.enq.valid := io.smsp(smspId).warp_exit_valid
    exit_fifos(smspId).io.enq.bits.block_id := io.smsp(smspId).warp_exit_block_id
    exit_fifos(smspId).io.enq.bits.warp_id  := io.smsp(smspId).warp_exit_warp_id
  }

  // ── S_WAIT_DONE: 等待 Warp 完成 ──
  when(state === BSState.S_WAIT_DONE) {

    // Round-Robin 轮询处理完成 FIFO (串行化)
    // 直接索引每个 exit_fifo 的 deq，避免 VecInit 导致的初始化问题
    val currentSmsp = rr_exit_ptr

    // 使用 Mux 链选择当前 SMSP 的 FIFO 输出
    val fifoValid = Wire(Bool())
    val fifoBlockId = Wire(UInt(BSConfig.blockIdWidth.W))
    val fifoWarpId  = Wire(UInt(BSConfig.warpIdWidth.W))
    fifoValid := false.B
    fifoBlockId := 0.U
    fifoWarpId  := 0.U

    // 为每个 exit_fifo 单独设置 deq.ready
    for (smspId <- 0 until BSConfig.numSmsp) {
      when(currentSmsp === smspId.U) {
        exit_fifos(smspId).io.deq.ready := true.B
        fifoValid := exit_fifos(smspId).io.deq.valid
        fifoBlockId := exit_fifos(smspId).io.deq.bits.block_id
        fifoWarpId  := exit_fifos(smspId).io.deq.bits.warp_id
      }
    }

    when(fifoValid) {
      val abtIdx = fifoBlockId

      when(abt_table(abtIdx).valid && abt_table(abtIdx).warp_remaining > 0.U) {
        abt_table(abtIdx).warp_remaining := abt_table(abtIdx).warp_remaining - 1.U

        when(abt_table(abtIdx).warp_remaining === 1.U) {
          // 最后一个 Warp 退出 → 触发释放
          block_done_pulse := true.B
          block_done_id := abtIdx
        }
      }
    }.otherwise {
      // 轮询下一个 SMSP
      rr_exit_ptr := Mux(currentSmsp === (BSConfig.numSmsp - 1).U, 0.U, currentSmsp + 1.U)
    }

    // 当有 Block 完成时，进入 CLEANUP
    when(block_done_pulse) {
      state := BSState.S_CLEANUP
    }
  }

  // ── S_CLEANUP: 资源回收与 ACE 通知 ──
  // 对应 MAS §6.2 和 §3.3.3
  when(state === BSState.S_CLEANUP) {
    val abtIdx = block_done_id
    val entry = abt_table(abtIdx)

    // 将释放请求压入异步 FIFO
    free_fifo.io.enq.valid := true.B
    free_fifo.io.enq.bits.vgpr_chunks := entry.vgpr_chunks
    free_fifo.io.enq.bits.smem_chunks := entry.smem_chunks
    free_fifo.io.enq.bits.bar_id      := entry.bar_id
    free_fifo.io.enq.bits.bar_count   := entry.bar_count

    when(free_fifo.io.enq.ready) {
      // 清除 ABT 条目
      abt_table(abtIdx).valid := false.B
      abt_table(abtIdx).warp_remaining := 0.U

      // 清除脉冲
      block_done_pulse := false.B

      // 回到 IDLE
      state := BSState.S_IDLE
    }
  }

  // ==========================================
  // 异步释放处理 (后台运行)
  // 对应 MAS §3.3.3
  // ==========================================

  when(free_fifo.io.deq.valid) {
    val req = free_fifo.io.deq.bits

    // 释放 vGPR Chunks
    for (i <- 0 until BSConfig.vgprChunks) {
      when(req.vgpr_chunks(i)) {
        vgpr_bitmap(i) := false.B
      }
    }

    // 释放 SMem Chunks
    for (i <- 0 until BSConfig.smemChunks) {
      when(req.smem_chunks(i)) {
        smem_bitmap(i) := false.B
      }
    }

    // 释放 Barrier
    for (i <- 0 until BSConfig.barPoolSize) {
      when(i.U >= req.bar_id && i.U < req.bar_id + req.bar_count) {
        bar_pool(i) := false.B
      }
    }

    free_fifo.io.deq.ready := true.B
  }

  // ==========================================
  // 输出连接
  // ==========================================

  // ── 忙状态 ──
  io.busy := state =/= BSState.S_IDLE

  // ── 资源可用计数 ──
  io.vgpr_available := PopCount(VecInit(vgpr_bitmap.map(b => !b)))
  io.smem_available := PopCount(VecInit(smem_bitmap.map(b => !b)))

  // ── 活跃 Block 计数 ──
  io.active_block_count := PopCount(VecInit(abt_table.map(e => e.valid)))

  // ── Block Done 通知 ──
  io.block_done_valid := block_done_pulse
  io.block_done_block_id := block_done_id

  // ── 预取触发 ──
  io.preload_icache_valid := preload_icache
  io.preload_icache_addr  := preload_pc
  io.preload_kcache_valid := preload_kcache
  io.preload_kcache_addr  := preload_kptr

}
