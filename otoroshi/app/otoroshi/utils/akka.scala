package otoroshi.utils

import otoroshi.env.Env

object CustomizePekkoMediaTypesParser {

  /**
   * Legacy hook method that attempted to use reflection to modify Pekko HTTP's MediaTypes registry.
   *
   * This feature tried to allow Content-Type: application/json; charset=utf-8 which is technically
   * non-standard (JSON is always UTF-8 per RFC 8259). The implementation used reflection to hack
   * Pekko HTTP's internal registry, which:
   *
   * 1. Was fragile and broke with Pekko 1.3.0 / Java 25 due to module encapsulation
   * 2. Is not the correct way to handle this (should use ParserSettings or body parsers)
   * 3. Worked around a problem that shouldn't exist (clients should send proper headers)
   *
   * This feature is now disabled. If you need to support non-standard JSON Content-Type headers,
   * consider:
   * - Fixing the clients to send proper headers
   * - Using a custom body parser that's more lenient
   * - Configuring Play's parser settings in application.conf
   */
  @deprecated("This reflection-based approach is no longer supported", "17.12.0")
  def hook(env: Env): Unit = {
    val enabled = env.configuration
      .getOptional[Boolean]("otoroshi.options.enable-json-media-type-with-open-charset")
      .getOrElse(false)
    if (enabled) {
      env.logger.warn("otoroshi.options.enable-json-media-type-with-open-charset is enabled but ignored")
      env.logger.warn("This feature is deprecated and no longer functional due to Java 25 / Pekko 1.3.0 compatibility")
      env.logger.warn("Please ensure clients send proper 'Content-Type: application/json' headers without charset parameter")
      env.logger.warn("See https://www.rfc-editor.org/rfc/rfc8259#section-11 - JSON is always UTF-8")
    }
  }
}
