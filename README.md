# OpenGPGPU

[![Scala](https://img.shields.io/badge/scala-2.13.12-red.svg)](https://scala-lang.org)
[![Chisel](https://img.shields.io/badge/chisel-6.2.0-blue.svg)](https://www.chisel-lang.org)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](https://opensource.org/licenses/BSD-3-Clause)

**OpenGPGPU** 是一个完整的开源 GPGPU 项目，旨在提供详细的、基于 Chisel 语言的 GPGPU 内核时钟精确架构建模。

---

## ⚠️ 当前开发状态

> **更新：** SMSP（Streaming Multiprocessor Subsystem）内部流水线已基本贯通。IFU（取指）→ Decoder（译码）→ IBuffer（指令缓冲）→ WarpScheduler（线程束调度）→ OperandCollector（操作数收集）→ vALU（向量运算）→ RCB（结果提交缓冲）→ vGPR（向量寄存器堆）的**完整数据通路已连通**，顶层 [`SMSPTop`](sim/smsp/SMSPTop.scala) 已集成所有核心模块并支持系统级仿真。

当前已实现以下关键模块的快速建模与集成测试：

| 模块 | 功能 | 状态 |
|------|------|------|
| **Memory Model** | 分页内存管理、延迟/突发模拟 | ✅ 已完成 |
| **L0ICache** | 指令缓存 (Level 0) | ✅ 已完成 |
| **L0KCache** | 常量缓存 (Level 0) | ✅ 已完成 |
| **IFU** | 指令取指单元 | ✅ 已完成 |
| **Decoder** | 指令译码（ISA Registry 驱动） | ✅ 已完成 |
| **IBuffer** | 指令缓冲队列 | ✅ 已完成 |
| **WarpScheduler** | 线程束调度器 | ✅ 已完成 |
| **Scoreboard** | 寄存器依赖追踪与记分板 | ✅ 已完成 |
| **OperandCollector** | 操作数收集与分发 | ✅ 已完成 |
| **vGPR / pGPR / uGPR** | 向量/谓词/统一寄存器堆 | ✅ 已完成 |
| **vALU** | 向量 ALU（8 PE，四拍 quarter-rate） | ✅ 已完成 |
| **RCB** | 结果提交缓冲（12 Entry，4 Bank 写仲裁） | ✅ 已完成 |
| **SMSPTop** | 顶层集成与系统级仿真 | ✅ 已完成 |

---

## 📁 项目目录结构

```text
OpenGPGPU/
├── src/                          # 源代码目录
│   ├── memory/                   # 内存模型（Phase 1）
│   │   ├── MemoryController.scala
│   │   ├── PagedMemoryModel.scala
│   │   ├── PageAllocator.scala
│   │   ├── Types.scala
│   │   └── ScalaMemoryModelOps.scala
│   ├── l0icache/                 # L0 指令缓存
│   │   ├── L0ICache.scala
│   │   └── Types.scala
│   ├── l0kcache/                 # L0 常量缓存
│   │   ├── L0KCache.scala
│   │   └── Types.scala
│   ├── ifu/                      # 指令取指单元
│   │   ├── IFU.scala
│   │   ├── Arbiter.scala
│   │   ├── Controller.scala
│   │   ├── PST.scala
│   │   └── Types.scala
│   ├── decoder/                  # 指令译码器
│   │   ├── Decoder.scala
│   │   └── Types.scala
│   ├── ibuffer/                  # 指令缓冲
│   │   ├── IBuffer.scala
│   │   └── Types.scala
│   ├── scheduler/                # 线程束调度与记分板
│   │   ├── WarpScheduler.scala
│   │   ├── SchedulerLogic.scala
│   │   ├── Scoreboard.scala
│   │   ├── ScoreboardLogic.scala
│   │   └── Types.scala
│   ├── collector/                # 操作数收集器
│   │   ├── OperandCollector.scala
│   │   ├── CollectorUnit.scala
│   │   ├── BankArbiter.scala
│   │   └── Types.scala
│   ├── register/                 # 寄存器堆
│   │   ├── vGPR.scala
│   │   ├── pGPR.scala
│   │   ├── uGPR.scala
│   │   └── Types.scala
│   ├── valu/                     # 向量 ALU
│   │   ├── vALU.scala
│   │   ├── PEArray.scala
│   │   └── Types.scala
│   ├── RCB/                      # 结果提交缓冲
│   │   ├── RCB.scala
│   │   └── RCBTypes.scala
│   └── isa/                      # ISA 指令定义
│       ├── SASS.scala            # 指令框架与 Registry
│       ├── LDC.scala / LDG.scala
│       ├── STG.scala / A2S.scala / V2A.scala
│       ├── FADD.scala / IMAD.scala / ISETP.scala
│       ├── EXIT.scala / MBARRIER.scala
│       ├── TMA.scala / WGMMA.scala / WBRA.scala
├── sim/                          # 仿真顶层目录
│   └── smsp/                     # SMSP 子系统仿真
│       ├── SMSPConfig.scala      # 仿真配置
│       ├── SMSPTop.scala         # 顶层集成
│       └── TestBench/
│           └── SMSPTestbench.scala  # 系统级仿真测试台
├── docs/                         # 项目文档
└── utils/                        # 公用工具（Logger）
```

---

## ✨ 核心特性

OpenGPGPU 旨在构建一个完整的、时钟精确的开源 GPGPU 架构模型：

- **时钟精确的微架构模拟**: 基于 Chisel/Hardware Construction 实现 cycle-accurate 行为模拟。
- **SMSP 完整流水线**: IFU → Decoder → IBuffer → WarpScheduler → OperandCollector → vALU → RCB → vGPR 全线贯通，支持指令级仿真。
- **模块化与可扩展设计**: 各子系统独立模块化，便于独立开发与迭代。
- **向量计算核心**: 8 个 PE（Processing Element）组成的向量 ALU，支持 quarter-rate 模式（4 拍完成 32 线程计算）。
- **结果提交缓冲**: RCB 支持 12 个 Entry 的结果缓存、4 Bank 写仲裁、释放信号生成以及 Barrier 同步机制。
- **灵活的指令框架**: 基于 `SASS_Instruction` 抽象类和 `Registry` 的指令注册机制，支持可扩展的 ISA 定义。
- **健壮的仿真基础设施**: 内置 Logger 系统，支持控制台/文件日志输出，仿真时间戳追踪。

---

## 🎯 关键里程碑

### Phase 1: 基础设施构建与内存模型 ✅
- [x] 构建项目基础架构，规范目录与测试标准。
- [x] 实现支持 64 位地址空间和 4KB 分页管理的底层后备内存分配器。
- [x] 实现包含状态机、突发传输和延迟模拟的 Chisel 硬件内存控制器模型及完整 test bench。

### Phase 2: 缓存子系统 ✅
- [x] **L0ICache**（指令缓存）快速建模完成。
- [x] **L0KCache**（常量缓存）快速建模完成。
- [x] 集成到 SMSP 前端流水线。

### Phase 3: 指令执行与核心流水线 ✅
- [x] **ISA 框架**: SASS 指令定义与 Registry 注册机制。
- [x] **IFU（取指单元）**: 含 Arbiter、Controller、PST（程序状态追踪）。
- [x] **Decoder（译码器）**: 支持 Registry 驱动的指令匹配与 MicroOp 生成。
- [x] **IBuffer（指令缓冲）**: 多 warp 指令缓存与 Credit 返回机制。
- [x] **WarpScheduler 与 Scoreboard**: 支持 8 Warp 调度与 6 Slot 记分板。
- [x] **OperandCollector**: 操作数收集、CU 分发与 Bank 写仲裁。
- [x] **vALU**: 8 PE 向量计算单元，支持流水线停顿与 quarter-rate 时序。
- [x] **RCB**: 结果提交缓冲，支持 Free List、Bank 写仲裁、Barrier 释放。
- [x] **寄存器堆**: vGPR（4 Bank 向量寄存器）、pGPR（谓词寄存器）、uGPR（统一寄存器）。

### Phase 4: 系统集成与验证 ✅（快速建模阶段）
- [x] **SMSPTop**: 顶层模块集成，所有内部子模块数据通路连通。
- [x] **SMSPTestbench**: 系统级 cycle-accurate 仿真测试台，支持指令注入与日志追踪。
- [x] **集成测试覆盖**: 前端流水线、中端调度-收集-计算-写回全路径验证。

### Phase 5: 完整功能与性能优化 🔜
- [ ] 支持完整 ISA 指令集（含复杂计算与访存指令）。
- [ ] L1/L2 Cache 层级结构与一致性机制。
- [ ] 结合 DPI-C 桥接外部模拟器协同仿真。
- [ ] 多 SMSP 集群与片上互联结构。

---

## 🚀 快速开始

### 环境要求
- JDK 11 或更高版本
- Scala 2.13.x
- sbt 1.x

### 运行测试

克隆项目后，在根目录执行以下命令：

```bash
sbt test
```

执行该命令将编译代码并运行所有的单元测试和集成测试。

### 运行系统级仿真

SMSP 顶层系统级仿真包含完整的流水线日志输出：

```bash
sbt "testOnly sim.smsp.TestBench.SMSPTestbench"
```

该仿真将启动一个 Warp，注入指令（如 ADD），驱动 ICache Fill，追踪 IFU → Decoder → IBuffer → Scheduler → Collector → vALU → RCB → vGPR 全路径状态，并以周期精度输出日志。

---

## 🔧 模块说明

### 前端流水线（Frontend）

| 模块 | 文件 | 说明 |
|------|------|------|
| **IFU** | [`src/ifu/IFU.scala`](src/ifu/IFU.scala) | 从 ICache 取指，包含请求仲裁、PC 管理、ICache 唤醒与 Credit 返回 |
| **L0ICache** | [`src/l0icache/L0ICache.scala`](src/l0icache/L0ICache.scala) | 指令缓存，支持 Hit/Miss 处理与 Fill 响应 |
| **L0KCache** | [`src/l0kcache/L0KCache.scala`](src/l0kcache/L0KCache.scala) | 常量缓存，支持 Decoder 早期探针与 OperandCollector 读取 |
| **Decoder** | [`src/decoder/Decoder.scala`](src/decoder/Decoder.scala) | 指令译码，通过 ISA Registry 匹配指令，提取 MicroOp 字段 |
| **IBuffer** | [`src/ibuffer/IBuffer.scala`](src/ibuffer/IBuffer.scala) | 指令缓冲队列，管理各 Warp 的已译码指令 |

### 中端流水线（Mid-End）

| 模块 | 文件 | 说明 |
|------|------|------|
| **WarpScheduler** | [`src/scheduler/WarpScheduler.scala`](src/scheduler/WarpScheduler.scala) | 线程束调度，从 IBuffer 弹出指令并派发 |
| **Scoreboard** | [`src/scheduler/Scoreboard.scala`](src/scheduler/Scoreboard.scala) | 记分板，追踪寄存器依赖与指令完成释放 |
| **OperandCollector** | [`src/collector/OperandCollector.scala`](src/collector/OperandCollector.scala) | 操作数收集，分发到执行单元 |
| **vGPR** | [`src/register/vGPR.scala`](src/register/vGPR.scala) | 向量寄存器堆（4 Bank，每 Bank 多端口读写） |
| **pGPR** | [`src/register/pGPR.scala`](src/register/pGPR.scala) | 谓词寄存器堆 |
| **uGPR** | [`src/register/uGPR.scala`](src/register/uGPR.scala) | 统一寄存器堆 |

### 执行与写回（Execute & Writeback）

| 模块 | 文件 | 说明 |
|------|------|------|
| **vALU** | [`src/valu/vALU.scala`](src/valu/vALU.scala) | 向量 ALU，8 PE 四拍 quarter-rate 流水线 |
| **PEArray** | [`src/valu/PEArray.scala`](src/valu/PEArray.scala) | PE 阵列，各 PE 独立运算单元 |
| **RCB** | [`src/RCB/RCB.scala`](src/RCB/RCB.scala) | 结果提交缓冲，12 Entry 池 + 4 Bank 写仲裁 + Barrier 释放 |

### ISA 指令集

| 指令 | 文件 | 类型 |
|------|------|------|
| LDC_64 | [`src/isa/LDC.scala`](src/isa/LDC.scala) | 访存指令 |
| LDG | [`src/isa/LDG.scala`](src/isa/LDG.scala) | 访存指令 |
| STG | [`src/isa/STG.scala`](src/isa/STG.scala) | 访存指令 |
| A2S / V2A | [`src/isa/A2S.scala`](src/isa/A2S.scala) / [`src/isa/V2A.scala`](src/isa/V2A.scala) | 数据传输 |
| FADD / IMAD / ISETP | [`src/isa/FADD.scala`](src/isa/FADD.scala) / [`src/isa/IMAD.scala`](src/isa/IMAD.scala) / [`src/isa/ISETP.scala`](src/isa/ISETP.scala) | 算术运算 |
| EXIT / MBARRIER | [`src/isa/EXIT.scala`](src/isa/EXIT.scala) / [`src/isa/MBARRIER.scala`](src/isa/MBARRIER.scala) | 控制流 |
| TMA / WGMMA / WBRA | [`src/isa/TMA.scala`](src/isa/TMA.scala) / [`src/isa/WGMMA.scala`](src/isa/WGMMA.scala) / [`src/isa/WBRA.scala`](src/isa/WBRA.scala) | 矩阵/同步 |

### 仿真顶层

| 组件 | 文件 | 说明 |
|------|------|------|
| **SMSPConfig** | [`sim/smsp/SMSPConfig.scala`](sim/smsp/SMSPConfig.scala) | 仿真日志级别、时钟周期等全局配置 |
| **SMSPTop** | [`sim/smsp/SMSPTop.scala`](sim/smsp/SMSPTop.scala) | 顶层集成：实例化并连接所有子模块 |
| **SMSPTestbench** | [`sim/smsp/TestBench/SMSPTestbench.scala`](sim/smsp/TestBench/SMSPTestbench.scala) | 系统级仿真测试台：Warp 初始化、ICache Fill、周期日志输出 |

---

## 📖 文档

- [**OpenGPGPU MVP ISA 概览**](docs/OpenGPGPU_MVP_ISA_Overview.md) - ISA 定义与指令格式
- [**项目目录结构**](docs/PROJECT_STRUCTURE.md) - 项目目录详细规划
- [**用户指南**](docs/USER_GUIDE.md) - 详细使用说明与测试方法

---

## 🗺️ 后续开发路线图

### 短期规划
- [ ] 增加 LDG/STG 访存指令的执行支持，打通访存路径。
- [ ] 完善 LSU（Load/Store Unit）模块与 RCB 的 LSU 预留槽位对接。
- [ ] 增加更多 ISA 指令的完整译码与执行支持。
- [ ] 增强 OperandCollector 的调度效率与背压处理。

### 中期规划
- [ ] 引入 DPI-C 协同仿真，与外部 C++ 模拟器交互。
- [ ] 加入 TLB 及虚拟地址到物理地址转换支持。
- [ ] 实现 L1/L2 Cache 层级结构与一致性协议。
- [ ] 增加乱序请求处理以及多通道读写控制。

### 长期规划
- [ ] 多 SMSP 集群与片上互联结构（Network-on-Chip）。
- [ ] 任务调度与分发模块（GPC 级别）。
- [ ] 典型 GPGPU Kernel 程序的全系统端到端验证。
- [ ] Verilog 代码生成与 FPGA 原型验证。

---

## 📄 许可证

本项目采用 BSD 3-Clause 许可证开源。有关详细信息，请参阅 [LICENSE](LICENSE) 文件。
