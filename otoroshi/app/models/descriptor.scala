package models

import akka.http.scaladsl.util.FastFuture
import env.Env
import play.api.Logger
import play.api.libs.json._
import security.IdGenerator
import storage.BasicStore

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class ServiceDescriptorQuery(subdomain: String,
                                  env: String = "prod",
                                  domain: String,
                                  root: String = "/",
                                  matchingHeaders: Map[String, String] = Map.empty[String, String]) {

  def asKey: String = s"opun:desclookup:$env:$domain:$subdomain:$root"

  def toHost: String = subdomain match {
    case s if s.isEmpty                  => s"$env.$domain"
    case s if s.isEmpty && env == "prod" => s"$domain"
    case s if env == "prod"              => s"$subdomain.$domain"
    case s                               => s"$subdomain.$env.$domain"
  }

  def toDevHost: String = subdomain match {
    case s if s.isEmpty                  => s"dev.$domain"
    case s if s.isEmpty && env == "prod" => s"$domain"
    case s if env == "prod"              => s"$subdomain.$domain"
    case s                               => s"$subdomain.dev.$domain"
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

  def addServices(services: Seq[ServiceDescriptor])(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.serviceDescriptorDataStore.addFastLookups(this, services)

  def remServices(services: Seq[ServiceDescriptor])(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.serviceDescriptorDataStore.removeFastLookups(this, services)
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
    privateApp: Boolean = false,
    forceHttps: Boolean = true,
    maintenanceMode: Boolean = false,
    buildMode: Boolean = false,
    enforceSecureCommunication: Boolean = true,
    secComExcludedPatterns: Seq[String] = Seq.empty[String],
    publicPatterns: Seq[String] = Seq.empty[String],
    privatePatterns: Seq[String] = Seq.empty[String],
    additionalHeaders: Map[String, String] = Map.empty[String, String],
    matchingHeaders: Map[String, String] = Map.empty[String, String],
    ipFiltering: IpFiltering = IpFiltering(),
    api: ApiDescriptor = ApiDescriptor(false, None),
    healthCheck: HealthCheck = HealthCheck(false, "/"),
    clientConfig: ClientConfig = ClientConfig(),
    canary: Canary = Canary(),
    metadata: Map[String, String] = Map.empty[String, String]
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
  def isUriExcludedFromSecuredCommunication(uri: String): Boolean =
    !secComExcludedPatterns.exists(p => utils.RegexPool.regex(p).matches(uri))
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
}

object ServiceDescriptor {

  lazy val logger = Logger("otoroshi-service-descriptor")

  val _fmt: Format[ServiceDescriptor] = new Format[ServiceDescriptor] {

    override def reads(json: JsValue): JsResult[ServiceDescriptor] =
      Try {
        ServiceDescriptor(
          id = (json \ "id").as[String],
          groupId = (json \ "groupId").as[String],
          name = (json \ "name").as[String],
          env = (json \ "env").as[String],
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
          privateApp = (json \ "privateApp").asOpt[Boolean].getOrElse(false),
          forceHttps = (json \ "forceHttps").asOpt[Boolean].getOrElse(true),
          maintenanceMode = (json \ "maintenanceMode").asOpt[Boolean].getOrElse(false),
          buildMode = (json \ "buildMode").asOpt[Boolean].getOrElse(false),
          enforceSecureCommunication = (json \ "enforceSecureCommunication").asOpt[Boolean].getOrElse(true),
          secComExcludedPatterns = (json \ "secComExcludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          publicPatterns = (json \ "publicPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          privatePatterns = (json \ "privatePatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          additionalHeaders =
            (json \ "additionalHeaders").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          matchingHeaders = (json \ "matchingHeaders").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          ipFiltering = (json \ "ipFiltering").asOpt(IpFiltering.format).getOrElse(IpFiltering()),
          api = (json \ "api").asOpt(ApiDescriptor.format).getOrElse(ApiDescriptor(false, None)),
          healthCheck = (json \ "healthCheck").asOpt(HealthCheck.format).getOrElse(HealthCheck(false, "/")),
          clientConfig = (json \ "clientConfig").asOpt(ClientConfig.format).getOrElse(ClientConfig()),
          canary = (json \ "canary").asOpt(Canary.format).getOrElse(Canary()),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty[String, String])
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
      "privateApp"                 -> sd.privateApp,
      "forceHttps"                 -> sd.forceHttps,
      "maintenanceMode"            -> sd.maintenanceMode,
      "buildMode"                  -> sd.buildMode,
      "enforceSecureCommunication" -> sd.enforceSecureCommunication,
      "secComExcludedPatterns"     -> JsArray(sd.secComExcludedPatterns.map(JsString.apply)),
      "publicPatterns"             -> JsArray(sd.publicPatterns.map(JsString.apply)),
      "privatePatterns"            -> JsArray(sd.privatePatterns.map(JsString.apply)),
      "additionalHeaders"          -> JsObject(sd.additionalHeaders.mapValues(JsString.apply)),
      "matchingHeaders"            -> JsObject(sd.matchingHeaders.mapValues(JsString.apply)),
      "ipFiltering"                -> sd.ipFiltering.toJson,
      "api"                        -> sd.api.toJson,
      "healthCheck"                -> sd.healthCheck.toJson,
      "clientConfig"               -> sd.clientConfig.toJson,
      "canary"                     -> sd.canary.toJson,
      "metadata"                   -> JsObject(sd.metadata.mapValues(JsString.apply))
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
      privateApp = false
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
}
