package plugins

import akka.stream.scaladsl.Source
import com.typesafe.config.ConfigFactory
import functional.{PluginsTestSpec, TargetService}
import otoroshi.api.Otoroshi
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgTarget}
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.tunnel.{TunnelPlugin, TunnelPluginConfig}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.Configuration
import play.api.libs.json.JsObject
import play.core.server.ServerConfig
import play.mvc.Http.Status

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

class RemoteTunnelCallsTests(parent: PluginsTestSpec) {

  import parent._

  case class OtoroshiInstance(port: Int, configuration: String) {
    private val ref: AtomicReference[Otoroshi] = new AtomicReference[Otoroshi]()
    def stop() = {
      ref.get().stop()
      Source
        .tick(1.millisecond, 1.second, ())
        .mapAsync(1) { _ =>
          ws
            .url(s"http://127.0.0.1:${port}/health")
            .withRequestTimeout(1.second)
            .get()
            .map(r => r.status)
            .recover { case e =>
              0
            }
        }
        .filter(_ != play.mvc.Http.Status.OK)
        .take(1)
        .runForeach(_ => ())
        .futureValue
    }

    def start() = {
      val instanceId = IdGenerator.uuid
      val otoroshi   = Otoroshi(
        ServerConfig(
          address = "0.0.0.0",
          port = port.some,
          rootDir = Files.createTempDirectory(s"otoroshi-test-helper-$instanceId").toFile
        ),
        getTestConfiguration(
          Configuration(
            ConfigFactory
              .parseString(configuration.stripMargin)
              .resolve()
          )
        ).underlying
      )
      otoroshi.env.logger.debug(s"Starting Otoroshi on $port!!!")
      ref.set(otoroshi.startAndStopOnShutdown())
      Source
        .tick(1.second, 1.second, ())
        .mapAsync(1) { _ =>
          ws
            .url(s"http://127.0.0.1:${port}/health")
            .withRequestTimeout(1.second)
            .get()
            .map(r => r.status)
            .recover { case e =>
              0
            }
        }
        .filter(_ == play.mvc.Http.Status.OK)
        .take(1)
        .runForeach(_ => ())
        .futureValue
    }
  }

  def leaderToLeaderCallPrivateAPI() = {
    val publicInstance  = OtoroshiInstance(
      TargetService.freePort,
      s"""
       |otoroshi.next.state-sync-interval=2000
       |otoroshi.tunnels.enabled=true
       |otoroshi.loggers.otoroshi-tunnel-agent=DEBUG
       |otoroshi.loggers.otoroshi-tunnel-plugin=DEBUG
       |otoroshi.loggers.otoroshi-tunnel-manager=DEBUG
       |"""
    )
    val privateInstance = OtoroshiInstance(
      TargetService.freePort,
      s"""
       |otoroshi {
       |  next.state-sync-interval = 2000
       |
       |  loggers {
       |    otoroshi-tunnel-agent = DEBUG
       |    otoroshi-tunnel-plugin = DEBUG
       |    otoroshi-tunnel-manager = DEBUG
       |  }
       |
       |  tunnels {
       |    enabled = true
       |
       |    public-apis {
       |      enabled = true
       |      id = "public-apis"
       |      name = "public apis tunnel"
       |      url = "http://otoroshi-api.oto.tools:${publicInstance.port}"
       |      host = "otoroshi-api.oto.tools"
       |      clientId = "admin-api-apikey-id"
       |      clientSecret = "admin-api-apikey-secret"
       |    }
       |  }
       |}
       |"""
    )

    publicInstance.start()
    getOtoroshiRoutes(publicInstance.port.some).futureValue
    await(5.seconds)

    privateInstance.start()
    getOtoroshiRoutes(privateInstance.port.some).futureValue
    await(5.seconds)

    val privateRoute = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        )
      ),
      customOtoroshiPort = privateInstance.port.some,
      domain = "private-api.oto.tools".some
    ).futureValue

    val publicRoute = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[TunnelPlugin],
          config = NgPluginInstanceConfig(
            TunnelPluginConfig(
              tunnelId = "public-apis"
            ).json
              .as[JsObject]
          )
        )
      ),
      target = NgTarget(
        hostname = privateRoute.frontend.domains.head.domain,
        port = privateInstance.port,
        id = "private-api",
        tls = false,
        ipAddress = "127.0.0.1".some
      ).some,
      customOtoroshiPort = publicInstance.port.some
    ).futureValue

    val privateRouteCall = ws
      .url(s"http://127.0.0.1:${privateInstance.port}/")
      .withHttpHeaders(
        "Host" -> privateRoute.frontend.domains.head.domain
      )
      .get()
      .futureValue

    privateRouteCall.status mustBe Status.OK

    val publicRouteCall = ws
      .url(s"http://127.0.0.1:${publicInstance.port}/")
      .withHttpHeaders(
        "Host" -> publicRoute.frontend.domains.head.domain
      )
      .get()
      .futureValue

    publicRouteCall.status mustBe Status.OK

    privateInstance.stop()
    publicInstance.stop()
  }
}
