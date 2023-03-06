package otoroshi.next.models

import akka.stream.OverflowStrategy
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models.NgTarget.readFrom
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.http.{CacheConnectionSettings, MtlsConfig}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSProxyServer

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

case class NgCustomTimeouts(
    path: String = "/*",
    connectionTimeout: Long = 10000,
    idleTimeout: Long = 60000,
    callAndStreamTimeout: Long = 1.hour.toMillis,
    callTimeout: Long = 30000,
    globalTimeout: Long = 30000
) {
  def json: JsValue               = NgCustomTimeouts.format.writes(this)
  lazy val legacy: CustomTimeouts = CustomTimeouts(
    path = path,
    connectionTimeout = connectionTimeout,
    idleTimeout = idleTimeout,
    callAndStreamTimeout = callAndStreamTimeout,
    callTimeout = callTimeout,
    globalTimeout = globalTimeout
  )
}

object NgCustomTimeouts {
  val format                                               = new Format[NgCustomTimeouts] {
    override def reads(json: JsValue): JsResult[NgCustomTimeouts] = {
      Try {
        NgCustomTimeouts(
          path = (json \ "path").asOpt[String].filterNot(_.trim.isEmpty).getOrElse("*"),
          connectionTimeout = (json \ "connection_timeout").asOpt[Long].getOrElse(10000),
          idleTimeout = (json \ "idle_timeout").asOpt[Long].getOrElse(60000),
          callAndStreamTimeout = (json \ "call_and_stream_timeout").asOpt[Long].getOrElse(1.hour.toMillis),
          callTimeout = (json \ "call_timeout").asOpt[Long].getOrElse(30000),
          globalTimeout = (json \ "global_timeout").asOpt[Long].getOrElse(30000)
        )
      } match {
        case Failure(e) => JsError(e.getMessage())
        case Success(v) => JsSuccess(v)
      }
    }
    override def writes(o: NgCustomTimeouts): JsValue = {
      Json.obj(
        "path"                    -> o.path,
        "call_timeout"            -> o.callTimeout,
        "call_and_stream_timeout" -> o.callAndStreamTimeout,
        "connection_timeout"      -> o.connectionTimeout,
        "idle_timeout"            -> o.idleTimeout,
        "global_timeout"          -> o.globalTimeout
      )
    }
  }
  def fromLegacy(config: CustomTimeouts): NgCustomTimeouts = NgCustomTimeouts(
    path = config.path,
    connectionTimeout = config.connectionTimeout,
    idleTimeout = config.idleTimeout,
    callAndStreamTimeout = config.callAndStreamTimeout,
    callTimeout = config.callTimeout,
    globalTimeout = config.globalTimeout
  )
}

case class NgCacheConnectionSettings(
    enabled: Boolean = false,
    queueSize: Int = 2048,
    strategy: OverflowStrategy = OverflowStrategy.dropNew
) {
  lazy val legacy: CacheConnectionSettings = CacheConnectionSettings(
    enabled = enabled,
    queueSize = queueSize,
    strategy = strategy
  )
  def json: JsValue = {
    Json.obj(
      "enabled"    -> enabled,
      "queue_size" -> queueSize
    )
  }
}

object NgCacheConnectionSettings {
  def fromLegacy(config: CacheConnectionSettings): NgCacheConnectionSettings = NgCacheConnectionSettings(
    enabled = config.enabled,
    queueSize = config.queueSize,
    strategy = config.strategy
  )
}

case class NgClientConfig(
    retries: Int = 1,
    maxErrors: Int = 20,
    retryInitialDelay: Long = 50,
    backoffFactor: Long = 2,
    connectionTimeout: Long = 10000,
    idleTimeout: Long = 60000,
    callAndStreamTimeout: Long =
      120000, // http client timeout per call with streaming from otoroshi to client included (actually end the call)
    callTimeout: Long =
      30000,  // circuit breaker timeout per call (soft, streaming from otoroshi to client not included)
    globalTimeout: Long =
      30000,  // circuit breaker timeout around all calls (soft, streaming from otoroshi to client not included)
    sampleInterval: Long = 2000,
    proxy: Option[WSProxyServer] = None,
    customTimeouts: Seq[NgCustomTimeouts] = Seq.empty[NgCustomTimeouts],
    cacheConnectionSettings: NgCacheConnectionSettings = NgCacheConnectionSettings()
) {
  def json: JsValue             = NgClientConfig.format.writes(this)
  lazy val legacy: ClientConfig = ClientConfig(
    retries = retries,
    maxErrors = maxErrors,
    retryInitialDelay = retryInitialDelay,
    backoffFactor = backoffFactor,
    connectionTimeout = connectionTimeout,
    idleTimeout = idleTimeout,
    callAndStreamTimeout = callAndStreamTimeout,
    callTimeout = callTimeout,
    globalTimeout = globalTimeout,
    sampleInterval = sampleInterval,
    proxy = proxy,
    customTimeouts = customTimeouts.map(_.legacy),
    cacheConnectionSettings = cacheConnectionSettings.legacy
  )
}

object NgClientConfig {
  val default                                          = NgClientConfig()
  val format                                           = new Format[NgClientConfig] {
    override def reads(json: JsValue): JsResult[NgClientConfig] = {
      Try {
        NgClientConfig(
          retries = (json \ "retries").asOpt[Int].getOrElse(1),
          maxErrors = (json \ "max_errors").asOpt[Int].getOrElse(20),
          retryInitialDelay = (json \ "retry_initial_delay").asOpt[Long].getOrElse(50),
          backoffFactor = (json \ "backoff_factor").asOpt[Long].getOrElse(2),
          connectionTimeout = (json \ "connection_timeout").asOpt[Long].getOrElse(10000),
          idleTimeout = (json \ "idle_timeout").asOpt[Long].getOrElse(60000),
          callAndStreamTimeout = (json \ "call_and_stream_timeout").asOpt[Long].getOrElse(120000),
          callTimeout = (json \ "call_timeout").asOpt[Long].getOrElse(30000),
          globalTimeout = (json \ "global_timeout").asOpt[Long].getOrElse(30000),
          sampleInterval = (json \ "sample_interval").asOpt[Long].getOrElse(2000),
          proxy = (json \ "proxy").asOpt[JsValue].flatMap(p => WSProxyServerJson.proxyFromJson(p)),
          cacheConnectionSettings = NgCacheConnectionSettings(
            enabled = (json \ "cache_connection_settings" \ "enabled").asOpt[Boolean].getOrElse(false),
            queueSize = (json \ "cache_connection_settings" \ "queue_size").asOpt[Int].getOrElse(2048),
            strategy = OverflowStrategy.dropNew
          ),
          customTimeouts = (json \ "custom_timeouts")
            .asOpt[JsArray]
            .map(_.value.map(e => NgCustomTimeouts.format.reads(e).get))
            .getOrElse(Seq.empty[NgCustomTimeouts])
        )
      } match {
        case Failure(e) => JsError(e.getMessage())
        case Success(v) => JsSuccess(v)
      }
    }

    override def writes(o: NgClientConfig): JsValue =
      Json.obj(
        "retries"                   -> o.retries,
        "max_errors"                -> o.maxErrors,
        "retry_initial_delay"       -> o.retryInitialDelay,
        "backoff_factor"            -> o.backoffFactor,
        "call_timeout"              -> o.callTimeout,
        "call_and_stream_timeout"   -> o.callAndStreamTimeout,
        "connection_timeout"        -> o.connectionTimeout,
        "idle_timeout"              -> o.idleTimeout,
        "global_timeout"            -> o.globalTimeout,
        "sample_interval"           -> o.sampleInterval,
        "proxy"                     -> o.proxy.map(p => WSProxyServerJson.proxyToJson(p)).getOrElse(Json.obj()).as[JsValue],
        "custom_timeouts"           -> JsArray(o.customTimeouts.map(_.json)),
        "cache_connection_settings" -> o.cacheConnectionSettings.json
      )
  }
  def fromLegacy(config: ClientConfig): NgClientConfig = NgClientConfig(
    retries = config.retries,
    maxErrors = config.maxErrors,
    retryInitialDelay = config.retryInitialDelay,
    backoffFactor = config.backoffFactor,
    connectionTimeout = config.connectionTimeout,
    idleTimeout = config.idleTimeout,
    callAndStreamTimeout = config.callAndStreamTimeout,
    callTimeout = config.callTimeout,
    globalTimeout = config.globalTimeout,
    sampleInterval = config.sampleInterval,
    proxy = config.proxy,
    customTimeouts = config.customTimeouts.map(NgCustomTimeouts.fromLegacy),
    cacheConnectionSettings = NgCacheConnectionSettings.fromLegacy(config.cacheConnectionSettings)
  )
}

case class NgTlsConfig(
    certs: Seq[String] = Seq.empty,
    trustedCerts: Seq[String] = Seq.empty,
    enabled: Boolean = false,
    loose: Boolean = false,
    trustAll: Boolean = false
) {
  def json: JsValue           = NgTlsConfig.format.writes(this)
  lazy val legacy: MtlsConfig = MtlsConfig(
    certs = certs,
    trustedCerts = trustedCerts,
    mtls = enabled,
    loose = loose,
    trustAll = trustAll
  )
}

object NgTlsConfig {
  val default = NgTlsConfig()
  val format                                      = new Format[NgTlsConfig] {
    override def reads(json: JsValue): JsResult[NgTlsConfig] = {
      Try {
        NgTlsConfig(
          certs = (json \ "certs")
            .asOpt[Seq[String]]
            .orElse((json \ "certId").asOpt[String].map(v => Seq(v)))
            .orElse((json \ "cert_id").asOpt[String].map(v => Seq(v)))
            .map(_.filter(_.trim.nonEmpty))
            .getOrElse(Seq.empty),
          trustedCerts = (json \ "trusted_certs")
            .asOpt[Seq[String]]
            .map(_.filter(_.trim.nonEmpty))
            .getOrElse(Seq.empty),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          loose = (json \ "loose").asOpt[Boolean].getOrElse(false),
          trustAll = (json \ "trust_all").asOpt[Boolean].getOrElse(false)
        )
      } match {
        case Failure(e) => JsError(e.getMessage())
        case Success(v) => JsSuccess(v)
      }
    }

    override def writes(o: NgTlsConfig): JsValue = {
      Json.obj(
        "certs"         -> JsArray(o.certs.map(JsString.apply)),
        "trusted_certs" -> JsArray(o.trustedCerts.map(JsString.apply)),
        "enabled"       -> o.enabled,
        "loose"         -> o.loose,
        "trust_all"     -> o.trustAll
      )
    }
  }
  def fromLegacy(config: MtlsConfig): NgTlsConfig = NgTlsConfig(
    certs = config.certs,
    trustedCerts = config.trustedCerts,
    enabled = config.mtls,
    loose = config.loose,
    trustAll = config.trustAll
  )
}

case class NgBackend(
    targets: Seq[NgTarget],
    root: String,
    rewrite: Boolean,
    loadBalancing: LoadBalancing,
    healthCheck: Option[HealthCheck] = None,
    client: NgClientConfig
) {
  // I know it's not ideal but we'll go with it for now !
  lazy val allTargets: Seq[NgTarget] = targets.distinct
  def json: JsValue                  = Json
    .obj(
      "targets"        -> JsArray(targets.map(_.json)),
      "root"           -> root,
      "rewrite"        -> rewrite,
      "load_balancing" -> loadBalancing.toJson,
      "client"         -> client.json,
      "health_check"   -> healthCheck.map(_.toJson).getOrElse(JsNull).as[JsValue]
    )
    .applyOnWithOpt(healthCheck) { case (obj, hc) =>
      obj ++ Json.obj("health_check" -> hc.toJson)
    }

  lazy val minimalBackend: NgMinimalBackend = {
    NgMinimalBackend(
      targets = targets,
      root = root,
      rewrite = rewrite,
      loadBalancing = loadBalancing
    )
  }
}

object NgBackend {
  def empty: NgBackend                            = NgBackend(Seq.empty, "/", false, RoundRobin, None, NgClientConfig())
  def readFrom(lookup: JsLookupResult): NgBackend = readFromJson(lookup.as[JsValue])
  def readFromJson(lookup: JsValue): NgBackend = {
    lookup.asOpt[JsObject] match {
      case None      => empty
      case Some(obj) =>
        NgBackend(
          targets = obj.select("targets").asOpt[Seq[JsValue]].map(_.map(NgTarget.readFrom)).getOrElse(Seq.empty),
          root = obj.select("root").asOpt[String].getOrElse("/"),
          rewrite = obj.select("rewrite").asOpt[Boolean].getOrElse(false),
          loadBalancing = LoadBalancing.format
            .reads(obj.select("load_balancing").asOpt[JsObject].getOrElse(Json.obj()))
            .getOrElse(RoundRobin),
          healthCheck = obj.select("health_check").asOpt(HealthCheck.format),
          client = obj.select("client").asOpt(NgClientConfig.format).getOrElse(NgClientConfig())
        )
    }
  }
  val fmt                                         = new Format[NgBackend] {
    override def writes(o: NgBackend): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgBackend] = JsSuccess(readFromJson(json))
  }
}

case class NgMinimalBackend(
    targets: Seq[NgTarget],
    root: String,
    rewrite: Boolean,
    loadBalancing: LoadBalancing
) {
  // I know it's not ideal but we'll go with it for now !
  lazy val allTargets: Seq[NgTarget] = targets.distinct
  def json: JsValue                  = Json.obj(
    "targets"        -> JsArray(targets.map(_.json)),
    "root"           -> root,
    "rewrite"        -> rewrite,
    "load_balancing" -> loadBalancing.toJson
  )
  def toBackend(client: NgClientConfig, healthCheck: Option[HealthCheck]) = {
    NgBackend(
      targets = targets,
      root = root,
      rewrite = rewrite,
      loadBalancing = loadBalancing,
      client = client,
      healthCheck = healthCheck
    )
  }
}

object NgMinimalBackend {
  def empty: NgMinimalBackend                            = NgMinimalBackend(Seq.empty, "/", false, RoundRobin)
  def readFrom(lookup: JsLookupResult): NgMinimalBackend = readFromJson(lookup.as[JsValue])
  def readFromJson(lookup: JsValue): NgMinimalBackend = {
    lookup.asOpt[JsObject] match {
      case None      => empty
      case Some(obj) =>
        NgMinimalBackend(
          targets = obj.select("targets").asOpt[Seq[JsValue]].map(_.map(NgTarget.readFrom)).getOrElse(Seq.empty),
          root = obj.select("root").asOpt[String].getOrElse("/"),
          rewrite = obj.select("rewrite").asOpt[Boolean].getOrElse(false),
          loadBalancing = LoadBalancing.format
            .reads(obj.select("load_balancing").asOpt[JsObject].getOrElse(Json.obj()))
            .getOrElse(RoundRobin)
        )
    }
  }
}

case class NgSelectedBackendTarget(target: NgTarget, attempts: Int, alreadyFailed: AtomicBoolean, cbStart: Long)

case class NgTarget(
    id: String,
    hostname: String,
    port: Int,
    tls: Boolean,
    weight: Int = 1,
    protocol: HttpProtocol = HttpProtocols.HTTP_1_1,
    predicate: TargetPredicate = AlwaysMatch,
    ipAddress: Option[String] = None,
    tlsConfig: NgTlsConfig = NgTlsConfig()
) {
  lazy val baseUrl: String                  = s"${if (tls) "https" else "http"}://${ipAddress.getOrElse(hostname)}${defaultPortString}"
  lazy val defaultPortString                = port match {
    case 443 => ""
    case 80  => ""
    case _   => s":${port}"
  }
  lazy val legacy: otoroshi.models.Target   = toTarget
  lazy val toTarget: otoroshi.models.Target = otoroshi.models.Target(
    host = s"${hostname}${defaultPortString}",
    scheme = if (tls) "https" else "http",
    weight = weight,
    protocol = protocol,
    predicate = predicate,
    ipAddress = ipAddress,
    mtlsConfig = tlsConfig.legacy,
    tags = Seq(id),
    metadata = Map.empty
  )
  def json: JsValue                         = Json.obj(
    "id"         -> id,
    "hostname"   -> hostname,
    "port"       -> port,
    "tls"        -> tls,
    "weight"     -> weight,
    "predicate"  -> predicate.toJson,
    "protocol"   -> protocol.value,
    "ip_address" -> ipAddress.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "tls_config" -> tlsConfig.json
  )
}

object NgTarget {
  def fromLegacy(target: Target): NgTarget = fromTarget(target)
  def fromTarget(target: Target): NgTarget = {
    NgTarget(
      id = target.tags.headOption.getOrElse(target.host),
      hostname = target.theHost,
      port = target.thePort,
      tls = target.scheme.toLowerCase == "https",
      weight = target.weight,
      protocol = target.protocol,
      predicate = target.predicate,
      ipAddress = target.ipAddress,
      tlsConfig = NgTlsConfig.fromLegacy(target.mtlsConfig)
    )
  }
  def readFrom(obj: JsValue): NgTarget = {
    val hostname   = obj.select("hostname").as[String]
    val port       = obj.select("port").as[Int]
    val ipAddress  = obj.select("ip_address").asOpt[String].filterNot(_.trim.isEmpty)
    val fallbackIp = ipAddress.map(ip => s"@$ip").getOrElse("")
    val tls        = obj.select("tls").asOpt[Boolean].getOrElse(false)
    val scheme     = if (tls) "https://" else "http://"
    val fallbackId = s"${scheme}${hostname}${fallbackIp}:${port}"
    NgTarget(
      id = obj.select("id").asOpt[String].getOrElse(fallbackId),
      hostname = hostname,
      port = port,
      tls = tls,
      weight = obj.select("weight").asOpt[Int].getOrElse(1),
      tlsConfig = obj.select("tls_config").asOpt(NgTlsConfig.format).getOrElse(NgTlsConfig()),
      protocol = (obj \ "protocol")
        .asOpt[String]
        .filterNot(_.trim.isEmpty)
        .map(s => HttpProtocols.parse(s))
        .getOrElse(HttpProtocols.HTTP_1_1),
      predicate = (obj \ "predicate").asOpt(TargetPredicate.format).getOrElse(AlwaysMatch),
      ipAddress = ipAddress
    )
  }
  val fmt                                  = new Format[NgTarget] {
    override def writes(o: NgTarget): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgTarget] = JsSuccess(readFrom(json))
  }
}

object StoredNgBackend {
  def fromJsons(value: JsValue): StoredNgBackend =
    try {
      format.reads(value).get
    } catch {
      case e: Throwable => throw e
    }
  val format                                     = new Format[StoredNgBackend] {
    override def reads(json: JsValue): JsResult[StoredNgBackend] = Try {
      StoredNgBackend(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").as[String],
        name = json.select("name").as[String],
        description = json.select("description").asOpt[String].getOrElse(""),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        backend = NgBackend.readFromJson(json.select("backend").as[JsValue])
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route)     => JsSuccess(route)
    }
    override def writes(o: StoredNgBackend): JsValue             = o.json
  }
}

case class StoredNgBackend(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    backend: NgBackend
) extends EntityLocationSupport {
  def save()(implicit env: Env, ec: ExecutionContext): Future[Boolean] = env.datastores.backendsDataStore.set(this)
  override def internalId: String                                      = id
  override def theName: String                                         = name
  override def theDescription: String                                  = description
  override def theTags: Seq[String]                                    = tags
  override def theMetadata: Map[String, String]                        = metadata
  override def json: JsValue                                           = location.jsonWithKey ++ Json.obj(
    "id"          -> id,
    "name"        -> name,
    "description" -> description,
    "tags"        -> tags,
    "metadata"    -> metadata,
    "backend"     -> backend.json
  )
}

trait StoredNgBackendDataStore extends BasicStore[StoredNgBackend] {
  def template(env: Env): StoredNgBackend = {
    val default = StoredNgBackend(
      location = EntityLocation.default,
      id = IdGenerator.namedId("backend", env),
      name = "New backend",
      description = "New backend",
      metadata = Map.empty,
      tags = Seq.empty,
      backend = NgBackend.empty
    )
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .backend
      .map { template =>
        StoredNgBackend.format.reads(default.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        default
      }
  }
}

class KvStoredNgBackendDataStore(redisCli: RedisLike, _env: Env)
    extends StoredNgBackendDataStore
    with RedisLikeStore[StoredNgBackend] {
  override def redisLike(implicit env: Env): RedisLike   = redisCli
  override def fmt: Format[StoredNgBackend]              = StoredNgBackend.format
  override def key(id: String): String                   = s"${_env.storageRoot}:backends:${id}"
  override def extractId(value: StoredNgBackend): String = value.id
}
