package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.models.RefJwtVerifier
import otoroshi.next.plugins.Keys.JwtInjectionKey
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue, BetterSyntax}
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

case class NgJwtVerificationConfig(verifiers: Seq[String] = Seq.empty) extends NgPluginConfig {
  def json: JsValue = NgJwtVerificationConfig.format.writes(this)
}

object NgJwtVerificationConfig {
  val format = new Format[NgJwtVerificationConfig] {
    override def reads(json: JsValue): JsResult[NgJwtVerificationConfig] = Try {
      NgJwtVerificationConfig(
        verifiers = json.select("verifiers").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJwtVerificationConfig): JsValue             = Json.obj("verifiers" -> o.verifiers)
  }
}

class JwtVerification extends NgAccessValidator with NgRequestTransformer {

  private val configReads: Reads[NgJwtVerificationConfig] = NgJwtVerificationConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess, NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Standard)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def usesCallbacks: Boolean      = false
  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false

  override def isAccessAsync: Boolean                      = true
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Jwt verifiers"
  override def description: Option[String]                 =
    "This plugin verifies the current request with one or more jwt verifier".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgJwtVerificationConfig().some

  override def configSchema: Option[JsObject] = Json
    .obj(
      "verifiers" -> Json.obj(
        "format"      -> "select",
        "array"       -> false,
        "type"        -> "string",
        "isMulti"     -> true,
        "optionsFrom" -> "/bo/api/proxy/api/verifiers",
        "transformer" -> Json.obj("value" -> "id", "label" -> "name")
      )
    )
    .some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    // val verifiers = ctx.config.select("verifiers").asOpt[Seq[String]].getOrElse(Seq.empty)
    val NgJwtVerificationConfig(verifiers) =
      ctx.cachedConfig(internalName)(configReads).getOrElse(NgJwtVerificationConfig())
    if (verifiers.nonEmpty) {
      val verifier = RefJwtVerifier(verifiers, true, Seq.empty)
      if (verifier.isAsync) {
        val promise = Promise[NgAccess]()
        verifier
          .verifyFromCache(
            request = ctx.request,
            desc = ctx.route.serviceDescriptor.some,
            apikey = ctx.apikey,
            user = ctx.user,
            elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
            attrs = ctx.attrs
          )
          .map {
            case Left(result)     => promise.trySuccess(NgAccess.NgDenied(result))
            case Right(injection) =>
              ctx.attrs.put(JwtInjectionKey -> injection)
              promise.trySuccess(NgAccess.NgAllowed)
          }
        promise.future
      } else {
        verifier.verifyFromCacheSync(
          request = ctx.request,
          desc = ctx.route.serviceDescriptor.some,
          apikey = ctx.apikey,
          user = ctx.user,
          elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          attrs = ctx.attrs
        ) match {
          case Left(result)     => NgAccess.NgDenied(result).vfuture
          case Right(injection) =>
            ctx.attrs.put(JwtInjectionKey -> injection)
            NgAccess.NgAllowed.vfuture
        }
      }
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    ctx.attrs.get(JwtInjectionKey) match {
      case None            => ctx.otoroshiRequest.right
      case Some(injection) => {
        ctx.otoroshiRequest
          .applyOnIf(injection.removeCookies.nonEmpty) { req =>
            req.copy(cookies = req.cookies.filterNot(c => injection.removeCookies.contains(c.name)))
          }
          .applyOnIf(injection.removeHeaders.nonEmpty) { req =>
            req.copy(headers =
              req.headers.filterNot(tuple => injection.removeHeaders.map(_.toLowerCase).contains(tuple._1.toLowerCase))
            )
          }
          .applyOnIf(injection.additionalHeaders.nonEmpty) { req =>
            req.copy(headers = req.headers ++ injection.additionalHeaders)
          }
          .applyOnIf(injection.additionalCookies.nonEmpty) { req =>
            req.copy(cookies = req.cookies ++ injection.additionalCookies.map(t => DefaultWSCookie(t._1, t._2)))
          }
          .right
      }
    }
  }
}
