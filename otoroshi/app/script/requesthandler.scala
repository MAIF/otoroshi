package otoroshi.script

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.{DataInOut, GatewayEvent, Header, Location}
import otoroshi.models.RemainingQuotas
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.utils.http.Implicits.BetterStandaloneWSResponse
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.http.WSCookieWithSameSite
import otoroshi.utils.syntax.implicits._
import play.api.http.HttpEntity
import play.api.http.websocket.{Message => PlayWSMessage}
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Results.Status
import play.api.mvc._

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait RequestHandler extends StartableAndStoppable with NamedPlugin {
  override def pluginType: PluginType                                                                       = PluginType.RequestHandlerType
  def handledDomains(implicit ec: ExecutionContext, env: Env): Seq[String]                                  = Seq.empty[String]
  def handle(
      request: Request[Source[ByteString, _]],
      defaultRouting: Request[Source[ByteString, _]] => Future[Result]
  )(implicit ec: ExecutionContext, env: Env): Future[Result]                                                = defaultRouting(request)
  def handleWs(
      request: RequestHeader,
      defaultRouting: RequestHeader => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] =
    defaultRouting(request)
}

class ForwardTrafficHandler extends RequestHandler {

  override def name: String = "Forward traffic"

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory] = Seq.empty
  override def steps: Seq[NgStep]                = Seq.empty

  override def description: Option[String] =
    "This plugin can be use to perform a raw traffic forward to an URL without passing through otoroshi routing".some

  override def configRoot: Option[String] = "ForwardTrafficHandler".some

  override def defaultConfig: Option[JsObject] = Json
    .obj(
      configRoot.get -> Json.obj(
        "domains" -> Json.obj(
          "my.domain.tld" -> Json.obj(
            "baseUrl" -> "https://my.otherdomain.tld",
            "secret"  -> "jwt signing secret",
            "service" -> Json.obj(
              "id"   -> "service id for analytics",
              "name" -> "service name for analytics"
            )
          )
        )
      )
    )
    .some

  def hasBody(request: Request[_]): Boolean = {
    request.theHasBody
    // (request.method, request.headers.get("Content-Length")) match {
    //   case ("GET", Some(_))    => true
    //   case ("GET", None)       => false
    //   case ("HEAD", Some(_))   => true
    //   case ("HEAD", None)      => false
    //   case ("PATCH", _)        => true
    //   case ("POST", _)         => true
    //   case ("PUT", _)          => true
    //   case ("DELETE", Some(_)) => true
    //   case ("DELETE", None)    => false
    //   case _                   => true
    // }
  }

  override def handledDomains(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    val config                         = env.datastores.globalConfigDataStore.latest().plugins.config.select(configRoot.get)
    val domains: Map[String, JsObject] = config.select("domains").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
    domains.keys.toSeq
  }

  override def handle(
      request: Request[Source[ByteString, _]],
      defaultRouting: Request[Source[ByteString, _]] => Future[Result]
  )(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    val config                         = env.datastores.globalConfigDataStore.latest().plugins.config.select(configRoot.get)
    val domains: Map[String, JsObject] = config.select("domains").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
    domains.get(request.theDomain) match {
      case None      => defaultRouting(request)
      case Some(obj) => {
        val start       = System.currentTimeMillis()
        val baseUrl     = obj.select("baseUrl").asString
        val secret      = obj.select("secret").asString
        val service     = obj.select("service").asObject
        val serviceId   = service.select("id").asString
        val serviceName = service.select("name").asString
        val date        = DateTime.now()
        val reqId       = UUID.randomUUID().toString
        val alg         = Algorithm.HMAC512(secret)
        val token       = JWT.create().withIssuer(env.Headers.OtoroshiIssuer).sign(alg)
        val path        = request.thePath
        val baseUri     = Uri(baseUrl)
        val host        = baseUri.authority.host.toString()
        // val ctype       = request.headers.get("Content-Type")
        // val clen        = request.headers.get("Content-Length").map(_.toLong)
        val headers     = request.headers.toSimpleMap.toSeq
          // .filterNot(_._1.toLowerCase == "content-type")
          .filterNot(_._1.toLowerCase == "timeout-access")
          .filterNot(_._1.toLowerCase == "tls-session-info")
          .filterNot(_._1.toLowerCase == "host") ++ Seq(
          (env.Headers.OtoroshiState -> reqId),
          (env.Headers.OtoroshiClaim -> token),
          ("Host"                    -> host)
        )
        val cookies     = request.cookies.toSeq.map { c =>
          WSCookieWithSameSite(
            name = c.name,
            value = c.value,
            domain = c.domain,
            path = Option(c.path),
            maxAge = c.maxAge.map(_.toLong),
            secure = c.secure,
            httpOnly = c.httpOnly,
            sameSite = c.sameSite
          )
        }
        val overhead    = System.currentTimeMillis() - start
        var builder     = env.gatewayClient
          .akkaUrl(s"$baseUrl$path")
          .withHttpHeaders(headers: _*)
          .withCookies(cookies: _*)
          .withMethod(request.method)
          .withFollowRedirects(false)

        if (hasBody(request)) {
          builder = builder.withBody(request.body)
        }

        builder
          .stream()
          .map { resp =>
            val duration = System.currentTimeMillis() - start
            val ctypeOut = resp.headers.get("Content-Type").orElse(resp.headers.get("content-type")).map(_.last)
            val clenOut = resp.headers.get("Content-Length").orElse(resp.headers.get("content-length")).map(_.last).map(_.toLong)
            val headersOut = resp.headers.mapValues(_.last)
              .filterNot {
                case (key, _) => key.toLowerCase == "content-length"
              }
              .filterNot {
                case (key, _) => key.toLowerCase == "content-type"
              }
              .toSeq
            val transferEncoding = resp.headers.get("Transfer-Encoding").orElse(resp.headers.get("transfer-encoding")).map(_.last)
            val hasChunkedHeader = transferEncoding.exists(h => h.toLowerCase().contains("chunked"))
            val isChunked: Boolean = resp.isChunked() match { // don't know if actualy legit ...
              case Some(chunked)                                                                         => chunked
              case None if !env.emptyContentLengthIsChunked                                              =>
                hasChunkedHeader // false
              case None if env.emptyContentLengthIsChunked && hasChunkedHeader                           =>
                true
              case None if env.emptyContentLengthIsChunked && !hasChunkedHeader && clenOut.isEmpty       =>
                true
              case _                                                                                     => false
            }
            val cookiesOut = resp.cookies.map {
              case c: WSCookieWithSameSite =>
                Cookie(
                  name = c.name,
                  value = c.value,
                  maxAge = c.maxAge.map(_.toInt),
                  path = c.path.getOrElse("/"),
                  domain = c.domain,
                  secure = c.secure,
                  httpOnly = c.httpOnly,
                  sameSite = c.sameSite
                )
              case c                       => {
                val sameSite: Option[Cookie.SameSite] = resp.headers.get("Set-Cookie").orElse(resp.headers.get("set-cookie")).flatMap { values => // legit
                  values
                    .find { sc =>
                      sc.startsWith(s"${c.name}=${c.value}")
                    }
                    .flatMap { sc =>
                      sc.split(";")
                        .map(_.trim)
                        .find(p => p.toLowerCase.startsWith("samesite="))
                        .map(_.replace("samesite=", "").replace("SameSite=", ""))
                        .flatMap(Cookie.SameSite.parse)
                    }
                }
                Cookie(
                  name = c.name,
                  value = c.value,
                  maxAge = c.maxAge.map(_.toInt),
                  path = c.path.getOrElse("/"),
                  domain = c.domain,
                  secure = c.secure,
                  httpOnly = c.httpOnly,
                  sameSite = sameSite
                )
              }
            }
            GatewayEvent(
              `@id` = reqId,
              `@timestamp` = date,
              `@calledAt` = date,
              reqId = reqId,
              parentReqId = None,
              protocol = request.version,
              to = Location(
                scheme = request.theProtocol,
                host = request.theHost,
                uri = request.relativeUri
              ),
              target = Location(
                scheme = request.theProtocol,
                host = host,
                uri = request.relativeUri
              ),
              url = request.theUrl,
              method = request.method,
              from = request.theIpAddress,
              env = env.env,
              duration = duration,
              overhead = overhead,
              cbDuration = 0,
              overheadWoCb = overhead,
              callAttempts = 1,
              data = DataInOut(0, 0),
              status = resp.status,
              headers = request.headers.toSimpleMap.toSeq.map(Header.apply),
              headersOut = resp.headers.mapValues(_.last).toSeq.map(Header.apply),
              otoroshiHeadersIn = headers.map(Header.apply),
              otoroshiHeadersOut = resp.headers.mapValues(_.last).toSeq.map(Header.apply),
              extraInfos = None,
              responseChunked = false,
              identity = None,
              gwError = None,
              err = false,
              `@serviceId` = serviceId,
              `@service` = serviceName,
              descriptor = None,
              remainingQuotas = RemainingQuotas(),
              viz = None,
              clientCertChain = Seq.empty[String],
              userAgentInfo = None,
              geolocationInfo = None,
              extraAnalyticsData = None
            ).toAnalytics()
            isChunked match {
              case true  => {
                // stream out
                val res = Status(resp.status)
                  .chunked(resp.bodyAsSource)
                  .withHeaders(headersOut: _*)
                  .withCookies(cookiesOut: _*)
                ctypeOut match {
                  case None      => res
                  case Some(ctp) => res.as(ctp)
                }
              }
              case false => {
                val res = Results
                  .Status(resp.status)
                  .sendEntity(
                    HttpEntity.Streamed(
                      resp.bodyAsSource,
                      clenOut,
                      ctypeOut
                    )
                  )
                  .withHeaders(headersOut: _*)
                  .withCookies(cookiesOut: _*)
                ctypeOut match {
                  case None      => res
                  case Some(ctp) => res.as(ctp)
                }
              }
            }
          }
          .recoverWith { case e =>
            defaultRouting(request)
          }
      }
    }
  }
}
