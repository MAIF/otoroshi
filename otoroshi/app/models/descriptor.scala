package models

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import auth._
import env.Env
import gateway.Errors
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}
import security.IdGenerator
import storage.BasicStore
import utils.ReplaceAllWith

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class ServiceDescriptorQuery(subdomain: String,
                                  line: String = "prod",
                                  domain: String,
                                  root: String = "/",
                                  matchingHeaders: Map[String, String] = Map.empty[String, String]) {

  def asKey(implicit _env: Env): String = s"${_env.storageRoot}:desclookup:$line:$domain:$subdomain:$root"

  def toHost: String = subdomain match {
    case s if s.isEmpty                   => s"$line.$domain"
    case s if s.isEmpty && line == "prod" => s"$domain"
    case s if line == "prod"              => s"$subdomain.$domain"
    case s                                => s"$subdomain.$line.$domain"
  }

  def toDevHost: String = subdomain match {
    case s if s.isEmpty                   => s"dev.$domain"
    case s if s.isEmpty && line == "prod" => s"$domain"
    case s if line == "prod"              => s"$subdomain.$domain"
    case s                                => s"$subdomain.dev.$domain"
  }

  private val existsCache     = new java.util.concurrent.ConcurrentHashMap[String, Boolean]
  private val serviceIdsCache = new java.util.concurrent.ConcurrentHashMap[String, Seq[String]]
  private val servicesCache   = new java.util.concurrent.ConcurrentHashMap[String, Seq[ServiceDescriptor]]

  def exists()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val key = this.asKey
    if (!existsCache.containsKey(key)) {
      env.datastores.serviceDescriptorDataStore.fastLookupExists(this).andThen {
        case scala.util.Success(ex) => existsCache.put(key, ex)
      }
    } else {
      env.datastores.serviceDescriptorDataStore.fastLookupExists(this).andThen {
        case scala.util.Success(ex) => existsCache.put(key, ex)
      }
      FastFuture.successful(existsCache.get(key))
    }
  }

  def get()(implicit ec: ExecutionContext, env: Env): Future[Seq[String]] = {
    val key = this.asKey
    if (!serviceIdsCache.containsKey(key)) {
      env.datastores.serviceDescriptorDataStore.getFastLookups(this).andThen {
        case scala.util.Success(ex) => serviceIdsCache.put(key, ex)
      }
    } else {
      env.datastores.serviceDescriptorDataStore.getFastLookups(this).andThen {
        case scala.util.Success(ex) => serviceIdsCache.put(key, ex)
      }
      FastFuture.successful(serviceIdsCache.get(key))
    }
  }

  def getServices()(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] = {
    val key = this.asKey
    get().flatMap { ids =>
      if (!servicesCache.containsKey(key)) {
        env.datastores.serviceDescriptorDataStore.findAllById(ids).andThen {
          case scala.util.Success(ex) => servicesCache.put(key, ex)
        }
      } else {
        env.datastores.serviceDescriptorDataStore.findAllById(ids).andThen {
          case scala.util.Success(ex) => servicesCache.put(key, ex)
        }
        FastFuture.successful(servicesCache.get(key))
      }
    }
  }

  def addServices(services: Seq[ServiceDescriptor])(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val key = this.asKey
    existsCache.put(key, true)
    serviceIdsCache.put(key, services.map(_.id))
    servicesCache.put(key, services)
    env.datastores.serviceDescriptorDataStore.addFastLookups(this, services)
  }

  def remServices(services: Seq[ServiceDescriptor])(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val key        = this.asKey
    val servicesId = services.map(_.id)
    val resulting =
      if (servicesCache.containsKey(key)) servicesCache.get(key).filterNot(s => servicesId.contains(s.id))
      else Seq.empty[ServiceDescriptor]
    if (resulting.isEmpty) {
      existsCache.put(key, false)
      servicesCache.remove(key)
      serviceIdsCache.remove(key)
    } else {
      existsCache.put(key, true)
      serviceIdsCache.put(key, resulting.map(_.id))
      servicesCache.put(key, resulting)
    }
    env.datastores.serviceDescriptorDataStore.removeFastLookups(this, services)
  }
}

case class ServiceLocation(domain: String, env: String, subdomain: String)

object ServiceLocation {

  def fullQuery(host: String, config: GlobalConfig): Option[ServiceLocation] = {
    val hostName = if (host.contains(":")) host.split(":")(0) else host
    hostName.split("\\.").toSeq.reverse match {
      case Seq(tld, domain, env, tail @ _*) if tail.nonEmpty && config.lines.contains(env) =>
        Some(ServiceLocation(s"$domain.$tld", env, tail.reverse.mkString(".")))
      case Seq(tld, domain, tail @ _*) if tail.nonEmpty =>
        Some(ServiceLocation(s"$domain.$tld", "prod", tail.reverse.mkString(".")))
      case Seq(domain, subdomain) => Some(ServiceLocation(s"$domain", "prod", subdomain))
      case Seq(domain)            => Some(ServiceLocation(s"$domain", "prod", ""))
      case _                      => None
    }
  }

  def apply(host: String, config: GlobalConfig): Option[ServiceLocation] = fullQuery(host, config)
}

case class ApiDescriptor(exposeApi: Boolean = false, openApiDescriptorUrl: Option[String] = None) {
  def toJson = ApiDescriptor.format.writes(this)
}

object ApiDescriptor {
  implicit val format = Json.format[ApiDescriptor]
}

case class BaseQuotas(throttlingQuota: Long = BaseQuotas.MaxValue,
                      dailyQuota: Long = BaseQuotas.MaxValue,
                      monthlyQuota: Long = BaseQuotas.MaxValue) {
  def toJson = BaseQuotas.format.writes(this)
}

object BaseQuotas {
  implicit val format = Json.format[BaseQuotas]
  val MaxValue: Long  = RemainingQuotas.MaxValue
}

case class Target(host: String, scheme: String = "https") {
  def toJson = Target.format.writes(this)
  def asUrl  = s"${scheme}://$host"
}

object Target {
  implicit val format = Json.format[Target]
}

case class IpFiltering(whitelist: Seq[String] = Seq.empty[String], blacklist: Seq[String] = Seq.empty[String]) {
  def toJson = IpFiltering.format.writes(this)
}

object IpFiltering {
  implicit val format = Json.format[IpFiltering]
}

case class HealthCheck(enabled: Boolean, url: String) {
  def toJson = HealthCheck.format.writes(this)
}

object HealthCheck {
  implicit val format = Json.format[HealthCheck]
}

case class ClientConfig(
    useCircuitBreaker: Boolean = true,
    retries: Int = 1,
    maxErrors: Int = 20,
    retryInitialDelay: Long = 50,
    backoffFactor: Long = 2,
    callTimeout: Long = 30000,
    globalTimeout: Long = 30000,
    sampleInterval: Long = 2000
) {
  def toJson = ClientConfig.format.writes(this)
}

object ClientConfig {

  lazy val logger = Logger("otoroshi-client-config")

  implicit val format = new Format[ClientConfig] {

    override def reads(json: JsValue): JsResult[ClientConfig] =
      Try {
        ClientConfig(
          useCircuitBreaker = (json \ "useCircuitBreaker").asOpt[Boolean].getOrElse(true),
          retries = (json \ "retries").asOpt[Int].getOrElse(1),
          maxErrors = (json \ "maxErrors").asOpt[Int].getOrElse(20),
          retryInitialDelay = (json \ "retryInitialDelay").asOpt[Long].getOrElse(50),
          backoffFactor = (json \ "backoffFactor").asOpt[Long].getOrElse(2),
          callTimeout = (json \ "callTimeout").asOpt[Long].getOrElse(30000),
          globalTimeout = (json \ "globalTimeout").asOpt[Long].getOrElse(30000),
          sampleInterval = (json \ "sampleInterval").asOpt[Long].getOrElse(2000)
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading ClientConfig", t)
          JsError(t.getMessage)
      } get

    override def writes(o: ClientConfig): JsValue = Json.obj(
      "useCircuitBreaker" -> o.useCircuitBreaker,
      "retries"           -> o.retries,
      "maxErrors"         -> o.maxErrors,
      "retryInitialDelay" -> o.retryInitialDelay,
      "backoffFactor"     -> o.backoffFactor,
      "callTimeout"       -> o.callTimeout,
      "globalTimeout"     -> o.globalTimeout,
      "sampleInterval"    -> o.sampleInterval
    )
  }
}

case class Canary(
    enabled: Boolean = false,
    traffic: Double = 0.2,
    targets: Seq[Target] = Seq.empty[Target],
    root: String = "/"
) {
  def toJson = Canary.format.writes(this)
}

object Canary {

  lazy val logger = Logger("otoroshi-canary")

  implicit val format = new Format[Canary] {
    override def reads(json: JsValue): JsResult[Canary] =
      Try {
        Canary(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          traffic = (json \ "traffic").asOpt[Double].getOrElse(0.2),
          targets = (json \ "targets")
            .asOpt[JsArray]
            .map(_.value.map(e => Target.format.reads(e).get))
            .getOrElse(Seq.empty[Target]),
          root = (json \ "root").asOpt[String].getOrElse("/")
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading Canary", t)
          JsError(t.getMessage)
      } get

    override def writes(o: Canary): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "traffic" -> o.traffic,
      "targets" -> JsArray(o.targets.map(_.toJson)),
      "root"    -> o.root
    )
  }
}

object RedirectionExpressionLanguage {

  import kaleidoscope._
  import utils.RequestImplicits._

  lazy val logger = Logger("otoroshi-redirection-el")

  val expressionReplacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

  def apply(value: String, req: RequestHeader): String = {
    value match {
      case v if v.contains("${") =>
        Try {
          expressionReplacer.replaceOn(value) { expression =>
            expression match {
              case "req.path"                 => req.path
              case "req.uri"                  => req.relativeUri
              case "req.host"                 => req.host
              case "req.domain"               => req.domain
              case "req.method"               => req.method
              case "req.protocol"             => req.theProtocol
              case r"req.headers.$field@(.*)" => req.headers.get(field).getOrElse(s"no-header-$field")
              case r"req.query.$field@(.*)"   => req.getQueryString(field).getOrElse(s"no-query-$field")
              case _                          => "bad-expr"
            }
          }
        } recover {
          case e =>
            logger.error(s"Error while parsing expression, returning raw value: $value", e)
            value
        } get
      case _ => value
    }
  }
}

case class RedirectionSettings(enabled: Boolean = false, code: Int = 303, to: String = "https://www.otoroshi.io") {
  def toJson                                      = RedirectionSettings.format.writes(this)
  def hasValidCode                                = RedirectionSettings.validRedirectionCodes.contains(code)
  def formattedTo(request: RequestHeader): String = RedirectionExpressionLanguage(to, request)
}

object RedirectionSettings {

  lazy val logger = Logger("otoroshi-redirection-settings")

  val validRedirectionCodes = Seq(301, 308, 302, 303, 307)

  implicit val format = new Format[RedirectionSettings] {
    override def reads(json: JsValue): JsResult[RedirectionSettings] =
      Try {
        RedirectionSettings(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          code = (json \ "code").asOpt[Int].getOrElse(303),
          to = (json \ "to").asOpt[String].getOrElse("https://www.otoroshi.io")
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading RedirectionSettings", t)
          JsError(t.getMessage)
      } get

    override def writes(o: RedirectionSettings): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "code"    -> o.code,
      "to"      -> o.to
    )
  }
}

case class ServiceDescriptor(
    id: String,
    groupId: String = "default",
    name: String,
    env: String,
    domain: String,
    subdomain: String,
    targets: Seq[Target] = Seq.empty[Target],
    root: String = "/",
    matchingRoot: Option[String] = None,
    localHost: String = "localhost:8080",
    localScheme: String = "http",
    redirectToLocal: Boolean = false,
    enabled: Boolean = true,
    userFacing: Boolean = false,
    privateApp: Boolean = false,
    forceHttps: Boolean = true,
    maintenanceMode: Boolean = false,
    buildMode: Boolean = false,
    strictlyPrivate: Boolean = false,
    enforceSecureCommunication: Boolean = true,
    sendStateChallenge: Boolean = true,
    sendOtoroshiHeadersBack: Boolean = true,
    readOnly: Boolean = false,
    xForwardedHeaders: Boolean = false,
    secComExcludedPatterns: Seq[String] = Seq.empty[String],
    securityExcludedPatterns: Seq[String] = Seq.empty[String],
    publicPatterns: Seq[String] = Seq.empty[String],
    privatePatterns: Seq[String] = Seq.empty[String],
    additionalHeaders: Map[String, String] = Map.empty[String, String],
    additionalHeadersOut: Map[String, String] = Map.empty[String, String],
    matchingHeaders: Map[String, String] = Map.empty[String, String],
    ipFiltering: IpFiltering = IpFiltering(),
    api: ApiDescriptor = ApiDescriptor(false, None),
    healthCheck: HealthCheck = HealthCheck(false, "/"),
    clientConfig: ClientConfig = ClientConfig(),
    canary: Canary = Canary(),
    metadata: Map[String, String] = Map.empty[String, String],
    chaosConfig: ChaosConfig = ChaosConfig(),
    jwtVerifier: JwtVerifier = RefJwtVerifier(),
    secComSettings: AlgoSettings = HSAlgoSettings(
      512,
      "${config.app.claim.sharedKey}"
    ),
    authConfigRef: Option[String] = None,
    cors: CorsSettings = CorsSettings(false),
    redirection: RedirectionSettings = RedirectionSettings(false),
    clientValidatorRef: Option[String] = None,
    transformerRef: Option[String] = None
) {

  def toHost: String = subdomain match {
    case s if s.isEmpty                  => s"$env.$domain"
    case s if s.isEmpty && env == "prod" => s"$domain"
    case s if env == "prod"              => s"$subdomain.$domain"
    case s                               => s"$subdomain.$env.$domain"
  }

  def toDevHost: String = subdomain match {
    case s if s.isEmpty => s"dev.$domain"
    case s              => s"$subdomain.dev.$domain"
  }

  def target: Target                                    = targets.head
  def save()(implicit ec: ExecutionContext, env: Env)   = env.datastores.serviceDescriptorDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.serviceDescriptorDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.serviceDescriptorDataStore.exists(this)
  def toJson                                            = ServiceDescriptor.toJson(this)
  def group(implicit ec: ExecutionContext, env: Env): Future[Option[ServiceGroup]] =
    env.datastores.serviceGroupDataStore.findById(groupId)
  def isUp(implicit ec: ExecutionContext, env: Env): Future[Boolean] = FastFuture.successful(true)
  // not useful anymore as circuit breakers should do the work
  // env.datastores.healthCheckDataStore.findLast(this).map(_.map(_.isUp).getOrElse(true))
  // TODO : check perfs
  // def isUriPublic(uri: String): Boolean = !privatePatterns.exists(p => uri.matches(p)) && publicPatterns.exists(p => uri.matches(p))
  def isUriPublic(uri: String): Boolean =
    !privatePatterns.exists(p => utils.RegexPool.regex(p).matches(uri)) && publicPatterns.exists(
      p => utils.RegexPool.regex(p).matches(uri)
    )

  def isExcludedFromSecurity(uri: String): Boolean = {
    securityExcludedPatterns.exists(
      p => utils.RegexPool.regex(p).matches(uri)
    )
  }

  def isUriExcludedFromSecuredCommunication(uri: String): Boolean =
    secComExcludedPatterns.exists(p => utils.RegexPool.regex(p).matches(uri))
  def isPrivate = privateApp
  def updateMetrics(callDuration: Long,
                    callOverhead: Long,
                    dataIn: Long,
                    dataOut: Long,
                    upstreamLatency: Long,
                    config: models.GlobalConfig)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Unit] =
    env.datastores.serviceDescriptorDataStore.updateMetrics(id,
                                                            callDuration,
                                                            callOverhead,
                                                            dataIn,
                                                            dataOut,
                                                            upstreamLatency,
                                                            config)
  def theScheme: String     = if (forceHttps) "https://" else "http://"
  def theLine: String       = if (env == "prod") "" else s".$env"
  def theDomain             = if (s"$subdomain$theLine".isEmpty) domain else s".$$subdomain$theLine"
  def exposedDomain: String = s"$theScheme://$subdomain$theLine$theDomain"

  def validateClientCertificates(
      req: RequestHeader,
      apikey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  )(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    clientValidatorRef.map { ref =>
      env.datastores.clientCertificateValidationDataStore.findById(ref).flatMap {
        case Some(validator) => validator.validateClientCertificates(req, this, apikey, user)(f)
        case None =>
          Errors.craftResponseResult(
            "Validator not found",
            Results.InternalServerError,
            req,
            None,
            None
          )
      }
    } getOrElse f
  }

  import play.api.http.websocket.{Message => PlayWSMessage}

  def wsValidateClientCertificates(req: RequestHeader,
                                   apikey: Option[ApiKey] = None,
                                   user: Option[PrivateAppsUser] = None)(
      f: => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    clientValidatorRef.map { ref =>
      env.datastores.clientCertificateValidationDataStore.findById(ref).flatMap {
        case Some(validator) => validator.wsValidateClientCertificates(req, this, apikey, user)(f)
        case None =>
          Errors
            .craftResponseResult(
              "Validator not found",
              Results.InternalServerError,
              req,
              None,
              None
            )
            .map(Left.apply)
      }
    } getOrElse f
  }
}

object ServiceDescriptor {

  lazy val logger = Logger("otoroshi-service-descriptor")

  val _fmt: Format[ServiceDescriptor] = new Format[ServiceDescriptor] {

    override def reads(json: JsValue): JsResult[ServiceDescriptor] =
      Try {
        ServiceDescriptor(
          id = (json \ "id").as[String],
          groupId = (json \ "groupId").as[String],
          name = (json \ "name").asOpt[String].getOrElse((json \ "id").as[String]),
          env = (json \ "env").asOpt[String].getOrElse("prod"),
          domain = (json \ "domain").as[String],
          subdomain = (json \ "subdomain").as[String],
          targets = (json \ "targets")
            .asOpt[JsArray]
            .map(_.value.map(e => Target.format.reads(e).get))
            .getOrElse(Seq.empty[Target]),
          root = (json \ "root").asOpt[String].getOrElse("/"),
          matchingRoot = (json \ "matchingRoot").asOpt[String].filter(_.nonEmpty),
          localHost = (json \ "localHost").asOpt[String].getOrElse("localhost:8080"),
          localScheme = (json \ "localScheme").asOpt[String].getOrElse("http"),
          redirectToLocal = (json \ "redirectToLocal").asOpt[Boolean].getOrElse(false),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
          userFacing = (json \ "userFacing").asOpt[Boolean].getOrElse(false),
          privateApp = (json \ "privateApp").asOpt[Boolean].getOrElse(false),
          forceHttps = (json \ "forceHttps").asOpt[Boolean].getOrElse(true),
          maintenanceMode = (json \ "maintenanceMode").asOpt[Boolean].getOrElse(false),
          buildMode = (json \ "buildMode").asOpt[Boolean].getOrElse(false),
          strictlyPrivate = (json \ "strictlyPrivate").asOpt[Boolean].getOrElse(false),
          enforceSecureCommunication = (json \ "enforceSecureCommunication").asOpt[Boolean].getOrElse(true),
          sendStateChallenge = (json \ "sendStateChallenge").asOpt[Boolean].getOrElse(true),
          sendOtoroshiHeadersBack = (json \ "sendOtoroshiHeadersBack").asOpt[Boolean].getOrElse(true),
          readOnly = (json \ "readOnly").asOpt[Boolean].getOrElse(false),
          xForwardedHeaders = (json \ "xForwardedHeaders").asOpt[Boolean].getOrElse(false),
          secComExcludedPatterns = (json \ "secComExcludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          securityExcludedPatterns = (json \ "securityExcludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          publicPatterns = (json \ "publicPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          privatePatterns = (json \ "privatePatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          additionalHeaders =
            (json \ "additionalHeaders").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          additionalHeadersOut =
            (json \ "additionalHeadersOut").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          matchingHeaders = (json \ "matchingHeaders").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          ipFiltering = (json \ "ipFiltering").asOpt(IpFiltering.format).getOrElse(IpFiltering()),
          api = (json \ "api").asOpt(ApiDescriptor.format).getOrElse(ApiDescriptor(false, None)),
          healthCheck = (json \ "healthCheck").asOpt(HealthCheck.format).getOrElse(HealthCheck(false, "/")),
          clientConfig = (json \ "clientConfig").asOpt(ClientConfig.format).getOrElse(ClientConfig()),
          canary = (json \ "canary").asOpt(Canary.format).getOrElse(Canary()),
          metadata = (json \ "metadata")
            .asOpt[Map[String, String]]
            .map(_.filter(_._1.nonEmpty))
            .getOrElse(Map.empty[String, String]),
          chaosConfig = (json \ "chaosConfig").asOpt(ChaosConfig._fmt).getOrElse(ChaosConfig()),
          jwtVerifier = JwtVerifier
            .fromJson((json \ "jwtVerifier").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(RefJwtVerifier()),
          secComSettings = AlgoSettings
            .fromJson((json \ "secComSettings").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(HSAlgoSettings(512, "${config.app.claim.sharedKey}")),
          authConfigRef = (json \ "authConfigRef").asOpt[String].filterNot(_.trim.isEmpty),
          clientValidatorRef = (json \ "clientValidatorRef").asOpt[String].filterNot(_.trim.isEmpty),
          transformerRef = (json \ "transformerRef").asOpt[String].filterNot(_.trim.isEmpty),
          cors = CorsSettings.fromJson((json \ "cors").asOpt[JsValue].getOrElse(JsNull)).getOrElse(CorsSettings(false)),
          redirection = RedirectionSettings.format
            .reads((json \ "redirection").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(RedirectionSettings(false))
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading ServiceDescriptor", t)
          JsError(t.getMessage)
      } get

    override def writes(sd: ServiceDescriptor): JsValue = Json.obj(
      "id"                         -> sd.id,
      "groupId"                    -> sd.groupId,
      "name"                       -> sd.name,
      "env"                        -> sd.env,
      "domain"                     -> sd.domain,
      "subdomain"                  -> sd.subdomain,
      "targets"                    -> JsArray(sd.targets.map(_.toJson)),
      "root"                       -> sd.root,
      "matchingRoot"               -> sd.matchingRoot,
      "localHost"                  -> sd.localHost,
      "localScheme"                -> sd.localScheme,
      "redirectToLocal"            -> sd.redirectToLocal,
      "enabled"                    -> sd.enabled,
      "userFacing"                 -> sd.userFacing,
      "privateApp"                 -> sd.privateApp,
      "forceHttps"                 -> sd.forceHttps,
      "maintenanceMode"            -> sd.maintenanceMode,
      "buildMode"                  -> sd.buildMode,
      "strictlyPrivate"            -> sd.strictlyPrivate,
      "enforceSecureCommunication" -> sd.enforceSecureCommunication,
      "sendStateChallenge"         -> sd.sendStateChallenge,
      "sendOtoroshiHeadersBack"    -> sd.sendOtoroshiHeadersBack,
      "readOnly"                   -> sd.readOnly,
      "xForwardedHeaders"          -> sd.xForwardedHeaders,
      "secComExcludedPatterns"     -> JsArray(sd.secComExcludedPatterns.map(JsString.apply)),
      "securityExcludedPatterns"   -> JsArray(sd.securityExcludedPatterns.map(JsString.apply)),
      "publicPatterns"             -> JsArray(sd.publicPatterns.map(JsString.apply)),
      "privatePatterns"            -> JsArray(sd.privatePatterns.map(JsString.apply)),
      "additionalHeaders"          -> JsObject(sd.additionalHeaders.mapValues(JsString.apply)),
      "additionalHeadersOut"       -> JsObject(sd.additionalHeadersOut.mapValues(JsString.apply)),
      "matchingHeaders"            -> JsObject(sd.matchingHeaders.mapValues(JsString.apply)),
      "ipFiltering"                -> sd.ipFiltering.toJson,
      "api"                        -> sd.api.toJson,
      "healthCheck"                -> sd.healthCheck.toJson,
      "clientConfig"               -> sd.clientConfig.toJson,
      "canary"                     -> sd.canary.toJson,
      "metadata"                   -> JsObject(sd.metadata.filter(_._1.nonEmpty).mapValues(JsString.apply)),
      "chaosConfig"                -> sd.chaosConfig.asJson,
      "jwtVerifier"                -> sd.jwtVerifier.asJson,
      "secComSettings"             -> sd.secComSettings.asJson,
      "cors"                       -> sd.cors.asJson,
      "redirection"                -> sd.redirection.toJson,
      "authConfigRef"              -> sd.authConfigRef,
      "clientValidatorRef"         -> sd.clientValidatorRef,
      "transformerRef"             -> sd.transformerRef
    )
  }
  def toJson(value: ServiceDescriptor): JsValue = _fmt.writes(value)
  def fromJsons(value: JsValue): ServiceDescriptor =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): JsResult[ServiceDescriptor] = _fmt.reads(value)
}

trait ServiceDescriptorDataStore extends BasicStore[ServiceDescriptor] {
  def initiateNewDescriptor()(implicit env: Env): ServiceDescriptor =
    ServiceDescriptor(
      id = IdGenerator.token(64),
      name = "my-service",
      env = "prod",
      domain = env.domain,
      subdomain = "myservice",
      targets = Seq(
        Target(
          host = "changeme.cleverapps.io",
          scheme = "https"
        )
      ),
      privateApp = false,
      sendOtoroshiHeadersBack = false, // try to hide otoroshi as much as possible
      enforceSecureCommunication = false, // try to hide otoroshi as much as possible
      forceHttps = if (env.exposedRootSchemeIsHttps) true else false,
    )
  def updateMetrics(id: String,
                    callDuration: Long,
                    callOverhead: Long,
                    dataIn: Long,
                    dataOut: Long,
                    upstreamLatency: Long,
                    config: models.GlobalConfig)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Unit]
  def updateIncrementableMetrics(id: String, calls: Long, dataIn: Long, dataOut: Long, config: models.GlobalConfig)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Unit]
  def count()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def dataInPerSecFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double]
  def dataOutPerSecFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double]
  def globalCalls()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def globalCallsPerSec()(implicit ec: ExecutionContext, env: Env): Future[Double]
  def globalCallsDuration()(implicit ec: ExecutionContext, env: Env): Future[Double]
  def globalCallsOverhead()(implicit ec: ExecutionContext, env: Env): Future[Double]
  def calls(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def callsPerSec(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double]
  def callsDuration(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double]
  def callsOverhead(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double]
  def globalDataIn()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def globalDataOut()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def dataInFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def dataOutFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def find(query: ServiceDescriptorQuery)(implicit ec: ExecutionContext, env: Env): Future[Option[ServiceDescriptor]]
  def findByEnv(env: String)(implicit ec: ExecutionContext, _env: Env): Future[Seq[ServiceDescriptor]]
  def findByGroup(id: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]]

  def getFastLookups(query: ServiceDescriptorQuery)(implicit ec: ExecutionContext, env: Env): Future[Seq[String]]
  def fastLookupExists(query: ServiceDescriptorQuery)(implicit ec: ExecutionContext, env: Env): Future[Boolean]
  def addFastLookups(query: ServiceDescriptorQuery, services: Seq[ServiceDescriptor])(implicit ec: ExecutionContext,
                                                                                      env: Env): Future[Boolean]
  def removeFastLookups(query: ServiceDescriptorQuery, services: Seq[ServiceDescriptor])(implicit ec: ExecutionContext,
                                                                                         env: Env): Future[Boolean]

  def cleanupFastLookups()(implicit ec: ExecutionContext, mat: Materializer, env: Env): Future[Long]
}
