package otoroshi.events

import akka.util.ByteString
import otoroshi.env.Env
import models._
import org.joda.time.DateTime
import otoroshi.script.{Job, JobContext}
import play.api.libs.json._
import otoroshi.ssl.Cert

import scala.concurrent.{ExecutionContext, Future}

trait AuditEvent extends AnalyticEvent {
  override def `@type`: String = "AuditEvent"
}

// TODO: include UA
case class BackOfficeEvent(`@id`: String,
                           `@env`: String,
                           user: BackOfficeUser,
                           action: String,
                           message: String,
                           from: String,
                           ua: String,
                           metadata: JsObject = Json.obj(),
                           `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)

  override def toJson(implicit env: Env): JsValue = Json.obj(
    "@id"          -> `@id`,
    "@timestamp"   -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"        -> `@type`,
    "@product"     -> env.eventsName,
    "@serviceId"   -> `@serviceId`,
    "@service"     -> `@service`,
    "@env"         -> `@env`,
    "audit"        -> "BackOfficeEvent",
    "userName"     -> user.name,
    "userEmail"    -> user.email,
    "user"         -> user.profile,
    "userRandomId" -> user.randomId,
    "action"       -> action,
    "from"         -> from,
    "message"      -> message,
    "metadata"     -> metadata
  )
}

case class AdminApiEvent(`@id`: String,
                         `@env`: String,
                         apiKey: Option[ApiKey],
                         user: Option[JsValue],
                         action: String,
                         message: String,
                         from: String,
                         ua: String,
                         metadata: JsValue = Json.obj(),
                         `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "AdminApiEvent",
    "user"       -> user.getOrElse(JsNull).as[JsValue],
    "apiKey"     -> apiKey.map(ak => ak.toJson).getOrElse(JsNull).as[JsValue],
    "action"     -> action,
    "from"       -> from,
    "message"    -> message,
    "metadata"   -> metadata
  )
}

case class SnowMonkeyOutageRegisteredEvent(`@id`: String,
                                           `@env`: String,
                                           action: String,
                                           message: String,
                                           config: SnowMonkeyConfig,
                                           desc: ServiceDescriptor,
                                           dryRun: Boolean,
                                           `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "SnowMonkeyOutageRegisteredEvent",
    "user"       -> "--",
    "apiKey"     -> "--",
    "action"     -> action,
    "from"       -> "--",
    "message"    -> message,
    "config"     -> config.asJson,
    "service"    -> desc.toJson,
    "dryRun"     -> dryRun
  )
}

case class CircuitBreakerOpenedEvent(`@id`: String,
                                     `@env`: String,
                                     target: Target,
                                     service: ServiceDescriptor,
                                     `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = service.id

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "CircuitBreakerOpenedEvent",
    "target"     -> target.toJson,
    "service"    -> service.toJson
  )
}

case class CircuitBreakerClosedEvent(`@id`: String,
                                     `@env`: String,
                                     target: Target,
                                     service: ServiceDescriptor,
                                     `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = service.id

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "CircuitBreakerClosedEvent",
    "target"     -> target.toJson,
    "service"    -> service.toJson
  )
}

case class MaxConcurrentRequestReachedEvent(`@id`: String,
                                            `@env`: String,
                                            limit: Long,
                                            current: Long,
                                            `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "MaxConcurrentRequestReachedEvent",
    "limit"      -> limit,
    "current"    -> current
  )
}

case class JobStartedEvent(`@id`: String, `@env`: String, job: Job, ctx: JobContext, `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "JobStartedEvent",
    "job"        -> job.auditJson(ctx)(_env)
  )
}

case class JobStoppedEvent(`@id`: String, `@env`: String, job: Job, ctx: JobContext, `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "JobStoppedEvent",
    "job"        -> job.auditJson(ctx)(_env)
  )
}

case class JobRunEvent(`@id`: String, `@env`: String, job: Job, ctx: JobContext, `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "JobRunEvent",
    "job"        -> job.auditJson(ctx)(_env)
  )
}

case class JobErrorEvent(`@id`: String,
                         `@env`: String,
                         job: Job,
                         ctx: JobContext,
                         err: Throwable,
                         `@timestamp`: DateTime = DateTime.now())
    extends AuditEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "audit"      -> "JobErrorEvent",
    "job"        -> job.auditJson(ctx)(_env),
    "err"        -> err.getMessage,
  )
}

object Audit {
  def send[A <: AuditEvent](audit: A)(implicit env: Env): Unit = {
    implicit val ec = env.analyticsExecutionContext
    audit.toAnalytics()
    audit.toEnrichedJson.map(e => env.datastores.auditDataStore.push(e))
  }
}

trait AuditDataStore {
  def count()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def findAllRaw(from: Long = 0, to: Long = 1000)(implicit ec: ExecutionContext, env: Env): Future[Seq[ByteString]]
  def push(event: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Long]
}
