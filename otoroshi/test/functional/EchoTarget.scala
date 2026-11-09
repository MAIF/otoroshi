package functional

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import otoroshi.next.models.NgTarget
import play.api.libs.json.{JsNull, JsObject, JsString, JsValue, Json}

import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * A local copy of the public echo backend `request.otoroshi.io`, so the tests proxying to "an external target" never
 * leave the machine (#2822). It runs once per test jvm and answers any method on any path with the request it got, in
 * the same json shape as the public one:
 *
 * {{{
 * {"method": "POST", "path": "/foo", "headers": {"host": "...", ...}, "cookies": {"name": "value"}, "body": ...}
 * }}}
 *
 *   - `path` has no query string
 *   - `headers` has lower cased names, the values of a header sent several times are joined with `, `
 *   - `body` is `null` when empty, the parsed json for an `application/json` content type (an invalid one is a 500),
 *     and the body as a string for any other content type
 *   - an `OPTIONS` request is a cors preflight answered with a 204, a `HEAD` request has no body
 *
 * It is exposed as `request.oto.tools`, which resolves to `127.0.0.1`: the target keeps a real hostname for the `Host`
 * header, and its ip address is given so that no dns resolution is needed either.
 */
object EchoTarget {

  val hostname: String = "request.oto.tools"

  private val corsHeaders = Seq(
    "Vary"                             -> "Origin",
    "Access-Control-Allow-Origin"      -> "*",
    "Access-Control-Allow-Credentials" -> "true",
    "Access-Control-Allow-Headers"     -> "Origin, X-Requested-With, Content-Type, Accept",
    "Access-Control-Allow-Methods"     -> "GET, POST, PUT, DELETE, OPTIONS"
  )

  private lazy val server: HttpServer = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", exchange => handle(exchange))
    server.setExecutor(Executors.newCachedThreadPool { (runnable: Runnable) =>
      val thread = new Thread(runnable, "echo-target")
      thread.setDaemon(true)
      thread
    })
    server.start()
    server
  }

  lazy val port: Int = server.getAddress.getPort

  lazy val target: NgTarget = NgTarget(
    id = s"$hostname.target",
    hostname = hostname,
    port = port,
    tls = false,
    ipAddress = Some("127.0.0.1")
  )

  def url: String = s"http://$hostname:$port"

  private def cookies(headers: Map[String, String]): JsObject = {
    JsObject(
      headers
        .get("cookie")
        .toSeq
        .flatMap(_.split(";"))
        .map(_.trim)
        .filter(_.contains("="))
        .map { cookie =>
          val index = cookie.indexOf("=")
          val value = cookie.substring(index + 1)
          cookie.substring(0, index) -> JsString(Try(URLDecoder.decode(value, "UTF-8")).getOrElse(value))
        }
    )
  }

  private def body(headers: Map[String, String], bytes: Array[Byte]): Try[JsValue] = {
    val isJson = headers.get("content-type").exists(_.split(";").head.trim.equalsIgnoreCase("application/json"))
    if (bytes.isEmpty) Success(JsNull)
    else if (isJson) Try(Json.parse(bytes))
    else Success(JsString(new String(bytes, StandardCharsets.UTF_8)))
  }

  private def respond(exchange: HttpExchange, status: Int, contentType: String, payload: Array[Byte]): Unit = {
    val responseHeaders = exchange.getResponseHeaders
    responseHeaders.add("X-Powered-By", "Express")
    corsHeaders.foreach { case (name, value) => responseHeaders.add(name, value) }
    if (contentType.nonEmpty) responseHeaders.add("Content-Type", contentType)
    if (payload.isEmpty || exchange.getRequestMethod.equalsIgnoreCase("HEAD")) {
      exchange.sendResponseHeaders(status, -1)
    } else {
      exchange.sendResponseHeaders(status, payload.length)
      exchange.getResponseBody.write(payload)
    }
  }

  private def handle(exchange: HttpExchange): Unit = {
    try {
      val bytes   = exchange.getRequestBody.readAllBytes()
      val method  = exchange.getRequestMethod.toUpperCase
      val headers = exchange.getRequestHeaders.asScala.toMap.map { case (name, values) =>
        name.toLowerCase -> values.asScala.mkString(", ")
      }
      if (method == "OPTIONS") {
        respond(exchange, 204, "", Array.empty)
      } else {
        body(headers, bytes) match {
          case Failure(e)    =>
            respond(
              exchange,
              500,
              "text/html; charset=utf-8",
              s"<!DOCTYPE html><html><body><pre>${e.getMessage}</pre></body></html>".getBytes(StandardCharsets.UTF_8)
            )
          case Success(body) =>
            val json = Json.obj(
              "method"  -> method,
              "path"    -> exchange.getRequestURI.getRawPath,
              "headers" -> headers,
              "cookies" -> cookies(headers),
              "body"    -> body
            )
            respond(exchange, 200, "application/json; charset=utf-8", Json.toBytes(json))
        }
      }
    } finally {
      exchange.close()
    }
  }
}
