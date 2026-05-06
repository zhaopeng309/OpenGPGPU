# 仿真日志系统 - 实施计划

## 需求概述

为 OpenGPGPU 的每个模块增加时钟消耗的日志，在 `sim` 仿真模式下可以输出每个时钟周期各模块在干什么的日志信息。默认输出到屏幕，也可以通过配置输出到文件中。在运行模式（非仿真）下不输出日志，以提高运行速度。

## 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                    sim/ 仿真目录结构                          │
│                                                             │
│  sim/                                                       │
│   ├── SimConfig.scala       ← 仿真配置（日志开关/输出目标）    │
│   ├── SimTop.scala          ← 顶层模块（集成所有子模块）      │
│   └── SimTestbench.scala    ← 测试平台（每个时钟周期打印日志） │
│                                                             │
│  utils/                                                     │
│   └── Logger.scala          ← 已有日志工具（保持不变）        │
└─────────────────────────────────────────────────────────────┘
```

## 核心设计决策

### 1. 日志输出方式

- **不使用 Chisel `printf`**：因为 `printf` 在 RTL 综合时也会生成硬件逻辑，影响运行速度
- **在 Scala Testbench 中通过 `peek()` 读取硬件信号**：每个时钟周期结束后，Testbench 读取各个模块的 IO 引脚状态，调用 `Logger` 打印
- **运行模式不输出**：SimTestbench 只在 `sim` 目录下存在，`sbt test` 不会编译 sim 目录

### 2. SimConfig 配置对象

```scala
package sim

object SimConfig {
  // 日志输出目标: "console" | "file"
  var logTarget: String = "console"
  
  // 日志文件路径（当 logTarget = "file" 时有效）
  var logFile: String = "sim.log"
  
  // 日志级别过滤
  var logLevel: String = "INFO"
  
  // 是否启用仿真日志
  var enabled: Boolean = true
}
```

### 3. SimTop 顶层模块

SimTop 将集成所有子模块（IFU、Decoder、IBuffer、WarpScheduler、OperandCollector、L0ICache、L0KCache、RegisterFile 等），并暴露所有内部模块的 IO 信号到顶层，供 Testbench 读取。

**关键设计**：SimTop 不添加任何额外的硬件逻辑，仅做模块例化和信号连线。所有内部模块的 IO 都通过 `SimTop` 的 IO 暴露出去。

### 4. SimTestbench 日志内容

每个时钟周期打印以下信息：

| 模块 | 日志内容 |
|------|---------|
| IFU | warp_init, grant_valid, grant_warp_id, icache_req_valid, icache_rsp(hit/miss), wakeup, decoder_out_valid |
| Decoder | validIn, microOpOut(opcode, rd, rs1, rs2), illegalInst |
| IBuffer | emptyMask, popEn, popWarpId, creditReturn |
| L0ICache | req_valid, is_hit, is_miss, mshr_alloc, fill_valid, wakeup |
| L0KCache | probe_req, oc_read, is_hit, is_mshr_hit, fill, wakeup |
| WarpScheduler | wst_valid/ready/stalled per warp, dispatch_valid, dispatch_warpId, hazard_mask, slot_full |
| OperandCollector | dispatch, cu_state, issue, release |
| vGPR | read_reqs, write_reqs |
| MemoryController | req, resp, state |

## 实施步骤

### Step 1: 创建 sim/ 目录结构

```
sim/
├── SimConfig.scala       # 仿真配置
├── SimTop.scala          # 顶层模块
└── SimTestbench.scala    # 测试平台
```

### Step 2: 实现 SimConfig.scala

- 定义 `SimConfig` 单例对象
- 包含 `logTarget`（console/file）、`logFile`、`logLevel`、`enabled` 等配置项
- 提供 `init()` 方法初始化 Logger

### Step 3: 实现 SimTop.scala

- 创建 `SimTop` 顶层 Chisel Module
- 例化所有子模块：IFU, Decoder, IBuffer, WarpScheduler, OperandCollector, L0ICache, L0KCache, vGPR_Top, pGPR, uGPR, MemoryController
- 将所有子模块的 IO 信号连接到 SimTop 的 IO 端口
- 添加必要的测试激励输入端口（warp_init, pop, memory_fill 等）

### Step 4: 实现 SimTestbench.scala

- 使用 `chiseltest` 框架
- 在每个时钟周期：
  1. 驱动测试激励
  2. `clock.step(1)` 前进一个时钟周期
  3. 使用 `peek()` 读取所有模块的 IO 信号
  4. 调用 `Logger` 打印每个模块的状态
- 支持通过 `SimConfig` 控制输出目标

### Step 5: 更新 build.sbt

- 添加 `sim` 源码目录到 `Compile` 或创建一个新的 `Sim` 配置

### Step 6: 更新 Makefile

- 添加 `make sim` 目标，运行仿真测试

## 模块状态打印格式

每个时钟周期输出格式示例：

```
[@cycle_123] [IFU] grant_valid=1 grant_warp_id=2 icache_req=1
[@cycle_123] [DEC] valid=1 opcode=0x05 rd=10 rs1=5 rs2=0
[@cycle_123] [IBUF] emptyMask=0b11111011 popEn=0 popWarpId=0
[@cycle_123] [ICACHE] req=1 hit=0 miss=1 mshr_alloc=1
[@cycle_123] [KCACHE] probe=0 oc_read=0 hit=0
[@cycle_123] [WS] dispatch_valid=1 dispatch_wid=2 wst_valid=0b0001 wst_ready=0b0001
[@cycle_123] [OC] dispatch_ready=1 cu_free=0b11111111 issue_valid=0
[@cycle_123] [VGPR] read_reqs=1 write_reqs=0
[@cycle_123] [MEM] state=IDLE req_valid=0 resp_valid=0
```

## 注意事项

1. **性能**：SimTestbench 仅在仿真时使用，不影响综合后的运行速度
2. **可配置性**：通过 SimConfig 可以控制日志级别和输出目标
3. **可扩展性**：新增模块时，只需在 SimTop 中例化并添加对应的日志打印逻辑
4. **与现有测试兼容**：现有的 `tests/` 目录下的单元测试不受影响
