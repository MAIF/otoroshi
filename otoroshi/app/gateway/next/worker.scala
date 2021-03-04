package otoroshi.gateway.next

import java.io.File
import java.net.{InetSocketAddress, URLEncoder}
import java.security.{Provider, SecureRandom}
import java.util.concurrent.atomic.AtomicInteger
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.auth.{AuthModuleConfig, SessionCookieValues}
import com.google.common.base.Charsets
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import otoroshi.env.Env
import otoroshi.gateway._

import javax.net.ssl._
import otoroshi.models._
import otoroshi.utils.letsencrypt._
import otoroshi.utils.{RegexPool, TypedMap}
import otoroshi.utils.syntax.implicits._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.ahc.{AhcWSClient, AhcWSClientConfig}
import play.api.libs.ws.{WSClient, WSClientConfig, WSConfigParser}
import play.api.mvc.Results._
import play.api.mvc._
import play.api.mvc.request.{Cell, RequestAttrKey}
import play.api.{Configuration, Environment, Logger, Mode}
import otoroshi.security.OtoroshiClaim
import otoroshi.ssl.{ClientAuth, DynamicSSLEngineProvider, KeyManagerCompatibility, SSLSessionJavaHelper}

import scala.concurrent.Future
import scala.concurrent.duration._

class OtoroshiWorker(interface: String, router: OtoroshiRequestHandler, errorHandler: ErrorHandler, env: Env) {

  private implicit val system = ActorSystem("otoroshi-worker-next")
  private implicit val ec     = system.dispatcher
  private implicit val mat    = Materializer(system)
  private val logger          = Logger("otoroshi-worker")

  private val conversion             = play.core.server.otoroshi.PlayUtils.conversion(env.configuration)
  private val http                   = Http()
  private val cipherSuites           = env.configuration.getOptional[Seq[String]]("otoroshi.ssl.cipherSuitesJDK11")
  private val protocols              = env.configuration.getOptional[Seq[String]]("otoroshi.ssl.protocolsJDK11")
  private val clientAuth: ClientAuth = env.configuration
    .getOptionalWithFileSupport[String]("otoroshi.ssl.fromOutside.clientAuth")
    .flatMap(ClientAuth.apply)
    .getOrElse(ClientAuth.None)

  def fu: HttpResponse =
    HttpResponse(
      StatusCodes.BadGateway
    )

  private def remoteAddressOfRequest(req: HttpRequest): InetSocketAddress = {
    req.header[headers.`Remote-Address`] match {
      case Some(headers.`Remote-Address`(RemoteAddress.IP(ip, Some(port)))) =>
        new InetSocketAddress(ip, port)
      case _                                                                => throw new IllegalStateException("`Remote-Address` header was missing")
    }
  }

  private def handle(request: HttpRequest, secure: Boolean): Future[HttpResponse] = {
    val (rawRequestHeader, rawBody) = conversion.convertRequest(
      requestId = 0L,
      remoteAddress = remoteAddressOfRequest(request),
      secureProtocol = secure,
      request = request
    )
    val requestHeader               = rawRequestHeader.withAttrs(
      play.api.libs.typedmap.TypedMap(
        RequestAttrKey.Id      -> 0,
        RequestAttrKey.Cookies -> Cell(Cookies.fromCookieHeader(rawRequestHeader.headers.get("Cookie"))),
        RequestAttrKey.Flash   -> Cell(Flash.emptyCookie),
        RequestAttrKey.Session -> Cell(Session.emptyCookie)
      )
    )
    val body: Source[ByteString, _] = rawBody.fold(Source.single, identity)
    router.routeRequest(request, requestHeader, secure) match {
      case None          => fu.future
      case Some(handler) => {
        handler.apply(requestHeader, body).flatMap { result =>
          conversion.convertResult(requestHeader, result, request.protocol, errorHandler)
        }
      }
    }
  }

  private val httpBinding = http.bindAndHandleAsync(
    handler = r => handle(r, false),
    interface = interface,
    port = env.port,
    connectionContext = ConnectionContext.noEncryption(),
    settings = ServerSettings(system),
    parallelism = 0
  )

  private val httpsBinding = http.bindAndHandleAsync(
    handler = r => handle(r, true),
    interface = interface,
    port = env.httpsPort,
    connectionContext = ConnectionContext.https(
      sslContext = sslContext(),
      enabledCipherSuites = cipherSuites.map(_.toList),
      enabledProtocols = protocols.map(_.toList),
      clientAuth = clientAuth.toAkkaClientAuth.some
    ),
    settings = ServerSettings(system),
    parallelism = 0
  )

  logger.info(s"Otoroshi worker listening on http://0.0.0.0:${env.port} and https://0.0.0.0:${env.httpsPort}")

  private def sslContext(): SSLContext = {
    new SSLContext(
      new SSLContextSpi() {
        override def engineCreateSSLEngine(): SSLEngine                     =
          DynamicSSLEngineProvider.createSSLEngine(clientAuth, cipherSuites, protocols)
        override def engineCreateSSLEngine(s: String, i: Int): SSLEngine    = engineCreateSSLEngine()
        override def engineInit(
            keyManagers: Array[KeyManager],
            trustManagers: Array[TrustManager],
            secureRandom: SecureRandom
        ): Unit                                                             = ()
        override def engineGetClientSessionContext(): SSLSessionContext     =
          DynamicSSLEngineProvider.current.getClientSessionContext
        override def engineGetServerSessionContext(): SSLSessionContext     =
          DynamicSSLEngineProvider.current.getServerSessionContext
        override def engineGetSocketFactory(): SSLSocketFactory             =
          DynamicSSLEngineProvider.current.getSocketFactory
        override def engineGetServerSocketFactory(): SSLServerSocketFactory =
          DynamicSSLEngineProvider.current.getServerSocketFactory
      },
      new Provider(
        "Otoroshi SSlEngineProvider delegate",
        1d,
        "A provider that delegates callss to otoroshi dynamic one"
      )                   {},
      "Otoroshi SSLEngineProvider delegate"
    ) {}
  }

  def stop(): Future[Unit] = {
    for {
      binding  <- httpBinding
      sbinding <- httpsBinding
    } yield {
      binding.terminate(2.seconds)
      sbinding.terminate(2.seconds)
    }
  }
}

object OtoroshiWorkerTest {

  def main(args: Array[String]): Unit = {

    val system = ActorSystem("foo")
    val mat    = Materializer(system)

    val environment   = Environment(new File("."), classOf[OtoroshiWorker].getClassLoader, Mode.Prod)
    val configuration = Configuration(
      ConfigFactory.load().withValue("app.storage", ConfigValueFactory.fromAnyRef("file"))
    )

    val lifecycle = new ApplicationLifecycle {
      override def addStopHook(hook: () => Future[_]): Unit = sys.addShutdownHook { hook() }
      override def stop(): Future[_]                        = ().future
    }

    val circuitBreakersHolder: CircuitBreakersHolder = new CircuitBreakersHolder()

    val wsClient: WSClient = WSClientFactory.ahcClient(configuration, environment.classLoader)(mat)

    val env: Env = new Env(
      configuration = configuration,
      environment = environment,
      lifecycle = lifecycle,
      wsClient = wsClient,
      circuitBeakersHolder = circuitBreakersHolder,
      getHttpPort = None,
      getHttpsPort = None,
      testing = false
    )

    val reverseProxyAction = new ReverseProxyAction(env)
    val httpHandler        = new HttpHandler()(env)
    val webSocketHandler   = new WebSocketHandler()(env)
    val errorHandler       = new ErrorHandler()(env)
    val snowMonkey         = new SnowMonkey()(env)
    val requestHandler     = new OtoroshiRequestHandler(snowMonkey, httpHandler, webSocketHandler, reverseProxyAction)(
      env,
      env.otoroshiMaterializer
    )
    val worker             = new OtoroshiWorker("0.0.0.0", requestHandler, errorHandler, env)
    sys.addShutdownHook {
      worker.stop()
    }
  }
}

object WSClientFactory {
  def ahcClient(env: Env): AhcWSClient = {
    ahcClient(env.configuration, env.environment.classLoader)(env.otoroshiMaterializer)
  }
  def ahcClient(configuration: Configuration, cl: ClassLoader)(implicit mat: Materializer): AhcWSClient = {
    val parser: WSConfigParser         = new WSConfigParser(configuration.underlying, cl)
    val config: AhcWSClientConfig      = new AhcWSClientConfig(wsClientConfig = parser.parse()).copy(
      keepAlive = configuration.getOptionalWithFileSupport[Boolean]("app.proxy.keepAlive").getOrElse(true)
      //setHttpClientCodecMaxChunkSize(1024 * 100)
    )
    val wsClientConfig: WSClientConfig = config.wsClientConfig.copy(
      userAgent = Some("Otoroshi-AHC"),
      compressionEnabled =
        configuration.getOptionalWithFileSupport[Boolean]("app.proxy.compressionEnabled").getOrElse(false),
      idleTimeout = configuration
        .getOptionalWithFileSupport[Int]("app.proxy.idleTimeout")
        .map(_.millis)
        .getOrElse((2 * 60 * 1000).millis),
      connectionTimeout = configuration
        .getOptionalWithFileSupport[Int]("app.proxy.connectionTimeout")
        .map(_.millis)
        .getOrElse((2 * 60 * 1000).millis)
    )
    val ahcClient: AhcWSClient         = AhcWSClient(
      config.copy(
        wsClientConfig = wsClientConfig
      )
    )(mat)
    sys.addShutdownHook {
      ahcClient.close()
    }
    ahcClient
  }
}

class FakeActionBuilder {
  def async(f: RequestHeader => Future[Result]): FakeActionBuilder.Handler = { (req, body) =>
    f(req)
  }
  def apply(f: RequestHeader => Result): FakeActionBuilder.Handler = { (req, body) =>
    FastFuture.successful(f(req))
  }
}

object FakeActionBuilder {
  type Handler = (RequestHeader, Source[ByteString, _]) => Future[Result]
}

class OtoroshiRequestHandler(
    snowMonkey: SnowMonkey,
    httpHandler: HttpHandler,
    webSocketHandler: WebSocketHandler,
    reverseProxyAction: ReverseProxyAction
)(implicit env: Env, mat: Materializer) {

  import FakeActionBuilder.Handler
  import otoroshi.utils.http.RequestImplicits._

  implicit lazy val ec        = env.otoroshiExecutionContext
  implicit lazy val scheduler = env.otoroshiScheduler

  lazy val logger = Logger("otoroshi-http-handler")

  lazy val analyticsQueue = env.otoroshiActorSystem.actorOf(AnalyticsQueue.props(env))

  lazy val ipRegex         = RegexPool.regex(
    "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])(:\\d{2,5})?$"
  )
  lazy val monitoringPaths = Seq("/health", "/metrics")

  val reqCounter    = new AtomicInteger(0)
  val actionBuilder = new FakeActionBuilder()

  val headersInFiltered = Seq(
    env.Headers.OtoroshiState,
    env.Headers.OtoroshiClaim,
    env.Headers.OtoroshiRequestId,
    env.Headers.OtoroshiClientId,
    env.Headers.OtoroshiClientSecret,
    env.Headers.OtoroshiAuthorization,
    "Host",
    "X-Forwarded-For",
    "X-Forwarded-Proto",
    "X-Forwarded-Protocol",
    "Raw-Request-Uri",
    "Remote-Address",
    "Timeout-Access",
    "Tls-Session-Info"
  ).map(_.toLowerCase)

  val headersOutFiltered = Seq(
    env.Headers.OtoroshiStateResp,
    "Transfer-Encoding",
    "Content-Length",
    "Raw-Request-Uri",
    "Remote-Address",
    "Timeout-Access",
    "Tls-Session-Info"
  ).map(_.toLowerCase)

  def hasBody(request: Request[_]): Boolean = {
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
  }

  def matchRedirection(host: String): Boolean = {
    env.redirections.nonEmpty && env.redirections.exists(it => host.contains(it))
  }

  def badCertReply(_request: HttpRequest) =
    actionBuilder.async { req =>
      Errors.craftResponseResult(
        "No SSL/TLS certificate found for the current domain name. Connection refused !",
        NotFound,
        req,
        None,
        Some("errors.ssl.nocert"),
        attrs = TypedMap.empty
      )
    }

  def routeRequest(_request: HttpRequest, request: RequestHeader, secure: Boolean): Option[Handler] = {
    val config = env.datastores.globalConfigDataStore.latestSafe
    if (request.theSecured && config.isDefined && config.get.autoCert.enabled) { // && config.get.autoCert.replyNicely) { // to avoid cache effet
      request.headers.get("Tls-Session-Info").flatMap(SSLSessionJavaHelper.computeKey) match {
        case Some(key) => {
          KeyManagerCompatibility.session(key) match {
            case Some((_, _, chain))
                if chain.headOption.exists(_.getSubjectDN.getName.contains(SSLSessionJavaHelper.NotAllowed)) =>
              Some(badCertReply(_request))
            case a => internalRouteRequest(_request, request, secure)
          }
        }
        case _         => Some(badCertReply(_request)) // TODO: is it accurate ?
      }
    } else {
      internalRouteRequest(_request, request, secure)
    }
  }

  def internalRouteRequest(_request: HttpRequest, request: RequestHeader, secure: Boolean): Option[Handler] = {
    if (env.globalMaintenanceMode) {
      Some(globalMaintenanceMode(TypedMap.empty))
    } else {
      val isSecured    = request.theSecured
      val protocol     = request.theProtocol
      lazy val url     = ByteString(s"$protocol://${request.theHost}${request.relativeUri}")
      lazy val cookies = request.cookies.map(_.value).map(ByteString.apply)
      lazy val headers = request.headers.toSimpleMap.map(t => (ByteString.apply(t._1), ByteString.apply(t._2)))
      // logger.trace(s"[SIZE] url: ${url.size} bytes, cookies: ${cookies.map(_.size).mkString(", ")}, headers: ${headers.map(_.size).mkString(", ")}")
      if (env.clusterConfig.mode == otoroshi.cluster.ClusterMode.Worker && env.clusterAgent.cannotServeRequests()) {
        Some(clusterError("Waiting for first Otoroshi leader sync."))
      } else if (env.validateRequests && url.size > env.maxUrlLength) {
        Some(tooBig("URL should be smaller", UriTooLong))
      } else if (env.validateRequests && cookies.exists(_.size > env.maxCookieLength)) {
        Some(tooBig("Cookies should be smaller"))
      } else if (
        env.validateRequests && headers
          .exists(t => t._1.size > env.maxHeaderNameLength || t._2.size > env.maxHeaderValueLength)
      ) {
        Some(tooBig(s"Headers should be smaller"))
      } else {
        val toHttps    = env.exposedRootSchemeIsHttps
        val host       = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
        val monitoring = monitoringPaths.exists(p => request.relativeUri.startsWith(p))
        host match {
          case str if matchRedirection(str)                                           => Some(redirectToMainDomain())
          case _ if ipRegex.matches(request.theHost) && monitoring                    => ??? // TODO: need to handle it !!!!
          case _ if request.relativeUri.startsWith("/__otoroshi_private_apps_login")  => Some(setPrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/__otoroshi_private_apps_logout") =>
            Some(removePrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/.well-known/otoroshi/login")     => Some(setPrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/.well-known/otoroshi/logout")    => Some(removePrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/.well-known/otoroshi/me")        => Some(myProfile())
          case _ if request.relativeUri.startsWith("/.well-known/acme-challenge/")    => Some(letsEncrypt())
          case env.backOfficeHost if !isSecured && toHttps                            => Some(redirectToHttps())
          case env.privateAppsHost if !isSecured && toHttps                           => Some(redirectToHttps())
          case env.backOfficeHost if monitoring                                       => Some(forbidden())
          case env.privateAppsHost if monitoring                                      => Some(forbidden())
          case env.adminApiHost if monitoring                                         => ??? // TODO: need to handle it !!!!
          //case env.adminApiHost if env.exposeAdminApi                              => super.routeRequest(request)
          //case env.backOfficeHost if env.exposeAdminDashboard                      => super.routeRequest(request)
          //case env.privateAppsHost                                                 => super.routeRequest(request)
          case _                                                                      =>
            request.headers.get("Sec-WebSocket-Version") match {
              case None    =>
                val act = httpHandler.rawForwardCall(
                  reverseProxyAction,
                  analyticsQueue,
                  snowMonkey,
                  headersInFiltered,
                  headersOutFiltered
                )
                Some((req, body) => act(req, body))
              case Some(_) =>
                ???
              // Some(webSocketHandler.forwardCall(reverseProxyAction, snowMonkey, headersInFiltered, headersOutFiltered))
            }
        }
      }
    }
  }

  def letsEncrypt() =
    actionBuilder.async { req =>
      if (!req.theSecured) {
        env.datastores.globalConfigDataStore.latestSafe match {
          case None                                                => FastFuture.successful(InternalServerError(Json.obj("error" -> "no config found !")))
          case Some(config) if !config.letsEncryptSettings.enabled =>
            FastFuture.successful(InternalServerError(Json.obj("error" -> "config disabled !")))
          case Some(config)                                        => {
            val domain = req.theDomain
            val token  = req.relativeUri.split("\\?").head.replace("/.well-known/acme-challenge/", "")
            LetsEncryptHelper.getChallengeForToken(domain, token).map {
              case None       => NotFound(Json.obj("error" -> "token not found !"))
              case Some(body) => Ok(body.utf8String).as("text/plain")
            }
          }
        }
      } else {
        FastFuture.successful(InternalServerError(Json.obj("error" -> "no config found !")))
      }
    }

  def setPrivateAppsCookies() =
    actionBuilder.async { req =>
      val redirectToOpt: Option[String] = req.queryString.get("redirectTo").map(_.last)
      val sessionIdOpt: Option[String]  = req.queryString.get("sessionId").map(_.last)
      val hostOpt: Option[String]       = req.queryString.get("host").map(_.last)
      val cookiePrefOpt: Option[String] = req.queryString.get("cp").map(_.last)
      val maOpt: Option[Int]            = req.queryString.get("ma").map(_.last).map(_.toInt)
      val httpOnlyOpt: Option[Boolean]  = req.queryString.get("httpOnly").map(_.last).map(_.toBoolean)
      val secureOpt: Option[Boolean]    = req.queryString.get("secure").map(_.last).map(_.toBoolean)
      val hashOpt: Option[String]       = req.queryString.get("hash").map(_.last)

      (hashOpt.map(h => env.sign(req.theUrl.replace(s"&hash=$h", ""))), hashOpt) match {
        case (Some(hashedUrl), Some(hash)) if hashedUrl == hash =>
          (redirectToOpt, sessionIdOpt, hostOpt, cookiePrefOpt, maOpt, httpOnlyOpt, secureOpt) match {
            case (Some("urn:ietf:wg:oauth:2.0:oob"), Some(sessionId), Some(host), Some(cp), ma, httpOnly, secure) =>
              FastFuture.successful(
                Ok(views.html.oto.token(env.signPrivateSessionId(sessionId), env)).withCookies(
                  env.createPrivateSessionCookiesWithSuffix(
                    host,
                    sessionId,
                    cp,
                    ma.getOrElse(86400),
                    SessionCookieValues(httpOnly.getOrElse(true), secure.getOrElse(true))
                  ): _*
                )
              )
            case (Some(redirectTo), Some(sessionId), Some(host), Some(cp), ma, httpOnly, secure)                  =>
              FastFuture.successful(
                Redirect(redirectTo).withCookies(
                  env.createPrivateSessionCookiesWithSuffix(
                    host,
                    sessionId,
                    cp,
                    ma.getOrElse(86400),
                    SessionCookieValues(httpOnly.getOrElse(true), secure.getOrElse(true))
                  ): _*
                )
              )
            case _                                                                                                =>
              Errors.craftResponseResult(
                "Missing parameters",
                BadRequest,
                req,
                None,
                Some("errors.missing.parameters"),
                attrs = TypedMap.empty
              )
          }
        case (_, _)                                             =>
          logger.warn(s"Unsecure redirection from privateApps login to ${redirectToOpt.getOrElse("no url")}")
          Errors.craftResponseResult(
            "Invalid redirection url",
            BadRequest,
            req,
            None,
            Some("errors.invalid.redirection.url"),
            attrs = TypedMap.empty
          )
      }
    }

  def withAuthConfig(descriptor: ServiceDescriptor, req: RequestHeader, attrs: TypedMap)(
      f: AuthModuleConfig => Future[Result]
  ): Future[Result] = {
    descriptor.authConfigRef match {
      case None      =>
        Errors.craftResponseResult(
          "Auth. config. ref not found on the descriptor",
          Results.InternalServerError,
          req,
          Some(descriptor),
          Some("errors.auth.config.ref.not.found"),
          attrs = attrs
        )
      case Some(ref) => {
        env.datastores.authConfigsDataStore.findById(ref).flatMap {
          case None       =>
            Errors.craftResponseResult(
              "Auth. config. not found on the descriptor",
              Results.InternalServerError,
              req,
              Some(descriptor),
              Some("errors.auth.config.not.found"),
              attrs = attrs
            )
          case Some(auth) => f(auth)
        }
      }
    }
  }

  def myProfile() =
    actionBuilder.async { req =>
      implicit val request = req

      val attrs = TypedMap.empty

      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        ServiceLocation(req.theHost, globalConfig) match {
          case None                                                 => {
            Errors.craftResponseResult(
              s"Service not found",
              NotFound,
              req,
              None,
              Some("errors.service.not.found"),
              attrs = attrs
            )
          }
          case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
            env.datastores.serviceDescriptorDataStore
              .find(
                ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap),
                req,
                attrs
              )
              .flatMap {
                case None                                                                                      => {
                  Errors.craftResponseResult(
                    s"Service not found",
                    NotFound,
                    req,
                    None,
                    Some("errors.service.not.found"),
                    attrs = attrs
                  )
                }
                case Some(desc) if !desc.enabled                                                               => {
                  Errors.craftResponseResult(
                    s"Service not found",
                    NotFound,
                    req,
                    None,
                    Some("errors.service.not.found"),
                    attrs = attrs
                  )
                }
                // case Some(descriptor) if !descriptor.privateApp => {
                //   Errors.craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
                // }
                case Some(descriptor)
                    if !descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id && descriptor
                      .isUriPublic(req.path) => {
                  // Public service, no profile but no error either ???
                  FastFuture.successful(Ok(Json.obj("access_type" -> "public")))
                }
                case Some(descriptor)
                    if !descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id && !descriptor
                      .isUriPublic(req.path) => {
                  // ApiKey
                  ApiKeyHelper.extractApiKey(req, descriptor, attrs).flatMap {
                    case None         =>
                      Errors
                        .craftResponseResult(
                          s"Invalid API key",
                          Unauthorized,
                          req,
                          None,
                          Some("errors.invalid.api.key"),
                          attrs = attrs
                        )
                    case Some(apiKey) =>
                      FastFuture.successful(Ok(apiKey.lightJson ++ Json.obj("access_type" -> "apikey")))
                  }
                }
                case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
                  withAuthConfig(descriptor, req, attrs) { auth =>
                    PrivateAppsUserHelper.isPrivateAppsSessionValid(req, descriptor, attrs).flatMap {
                      case None          =>
                        Errors.craftResponseResult(
                          s"Invalid session",
                          Unauthorized,
                          req,
                          None,
                          Some("errors.invalid.session"),
                          attrs = attrs
                        )
                      case Some(session) =>
                        FastFuture.successful(Ok(session.profile.as[JsObject] ++ Json.obj("access_type" -> "session")))
                    }
                  }
                }
                case _                                                                                         => {
                  Errors.craftResponseResult(
                    s"Unauthorized",
                    Unauthorized,
                    req,
                    None,
                    Some("errors.unauthorized"),
                    attrs = attrs
                  )
                }
              }
          }
        }
      }
    }

  def removePrivateAppsCookies() =
    actionBuilder.async { req =>
      implicit val request = req

      val attrs = TypedMap.empty

      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        ServiceLocation(req.theHost, globalConfig) match {
          case None                                                 => {
            Errors.craftResponseResult(
              s"Service not found for URL ${req.theHost}::${req.relativeUri}",
              NotFound,
              req,
              None,
              Some("errors.service.not.found"),
              attrs = attrs
            )
          }
          case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
            env.datastores.serviceDescriptorDataStore
              .find(
                ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap),
                req,
                attrs
              )
              .flatMap {
                case None                                                                                      => {
                  Errors.craftResponseResult(
                    s"Service not found",
                    NotFound,
                    req,
                    None,
                    Some("errors.service.not.found"),
                    attrs = attrs
                  )
                }
                case Some(desc) if !desc.enabled                                                               => {
                  Errors.craftResponseResult(
                    s"Service not found",
                    NotFound,
                    req,
                    None,
                    Some("errors.service.not.found"),
                    attrs = attrs
                  )
                }
                case Some(descriptor) if !descriptor.privateApp                                                => {
                  Errors.craftResponseResult(
                    s"Private apps are not configured",
                    InternalServerError,
                    req,
                    None,
                    Some("errors.service.auth.not.configured"),
                    attrs = attrs
                  )
                }
                case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
                  withAuthConfig(descriptor, req, attrs) { auth =>
                    auth.authModule(globalConfig).paLogout(req, globalConfig, descriptor).map {
                      case None            => {
                        val cookieOpt     = request.cookies.find(c => c.name.startsWith("oto-papps-"))
                        cookieOpt.flatMap(env.extractPrivateSessionId).map { id =>
                          env.datastores.privateAppsUserDataStore.findById(id).map(_.foreach(_.delete()))
                        }
                        val finalRedirect = req.getQueryString("redirect").getOrElse(s"http://${req.theHost}")
                        val redirectTo    = env.rootScheme + env.privateAppsHost + env.privateAppsPort
                          .map(a => s":$a")
                          .getOrElse("") + otoroshi.controllers.routes.AuthController
                          .confidentialAppLogout()
                          .url + s"?redirectTo=${finalRedirect}&host=${req.theHost}&cp=${auth.cookieSuffix(descriptor)}"
                        logger.trace("should redirect to " + redirectTo)
                        Redirect(redirectTo)
                          .discardingCookies(env.removePrivateSessionCookies(req.theHost, descriptor, auth): _*)
                      }
                      case Some(logoutUrl) => {
                        val cookieOpt         = request.cookies.find(c => c.name.startsWith("oto-papps-"))
                        cookieOpt.flatMap(env.extractPrivateSessionId).map { id =>
                          env.datastores.privateAppsUserDataStore.findById(id).map(_.foreach(_.delete()))
                        }
                        val finalRedirect     = req.getQueryString("redirect").getOrElse(s"http://${req.theHost}")
                        val redirectTo        = env.rootScheme + env.privateAppsHost + env.privateAppsPort
                          .map(a => s":$a")
                          .getOrElse("") + otoroshi.controllers.routes.AuthController
                          .confidentialAppLogout()
                          .url + s"?redirectTo=${finalRedirect}&host=${req.theHost}&cp=${auth.cookieSuffix(descriptor)}"
                        val actualRedirectUrl = logoutUrl.replace("${redirect}", URLEncoder.encode(redirectTo, "UTF-8"))
                        logger.trace("should redirect to " + actualRedirectUrl)
                        Redirect(actualRedirectUrl)
                          .discardingCookies(env.removePrivateSessionCookies(req.theHost, descriptor, auth): _*)
                      }
                    }
                  }
                }
                case _                                                                                         => {
                  Errors.craftResponseResult(
                    s"Private apps are not configured",
                    InternalServerError,
                    req,
                    None,
                    Some("errors.service.auth.not.configured"),
                    attrs = attrs
                  )
                }
              }
          }
        }
      }
    }

  def clusterError(message: String) =
    actionBuilder.async { req =>
      Errors.craftResponseResult(
        message,
        InternalServerError,
        req,
        None,
        Some("errors.no.cluster.state.yet"),
        attrs = TypedMap.empty
      )
    }

  def tooBig(message: String, status: Results.Status = BadRequest) =
    actionBuilder.async { req =>
      Errors.craftResponseResult(message, BadRequest, req, None, Some("errors.entity.too.big"), attrs = TypedMap.empty)
    }

  def globalMaintenanceMode(attrs: TypedMap) =
    actionBuilder.async { req =>
      Errors.craftResponseResult(
        "Service in maintenance mode",
        ServiceUnavailable,
        req,
        None,
        Some("errors.service.in.maintenance"),
        attrs = attrs
      )
    }

  def forbidden() =
    actionBuilder { req =>
      Forbidden(Json.obj("error" -> "forbidden"))
    }

  def redirectToHttps() =
    actionBuilder { req =>
      val domain   = req.theDomain
      val protocol = req.theProtocol
      logger.trace(
        s"redirectToHttps from ${protocol}://$domain${req.relativeUri} to ${env.rootScheme}$domain${req.relativeUri}"
      )
      Redirect(s"${env.rootScheme}$domain${req.relativeUri}").withHeaders("otoroshi-redirect-to" -> "https")
    }

  def redirectToMainDomain() =
    actionBuilder { req =>
      val domain: String = env.redirections.foldLeft(req.theDomain)((domain, item) => domain.replace(item, env.domain))
      val protocol       = req.theProtocol
      logger.debug(
        s"redirectToMainDomain from $protocol://${req.theDomain}${req.relativeUri} to $protocol://$domain${req.relativeUri}"
      )
      Redirect(s"$protocol://$domain${req.relativeUri}")
    }

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)
}
