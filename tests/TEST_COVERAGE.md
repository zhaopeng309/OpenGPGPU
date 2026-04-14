# OpenGPGPU 测试用例覆盖说明

当前已实现基于 `chiseltest` 的完善测试台机制，针对现有的模块覆盖了以下核心场景（各项功能运行稳定，测试已全部通过）：

## Memory 模块 (单元测试: `tests/unit/memory/`)

- **基础读写时序** (`BasicMemoryOpsTest`): 测试单次读写的延迟和响应。
- **突发传输 (Burst) 机制** (`BurstTransferTest`): 测试连续数据块的高效传输支持。
- **64位地址空间分页与大容量分配** (`PagedMemoryTest`): 测试大内存场景下的分页管理机制。
- **非法释放与未初始化访问的安全容错处理** (`ErrorHandlingTest`): 测试异常情况下的边界保护和报错。
- **内存使用率及分配状态准确追踪** (`PerformanceMonitorTest`): 测试内存分配监控和泄漏检测。

随着后续模块（如缓存、ISA流水线、任务调度等）的开发，本文件将持续更新相应的测试用例说明。
