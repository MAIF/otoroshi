package otoroshi.script

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.{DataInOut, GatewayEvent, Header, Location}
import otoroshi.models.RemainingQuotas
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.http.WSCookieWithSameSite
import otoroshi.utils.syntax.implicits._
import play.api.http.HttpEntity
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{Request, Result, Results}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait RequestHandler extends StartableAndStoppable with NamedPlugin {
  override def pluginType: PluginType                                      = PluginType.RequestHandlerType
  def handledDomains(implicit ec: ExecutionContext, env: Env): Seq[String] = Seq.empty[String]
  def handle(
      request: Request[Source[ByteString, _]],
      defaultRouting: Request[Source[ByteString, _]] => Future[Result]
  )(implicit ec: ExecutionContext, env: Env): Future[Result]               = defaultRouting(request)
}

class ForwardTrafficHandler extends RequestHandler {

  override def name: String = "Forward traffic"

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

  def hasBody(request: Request[_]): Boolean =
    (request.method, request.headers.get("Content-Length")) match {
      case ("GET", Some(_))    => true
      case ("GET", None)       => false
      case ("HEAD", Some(_))   => true
      case ("HEAD", None)      => false
      case ("PATCH", _)        => true
      case ("POST", _)         => true
      case ("PUT", _)          => true
      case ("DELETE", Some(_)) => true
      case ("DELETE", None)    => false
      case _                   => true
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
        val headers     = request.headers.toSimpleMap.toSeq
          .filterNot(_._1.toLowerCase == "content-type")
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
            // TODO : send event
            val duration = System.currentTimeMillis() - start
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
            Results
              .Status(resp.status)
              .sendEntity(
                HttpEntity.Streamed(
                  data = resp.bodyAsSource,
                  contentLength = None,
                  contentType = Some(resp.contentType)
                )
              )
              .as(resp.contentType)
              .withHeaders(resp.headers.mapValues(_.last).toSeq.filterNot(_._1.toLowerCase == "content-type"): _*)
          }
          .recoverWith { case e =>
            defaultRouting(request)
          }
      }
    }
  }
}
