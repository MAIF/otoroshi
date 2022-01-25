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

case class JwtVerificationConfig(verifiers: Seq[String] = Seq.empty) {
  def json: JsValue = JwtVerificationConfig.format.writes(this)
}

object JwtVerificationConfig {
  val format = new Format[JwtVerificationConfig] {
    override def reads(json: JsValue): JsResult[JwtVerificationConfig] = Try {
      JwtVerificationConfig(
        verifiers = json.select("verifiers").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: JwtVerificationConfig): JsValue = Json.obj("verifiers" -> o.verifiers)
  }
}

class JwtVerification extends NgAccessValidator with NgRequestTransformer {

  private val configReads: Reads[JwtVerificationConfig] = JwtVerificationConfig.format

  override def core: Boolean = true
  override def usesCallbacks: Boolean = false
  override def name: String = "Jwt verifiers"
  override def description: Option[String] = "This plugin verifies the current request with one or more jwt verifier".some
  override def defaultConfig: Option[JsObject] = JwtVerificationConfig().json.asObject.some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    // val verifiers = ctx.config.select("verifiers").asOpt[Seq[String]].getOrElse(Seq.empty)
    val JwtVerificationConfig(verifiers) = ctx.cachedConfig(internalName)(configReads).getOrElse(JwtVerificationConfig())
    if (verifiers.nonEmpty) {
      val verifier = RefJwtVerifier(verifiers, true, Seq.empty)
      val promise = Promise[NgAccess]()
      verifier.verifyFromCache(
        request = ctx.request,
        desc = ctx.route.serviceDescriptor.some,
        apikey = ctx.apikey,
        user = ctx.user,
        elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
        attrs = ctx.attrs
      ).map {
        case Left(result) => promise.trySuccess(NgAccess.NgDenied(result))
        case Right(injection) =>
          ctx.attrs.put(JwtInjectionKey -> injection)
          promise.trySuccess(NgAccess.NgAllowed)
      }
      promise.future
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    ctx.attrs.get(JwtInjectionKey) match {
      case None => ctx.otoroshiRequest.right.vfuture
      case Some(injection) => {
        ctx.otoroshiRequest
          .applyOnIf(injection.removeCookies.nonEmpty) { req => req.copy(cookies = req.cookies.filterNot(c => injection.removeCookies.contains(c.name))) }
          .applyOnIf(injection.removeHeaders.nonEmpty) { req => req.copy(headers = req.headers.filterNot(tuple => injection.removeHeaders.map(_.toLowerCase).contains(tuple._1.toLowerCase))) }
          .applyOnIf(injection.additionalHeaders.nonEmpty) { req => req.copy(headers = req.headers ++ injection.additionalHeaders) }
          .applyOnIf(injection.additionalCookies.nonEmpty) { req => req.copy(cookies = req.cookies ++ injection.additionalCookies.map(t => DefaultWSCookie(t._1, t._2))) }
          .right
          .vfuture
      }
    }
  }
}
