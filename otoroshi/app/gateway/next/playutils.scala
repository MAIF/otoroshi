package play.core.server.otoroshi

import akka.http.scaladsl.settings.ParserSettings
import play.api.Configuration
import play.api.http.HttpConfiguration
import play.api.libs.crypto.CookieSignerProvider
import play.api.mvc.{DefaultCookieHeaderEncoding, DefaultFlashCookieBaker, DefaultSessionCookieBaker}
import play.core.server.akkahttp.AkkaModelConversion
import play.core.server.common.{ForwardedHeaderHandler, ServerResultUtils}

object PlayUtils {
  def conversion(
    configuration: Configuration
  ): AkkaModelConversion = {

    val serverConfig = configuration.get[Configuration]("play.server")
    val akkaServerConfig = serverConfig.get[Configuration]("akka")
    val illegalResponseHeaderValueProcessingMode =
      akkaServerConfig.get[String]("illegal-response-header-value-processing-mode")

    val httpConfig   = HttpConfiguration()
    val cookieSigner = new CookieSignerProvider(httpConfig.secret).get
    val resultUtils = new ServerResultUtils(
      sessionBaker = new DefaultSessionCookieBaker(httpConfig.session, httpConfig.secret, cookieSigner),
      flashBaker = new DefaultFlashCookieBaker(httpConfig.flash, httpConfig.secret, cookieSigner),
      cookieHeaderEncoding = new DefaultCookieHeaderEncoding(httpConfig.cookies)
    )

    val parserSettings = ParserSettings.IllegalResponseHeaderValueProcessingMode(
      illegalResponseHeaderValueProcessingMode
    )

    val forwardedHeaderConfiguration = ForwardedHeaderHandler.ForwardedHeaderHandlerConfig(Some(configuration))

    new AkkaModelConversion(
      resultUtils,
      new ForwardedHeaderHandler(forwardedHeaderConfiguration),
      parserSettings
    )
  }
}
