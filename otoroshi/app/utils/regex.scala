package otoroshi.utils

import otoroshi.utils.cache.types.LegitConcurrentHashMap
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.Logger

import java.util.regex.Pattern.CASE_INSENSITIVE
import java.util.regex.{MatchResult, Matcher, Pattern}
import scala.concurrent.{ExecutionContext, Future}

case class Regex(originalPattern: String, compiledPattern: Pattern) {
  def matches(value: String): Boolean   = compiledPattern.matcher(value).matches()
  def split(value: String): Seq[String] = compiledPattern.split(value).toSeq
}

object RegexPool {

  lazy val logger = Logger("otoroshi-regex-pool")

  private val pool = new LegitConcurrentHashMap[String, Regex]() // TODO: check growth over time

  def apply(originalPattern: String): Regex = {
    if (!pool.containsKey(originalPattern)) {
      val processedPattern: String = originalPattern.replace(".", "\\.").replaceAll("\\*", ".*")
      if (logger.isTraceEnabled) logger.trace(s"Compiling pattern : `$processedPattern`")
      pool.putIfAbsent(originalPattern, Regex(originalPattern, Pattern.compile(processedPattern)))
    }
    pool.get(originalPattern)
  }

  def regex(originalPattern: String): Regex = {
    if (!pool.containsKey(originalPattern)) {
      if (logger.isTraceEnabled) logger.trace(s"Compiling pattern : `$originalPattern`")
      pool.putIfAbsent(originalPattern, Regex(originalPattern, Pattern.compile(originalPattern)))
    }
    pool.get(originalPattern)
  }

  def theRegex(originalPattern: String): Option[Regex] = {
    originalPattern match {
      case value if value.startsWith("Regex(") => regex(value.substring(6).init).some
      case value if value.startsWith("Wildcard(") => apply(value.substring(9).init).some
      case _ => None
    }
  }
}

object ReplaceAllWith {
  def apply(regex: String): ReplaceAllWith = new ReplaceAllWith(regex)
}

class ReplaceAllWith(regex: String) {

  val pattern: Pattern = Pattern.compile(regex, CASE_INSENSITIVE)

  def replaceOn(value: String)(callback: String => String): String = {
    var str: String      = value
    val matcher: Matcher = pattern.matcher(str)
    while (matcher.find()) {
      val matchResult: MatchResult = matcher.toMatchResult
      val expression: String       = matchResult.group().substring(2).init
      val replacement: String      = callback(expression)
      str = str.substring(0, matchResult.start) + replacement + str.substring(matchResult.end)
      matcher.reset(str)
    }
    str
  }

  def replaceOnAsync(
      value: String
  )(callback: String => Future[String])(implicit ec: ExecutionContext): Future[String] = {
    var str: String      = value
    val matcher: Matcher = pattern.matcher(str)
    def next(): Future[String] = {
      if (matcher.find()) {
        val matchResult: MatchResult = matcher.toMatchResult
        val expression: String       = matchResult.group().substring(2).init
        callback(expression).flatMap { replacement =>
          str = str.substring(0, matchResult.start) + replacement + str.substring(matchResult.end)
          matcher.reset(str)
          next()
        }
      } else {
        str.vfuture
      }
    }
    next()
  }
}

object test {
  new ReplaceAllWith("\\$\\{(.*)\\}").replaceOn("hello ${value}") { expression =>
    println("variable: " + expression)
    "pouet"
  }
}

object UrlSanitizer {
  private val doubleSlash = Pattern.compile("([^:])\\/\\/")
  def sanitize(url: String): String = {
    doubleSlash.matcher(url).replaceAll("$1/")
  }
}
