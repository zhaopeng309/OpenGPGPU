# OpenGPGPU 用户指南

## 目录
1. [项目简介](#1-项目简介)
2. [当前开发状态](#2-当前开发状态)
3. [核心组件](#3-核心组件)
4. [测试用例与用法](#4-测试用例与用法)
5. [后续开发建议](#5-后续开发建议)

---

## 1. 项目简介
**OpenGPGPU** 是一个完整的开源 GPGPU 项目，旨在提供详细的、基于 Chisel 语言的 GPGPU 内核时钟精确架构建模。

> **注意：** 目前该项目正处于初始阶段，代码库中仅仅包含了一个 Memory 模型（内存模型），目的是为了建立并验证基础的开发和仿真环境。请不要误以为本项目仅仅是一个内存模型。

当前的 Memory 模型为后续的 GPGPU 开发提供了一个包含延迟模拟 (Latency)、突发传输 (Burst Transfer) 以及分页内存管理的内存控制器硬件模型与软件后备模型。它使得系统能够在仿真阶段准确地模拟和追踪真实的物理内存行为。

## 2. 当前开发状态
项目处于初期阶段，已完成了基础 Memory 模型核心组件的开发和功能验证，各项功能运行稳定：
- **硬件内存控制器 (`MemoryController`)**: 基于 Chisel3 实现，支持 `Decoupled` 握手协议接收请求。内部集成了状态机 (Idle -> Process -> Respond)，完美支持内存操作的延迟模拟以及突发传输机制。
- **软件后备内存 (`PagedMemoryModel` & `PageAllocator`)**: 实现了底层的 64 位地址空间与 4KB 分页管理。提供包括分配 (`allocate`)、释放 (`free`)、读写、大容量分配追踪等 API，防止了未初始化访问。
- **功能验证**: 基于 `chiseltest` 实现了完善的测试台机制。所有核心场景的单元测试已全部通过，覆盖率表现良好。

## 3. 核心组件
项目的代码主要集中在 `src/memory/` 目录下：
- [`MemoryController.scala`](../src/memory/MemoryController.scala): 处理内存的读写时序与请求响应，包含一个用于仿真的后门接口 `simIO`。
- [`PagedMemoryModel.scala`](../src/memory/PagedMemoryModel.scala): 软件模拟内存对外的 API 抽象层。
- [`PageAllocator.scala`](../src/memory/PageAllocator.scala): 负责模拟内存分配、追踪分页数据及内存使用情况统计。
- [`Types.scala`](../src/memory/Types.scala) & [`ScalaMemoryModelOps.scala`](../src/memory/ScalaMemoryModelOps.scala): 统一定义了内部的数据结构（如请求/响应 Bundle）和接口特质。

## 4. 测试用例与用法

项目使用 `sbt` 作为构建工具，相关的测试文件放置在 `test/memory/` 目录下。

### 4.1 运行测试
在项目的根目录下（即当前工作区 `/home/designer/public/OpenGPGPU`），在终端执行以下命令即可启动完整的测试流程：
```bash
sbt test
```
*执行该命令将编译所有的代码，并按顺序执行所有针对内存模型的单元测试。*

### 4.2 当前覆盖的测试场景
当前的测试分为 5 个测试集，总计覆盖 7 项主要特性，且**已全部通过**：
1. **PerformanceMonitorTest**:
   - `PagedMemoryModel` 能够准确地追踪内存使用情况 (`should track memory usage accurately`)。
2. **ErrorHandlingTest**:
   - 优雅地处理非法内存的释放操作，不会导致系统崩溃 (`should handle invalid frees gracefully`)。
   - 读取未初始化的内存时，能够安全地返回全零数据 (`should return zeros for uninitialized memory reads`)。
3. **PagedMemoryTest**:
   - 在 64 位地址空间下正确进行常规内存的分配和释放 (`should allocate and free memory correctly in 64-bit space`)。
   - 能够有效支持大容量的内存分配 (`should support large allocations`)。
4. **BasicMemoryOpsTest**:
   - `MemoryController` 硬件模块能够成功执行基本的读写操作时序交互 (`should perform basic read and write operations`)。
5. **BurstTransferTest**:
   - `MemoryController` 能够正确处理突发长度带来的状态转移及地址递增，完成 128B 的突发读取操作 (`should perform 128B burst read operations`)。

## 5. 后续开发建议
现有的内存基础架构已经足够用于上层缓存模块或总线协议模块的对接。未来可以考虑：
- 结合 DPI-C 或其他协同仿真工具桥接 `simIO`，以便与 C++ 等外部模拟器交互。
- 加入 TLB 及虚拟地址到物理地址转换的支持，完善更上层的地址翻译机制。
- 进一步增加乱序请求处理以及多通道 (Multi-channel) 读写控制。