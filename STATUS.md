# OpenGPGPU 开发状态报告

> **最后更新**: 2026-05-09
>
> 本文档全面记录 OpenGPGPU 架构模型的开发进展，涵盖已完成、进行中和未开始的模块与功能。

---

## 目录

1. [总体概览](#1-总体概览)
2. [已完成模块](#2-已完成模块)
3. [正在开发模块](#3-正在开发模块)
4. [未开始/规划中模块](#4-未开始规划中模块)
5. [仿真与测试环境](#5-仿真与测试环境)
6. [ISA 指令集实现状态](#6-isa-指令集实现状态)
7. [开发路线图](#7-开发路线图)

---

## 1. 总体概览

### 1.1 项目阶段

| 阶段 | 状态 | 说明 |
|------|------|------|
| Phase 0: 基础设施搭建 | ✅ **已完成** | 构建系统、内存模型、基础工具链 |
| Phase 1: SMSP 核心流水线 | 🔄 **进行中** | IFU → Decoder → IBuffer → Scheduler → OC → vALU → RCB |
| Phase 2: SM 级集成 | 🔄 **进行中** | Block Scheduler、SMSP 阵列、RBMU 寄存器总线 |
| Phase 3: 存储子系统 | ⏳ **规划中** | LSU、L1/ULM、TMA、WGMMA |
| Phase 4: 全芯片集成 | ⏳ **规划中** | ACE、GMMU、多 Cluster |

### 1.2 代码统计

| 目录 | 文件数 | 状态 |
|------|--------|------|
| `src/` | 50+ 个 Scala 源文件 | 核心 RTL 设计 |
| `sim/` | 10+ 个仿真文件 | 仿真顶层与 Testbench |
| `utils/` | 2 个工具文件 | 日志与 RBMU 管理器 |
| `docs/` | 4 个文档 | 架构/ISA/用户指南 |
| `vplan/` | 1 个规划文档 | 仿真日志实施计划 |

---

## 2. 已完成模块

### 2.1 内存模型 (Memory Model) — ✅ 100%

**文件位置**: [`src/memory/`](../src/memory/)

| 组件 | 文件 | 状态 | 说明 |
|------|------|------|------|
| MemoryController | [`MemoryController.scala`](../src/memory/MemoryController.scala) | ✅ 完成 | 硬件内存控制器，支持 Decoupled 握手、延迟模拟、突发传输 |
| PagedMemoryModel | [`PagedMemoryModel.scala`](../src/memory/PagedMemoryModel.scala) | ✅ 完成 | 软件后备内存模型，64 位地址空间 |
| PageAllocator | [`PageAllocator.scala`](../src/memory/PageAllocator.scala) | ✅ 完成 | 4KB 分页管理，分配/释放/追踪 |
| ScalaMemoryModelOps | [`ScalaMemoryModelOps.scala`](../src/memory/ScalaMemoryModelOps.scala) | ✅ 完成 | 内存操作接口特质 |
| Types | [`Types.scala`](../src/memory/Types.scala) | ✅ 完成 | 请求/响应 Bundle 定义 |

**测试覆盖**: 5 个测试集，7 项特性，**全部通过** ✅

### 2.2 ISA 指令定义 — ✅ 基础框架完成

**文件位置**: [`src/isa/`](../src/isa/)

| 组件 | 文件 | 状态 | 说明 |
|------|------|------|------|
| SASS 基类 | [`SASS.scala`](../src/isa/SASS.scala) | ✅ 完成 | 128-bit 指令基类，BitPat 匹配，字段提取，反汇编 |
| Registry | [`SASS.scala`](../src/isa/SASS.scala) | ✅ 完成 | 指令注册表，冲突检测 |
| EXIT | [`EXIT.scala`](../src/isa/EXIT.scala) | ✅ 完成 | Warp 退出指令 |
| FADD | [`FADD.scala`](../src/isa/FADD.scala) | ✅ 完成 | FP32 加法指令 |
| IMAD | [`IMAD.scala`](../src/isa/IMAD.scala) | ✅ 完成 | 整数乘加指令 (含 IMM 变体) |
| ISETP | [`ISETP.scala`](../src/isa/ISETP.scala) | ✅ 完成 | 整数比较置谓词指令 |
| LDC | [`LDC.scala`](../src/isa/LDC.scala) | ✅ 完成 | Constant Cache 加载 (32/64-bit) |
| LDG | [`LDG.scala`](../src/isa/LDG.scala) | ✅ 完成 | Global Memory 加载 |
| STG | [`STG.scala`](../src/isa/STG.scala) | ✅ 完成 | Global Memory 存储 |
| WBRA | [`WBRA.scala`](../src/isa/WBRA.scala) | ✅ 完成 | 无条件分支跳转 |
| WGMMA | [`WGMMA.scala`](../src/isa/WGMMA.scala) | ✅ 完成 | 张量矩阵乘加指令 |
| TMA | [`TMA.scala`](../src/isa/TMA.scala) | ✅ 完成 | 张量内存加速器指令 (LOAD/STORE) |
| MBARRIER | [`MBARRIER.scala`](../src/isa/MBARRIER.scala) | ✅ 完成 | 内存屏障指令 (INIT/WAIT) |
| V2A | [`V2A.scala`](../src/isa/V2A.scala) | ✅ 完成 | 向量转累加器指令 |
| A2S | [`A2S.scala`](../src/isa/A2S.scala) | ✅ 完成 | 累加器转共享内存指令 (含 F32→F16+ReLU) |

### 2.3 工具与基础设施 — ✅ 完成

| 组件 | 文件 | 状态 | 说明 |
|------|------|------|------|
| Logger | [`utils/Logger.scala`](../utils/Logger.scala) | ✅ 完成 | 多级别日志系统，支持控制台/文件输出 |
| RBMUManager | [`utils/RBMUManager.scala`](../utils/RBMUManager.scala) | ✅ 完成 | 寄存器管理器，去中心化可编址寄存器开发 |
| 构建系统 | [`build.sbt`](../build.sbt) | ✅ 完成 | SBT 构建，Chisel 6.2.0 |
| Makefile | [`Makefile`](../Makefile) | ✅ 完成 | 编译/测试/仿真/文档一键命令 |

---

## 3. 正在开发模块

### 3.1 SMSP 核心流水线 — 🔄 ~80%

**文件位置**: [`src/smsp/`](../src/smsp/), [`sim/smsp/`](../sim/smsp/)

SMSP (Sub-Core) 是 OpenGPGPU 的核心执行单元，封装了完整的指令流水线。

#### 3.1.1 IFU (取指单元) — ✅ 完成

**文件位置**: [`src/ifu/`](../src/ifu/)

| 子模块 | 文件 | 状态 | 说明 |
|--------|------|------|------|
| PST | [`PST.scala`](../src/ifu/PST.scala) | ✅ 完成 | Program Status Table，8 Warp 状态跟踪 |
| Arbiter | [`Arbiter.scala`](../src/ifu/Arbiter.scala) | ✅ 完成 | Round-Robin 仲裁器，信用驱动流控 |
| Controller | [`Controller.scala`](../src/ifu/Controller.scala) | ✅ 完成 | 取指控制器，L0 I-Cache 接口 |
| IFU Top | [`IFU.scala`](../src/ifu/IFU.scala) | ✅ 完成 | 顶层集成 |
| Types | [`Types.scala`](../src/ifu/Types.scala) | ✅ 完成 | PSTEntry、WarpState、IFUConfig |

#### 3.1.2 L0 I-Cache (一级指令缓存) — ✅ 完成

**文件位置**: [`src/l0icache/`](../src/l0icache/)

| 组件 | 文件 | 状态 | 说明 |
|------|------|------|------|
| L0ICache | [`L0ICache.scala`](../src/l0icache/L0ICache.scala) | ✅ 完成 | 4KB 4路组相联，MSHR，预取支持 |
| Types | [`Types.scala`](../src/l0icache/Types.scala) | ✅ 完成 | 配置参数与接口 Bundle |

#### 3.1.3 Decoder (译码器) — ✅ 完成

**文件位置**: [`src/decoder/`](../src/decoder/)

| 组件 | 文件 | 状态 | 说明 |
|------|------|------|------|
| Decoder | [`Decoder.scala`](../src/decoder/Decoder.scala) | ✅ 完成 | 指令译码，MicroOp 生成，K-Sniffer 探针 |
| Types | [`Types.scala`](../src/decoder/Types.scala) | ✅ 完成 | MicroOp、ConstantProbeReq、BranchRedirect |

#### 3.1.4 I-Buffer (指令缓冲) — ✅ 完成

**文件位置**: [`src/ibuffer/`](../src/ibuffer/)

| 组件 | 文件 | 状态 | 说明 |
|------|------|------|------|
| IBuffer | [`IBuffer.scala`](../src/ibuffer/IBuffer.scala) | ✅ 完成 | 8 通道 × 4 深度 Queue，信用反馈，Flush |
| Types | [`Types.scala`](../src/ibuffer/Types.scala) | ✅ 完成 | 接口 Bundle 定义 |

#### 3.1.5 Warp Scheduler (Warp 调度器) — ✅ 完成

**文件位置**: [`src/scheduler/`](../src/scheduler/)

| 子模块 | 文件 | 状态 | 说明 |
|--------|------|------|------|
| WarpScheduler | [`WarpScheduler.scala`](../src/scheduler/WarpScheduler.scala) | ✅ 完成 | WST 管理，GTO 调度，K-Cache 等待，EXIT 处理 |
| SchedulerLogic | [`SchedulerLogic.scala`](../src/scheduler/SchedulerLogic.scala) | ✅ 完成 | GTO 仲裁器纯逻辑 (TMA > WGMMA > ALU 优先级) |
| Scoreboard | [`Scoreboard.scala`](../src/scheduler/Scoreboard.scala) | ✅ 完成 | 硬件 Scoreboard，TID 分配/释放 |
| ScoreboardLogic | [`ScoreboardLogic.scala`](../src/scheduler/ScoreboardLogic.scala) | ✅ 完成 | Scoreboard 纯逻辑模型 |
| Types | [`Types.scala`](../src/scheduler/Types.scala) | ✅ 完成 | WarpContext、SlotEntry、InstType |

#### 3.1.6 Operand Collector (操作数收集器) — ✅ 完成

**文件位置**: [`src/collector/`](../src/collector/)

| 子模块 | 文件 | 状态 | 说明 |
|--------|------|------|------|
| OperandCollector | [`OperandCollector.scala`](../src/collector/OperandCollector.scala) | ✅ 完成 | 8 CU 分配，Bank 仲裁，Issue 调度 |
| CollectorUnit | [`CollectorUnit.scala`](../src/collector/CollectorUnit.scala) | ✅ 完成 | CU 状态机 (Free→Collect→Ready) |
| BankArbiter | [`BankArbiter.scala`](../src/collector/BankArbiter.scala) | ✅ 完成 | 4 Bank 固定优先级仲裁，流水线响应 |
| Types | [`Types.scala`](../src/collector/Types.scala) | ✅ 完成 | OperandBundle、BankReadReq、CollectorConfig |

#### 3.1.7 寄存器文件 — ✅ 完成

**文件位置**: [`src/register/`](../src/register/)

| 子模块 | 文件 | 状态 | 说明 |
|--------|------|------|------|
| vGPR | [`vGPR.scala`](../src/register/vGPR.scala) | ✅ 完成 | 向量寄存器，4 Bank 1R1W SyncReadMem |
| pGPR | [`pGPR.scala`](../src/register/pGPR.scala) | ✅ 完成 | 谓词寄存器，P7 硬连线全 1 |
| uGPR | [`uGPR.scala`](../src/register/uGPR.scala) | ✅ 完成 | 标量寄存器，1R1W SyncReadMem |
| Types | [`Types.scala`](../src/register/Types.scala) | ✅ 完成 | RegisterFileConfig |

#### 3.1.8 vALU (向量 ALU) — ✅ 完成

**文件位置**: [`src/valu/`](../src/valu/)

| 子模块 | 文件 | 状态 | 说明 |
|--------|------|------|------|
| vALU | [`vALU.scala`](../src/valu/vALU.scala) | ✅ 完成 | 4 级流水线，8 PE，4 Beat 分时 |
| PEArray | [`PEArray.scala`](../src/valu/PEArray.scala) | ✅ 完成 | 8 PE 计算阵列 (ADD/SUB/AND/OR/XOR) |
| Types | [`Types.scala`](../src/valu/Types.scala) | ✅ 完成 | ResultPacket、vALUOpcode |

#### 3.1.9 RCB (结果提交缓冲) — ✅ 完成

**文件位置**: [`src/RCB/`](../src/RCB/)

| 子模块 | 文件 | 状态 | 说明 |
|--------|------|------|------|
| RCB | [`RCB.scala`](../src/RCB/RCB.scala) | ✅ 完成 | 12 Entry 池，Bank 写仲裁，Free List |
| RCBTypes | [`RCBTypes.scala`](../src/RCB/RCBTypes.scala) | ✅ 完成 | RCBConfig、ResultPacket、BankWriteReq |

#### 3.1.10 SMSP Top 集成 — 🔄 ~90%

**文件位置**: [`src/smsp/SMSP.scala`](../src/smsp/SMSP.scala)

| 功能 | 状态 | 说明 |
|------|------|------|
| 子模块例化 | ✅ 完成 | IFU、Decoder、IBuffer、WarpScheduler、Scoreboard、OC、vGPR/pGPR/uGPR、vALU、RCB、L0ICache、L0KCache |
| 模块间连线 | ✅ 完成 | 各子模块 IO 连接 |
| SMInterface | ✅ 完成 | SM 级接口 (Warp init/exit、Cache fill、LSU/MMA/mBarrier 预留) |
| 仿真顶层 | 🔄 进行中 | SMSPTop 已实现，Testbench 待完善 |

### 3.2 SM 级集成 — 🔄 ~70%

**文件位置**: [`src/sm/`](../src/sm/), [`sim/sm/`](../sim/sm/)

| 组件 | 文件 | 状态 | 说明 |
|------|------|------|------|
| SMConfig | [`SMConfig.scala`](../src/sm/SMConfig.scala) | ✅ 完成 | 可配置参数 (4 SMSP、32 Warp、ULM 等) |
| SM Top | [`SM.scala`](../src/sm/SM.scala) | ✅ 完成 | SM 顶层，Block Scheduler + 4 SMSP + RBMU + MockBackend |
| SMTop (仿真) | [`SMTop.scala`](../sim/sm/SMTop.scala) | ✅ 完成 | 仿真顶层，暴露探针信号 |
| MockBackend | [`MockBackend.scala`](../sim/sm/Mock/MockBackend.scala) | ✅ 完成 | LSU/MMA/mBarrier Mock |
| SM Testbench | [`SMTestbench.scala`](../sim/sm/TestBench/SMTestbench.scala) | 🔄 进行中 | 待完善测试用例 |
| SM Driver | [`SMDriver.scala`](../sim/sm/TestBench/SMDriver.scala) | 🔄 进行中 | 待完善驱动逻辑 |
| SM Monitor | [`SMMonitor.scala`](../sim/sm/TestBench/SMMonitor.scala) | 🔄 进行中 | 待完善监视逻辑 |
| SM Test | [`SMTest.scala`](../sim/sm/TestBench/SMTest.scala) | 🔄 进行中 | 待完善测试用例 |

### 3.3 Block Scheduler (块调度器) — ✅ 完成

**文件位置**: [`src/blksch/`](../src/blksch/)

| 组件 | 文件 | 状态 | 说明 |
|------|------|------|------|
| BlockScheduler | [`BlockScheduler.scala`](../src/blksch/BlockScheduler.scala) | ✅ 完成 | BD 接收、资源分配 (vGPR/SMem/Barrier)、Warp 裂变分发、ABT 管理、L1 预取 |
| Types | [`Types.scala`](../src/blksch/Types.scala) | ✅ 完成 | BlockDescriptor、WarpInitBundle、ABTEntry、FreeRequest |

### 3.4 RBMU 寄存器总线 — ✅ 完成

**文件位置**: [`src/rbmu/`](../src/rbmu/)

| 组件 | 文件 | 状态 | 说明 |
|------|------|------|------|
| RBMUBus | [`RBMUBus.scala`](../src/rbmu/RBMUBus.scala) | ✅ 完成 | 总线协议定义 (24-bit 地址，4-bit Target_ID) |
| RBMURingStop | [`RBMURingStop.scala`](../src/rbmu/RBMURingStop.scala) | ✅ 完成 | Ring Stop 模板，请求转发/响应路由 |
| SMGlobalRegisters | [`SMGlobalRegisters.scala`](../src/rbmu/SMGlobalRegisters.scala) | ✅ 完成 | SM 全局寄存器 (SM_ID、IFU 控制、Kernel Launch) |
| SMSPRegisters | [`SMSPRegisters.scala`](../src/rbmu/SMSPRegisters.scala) | ✅ 完成 | SMSP 寄存器 (状态、PST 表、缓存策略、GPR 基址) |
| LSURegisters | [`LSURegisters.scala`](../src/rbmu/LSURegisters.scala) | ✅ 完成 | LSU 寄存器 (地址区间、PRT、性能计数器) |
| GMMURegisters | [`GMMURegisters.scala`](../src/rbmu/GMMURegisters.scala) | ✅ 完成 | GMMU 寄存器 (VMID-PASID 映射、页表根指针) |
| PMRegisters | [`PMRegisters.scala`](../src/rbmu/PMRegisters.scala) | ✅ 完成 | 性能计数器 (MMA/TMA、vALU) |

### 3.5 L0 K-Cache (一级常量缓存) — ✅ 完成

**文件位置**: [`src/l0kcache/`](../src/l0kcache/)

| 组件 | 文件 | 状态 | 说明 |
|------|------|------|------|
| L0KCache | [`L0KCache.scala`](../src/l0kcache/L0KCache.scala) | ✅ 完成 | 2KB 2路组相联，静态/动态探针，MSHR，唤醒 |
| Types | [`Types.scala`](../src/l0kcache/Types.scala) | ✅ 完成 | 配置参数与接口 Bundle |

### 3.6 仿真日志系统 — 🔄 ~80%

**文件位置**: [`sim/smsp/`](../sim/smsp/), [`sim/sm/`](../sim/sm/)

| 组件 | 文件 | 状态 | 说明 |
|------|------|------|------|
| SMSPConfig | [`SMSPConfig.scala`](../sim/smsp/SMSPConfig.scala) | ✅ 完成 | 仿真配置 (日志开关、输出目标、级别) |
| SMSPTop | [`SMSPTop.scala`](../sim/smsp/SMSPTop.scala) | ✅ 完成 | 仿真顶层，暴露所有子模块探针 |
| SMSPTestbench | [`SMSPTestbench.scala`](../sim/smsp/TestBench/SMSPTestbench.scala) | 🔄 进行中 | 周期级日志打印，待完善 |
| Driver | [`Driver.scala`](../sim/smsp/TestBench/Driver.scala) | 🔄 进行中 | 待完善 |
| Monitor | [`Monitor.scala`](../sim/smsp/TestBench/Monitor.scala) | 🔄 进行中 | 待完善 |
| Test | [`Test.scala`](../sim/smsp/TestBench/Test.scala) | 🔄 进行中 | 待完善 |

---

## 4. 未开始/规划中模块

### 4.1 LSU (加载/存储单元) — ⏳ 未开始

| 功能 | 优先级 | 说明 |
|------|--------|------|
| LSU Hub 仲裁器 | P1 | 4 SMSP 共享 LSU 的请求仲裁 |
| 地址转换 | P1 | 虚拟地址到物理地址转换 |
| 加载/存储流水线 | P1 | 支持 LDG/STG 指令 |
| 原子操作 | P2 | 全局内存原子操作 |
| 常量加载 (LDC) | P1 | Constant Cache 读取通路 |

**当前状态**: 接口已在 [`SMInterface`](../src/smsp/SMSP.scala:67) 中预留，[`MockBackend`](../sim/sm/Mock/MockBackend.scala) 提供 Mock 实现。

### 4.2 TMA (张量内存加速器) — ⏳ 未开始

| 功能 | 优先级 | 说明 |
|------|--------|------|
| TMA Load | P1 | Global → Shared Memory 异步搬运 |
| TMA Store | P1 | Shared → Global Memory 异步搬运 |
| 描述符表管理 | P1 | TMA 描述符解析 |
| MBARRIER 同步 | P1 | 异步事务完成通知 |

**当前状态**: ISA 指令定义已完成 ([`TMA.scala`](../src/isa/TMA.scala))，硬件实现未开始。

### 4.3 WGMMA (张量核心) — ⏳ 未开始

| 功能 | 优先级 | 说明 |
|------|--------|------|
| 3D MAC 阵列 | P1 | 16×16×16 张量运算 |
| Shared Memory 直读 | P1 | 从 Smem 读取输入 |
| aGPR 累加器 | P1 | 张量累加器寄存器 |
| Epilogue 回写 | P2 | A2S 指令支持 |

**当前状态**: ISA 指令定义已完成 ([`WGMMA.scala`](../src/isa/WGMMA.scala), [`A2S.scala`](../src/isa/A2S.scala), [`V2A.scala`](../src/isa/V2A.scala))，硬件实现未开始。

### 4.4 ULM / L1 Data Cache — ⏳ 未开始

| 功能 | 优先级 | 说明 |
|------|--------|------|
| ULM (统一局部存储) | P1 | Shared Memory + L1 Data Cache 统一管理 |
| L1 Data Cache | P2 | 数据缓存 |
| Bank Conflict 处理 | P2 | 多 Bank 访问冲突 |

### 4.5 ROC (统一只读缓存) — ⏳ 未开始

| 功能 | 优先级 | 说明 |
|------|--------|------|
| L1 I-Cache 聚合 | P1 | 4 SMSP 的 I-Cache Miss 汇聚 |
| L1 K-Cache 聚合 | P1 | 4 SMSP 的 K-Cache Miss 汇聚 |
| Fill 广播 | P1 | 缓存行填充广播到所有 SMSP |

**当前状态**: 接口已在 [`SMIO`](../src/sm/SM.scala:38) 中预留。

### 4.6 ACE (异步计算引擎) — ⏳ 未开始

| 功能 | 优先级 | 说明 |
|------|--------|------|
| BD 分发 | P1 | Block Descriptor 下发到 SM |
| Block Done 收集 | P1 | 完成 Block 的回收 |
| 跨 SM 调度 | P2 | 多 SM 负载均衡 |

**当前状态**: BD 接口已在 [`BlockSchedulerIO`](../src/blksch/BlockScheduler.scala:11) 中定义。

### 4.7 GMMU (全局内存管理单元) — ⏳ 未开始

| 功能 | 优先级 | 说明 |
|------|--------|------|
| 页表遍历 | P1 | 四级页表硬件遍历 |
| TLB | P1 | 地址转换缓存 |
| VMID 管理 | P2 | 多进程虚拟地址空间 |

**当前状态**: 寄存器接口已在 [`GMMURegisters`](../src/rbmu/GMMURegisters.scala) 中定义。

### 4.8 测试框架完善 — ⏳ 未开始

| 测试类型 | 优先级 | 说明 |
|---------|--------|------|
| SMSP 单元测试 | P1 | 各子模块独立测试 |
| SM 集成测试 | P1 | SM 级流水线测试 |
| ISA 指令测试 | P1 | 每条指令的功能验证 |
| 端到端测试 | P2 | 完整 Kernel 执行测试 |

---

## 5. 仿真与测试环境

### 5.1 当前测试覆盖

| 测试集 | 文件 | 状态 |
|--------|------|------|
| 内存模型测试 | (tests/ 目录) | ✅ **全部通过** |
| SMSP 仿真 | [`sim/smsp/TestBench/`](../sim/smsp/TestBench/) | 🔄 开发中 |
| SM 仿真 | [`sim/sm/TestBench/`](../sim/sm/TestBench/) | 🔄 开发中 |

### 5.2 构建与运行

```bash
make compile    # 编译项目
make test       # 运行所有测试
make test-memory # 仅运行内存测试
make sim        # 运行仿真
make doc        # 生成文档
```

---

## 6. ISA 指令集实现状态

| 指令 | 编码定义 | Decoder 支持 | 硬件执行 | 说明 |
|------|----------|-------------|---------|------|
| EXIT | ✅ | ✅ | ⏳ | Warp 退出，需 WarpScheduler 处理 |
| FADD | ✅ | ✅ | ⏳ | FP32 加法，vALU 需扩展 FPU |
| IMAD | ✅ | ✅ | ⏳ | 整数乘加，vALU 需扩展 |
| IMAD.IMM | ✅ | ✅ | ⏳ | 立即数变体 |
| ISETP | ✅ | ✅ | ⏳ | 整数比较置谓词 |
| LDC.32/64 | ✅ | ✅ | ⏳ | Constant Cache 加载 |
| LDG | ✅ | ✅ | ⏳ | Global Load，需 LSU |
| STG | ✅ | ✅ | ⏳ | Global Store，需 LSU |
| WBRA | ✅ | ✅ | ⏳ | 无条件分支，需 BRU |
| WGMMA | ✅ | ✅ | ⏳ | 张量运算，需 MMA 硬件 |
| TMA.LOAD | ✅ | ✅ | ⏳ | 异步 DMA 加载 |
| TMA.STORE | ✅ | ✅ | ⏳ | 异步 DMA 存储 |
| MBARRIER.INIT | ✅ | ✅ | ⏳ | 内存屏障初始化 |
| MBARRIER.WAIT | ✅ | ✅ | ⏳ | 内存屏障等待 |
| V2A | ✅ | ✅ | ⏳ | 向量转累加器 |
| A2S | ✅ | ✅ | ⏳ | 累加器转共享内存 |

**图例**: ✅ = 已完成, 🔄 = 进行中, ⏳ = 未开始

---

## 7. 开发路线图

### Phase 1: SMSP 核心流水线 (当前阶段)

```
目标: SMSP 单核完整指令流水线验证
状态: 🔄 ~80% 完成
剩余工作:
  - [ ] SMSP Testbench 完善 (Driver/Monitor/Test)
  - [ ] SM Testbench 完善
  - [ ] 流水线级联仿真验证
  - [ ] Scoreboard 与 WarpScheduler 联调
  - [ ] vALU 扩展 FPU 支持 FADD
```

### Phase 2: SM 级集成

```
目标: 4 SMSP 阵列 + Block Scheduler + RBMU 集成验证
状态: 🔄 ~70% 完成
剩余工作:
  - [ ] SM 级仿真测试
  - [ ] Block Scheduler 与 SMSP 联调
  - [ ] RBMU 寄存器读写验证
  - [ ] MockBackend 替换为真实 LSU
```

- [x] Phase 3: 存储子系统

```
目标: LSU + ULM + TMA + WGMMA 实现
状态: ⏳ 未开始
计划:
  - [ ] LSU Hub 实现
  - [ ] ULM (Shared Memory) 实现
  - [x] TMA 异步搬运引擎 (Plan Archiving Complete)
  - [x] WGMMA 张量核心 (Plan Archiving Complete)
  - [x] mBarrier 异步屏障 (Plan Archiving Complete)
  - [ ] ROC 统一只读缓存
```

### Phase 4: 全芯片集成

```
目标: ACE + GMMU + 多 Cluster
状态: ⏳ 未开始
计划:
  - [ ] ACE 异步计算引擎
  - [ ] GMMU 全局内存管理
  - [ ] 多 Cluster 互联
  - [ ] 端到端 Kernel 执行
```

---

## 附录

### A. 模块依赖关系

```
SM (Streaming Multiprocessor)
├── Block Scheduler (blksch)
│   ├── BlockDescriptor 接收 (ACE → BS)
│   ├── 资源分配 (vGPR/SMem/Barrier)
│   └── Warp 分发 → SMSP × 4
├── SMSP × 4 (Sub-Core)
│   ├── IFU (取指)
│   │   ├── PST (Program Status Table)
│   │   ├── Arbiter (Round-Robin)
│   │   └── Controller (Cache 接口)
│   ├── L0 I-Cache (4KB 4-way)
│   ├── Decoder (译码)
│   ├── I-Buffer (8×4 Queue)
│   ├── L0 K-Cache (2KB 2-way)
│   ├── Warp Scheduler
│   │   ├── WST (Warp State Table)
│   │   ├── Scoreboard (6 Slot/Warp)
│   │   └── GTO Arbiter
│   ├── Operand Collector
│   │   ├── CollectorUnit × 8
│   │   └── Bank Arbiter (4 Bank)
│   ├── Register File
│   │   ├── vGPR (4 Bank 1R1W)
│   │   ├── pGPR (Predicate)
│   │   └── uGPR (Scalar)
│   ├── vALU (8 PE, 4-stage)
│   └── RCB (12 Entry)
├── RBMU (Ring Bus)
│   ├── Ring Stop × N
│   ├── SM Global Registers
│   ├── SMSP Registers
│   ├── LSU Registers
│   ├── GMMU Registers
│   └── PM Registers
├── LSU Hub (Phase 3)
├── TMA (Phase 3)
├── WGMMA (Phase 3)
└── ROC (Phase 3)
```