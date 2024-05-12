package otoroshi.netty

import otoroshi.env.Env
import otoroshi.next.extensions.HttpListenerNames
import otoroshi.ssl._
import otoroshi.utils.syntax.implicits._
import play.api.Configuration
import play.api.libs.json.{Format, JsError, JsResult, JsSuccess, JsValue, Json}
import reactor.netty.http.HttpDecoderSpec

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.{Failure, Success, Try}

case class HttpRequestParserConfig(
    allowDuplicateContentLengths: Boolean,
    validateHeaders: Boolean,
    h2cMaxContentLength: Int,
    initialBufferSize: Int,
    maxHeaderSize: Int,
    maxInitialLineLength: Int,
    maxChunkSize: Int
) {
  def json: JsValue = HttpRequestParserConfig.format.writes(this)
}

object HttpRequestParserConfig {
  lazy val default = format.reads(Json.obj()).get
  val format = new Format[HttpRequestParserConfig] {
    override def reads(json: JsValue): JsResult[HttpRequestParserConfig] = Try {
      HttpRequestParserConfig(
        allowDuplicateContentLengths = json.select("allowDuplicateContentLengths").asOpt[Boolean].getOrElse(HttpDecoderSpec.DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS),
        validateHeaders = json.select("validateHeaders").asOpt[Boolean].getOrElse(HttpDecoderSpec.DEFAULT_VALIDATE_HEADERS),
        h2cMaxContentLength = json.select("h2cMaxContentLength").asOpt[Int].getOrElse(65536),
        initialBufferSize = json.select("initialBufferSize").asOpt[Int].getOrElse(HttpDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE),
        maxHeaderSize = json.select("maxHeaderSize").asOpt[Int].getOrElse(HttpDecoderSpec.DEFAULT_MAX_HEADER_SIZE),
        maxInitialLineLength = json.select("maxInitialLineLength").asOpt[Int].getOrElse(HttpDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH),
        maxChunkSize = json.select("maxChunkSize").asOpt[Int].getOrElse(8192),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(config) => JsSuccess(config)
    }
    override def writes(o: HttpRequestParserConfig): JsValue = Json.obj(
      "allowDuplicateContentLengths" -> o.allowDuplicateContentLengths,
      "validateHeaders" -> o.validateHeaders,
      "h2cMaxContentLength" -> o.h2cMaxContentLength,
      "initialBufferSize" -> o.initialBufferSize,
      "maxHeaderSize" -> o.maxHeaderSize,
      "maxInitialLineLength" -> o.maxInitialLineLength,
      "maxChunkSize" -> o.maxChunkSize,
    )
  }
}

sealed trait NativeDriver {
  def name: String
  def json: JsValue = name.json
}
object NativeDriver {
  case object Auto    extends NativeDriver { def name: String = "Auto" }
  case object Epoll   extends NativeDriver { def name: String = "Epoll" }
  case object KQueue  extends NativeDriver { def name: String = "KQueue" }
  case object IOUring extends NativeDriver { def name: String = "IOUring" }
  val format = new Format[NativeDriver] {

    override def reads(json: JsValue): JsResult[NativeDriver] = json.asOpt[String].map(_.toLowerCase()) match {
      case Some("auto") => JsSuccess(Auto)
      case Some("epoll") => JsSuccess(Epoll)
      case Some("kqueue") => JsSuccess(KQueue)
      case Some("iouring") => JsSuccess(IOUring)
      case v => JsError(s"unsupported value: ${v}")
    }
    override def writes(o: NativeDriver): JsValue = o.json
  }
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
) {
  def json: JsValue = Http3Settings.format.writes(this)
}
object Http3Settings {
  lazy val default = format.reads(Json.obj()).get
  val format = new Format[Http3Settings] {
    override def reads(json: JsValue): JsResult[Http3Settings] = Try {
      Http3Settings(
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(false),
        port = json.select("port").asOpt[Int].getOrElse(-1),
        exposedPort = json.select("exposedPort").asOpt[Int].getOrElse(-1),
        maxSendUdpPayloadSize = json.select("maxSendUdpPayloadSize").asOpt[Long].getOrElse(1500),
        maxRecvUdpPayloadSize = json.select("maxRecvUdpPayloadSize").asOpt[Long].getOrElse(1500),
        initialMaxData = json.select("initialMaxData").asOpt[Long].getOrElse(10000000),
        initialMaxStreamDataBidirectionalLocal = json.select("initialMaxStreamDataBidirectionalLocal").asOpt[Long].getOrElse(10000000),
        initialMaxStreamDataBidirectionalRemote = json.select("initialMaxStreamDataBidirectionalRemote").asOpt[Long].getOrElse(10000000),
        initialMaxStreamsBidirectional = json.select("initialMaxStreamsBidirectional").asOpt[Long].getOrElse(10000000),
        disableQpackDynamicTable = json.select("disableQpackDynamicTable").asOpt[Boolean].getOrElse(true),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(config) => JsSuccess(config)
    }
    override def writes(o: Http3Settings): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "port" -> o.port,
      "exposedPort" -> o.exposedPort,
      "maxSendUdpPayloadSize" -> o.maxSendUdpPayloadSize,
      "maxRecvUdpPayloadSize" -> o.maxRecvUdpPayloadSize,
      "initialMaxData" -> o.initialMaxData,
      "initialMaxStreamDataBidirectionalLocal" -> o.initialMaxStreamDataBidirectionalLocal,
      "initialMaxStreamDataBidirectionalRemote" -> o.initialMaxStreamDataBidirectionalRemote,
      "initialMaxStreamsBidirectional" -> o.initialMaxStreamsBidirectional,
      "disableQpackDynamicTable" -> o.disableQpackDynamicTable,
    )
  }
}
case class Http1Settings(enabled: Boolean) {
  def json: JsValue = Http1Settings.format.writes(this)
}
object Http1Settings {
  lazy val default = format.reads(Json.obj()).get
  val format = new Format[Http1Settings] {
    override def reads(json: JsValue): JsResult[Http1Settings] = Try {
      Http1Settings(
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(config) => JsSuccess(config)
    }
    override def writes(o: Http1Settings): JsValue = Json.obj(
      "enabled" -> o.enabled,
    )
  }
}
case class Http2Settings(enabled: Boolean, h2cEnabled: Boolean) {
  def json: JsValue = Http2Settings.format.writes(this)
}
object Http2Settings {
  lazy val default = format.reads(Json.obj()).get
  val format = new Format[Http2Settings] {
    override def reads(json: JsValue): JsResult[Http2Settings] = Try {
      Http2Settings(
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        h2cEnabled = json.select("h2cEnabled").asOpt[Boolean].getOrElse(true)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(config) => JsSuccess(config)
    }
    override def writes(o: Http2Settings): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "h2cEnabled" -> o.h2cEnabled,
    )
  }
}
case class NativeSettings(enabled: Boolean, driver: NativeDriver) {
  def isEpoll: Boolean   = enabled && (driver == NativeDriver.Auto || driver == NativeDriver.Epoll)
  def isKQueue: Boolean  = enabled && (driver == NativeDriver.Auto || driver == NativeDriver.KQueue)
  def isIOUring: Boolean = enabled && (driver == NativeDriver.Auto || driver == NativeDriver.IOUring)
  def json: JsValue = NativeSettings.format.writes(this)
}
object NativeSettings {
  lazy val default = format.reads(Json.obj()).get
  val format = new Format[NativeSettings] {
    override def reads(json: JsValue): JsResult[NativeSettings] = Try {
      NativeSettings(
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        driver = json.select("driver").asOpt(NativeDriver.format).getOrElse(NativeDriver.Auto)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(config) => JsSuccess(config)
    }
    override def writes(o: NativeSettings): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "driver" -> o.driver.json,
    )
  }
}

case class ReactorNettyServerConfig(
    id: String,
    enabled: Boolean,
    exclusive: Boolean,
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
    idleTimeout: java.time.Duration,
    clientAuth: ClientAuth,
    parser: HttpRequestParserConfig,
    http1: Http1Settings,
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
    parseFromConfig(config, env, HttpListenerNames.Experimental.some)
  }

  def parseFromConfig(config: Configuration, env: Env, maybeId: Option[String]): ReactorNettyServerConfig = {
    ReactorNettyServerConfig(
      id = maybeId.orElse(config.getOptionalWithFileSupport[String]("id")).getOrElse(UUID.randomUUID().toString),
      enabled = config.getOptionalWithFileSupport[Boolean]("enabled").getOrElse(false),
      exclusive = config.getOptionalWithFileSupport[Boolean]("exclusive").getOrElse(false),
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
          .getOrElse(8192)
      ),
      http1 = Http1Settings(
        enabled = config.getOptionalWithFileSupport[Boolean]("http1.enabled").getOrElse(true),
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

  val format = new Format[ReactorNettyServerConfig] {

    override def reads(json: JsValue): JsResult[ReactorNettyServerConfig] = {
      Try {
        ReactorNettyServerConfig(
          id = json.select("id").as[String],
          enabled = json.select("enabled").asOpt[Boolean].getOrElse(false),
          exclusive = json.select("exclusive").asOpt[Boolean].getOrElse(false),
          newEngineOnly = json.select("new_engine_only").asOpt[Boolean].getOrElse(true),
          host = json.select("host").asOpt[String].getOrElse("0.0.0.0"),
          httpPort = json.select("http_port").asOpt[Int].getOrElse(-1),
          exposedHttpPort = json.select("exposed_http_port").asOpt[Int].getOrElse(-1),
          httpsPort = json.select("https_port").asOpt[Int].getOrElse(-1),
          exposedHttpsPort = json.select("exposed_https_port").asOpt[Int].getOrElse(-1),
          nThread = json.select("n_thread").asOpt[Int].getOrElse(0),
          wiretap = json.select("wiretap").asOpt[Boolean].getOrElse(false),
          accessLog = json.select("access_log").asOpt[Boolean].getOrElse(false),
          cipherSuites = json.select("cipher_suites").asOpt[Seq[String]],
          protocols = json.select("protocols").asOpt[Seq[String]],
          idleTimeout = json.select("idle_timeout").asOpt[Long].map(v => java.time.Duration.ofMillis(v)).getOrElse(java.time.Duration.ofMillis(60000)),
          clientAuth = json.select("client_auth").asOpt[String].flatMap(ClientAuth.apply).getOrElse(ClientAuth.None),
          parser = json.select("parser").asOpt(HttpRequestParserConfig.format).getOrElse(HttpRequestParserConfig.default),
          http1 = json.select("http_1").asOpt(Http1Settings.format).getOrElse(Http1Settings.default),
          http2 = json.select("http_2").asOpt(Http2Settings.format).getOrElse(Http2Settings.default),
          http3 = json.select("http_3").asOpt(Http3Settings.format).getOrElse(Http3Settings.default),
          native = json.select("native").asOpt(NativeSettings.format).getOrElse(NativeSettings.default),
        )
      } match {
        case Failure(exception) => JsError(exception.getMessage)
        case Success(config) => JsSuccess(config)
      }
    }

    override def writes(o: ReactorNettyServerConfig): JsValue = {
      Json.obj(
        "id" -> o.id,
        "enabled" -> o.enabled,
        "exclusive" -> o.exclusive,
        "new_engine_only" -> o.newEngineOnly,
        "host" -> o.host,
        "http_port" -> o.httpPort,
        "exposed_http_port" -> o.exposedHttpPort,
        "https_port" -> o.httpsPort,
        "exposed_https_port" -> o.exposedHttpsPort,
        "n_thread" -> o.nThread,
        "wiretap" -> o.wiretap,
        "access_log" -> o.accessLog,
        "cipher_suites" -> o.cipherSuites,
        "protocols" -> o.protocols,
        "idle_timeout" -> o.idleTimeout,
        "client_auth" -> o.clientAuth.name,
        "parser" -> o.parser.json,
        "http_2" -> o.http2.json,
        "http_3" -> o.http3.json,
        "native" -> o.native.json,
      )
    }
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
