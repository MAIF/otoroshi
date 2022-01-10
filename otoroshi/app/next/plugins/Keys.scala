package otoroshi.next.plugins

import otoroshi.models.{ApiKey, PrivateAppsUser}
import otoroshi.next.models.Backend
import play.api.libs.typedmap.TypedKey

object Keys {
  val BackendKey = TypedKey[Backend]("otoroshi.next.core.Backend")
  val ApikeyKey = TypedKey[ApiKey]("otoroshi.next.core.Apikey")
  val UserKey = TypedKey[PrivateAppsUser]("otoroshi.next.core.User")
}
