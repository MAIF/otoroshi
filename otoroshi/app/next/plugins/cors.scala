package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import otoroshi.el.HeadersExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.CorsSettings
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgCorsSettings(
    allowOrigin: String = "*",
    exposeHeaders: Seq[String] = Seq.empty[String],
    allowHeaders: Seq[String] = Seq.empty[String],
    allowMethods: Seq[String] = Seq.empty[String],
    excludedPatterns: Seq[String] = Seq.empty[String],
    maxAge: Option[FiniteDuration] = None,
    allowCredentials: Boolean = true
) extends NgPluginConfig {
  def json: JsValue             = NgCorsSettings.format.writes(this)
  lazy val legacy: CorsSettings = CorsSettings(
    enabled = true,
    allowOrigin = allowOrigin,
    exposeHeaders = exposeHeaders,
    allowHeaders = allowHeaders,
    allowMethods = allowMethods,
    excludedPatterns = excludedPatterns,
    maxAge = maxAge,
    allowCredentials = allowCredentials
  )
}

object NgCorsSettings {
  def fromLegacy(settings: CorsSettings): NgCorsSettings = NgCorsSettings(
    allowOrigin = settings.allowOrigin,
    exposeHeaders = settings.exposeHeaders,
    allowHeaders = settings.allowHeaders,
    allowMethods = settings.allowMethods,
    excludedPatterns = settings.excludedPatterns,
    maxAge = settings.maxAge,
    allowCredentials = settings.allowCredentials
  )
  val format                                             = new Format[NgCorsSettings] {
    override def reads(json: JsValue): JsResult[NgCorsSettings] = Try {
      NgCorsSettings(
        allowOrigin = (json \ "allow_origin").asOpt[String].getOrElse("*"),
        exposeHeaders = (json \ "expose_headers").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        allowHeaders = (json \ "allow_headers").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        allowMethods = (json \ "allow_methods").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        excludedPatterns = (json \ "excluded_patterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        maxAge = (json \ "max_age").asOpt[Long].map(a => FiniteDuration(a, TimeUnit.SECONDS)),
        allowCredentials = (json \ "allow_credentials").asOpt[Boolean].getOrElse(true)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgCorsSettings): JsValue             = Json.obj(
      "allow_origin"      -> o.allowOrigin,
      "expose_headers"    -> JsArray(o.exposeHeaders.map(_.toLowerCase().trim).map(JsString.apply)),
      "allow_headers"     -> JsArray(o.allowHeaders.map(_.toLowerCase().trim).map(JsString.apply)),
      "allow_methods"     -> JsArray(o.allowMethods.map(JsString.apply)),
      "excluded_patterns" -> JsArray(o.excludedPatterns.map(JsString.apply)),
      "max_age"           -> o.maxAge.map(a => JsNumber(BigDecimal(a.toSeconds))).getOrElse(JsNull).as[JsValue],
      "allow_credentials" -> o.allowCredentials
    )
  }
}

class Cors extends NgRequestTransformer with NgPreRouting {

  private val configReads: Reads[NgCorsSettings] = NgCorsSettings.format

  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def isTransformRequestAsync: Boolean            = true
  override def isTransformResponseAsync: Boolean           = false
  override def isPreRouteAsync: Boolean                    = true
  override def transformsError: Boolean                    = true
  override def name: String                                = "CORS"
  override def description: Option[String]                 = "This plugin applies CORS rules".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgCorsSettings().some
  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val req  = ctx.request
    val cors = ctx.cachedConfig(internalName)(configReads).getOrElse(NgCorsSettings())

    if (req.method == "OPTIONS" && req.headers.get("Access-Control-Request-Method").isDefined) {
      // handle cors preflight request
      if (cors.legacy.shouldNotPass(req)) {
        Errors
          .craftResponseResult(
            "Cors error",
            Results.NotFound,
            ctx.request,
            None,
            "errors.cors.error".some,
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some
          )
          .map(r => Left(NgPreRoutingErrorWithResult(r)))
      } else {
        NgPreRoutingErrorWithResult(
          Results.NoContent
            .withHeaders(cors.legacy.asHeaders(req): _*)
        ).left.vfuture
      }
    } else {
      if (!cors.legacy.shouldNotPass(req)) {
        val corsHeaders = cors.legacy.asHeaders(req)
        ctx.attrs.update(otoroshi.next.plugins.Keys.ResponseAddHeadersKey) {
          case None             => corsHeaders
          case Some(oldHeaders) => oldHeaders ++ corsHeaders
        }
      }
      Done.right.vfuture
    }
  }

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val req         = ctx.request
    val cors = ctx.cachedConfig(internalName)(configReads).getOrElse(NgCorsSettings())

    val corsHeaders = cors
      .legacy
      .asHeaders(req)
      .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
      .map(v =>
        (
          v._1,
          HeadersExpressionLanguage(
            v._2,
            Some(req),
            ctx.route.legacy.some,
            ctx.route.some,
            ctx.apikey,
            ctx.user,
            ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
            ctx.attrs,
            env
          )
        )
      )
      .filterNot(h => h._2 == "null")
    ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers ++ corsHeaders).right
  }

  override def transformError(
      ctx: NgTransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    val req         = ctx.request
    val cors = ctx.cachedConfig(internalName)(configReads).getOrElse(NgCorsSettings())

    val corsHeaders = cors
      .legacy
      .asHeaders(req)
      .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
      .map(v =>
        (
          v._1,
          HeadersExpressionLanguage(
            v._2,
            Some(req),
            ctx.route.legacy.some,
            ctx.route.some,
            ctx.apikey,
            ctx.user,
            ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
            ctx.attrs,
            env
          )
        )
      )
      .filterNot(h => h._2 == "null")
    ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers ++ corsHeaders).vfuture
  }
}
