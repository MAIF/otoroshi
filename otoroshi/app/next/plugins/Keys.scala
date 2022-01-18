package otoroshi.next.plugins

import otoroshi.models.{ApiKey, JwtInjection, PrivateAppsUser}
import otoroshi.next.models.{Backend, Route}
import otoroshi.next.proxy.ExecutionReport
import play.api.libs.typedmap.TypedKey

import java.util.concurrent.atomic.AtomicBoolean

object Keys {
  val ReportKey = TypedKey[ExecutionReport]("otoroshi.next.core.Report")
  val RouteKey = TypedKey[Route]("otoroshi.next.core.Route")
  val BackendKey = TypedKey[Backend]("otoroshi.next.core.Backend")
  // val ApikeyKey = TypedKey[ApiKey]("otoroshi.next.core.Apikey")
  // val UserKey = TypedKey[PrivateAppsUser]("otoroshi.next.core.User")
  val RequestTrackingIdKey = TypedKey[String]("otoroshi.next.core.TrackingId")
  val BodyAlreadyConsumedKey = TypedKey[AtomicBoolean]("otoroshi.next.core.BodyAlreadyConsumed")
  val JwtInjectionKey = TypedKey[JwtInjection]("otoroshi.next.core.JwtInjection")
}
