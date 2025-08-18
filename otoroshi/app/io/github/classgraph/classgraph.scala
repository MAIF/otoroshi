package io.github.classgraph

object ClassgraphUtils {
  def clear(result: ScanResult): Unit = {
    result.close()
    try {
      result.classNameToClassInfo.clear()
      result.classNameToClassInfo = null
    } catch {
      case _: Throwable =>
    }
  }
}
