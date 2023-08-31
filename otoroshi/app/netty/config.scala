package otoroshi.netty

import otoroshi.env.Env
import otoroshi.ssl._
import otoroshi.utils.syntax.implicits._
import play.api.Configuration
import reactor.netty.http.HttpDecoderSpec

import java.util.concurrent.atomic.AtomicReference

case class HttpRequestParserConfig(
    allowDuplicateContentLengths: Boolean,
    validateHeaders: Boolean,
    h2cMaxContentLength: Int,
    initialBufferSize: Int,
    maxHeaderSize: Int,
    maxInitialLineLength: Int,
    maxChunkSize: Int
)

sealed trait NativeDriver
object NativeDriver {
  case object Auto    extends NativeDriver
  case object Epoll   extends NativeDriver
  case object KQueue  extends NativeDriver
  case object IOUring extends NativeDriver
}

case class Http3Settings(
    enabled: Boolean,
    port: Int,
    exposedPort: Int,
    maxSendUdpPayloadSize: Long,
    maxRecvUdpPayloadSize: Long,
    initialMaxData: Long,
    initialMaxStreamDataBidirectionalLocal: Long,
    initialMaxStreamDataBidirectionalRemote: Long,
    initialMaxStreamsBidirectional: Long,
    disableQpackDynamicTable: Boolean
)
case class Http2Settings(enabled: Boolean, h2cEnabled: Boolean)
case class NativeSettings(enabled: Boolean, driver: NativeDriver) {
  def isEpoll: Boolean   = enabled && (driver == NativeDriver.Auto || driver == NativeDriver.Epoll)
  def isKQueue: Boolean  = enabled && (driver == NativeDriver.Auto || driver == NativeDriver.KQueue)
  def isIOUring: Boolean = enabled && (driver == NativeDriver.Auto || driver == NativeDriver.IOUring)
}

case class ReactorNettyServerConfig(
    enabled: Boolean,
    newEngineOnly: Boolean,
    host: String,
    httpPort: Int,
    exposedHttpPort: Int,
    httpsPort: Int,
    exposedHttpsPort: Int,
    nThread: Int,
    wiretap: Boolean,
    accessLog: Boolean,
    cipherSuites: Option[Seq[String]],
    protocols: Option[Seq[String]],
    clientAuth: ClientAuth,
    idleTimeout: java.time.Duration,
    parser: HttpRequestParserConfig,
    http2: Http2Settings,
    http3: Http3Settings,
    native: NativeSettings
)

object ReactorNettyServerConfig {

  private val cache = new AtomicReference[ReactorNettyServerConfig](null)

  def parseFromWithCache(env: Env): ReactorNettyServerConfig = {
    cache.compareAndSet(null, _parseFrom(env))
    cache.get()
  }

  def _parseFrom(env: Env): ReactorNettyServerConfig = {
    val config = env.configuration.get[Configuration]("otoroshi.next.experimental.netty-server")
    ReactorNettyServerConfig(
      enabled = config.getOptionalWithFileSupport[Boolean]("enabled").getOrElse(false),
      newEngineOnly = config.getOptionalWithFileSupport[Boolean]("new-engine-only").getOrElse(false),
      host = config.getOptionalWithFileSupport[String]("host").getOrElse("0.0.0.0"),
      httpPort = config.getOptionalWithFileSupport[Int]("http-port").getOrElse(env.httpPort + 50),
      exposedHttpPort = config.getOptionalWithFileSupport[Int]("exposed-http-port").getOrElse(env.httpPort + 50),
      httpsPort = config.getOptionalWithFileSupport[Int]("https-port").getOrElse(env.httpsPort + 50),
      exposedHttpsPort = config.getOptionalWithFileSupport[Int]("exposed-https-port").getOrElse(env.httpsPort + 50),
      nThread = config.getOptionalWithFileSupport[Int]("threads").getOrElse(0),
      wiretap = config.getOptionalWithFileSupport[Boolean]("wiretap").getOrElse(false),
      accessLog = config.getOptionalWithFileSupport[Boolean]("accesslog").getOrElse(false),
      idleTimeout = config
        .getOptionalWithFileSupport[Long]("idleTimeout")
        .map(l => java.time.Duration.ofMillis(l))
        .getOrElse(java.time.Duration.ofMillis(60000)),
      cipherSuites = env.configuration
        .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.cipherSuites")
        .filterNot(_.isEmpty),
      protocols = env.configuration
        .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.protocols")
        .filterNot(_.isEmpty),
      clientAuth = {
        val auth = env.configuration
          .getOptionalWithFileSupport[String]("otoroshi.ssl.fromOutside.netty.clientAuth")
          .orElse(
            env.configuration
              .getOptionalWithFileSupport[String]("otoroshi.ssl.fromOutside.clientAuth")
          )
          .flatMap(ClientAuth.apply)
          .getOrElse(ClientAuth.None)
        if (DynamicSSLEngineProvider.logger.isDebugEnabled)
          DynamicSSLEngineProvider.logger.debug(s"Otoroshi netty client auth: ${auth}")
        auth
      },
      parser = HttpRequestParserConfig(
        allowDuplicateContentLengths = config
          .getOptionalWithFileSupport[Boolean]("parser.allowDuplicateContentLengths")
          .getOrElse(HttpDecoderSpec.DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS),
        validateHeaders = config
          .getOptionalWithFileSupport[Boolean]("parser.validateHeaders")
          .getOrElse(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS),
        h2cMaxContentLength = config.getOptionalWithFileSupport[Int]("parser.h2cMaxContentLength").getOrElse(65536),
        initialBufferSize = config
          .getOptionalWithFileSupport[Int]("parser.initialBufferSize")
          .getOrElse(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE),
        maxHeaderSize = config
          .getOptionalWithFileSupport[Int]("parser.maxHeaderSize")
          .getOrElse(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE),
        maxInitialLineLength = config
          .getOptionalWithFileSupport[Int]("parser.maxInitialLineLength")
          .getOrElse(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH),
        maxChunkSize = config
          .getOptionalWithFileSupport[Int]("parser.maxChunkSize")
          .getOrElse(HttpDecoderSpec.DEFAULT_MAX_CHUNK_SIZE)
      ),
      http2 = Http2Settings(
        enabled = config.getOptionalWithFileSupport[Boolean]("http2.enabled").getOrElse(true),
        h2cEnabled = config.getOptionalWithFileSupport[Boolean]("http2.h2c").getOrElse(true)
      ),
      http3 = Http3Settings(
        enabled = config.getOptionalWithFileSupport[Boolean]("http3.enabled").getOrElse(false),
        port = config.getOptionalWithFileSupport[Int]("http3.port").getOrElse(10048),
        exposedPort = config.getOptionalWithFileSupport[Int]("http3.exposedPort").getOrElse(10048),
        maxSendUdpPayloadSize = config.getOptionalWithFileSupport[Long]("http3.maxSendUdpPayloadSize").getOrElse(1500),
        maxRecvUdpPayloadSize = config.getOptionalWithFileSupport[Long]("http3.maxRecvUdpPayloadSize").getOrElse(1500),
        initialMaxData = config.getOptionalWithFileSupport[Long]("http3.initialMaxData").getOrElse(10000000),
        initialMaxStreamDataBidirectionalLocal =
          config.getOptionalWithFileSupport[Long]("http3.initialMaxStreamDataBidirectionalLocal").getOrElse(1000000),
        initialMaxStreamDataBidirectionalRemote =
          config.getOptionalWithFileSupport[Long]("http3.initialMaxStreamDataBidirectionalRemote").getOrElse(1000000),
        initialMaxStreamsBidirectional =
          config.getOptionalWithFileSupport[Long]("http3.initialMaxStreamsBidirectional").getOrElse(100000),
        disableQpackDynamicTable = config
          .getOptionalWithFileSupport[Boolean]("http3.disableQpackDynamicTable")
          .getOrElse(true) // cause it doesn't work in browsers if enabled
      ),
      native = NativeSettings(
        enabled = config.getOptionalWithFileSupport[Boolean]("native.enabled").getOrElse(true),
        driver = config
          .getOptionalWithFileSupport[String]("native.driver")
          .map {
            case "Auto"    => NativeDriver.Auto
            case "Epoll"   => NativeDriver.Epoll
            case "KQueue"  => NativeDriver.KQueue
            case "IOUring" => NativeDriver.IOUring
            case "auto"    => NativeDriver.Auto
            case "epoll"   => NativeDriver.Epoll
            case "kqueue"  => NativeDriver.KQueue
            case "iOUring" => NativeDriver.IOUring
            case _         => NativeDriver.Auto
          }
          .getOrElse(NativeDriver.Auto)
      )
    )
  }
}

case class NettyClientConfig(
    wiretap: Boolean,
    enforceAkkaClient: Boolean,
    enforceAll: Boolean
)

object NettyClientConfig {
  def parseFrom(env: Env): NettyClientConfig = {
    val config = env.configuration.get[Configuration]("otoroshi.next.experimental.netty-client")
    NettyClientConfig(
      wiretap = config.getOptionalWithFileSupport[Boolean]("wiretap").getOrElse(false),
      enforceAll = config.getOptionalWithFileSupport[Boolean]("enforce").getOrElse(false),
      enforceAkkaClient = config.getOptionalWithFileSupport[Boolean]("enforce-akka").getOrElse(false)
    )
  }
}
