package otoroshi.next.plugins

import akka.Done
import akka.http.scaladsl.model.HttpProtocols
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.models.{AlwaysMatch, Canary}
import otoroshi.next.models.{NgBackend, NgTarget}
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// TODO: NgBackend instead of NgTarget ?
case class NgCanarySettings(
    traffic: Double = 0.2,
    targets: Seq[NgTarget] = Seq.empty[NgTarget],
    root: String = "/"
) extends NgPluginConfig {
  def json: JsValue       = NgCanarySettings.format.writes(this)
  lazy val legacy: Canary = Canary(
    enabled = true,
    traffic = traffic,
    targets = targets.map(_.toTarget),
    root = root
  )
}

object NgCanarySettings {
  def fromLegacy(settings: Canary): NgCanarySettings = NgCanarySettings(
    traffic = settings.traffic,
    targets = settings.targets.map(t => NgTarget.fromLegacy(t)),
    root = settings.root
  )
  val format                                         = new Format[NgCanarySettings] {
    override def reads(json: JsValue): JsResult[NgCanarySettings] =
      Try {
        NgCanarySettings(
          traffic = (json \ "traffic").asOpt[Double].getOrElse(0.2),
          targets = (json \ "targets")
            .asOpt[JsArray]
            .map(_.value.map(e => NgTarget.readFrom(e)))
            .getOrElse(Seq.empty[NgTarget]),
          root = (json \ "root").asOpt[String].getOrElse("/")
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(c) => JsSuccess(c)
      }

    override def writes(o: NgCanarySettings): JsValue =
      Json.obj(
        "traffic" -> o.traffic,
        "targets" -> JsArray(o.targets.map(_.json)),
        "root"    -> o.root
      )
  }
}

class CanaryMode extends NgPreRouting with NgRequestTransformer {

  private val logger                               = Logger("otoroshi-next-plugins-canary-mode")
  private val configReads: Reads[NgCanarySettings] = NgCanarySettings.format

  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def name: String                                = "Canary mode"
  override def description: Option[String]                 = "This plugin can split a portion of the traffic to canary backends".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgCanarySettings().some

  override def isPreRouteAsync: Boolean          = true
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = false

  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val config     = ctx.cachedConfig(internalName)(configReads).getOrElse(NgCanarySettings())
    val gconfig    = env.datastores.globalConfigDataStore.latest()
    val reqNumber  = ctx.attrs.get(otoroshi.plugins.Keys.RequestNumberKey).get
    val trackingId = ctx.attrs.get(otoroshi.plugins.Keys.RequestCanaryIdKey).getOrElse {
      val maybeCanaryId: Option[String] = ctx.request.cookies
        .get("otoroshi-canary")
        .map(_.value)
        .orElse(ctx.request.headers.get(env.Headers.OtoroshiTrackerId))
        .filter { value =>
          if (value.contains("::")) {
            value.split("::").toList match {
              case signed :: id :: Nil if env.sign(id) == signed => true
              case _                                             => false
            }
          } else {
            false
          }
        } map (value => value.split("::")(1))
      val canaryId: String              = maybeCanaryId.getOrElse(IdGenerator.uuid + "-" + reqNumber)
      ctx.attrs.put(otoroshi.plugins.Keys.RequestCanaryIdKey -> canaryId)
      if (maybeCanaryId.isDefined) {
        logger.debug(s"request already has canary id : $canaryId")
      } else {
        logger.debug(s"request has a new canary id : $canaryId")
      }
      canaryId
    }
    env.datastores.canaryDataStore
      .isCanary(ctx.route.cacheableId, trackingId, config.traffic, reqNumber, gconfig)
      .fast
      .map {
        case false => Right(Done)
        case true  =>
          val backends = NgBackend(
            targets = config.targets,
            targetRefs = Seq.empty,
            root = config.root,
            rewrite = false,
            loadBalancing = ctx.route.backend.loadBalancing,
            client = ctx.route.backend.client
          )
          ctx.attrs.put(otoroshi.next.plugins.Keys.PossibleBackendsKey -> backends)
          Right(Done)
      }
  }

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    ctx.attrs.get(otoroshi.plugins.Keys.RequestCanaryIdKey) match {
      case None           => ctx.otoroshiResponse.right
      case Some(canaryId) => {
        val cookie = DefaultWSCookie(
          name = "otoroshi-canary",
          value = s"${env.sign(canaryId)}::$canaryId",
          maxAge = Some(2592000),
          path = "/".some,
          domain = ctx.request.theDomain.some,
          httpOnly = false
        )
        ctx.otoroshiResponse.copy(cookies = ctx.otoroshiResponse.cookies ++ Seq(cookie)).right
      }
    }
  }
}
