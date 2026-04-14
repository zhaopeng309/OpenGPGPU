# OpenGPGPU 项目目录规划

本项目采用了模块化和分层设计的原则，源代码与测试代码分离，并按照模块进行组织。

## 目录结构说明

```text
OpenGPGPU/
├── src/                    # 源代码目录
│   └── <module_name>/      # 各功能模块源代码（例如 memory）
│       ├── ...             # 模块的设计文件
├── tests/                  # 测试代码总目录
│   ├── unit/               # 单元测试目录
│   │   └── <module_name>/  # 各功能模块的单元测试文件存放处（例如 memory 模块测试位于 tests/unit/memory/）
│   └── integrate/          # 集成测试目录
│       ├── e2e/            # 全局端到端 (End-to-End) 测试
│       ├── tb_isa/         # ISA 指令流水线子系统集成测试
│       ├── tb_task/        # 任务分发子系统集成测试
│       └── tb_cache/       # 缓存子系统集成测试
├── docs/                   # 项目文档目录
├── utils/                  # 公用工具和辅助类源码
├── build/                  # SBT 构建和测试输出目录
└── project/                # SBT 构建配置文件
```

## 测试规范

1. **单元测试 (Unit Tests):** 
   必须放置在 `tests/unit/<对应的模块名>/` 目录下。这些测试用于验证单个组件或类的行为是否符合预期。

2. **集成测试 (Integration Tests):**
   用于跨模块或子系统级别的测试，放置在 `tests/integrate/` 下对应的子模块或 e2e 目录中。全局系统测试或者关键子系统的整合测试在这里进行。
