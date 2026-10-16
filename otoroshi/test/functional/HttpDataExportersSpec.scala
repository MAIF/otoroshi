package functional

import com.sun.net.httpserver.HttpServer
import otoroshi.env.Env
import otoroshi.events.ExportResult
import otoroshi.models.{GlobalConfig, HttpCallSettings, NewRelicCallSettings}
import otoroshi.next.models.NgTlsConfig
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.{DefaultWSCookie, WSCookie}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

// the calls of the http exporters: any 2xx status is a success, `${events.nd_prettify}` is each event pretty printed,
// the cookies of the http exporter are sent and the hostname of the new relic exporter is the one of its logs
class HttpDataExportersSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  // answers 299, keeps the body and the cookies of the last call
  private val received = new AtomicReference[(String, String)](("", ""))
  private val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  server.createContext(
    "/",
    exchange => {
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      received.set((body, Option(exchange.getRequestHeaders.getFirst("Cookie")).getOrElse("")))
      exchange.sendResponseHeaders(299, -1)
      exchange.close()
    }
  )

  private val events = Seq(Json.obj("@type" -> "GatewayEvent", "to" -> Json.obj("host" -> "backend.oto.tools")))

  private def httpSettings(body: String, cookies: Seq[WSCookie] = Seq.empty) = HttpCallSettings(
    url = s"http://127.0.0.1:${server.getAddress.getPort}/events",
    method = "POST",
    headers = Map.empty,
    cookies = cookies,
    body = body,
    timeout = 10.seconds,
    tlsConfig = NgTlsConfig()
  )

  private def httpConfig(using env: Env) = env.datastores.dataExporterConfigDataStore.template(Some("http"))

  private def globalConfig(using env: Env, ec: ExecutionContext): GlobalConfig =
    env.datastores.globalConfigDataStore.latest()

  "The http exporters" should {
    "warm up" in {
      startOtoroshi()
      server.start()
    }
    "count any 2xx status as a success" in {
      given env: Env             = otoroshiComponents.env
      given ec: ExecutionContext = env.otoroshiExecutionContext
      val result                 = httpSettings(body = "x").call(events, httpConfig, globalConfig).futureValue
      result mustBe ExportResult.ExportResultSuccess
    }
    "send each event pretty printed for ${events.nd_prettify}" in {
      given env: Env             = otoroshiComponents.env
      given ec: ExecutionContext = env.otoroshiExecutionContext
      httpSettings(body = "${events.nd_prettify}").call(events, httpConfig, globalConfig).futureValue
      received.get()._1 mustBe events.map(_.prettify).mkString("\n\n")
    }
    "send the cookies of the http exporter" in {
      given env: Env             = otoroshiComponents.env
      given ec: ExecutionContext = env.otoroshiExecutionContext
      httpSettings(body = "x", cookies = Seq(DefaultWSCookie(name = "session", value = "abc")))
        .call(events, httpConfig, globalConfig)
        .futureValue
      received.get()._2 must include("session=abc")
    }
    "write the hostname of the new relic exporter in its logs" in {
      given env: Env             = otoroshiComponents.env
      given ec: ExecutionContext = env.otoroshiExecutionContext
      val settings               = NewRelicCallSettings(
        url = s"http://127.0.0.1:${server.getAddress.getPort}/logs",
        headers = Map.empty,
        token = None,
        logtype = None,
        service = None,
        hostname = Some("otoroshi-node-1"),
        timeout = 10.seconds,
        tlsConfig = NgTlsConfig()
      )
      val config                 = env.datastores.dataExporterConfigDataStore.template(Some("newrelic"))
      settings.call(events, config, globalConfig).futureValue mustBe ExportResult.ExportResultSuccess
      (Json.parse(received.get()._1) \ 0 \ "common" \ "hostname").as[String] mustBe "otoroshi-node-1"
      // without hostname, the host called
      val unset                  = settings.copy(hostname = None).call(events, config, globalConfig).futureValue
      unset mustBe ExportResult.ExportResultSuccess
      (Json.parse(received.get()._1) \ 0 \ "common" \ "hostname").as[String] mustBe "backend.oto.tools"
    }
    "shutdown" in {
      server.stop(0)
      stopAll()
    }
  }
}
