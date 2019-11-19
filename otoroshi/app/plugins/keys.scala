package otoroshi.plugins

import models.{ApiKey, PrivateAppsUser}
import play.api.libs.json.JsValue
import play.api.libs.typedmap.TypedKey

object Keys {
  val ApiKeyKey = TypedKey[ApiKey]("otoroshi.core.ApiKey")
  val UserKey = TypedKey[PrivateAppsUser]("otoroshi.core.UserKey")
  val GeolocationInfoKey = TypedKey[JsValue]("otoroshi.plugins.GeolocationInfo")
  val UserAgentInfoKey = TypedKey[JsValue]("otoroshi.plugins.UserAgentInfo")
}
