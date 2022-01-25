package otoroshi.next.plugins

import akka.Done
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.models.Canary
import otoroshi.next.models.{NgTarget, Backend}
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsObject, Reads}
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

class CanaryMode extends NgPreRouting with NgRequestTransformer {

  private val logger = Logger("otoroshi-next-plugins-canary-mode")
  private val configReads: Reads[Canary] = Canary.format
  override def core: Boolean = true
  override def usesCallbacks: Boolean = false
  override def name: String = "Canary mode"
  override def description: Option[String] = "This plugin can split a portion of the traffic to canary backends".some
  override def defaultConfig: Option[JsObject] = Canary().toJson.asObject.-("enabled")some

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(Canary(enabled = true))
    val gconfig = env.datastores.globalConfigDataStore.latest()
    val reqNumber = ctx.attrs.get(otoroshi.plugins.Keys.RequestNumberKey).get
    val trackingId = ctx.attrs.get(otoroshi.plugins.Keys.RequestCanaryIdKey).getOrElse {
      val maybeCanaryId: Option[String] = ctx.request.cookies
        .get("otoroshi-canary")
        .map(_.value)
        .orElse(ctx.request.headers.get(env.Headers.OtoroshiTrackerId))
        .filter { value =>
          if (value.contains("::")) {
            value.split("::").toList match {
              case signed :: id :: Nil if env.sign(id) == signed => true
              case _ => false
            }
          } else {
            false
          }
        } map (value => value.split("::")(1))
      val canaryId: String = maybeCanaryId.getOrElse(IdGenerator.uuid + "-" + reqNumber)
      ctx.attrs.put(otoroshi.plugins.Keys.RequestCanaryIdKey -> canaryId)
      if (maybeCanaryId.isDefined) {
        logger.debug(s"request already has canary id : $canaryId")
      } else {
        logger.debug(s"request has a new canary id : $canaryId")
      }
      canaryId
    }
    env.datastores.canaryDataStore.isCanary(ctx.route.id, trackingId, config.traffic, reqNumber, gconfig).fast.map {
      case false => Right(Done)
      case true  =>
        val backends = Backend(
          targets = config.targets.map(NgTarget.fromTarget),
          targetRefs = Seq.empty,
          root = config.root,
          loadBalancing = ctx.route.backend.loadBalancing,
        )
        ctx.attrs.put(otoroshi.next.plugins.Keys.PossibleBackendsKey -> backends)
        Right(Done)
    }
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    ctx.attrs.get(otoroshi.plugins.Keys.RequestCanaryIdKey) match {
      case None => ctx.otoroshiResponse.right.vfuture
      case Some(canaryId) => {
        val cookie = DefaultWSCookie(
          name = "otoroshi-canary",
          value = s"${env.sign(canaryId)}::$canaryId",
          maxAge = Some(2592000),
          path = "/".some,
          domain = ctx.request.theDomain.some,
          httpOnly = false
        )
        ctx.otoroshiResponse.copy(cookies = ctx.otoroshiResponse.cookies ++ Seq(cookie)).right.vfuture
      }
    }
  }
}
