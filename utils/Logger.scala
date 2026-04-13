package utils

import java.io.{FileWriter, PrintWriter}
import java.time.{Duration, Instant}

object Level extends Enumeration {
  type Level = Value
  val DEBUG, INFO, WARNING, ERROR = Value
  
  implicit val ordering: Ordering[Value] = Ordering.by(_.id)
}

import Level._

class Logger private {
  private var currentLevel: Level = Level.INFO
  private var fileWriter: Option[PrintWriter] = None
  private var timeProvider: () => Long = () => 0L
  
  def setTimeProvider(provider: () => Long): Unit = {
    timeProvider = provider
  }
  
  def init(filename: String = "", level: Level = Level.INFO): Unit = {
    currentLevel = level
    fileWriter.foreach(_.close())
    fileWriter = if (filename.nonEmpty) {
      Some(new PrintWriter(new FileWriter(filename, true)))
    } else {
      None
    }
  }
  
  def setLevel(level: Level): Unit = {
    currentLevel = level
  }
  
  def log(level: Level, module: String, message: String): Unit = {
    if (level.id >= currentLevel.id) {
      val simTime = timeProvider()
      val levelStr = level.toString.padTo(7, ' ')
      val formatted = s"[@$simTime] [$levelStr] [$module] $message"
      
      // Console output with colors
      val color = level match {
        case Level.DEBUG => Console.CYAN
        case Level.INFO => Console.GREEN
        case Level.WARNING => Console.YELLOW
        case Level.ERROR => Console.RED
      }
      println(s"$color$formatted${Console.RESET}")
      
      // File output
      fileWriter.foreach { writer =>
        writer.println(formatted)
        writer.flush()
      }
    }
  }
  
  def toHexString(value: Long): String = {
    f"0x$value%016X"
  }
  
  def toHexString(value: BigInt): String = {
    f"0x$value%X"
  }

  def toHexString(value: Int): String = {
    f"0x$value%08X"
  }
  
  def close(): Unit = {
    fileWriter.foreach(_.close())
    fileWriter = None
  }
}

object Logger {
  private val instanceVar = new Logger()
  
  def instance: Logger = instanceVar
  
  // Convenient methods
  def debug(module: String, message: String): Unit = 
    instance.log(Level.DEBUG, module, message)
  
  def info(module: String, message: String): Unit = 
    instance.log(Level.INFO, module, message)
  
  def warning(module: String, message: String): Unit = 
    instance.log(Level.WARNING, module, message)
  
  def error(module: String, message: String): Unit = 
    instance.log(Level.ERROR, module, message)

  // Backward compatibility with simple single-string logs
  def info(msg: String): Unit = instance.log(Level.INFO, "DEFAULT", msg)
  def warn(msg: String): Unit = instance.log(Level.WARNING, "DEFAULT", msg)
  def error(msg: String): Unit = instance.log(Level.ERROR, "DEFAULT", msg)
  def debug(msg: String): Unit = instance.log(Level.DEBUG, "DEFAULT", msg)
}

class ModuleLogger(moduleName: String, var minLevel: Level = Level.INFO) {
  def debug(message: String): Unit = {
    if (minLevel <= Level.DEBUG) {
      Logger.debug(moduleName, message)
    }
  }
  
  def info(message: String): Unit = {
    if (minLevel <= Level.INFO) {
      Logger.info(moduleName, message)
    }
  }
  
  def warning(message: String): Unit = {
    if (minLevel <= Level.WARNING) {
      Logger.warning(moduleName, message)
    }
  }
  
  def error(message: String): Unit = {
    if (minLevel <= Level.ERROR) {
      Logger.error(moduleName, message)
    }
  }
  
  def setLevel(level: Level): Unit = {
    minLevel = level
  }
}

class PerformanceMonitor(operationName: String) {
  private val startTime: Instant = Instant.now()
  
  Logger.debug("PERF", s"Starting: $operationName")
  
  def complete(): Unit = {
    val duration = Duration.between(startTime, Instant.now())
    Logger.debug("PERF", s"Completed: $operationName, duration: ${duration.toMillis}ms")
  }
}
