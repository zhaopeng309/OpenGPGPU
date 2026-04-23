# OpenGPGPU MVP ISA：128-bit 异步流指令集架构规范综述

## 1. 架构定位与设计哲学
OpenGPGPU MVP ISA 是一套面向现代 AI 算力需求定义的 128-bit 定长异步流指令集。其核心设计哲学为**“计算与访存物理级解耦”**与**“算子宏指令化”**。通过将调度智能上移至编译器，微架构得以大幅简化指令发射（Issue）与计分板（Scoreboard）的硬件复杂度，从而将晶体管资源集中于张量计算阵列。

## 2. 指令编码与显式调度
架构全面采用 128-bit 定长编码，物理上划分为高 64 位（控制与元数据层）和低 64 位（执行与操作数层）：
- **显式调度域（Scheduling Domain）**：指令高 24 位显式下发 `Stall_Count`（停等周期）、`Read/Write Barrier_ID`（寄存器级屏障）以及 `Yield`（出让权限）。这种设计消除了硬件对复杂动态调度算法的依赖。
- **双重屏障体系**：架构区分了细粒度的 **Scoreboard Barrier**（用于寄存器依赖隐藏）与粗粒度的 **Memory Barrier (MBARRIER)**（用于内存级异步事务同步）。

## 3. 计算与访存的深度解耦
针对现代算力墙，ISA 实现了数据搬运与计算逻辑的物理隔离：
- **TMA（张量内存加速器）**：通过异步 DMA 引擎实现 Global Memory 与 Shared Memory 间的数据直连。TMA 采用指令发射即遗忘（Fire-and-Forget）模式，通过 MBARRIER 句柄在后台完成事务确认。
- **异步流控**：计算单元（MMA）无需占用通用寄存器（vGPR）带宽进行访存中转，数据流经由 `TMA -> Smem -> MMA` 的专用通路，极大降低了访存延迟对计算流水线的干扰。

## 4. 算子宏指令化与张量通路
为了提升能效比并缓解寄存器压力，ISA 引入了面向领域的宏指令集：
- **WGMMA（Warp 组矩阵乘加）**：单条指令驱动 3D MAC 阵列，实现 $16 \times 16 \times 16$ 等规模的张量运算。数据从 Shared Memory 直读，结果存入异构的 aGPR（张量累加器）。
- **Epilogue 融合回写**：通过 `A2S` 宏指令实现计算结果从 aGPR 回写至 Shared Memory。该过程支持硬件级交织（Swizzle）以规避存储库冲突（Bank Conflict），并集成 FP32 转 FP16、ReLU 激活等后处理逻辑。

## 5. 操作数路由的绝对正交性
微架构通过 **8-bit OT（操作数类型）位图掩码**实现了高度正交的物理路由逻辑：
- **通用路由协议**：每个操作数槽位均有独立的 2-bit 标签，用于在 vGPR、uGPR、pGPR 与立即数（Immediate）之间进行零歧义切换。
- **地址空间复用**：利用 8-bit 寄存器索引的最高位（MSB）作为硬件别名，将索引 128-255 物理重定向至 aGPR 阵列。这一设计在保持 OT 正交性的同时，无需扩展指令域即可支持异构寄存器寻址。

## 6. 软件管理的 SIMT 发散模型
OpenGPGPU 废弃了传统的硬件收敛栈（Reconvergence Stack），转向纯软件管理的 SIMT 发散策略：
- **掩蔽执行（Masking）**：发散分支的本质被定义为执行掩码（`SR_EXEC`）的改变而非 PC 的物理分叉。
- **软件栈维护**：编译器利用 uGPR 作为软件栈，显式保存/恢复发散前后的 `SR_EXEC`。
- **WBRA 宏观跳转**：IFU（取指单元）仅需根据 `SR_EXEC` 是否全零执行 `WBRA` 跳转，极大地简化了前端取指逻辑，消除了硬件分支预测器的开销。

## 7. 硬件引导 ABI 契约
架构规定了严格的硬件启动初始化（Bootstrapping）规范。在 `PC=0` 时，Warp Allocator 必须确保：
- `vGPR[R0]` 注入 `Lane_ID`；
- `uGPR[R0:R1]` 注入 `Block_ID` 与坐标参数；
- `uGPR[R4:R5]` 注入内核参数区根指针（Kernel Argument Root Pointer）。

内核程序随后通过 `LDC` 指令从 Constant Cache 自主引导后续参数加载，确保了软硬件边界的透明与解耦。

## 8. 架构实战：指令示例

为了直观展示上述架构哲学的威力，以下提供两个极简内核示例：

### 8.1 Demo 1: Vector Add (C = A + B)

目标：对两个长度为 `N` 的 FP32 数组进行逐元素相加。
微架构看点：地址计算 (`IMAD`)、显式屏障同步 (`[WBar]` / `[RBar]`)、vALU 四级流水线。

#### 8.1.1 内核汇编代码 (SASS 风格)

```asm
// --------------------------------------------------------------------------
// Kernel: VectorAdd (引导加载阶段 Bootstrapping)
// 硬件初始隐式 ABI: 
//   vGPR[R0]      : 预置 Lane_ID (0~31)
//   uGPR[R0]      : 预置 Block_ID
//   uGPR[R4_R5]   : 预置 Kernel Argument Root Pointer (参数区根指针)
//
// 操作数类型 (OT) 8-bit 路由规范 (每操作数2-bit)：00=vGPR, 01=uGPR, 10=pGPR, 11=Imm/Const
// --------------------------------------------------------------------------

// [步骤 0: 从 Constant Cache 拉取内核参数 (Bootstrapping)]
// 假设 CPU 已经将参数打包好，按照 64-bit 对齐排列。极速拉取并全 Warp 广播。
LDC.64 R_BaseA,     [R4_R5 + 0x00]   // 抓取输入数组 A 的显存基址
LDC.64 R_BaseB,     [R4_R5 + 0x08]   // 抓取输入数组 B 的显存基址
LDC.64 R_BaseC,     [R4_R5 + 0x10]   // 抓取输出数组 C 的显存基址
LDC.32 R_BlockSize, [R4_R5 + 0x18]   // 抓取 Block Size 大小
LDC.32 R_N,         [R4_R5 + 0x1C]   // 抓取数组总长度 N

// [步骤 1: 计算全局唯一 Thread_ID]
// 算法: Global_TID = Block_ID * BlockSize + Lane_ID
// 这里完美展示标量-向量混合计算：
// OT = 0b00_01_01_00 (Rd=vGPR, Rs1=uGPR, Rs2=uGPR, Rs3=vGPR)
IMAD.U32 R_TID, R_BlockID, R_BlockSize, R_LaneID 

// [步骤 2: 边界检查]
// 标量-向量比较，OT = 0b10_00_01_00 (Rd=pGPR, Rs1=vGPR, Rs2=uGPR)
ISETP.GE.U32 P0, R_TID, R_N          // 如果 Global_TID >= N，设置谓词 P0 = True
@P0 WBRA.U End_Kernel                 // [越界 Warp 跳过] 如果全员越界，直接宏观跳跃结束

// [步骤 3: 计算每个线程的全局内存物理地址]
// 算法: Addr = Base + TID * 4 (FP32占4字节)
// 立即数 4 通过 Extended Modifiers 提供。
// OT = 0b00_00_11_01 (Rd=vGPR, Rs1=vGPR, Rs2=Imm, Rs3=uGPR)
// vALU 行为: 3条指令连续进入 8-PE 的 EX1->EX4 流水线，无气泡
IMAD.U32.IMM R_AddrA, R_TID, 4, R_BaseA  // R_AddrA = R_TID * 4 + R_BaseA
IMAD.U32.IMM R_AddrB, R_TID, 4, R_BaseB  // R_AddrB = R_TID * 4 + R_BaseB
IMAD.U32.IMM R_AddrC, R_TID, 4, R_BaseC  // R_AddrC = R_TID * 4 + R_BaseC

// [步骤 4: 发起全局内存加载]
[WBar: 0] LDG.F32 R_A, [R_AddrA]     // 异步加载 A[i]，绑定写回屏障 0
[WBar: 1] LDG.F32 R_B, [R_AddrB]     // 异步加载 B[i]，绑定写回屏障 1

// [步骤 5: 核心计算 (带有读屏障)]
[RBar: 0,1] FADD.F32 R_C, R_A, R_B   // R_C = R_A + R_B，等待依赖就绪

// [步骤 6: 结果写回全局内存]
STG.F32 [R_AddrC], R_C               // C[i] = R_C

// 结束
End_Kernel:
EXIT
```

#### 8.1.2 硬件微架构执行映射 (Hardware Execution Flow)

- **Operand Collector (OC) 表现**：当执行 `FADD.F32 R_C, R_A, R_B` 时，OC 向 vGPR 的 4 个 Bank 发起读取。因为是纯向量操作，`R_A` 和 `R_B` 的 32 个线程数据被 OC 完美搜集，打包成 1024-bit 的 Bundle 发给 vALU。
- **vALU 流水线表现**：vALU 的 `Warp Sequencer` 将 1024-bit 拆分为 4 拍，喂给底层的 8 个物理 FP32 加法器。4 个周期后，数据在 EX4 级重组，送入 RCB (Result Commit Buffer)。

---

### 8.2 Demo 2: GEMM 16x16x16 (纯异步张量流终极版)

目标：计算 $16 	imes 16$ 大小的矩阵 $C = A 	imes B$（假设数据类型为 FP16，累加结果为 FP32，写回转换为 FP16）。
微架构看点：完全剥离 vGPR 的张量数据流 (`TMA -> Smem -> MMA -> aGPR -> Smem -> TMA`)，硬件内嵌后处理 (Epilogue)。

#### 8.2.1 内核汇编代码 (SASS 风格)

```asm
// --------------------------------------------------------------------------
// Kernel: TileGEMM_16x16x16 (纯异步张量流终极版)
// --------------------------------------------------------------------------

// [步骤 1: 初始化与搬运]
V2A A0, RZ                            // aGPR 清零
MBARRIER.INIT R_Bar_Load, 512         // 初始化加载屏障，获取句柄放入 R_Bar_Load

// 显式三操作数：数据去哪 (Smem)，从哪来 (TMA)，完事了通知谁 (R_Bar_Load)
TMA.LOAD R_Smem_Desc_A, R_TMA_Desc_A, R_Bar_Load
TMA.LOAD R_Smem_Desc_B, R_TMA_Desc_B, R_Bar_Load

MBARRIER.WAIT R_Bar_Load              // 挂起，等待 TMA 硬件向内存级屏障汇报落盘完毕

// [步骤 2: 核心 MMA 计算]
// 从 Smem 直读，算完留在 aGPR
WGMMA.M16N16K16.F32.F16.F16 A0, R_Smem_Desc_A, R_Smem_Desc_B

// [步骤 3: 硬件内嵌后处理与直写 Smem]
// 初始化写回屏障，准备将 256 bytes (FP16) 的写回任务通知给下一个环节
MBARRIER.INIT R_Bar_Store, 256        
// 下采样为 FP16，应用 ReLU，扔回 Smem，完工后通知 R_Bar_Store
A2S.F32TO16.RELU R_Smem_Desc_C, A0, R_Bar_Store  

// [步骤 4: 异步写回全局内存]
// 等待 A2S 落盘后，触发硬件 DMA 搬出数据
MBARRIER.WAIT R_Bar_Store
TMA.STORE R_TMA_Desc_C, R_Smem_Desc_C

// 结束 (vGPR 全程除了提供描述符地址外，完全闲置！)
EXIT
```

#### 8.2.2 硬件微架构执行映射 (Hardware Execution Flow)

- **TMA & MBarrier 表现**：数据的加载 (`TMA.LOAD`) 和存储 (`TMA.STORE`) 被完美异步化。计算核心只需配置好描述符，然后挂起等待。总线级的 Burst 传输将 HBM 带宽利用率推向极致。
- **MMA & Epilogue 引擎表现**：`WGMMA` 在几个周期内完成海量乘加。紧随其后的 `A2S` 不仅完成了极速的 SRAM 写回，还顺路通过硬件飞线 (Epilogue) 完成了 FP32 -> FP16 降级和 ReLU 激活。
- **vGPR 的终极解脱**：在这个算子中，vALU 和 vGPR 彻底沦为“计步器”和“指针簿”。真正笨重的张量数据从始至终都在宽总线上狂飙，没有一个字节流经基础寄存器。这就是 OpenGPGPU 应对大模型时代的答案。

---
