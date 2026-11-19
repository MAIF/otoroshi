package otoroshi.next.utils

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.models.EntityLocationSupport
import otoroshi.next.models.{NgFrontend, NgRoute, NgRouteComposition, NgTarget, NgTlsConfig}
import otoroshi.next.plugins.ForceHttpsTraffic
import otoroshi.utils.http.ResponseImplicits.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.given
import play.api.libs.ws.WSCookie

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// a trial request sent through a route: what the body of the backoffice tester and of the try it endpoints of the
// admin api carry. Without a path, the trial calls the path of the first domain of the route
case class TryItRequest(
    method: String = "GET",
    path: Option[String] = None,
    headers: Map[String, String] = Map.empty,
    cookies: Seq[WSCookie] = Seq.empty,
    body: Option[ByteString] = None,
    clientCert: Option[String] = None,
    frontendIdx: Option[Int] = None
)

object TryItRequest {

  // the backoffice tester reads its body leniently, as it always did: a field it cannot read is ignored
  def lenient(json: JsValue): TryItRequest = {
    val bodyBase64 = json.select("base_64").asOpt[Boolean].getOrElse(false)
    TryItRequest(
      method = json.select("method").asOpt[String].map(_.toUpperCase()).getOrElse("GET"),
      path = json.select("path").asOpt[String],
      headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
      cookies = json
        .select("cookies")
        .asOpt[Seq[JsObject]]
        .map(_.map(JsonHelpers.cookieFromJson))
        .getOrElse(Seq.empty)
        .toSeq,
      body = json.select("body").asOpt[String].filter(_.nonEmpty).map { rb =>
        if (bodyBase64) rb.byteString.decodeBase64 else rb.byteString
      },
      clientCert = json.select("client_cert").asOpt[String],
      frontendIdx = json.select("frontend_idx").asOpt[Int]
    )
  }

  private def field[A](obj: JsObject, name: String, expected: String)(
      read: PartialFunction[JsValue, A]
  ): Either[String, Option[A]] =
    obj.value.get(name) match {
      case None | Some(JsNull)                    => Right(None)
      case Some(value) if read.isDefinedAt(value) => Right(Some(read(value)))
      case Some(_)                                => Left(s"'$name' must be $expected")
    }

  private def cookie(value: JsValue): Option[WSCookie] =
    value match {
      case obj: JsObject if obj.select("name").asOpt[String].isDefined && obj.select("value").asOpt[String].isDefined =>
        Try(JsonHelpers.cookieFromJson(obj)).toOption
      case _                                                                                                          => None
    }

  // the admin api reads it strictly: a tool driving otoroshi has to know that a field of its trial was not used.
  // route_id, route and frontend_idx are not read, the route comes from the path of the call
  def read(json: JsValue): Either[String, TryItRequest] =
    json match {
      case obj: JsObject =>
        for {
          method  <- field(obj, "method", "a string") { case JsString(m) if m.trim.nonEmpty => m.trim.toUpperCase() }
          path    <- field(obj, "path", "a string starting with /") { case JsString(p) if p.startsWith("/") => p }
          headers <- field(obj, "headers", "an object of strings") {
                       case JsObject(values) if values.values.forall(_.isInstanceOf[JsString]) =>
                         values.collect { case (name, JsString(value)) => name -> value }.toMap
                     }
          cookies <- field(obj, "cookies", "an array of cookies, each with a name and a value") {
                       case JsArray(values) if values.forall(v => cookie(v).isDefined) => values.flatMap(cookie).toSeq
                     }
          base64  <- field(obj, "base_64", "a boolean") { case JsBoolean(b) => b }
          raw     <- field(obj, "body", "a string") { case JsString(b) => b }
          body    <- raw.filter(_.nonEmpty) match {
                       case None                                => Right(None)
                       case Some(b) if !base64.getOrElse(false) => Right(Some(ByteString(b)))
                       case Some(b)                             =>
                         Try(java.util.Base64.getDecoder.decode(b)).toOption
                           .map(bytes => ByteString(bytes))
                           .toRight("'body' is not base64 encoded, while 'base_64' is true")
                           .map(Some(_))
                     }
          cert    <- field(obj, "client_cert", "the id of a certificate") { case JsString(c) if c.nonEmpty => c }
        } yield TryItRequest(
          method = method.getOrElse("GET"),
          path = path,
          headers = headers.getOrElse(Map.empty),
          cookies = cookies.getOrElse(Seq.empty),
          body = body,
          clientCert = cert
        )
      case _             => Left("the body must be a json object")
    }
}

// sends a trial request through a route and answers its response with the execution report of the call: the
// backoffice tester and the try it endpoints of the admin api. The request is sent by this node, from 127.0.0.1, to
// its own gateway port: the report lives in the proxy state of the node that handled the call
object TryIt {

  // the frontend the trial calls: the one of the route, or of the route of the composition at frontend_idx
  private def frontendOf(route: Either[NgRouteComposition, NgRoute], request: TryItRequest): Option[NgFrontend] =
    route match {
      case Left(composition) =>
        request.frontendIdx match {
          case Some(idx) => composition.routes.lift(idx).map(_.frontend)
          case None      => composition.routes.headOption.map(_.frontend)
        }
      case Right(route)      => route.frontend.some
    }

  // the path the trial calls: the one of the request, or the path of the first domain of the route, so that the
  // default trial of a route of an api reaches that route (its domain is <domain><context path><route path>)
  def pathOf(route: Either[NgRouteComposition, NgRoute], request: TryItRequest): String =
    request.path.getOrElse(
      frontendOf(route, request).flatMap(_.domains.headOption).map(_.path).getOrElse("/")
    )

  // `canRead` is the access of the caller: a client certificate it cannot read is not presented, and the report, which
  // holds the full json of the route that handled the call, is only answered for a route it can read. None when the
  // route has no domain to call
  def run(
      route: Either[NgRouteComposition, NgRoute],
      request: TryItRequest,
      canRead: EntityLocationSupport => Boolean
  )(using env: Env): Future[Option[JsObject]] = {
    given ec: ExecutionContext                      = env.otoroshiExecutionContext
    given mat: org.apache.pekko.stream.Materializer = env.otoroshiMaterializer
    frontendOf(route, request).flatMap(_.domains.map(_.domainLowerCase).headOption) match {
      case None           => None.vfuture
      case Some(hostname) =>
        val requestId  = UUID.randomUUID().toString
        env.proxyState.enableReportFor(requestId)
        val path       = pathOf(route, request)
        val clientCert = request.clientCert.filter(id => env.proxyState.certificate(id).exists(c => canRead(c)))
        val headers    = request.headers ++ Map(
          "Otoroshi-Try-It-Request-Id" -> requestId,
          "Host"                       -> hostname
        )
        val isHttps    = route match {
          case Left(composition) => composition.plugins.hasPlugin[ForceHttpsTraffic]
          case Right(route)      => route.plugins.hasPlugin[ForceHttpsTraffic]
        }
        val url        =
          if (isHttps) s"https://${hostname}:${env.httpsPort}${path}" else s"http://${hostname}:${env.port}${path}"
        val target     = NgTarget(
          id = s"tryit-${requestId}",
          hostname = hostname,
          port = if (isHttps) env.httpsPort else env.port,
          tls = isHttps,
          ipAddress = "127.0.0.1".some,
          backup = false,
          tlsConfig = NgTlsConfig(
            certs = clientCert.toSeq,
            enabled = true,
            loose = true,
            trustAll = true
          )
        ).legacy
        val wsRequest  = env.gatewayClient
          .akkaUrlWithTarget(url, target)
          .withFollowRedirects(false)
          .withMethod(request.method)
          .addHttpHeaders(headers.toSeq*)
          .withCookies(request.cookies*)
          .withRequestTimeout(1.minute)
        val respF      = request.body match {
          case None          => wsRequest.execute()
          case Some(content) => wsRequest.withBody(Source(content.grouped(32 * 1024).toList)).execute()
        }
        val curl       =
          s"curl -X ${request.method} $url ${headers.map { case (key, value) => s"""-H "$key: $value"""" }.mkString(" ")}"
        respF.flatMap { resp =>
          // the report carries the full json of the route that handled the call: only for a route the caller can read
          val report: JsValue = env.proxyState
            .report(requestId)
            .map(_.json)
            .filter(r =>
              (r \\ "found_route").flatMap(_.select("id").asOpt[String]).forall { id =>
                env.proxyState.route(id).forall(route => canRead(route))
              }
            )
            .getOrElse(JsNull)
          val status          = resp.status
          val respHeaders     = resp.headers.view.mapValues(_.last).toMap
          resp.bodyAsSource.runFold(ByteString.empty)(_ ++ _).map { respBodyRaw =>
            Json
              .obj(
                "status"       -> status,
                "headers"      -> respHeaders,
                "body_base_64" -> respBodyRaw.encodeBase64.utf8String,
                "cookies"      -> JsArray(resp.safeCookies(env).toSeq.map(c => JsonHelpers.wsCookieToJson(c))),
                "report"       -> report,
                "curl"         -> curl
              )
              .some
          }
        }
    }
  }
}
