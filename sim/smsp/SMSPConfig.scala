package sim.smsp

import utils.{Level, Logger}

object SMSPConfig {
  // 控制是否启用仿真日志打印
  var enableSimLog: Boolean = true

  // 配置日志输出目标: "console", "file", 或 "both"
  var logTarget: String = "console"

  // 日志文件路径
  var logFile: String = "sim_trace.log"

  // 最低输出级别
  var logLevel: Level.Level = Level.INFO

  // ========== 时钟/时间配置 ==========
  // 时钟周期（纳秒），默认 1 ns（对应 1 GHz 内核频率）
  var clockPeriodNs: Double = 1.0

  // 当前仿真时间（纳秒），由 TestBench 在每个周期更新
  var currentSimTimeNs: Long = 0L

  /**
   * 初始化日志系统。
   * 根据当前配置，设置底层 utils.Logger
   */
  def initLogger(): Unit = {
    // 设置 Level
    Logger.instance.setLevel(logLevel)

    // 设置时间提供函数：返回当前仿真时间（纳秒）
    Logger.instance.setTimeProvider(() => currentSimTimeNs)

    // 配置输出。如果只需 console，则将 filename 设为空。
    // utils.Logger 的 console 是默认打印的，如果提供了 filename 则同时打印到文件
    val filename = if (logTarget == "file" || logTarget == "both") logFile else ""
    Logger.instance.init(filename, logLevel)
  }
}
