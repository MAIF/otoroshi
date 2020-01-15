package events

import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.{Actor, Cancellable, OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated}
import akka.actor.SupervisorStrategy._
import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{OverflowStrategy, QueueOfferResult, ThrottleMode}
import akka.util.ByteString
import env.Env
import models._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsArray, JsValue, Json, Writes}
import play.api.libs.ws.WSAuthScheme
import play.api.mvc.RequestHeader
import ssl.Cert
import utils.EmailLocation

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration.FiniteDuration
import utils.RequestImplicits._

trait AlertEvent extends AnalyticEvent {
  override def `@type`: String = "AlertEvent"
}

case class MaxConcurrentRequestReachedAlert(`@id`: String,
                                            `@env`: String,
                                            limit: Long,
                                            current: Long,
                                            `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "MaxConcurrentRequestReachedAlert",
    "limit"      -> limit,
    "current"    -> current
  )
}

case class HighOverheadAlert(`@id`: String,
                             limitOverhead: Double,
                             currentOverhead: Long,
                             serviceDescriptor: ServiceDescriptor,
                             target: Location,
                             `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

  override def `@service`: String   = serviceDescriptor.name
  override def `@serviceId`: String = serviceDescriptor.id

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> serviceDescriptor.id,
    "@service"   -> serviceDescriptor.name,
    "alert"      -> "HighOverheadAlert",
    "limit"      -> limitOverhead,
    "overhead"   -> currentOverhead,
    "target"     -> Location.format.writes(target),
    "service"    -> serviceDescriptor.toJson
  )
}

case class CircuitBreakerOpenedAlert(`@id`: String,
                                     `@env`: String,
                                     target: Target,
                                     service: ServiceDescriptor,
                                     `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "CircuitBreakerOpenedAlert",
    "target"     -> target.toJson,
    "service"    -> service.toJson
  )
}

case class CircuitBreakerClosedAlert(`@id`: String,
                                     `@env`: String,
                                     target: Target,
                                     service: ServiceDescriptor,
                                     `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "CircuitBreakerClosedAlert",
    "target"     -> target.toJson,
    "service"    -> service.toJson
  )
}

case class SessionDiscardedAlert(`@id`: String,
                                 `@env`: String,
                                 user: BackOfficeUser,
                                 event: AuditEvent,
                                 from: String,
                                 ua: String,
                                 `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "SessionDiscardedAlert",
    "user"       -> user.profile
  )
}

case class SessionsDiscardedAlert(`@id`: String,
                                  `@env`: String,
                                  user: BackOfficeUser,
                                  event: AuditEvent,
                                  from: String,
                                  ua: String,
                                  `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "SessionsDiscardedAlert",
    "user"       -> user.profile
  )
}

case class PanicModeAlert(`@id`: String,
                          `@env`: String,
                          user: BackOfficeUser,
                          event: BackOfficeEvent,
                          from: String,
                          ua: String,
                          `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "PanicModeAlert",
    "user"       -> user.profile
  )
}

case class OtoroshiExportAlert(`@id`: String,
                               `@env`: String,
                               user: JsValue,
                               event: AdminApiEvent,
                               export: JsValue,
                               from: String,
                               ua: String,
                               `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "OtoroshiExportAlert",
    "user"       -> user,
    "event"      -> event.toJson,
    "export"     -> export
  )
}

case class SnowMonkeyStartedAlert(`@id`: String,
                                  `@env`: String,
                                  user: JsValue,
                                  audit: AuditEvent,
                                  from: String,
                                  ua: String,
                                  `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "SnowMonkeyStartedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class SnowMonkeyStoppedAlert(`@id`: String,
                                  `@env`: String,
                                  user: JsValue,
                                  audit: AuditEvent,
                                  from: String,
                                  ua: String,
                                  `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "SnowMonkeyStoppedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class SnowMonkeyConfigUpdatedAlert(`@id`: String,
                                        `@env`: String,
                                        user: JsValue,
                                        audit: AuditEvent,
                                        from: String,
                                        ua: String,
                                        `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "SnowMonkeyConfigUpdatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class SnowMonkeyResetAlert(`@id`: String,
                                `@env`: String,
                                user: JsValue,
                                audit: AuditEvent,
                                from: String,
                                ua: String,
                                `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "SnowMonkeyResetAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}

case class CertCreatedAlert(`@id`: String,
                            `@env`: String,
                            user: JsValue,
                            audit: AuditEvent,
                            from: String,
                            ua: String,
                            `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "CertCreatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}

case class CertUpdatedAlert(`@id`: String,
                            `@env`: String,
                            user: JsValue,
                            audit: AuditEvent,
                            from: String,
                            ua: String,
                            `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "CertUpdatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}

case class CertDeleteAlert(`@id`: String,
                           `@env`: String,
                           user: JsValue,
                           audit: AuditEvent,
                           from: String,
                           ua: String,
                           `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "CertDeleteAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}

case class CertRenewalAlert(`@id`: String, `@env`: String, cert: Cert, `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"         -> `@id`,
    "@timestamp"  -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"       -> `@type`,
    "@product"    -> _env.eventsName,
    "@serviceId"  -> `@serviceId`,
    "@service"    -> `@service`,
    "@env"        -> `@env`,
    "audit"       -> "CertRenewalAlert",
    "certificate" -> cert.toJson,
  )
}

case class SnowMonkeyOutageRegisteredAlert(`@id`: String,
                                           `@env`: String,
                                           audit: AuditEvent,
                                           `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "SnowMonkeyResetAlert",
    "adminApiAlert" -> true,
    "user"          -> "--",
    "audit"         -> audit.toJson
  )
}

case class U2FAdminDeletedAlert(`@id`: String,
                                `@env`: String,
                                user: BackOfficeUser,
                                event: AuditEvent,
                                from: String,
                                ua: String,
                                `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "U2FAdminDeletedAlert",
    "event"      -> event.toJson,
    "user"       -> user.profile
  )
}

case class WebAuthnAdminDeletedAlert(`@id`: String,
                                     `@env`: String,
                                     user: BackOfficeUser,
                                     event: AuditEvent,
                                     from: String,
                                     ua: String,
                                     `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "WebAuthnAdminDeletedAlert",
    "event"      -> event.toJson,
    "user"       -> user.profile
  )
}

case class BlackListedBackOfficeUserAlert(`@id`: String,
                                          `@env`: String,
                                          user: BackOfficeUser,
                                          from: String,
                                          ua: String,
                                          `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "BlackListedBackOfficeUserAlert",
    "user"       -> user.profile
  )
}

case class AdminLoggedInAlert(`@id`: String,
                              `@env`: String,
                              user: BackOfficeUser,
                              from: String,
                              ua: String,
                              `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"          -> `@id`,
    "@timestamp"   -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"        -> `@type`,
    "@product"     -> _env.eventsName,
    "@serviceId"   -> `@serviceId`,
    "@service"     -> `@service`,
    "@env"         -> `@env`,
    "alert"        -> "AdminLoggedInAlert",
    "userName"     -> user.name,
    "userEmail"    -> user.email,
    "user"         -> user.profile,
    "userRandomId" -> user.randomId
  )
}

case class UserLoggedInAlert(`@id`: String,
                             `@env`: String,
                             user: PrivateAppsUser,
                             from: String,
                             ua: String,
                             `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"          -> `@id`,
    "@timestamp"   -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"        -> `@type`,
    "@product"     -> _env.eventsName,
    "@serviceId"   -> `@serviceId`,
    "@service"     -> `@service`,
    "@env"         -> `@env`,
    "alert"        -> "UserLoggedInAlert",
    "userName"     -> user.name,
    "userEmail"    -> user.email,
    "user"         -> user.profile,
    "userRandomId" -> user.randomId
  )
}

case class AdminFirstLogin(`@id`: String,
                           `@env`: String,
                           user: BackOfficeUser,
                           from: String,
                           ua: String,
                           `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"          -> `@id`,
    "@timestamp"   -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"        -> `@type`,
    "@product"     -> _env.eventsName,
    "@serviceId"   -> `@serviceId`,
    "@service"     -> `@service`,
    "@env"         -> `@env`,
    "alert"        -> "AdminFirstLogin",
    "userName"     -> user.name,
    "userEmail"    -> user.email,
    "user"         -> user.profile,
    "userRandomId" -> user.randomId
  )
}

case class AdminLoggedOutAlert(`@id`: String,
                               `@env`: String,
                               user: BackOfficeUser,
                               from: String,
                               ua: String,
                               `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"          -> `@id`,
    "@timestamp"   -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"        -> `@type`,
    "@product"     -> _env.eventsName,
    "@serviceId"   -> `@serviceId`,
    "@service"     -> `@service`,
    "@env"         -> `@env`,
    "alert"        -> "AdminLoggedOutAlert",
    "userName"     -> user.name,
    "userEmail"    -> user.email,
    "user"         -> user.profile,
    "userRandomId" -> user.randomId
  )
}

case class DbResetAlert(`@id`: String,
                        `@env`: String,
                        user: JsValue,
                        from: String,
                        ua: String,
                        `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "DbResetAlert",
    "user"       -> user
  )
}

case class DangerZoneAccessAlert(`@id`: String,
                                 `@env`: String,
                                 user: JsValue,
                                 from: String,
                                 ua: String,
                                 `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

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
    "alert"      -> "DangerZoneAccessAlert",
    "user"       -> user
  )
}

case class GlobalConfigModification(`@id`: String,
                                    `@env`: String,
                                    user: JsValue,
                                    oldConfig: JsValue,
                                    newConfig: JsValue,
                                    audit: AuditEvent,
                                    from: String,
                                    ua: String,
                                    `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "GlobalConfigModification",
    "adminApiAlert" -> true,
    "oldConfig"     -> oldConfig,
    "newConfig"     -> newConfig,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}

case class RevokedApiKeyUsageAlert(`@id`: String,
                                   `@timestamp`: DateTime,
                                   `@env`: String,
                                   req: RequestHeader,
                                   apiKey: ApiKey,
                                   descriptor: ServiceDescriptor,
                                   env: Env)
    extends AlertEvent {

  override def `@service`: String   = descriptor.name
  override def `@serviceId`: String = descriptor.id

  override def fromOrigin: Option[String]    = Some(req.theIpAddress(env))
  override def fromUserAgent: Option[String] = Some(req.theUserAgent)

  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"        -> `@id`,
    "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"      -> `@type`,
    "@product"   -> _env.eventsName,
    "@serviceId" -> `@serviceId`,
    "@service"   -> `@service`,
    "@env"       -> `@env`,
    "alert"      -> "RevokedApiKeyUsageAlert",
    "from"       -> req.remoteAddress,
    "to"         -> req.theHost,
    "uri"        -> req.relativeUri,
    "apiKey"     -> apiKey.toJson
  )
}
case class ServiceGroupCreatedAlert(`@id`: String,
                                    `@env`: String,
                                    user: JsValue,
                                    audit: AuditEvent,
                                    from: String,
                                    ua: String,
                                    `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceGroupCreatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ServiceGroupUpdatedAlert(`@id`: String,
                                    `@env`: String,
                                    user: JsValue,
                                    audit: AuditEvent,
                                    from: String,
                                    ua: String,
                                    `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceGroupUpdatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ServiceGroupDeletedAlert(`@id`: String,
                                    `@env`: String,
                                    user: JsValue,
                                    audit: AuditEvent,
                                    from: String,
                                    ua: String,
                                    `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceGroupDeletedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ServiceCreatedAlert(`@id`: String,
                               `@env`: String,
                               user: JsValue,
                               audit: AuditEvent,
                               from: String,
                               ua: String,
                               `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceCreatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ServiceUpdatedAlert(`@id`: String,
                               `@env`: String,
                               user: JsValue,
                               audit: AuditEvent,
                               from: String,
                               ua: String,
                               `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceUpdatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ServiceDeletedAlert(`@id`: String,
                               `@env`: String,
                               user: JsValue,
                               audit: AuditEvent,
                               from: String,
                               ua: String,
                               `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ServiceDeletedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ApiKeyCreatedAlert(`@id`: String,
                              `@env`: String,
                              user: JsValue,
                              audit: AuditEvent,
                              from: String,
                              ua: String,
                              `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ApiKeyCreatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ApiKeyUpdatedAlert(`@id`: String,
                              `@env`: String,
                              user: JsValue,
                              audit: AuditEvent,
                              from: String,
                              ua: String,
                              `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ApiKeyUpdatedAlert",
    "adminApiAlert" -> true,
    "user"          -> user,
    "audit"         -> audit.toJson
  )
}
case class ApiKeyDeletedAlert(`@id`: String,
                              `@env`: String,
                              user: JsValue,
                              audit: AuditEvent,
                              from: String,
                              ua: String,
                              `@timestamp`: DateTime = DateTime.now())
    extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
    "@id"           -> `@id`,
    "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
    "@type"         -> `@type`,
    "@product"      -> _env.eventsName,
    "@serviceId"    -> `@serviceId`,
    "@service"      -> `@service`,
    "@env"          -> `@env`,
    "alert"         -> "ApiKeyDeletedAlert",
    "adminApiAlert" -> true,
    "identity"      -> user,
    "audit"         -> audit.toJson
  )
}

object AlertsActor {
  def props(implicit env: Env) = Props(new AlertsActor())
}

class AlertsActor(implicit env: Env) extends Actor {

  import org.joda.time.DateTime
  import events.KafkaWrapper
  import utils.http.Implicits._

  implicit val ec  = env.analyticsExecutionContext
  implicit val mat = env.analyticsMaterializer

  lazy val logger = Logger("otoroshi-alert-actor")

  lazy val kafkaWrapper = new KafkaWrapper(env.analyticsActorSystem, env, _.alertsTopic)

  lazy val emailStream = Source
    .queue[AlertEvent](5000, OverflowStrategy.dropHead)
    .mapAsync(5)(evt => evt.toEnrichedJson)
    .groupedWithin(25, FiniteDuration(60, TimeUnit.SECONDS))
    .mapAsync(1) { evts =>
      val titles = evts
        .map { jsonEvt =>
          val date = new DateTime((jsonEvt \ "@timestamp").as[Long])
          val id   = (jsonEvt \ "@id").as[String]
          s"""<li><a href="#$id">""" + (jsonEvt \ "alert")
            .asOpt[String]
            .getOrElse("Unkown alert") + s" - ${date.toString()}</a></li>"
        }
        .mkString("<ul>", "\n", "</ul>")

      val email = evts
        .map { jsonEvt =>
          val alert   = (jsonEvt \ "alert").asOpt[String].getOrElse("Unkown alert")
          val message = (jsonEvt \ "audit" \ "message").asOpt[String].getOrElse("No description message")
          val date    = new DateTime((jsonEvt \ "@timestamp").as[Long])
          val id      = (jsonEvt \ "@id").as[String]
          s"""<h3 id="$id">$alert - ${date.toString()}</h3><pre>${Json.prettyPrint(jsonEvt)}</pre><br/>"""
        }
        .mkString("\n")

      val emailBody =
        s"""<p>${evts.size} new alerts occured on Otoroshi, you can visualize it on the <a href="${env.rootScheme}${env.backOfficeHost}/">Otoroshi Dashboard</a></p>
      |$titles
      |$email
    """.stripMargin
      env.datastores.globalConfigDataStore
        .singleton()
        .flatMap(
          config =>
            config.mailerSettings
              .map(
                _.asMailer(config, env).send(
                  from = EmailLocation("Otoroshi Alerts", s"otoroshi-alerts@${env.domain}"),
                  to = config.alertsEmails.map(e => EmailLocation(e, e)),
                  subject = s"Otoroshi Alert - ${evts.size} new alerts",
                  html = emailBody
                )
              )
              .getOrElse(FastFuture.successful(()))
        )
    }

  lazy val stream = Source.queue[AlertEvent](50000, OverflowStrategy.dropHead).mapAsync(5) { _evt =>
    for {
      evt    <- _evt.toEnrichedJson
      config <- env.datastores.globalConfigDataStore.singleton()
      r <- {
        config.kafkaConfig.foreach { kafkaConfig =>
          kafkaWrapper.publish(evt)(env, kafkaConfig)
        }
        if (config.kafkaConfig.isEmpty) kafkaWrapper.close()
        Future.sequence(config.alertsWebhooks.map { webhook =>
          val url = webhook.url
          env.MtlsWs
            .url(url, webhook.mtlsConfig)
            .withHttpHeaders(webhook.headers.toSeq: _*)
            .withMaybeProxyServer(config.proxies.alertWebhooks)
            .post(Json.obj("event" -> "ALERT", "payload" -> evt))
            .andThen {
              case Success(r) => r.ignore()
              case Failure(e) => {
                logger.error(s"Error while sending AlertEvent at '$url'", e)
              }
            }
        })
      }
      _ <- env.datastores.alertDataStore.push(evt)
    } yield r
  }

  lazy val (alertQueue, alertDone) = stream.toMat(Sink.ignore)(Keep.both).run()(env.analyticsMaterializer)
  lazy val (emailQueue, emailDone) = emailStream.toMat(Sink.ignore)(Keep.both).run()(env.analyticsMaterializer)

  override def receive: Receive = {
    case ge: AlertEvent => {
      val myself = self
      alertQueue.offer(ge).andThen {
        case Success(QueueOfferResult.Dropped) => logger.error("Enqueue Dropped AlertEvent :(")
        case Success(QueueOfferResult.QueueClosed) =>
          logger.error("Queue closed AlertEvent :(")
          context.stop(myself)
        case Success(QueueOfferResult.Failure(t)) =>
          logger.error("Enqueue Failed AlertEvent :(", t)
          context.stop(myself)
      }
      emailQueue.offer(ge).andThen {
        case Success(QueueOfferResult.Dropped) => logger.error("Enqueue Dropped EmailAlertEvent :(")
        case Success(QueueOfferResult.QueueClosed) =>
          logger.error("Queue closed AlertEvent :(")
          context.stop(myself)
        case Success(QueueOfferResult.Failure(t)) =>
          logger.error("Enqueue Failed EmailAlertEvent :(", t)
          context.stop(myself)
      }
    }
    case _ =>
  }
}

class AlertsActorSupervizer(env: Env) extends Actor {

  lazy val childName = "alert-actor"
  lazy val logger    = Logger("otoroshi-alert-actor-supervizer")

  // override def supervisorStrategy: SupervisorStrategy =
  //   OneForOneStrategy() {
  //     case e =>
  //       Restart
  //   }

  override def receive: Receive = {
    case Terminated(ref) =>
      logger.debug("Restarting alert actor child")
      context.watch(context.actorOf(AlertsActor.props(env), childName))
    case evt => context.child(childName).map(_ ! evt)
  }

  override def preStart(): Unit =
    if (context.child(childName).isEmpty) {
      logger.debug(s"Starting new child $childName")
      val ref = context.actorOf(AlertsActor.props(env), childName)
      context.watch(ref)
    }

  override def postStop(): Unit =
    context.children.foreach(_ ! PoisonPill)
}

object AlertsActorSupervizer {
  def props(implicit env: Env) = Props(new AlertsActorSupervizer(env))
}

object Alerts {

  lazy val logger = Logger("otoroshi-alerts")

  def send[A <: AlertEvent](alert: A)(implicit env: Env): Unit = {
    // logger.trace("Alert " + Json.stringify(alert.toEnrichedJson))
    alert.toAnalytics()
    env.alertsActor ! alert
  }
}

trait AlertDataStore {
  def count()(implicit ec: ExecutionContext, env: Env): Future[Long]
  def findAllRaw(from: Long = 0, to: Long = 1000)(implicit ec: ExecutionContext, env: Env): Future[Seq[ByteString]]
  // def push(event: AlertEvent)(implicit ec: ExecutionContext, env: Env): Future[Long]
  def push(event: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Long]
}
