package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.plugins.log4j.Log4jExpressionParser
import otoroshi.utils.body.BodyUtils
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class NgLog4ShellFilterConfig(
    status: Int = 200,
    body: String = "",
    parseBody: Boolean = false
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "status"     -> status,
    "body"       -> body,
    "parse_body" -> parseBody
  )
}

object NgLog4ShellFilterConfig {
  val format = new Format[NgLog4ShellFilterConfig] {
    override def writes(o: NgLog4ShellFilterConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgLog4ShellFilterConfig] = Try {
      NgLog4ShellFilterConfig(
        status = json.select("status").asOpt[Int].getOrElse(200),
        body = json.select("body").asOpt[String].getOrElse(""),
        parseBody = json.select("parse_body").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgLog4ShellFilter extends NgRequestTransformer {

  private val logger = Logger("otoroshi-plugins-log4shell")

  override def name: String                                = "Log4Shell mitigation plugin"
  override def description: Option[String]                 =
    "This plugin try to detect Log4Shell attacks in request and block them".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgLog4ShellFilterConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Security)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)

  def containsBadValue(value: String): Boolean = {
    if (value.contains("${")) {
      value.toLowerCase().contains("${jndi:rmi://") ||
      value.toLowerCase().contains("${jndi:http://") ||
      value.toLowerCase().contains("${jndi:ldap://") ||
      value.toLowerCase().contains("${jndi:") ||
      Log4jExpressionParser.parseAsExp(value).hasJndi
    } else {
      false
    }
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config           = ctx.cachedConfig(internalName)(NgLog4ShellFilterConfig.format).getOrElse(NgLog4ShellFilterConfig())
    val hasBadHeaders    = ctx.request.headers.toMap.values.flatten.exists(containsBadValue)
    val hasBadMethod     = containsBadValue(ctx.request.method)
    val hasBadPath       = containsBadValue(ctx.request.thePath)
    val hasBadQueryParam = containsBadValue(ctx.request.rawQueryString)
    if (logger.isDebugEnabled)
      logger.debug(
        s"hasBadHeaders: $hasBadHeaders, hasBadMethod: $hasBadMethod, hasBadPath: $hasBadPath, hasBadQueryParam: $hasBadQueryParam"
      )
    if (hasBadHeaders || hasBadMethod || hasBadPath || hasBadQueryParam) {
      Results.Status(config.status)(config.body).as("text/plain").leftf
    } else {
      if (config.parseBody && BodyUtils.hasBody(ctx.request)) {
        ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
          val bodyStr = bodyRaw.utf8String
          if (containsBadValue(bodyStr)) {
            Results.Status(config.status)(config.body).as("text/plain").left
          } else {
            val source = Source(bodyRaw.grouped(32 * 1024).toList)
            ctx.otoroshiRequest.copy(body = source).right
          }
        }
      } else {
        ctx.otoroshiRequest.rightf
      }
    }
  }
}
