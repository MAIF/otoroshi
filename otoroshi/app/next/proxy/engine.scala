package otoroshi.next.proxy

import akka.Done
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.models.{RemainingQuotas, ServiceLocation}
import otoroshi.next.models.{Backend, Route}
import otoroshi.script.{HttpRequest, HttpResponse, RequestHandler}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSResponse
import play.api.mvc.{Request, Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class ProxyEngine() extends RequestHandler {

  override def name: String = "Otoroshi newest proxy engine"

  override def description: Option[String] = "This plugin holds the next generation otoroshi proxy engine implementation".some

  override def configRoot: Option[String] = "NextGenProxyEngine".some

  override def defaultConfig: Option[JsObject] = {
    Json.obj(
      configRoot.get -> Json.obj(
        "enabled" -> true,
        "domains" -> Json.arr()
      )
    ).some
  }

  override def handledDomains(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    val config = env.datastores.globalConfigDataStore.latest().plugins.config.select(configRoot.get)
    config.select("domains").asOpt[Seq[String]].getOrElse(Seq.empty)
  }

  override def handle(request: Request[Source[ByteString, _]], defaultRouting: Request[Source[ByteString, _]] => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    (for {
      // TODO: handle concurrent requests counter, limiting and alerting
      requestLocation <- extractRequestLocation(request)
      route           <- findRoute(request, requestLocation)
      // TODO: handle tenant per worker routing
      // TODO: handle global maintenance if not backoffice route
      // TODO: handle route enabled (if needed)
      _               <- callPluginsBeforeRequestCallback(request, route)
      _               <- callPreRoutePlugins(request, route)
      // TODO: handle tcp/udp tunneling if not possible as plugin
      _               <- callAccessValidatorPlugins(request, route)
      _               <- checkGlobalLimits(request, route) // generic.scala (1269)
      result          <- callTarget(request, route) { backend => // TODO: handle circuit breaker and target stuff
        for {
          // TODO: handle high overhead alerting
          remQuotas     <- updateQuotas(request, route, backend)
          finalRequest  <- callRequestTransformer(request, route, backend)
          finalReqBody  <- callRequestBodyTransformer(request, route, backend)
          response      <- callBackend(request, finalRequest, finalReqBody, route, backend)
          finalResp     <- callResponseTransformer(request, response, remQuotas, route, backend)
          finalRespBody <- callResponseBodyTransformer(request, response, remQuotas, route, backend)
          clientResp    <- streamResponse(request, response, finalResp, finalRespBody, route, backend)
          _             <- triggerProxyDone(request, response, route, backend)
        } yield clientResp
      }
    } yield {
      result
    }).value.flatMap {
      case Left(error)   => error.asResult()
      case Right(result) => result.future
    }
  }

  def extractRequestLocation(value: Request[Source[ByteString, _]])(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, ServiceLocation] = ???
  def findRoute(value: Request[Source[ByteString, _]], location: ServiceLocation)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Route] = ???
  def callPluginsBeforeRequestCallback(request: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = ???
  def callPreRoutePlugins(request: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = ???
  def callAccessValidatorPlugins(request: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = ???
  def callTarget(request: Request[Source[ByteString, _]], route: Route)(f: Backend => FEither[ProxyEngineError, Result])(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Result] = ???
  def checkGlobalLimits(request: Request[Source[ByteString, _]], route: Route) (implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = ???
  def updateQuotas(request: Request[Source[ByteString, _]], route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, RemainingQuotas] = ???
  def callRequestTransformer(request: Request[Source[ByteString, _]], route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, HttpRequest] = ???
  def callRequestBodyTransformer(request: Request[Source[ByteString, _]], route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Source[ByteString, _]] = ???
  def callBackend(rawRequest: Request[Source[ByteString, _]], request : HttpRequest, body: Source[ByteString, _], route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, WSResponse] = ???
  def callResponseTransformer(rawRequest: Request[Source[ByteString, _]], response: WSResponse, quotas: RemainingQuotas, route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, HttpResponse] = ???
  def callResponseBodyTransformer(rawRequest: Request[Source[ByteString, _]], response: WSResponse, quotas: RemainingQuotas, route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Source[ByteString, _]] = ???
  def streamResponse(rawRequest: Request[Source[ByteString, _]], rawResponse: WSResponse, response: HttpResponse, body: Source[ByteString, _], route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Result] = {
    FEither(Results.Ok("hello").rightf[ProxyEngineError])
  }
  def triggerProxyDone(rawRequest: Request[Source[ByteString, _]], rawResponse: WSResponse, route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = ???
}

sealed trait ProxyEngineError {
  def asResult()(implicit ec: ExecutionContext, env: Env): Future[Result]
}

/*
 needed plugins

 - redirection plugin
 - tcp/udp tunneling (??? - if possible)
 - headers verification (access validator)
 - readonly route (access validator)
 - readonly apikey (access validator)
 - jwt verifier (access validator)
 - apikey validation with constraints (access validator)
 - auth. module validation (access validator)
 - route restrictions (access validator)
 - public/private path plugin (access validator)
 - force https traffic (pre route)
 - allow http/1.0 traffic (pre route or access validator)
 - snowmonkey (???)
 - canary (???)
 - otoroshi state plugin (transformer)
 - otoroshi claim plugin (transformer)
 - headers manipulation (transformer)
 - endless response clients (transformer)
 - maintenance mode (transformer)
 - construction mode (transformer)
 - apikey extractor (pre route)
 - send otoroshi headers back (transformer)
 - override host header (transformer)
 - send xforwarded headers (transformer)
 - CORS (transformer)
 - gzip (transformer)
 - ip blocklist (access validator)
 - ip allowed list (access validator)

 */

/*
 killed features

 - sidecar (handled with kube stuff now)
 - local redirection

 */

object FEither {
  def apply[L, R](value: Future[Either[L, R]]): FEither[L, R] = new FEither[L, R](value)
  def apply[L, R](value: Either[L, R]): FEither[L, R] = new FEither[L, R](value.future)
  def left[L, R](value: L): FEither[L, R] = new FEither[L, R](Left(value).future)
  def fleft[L, R](value: Future[L])(implicit ec: ExecutionContext): FEither[L, R] = new FEither[L, R](value.map(v => Left(v)))
  def right[L, R](value: R): FEither[L, R] = new FEither[L, R](Right(value).future)
  def fright[L, R](value: Future[R])(implicit ec: ExecutionContext): FEither[L, R] = new FEither[L, R](value.map(v => Right(v)))
}

class FEither[L, R](val value: Future[Either[L, R]]) {

  def map[S](f: R => S)(implicit executor: ExecutionContext): FEither[L, S] = {
    val result = value.map {
      case Right(r)    => Right(f(r))
      case Left(error) => Left(error)
    }
    new FEither[L, S](result)
  }

  def flatMap[S](f: R => FEither[L, S])(implicit executor: ExecutionContext): FEither[L, S] = {
    val result = value.flatMap {
      case Right(r)    => f(r).value
      case Left(error) => Future.successful(Left(error))
    }
    new FEither(result)
  }

  def filter(f: R => Boolean)(implicit executor: ExecutionContext): FEither[L, R] = {
    val result = value.flatMap {
      case r @ Right(_) => r.future
      case Left(error)  => Future.successful(Left(error))
    }
    new FEither(result)
  }
}

