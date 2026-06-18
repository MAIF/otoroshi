package otoroshi.utils

import otoroshi.env.Env
import play.api.Logger

object CustomizeAkkaMediaTypesParser {

  def hook(env: Env): Unit = {
    val enabled = env.configuration
      .getOptional[Boolean]("otoroshi.options.enable-json-media-type-with-open-charset")
      .getOrElse(false)
    if (enabled) {
      import org.apache.pekko.http.scaladsl.model._
      env.logger.warn("application/json mediatype with open charset is enabled")
      val openJson  = MediaType.customWithOpenCharset("application", "json")
      val fieldName = "org$apache$pekko$http$impl$util$ObjectRegistry$$_registry"
      val clazz     = getClass.getClassLoader.loadClass("org.apache.pekko.http.scaladsl.model.MediaTypes$")
      val field     = Option(clazz.getDeclaredField(fieldName)).getOrElse(clazz.getField(fieldName))
      field.setAccessible(true)
      val map       = field.get(MediaTypes).asInstanceOf[Map[(String, String), MediaType]]
      field.set(MediaTypes, map + (("application", "json") -> openJson))
    }
  }
}
