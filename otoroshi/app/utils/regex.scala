package otoroshi.utils

import otoroshi.utils.cache.types.UnboundedConcurrentHashMap
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.Logger

import java.util.regex.Pattern.CASE_INSENSITIVE
import java.util.regex.{Matcher, Pattern}
import scala.concurrent.{ExecutionContext, Future}

case class Regex(originalPattern: String, compiledPattern: Pattern) {
  def matches(value: String): Boolean   = compiledPattern.matcher(value).matches()
  def split(value: String): Seq[String] = compiledPattern.split(value).toSeq
}

object RegexPool {

  lazy val logger = Logger("otoroshi-regex-pool")

  private val pool = new UnboundedConcurrentHashMap[String, Regex]() // TODO: check growth over time

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
      case value if value.startsWith("Regex(")    => regex(value.substring(6).init).some
      case value if value.startsWith("Wildcard(") => apply(value.substring(9).init).some
      case _                                      => None
    }
  }
}

object ReplaceAllWith {
  def apply(regex: String): ReplaceAllWith = new ReplaceAllWith(regex)
}

class ReplaceAllWith(regex: String) {

  val pattern: Pattern = Pattern.compile(regex, CASE_INSENSITIVE)

  // one pass over the value: a replacement is inserted as is and never scanned again. it can hold data that does not
  // come from the configuration, like a request header, and scanning it again would evaluate that data as well
  def replaceOn(value: String, beginIndex: Int = 2)(callback: String => String): String = {
    val matcher: Matcher = pattern.matcher(value)
    if (!matcher.find()) value
    else {
      val builder = new java.lang.StringBuilder(value.length + 16)
      var last    = 0
      var found   = true
      while (found) {
        val expression: String = value.substring(matcher.start() + beginIndex, matcher.end() - 1)
        builder.append(value, last, matcher.start()).append(callback(expression))
        last = matcher.end()
        found = matcher.find()
      }
      builder.append(value, last, value.length).toString
    }
  }

  // same single pass as replaceOn, the callbacks run one after the other
  def replaceOnAsync(
      value: String,
      beginIndex: Int = 2
  )(callback: String => Future[String])(using ec: ExecutionContext): Future[String] = {
    val matcher: Matcher = pattern.matcher(value)
    val builder          = new java.lang.StringBuilder(value.length + 16)
    def next(last: Int): Future[String] = {
      if (matcher.find()) {
        val start              = matcher.start()
        val end                = matcher.end()
        val expression: String = value.substring(start + beginIndex, end - 1)
        callback(expression).flatMap { replacement =>
          builder.append(value, last, start).append(replacement)
          next(end)
        }
      } else {
        builder.append(value, last, value.length).toString.vfuture
      }
    }
    next(0)
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
