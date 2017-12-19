package utils

import collection.JavaConversions._
import java.util.regex.Pattern
import play.api.Logger

case class Regex(originalPattern: String, compiledPattern: Pattern) {
  def matches(value: String): Boolean = compiledPattern.matcher(value).matches()
}

object RegexPool {

  lazy val logger = Logger("otoroshi-regex-pool")

  private val pool = new java.util.concurrent.ConcurrentHashMap[String, Regex]()

  def apply(originalPattern: String): Regex = {
    if (!pool.containsKey(originalPattern)) {
      val processedPattern: String = originalPattern.replace(".", "\\.").replaceAll("\\*", ".*")
      logger.info(s"Compiling pattern : `$processedPattern`")
      pool.putIfAbsent(originalPattern, Regex(originalPattern, Pattern.compile(processedPattern)))
    }
    pool.get(originalPattern)
  }

  def regex(originalPattern: String): Regex = {
    if (!pool.containsKey(originalPattern)) {
      logger.info(s"Compiling pattern : `$originalPattern`")
      pool.putIfAbsent(originalPattern, Regex(originalPattern, Pattern.compile(originalPattern)))
    }
    pool.get(originalPattern)
  }
}
