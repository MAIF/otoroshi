package otoroshi.next.plugins

import akka.Done
import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.models.Target
import otoroshi.next.plugins.api._
import otoroshi.plugins.discovery.{DiscoveryHelper, SelfRegistrationConfig}
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util._  

  
case class NgDiscoverySelfRegistrationConfig(
  raw: JsValue = Json.obj()
) extends NgPluginConfig {

  override def json: JsValue = raw

  def legacy: SelfRegistrationConfig = SelfRegistrationConfig(raw)

  lazy val hosts: Seq[String] = raw.select("hosts").asOpt[Seq[String]].getOrElse(Seq.empty)
  lazy val targetTemplate: JsObject = raw.select("targetTemplate").asOpt[JsObject]
    .orElse(raw.select("target_template").asOpt[JsObject])
    .getOrElse(Json.obj())
  lazy val registrationTtl: FiniteDuration =
    raw.select("registrationTtl").asOpt[Long]
      .orElse(raw.select("registration_ttl").asOpt[Long])
      .map(_.millis).getOrElse(60.seconds)
}

object NgDiscoverySelfRegistrationConfig {
  val format = new Format[NgDiscoverySelfRegistrationConfig] {
    override def writes(o: NgDiscoverySelfRegistrationConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgDiscoverySelfRegistrationConfig] = Try {
      NgDiscoverySelfRegistrationConfig(json)
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgDiscoverySelfRegistrationSink extends NgRequestSink {

  import kaleidoscope._

  override def name: String = "Global self registration endpoints (service discovery)"
  override def description: Option[String] = "This plugin add support for self registration endpoint on specific hostnames".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgDiscoverySelfRegistrationConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.ServiceDiscovery)
  override def steps: Seq[NgStep] = Seq(NgStep.Sink)

  override def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val config = NgDiscoverySelfRegistrationConfig(ctx.config)
    config.hosts.contains(ctx.request.theDomain)
  }

  override def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val config = NgDiscoverySelfRegistrationConfig(ctx.config)
    (ctx.request.method.toLowerCase(), ctx.request.thePath) match {
      case ("post", "/discovery/_register")                             => DiscoveryHelper.register(None, ctx.body, config.legacy)
      case ("delete", r"/discovery/${registrationId}@(.*)/_unregister") =>
        DiscoveryHelper.unregister(registrationId, None, ctx.request, config.legacy)
      case ("post", r"/discovery/${registrationId}@(.*)/_heartbeat")    =>
        DiscoveryHelper.heartbeat(registrationId, None, ctx.request, config.legacy)
      case _                                                            => Results.NotFound(Json.obj("error" -> "resource not found !")).future
    }
  }
}

class NgDiscoverySelfRegistrationTransformer extends NgRequestTransformer {

  import kaleidoscope._

  override def name: String = "Self registration endpoints (service discovery)"
  override def description: Option[String] = "This plugin add support for self registration endpoint on a specific service".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgDiscoverySelfRegistrationConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.ServiceDiscovery)
  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(NgDiscoverySelfRegistrationConfig.format).getOrElse(NgDiscoverySelfRegistrationConfig())
    (ctx.request.method.toLowerCase(), ctx.request.thePath) match {
      case ("post", "/discovery/_register")                             =>
        DiscoveryHelper.register(ctx.route.id.some, ctx.otoroshiRequest.body, config.legacy).map(r => Left(r))
      case ("delete", r"/discovery/${registrationId}@(.*)/_unregister") =>
        DiscoveryHelper.unregister(registrationId, ctx.route.id.some, ctx.request, config.legacy).map(r => Left(r))
      case ("post", r"/discovery/${registrationId}@(.*)/_heartbeat")    =>
        DiscoveryHelper.heartbeat(registrationId, ctx.route.id.some, ctx.request, config.legacy).map(r => Left(r))
      case _                                                            => Right(ctx.otoroshiRequest).future
    }
  }
}

class NgDiscoveryTargetsSelector extends NgPreRouting {

  override def name: String = "Service discovery target selector (service discovery)"
  override def description: Option[String] = "This plugin select a target in the pool of discovered targets for this service.\nUse in combination with either `DiscoverySelfRegistrationSink` or `DiscoverySelfRegistrationTransformer` to make it work using the `self registration` pattern.\nOr use an implementation of `DiscoveryJob` for the `third party registration pattern`.".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgDiscoverySelfRegistrationConfig().some
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.ServiceDiscovery)
  override def steps: Seq[NgStep] = Seq(NgStep.PreRoute)

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val config = ctx.cachedConfig(internalName)(NgDiscoverySelfRegistrationConfig.format).getOrElse(NgDiscoverySelfRegistrationConfig())
    DiscoveryHelper.getTargetsFor(ctx.route.id, config.legacy).map {
      case targets if targets.isEmpty => Done.right
      case _targets                   => {
        val reqNumber            = ctx.attrs.get(otoroshi.plugins.Keys.RequestNumberKey).getOrElse(0)
        val trackingId           = ctx.attrs.get(otoroshi.plugins.Keys.RequestTrackingIdKey).getOrElse("none")
        val targets: Seq[Target] = _targets
          .map(_._2)
          .filter(_.predicate.matches(reqNumber.toString, ctx.request, ctx.attrs))
          .flatMap(t => Seq.fill(t.weight)(t))
        val target               = ctx.route.backend.loadBalancing
          .select(
            reqNumber.toString,
            trackingId,
            ctx.request,
            targets,
            ctx.route.id
          )
        ctx.attrs.put(otoroshi.plugins.Keys.PreExtractedRequestTargetKey -> target)
        Done.right
      }
    }
  }
}
  