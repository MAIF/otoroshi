package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.plugins.geoloc.{IpStackGeolocationHelper, MaxMindGeolocationHelper}
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util._  

case class NgMaxMindGeolocationInfoExtractorConfig(
  path: String = "global",
  log: Boolean = false,
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "path" -> path,
    "log" -> log,
  )
}

object NgMaxMindGeolocationInfoExtractorConfig {
  val format = new Format[NgMaxMindGeolocationInfoExtractorConfig] {
    override def writes(o: NgMaxMindGeolocationInfoExtractorConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgMaxMindGeolocationInfoExtractorConfig] = Try {
      NgMaxMindGeolocationInfoExtractorConfig(
        path = json.select("path").asOpt[String].getOrElse("global"),
        log = json.select("log").asOpt[Boolean].getOrElse(false),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgMaxMindGeolocationInfoExtractor extends NgPreRouting {

  private val logger = Logger("otoroshi-plugins-maxmind-geolocation-info")
  override def name: String = "Geolocation details extractor (using Maxmind db)"
  override def description: Option[String] = "This plugin extract geolocation informations from ip address using the [Maxmind dbs](https://www.maxmind.com/en/geoip2-databases).\nThe informations are store in plugins attrs for other plugins to use".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgMaxMindGeolocationInfoExtractorConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep] = Seq(NgStep.PreRoute)

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val config = ctx.cachedConfig(internalName)(NgMaxMindGeolocationInfoExtractorConfig.format).getOrElse(NgMaxMindGeolocationInfoExtractorConfig())
    val from = ctx.request.theIpAddress
    config.path match {
      case "global" =>
        env.datastores.globalConfigDataStore.latestSafe match {
          case None => Done.rightf
          case Some(c) if !c.geolocationSettings.enabled => Done.rightf
          case Some(c) =>
            c.geolocationSettings.find(from).map {
              case None => Done.right
              case Some(location) => {
                if (config.log) logger.info(s"Ip-Address: $from, ${Json.prettyPrint(location)}")
                ctx.attrs.putIfAbsent(otoroshi.plugins.Keys.GeolocationInfoKey -> location)
                Done.right
              }
            }
        }
      case path =>
        MaxMindGeolocationHelper.find(from, path).map {
          case None => Done.right
          case Some(location) => {
            if (config.log) logger.info(s"Ip-Address: $from, ${Json.prettyPrint(location)}")
            ctx.attrs.putIfAbsent(otoroshi.plugins.Keys.GeolocationInfoKey -> location)
            Done.right
          }
        }
    }
  }
}

case class NgIpStackGeolocationInfoExtractorConfig(
  apikey: Option[String] = None,
  timeout: Long = 2000L,
  log: Boolean = false,
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "apikey" -> apikey.map(JsString.apply).getOrElse(JsNull).asValue,
    "timeout" -> timeout,
    "log" -> log,
  )
}

object NgIpStackGeolocationInfoExtractorConfig {
  val format = new Format[NgIpStackGeolocationInfoExtractorConfig] {
    override def writes(o: NgIpStackGeolocationInfoExtractorConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgIpStackGeolocationInfoExtractorConfig] = Try {
      NgIpStackGeolocationInfoExtractorConfig(
        apikey = json.select("apikey").asOpt[String].map(_.trim).filterNot(_.isEmpty),
        timeout = json.select("timeout").asOpt[Long].getOrElse(2000L),
        log = json.select("log").asOpt[Boolean].getOrElse(false),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgIpStackGeolocationInfoExtractor extends NgPreRouting {

  private val logger = Logger("otoroshi-plugins-ipstack-geolocation-info")
  override def name: String = "Geolocation details extractor (using IpStack api)"
  override def description: Option[String] = "This plugin extract geolocation informations from ip address using the [IpStack dbs](https://ipstack.com/).\nThe informations are store in plugins attrs for other plugins to use".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgIpStackGeolocationInfoExtractorConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep] = Seq(NgStep.PreRoute)

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val config = ctx.cachedConfig(internalName)(NgIpStackGeolocationInfoExtractorConfig.format).getOrElse(NgIpStackGeolocationInfoExtractorConfig())
    val from          = ctx.request.theIpAddress
    config.apikey match {
      case None         => Done.rightf
      case Some(apiKey) =>
        IpStackGeolocationHelper.find(from, apiKey, config.timeout).map {
          case None           => Done.right
          case Some(location) => {
            if (config.log) logger.info(s"Ip-Address: $from, ${Json.prettyPrint(location)}")
            ctx.attrs.putIfAbsent(otoroshi.plugins.Keys.GeolocationInfoKey -> location)
            Done.right
          }
        }
    }
  }
}

case class NgGeolocationInfoHeaderConfig(
  headerName: String = "X-User-Agent-Info",
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "header_name" -> headerName,
  )
}

object NgGeolocationInfoHeaderConfig {
  val format = new Format[NgGeolocationInfoHeaderConfig] {
    override def writes(o: NgGeolocationInfoHeaderConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgGeolocationInfoHeaderConfig] = Try {
      NgGeolocationInfoHeaderConfig(
        headerName = json.select("header_name").asOpt[String].getOrElse("X-User-Agent-Info")
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgGeolocationInfoHeader extends NgRequestTransformer {

  override def name: String = "Geolocation header"
  override def description: Option[String] = "This plugin will send informations extracted by the Geolocation details extractor to the target service in a header.".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgGeolocationInfoHeaderConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(NgGeolocationInfoHeaderConfig.format).getOrElse(NgGeolocationInfoHeaderConfig())
    ctx.attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey) match {
      case None           => Right(ctx.otoroshiRequest).future
      case Some(location) => {
        Right(
          ctx.otoroshiRequest.copy(
            headers = ctx.otoroshiRequest.headers ++ Map(
              config.headerName -> Json.stringify(location)
            )
          )
        ).future
      }
    }
  }
}

class NgGeolocationInfoEndpoint extends NgRequestTransformer {

  override def name: String = "Geolocation endpoint"
  override def description: Option[String] = "This plugin will expose current geolocation informations on the following endpoint `/.well-known/otoroshi/plugins/geolocation`".some
  override def defaultConfigObject: Option[NgPluginConfig] =None
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    (ctx.rawRequest.method.toLowerCase(), ctx.rawRequest.path) match {
      case ("get", "/.well-known/otoroshi/plugins/geolocation") =>
        ctx.attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey) match {
          case None           => Results.NotFound(Json.obj("error" -> "geolocation not found")).leftf
          case Some(location) => Results.Ok(location).leftf
        }
      case _ => ctx.otoroshiRequest.rightf
    }
  }
}
  