package otoroshi.next.plugins

import otoroshi.models.{ApiKey, PrivateAppsUser}
import otoroshi.next.models.Backend
import play.api.libs.typedmap.TypedKey

import java.util.concurrent.atomic.AtomicBoolean

object Keys {
  val BackendKey = TypedKey[Backend]("otoroshi.next.core.Backend")
  val ApikeyKey = TypedKey[ApiKey]("otoroshi.next.core.Apikey")
  val UserKey = TypedKey[PrivateAppsUser]("otoroshi.next.core.User")
  val RequestTrackingIdKey = TypedKey[String]("otoroshi.next.core.TrackingId")
  val BodyAlreadyConsumedKey = TypedKey[AtomicBoolean]("otoroshi.next.core.BodyAlreadyConsumed")
}
