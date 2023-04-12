package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util._  

case class NgUserAgentExtractorConfig(
  log: Boolean = false,
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "log" -> log
  )
}

object NgUserAgentExtractorConfig {
  val format = new Format[NgUserAgentExtractorConfig] {
    override def writes(o: NgUserAgentExtractorConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgUserAgentExtractorConfig] = Try {
      NgUserAgentExtractorConfig(
        log = json.select("log").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgUserAgentExtractor extends NgPreRouting {

  private val logger = Logger("otoroshi-plugins-user-agent-helper")
  override def name: String = "User-Agent details extractor"
  override def description: Option[String] = "This plugin extract informations from User-Agent header such as browsser version, OS version, etc.\nThe informations are store in plugins attrs for other plugins to use".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgUserAgentExtractorConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep] = Seq(NgStep.PreRoute)

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    ctx.request.headers.get("User-Agent") match {
      case None     => Done.rightf
      case Some(ua) =>
        otoroshi.plugins.useragent.UserAgentHelper.userAgentDetails(ua) match {
          case None       => Done.rightf
          case Some(info) => {
            val config = ctx.cachedConfig(internalName)(NgUserAgentExtractorConfig.format).getOrElse(NgUserAgentExtractorConfig())
            if (config.log) logger.info(s"User-Agent: $ua, ${Json.prettyPrint(info)}")
            ctx.attrs.putIfAbsent(otoroshi.plugins.Keys.UserAgentInfoKey -> info)
            Done.rightf
          }
        }
    }
  }
}

class NgUserAgentInfoEndpoint extends NgRequestTransformer {

  override def name: String = "User-Agent endpoint"
  override def description: Option[String] = "This plugin will expose current user-agent informations on the following endpoint: /.well-known/otoroshi/plugins/user-agent".some
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl)
  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    (ctx.rawRequest.method.toLowerCase(), ctx.rawRequest.path) match {
      case ("get", "/.well-known/otoroshi/plugins/user-agent") =>
        ctx.attrs.get(otoroshi.plugins.Keys.UserAgentInfoKey) match {
          case None => Right(ctx.otoroshiRequest).future
          case Some(location) => Left(Results.Ok(location)).future
        }
      case _ => Right(ctx.otoroshiRequest).future
    }
  }
}

case class NgUserAgentInfoHeaderConfig(
  headerName: String = "X-User-Agent-Info",
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "header_name" -> headerName,
  )
}

object NgUserAgentInfoHeaderConfig {
  val format = new Format[NgUserAgentInfoHeaderConfig] {
    override def writes(o: NgUserAgentInfoHeaderConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgUserAgentInfoHeaderConfig] = Try {
      NgUserAgentInfoHeaderConfig(
        headerName = json.select("header_name").asOpt[String].getOrElse("X-User-Agent-Info")
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgUserAgentInfoHeader extends NgRequestTransformer {

  override def name: String = "User-Agent header"
  override def description: Option[String] = "This plugin will sent informations extracted by the User-Agent details extractor to the target service in a header".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgUserAgentInfoHeaderConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers)
  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(NgUserAgentInfoHeaderConfig.format).getOrElse(NgUserAgentInfoHeaderConfig())
    ctx.attrs.get(otoroshi.plugins.Keys.UserAgentInfoKey) match {
      case None       => Right(ctx.otoroshiRequest).future
      case Some(info) => {
        Right(
          ctx.otoroshiRequest.copy(
            headers = ctx.otoroshiRequest.headers ++ Map(
              config.headerName -> Json.stringify(info)
            )
          )
        ).future
      }
    }
  }
}
  