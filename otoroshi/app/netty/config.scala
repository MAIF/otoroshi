package otoroshi.netty

import otoroshi.env.Env
import otoroshi.ssl._
import otoroshi.utils.syntax.implicits._
import play.api.Configuration
import reactor.netty.http.HttpDecoderSpec

case class HttpRequestParserConfig(
  allowDuplicateContentLengths: Boolean,
  validateHeaders: Boolean,
  h2cMaxContentLength: Int,
  initialBufferSize: Int,
  maxHeaderSize: Int,
  maxInitialLineLength: Int,
  maxChunkSize: Int,
)

case class Http3Settings(enabled: Boolean, port: Int)

case class ReactorNettyServerConfig(
  enabled: Boolean,
  newEngineOnly: Boolean,
  host: String,
  httpPort: Int,
  httpsPort: Int,
  nThread: Int,
  wiretap: Boolean,
  accessLog: Boolean,
  cipherSuites: Option[Seq[String]],
  protocols: Option[Seq[String]],
  clientAuth: ClientAuth,
  idleTimeout: java.time.Duration,
  parser: HttpRequestParserConfig,
  http3: Http3Settings,
)

object ReactorNettyServerConfig {
  def parseFrom(env: Env): ReactorNettyServerConfig = {
    val config = env.configuration.get[Configuration]("otoroshi.next.experimental.netty-server")
    ReactorNettyServerConfig(
      enabled = config.getOptionalWithFileSupport[Boolean]("enabled").getOrElse(false),
      newEngineOnly = config.getOptionalWithFileSupport[Boolean]("new-engine-only").getOrElse(false),
      host = config.getOptionalWithFileSupport[String]("host").getOrElse("0.0.0.0"),
      httpPort = config.getOptionalWithFileSupport[Int]("http-port").getOrElse(env.httpPort + 50),
      httpsPort = config.getOptionalWithFileSupport[Int]("https-port").getOrElse(env.httpsPort + 50),
      nThread = config.getOptionalWithFileSupport[Int]("threads").getOrElse(0),
      wiretap = config.getOptionalWithFileSupport[Boolean]("wiretap").getOrElse(false),
      accessLog = config.getOptionalWithFileSupport[Boolean]("accesslog").getOrElse(false),
      idleTimeout = config.getOptionalWithFileSupport[Long]("idleTimeout").map(l => java.time.Duration.ofMillis(l)).getOrElse(java.time.Duration.ofMillis(60000)),
      cipherSuites =
        env.configuration
          .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.cipherSuites")
          .filterNot(_.isEmpty),
      protocols    =
        env.configuration
          .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.protocols")
          .filterNot(_.isEmpty),
      clientAuth = {
        val auth = env.configuration
          .getOptionalWithFileSupport[String]("otoroshi.ssl.fromOutside.clientAuth")
          .flatMap(ClientAuth.apply)
          .getOrElse(ClientAuth.None)
        if (DynamicSSLEngineProvider.logger.isDebugEnabled)
          DynamicSSLEngineProvider.logger.debug(s"Otoroshi netty client auth: ${auth}")
        auth
      },
      parser = HttpRequestParserConfig(
        allowDuplicateContentLengths = config.getOptionalWithFileSupport[Boolean]("parser.allowDuplicateContentLengths").getOrElse(HttpDecoderSpec.DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS),
        validateHeaders = config.getOptionalWithFileSupport[Boolean]("parser.validateHeaders").getOrElse(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS),
        h2cMaxContentLength = config.getOptionalWithFileSupport[Int]("parser.h2cMaxContentLength").getOrElse(65536),
        initialBufferSize = config.getOptionalWithFileSupport[Int]("parser.initialBufferSize").getOrElse(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE),
        maxHeaderSize = config.getOptionalWithFileSupport[Int]("parser.maxHeaderSize").getOrElse(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE),
        maxInitialLineLength = config.getOptionalWithFileSupport[Int]("parser.maxInitialLineLength").getOrElse(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH),
        maxChunkSize = config.getOptionalWithFileSupport[Int]("parser.maxChunkSize").getOrElse(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE),
      ),
      http3 = Http3Settings(
        enabled = config.getOptionalWithFileSupport[Boolean]("http3.enabled").getOrElse(false),
        port = config.getOptionalWithFileSupport[Int]("http3.port").getOrElse(10050),
      )
    )
  }
}