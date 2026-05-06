.PHONY: all clean compile test test-memory sim doc build format help

# 默认目标：清理、编译并测试
all: clean compile test

# 清理构建输出
clean:
	sbt clean

# 编译代码
compile:
	sbt compile

# 运行所有测试
test:
	sbt test

# 运行特定模块（内存）的测试
test-memory:
	sbt "testOnly *memory*"

# 运行仿真（包含周期级别的日志输出）
sim:
	sbt "testOnly sim.SimTestbench"

# 生成 Scaladoc 文档
doc:
	sbt doc

# 打包项目
build:
	sbt package

# 格式化代码（如果配置了 scalafmt）
format:
	sbt scalafmt scalafmtSbt

# 显示帮助信息
help:
	@echo "OpenGPGPU Makefile 常用命令指南:"
	@echo "--------------------------------------------------------"
	@echo "  make all         - 执行 clean, compile 和 test (默认)"
	@echo "  make clean       - 清理构建生成的 target 目录"
	@echo "  make compile     - 编译项目源代码"
	@echo "  make test        - 运行项目中所有的测试用例"
	@echo "  make test-memory - 仅运行内存(memory)相关的测试用例"
	@echo "  make sim         - 运行周期级别的仿真测试并输出日志"
	@echo "  make doc         - 生成项目的 Scaladoc API 文档"
	@echo "  make build       - 编译并打包为 JAR 文件"
	@echo "  make format      - 使用 scalafmt 格式化 Scala 源码"
	@echo "  make help        - 显示此帮助信息"
	@echo "--------------------------------------------------------"
