package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.models.IpFiltering
import otoroshi.next.plugins.api._
import otoroshi.utils.RegexPool
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class RegexRule(pattern: String = ".*", mode: String = "allow") {
  def matches(pathAndQuery: String): Boolean = {
    RegexPool.regex(pattern).matches(pathAndQuery)
  }
  def json: JsValue = Json.obj(
    "pattern" -> pattern,
    "mode"    -> mode
  )
}

object RegexRule {
  def apply(obj: JsObject): RegexRule =
    RegexRule(obj.select("pattern").asOptString.getOrElse(".*"), obj("mode").asOptString.getOrElse("allow"))
}

case class StatusCodeRange(from: Int, to: Int) {
  def str: String = {
    if (from == to) {
      from.toString
    } else {
      s"$from-$to"
    }
  }
}
object StatusCodeRange                         {
  def apply(from: Int): StatusCodeRange = StatusCodeRange(from, from)
}

case class Fail2BanConfig(
    identifier: String = "${route.id}-${req.ip}",
    detectTimeMs: FiniteDuration = 10.minute,
    banTimeMs: FiniteDuration = 3.hour,
    maxRetry: Int = 4,
    urlRegex: Seq[RegexRule] = Seq.empty,
    statusMatchers: Seq[StatusCodeRange] = Fail2BanConfig.defaultRanges, // inclusive ranges
    ignored: Seq[String] = Seq.empty,
    blocked: Seq[String] = Seq.empty
) extends NgPluginConfig {
  def isUrlInScope(pathAndQuery: String): Boolean = {
    if (urlRegex.isEmpty) true
    else {
      urlRegex.find(_.matches(pathAndQuery)) match {
        case None                                              => true
        case Some(rule) if rule.mode.equalsIgnoreCase("allow") => true
        case Some(rule) if rule.mode.equalsIgnoreCase("block") => false
      }
    }
  }
  def isFailedStatus(status: Int): Boolean =
    statusMatchers.exists { case StatusCodeRange(min, max) => status >= min && status <= max }

  def isIgnored(remoteAddress: String): Boolean = {
    ignored.exists {
      case ip if ip.startsWith("Ip(") && ip.endsWith(")")         => {
        otoroshi.utils.RegexPool(ip.substring(3).init).matches(remoteAddress)
      }
      case cidr if cidr.startsWith("Cidr(") && cidr.endsWith(")") => {
        IpFiltering.cidr(cidr).contains(remoteAddress)
      }
      case identifier                                             => otoroshi.utils.RegexPool(identifier).matches(remoteAddress)
    }
  }

  def isBlocked(remoteAddress: String): Boolean = {
    blocked.exists {
      case ip if ip.startsWith("Ip(") && ip.endsWith(")")         => {
        otoroshi.utils.RegexPool(ip.substring(3).init).matches(remoteAddress)
      }
      case cidr if cidr.startsWith("Cidr(") && cidr.endsWith(")") => {
        IpFiltering.cidr(cidr).contains(remoteAddress)
      }
      case identifier                                             => otoroshi.utils.RegexPool(identifier).matches(remoteAddress)
    }
  }

  def json: JsValue = Fail2BanConfig.format.writes(this)
}

object Fail2BanConfig {

  private val defaultRanges =
    Seq(StatusCodeRange(400), StatusCodeRange(401), StatusCodeRange(403, 499), StatusCodeRange(500, 599))

  def configFlow: Seq[String] = Seq(
    "identifier",
    "detect_time",
    "ban_time",
    "max_retry",
    "url_regex",
    "status_codes",
    "ignored",
    "blocked"
  )

  def configSchema: JsObject = Json.obj(
    "identifier"   -> Json.obj("type" -> "string", "label" -> "Client identifier", "default" -> "${route.id}-${req.ip}"),
    "detect_time"  -> Json.obj("type" -> "string", "label" -> "Detection window", "default" -> "60s"),
    "ban_time"     -> Json.obj("type" -> "string", "label" -> "Ban time", "default" -> "15m"),
    "max_retry"    -> Json.obj("type" -> "number", "label" -> "Max retries", "default" -> 5),
    "url_regex"    -> Json.obj(
      "type"    -> "array",
      "label"   -> "URLs rules",
      "array"   -> true,
      "format"  -> "form",
      "default" -> Json.arr(),
      "schema"  -> Json.obj(
        "pattern" -> Json.obj("type" -> "string", "label" -> "Pattern", "placeholder" -> "/v1/.*"),
        "mode"    -> Json.obj(
          "type"           -> "select",
          "label"          -> "Mode",
          "possibleValues" -> Json
            .arr(Json.obj("label" -> "Allow", "value" -> "allow"), Json.obj("label" -> "Block", "value" -> "block"))
        )
      ),
      "flow"    -> Json.arr("pattern", "mode")
    ),
    "status_codes" -> Json.obj(
      "type"    -> "array",
      "array"   -> true,
      "format"  -> JsNull,
      "label"   -> "Status codes",
      "default" -> Json.arr("400", "401", "403-499", "500-599")
    ),
    "ignored"      -> Json.obj(
      "type"    -> "array",
      "array"   -> true,
      "format"  -> JsNull,
      "label"   -> "Ignored identifiers",
      "default" -> Json.arr()
    ),
    "blocked"      -> Json.obj(
      "type"    -> "array",
      "array"   -> true,
      "format"  -> JsNull,
      "label"   -> "Blocked identifiers",
      "default" -> Json.arr()
    )
  )

  def default: Fail2BanConfig = Fail2BanConfig()

  val format = new Format[Fail2BanConfig] {

    override def reads(js: JsValue): JsResult[Fail2BanConfig] = Try {
      val detectMs   = parseDurationMillis((js \ "detect_time").asOpt[JsValue].getOrElse(JsString("60s")))
      val banMs      = parseDurationMillis((js \ "ban_time").asOpt[JsValue].getOrElse(JsString("15m")))
      val identifier = (js \ "identifier").asOpt[String].getOrElse("${req.ip}")
      val maxRetry   = (js \ "max_retry").asOpt[Int].getOrElse(5)
      val regexes    = (js \ "url_regex").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj => RegexRule(obj) }
      val scodes     = (js \ "status_codes").asOpt[Seq[String]].getOrElse(Seq("401", "403", "429", "500-599"))
      val ranges     = parseStatusRanges(scodes).getOrElse(defaultRanges)
      val ignored    = (js \ "ignored").asOpt[Seq[String]].getOrElse(Seq.empty)
      val blocked    = (js \ "blocked").asOpt[Seq[String]].getOrElse(Seq.empty)
      Fail2BanConfig(
        identifier = identifier,
        detectTimeMs = detectMs,
        banTimeMs = banMs,
        maxRetry = maxRetry,
        urlRegex = regexes,
        statusMatchers = ranges,
        ignored = ignored,
        blocked = blocked
      )
    } match {
      case Success(config)    => JsSuccess(config)
      case Failure(exception) => JsError(exception.getMessage)
    }

    override def writes(o: Fail2BanConfig): JsValue = Json.obj(
      "identifier"   -> o.identifier,
      "detect_time"  -> o.detectTimeMs.toMillis,
      "ban_time"     -> o.banTimeMs.toMillis,
      "max_retry"    -> o.maxRetry,
      "url_regex"    -> JsArray(o.urlRegex.map(_.json)),
      "status_codes" -> JsArray(o.statusMatchers.map(_.str.json)),
      "ignored"      -> o.ignored,
      "blocked"      -> o.blocked
    )
  }

  private def parseDurationMillis(v: JsValue): FiniteDuration = (v match {
    case JsNumber(n) => n.toLong
    case JsString(s) =>
      val str = s.trim.toLowerCase
      if (str.endsWith("ms")) str.stripSuffix("ms").trim.toLong
      else if (str.endsWith("s")) str.stripSuffix("s").trim.toLong * 1000
      else if (str.endsWith("m")) str.stripSuffix("m").trim.toLong * 60 * 1000
      else if (str.endsWith("h")) str.stripSuffix("h").trim.toLong * 60 * 60 * 1000
      else Try(str.toLong).getOrElse(60000L)
    case _           => 60000L
  }).millis

  private def parseStatusRanges(items: Seq[String]): Option[Seq[StatusCodeRange]] = Try {
    items.flatMap { s =>
      val t = s.trim
      if (t.contains("-")) {
        val Array(a, b) = t.split("-", 2)
        StatusCodeRange(a.trim.toInt, b.trim.toInt).some
      } else {
        val v = t.toInt
        StatusCodeRange(v, v).some
      }
    }
  }.toOption
}

final class RollingWindowCounter {
  private val count       = new AtomicLong(0L)
  private val windowStart = new AtomicLong(System.currentTimeMillis())

  def increment(nowMs: Long, windowMs: Long): Long = {
    val start = windowStart.get()
    if (nowMs - start >= windowMs) {
      windowStart.set(nowMs)
      count.set(0L)
    }
    count.incrementAndGet()
  }

  def reset(): Unit = count.set(0L)
}

object Fail2BanState {
  // TODO (distributed): replace by Redis-backed counters & bans if you need cluster-wide behavior
  private val counters    = new TrieMap[String, RollingWindowCounter]()
  private val bannedUntil = new TrieMap[String, Long]()

  def counterFor(ip: String): RollingWindowCounter =
    counters.getOrElseUpdate(ip, new RollingWindowCounter())

  def ban(ip: String, untilMs: FiniteDuration): Unit = bannedUntil.put(ip, untilMs.toMillis)

  def isBanned(ip: String, nowMs: Long): Boolean = {
    bannedUntil.get(ip) match {
      case None     => false
      case Some(ts) => {
        if (nowMs > ts) {
          bannedUntil.remove(ip)
          false
        } else {
          true
        }
      }
    }
  }

  def remainingBanSeconds(ip: String, nowMs: Long): Long = {
    bannedUntil.get(ip).map(deadline => math.max(0L, (deadline - nowMs) / 1000L)).getOrElse(0L)
  }
}

class Fail2BanPlugin extends NgAccessValidator with NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Security)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def usesCallbacks: Boolean            = false
  override def transformsRequest: Boolean        = false
  override def transformsResponse: Boolean       = true
  override def transformsError: Boolean          = true

  override def noJsForm: Boolean                           = true
  override def defaultConfigObject: Option[NgPluginConfig] = Fail2BanConfig.default.some
  override def configFlow: Seq[String]                     = Fail2BanConfig.configFlow
  override def configSchema: Option[JsObject]              = Fail2BanConfig.configSchema.some

  override def name: String                = "fail2ban"
  override def description: Option[String] =
    Some(
      "Temporarily bans client when too many failed requests occur within a detection window (fail2ban-like). Client is identified by the 'identifier' that can use the Otoroshi expression language to extract informations like user id, apikey, ip address, etc."
    )

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val conf = ctx
      .cachedConfig(internalName)(Fail2BanConfig.format)
      .getOrElse(Fail2BanConfig.default)
    val ip   = conf.identifier.evaluateEl(ctx.attrs)
    val now  = System.currentTimeMillis()
    if (conf.isIgnored(ip)) {
      NgAccess.NgAllowed.vfuture
    } else if (conf.isBlocked(ip)) {
      NgAccess
        .NgDenied(
          Results.Forbidden(
            Json.obj(
              "error"   -> "blocked",
              "message" -> s"You cant access this resource."
            )
          )
        )
        .vfuture
    } else if (Fail2BanState.isBanned(ip, now)) {
      val remain = Fail2BanState.remainingBanSeconds(ip, now)
      val body   = Json.obj(
        "error"            -> "temporary_ban",
        "message"          -> s"You are temporarily banned due to too many failed requests.",
        "retry_in_seconds" -> remain
      )
      NgAccess.NgDenied(Results.Forbidden(body)).vfuture
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val conf = ctx
      .cachedConfig(internalName)(Fail2BanConfig.format)
      .getOrElse(Fail2BanConfig.default)
    val ip   = conf.identifier.evaluateEl(ctx.attrs)
    if (conf.isIgnored(ip)) {
      Right(ctx.otoroshiResponse).vfuture
    } else if (conf.isBlocked(ip)) {
      Right(ctx.otoroshiResponse).vfuture
    } else {
      val pathAndQuery = ctx.request.thePath
      val status       = ctx.otoroshiResponse.status

      if (conf.isUrlInScope(pathAndQuery) && conf.isFailedStatus(status)) {
        val now     = System.currentTimeMillis()
        val counter = Fail2BanState.counterFor(ip)
        val n       = counter.increment(now, conf.detectTimeMs.toMillis)

        if (n >= conf.maxRetry) {
          Fail2BanState.ban(ip, (now + conf.banTimeMs.toMillis).millis)
          counter.reset()
        }
      }
      Right(ctx.otoroshiResponse).vfuture
    }
  }

  override def transformError(
      ctx: NgTransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    val conf = ctx
      .cachedConfig(internalName)(Fail2BanConfig.format)
      .getOrElse(Fail2BanConfig.default)
    val ip   = conf.identifier.evaluateEl(ctx.attrs)
    if (conf.isIgnored(ip)) {
      ctx.otoroshiResponse.vfuture
    } else if (conf.isBlocked(ip)) {
      ctx.otoroshiResponse.vfuture
    } else {
      val pathAndQuery = ctx.request.thePath
      val status       = ctx.otoroshiResponse.status
      if (conf.isUrlInScope(pathAndQuery) && conf.isFailedStatus(status)) {
        val now     = System.currentTimeMillis()
        val counter = Fail2BanState.counterFor(ip)
        val n       = counter.increment(now, conf.detectTimeMs.toMillis)

        if (n >= conf.maxRetry) {
          Fail2BanState.ban(ip, (now + conf.banTimeMs.toMillis).millis)
          counter.reset()
        }
      }
      ctx.otoroshiResponse.vfuture
    }
  }
}
