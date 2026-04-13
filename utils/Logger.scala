package utils

object Logger {
  def info(msg: String): Unit = {
    println(s"[INFO] $msg")
  }

  def warn(msg: String): Unit = {
    println(s"[WARN] $msg")
  }

  def error(msg: String): Unit = {
    System.err.println(s"[ERROR] $msg")
  }

  def debug(msg: String): Unit = {
    // You can toggle debug logs based on some configuration
    println(s"[DEBUG] $msg")
  }
}
