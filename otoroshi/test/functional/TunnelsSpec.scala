package functional

import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import otoroshi.api.Otoroshi
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse}
import play.core.server.ServerConfig

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.concurrent.duration.*

// a call that goes through a tunnel: from a route of the instance the tunnel is connected to, to a route of the agent
// that opened it, then to the backend the agent reaches. the call keeps its body, its cookies, and the cookies the
// backend sets
class TunnelsSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private val domain     = "tunnel-test.oto.tools"
  private var agent      = Option.empty[Otoroshi]
  private val backend    = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  private val agentPort  = TargetService.freePort

  // answers the body and the cookies it receives, and sets a cookie
  backend.createContext(
    "/",
    exchange => {
      val body    = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val cookies = Option(exchange.getRequestHeaders.getFirst("Cookie")).getOrElse("")
      val answer  = Json.obj("body" -> body, "cookies" -> cookies).stringify.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.getResponseHeaders.add("Set-Cookie", "from_backend=yes; Path=/")
      exchange.sendResponseHeaders(200, answer.length)
      exchange.getResponseBody.write(answer)
      exchange.close()
    }
  )

  private def admin(instancePort: Int, method: String, path: String, body: Option[JsValue] = None): WSResponse = {
    val req = ws
      .url(s"http://127.0.0.1:$instancePort$path")
      .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Accept" -> "application/json")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withMethod(method)
    body
      .map(b => req.addHttpHeaders("Content-Type" -> "application/json").withBody(b.stringify))
      .getOrElse(req)
      .execute()
      .futureValue
  }

  private def createRoute(instancePort: Int, id: String, change: JsObject => JsObject): Unit = {
    val template = admin(instancePort, "GET", "/apis/proxy.otoroshi.io/v1/routes/_template").json.as[JsObject]
    val route    = change(
      template ++ Json.obj(
        "id"       -> id,
        "name"     -> id,
        "frontend" -> ((template \ "frontend").as[JsObject] ++ Json.obj("domains" -> Json.arr(domain)))
      )
    )
    val resp     = admin(instancePort, "POST", "/apis/proxy.otoroshi.io/v1/routes", Some(route))
    withClue(resp.body[String])(resp.status mustBe 201)
  }

  private def callOnce(cookie: Option[String], body: Option[String]): WSResponse = {
    val req = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders(Seq("Host" -> domain) ++ cookie.map(c => "Cookie" -> c).toSeq*)
      .withRequestTimeout(10.seconds)
    body
      .map(b => req.addHttpHeaders("Content-Type" -> "text/plain").post(b))
      .getOrElse(req.get())
      .futureValue
  }

  // the agent opens the tunnel once it is started, and the proxy states take the routes a few seconds after
  private def call(cookie: Option[String], body: Option[String]): WSResponse = {
    val deadline = System.currentTimeMillis() + 60000L
    var resp     = scala.util.Try(callOnce(cookie, body)).toOption
    while (!resp.exists(_.status == 200) && System.currentTimeMillis() < deadline) {
      Thread.sleep(1000)
      resp = scala.util.Try(callOnce(cookie, body)).toOption
    }
    resp.getOrElse(callOnce(cookie, body))
  }

  "A call through a tunnel" should {
    "warm up" in {
      startOtoroshi()
      backend.start()
      agent = Some(
        Otoroshi(
          ServerConfig(
            address = "0.0.0.0",
            port = Some(agentPort),
            sslPort = None,
            rootDir = Files.createTempDirectory("otoroshi-tunnel-agent").toFile
          ),
          ConfigFactory
            .parseString(s"""
              |app.storage = "inmemory"
              |otoroshi.tunnels.default.enabled = true
              |otoroshi.tunnels.default.id = "tunnel-test"
              |otoroshi.tunnels.default.url = "http://127.0.0.1:$port"
              |otoroshi.tunnels.default.export-routes = false
              |""".stripMargin)
            .resolve()
        ).start()
      )
      // the agent reaches the backend, the instance the tunnel is connected to reaches the agent through the tunnel
      createRoute(
        agentPort,
        "route-behind-the-tunnel",
        route =>
          route ++ Json.obj(
            "backend" -> ((route \ "backend").as[JsObject] ++ Json.obj(
              "targets" -> Json.arr(
                Json.obj("id" -> "backend", "hostname" -> "127.0.0.1", "port" -> backend.getAddress.getPort, "tls" -> false)
              )
            ))
          )
      )
      createRoute(
        port,
        "route-through-the-tunnel",
        // the request sent through the tunnel is the one to the backend of the route: to the domain the agent serves
        route =>
          route ++ Json.obj(
            "backend" -> ((route \ "backend").as[JsObject] ++ Json.obj(
              "targets" -> Json.arr(Json.obj("id" -> "agent", "hostname" -> domain, "port" -> 80, "tls" -> false))
            )),
            "plugins" -> Json.arr(
              Json.obj(
                "enabled" -> true,
                "plugin"  -> "cp:otoroshi.next.tunnel.TunnelPlugin",
                "config"  -> Json.obj("tunnel_id" -> "tunnel-test")
              )
            )
          )
      )
    }
    "keep its body" in {
      val resp = call(cookie = None, body = Some("hello through the tunnel"))
      withClue(resp.body[String]) {
        resp.status mustBe 200
        (resp.json \ "body").as[String] mustBe "hello through the tunnel"
      }
    }
    "keep the cookies the backend sets" in {
      val resp = call(cookie = None, body = None)
      withClue(resp.body[String]) {
        resp.status mustBe 200
        resp.headers.getOrElse("Set-Cookie", Seq.empty).mkString(" ") must include("from_backend=yes")
      }
    }
    "keep the cookies of the client" in {
      val resp = call(cookie = Some("from_client=yes"), body = None)
      withClue(resp.body[String]) {
        resp.status mustBe 200
        (resp.json \ "cookies").as[String] must include("from_client=yes")
      }
    }
    "shutdown" in {
      agent.foreach(_.stop())
      backend.stop(0)
      stopAll()
    }
  }
}
