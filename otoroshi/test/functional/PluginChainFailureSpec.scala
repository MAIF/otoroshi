package functional

import org.apache.pekko.Done
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.api.*
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Result

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

// plugins throwing when their config says so, the way a sync plugin with a bug throws: the default async method of a
// plugin calls the sync one, so the exception comes out of the call of the plugin, not as a failed future. When they do
// not throw, they answer later, like a plugin doing io: the next plugin of the chain is then called from a callback
object ThrowingPlugins {
  def throws(config: JsValue, phase: String): Boolean = (config \ "throws").asOpt[String].contains(phase)
  def fail(phase: String): Nothing                    = throw new RuntimeException(s"plugin throwing in $phase")
  def later[A](value: => A)(using env: Env, ec: ExecutionContext): Future[A] =
    org.apache.pekko.pattern.after(20.millis, env.otoroshiScheduler)(Future.successful(value))
}

class ThrowingPreRoute extends NgPreRouting {
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory]           = Seq.empty
  override def steps: Seq[NgStep]                          = Seq(NgStep.PreRoute)
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def preRoute(
      ctx: NgPreRoutingContext
  )(using env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    if (ThrowingPlugins.throws(ctx.config, "pre-route")) ThrowingPlugins.fail("pre-route")
    ThrowingPlugins.later(NgPreRouting.done)
  }
}

class ThrowingAccessValidator extends NgAccessValidator {
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory]           = Seq.empty
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    if (ThrowingPlugins.throws(ctx.config, "access")) ThrowingPlugins.fail("access")
    ThrowingPlugins.later(NgAccess.NgAllowed)
  }
}

class ThrowingTransformer extends NgRequestTransformer {
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory]           = Seq.empty
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def transformsError: Boolean                    = false

  override def beforeRequest(
      ctx: NgBeforeRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    if (ThrowingPlugins.throws(ctx.config, "before-request")) ThrowingPlugins.fail("before-request")
    ThrowingPlugins.later(())
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    if (ThrowingPlugins.throws(ctx.config, "transform-request")) ThrowingPlugins.fail("transform-request")
    ThrowingPlugins.later(Right(ctx.otoroshiRequest))
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    if (ThrowingPlugins.throws(ctx.config, "transform-response")) ThrowingPlugins.fail("transform-response")
    ThrowingPlugins.later(Right(ctx.otoroshiResponse))
  }
}

// a plugin throwing instead of returning a failed future fails the request at once with the error of its phase,
// whatever its position in the chain of plugins (#2920). sbt "testOnly functional.PluginChainFailureSpec"
class PluginChainFailureSpec extends PluginsTestSpecBase {

  "A plugin throwing" should {

    "fail the request at once whatever its position in the chain" in {
      val echo = TargetService
        .jsonFull(None, "/", (r: HttpRequest) => (200, Json.obj("path" -> r.uri.path.toString), List.empty))
        .await()

      // (phase, plugin of the phase, error of the phase)
      val phases    = Seq(
        ("pre-route", NgPluginHelper.pluginId[ThrowingPreRoute], "pre-routing"),
        ("access", NgPluginHelper.pluginId[ThrowingAccessValidator], "access"),
        ("before-request", NgPluginHelper.pluginId[ThrowingTransformer], "before-request"),
        ("transform-request", NgPluginHelper.pluginId[ThrowingTransformer], "request-transformation"),
        ("transform-response", NgPluginHelper.pluginId[ThrowingTransformer], "response-transformation")
      )
      // (position, plugins of the chain as throwing or not)
      val positions = Seq(("alone", Seq(true)), ("first", Seq(true, false)), ("second", Seq(false, true)))

      val results = for {
        (phase, plugin, error) <- phases
        (position, chain)      <- positions
      } yield {
        val base  = NgFakeRoute.route(s"plugin-chain-$phase-$position")
        val route = base.copy(
          frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"${base.id}.oto.tools"))),
          backend = base.backend.copy(
            targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = echo.port, tls = false)),
            root = "/"
          ),
          plugins = NgPlugins(chain.map { throws =>
            NgPluginInstance(
              plugin = plugin,
              config = NgPluginInstanceConfig(Json.obj("throws" -> (if (throws) phase else "none")))
            )
          })
        )
        createOtoroshiRoute(route).futureValue
        val start  = System.currentTimeMillis()
        // a hanging request is reported as a 0 status, the other cases still run
        val resp   = Try(
          Await.result(
            ws.url(s"http://127.0.0.1:$port/hello")
              .withHttpHeaders("Host" -> s"${route.id}.oto.tools")
              .withRequestTimeout(15.seconds)
              .get(),
            20.seconds
          )
        ).toOption
        val took   = System.currentTimeMillis() - start
        val status = resp.map(_.status).getOrElse(0)
        val body   = resp.map(_.body).getOrElse("")
        deleteOtoroshiRoute(route).futureValue
        logger.info(s"plugin-chain $phase $position => $status in $took ms")
        (phase, position, status, took, body.contains(s"an error happened during $error plugins phase"))
      }
      echo.stop()
      results.filterNot { case (_, _, status, took, phaseError) => status == 500 && took < 5000 && phaseError } mustBe empty
    }
  }
}
