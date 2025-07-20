package otoroshi.plugins.core

import otoroshi.plugins.core.apikeys._

object DefaultPlugins {
  val all = Seq(
    s"cp:${classOf[ClientIdApikeyExtractor].getName}",
    s"cp:${classOf[CustomHeadersApikeyExtractor].getName}",
    s"cp:${classOf[JwtApikeyExtractor].getName}",
    s"cp:${classOf[BasicAuthApikeyExtractor].getName}"
  )
}
