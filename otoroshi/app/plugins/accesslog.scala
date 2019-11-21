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

class AccessLog extends RequestTransformer {

  private val logger = Logger("otoroshi-plugins-access-log")

  override def name: String = "Access log (CLF)"

  override def description: Option[String] = Some(
    """With this plugin, any access to a service will be logged in CLF format.
      |
      |The plugin accepts the following configuration
      |
      |```json
      |{
      |  "AccessLog": {
      |    "statuses": [ ... ], // list of status to enable logs, if none, log everything
      |    "paths": [ ... ], // list of paths to enable logs, if none, log everything
      |    "methods": [ ... ] // list of http methods to enable logs, if none, log everything
      |  }
      |}
      |```
    """.stripMargin)

  override def transformResponseWithCtx(ctx: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {

    val status = ctx.rawResponse.status
    val path = ctx.request.relativeUri
    val method = ctx.request.method

    val (matchPath, methodMatch, statusMatch) = if ((ctx.config \ "AccessLog").isDefined) {
      val validPaths: Seq[String] = (ctx.config \ "AccessLog" \ "paths").asOpt[Seq[String]].getOrElse(Seq.empty)
      val validStatuses: Seq[Int] = (ctx.config \ "AccessLog" \ "statuses").asOpt[Seq[Int]].getOrElse(Seq.empty)
      val validMethods: Seq[String] = (ctx.config \ "AccessLog" \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)

      val matchPath = if (validPaths.isEmpty) true else validPaths.exists(p => RegexPool.regex(p).matches(path))
      val methodMatch = if (validMethods.isEmpty) true else validMethods.map(_.toLowerCase()).contains(method.toLowerCase())
      val statusMatch = if (validStatuses.isEmpty) true else validStatuses.contains(status)

      (matchPath, methodMatch, statusMatch)
    } else {
      (true, true, true)
    }

    if (matchPath && methodMatch && statusMatch) {
      val ipAddress = ctx.request.theIpAddress
      val userId = ctx.user.map(_.name).orElse(ctx.apikey.map(_.clientName)).getOrElse("-")
      val timestamp = ctx.attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).getOrElse(DateTime.now()).toString("yyyy-MM-dd HH:mm:ss.SSS z")
      val duration = ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).map(v => System.currentTimeMillis() - v).getOrElse(0L)
      val http = ctx.request.theProtocol
      val protocol = ctx.request.version
      val size = ctx.rawResponse.headers.get("Content-Length").orElse(ctx.rawResponse.headers.get("content-length")).getOrElse("-")
      val referrer = ctx.rawResponse.headers.get("Referrer").orElse(ctx.rawResponse.headers.get("Referrer")).getOrElse("-")
      val userAgent = ctx.request.headers.get("User-Agent").orElse(ctx.rawResponse.headers.get("user-agent")).getOrElse("-")
      val service = ctx.descriptor.name
      logger.info(s""""$service" $ipAddress - $userId [$timestamp] "$method $path $protocol" $status $size ${ctx.snowflake} "$referrer" "$userAgent" $http ${duration}ms""")
    }
    Right(ctx.otoroshiResponse).future
  }
}
