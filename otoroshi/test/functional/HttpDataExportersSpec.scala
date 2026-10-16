package functional

import com.sun.net.httpserver.HttpServer
import otoroshi.env.Env
import otoroshi.events.ExportResult
import otoroshi.models.{GlobalConfig, HttpCallSettings}
import otoroshi.next.models.NgTlsConfig
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.WSCookie

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

// the calls of the http exporters: any 2xx status is a success
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
    "shutdown" in {
      server.stop(0)
      stopAll()
    }
  }
}
