# OpenGPGPU

[![Scala](https://img.shields.io/badge/scala-2.13.12-red.svg)](https://scala-lang.org)
[![Chisel](https://img.shields.io/badge/chisel-6.2.0-blue.svg)](https://www.chisel-lang.org)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](https://opensource.org/licenses/BSD-3-Clause)

**OpenGPGPU** 是一个完整的开源 GPGPU 项目，旨在提供详细的、基于 Chisel 语言的 GPGPU 内核时钟精确架构建模。

## ⚠️ 当前开发状态

> **注意：** 目前该项目正处于初始阶段，代码库中主要包含了**Memory 模型（内存模型）**的基础设施，目的是为了建立并验证基础的开发和仿真环境。本项目旨在打造一个完整的 GPGPU 架构，内存模块仅仅是整个项目的第一步。

当前的 Memory 模型为后续的 GPGPU 开发提供了一个包含延迟模拟 (Latency)、突发传输 (Burst Transfer) 以及分页内存管理的内存控制器硬件模型与软件后备模型。它使得系统能够在仿真阶段准确地模拟和追踪真实的物理内存行为。

## ✨ 核心特性与组件

项目初期的核心代码位于 `src/memory/`，主要包含以下模块：

*   **硬件内存控制器 (`MemoryController.scala`)**: 基于 Chisel3 实现，支持 `Decoupled` 握手协议接收请求。内部集成了状态机，完美支持内存操作的延迟模拟以及突发传输机制。包含一个用于仿真的后门接口 `simIO`。
*   **软件后备内存**:
    *   **`PagedMemoryModel.scala`**: 软件模拟内存对外的 API 抽象层。
    *   **`PageAllocator.scala`**: 实现了底层的 64 位地址空间与 4KB 分页管理。提供包括分配、释放、读写、大容量分配追踪等 API，内置防越界和未初始化访问保护。
*   **内部接口 (`Types.scala`, `ScalaMemoryModelOps.scala`)**: 统一定义了内部的数据结构（如请求/响应 Bundle）和接口特质。

## 🚀 快速开始

本项目使用 `sbt` 作为构建工具。

### 环境要求
- JDK 11 或更高版本
- Scala 2.13.x
- sbt 1.x

### 运行测试

克隆项目后，在根目录执行以下命令即可启动完整的测试流程，验证内存模型的各项功能：

```bash
sbt test
```

执行该命令将编译代码并运行所有针对内存模型的单元测试（位于 `test/memory/` 目录下）。

### 测试用例覆盖

当前已实现基于 `chiseltest` 的完善测试台机制，覆盖以下核心场景（各项功能运行稳定，测试已全部通过）：
- **基础读写时序** (`BasicMemoryOpsTest`)
- **突发传输 (Burst) 机制** (`BurstTransferTest`)
- **64位地址空间分页与大容量分配** (`PagedMemoryTest`)
- **非法释放与未初始化访问的安全容错处理** (`ErrorHandlingTest`)
- **内存使用率及分配状态准确追踪** (`PerformanceMonitorTest`)

## 📖 文档

更多详细说明、系统架构与用法指南，请参阅 [OpenGPGPU 用户指南](docs/USER_GUIDE.md)。

## 🗺️ 后续开发路线图

现有的内存基础架构已经足够用于上层缓存模块或总线协议模块的对接。未来的开发重点包括：
- [ ] 结合 DPI-C 或其他协同仿真工具桥接 `simIO`，以便与 C++ 等外部模拟器交互。
- [ ] 加入 TLB 及虚拟地址到物理地址转换的支持，完善更上层的地址翻译机制。
- [ ] 进一步增加乱序请求处理以及多通道 (Multi-channel) 读写控制。
- [ ] 逐步建模 GPGPU 的计算核心流水线及其他架构组件。

## 📄 许可证

本项目采用 BSD 3-Clause 许可证开源。有关详细信息，请参阅 [LICENSE](LICENSE) 文件。
