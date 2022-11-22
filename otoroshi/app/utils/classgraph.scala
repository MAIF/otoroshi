package io.github.classgraph

object ClassgraphUtils {
  def clear(result: ScanResult): Unit = {
    result.close()
    result.classNameToClassInfo.clear()
    result.classNameToClassInfo = null
  }
}
