package otoroshi.plugins.accesslog

import akka.stream.Materializer
import env.Env
import org.joda.time.DateTime
import otoroshi.script.{HttpResponse, RequestTransformer, TransformerResponseContext}
import play.api.Logger
import play.api.mvc.Result
import utils.RegexPool
import utils.RequestImplicits._
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}

object AccessLog {
  val statusNames = Map(
    100 -> "Continue",
    101 -> "Switching Protocols",
    102 -> "Processing",
    200 -> "OK",
    201 -> "Created",
    202 -> "Accepted",
    203 -> "Non-authoritative Information",
    204 -> "No Content",
    205 -> "Reset Content",
    206 -> "Partial Content",
    207 -> "Multi-Status",
    208 -> "Already Reported",
    226 -> "IM Used",
    300 -> "Multiple Choices",
    301 -> "Moved Permanently",
    302 -> "Found",
    303 -> "See Other",
    304 -> "Not Modified",
    305 -> "Use Proxy",
    307 -> "Temporary Redirect",
    308 -> "Permanent Redirect",
    400 -> "Bad Request",
    401 -> "Unauthorized",
    402 -> "Payment Required",
    403 -> "Forbidden",
    404 -> "Not Found",
    405 -> "Method Not Allowed",
    406 -> "Not Acceptable",
    407 -> "Proxy Authentication Required",
    408 -> "Request Timeout",
    409 -> "Conflict",
    410 -> "Gone",
    411 -> "Length Required",
    412 -> "Precondition Failed",
    413 -> "Payload Too Large",
    414 -> "Request-URI Too Long",
    415 -> "Unsupported Media Type",
    416 -> "Requested Range Not Satisfiable",
    417 -> "Expectation Failed",
    418 -> "I'm a teapot",
    421 -> "Misdirected Request",
    422 -> "Unprocessable Entity",
    423 -> "Locked",
    424 -> "Failed Dependency",
    426 -> "Upgrade Required",
    428 -> "Precondition Required",
    429 -> "Too Many Requests",
    431 -> "Request Header Fields Too Large",
    444 -> "Connection Closed Without Response",
    451 -> "Unavailable For Legal Reasons",
    499 -> "Client Closed Request",
    500 -> "Internal Server Error",
    501 -> "Not Implemented",
    502 -> "Bad Gateway",
    503 -> "Service Unavailable",
    504 -> "Gateway Timeout",
    505 -> "HTTP Version Not Supported",
    506 -> "Variant Also Negotiates",
    507 -> "Insufficient Storage",
    508 -> "Loop Detected",
    510 -> "Not Extended",
    511 -> "Network Authentication Required",
    599 -> "Network Connect Timeout Error"
  )
}

class AccessLog extends RequestTransformer {

  private val logger = Logger("otoroshi-plugins-access-log")

  override def name: String = "Access log (CLF)"

  override def description: Option[String] = Some(
    """With this plugin, any access to a service will be logged in CLF format.
      |
      |Log format is the following:
      |
      |`"$service" $clientAddress - $userId [$timestamp] "$host $method $path $protocol" "$status $statusTxt" $size $snowflake "$to" "$referrer" "$userAgent" $http $duration`
      |
      |The plugin accepts the following configuration
      |
      |```json
      |{
      |  "AccessLog": {
      |    "enabled": true,
      |    "statuses": [], // list of status to enable logs, if none, log everything
      |    "paths": [], // list of paths to enable logs, if none, log everything
      |    "methods": [], // list of http methods to enable logs, if none, log everything
      |    "identities": [] // list of identities to enable logs, if none, log everything
      |  }
      |}
      |```
    """.stripMargin)

  override def transformResponseWithCtx(ctx: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {

    val snowflake = ctx.snowflake
    val status = ctx.rawResponse.status
    val statusTxt = AccessLog.statusNames.getOrElse(status, "-")
    val path = ctx.request.relativeUri
    val method = ctx.request.method
    val userId = ctx.user.map(_.name).orElse(ctx.apikey.map(_.clientName)).getOrElse("-")

    val (matchPath, methodMatch, statusMatch, identityMatch, enabled) = if ((ctx.config \ "AccessLog").isDefined) {
      val enabled: Boolean = (ctx.config \ "AccessLog" \ "enabled").asOpt[Boolean].getOrElse(true)
      val validPaths: Seq[String] = (ctx.config \ "AccessLog" \ "paths").asOpt[Seq[String]].getOrElse(Seq.empty)
      val validStatuses: Seq[Int] = (ctx.config \ "AccessLog" \ "statuses").asOpt[Seq[Int]].getOrElse(Seq.empty)
      val validMethods: Seq[String] = (ctx.config \ "AccessLog" \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
      val validIdentities: Seq[String] = (ctx.config \ "AccessLog" \ "identities").asOpt[Seq[String]].getOrElse(Seq.empty)

      val matchPath = if (validPaths.isEmpty) true else validPaths.exists(p => RegexPool.regex(p).matches(path))
      val methodMatch = if (validMethods.isEmpty) true else validMethods.map(_.toLowerCase()).contains(method.toLowerCase())
      val statusMatch = if (validStatuses.isEmpty) true else validStatuses.contains(status)
      val identityMatch = if (validIdentities.isEmpty) true else validStatuses.contains(userId)

      (matchPath, methodMatch, statusMatch, identityMatch, enabled)
    } else {
      (true, true, true, true, true)
    }

    if (matchPath && methodMatch && statusMatch && identityMatch && enabled) {
      val ipAddress = ctx.request.theIpAddress
      val timestamp = ctx.attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).getOrElse(DateTime.now()).toString("yyyy-MM-dd HH:mm:ss.SSS z")
      val duration = ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).map(v => System.currentTimeMillis() - v).getOrElse(0L)
      val to = ctx.attrs.get(otoroshi.plugins.Keys.RequestTargetKey).map(_.asTargetStr).getOrElse("-")
      val http = ctx.request.theProtocol
      val protocol = ctx.request.version
      val size = ctx.rawResponse.headers.get("Content-Length").orElse(ctx.rawResponse.headers.get("content-length")).getOrElse("-")
      val referrer = ctx.rawResponse.headers.get("Referrer").orElse(ctx.rawResponse.headers.get("Referrer")).getOrElse("-")
      val userAgent = ctx.request.headers.get("User-Agent").orElse(ctx.rawResponse.headers.get("user-agent")).getOrElse("-")
      val service = ctx.descriptor.name
      val host = ctx.request.host
      logger.info(s""""$service" $ipAddress - $userId [$timestamp] "$host $method $path $protocol" "$status $statusTxt" $size $snowflake $to "$referrer" "$userAgent" $http ${duration}ms""")
    }
    Right(ctx.otoroshiResponse).future
  }
}
