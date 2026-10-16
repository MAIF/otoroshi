package functional

import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import org.apache.pekko.stream.Materializer
import otoroshi.env.Env
import otoroshi.health.HealthCheckLogic
import otoroshi.models.Target
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext

// a target whose health check answers red is left out of the load balancing of its route when the health check
// blocks on red, as soon as its health check answers
class HealthCheckBlockOnRedSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration =
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.healthcheck.job.initial-delay = 1000
          |otoroshi.healthcheck.job.interval = 2000
          |""".stripMargin)
        .resolve()
    ).withFallback(configuration)

  private val domain = "block-on-red.oto.tools"

  // answers its name, and its health on /health
  private def target(name: String, healthStatus: Int): HttpServer = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange => {
        val (status, body) =
          if (exchange.getRequestURI.getPath == "/health") (healthStatus, "{}")
          else (200, Json.obj("target" -> name).stringify)
        val bytes          = body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    server
  }

  "A health check that blocks on red" should {
    "warm up" in {
      startOtoroshi()
    }
    "leave a red target out of the load balancing" in {
      val green    = target("green", 200)
      val red      = target("red", 500)
      val admin    = ws
        .url(s"http://127.0.0.1:$port/apis/proxy.otoroshi.io/v1/routes")
        .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Content-Type" -> "application/json")
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      val template = ws
        .url(s"http://127.0.0.1:$port/apis/proxy.otoroshi.io/v1/routes/_template")
        .withHttpHeaders("Host" -> "otoroshi-api.oto.tools")
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .get()
        .futureValue
        .json
        .as[JsObject]
      def targetJson(id: String, server: HttpServer) =
        Json.obj("id" -> id, "hostname" -> "127.0.0.1", "port" -> server.getAddress.getPort, "tls" -> false)
      val route    = template ++ Json.obj(
        "id"       -> "route-block-on-red",
        "frontend" -> ((template \ "frontend").as[JsObject] ++ Json.obj("domains" -> Json.arr(domain))),
        "backend"  -> ((template \ "backend").as[JsObject] ++ Json.obj(
          "targets"      -> Json.arr(targetJson("green", green), targetJson("red", red)),
          "health_check" -> Json.obj(
            "enabled"    -> true,
            "url"        -> "/health",
            "blockOnRed" -> true,
            "logicCheck" -> false
          )
        ))
      )
      val created  = admin.post(route.stringify).futureValue
      withClue(created.body[String])(created.status mustBe 201)
      try {
        def calls(): Seq[String] = (1 to 10).map { _ =>
          (ws.url(s"http://127.0.0.1:$port/").withHttpHeaders("Host" -> domain).get().futureValue.json \ "target")
            .asOpt[String]
            .getOrElse("none")
        }
        // the health checks run every 2 seconds, the targets they block are loaded every 10 seconds
        val deadline = System.currentTimeMillis() + 40000L
        var answered = calls()
        while (!answered.forall(_ == "green") && System.currentTimeMillis() < deadline) {
          Thread.sleep(2000)
          answered = calls()
        }
        answered.toSet mustBe Set("green")
      } finally {
        green.stop(0)
        red.stop(0)
      }
    }
    "leave a red target out as soon as its health check answers" in {
      given env: Env             = otoroshiComponents.env
      given ec: ExecutionContext = env.otoroshiExecutionContext
      given mat: Materializer    = env.otoroshiMaterializer
      // a target of no route: only this check reads its health
      val red                    = target("red-checked", 500)
      try {
        val desc    = env.proxyState.rawRoute("route-block-on-red").get.legacy
        val checked = Target(host = s"127.0.0.1:${red.getAddress.getPort}", scheme = "http")
        HealthCheckLogic.badHealth.contains(checked.asCleanTarget) mustBe false
        HealthCheckLogic.checkTarget(desc, checked, play.api.Logger("health-check-block-on-red-spec")).futureValue
        HealthCheckLogic.badHealth.contains(checked.asCleanTarget) mustBe true
      } finally {
        red.stop(0)
      }
    }
    "shutdown" in {
      stopAll()
    }
  }
}
