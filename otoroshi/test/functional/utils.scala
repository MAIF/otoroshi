package functional

import java.net.ServerSocket
import java.nio.file.Files
import java.util.Optional
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import akka.NotUsed
import akka.actor.{ActorSystem, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{Message, TextMessage, UpgradeToWebSocket}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Framing, Sink, Source}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import otoroshi.env.Env
import otoroshi.models._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{MustMatchers, OptionValues, TestSuite, WordSpec}
import org.scalatestplus.play.components.{OneServerPerSuiteWithComponents, OneServerPerTestWithComponents}
import org.slf4j.LoggerFactory
import otoroshi.api.Otoroshi
import otoroshi.models.DataExporterConfig
import otoroshi.modules.OtoroshiComponentsInstances
import play.api.ApplicationLoader.Context
import play.api.libs.json._
import play.api.libs.ws._
import play.api.libs.ws.ahc.{AhcWSClient, AhcWSClientConfig}
import play.api.{Application, BuiltInComponents, Configuration, Logger}
import play.core.server.ServerConfig

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Random, Success, Try}

trait AddConfiguration {
  def getConfiguration(configuration: Configuration): Configuration
}

class OtoroshiTestComponentsInstances(
    context: Context,
    conf: Configuration => Configuration,
    getHttpPort: => Option[Int],
    getHttpsPort: => Option[Int]
) extends OtoroshiComponentsInstances(context, getHttpPort, getHttpsPort, true) {
  override def configuration = conf(super.configuration)
}

trait OneServerPerSuiteWithMyComponents
    extends OneServerPerSuiteWithComponents
    with ScalaFutures
    with AddConfiguration { this: TestSuite =>

  lazy val otoroshiComponents = {
    val cpts = new OtoroshiTestComponentsInstances(context, getConfiguration, Some(port), Some(port + 1))
    println(s"Using env ${cpts.env}") // WARNING: important to keep, needed to switch env between suites
    cpts
  }

  override def components: BuiltInComponents = otoroshiComponents

  override def fakeApplication(): Application = {
    otoroshiComponents.application
  }

  private lazy val theConfig = new AtomicReference[Configuration]()

  final override def getConfiguration(configuration: Configuration) = {
    if (theConfig.get() == null) {
      theConfig.set(getTestConfiguration(configuration))
      // theConfig.set(theConfig.get().withFallback(Configuration(
      //   ConfigFactory
      //     .parseString(s"""
      //                    {
      //                      http.port=$port
      //                      play.server.http.port=$port
      //                    }
      //                  """)
      //     .resolve()
      // )))
    }
    theConfig.get()
  }

  def getTestConfiguration(configuration: Configuration): Configuration
}

trait OneServerPerTestWithMyComponents extends OneServerPerTestWithComponents with ScalaFutures with AddConfiguration {
  this: TestSuite =>

  val otoroshiComponents = {
    val components = new OtoroshiTestComponentsInstances(context, getConfiguration, Some(port), Some(port + 1))
    println(s"Using env ${components.env}") // WARNING: important to keep, needed to switch env between suites
    components
  }

  override def components: BuiltInComponents = otoroshiComponents
}

trait OtoroshiSpecHelper { suite: OneServerPerSuiteWithMyComponents =>

  lazy implicit val ec = otoroshiComponents.env.otoroshiExecutionContext
  lazy val logger      = Logger("otoroshi-spec-helper")

  private var _servers: Set[TargetService] = Set.empty

  def testServer(
      host: String,
      port: Int,
      delay: FiniteDuration = 0.millis,
      streamDelay: FiniteDuration = 0.millis,
      validate: HttpRequest => Boolean = _ => true,
      additionalHeadersOut: List[HttpHeader] = List.empty
  )(implicit ws: WSClient): (TargetService, Int, AtomicInteger, Map[String, String] => WSResponse) = {
    val counter = new AtomicInteger(0)
    val body    = """{"message":"hello world"}"""
    val server  = TargetService
      .streamed(
        None,
        "/api",
        "application/json",
        { r =>
          if (validate(r)) {
            counter.incrementAndGet()
          }
          if (delay.toMillis > 0L) {
            await(delay)
          }
          if (streamDelay.toMillis > 0L) {
            val head = body.head.toString
            val tail = body.tail
            Source
              .single(ByteString(head))
              .concat(Source.future(awaitF(streamDelay)(otoroshiComponents.actorSystem).map(_ => ByteString(tail))))
          } else {
            Source(List(ByteString(body)))
          }
        },
        additionalHeadersOut
      )
      .await()
    _servers = _servers + server
    (
      server,
      server.port,
      counter,
      (headers: Map[String, String]) => {
        val finalHeaders = (Map("Host" -> host) ++ headers).toSeq
        ws.url(s"http://127.0.0.1:${port}/api")
          .withHttpHeaders(finalHeaders: _*)
          .get()
          .futureValue
      }
    )
  }

  def testServerWithClientPath(
      host: String,
      port: Int,
      delay: FiniteDuration = 0.millis,
      streamDelay: FiniteDuration = 0.millis,
      validate: HttpRequest => Boolean = _ => true
  )(implicit ws: WSClient): (TargetService, Int, AtomicInteger, String => Map[String, String] => WSResponse) = {
    val counter = new AtomicInteger(0)
    val body    = """{"message":"hello world"}"""
    val server  = TargetService
      .streamed(
        None,
        "/api",
        "application/json",
        { r =>
          if (validate(r)) {
            counter.incrementAndGet()
          }
          if (delay.toMillis > 0L) {
            await(delay)
          }
          if (streamDelay.toMillis > 0L) {
            val head = body.head.toString
            val tail = body.tail
            Source
              .single(ByteString(head))
              .concat(Source.future(awaitF(streamDelay)(otoroshiComponents.actorSystem).map(_ => ByteString(tail))))
          } else {
            Source(List(ByteString(body)))
          }
        }
      )
      .await()
    _servers = _servers + server
    (
      server,
      server.port,
      counter,
      (path: String) =>
        (headers: Map[String, String]) => {
          val finalHeaders = (Map("Host" -> host) ++ headers).toSeq
          ws.url(s"http://127.0.0.1:${port}$path")
            .withHttpHeaders(finalHeaders: _*)
            .get()
            .futureValue
        }
    )
  }

  def stopServers(): Unit = {
    _servers.foreach(_.stop())
    _servers = Set.empty
  }

  def await(duration: FiniteDuration): Unit = {
    val p = Promise[Unit]
    otoroshiComponents.env.otoroshiScheduler.scheduleOnce(duration) {
      p.trySuccess(())
    }
    Await.result(p.future, duration + 1.second)
  }

  def awaitF(duration: FiniteDuration)(implicit system: ActorSystem): Future[Unit] = {
    val p = Promise[Unit]
    system.scheduler.scheduleOnce(duration) {
      p.trySuccess(())
    }
    p.future
  }

  def otoroshiApiCall(
      method: String,
      path: String,
      payload: Option[JsValue] = None,
      customPort: Option[Int] = None
  ): Future[(JsValue, Int)] = {
    val headers = Seq(
      "Host"   -> "otoroshi-api.oto.tools",
      "Accept" -> "application/json"
    )
    if (payload.isDefined) {
      suite.otoroshiComponents.wsClient
        .url(s"http://127.0.0.1:${customPort.getOrElse(port)}$path")
        .withHttpHeaders(headers :+ ("Content-Type" -> "application/json"): _*)
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .withFollowRedirects(false)
        .withMethod(method)
        .withBody(Json.stringify(payload.get))
        .execute()
        .map { response =>
          if (response.status != 200) {
            logger.error(response.body)
          }
          (response.json, response.status)
        }
    } else {
      suite.otoroshiComponents.wsClient
        .url(s"http://127.0.0.1:${customPort.getOrElse(port)}$path")
        .withHttpHeaders(headers: _*)
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .withFollowRedirects(false)
        .withMethod(method)
        .execute()
        .map { response =>
          if (response.status != 200) {
            logger.error(response.body)
          }
          (response.json, response.status)
        }
    }
  }

  def getOtoroshiConfig(
      customPort: Option[Int] = None,
      ws: WSClient = suite.otoroshiComponents.wsClient
  ): Future[GlobalConfig] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/globalconfig")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .map { response =>
        //if (response.status != 200) {
        //  println(response.body)
        //}
        GlobalConfig.fromJsons(response.json)
      }
  }

  def updateOtoroshiConfig(
      config: GlobalConfig,
      customPort: Option[Int] = None,
      ws: WSClient = suite.otoroshiComponents.wsClient
  ): Future[GlobalConfig] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/globalconfig")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .put(Json.stringify(config.toJson))
      .map { response =>
        //if (response.status != 200) {
        //  println(response.body)
        //}
        GlobalConfig.fromJsons(response.json)
      }
  }

  def getOtoroshiServices(
      customPort: Option[Int] = None,
      ws: WSClient = suite.otoroshiComponents.wsClient
  ): Future[Seq[ServiceDescriptor]] = {
    def fetch() =
      ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/services")
        .withHttpHeaders(
          "Host"   -> "otoroshi-api.oto.tools",
          "Accept" -> "application/json"
        )
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .get()

    for {
      _        <- fetch().recoverWith { case _ => FastFuture.successful(()) }
      response <- fetch()
    } yield {
      // if (response.status != 200) {
      //   println(response.body)
      // }
      try {
        response.json.as[JsArray].value.map(e => ServiceDescriptor.fromJsons(e))
      } catch {
        case e: Throwable => Seq.empty
      }
    }
  }

  def startSnowMonkey(customPort: Option[Int] = None): Future[Unit] = {
    suite.otoroshiComponents.wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/snowmonkey/_start")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post("")
      .map { response =>
        ()
      }
  }

  def stopSnowMonkey(customPort: Option[Int] = None): Future[Unit] = {
    suite.otoroshiComponents.wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/snowmonkey/_start")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post("")
      .map { response =>
        ()
      }
  }

  def updateSnowMonkey(
      f: SnowMonkeyConfig => SnowMonkeyConfig,
      customPort: Option[Int] = None
  ): Future[SnowMonkeyConfig] = {
    suite.otoroshiComponents.wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/snowmonkey/config")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .flatMap { response =>
        val config    = response.json.as[SnowMonkeyConfig](SnowMonkeyConfig._fmt)
        val newConfig = f(config)
        suite.otoroshiComponents.wsClient
          .url(s"http://localhost:${customPort.getOrElse(port)}/api/snowmonkey/config")
          .withHttpHeaders(
            "Host"         -> "otoroshi-api.oto.tools",
            "Accept"       -> "application/json",
            "Content-Type" -> "application/json"
          )
          .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
          .put(Json.stringify(newConfig.asJson))
          .flatMap { response =>
            val r = response.json.as[SnowMonkeyConfig](SnowMonkeyConfig._fmt)
            awaitF(100.millis)(otoroshiComponents.actorSystem).map(_ => r)
          }
      }
  }

  def getSnowMonkeyOutages(customPort: Option[Int] = None): Future[Seq[Outage]] = {
    suite.otoroshiComponents.wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/snowmonkey/outages")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .map { response =>
        response.json.as[JsArray].value.map(e => Outage.fmt.reads(e).get)
      }
  }

  def getOtoroshiServiceGroups(customPort: Option[Int] = None): Future[Seq[ServiceGroup]] = {
    suite.otoroshiComponents.wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/groups")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .map { response =>
        response.json.as[JsArray].value.map(e => ServiceGroup.fromJsons(e))
      }
  }

  def getOtoroshiApiKeys(customPort: Option[Int] = None): Future[Seq[ApiKey]] = {
    suite.otoroshiComponents.wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/apikeys")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .map { response =>
        response.json.as[JsArray].value.map(e => ApiKey.fromJsons(e))
      }
  }

  def createOtoroshiService(
      service: ServiceDescriptor,
      customPort: Option[Int] = None,
      ws: WSClient = suite.otoroshiComponents.wsClient
  ): Future[(JsValue, Int)] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/services")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post(Json.stringify(service.toJson))
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def createOtoroshiVerifier(
      verifier: GlobalJwtVerifier,
      customPort: Option[Int] = None,
      ws: WSClient = suite.otoroshiComponents.wsClient
  ): Future[(JsValue, Int)] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/verifiers")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post(Json.stringify(verifier.asJson))
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def createOtoroshiApiKey(
      apiKey: ApiKey,
      customPort: Option[Int] = None,
      ws: WSClient = suite.otoroshiComponents.wsClient
  ): Future[(JsValue, Int)] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/groups/default/apikeys")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post(Json.stringify(apiKey.toJson))
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def deleteOtoroshiApiKey(
      apiKey: ApiKey,
      customPort: Option[Int] = None,
      ws: WSClient = suite.otoroshiComponents.wsClient
  ): Future[(JsValue, Int)] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/groups/default/apikeys/${apiKey.clientId}")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .delete()
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def updateOtoroshiService(service: ServiceDescriptor, customPort: Option[Int] = None): Future[(JsValue, Int)] = {
    suite.otoroshiComponents.wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/services/${service.id}")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .put(Json.stringify(service.toJson))
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def deleteOtoroshiService(service: ServiceDescriptor, customPort: Option[Int] = None): Future[(JsValue, Int)] = {
    suite.otoroshiComponents.wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/services/${service.id}")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .delete()
      .map { resp =>
        (resp.json, resp.status)
      }
  }
}

trait OtoroshiSpec extends WordSpec with MustMatchers with OptionValues with ScalaFutures with IntegrationPatience {

  def getTestConfiguration(configuration: Configuration): Configuration

  private lazy val logger                              = Logger("otoroshi-spec")
  private lazy implicit val actorSystem: ActorSystem   = ActorSystem(s"test-actor-system")
  private lazy implicit val materializer: Materializer = Materializer(actorSystem)
  private lazy val wsClientInstance: WSClient = {
    val parser: WSConfigParser         = new WSConfigParser(
      Configuration(
        ConfigFactory
          .parseString("""
            |play {
            |
            |  # Configuration for Play WS
            |  ws {
            |
            |    timeout {
            |
            |      # If non null, the connection timeout, this is how long to wait for a TCP connection to be made
            |      connection = 2 minutes
            |
            |      # If non null, the idle timeout, this is how long to wait for any IO activity from the remote host
            |      # while making a request
            |      idle = 2 minutes
            |
            |      # If non null, the request timeout, this is the maximum amount of time to wait for the whole request
            |      request = 2 minutes
            |    }
            |
            |    # Whether redirects should be followed
            |    followRedirects = true
            |
            |    # Whether the JDK proxy properties should be read
            |    useProxyProperties = true
            |
            |    # If non null, will set the User-Agent header on requests to this
            |    useragent = null
            |
            |    # Whether compression should be used on incoming and outgoing requests
            |    compressionEnabled = false
            |
            |    # ssl configuration
            |    ssl {
            |
            |      # Whether we should use the default JVM SSL configuration or not
            |      default = false
            |
            |      # The ssl protocol to use
            |      protocol = "TLSv1.2"
            |
            |      # Whether revocation lists should be checked, if null, defaults to platform default setting.
            |      checkRevocation = null
            |
            |      # A sequence of URLs for obtaining revocation lists
            |      revocationLists = []
            |
            |      # The enabled cipher suites. If empty, uses the platform default.
            |      enabledCipherSuites = []
            |
            |      # The enabled protocols. If empty, uses the platform default.
            |      enabledProtocols = ["TLSv1.2", "TLSv1.1", "TLSv1"]
            |
            |      # The disabled signature algorithms
            |      disabledSignatureAlgorithms = ["MD2", "MD4", "MD5"]
            |
            |      # The disabled key algorithms
            |      disabledKeyAlgorithms = ["RSA keySize < 2048", "DSA keySize < 2048", "EC keySize < 224"]
            |
            |      # The debug configuration
            |      debug = []
            |
            |      # The hostname verifier class.
            |      # If non null, should be the fully qualify classname of a class that implements HostnameVerifier, otherwise
            |      # the default will be used
            |      hostnameVerifierClass = null
            |
            |      # Configuration for the key manager
            |      keyManager {
            |        # The key manager algorithm. If empty, uses the platform default.
            |        algorithm = null
            |
            |        # The key stores
            |        stores = [
            |        ]
            |        # The key stores should look like this
            |        prototype.stores {
            |          # The store type. If null, defaults to the platform default store type, ie JKS.
            |          type = null
            |
            |          # The path to the keystore file. Either this must be non null, or data must be non null.
            |          path = null
            |
            |          # The data for the keystore. Either this must be non null, or path must be non null.
            |          data = null
            |
            |          # The password for loading the keystore. If null, uses no password.
            |          password = null
            |        }
            |      }
            |
            |      trustManager {
            |        # The trust manager algorithm. If empty, uses the platform default.
            |        algorithm = null
            |
            |        # The trust stores
            |        stores = [
            |        ]
            |        # The key stores should look like this
            |        prototype.stores {
            |          # The store type. If null, defaults to the platform default store type, ie JKS.
            |          type = null
            |
            |          # The path to the keystore file. Either this must be non null, or data must be non null.
            |          path = null
            |
            |          # The data for the keystore. Either this must be non null, or path must be non null.
            |          data = null
            |        }
            |
            |      }
            |
            |      # The loose ssl options.  These allow configuring ssl to be more loose about what it accepts,
            |      # at the cost of introducing potential security issues.
            |      loose {
            |
            |        # Whether weak protocols should be allowed
            |        allowWeakProtocols = false
            |
            |        # Whether weak ciphers should be allowed
            |        allowWeakCiphers = false
            |
            |        # If non null, overrides the platform default for whether legacy hello messages should be allowed.
            |        allowLegacyHelloMessages = null
            |
            |        # If non null, overrides the platform default for whether unsafe renegotiation should be allowed.
            |        allowUnsafeRenegotiation = null
            |
            |        # Whether hostname verification should be disabled
            |        disableHostnameVerification = false
            |
            |        # Whether any certificate should be accepted or not
            |        acceptAnyCertificate = false
            |
            |        # Whether the SNI (Server Name Indication) TLS extension should be disabled
            |        # This setting MAY be respected by client libraries.
            |        #
            |        # https://tools.ietf.org/html/rfc3546#sectiom-3.1
            |        disableSNI = false
            |      }
            |
            |      # Debug configuration
            |      debug {
            |
            |        # Turn on all debugging
            |        all = false
            |
            |        # Turn on ssl debugging
            |        ssl = false
            |
            |        # Turn certpath debugging on
            |        certpath = false
            |
            |        # Turn ocsp debugging on
            |        ocsp = false
            |
            |        # Enable per-record tracing
            |        record = false
            |
            |        # hex dump of record plaintext, requires record to be true
            |        plaintext = false
            |
            |        # print raw SSL/TLS packets, requires record to be true
            |        packet = false
            |
            |        # Print each handshake message
            |        handshake = false
            |
            |        # Print hex dump of each handshake message, requires handshake to be true
            |        data = false
            |
            |        # Enable verbose handshake message printing, requires handshake to be true
            |        verbose = false
            |
            |        # Print key generation data
            |        keygen = false
            |
            |        # Print session activity
            |        session = false
            |
            |        # Print default SSL initialization
            |        defaultctx = false
            |
            |        # Print SSLContext tracing
            |        sslctx = false
            |
            |        # Print session cache tracing
            |        sessioncache = false
            |
            |        # Print key manager tracing
            |        keymanager = false
            |
            |        # Print trust manager tracing
            |        trustmanager = false
            |
            |        # Turn pluggability debugging on
            |        pluggability = false
            |
            |      }
            |
            |      sslParameters {
            |        # translates to a setNeedClientAuth / setWantClientAuth calls
            |        # "default" – leaves the (which for JDK8 means wantClientAuth and needClientAuth are set to false.)
            |        # "none"    – `setNeedClientAuth(false)`
            |        # "want"    – `setWantClientAuth(true)`
            |        # "need"    – `setNeedClientAuth(true)`
            |        clientAuth = "default"
            |
            |        # protocols (names)
            |        protocols = []
            |      }
            |    }
            |    ahc {
            |      # Pools connections.  Replaces setAllowPoolingConnections and setAllowPoolingSslConnections.
            |      keepAlive = true
            |
            |      # The maximum number of connections to make per host. -1 means no maximum.
            |      maxConnectionsPerHost = -1
            |
            |      # The maximum total number of connections. -1 means no maximum.
            |      maxConnectionsTotal = -1
            |
            |      # The maximum number of redirects.
            |      maxNumberOfRedirects = 5
            |
            |      # The maximum number of times to retry a request if it fails.
            |      maxRequestRetry = 5
            |
            |      # If non null, the maximum time that a connection should live for in the pool.
            |      maxConnectionLifetime = null
            |
            |      # If non null, the time after which a connection that has been idle in the pool should be closed.
            |      idleConnectionInPoolTimeout = 1 minute
            |
            |      # If non null, the frequency to cleanup timeout idle connections
            |      connectionPoolCleanerPeriod = 1 second
            |
            |      # Whether the raw URL should be used.
            |      disableUrlEncoding = false
            |
            |      # Whether to use LAX(no cookie name/value verification) or STRICT (verifies cookie name/value) cookie decoder
            |      useLaxCookieEncoder = false
            |
            |      # Whether to use a cookie store
            |      useCookieStore = false
            |    }
            |  }
            |}
          """.stripMargin)
          .resolve()
      ).underlying,
      this.getClass.getClassLoader
    )
    val config: AhcWSClientConfig      = new AhcWSClientConfig(wsClientConfig = parser.parse()).copy(
      keepAlive = true
    )
    val wsClientConfig: WSClientConfig = config.wsClientConfig.copy(
      compressionEnabled = false,
      idleTimeout = (2 * 60 * 1000).millis,
      connectionTimeout = (2 * 60 * 1000).millis
    )
    AhcWSClient(
      config.copy(
        wsClientConfig = wsClientConfig
      )
    )(materializer)
  }
  private lazy implicit val scheduler: Scheduler       = actorSystem.scheduler
  lazy implicit val ec: ExecutionContext               = actorSystem.dispatcher
  private lazy val httpPort: Int = {
    Try {
      val s = new ServerSocket(0)
      val p = s.getLocalPort
      s.close()
      p
    }.getOrElse(8080)
  }
  private lazy val httpsPort: Int = {
    Try {
      val s = new ServerSocket(0)
      val p = s.getLocalPort
      s.close()
      p
    }.getOrElse(8443)
  }

  def wsClient: WSClient             = wsClientInstance
  def ws: WSClient                   = wsClientInstance
  lazy implicit val wsImpl: WSClient = wsClientInstance
  def port: Int                      = httpPort
  def otoroshiInstance: Otoroshi     = otoroshi
  def otoroshiComponents: Otoroshi   = otoroshi

  private lazy val otoroshi = Otoroshi(
    ServerConfig(
      address = "0.0.0.0",
      port = Some(httpPort),
      rootDir = Files.createTempDirectory("otoroshi-test-helper").toFile
    ),
    getTestConfiguration(Configuration.empty).underlying
  ).startAndStopOnShutdown()

  private var _servers: Set[TargetService] = Set.empty

  def testServer(
      host: String,
      port: Int,
      delay: FiniteDuration = 0.millis,
      streamDelay: FiniteDuration = 0.millis,
      validate: HttpRequest => Boolean = _ => true,
      additionalHeadersOut: List[HttpHeader] = List.empty
  )(implicit ws: WSClient): (TargetService, Int, AtomicInteger, Map[String, String] => WSResponse) = {
    val counter = new AtomicInteger(0)
    val body    = """{"message":"hello world"}"""
    val server  = TargetService
      .streamed(
        None,
        "/api",
        "application/json",
        { r =>
          if (validate(r)) {
            counter.incrementAndGet()
          }
          if (delay.toMillis > 0L) {
            await(delay)
          }
          if (streamDelay.toMillis > 0L) {
            val head = body.head.toString
            val tail = body.tail
            Source
              .single(ByteString(head))
              .concat(Source.future(awaitF(streamDelay)(actorSystem).map(_ => ByteString(tail))))
          } else {
            Source(List(ByteString(body)))
          }
        },
        additionalHeadersOut
      )
      .await()
    _servers = _servers + server
    (
      server,
      server.port,
      counter,
      (headers: Map[String, String]) => {
        val finalHeaders = (Map("Host" -> host) ++ headers).toSeq
        ws.url(s"http://127.0.0.1:${port}/api")
          .withHttpHeaders(finalHeaders: _*)
          .get()
          .futureValue
      }
    )
  }

  def testServerWithClientPath(
      host: String,
      port: Int,
      delay: FiniteDuration = 0.millis,
      streamDelay: FiniteDuration = 0.millis,
      validate: HttpRequest => Boolean = _ => true
  )(implicit ws: WSClient): (TargetService, Int, AtomicInteger, String => Map[String, String] => WSResponse) = {
    val counter = new AtomicInteger(0)
    val body    = """{"message":"hello world"}"""
    val server  = TargetService
      .streamed(
        None,
        "/api",
        "application/json",
        { r =>
          if (validate(r)) {
            counter.incrementAndGet()
          }
          if (delay.toMillis > 0L) {
            await(delay)
          }
          if (streamDelay.toMillis > 0L) {
            val head = body.head.toString
            val tail = body.tail
            Source
              .single(ByteString(head))
              .concat(Source.future(awaitF(streamDelay)(actorSystem).map(_ => ByteString(tail))))
          } else {
            Source(List(ByteString(body)))
          }
        }
      )
      .await()
    _servers = _servers + server
    (
      server,
      server.port,
      counter,
      (path: String) =>
        (headers: Map[String, String]) => {
          val finalHeaders = (Map("Host" -> host) ++ headers).toSeq
          ws.url(s"http://127.0.0.1:${port}$path")
            .withHttpHeaders(finalHeaders: _*)
            .get()
            .futureValue
        }
    )
  }

  def stopServers(): Unit = {
    _servers.foreach(_.stop())
    _servers = Set.empty
  }

  def startOtoroshi(): Unit = {
    otoroshi.env.logger.debug("Starting !!!")
    Source
      .tick(1.second, 1.second, ())
      .mapAsync(1) { _ =>
        wsClientInstance
          .url(s"http://127.0.0.1:$port/health")
          .withRequestTimeout(1.second)
          .get()
          .map(r => r.status)
          .recover { case e =>
            0
          }
      }
      .filter(_ == 200)
      .take(1)
      .runForeach(_ => ())
      .futureValue
  }

  private def stopOtoroshi() = {
    otoroshi.stop()
    Source
      .tick(1.millisecond, 1.second, ())
      .mapAsync(1) { _ =>
        wsClientInstance
          .url(s"http://127.0.0.1:$port/health")
          .withRequestTimeout(1.second)
          .get()
          .map(r => r.status)
          .recover { case e =>
            0
          }
      }
      .filter(_ != 200)
      .take(1)
      .runForeach(_ => ())
      .futureValue
  }

  def stopAll(): Unit = {
    stopServers()
    stopOtoroshi()
  }

  def await(duration: FiniteDuration): Unit = {
    val p = Promise[Unit]
    scheduler.scheduleOnce(duration) {
      p.trySuccess(())
    }
    Await.result(p.future, duration + 1.second)
  }

  def awaitF(duration: FiniteDuration)(implicit system: ActorSystem): Future[Unit] = {
    val p = Promise[Unit]
    system.scheduler.scheduleOnce(duration) {
      p.trySuccess(())
    }
    p.future
  }

  def otoroshiApiCall(
      method: String,
      path: String,
      payload: Option[JsValue] = None,
      customPort: Option[Int] = None
  ): Future[(JsValue, Int)] = {
    val headers = Seq(
      "Host"   -> "otoroshi-api.oto.tools",
      "Accept" -> "application/json"
    )
    if (payload.isDefined) {
      wsClient
        .url(s"http://127.0.0.1:${customPort.getOrElse(port)}$path")
        .withHttpHeaders(headers :+ ("Content-Type" -> "application/json"): _*)
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .withFollowRedirects(false)
        .withMethod(method)
        .withBody(Json.stringify(payload.get))
        .execute()
        .map { response =>
          if (response.status != 200 && response.status != 201) {
            logger.error(response.status + " - " + response.body)
          }
          (response.json, response.status)
        }
    } else {
      wsClient
        .url(s"http://127.0.0.1:${customPort.getOrElse(port)}$path")
        .withHttpHeaders(headers: _*)
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .withFollowRedirects(false)
        .withMethod(method)
        .execute()
        .map { response =>
          if (response.status != 200 && response.status != 201) {
            logger.error(response.status + " - " + response.body)
          }
          (response.json, response.status)
        }
    }
  }

  def getOtoroshiConfig(customPort: Option[Int] = None, ws: WSClient = wsClient): Future[GlobalConfig] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/globalconfig")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .map { response =>
        //if (response.status != 200) {
        //  println(response.body)
        //}
        GlobalConfig.fromJsons(response.json)
      }
  }

  def createExporterConfig(
      config: DataExporterConfig,
      customPort: Option[Int] = None,
      ws: WSClient = wsClient
  ): Future[DataExporterConfig] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/data-exporter-configs")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post(Json.stringify(config.json))
      .map { response =>
        DataExporterConfig.fromJsons(response.json)
      }
  }
  def deleteExporterConfig(id: String, customPort: Option[Int] = None, ws: WSClient = wsClient): Future[Unit] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/data-exporter-configs/$id")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .delete()
      .map(_ => ())
  }

  def updateOtoroshiConfig(
      config: GlobalConfig,
      customPort: Option[Int] = None,
      ws: WSClient = wsClient
  ): Future[GlobalConfig] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/globalconfig")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .put(Json.stringify(config.toJson))
      .map { response =>
        //if (response.status != 200) {
        //  println(response.body)
        //}
        GlobalConfig.fromJsons(response.json)
      }
  }

  def getOtoroshiServices(customPort: Option[Int] = None, ws: WSClient = wsClient): Future[Seq[ServiceDescriptor]] = {
    def fetch() =
      ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/services")
        .withHttpHeaders(
          "Host"   -> "otoroshi-api.oto.tools",
          "Accept" -> "application/json"
        )
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .get()

    for {
      _        <- fetch().recoverWith { case _ => FastFuture.successful(()) }
      response <- fetch()
    } yield {
      // if (response.status != 200) {
      //   println(response.body)
      // }
      try {
        response.json.as[JsArray].value.map(e => ServiceDescriptor.fromJsons(e))
      } catch {
        case e: Throwable => Seq.empty
      }
    }
  }

  def startSnowMonkey(customPort: Option[Int] = None): Future[Unit] = {
    wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/snowmonkey/_start")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post("")
      .map { response =>
        ()
      }
  }

  def stopSnowMonkey(customPort: Option[Int] = None): Future[Unit] = {
    wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/snowmonkey/_start")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post("")
      .map { response =>
        ()
      }
  }

  def updateSnowMonkey(
      f: SnowMonkeyConfig => SnowMonkeyConfig,
      customPort: Option[Int] = None
  ): Future[SnowMonkeyConfig] = {
    wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/snowmonkey/config")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .flatMap { response =>
        val config    = response.json.as[SnowMonkeyConfig](SnowMonkeyConfig._fmt)
        val newConfig = f(config)
        wsClient
          .url(s"http://localhost:${customPort.getOrElse(port)}/api/snowmonkey/config")
          .withHttpHeaders(
            "Host"         -> "otoroshi-api.oto.tools",
            "Accept"       -> "application/json",
            "Content-Type" -> "application/json"
          )
          .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
          .put(Json.stringify(newConfig.asJson))
          .flatMap { response =>
            val r = response.json.as[SnowMonkeyConfig](SnowMonkeyConfig._fmt)
            awaitF(100.millis)(actorSystem).map(_ => r)
          }
      }
  }

  def getSnowMonkeyOutages(customPort: Option[Int] = None): Future[Seq[Outage]] = {
    wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/snowmonkey/outages")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .map { response =>
        response.json.as[JsArray].value.map(e => Outage.fmt.reads(e).get)
      }
  }

  def getOtoroshiServiceGroups(customPort: Option[Int] = None): Future[Seq[ServiceGroup]] = {
    wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/groups")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .map { response =>
        response.json.as[JsArray].value.map(e => ServiceGroup.fromJsons(e))
      }
  }

  def getOtoroshiApiKeys(customPort: Option[Int] = None): Future[Seq[ApiKey]] = {
    wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/apikeys")
      .withHttpHeaders(
        "Host"   -> "otoroshi-api.oto.tools",
        "Accept" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .get()
      .map { response =>
        response.json.as[JsArray].value.map(e => ApiKey.fromJsons(e))
      }
  }

  def createOtoroshiService(
      service: ServiceDescriptor,
      customPort: Option[Int] = None,
      ws: WSClient = wsClient
  ): Future[(JsValue, Int)] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/services")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post(Json.stringify(service.toJson))
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def createOtoroshiVerifier(
      verifier: GlobalJwtVerifier,
      customPort: Option[Int] = None,
      ws: WSClient = wsClient
  ): Future[(JsValue, Int)] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/verifiers")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post(Json.stringify(verifier.asJson))
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def createOtoroshiApiKey(
      apiKey: ApiKey,
      customPort: Option[Int] = None,
      ws: WSClient = wsClient
  ): Future[(JsValue, Int)] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/groups/default/apikeys")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post(Json.stringify(apiKey.toJson))
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def deleteOtoroshiApiKey(
      apiKey: ApiKey,
      customPort: Option[Int] = None,
      ws: WSClient = wsClient
  ): Future[(JsValue, Int)] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/groups/default/apikeys/${apiKey.clientId}")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .delete()
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def updateOtoroshiService(service: ServiceDescriptor, customPort: Option[Int] = None): Future[(JsValue, Int)] = {
    wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/services/${service.id}")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .put(Json.stringify(service.toJson))
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def deleteOtoroshiService(service: ServiceDescriptor, customPort: Option[Int] = None): Future[(JsValue, Int)] = {
    wsClient
      .url(s"http://localhost:${customPort.getOrElse(port)}/api/services/${service.id}")
      .withHttpHeaders(
        "Host" -> "otoroshi-api.oto.tools"
        // "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .delete()
      .map { resp =>
        (resp.json, resp.status)
      }
  }
}

object Implicits {
  implicit class BetterFuture[A](val fu: Future[A])      extends AnyVal {
    def await(): A = {
      Await.result(fu, 60.seconds)
    }
  }
  implicit class BetterOptional[A](val opt: Optional[A]) extends AnyVal {
    def asOption: Option[A] = {
      if (opt.isPresent) {
        Some(opt.get())
      } else {
        None
      }
    }
  }
}

object HttpResponses {

  def NotFound(path: String) =
    HttpResponse(
      404,
      entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> s"$path not found")))
    )

  def GatewayTimeout() =
    HttpResponse(
      504,
      entity =
        HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> s"Target servers timeout")))
    )

  def BadGateway(message: String) =
    HttpResponse(
      502,
      entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> message)))
    )

  def BadRequest(message: String) =
    HttpResponse(
      400,
      entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> message)))
    )

  def Unauthorized(message: String) =
    HttpResponse(
      401,
      entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> message)))
    )

  def Ok(json: JsValue) =
    HttpResponse(
      200,
      entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(json))
    )
}

class TargetService(
    val port: Int,
    host: Option[String],
    path: String,
    contentType: String,
    result: HttpRequest => (Int, String, Option[Source[ByteString, _]], List[HttpHeader])
) {

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = Materializer(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    (request.method, request.uri.path) match {
      case (HttpMethods.GET, p) if host.isEmpty                                    => {
        val (code, body, source, headers) = result(request)
        val entity                        = source match {
          case None    =>
            HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`), ByteString(body))
          case Some(s) => HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`), s)
        }
        FastFuture.successful(
          HttpResponse(
            code,
            headers = headers,
            entity = entity
          )
        )
      }
      case (HttpMethods.GET, p) if TargetService.extractHost(request) == host.get  => {
        val (code, body, source, headers) = result(request)
        val entity                        = source match {
          case None    =>
            HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`), ByteString(body))
          case Some(s) => HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`), s)
        }
        FastFuture.successful(
          HttpResponse(
            code,
            headers = headers,
            entity = entity
          )
        )
      }
      case (HttpMethods.POST, p) if TargetService.extractHost(request) == host.get => {
        val (code, body, source, headers) = result(request)
        val entity                        = source match {
          case None    =>
            HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`), ByteString(body))
          case Some(s) => HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`), s)
        }
        FastFuture.successful(
          HttpResponse(
            code,
            headers = headers,
            entity = entity
          )
        )
      }
      case (HttpMethods.DELETE, p)                                                 => {
        val (code, body, source, headers) = result(request)
        val entity                        = source match {
          case None    =>
            HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`), ByteString(body))
          case Some(s) => HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`), s)
        }
        FastFuture.successful(
          HttpResponse(
            code,
            headers = headers,
            entity = entity
          )
        )
      }
      case (_, p)                                                                  => {
        FastFuture.successful(HttpResponses.NotFound(p.toString()))
      }
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): TargetService = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(stopAsync(), 10.seconds)
  }

  def stopAsync(): Future[Unit] = {
    for {
      _ <- bound.map(_.unbind())
      _ <- http.shutdownAllConnectionPools()
      _ <- system.terminate()
    } yield ()
  }
}

class SimpleTargetService(host: Option[String], path: String, contentType: String, result: HttpRequest => String) {

  val port = TargetService.freePort

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = Materializer(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    (request.method, request.uri.path) match {
      case (_, _) => {
        FastFuture.successful(
          HttpResponse(
            200,
            entity = HttpEntity(
              ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`),
              ByteString(result(request))
            )
          )
        )
      }
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): SimpleTargetService = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(bound, 60.seconds).unbind()
    Await.result(http.shutdownAllConnectionPools(), 60.seconds)
    Await.result(system.terminate(), 60.seconds)
  }
}

class AlertServer(counter: AtomicInteger) {

  val port = TargetService.freePort

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = Materializer(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    request.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { bodyByteString =>
      val body = bodyByteString.utf8String
      counter.incrementAndGet()
      HttpResponse(
        200,
        entity = HttpEntity(ContentTypes.`application/json`, ByteString(Json.stringify(Json.obj("done" -> true))))
      )
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): AlertServer = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(bound, 60.seconds).unbind()
    Await.result(http.shutdownAllConnectionPools(), 60.seconds)
    Await.result(system.terminate(), 60.seconds)
  }
}

class AnalyticsServer(counter: AtomicInteger) {

  val port = TargetService.freePort

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = Materializer(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    request.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { bodyByteString =>
      val body   = bodyByteString.utf8String
      val events = Json.parse(body).as[JsArray].value
      // println(Json.parse(body).as[JsArray].value.filter(a => (a \ "@type").as[String] == "AlertEvent").map(a => (a \ "alert").as[String]))
      counter.addAndGet(events.size)
      HttpResponse(
        200,
        entity = HttpEntity(ContentTypes.`application/json`, ByteString(Json.stringify(Json.obj("done" -> true))))
      )
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): AnalyticsServer = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(bound, 60.seconds).unbind()
    Await.result(http.shutdownAllConnectionPools(), 60.seconds)
    Await.result(system.terminate(), 60.seconds)
  }
}

class WebsocketServer(counter: AtomicInteger) {

  val port = TargetService.freePort

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = Materializer(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  val greeterWebSocketService =
    Flow[Message]
      .map { message =>
        println("server received message")
        counter.incrementAndGet()
        TextMessage(Source.single("Hello ") ++ message.asTextMessage.getStreamedText)
      }

  def handler(request: HttpRequest): Future[HttpResponse] = {
    request.header[UpgradeToWebSocket] match {
      case Some(upgrade) => FastFuture.successful(upgrade.handleMessages(greeterWebSocketService))
      case None          => FastFuture.successful(HttpResponse(400, entity = "Not a valid websocket request!"))
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): WebsocketServer = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(bound, 60.seconds).unbind()
    Await.result(http.shutdownAllConnectionPools(), 60.seconds)
    Await.result(system.terminate(), 60.seconds)
  }
}

object TargetService {

  import Implicits._

  def apply(host: Option[String], path: String, contentType: String, result: HttpRequest => String): TargetService = {
    new TargetService(
      TargetService.freePort,
      host,
      path,
      contentType,
      r => (200, result(r), None, List.empty[HttpHeader])
    )
  }

  def streamed(
      host: Option[String],
      path: String,
      contentType: String,
      result: HttpRequest => Source[ByteString, NotUsed],
      headers: List[HttpHeader] = List.empty[HttpHeader]
  ): TargetService = {
    new TargetService(TargetService.freePort, host, path, contentType, r => (200, "", Some(result(r)), headers))
  }

  def full(
      host: Option[String],
      path: String,
      contentType: String,
      result: HttpRequest => (Int, String, List[HttpHeader])
  ): TargetService = {
    new TargetService(
      TargetService.freePort,
      host,
      path,
      contentType,
      r =>
        result(r) match {
          case (p, b, h) => (p, b, None, h)
        }
    )
  }

  def withPort(
      port: Int,
      host: Option[String],
      path: String,
      contentType: String,
      result: HttpRequest => String
  ): TargetService = {
    new TargetService(port, host, path, contentType, r => (200, result(r), None, List.empty[HttpHeader]))
  }

  def freePort: Int = {
    Try {
      val serverSocket = new ServerSocket(0)
      val port         = serverSocket.getLocalPort
      serverSocket.close()
      port
    }.toOption.getOrElse(Random.nextInt(1000) + 7000)
  }

  private val AbsoluteUri = """(?is)^(https?)://([^/]+)(/.*|$)""".r

  def extractHost(request: HttpRequest): String =
    request.getHeader("Otoroshi-Proxied-Host").asOption.map(_.value()).getOrElse("--")
}

class BodySizeService() {

  val port = TargetService.freePort

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = Materializer(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    request.entity.withoutSizeLimit().dataBytes.runFold(ByteString.empty)(_ ++ _) map { body =>
      HttpResponse(
        200,
        entity = HttpEntity(
          ContentTypes.`application/json`,
          ByteString(Json.stringify(Json.obj("bodySize" -> body.size, "body" -> body.utf8String)))
        )
      )
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): BodySizeService = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(bound, 60.seconds).unbind()
    Await.result(http.shutdownAllConnectionPools(), 60.seconds)
    Await.result(system.terminate(), 60.seconds)
  }
}

object TestRegex {

  import java.util.regex.Pattern
  val pattern = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[#$^+=!*()@%&]).{8,1000}$")
  val matches = pattern.matcher("FifouFifou1!").matches()
  println(matches)
}

case class ApiTesterResult(
    create: Boolean,
    createBulk: Boolean,
    findAll: Boolean,
    findById: Boolean,
    update: Boolean,
    updateBulk: Boolean,
    patch: Boolean,
    patchBulk: Boolean,
    delete: Boolean,
    deleteBulk: Boolean
) {
  def works: Boolean =
    create && createBulk && findAll && findById && update && updateBulk && patch && patchBulk && delete && deleteBulk
}

trait ApiTester[Entity] {

  import otoroshi.utils.syntax.implicits._

  private val logger = Logger("otoroshi-api-tester")

  def entityName: String
  def singleEntity(): Entity
  def bulkEntities(): Seq[Entity] =
    Seq(
      singleEntity(),
      singleEntity(),
      singleEntity()
    )
  def route(): String
  def readEntityFromJson(json: JsValue): Entity
  def writeEntityToJson(entity: Entity): JsValue
  def updateEntity(entity: Entity): Entity
  def patchEntity(entity: Entity): (Entity, JsArray)
  def extractId(entity: Entity): String
  def ws: WSClient
  def env: Env
  def port: Int

  def testingBulk: Boolean = true

  def beforeTest()(implicit ec: ExecutionContext): Future[Unit] = FastFuture.successful(())
  def afterTest()(implicit ec: ExecutionContext): Future[Unit]  = FastFuture.successful(())

  private def assertBodyJson(expected: JsValue, result: JsValue, name: String): Boolean = {
    if (result != expected) {
      logger.error(
        s"[$entityName] $name: expected body does not match - ${Json.stringify(expected)} / ${Json.stringify(result)}"
      )
      false
    } else {
      true
    }
  }

  private def assertBody(expected: Entity, result: JsValue, name: String): Boolean = {
    //if (result != writeEntityToJson(expected)) {
    val resEntity = readEntityFromJson(result)
    if (resEntity != expected) {
      logger.error(s"[$entityName] $name: expected entity does not match")
      false
    } else {
      true
    }
  }

  private def assertBodyHasAllIds(
      entities: Seq[Entity],
      checker: Int => Boolean,
      body: Source[ByteString, _],
      name: String
  ): Boolean = {
    implicit val ec           = env.otoroshiExecutionContext
    implicit val mat          = env.otoroshiMaterializer
    val indexedEntities       = entities.map(v => (extractId(v), v)).toMap
    val indexedResultEntities = Await.result(
      body
        .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
        .filter(_.utf8String.trim.nonEmpty)
        .map(bs => Try(Json.parse(bs.utf8String)))
        .collect { case Success(e) => e }
        .map(v => ((v \ "id").as[String], (v \ "status").as[Int]))
        .runWith(Sink.seq)
        .map(_.toMap),
      30.seconds
    )
    indexedEntities.forall { case (k, v) =>
      indexedResultEntities.get(k) match {
        case Some(i) => checker(i)
        case None    => false
      }
    }
  }

  private def testCreateEntity(entity: Entity)(implicit ec: ExecutionContext): Future[Boolean] = {
    val path = route()
    ws
      .url(s"http://otoroshi-api.oto.tools:$port$path")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withHttpHeaders("Content-Type" -> "application/json")
      .withFollowRedirects(false)
      .withMethod("POST")
      .withBody(ByteString(Json.stringify(writeEntityToJson(entity))))
      .execute()
      .flatMap { resp =>
        if (resp.status == 200 || resp.status == 201) {
          if (assertBody(entity, resp.json, "testCreateEntity")) {
            testFindById(entity, "testCreateEntity".some)
          } else {
            false.future
          }
        } else {
          logger.error(s"[$entityName] testCreateEntity: bad status code: ${resp.status}, expected 201 or 200")
          logger.error(s"[$entityName] testCreateEntity: ${resp.body}")
          false.future
        }
      }
  }
  private def testUpdateEntity(entity: Entity, updatedEntity: Entity)(implicit
      ec: ExecutionContext
  ): Future[Boolean] = {
    testFindById(entity, "testUpdateEntity pre".some).flatMap {
      case false => false.future
      case true  => {
        val path = route() + "/" + extractId(entity)
        ws
          .url(s"http://otoroshi-api.oto.tools:$port$path")
          .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
          .withHttpHeaders("Content-Type" -> "application/json")
          .withFollowRedirects(false)
          .withMethod("PUT")
          .withBody(ByteString(Json.stringify(writeEntityToJson(updatedEntity))))
          .execute()
          .flatMap { resp =>
            if (resp.status == 200) {
              if (assertBody(updatedEntity, resp.json, "testUpdateEntity")) {
                testFindById(updatedEntity, "testUpdateEntity".some)
              } else {
                false.future
              }
            } else {
              logger.error(s"[$entityName] testUpdateEntity: bad status code: ${resp.status}, expected 200")
              false.future
            }
          }
      }
    }
  }
  private def testPatchEntity(entity: Entity, updatedEntity: (Entity, JsArray))(implicit
      ec: ExecutionContext
  ): Future[Boolean] = {
    testFindById(entity, "testPatchEntity pre".some).flatMap {
      case false => false.future
      case true  => {
        val path = route() + "/" + extractId(entity)
        ws
          .url(s"http://otoroshi-api.oto.tools:$port$path")
          .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
          .withHttpHeaders("Content-Type" -> "application/json")
          .withFollowRedirects(false)
          .withMethod("PATCH")
          .withBody(ByteString(Json.stringify(updatedEntity._2)))
          .execute()
          .flatMap { resp =>
            if (resp.status == 200 || resp.status == 201) {
              if (assertBody(updatedEntity._1, resp.json, "testPatchEntity")) {
                testFindById(updatedEntity._1, "testPatchEntity".some)
              } else {
                false.future
              }
            } else {
              logger.error(s"[$entityName] testPatchEntity: bad status code: ${resp.status}, expected 200")
              false.future
            }
          }
      }
    }
  }
  private def testDeleteEntity(entity: Entity)(implicit ec: ExecutionContext): Future[Boolean] = {
    testFindById(entity, "testDeleteEntity pre".some).flatMap {
      case false => false.future
      case true  => {
        val path = route() + "/" + extractId(entity)
        ws
          .url(s"http://otoroshi-api.oto.tools:$port$path")
          .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
          .withFollowRedirects(false)
          .withMethod("DELETE")
          .execute()
          .flatMap { resp =>
            if (resp.status == 200 || resp.status == 201) {
              if (assertBodyJson(Json.obj("deleted" -> true), resp.json, "testDeleteEntity")) {
                testFindById(entity, "testDeleteEntity".some).map(v => !v)
              } else {
                false.future
              }
            } else {
              logger.error(s"[$entityName] testDeleteEntity: bad status code: ${resp.status}, expected 200")
              false.future
            }
          }
      }
    }
  }
  private def testFindAll(entities: Seq[Entity], ctx: Option[String])(implicit
      ec: ExecutionContext
  ): Future[Boolean] = {
    val path = route()
    ws
      .url(s"http://otoroshi-api.oto.tools:$port$path")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withFollowRedirects(false)
      .withMethod("GET")
      .execute()
      .map { resp =>
        if (resp.status == 200) {
          val arr         = resp.json.as[JsArray].value
          if (arr.isEmpty) logger.info(s"[$entityName] ${ctx.getOrElse("testFindAll")}: empty collection")
          val retEntities = arr.map(readEntityFromJson)
          // logger.info(s"$retEntities - $entities")
          entities.forall(e => retEntities.contains(e))
        } else {
          logger.error(s"[$entityName] ${ctx.getOrElse("testFindAll")}: bad status code: ${resp.status}, expected 200")
          false
        }
      }
  }
  private def testFindById(entity: Entity, ctx: Option[String])(implicit ec: ExecutionContext): Future[Boolean] = {
    val path = route() + "/" + extractId(entity)
    ws
      .url(s"http://otoroshi-api.oto.tools:$port$path")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withFollowRedirects(false)
      .withMethod("GET")
      .execute()
      .map { resp =>
        if (resp.status == 200) {
          val retEntity = readEntityFromJson(resp.json)
          val eq        = retEntity == entity
          if (!eq) logger.info(s"[$entityName] ${ctx.getOrElse("testFindById")}: $retEntity found, expected $entity")
          eq
        } else {
          logger.error(s"[$entityName] ${ctx.getOrElse("testFindById")}: bad status code: ${resp.status}, expected 200")
          false
        }
      }
  }

  private def testCreateEntities(entities: Seq[Entity])(implicit ec: ExecutionContext): Future[Boolean] = {
    val path = route() + "/_bulk"
    ws
      .url(s"http://otoroshi-api.oto.tools:$port$path")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withHttpHeaders("Content-Type" -> "application/x-ndjson")
      .withFollowRedirects(false)
      .withMethod("POST")
      .withBody(SourceBody(Source(entities.toList).map(v => ByteString(Json.stringify(writeEntityToJson(v)) + "\n"))))
      .execute()
      .flatMap { resp =>
        if (resp.status == 200 || resp.status == 201) {
          if (assertBodyHasAllIds(entities, s => s == 200 || s == 201, resp.bodyAsSource, "testCreateEntities")) {
            testFindAll(entities, "testCreateEntities".some)
          } else {
            false.future
          }
        } else {
          logger.error(s"[$entityName] testCreateEntities: bad status code: ${resp.status}, expected 201 or 200")
          false.future
        }
      }
  }
  private def testPatchEntities(entities: Seq[Entity], updatedEntities: Seq[(Entity, JsArray)])(implicit
      ec: ExecutionContext
  ): Future[Boolean] = {
    testFindAll(entities, "testPatchEntities pre".some).flatMap {
      case false =>
        false.future
      case true  => {
        val path = route() + "/_bulk"
        ws
          .url(s"http://otoroshi-api.oto.tools:$port$path")
          .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
          .withHttpHeaders("Content-Type" -> "application/x-ndjson")
          .withFollowRedirects(false)
          .withMethod("PATCH")
          .withBody(
            SourceBody(
              Source(updatedEntities.toList).map(v =>
                ByteString(Json.stringify(Json.obj("id" -> extractId(v._1), "patch" -> v._2)) + "\n")
              )
            )
          )
          .execute()
          .flatMap { resp =>
            if (resp.status == 200) {
              if (
                assertBodyHasAllIds(
                  updatedEntities.map(_._1),
                  s => s == 200 || s == 201,
                  resp.bodyAsSource,
                  "testPatchEntities"
                )
              ) {
                testFindAll(updatedEntities.map(_._1), "testPatchEntities".some)
              } else {
                false.future
              }
            } else {
              logger.error(s"[$entityName] testPatchEntities: bad status code: ${resp.status}, expected 200")
              false.future
            }
          }
      }
    }
  }
  private def testUpdateEntities(entities: Seq[Entity], updatedEntities: Seq[Entity])(implicit
      ec: ExecutionContext
  ): Future[Boolean] = {
    testFindAll(entities, "testUpdateEntities pre".some).flatMap {
      case false =>
        false.future
      case true  => {
        val path = route() + "/_bulk"
        ws
          .url(s"http://otoroshi-api.oto.tools:$port$path")
          .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
          .withHttpHeaders("Content-Type" -> "application/x-ndjson")
          .withFollowRedirects(false)
          .withMethod("PUT")
          .withBody(
            SourceBody(Source(updatedEntities.toList).map(v => ByteString(Json.stringify(writeEntityToJson(v)) + "\n")))
          )
          .execute()
          .flatMap { resp =>
            if (resp.status == 200) {
              if (
                assertBodyHasAllIds(updatedEntities, s => s == 200 || s == 201, resp.bodyAsSource, "testUpdateEntities")
              ) {
                testFindAll(updatedEntities, "testPatchEntities".some)
              } else {
                false.future
              }
            } else {
              logger.error(s"[$entityName] testUpdateEntities: bad status code: ${resp.status}, expected 200")
              false.future
            }
          }
      }
    }
  }
  private def testDeleteEntities(entities: Seq[Entity])(implicit ec: ExecutionContext): Future[Boolean] = {
    testFindAll(entities, "testDeleteEntities pre".some).flatMap {
      case false => false.future
      case true  => {
        val path = route() + "/_bulk"
        ws
          .url(s"http://otoroshi-api.oto.tools:$port$path")
          .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
          .withFollowRedirects(false)
          .withMethod("DELETE")
          .withBody(
            entities.map(v => Json.stringify(Json.obj("id" -> extractId(v)))).mkString("\n")
          ) // weird ahc behavior, overrides content type. source doesnt work too
          .withHttpHeaders("Content-Type" -> "application/x-ndjson", "X-Content-Type" -> "application/x-ndjson")
          .execute()
          .flatMap { resp =>
            if (resp.status == 200) {
              if (assertBodyHasAllIds(entities, s => s == 200 || s == 201, resp.bodyAsSource, "testDeleteEntities")) {
                testFindAll(entities, "testDeleteEntities".some).map(v => !v)
              } else {
                false.future
              }
            } else {
              logger.error(s"[$entityName] testDeleteEntities: bad status code: ${resp.status}, expected 200")
              false.future
            }
          }
      }
    }
  }

  def testApi(implicit ec: ExecutionContext): Future[ApiTesterResult] = {

    for {

      _ <- beforeTest()

      entity        = singleEntity()
      updatedEntity = updateEntity(entity)
      patchedEntity = patchEntity(entity)
      create       <- testCreateEntity(entity)
      findAll      <- testFindAll(Seq(entity), None)
      findById     <- testFindById(entity, None)
      update       <- testUpdateEntity(entity, updatedEntity)
      patch        <- testPatchEntity(updatedEntity, patchedEntity)
      delete       <- testDeleteEntity(patchedEntity._1)

      entities        = bulkEntities()
      updatedEntities = entities.map(entity => updateEntity(entity))
      patchedEntities = entities.map(entity => patchEntity(entity))
      createBulk     <- if (testingBulk) testCreateEntities(entities) else true.future
      findAll2       <- if (testingBulk) testFindAll(entities, None) else true.future
      updateBulk     <- if (testingBulk) testUpdateEntities(entities, updatedEntities) else true.future
      patchBulk      <- if (testingBulk) testPatchEntities(updatedEntities, patchedEntities) else true.future
      deleteBulk     <- if (testingBulk) testDeleteEntities(patchedEntities.map(_._1)) else true.future

      _ <- afterTest()

    } yield {
      ApiTesterResult(
        create,
        createBulk,
        findAll && findAll2,
        findById,
        update,
        updateBulk,
        patch,
        patchBulk,
        delete,
        deleteBulk
      )
    }
  }
}
