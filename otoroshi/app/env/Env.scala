package env

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{Executors, ThreadFactory, TimeUnit}

import akka.actor.{ActorSystem, PoisonPill}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import events._
import gateway.CircuitBreakersHolder
import health.{HealthCheckerActor, StartHealthCheck}
import models._
import org.mindrot.jbcrypt.BCrypt
import play.api._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import play.api.libs.ws._
import play.api.libs.ws.ahc._
import play.shaded.ahc.org.asynchttpclient.AsyncHttpClientConfig
import security.{ClaimCrypto, IdGenerator}
import storage.DataStores
import storage.cassandra.CassandraDataStores
import storage.inmemory.InMemoryDataStores
import storage.leveldb.LevelDbDataStores
import storage.redis.RedisDataStores

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.io.Source
import scala.util.{Failure, Success}

class Env(val configuration: Configuration,
          environment: Environment,
          lifecycle: ApplicationLifecycle,
          wsClient: WSClient,
          val circuitBeakersHolder: CircuitBreakersHolder) {

  private lazy val scheduler = Executors.newScheduledThreadPool(procNbr * 2)

  lazy val logger = Logger("otoroshi-env")

  def timeout(duration: FiniteDuration): Future[Unit] = {
    val promise = Promise[Unit]
    scheduler.schedule(new Runnable {
      override def run(): Unit = promise.trySuccess(())
    }, duration.toMillis, TimeUnit.MILLISECONDS)
    promise.future
  }

  val (internalActorSystem, analyticsActor, alertsActor, healthCheckerActor) = {
    implicit val as = ActorSystem(
      "otoroshi-internal-system",
      configuration
        .getOptional[Configuration]("app.actorsystems.internal")
        .map(_.underlying)
        .getOrElse(ConfigFactory.empty)
    )
    implicit val mat = ActorMaterializer.create(as)
    implicit val ec  = as.dispatcher
    val aa           = as.actorOf(AnalyticsActorSupervizer.props(this))
    val ala          = as.actorOf(AlertsActorSupervizer.props(this))
    val ha           = as.actorOf(HealthCheckerActor.props(this))
    timeout(FiniteDuration(5, SECONDS)).andThen { case _ if isProd => ha ! StartHealthCheck() }
    (as, aa, ala, ha)
  }

  lazy val materializer = ActorMaterializer.create(internalActorSystem)

  lazy val websocketHandlerActorSystem = ActorSystem(
    "otoroshi-websockets-system",
    configuration
      .getOptional[Configuration]("app.actorsystems.websockets")
      .map(_.underlying)
      .getOrElse(ConfigFactory.empty)
  )

  lazy val maxWebhookSize: Int = configuration.getOptional[Int]("app.webhooks.size").getOrElse(100)

  //lazy val middleFingers: Boolean = configuration.getOptional[Boolean]]("app.middleFingers").getOrElse(false)
  //lazy val maxLocalLogsSize: Int = configuration.getOptional[Int]("app.events.maxSize").getOrElse(1000)

  lazy val healthLimit: Double           = configuration.getOptional[Double]("app.healthLimit").getOrElse(1000.0)
  lazy val throttlingWindow: Int         = configuration.getOptional[Int]("app.throttlingWindow").getOrElse(10)
  lazy val analyticsWindow: Int          = configuration.getOptional[Int]("app.analyticsWindow").getOrElse(30)
  lazy val auth0UserMeta: String         = configuration.getOptional[String]("app.userMeta").getOrElse("otoroshi_data")
  lazy val auth0AppMeta: String          = configuration.getOptional[String]("app.appMeta").getOrElse("app_metadata")
  lazy val eventsName: String            = configuration.getOptional[String]("app.eventsName").getOrElse("otoroshi")
  lazy val storageRoot: String           = configuration.getOptional[String]("app.storageRoot").getOrElse("otoroshi")
  lazy val useCache: Boolean             = configuration.getOptional[Boolean]("app.useCache").getOrElse(false)
  lazy val useRedisScan: Boolean         = configuration.getOptional[Boolean]("app.redis.useScan").getOrElse(false)
  lazy val secret: String                = configuration.getOptional[String]("play.crypto.secret").get
  lazy val sharedKey: String             = configuration.getOptional[String]("app.claim.sharedKey").get
  lazy val env: String                   = configuration.getOptional[String]("app.env").getOrElse("prod")
  lazy val exposeAdminApi: Boolean       = configuration.getOptional[Boolean]("app.adminapi.exposed").getOrElse(true)
  lazy val exposeAdminDashboard: Boolean = configuration.getOptional[Boolean]("app.backoffice.exposed").getOrElse(true)
  lazy val adminApiProxyHttps: Boolean   = configuration.getOptional[Boolean]("app.adminapi.proxy.https").getOrElse(false)
  lazy val adminApiProxyUseLocal: Boolean =
    configuration.getOptional[Boolean]("app.adminapi.proxy.local").getOrElse(true)
  lazy val redirectToDev: Boolean = env
    .toLowerCase() == "dev" && configuration.getOptional[Boolean]("app.redirectToDev").getOrElse(false)
  lazy val envInUrl: String =
    configuration.getOptional[String]("app.env").filterNot(_ == "prod").map(v => s"$v.").getOrElse("")
  lazy val domain: String = configuration.getOptional[String]("app.domain").getOrElse("foo.bar")
  lazy val adminApiSubDomain: String =
    configuration.getOptional[String]("app.adminapi.targetSubdomain").getOrElse("otoroshi-admin-internal-api")
  lazy val adminApiExposedSubDomain: String =
    configuration.getOptional[String]("app.adminapi.exposedSubdomain").getOrElse("otoroshi-api")
  lazy val backOfficeSubDomain: String =
    configuration.getOptional[String]("app.backoffice.subdomain").getOrElse("otoroshi")
  lazy val privateAppsSubDomain: String =
    configuration.getOptional[String]("app.privateapps.subdomain").getOrElse("privateapps")
  lazy val retries: Int = configuration.getOptional[Int]("app.retries").getOrElse(5)

  lazy val backOfficeServiceId = configuration.getOptional[String]("app.adminapi.defaultValues.backOfficeServiceId").get
  lazy val backOfficeGroupId   = configuration.getOptional[String]("app.adminapi.defaultValues.backOfficeGroupId").get
  lazy val backOfficeApiKeyClientId =
    configuration.getOptional[String]("app.adminapi.defaultValues.backOfficeApiKeyClientId").get
  lazy val backOfficeApiKeyClientSecret =
    configuration.getOptional[String]("app.adminapi.defaultValues.backOfficeApiKeyClientSecret").get

  def composeUrl(subdomain: String): String     = s"$subdomain.$envInUrl$domain"
  def composeMainUrl(subdomain: String): String = if (isDev) composeUrl(subdomain) else s"$subdomain.$domain"
  // def composeMainUrl(subdomain: String): String = composeUrl(subdomain)

  lazy val adminApiExposedHost = composeMainUrl(adminApiExposedSubDomain)
  lazy val adminApiHost        = composeMainUrl(adminApiSubDomain)
  lazy val backOfficeHost      = composeMainUrl(backOfficeSubDomain)
  lazy val privateAppsHost     = composeMainUrl(privateAppsSubDomain)

  lazy val procNbr = Runtime.getRuntime.availableProcessors()

  lazy val auth0ExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutorService(
      Executors.newFixedThreadPool(procNbr + 1, factory("otoroshi-auth0-requests"))
    )
  lazy val auditExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutorService(
      Executors.newFixedThreadPool(procNbr + 1, factory("otoroshi-audit-requests"))
    )
  lazy val apiExecutionContext: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(procNbr + 1, factory("otoroshi-admin-api-requests"))
  )
  lazy val backOfficeExecutionContext: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(procNbr + 1, factory("otoroshi-backoffice-requests"))
  )
  lazy val privateAppsExecutionContext: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(procNbr + 1, factory("otoroshi-private-apps-requests"))
  )
  lazy val pressureActorSystem = ActorSystem(
    "otoroshi-pressure-system",
    configuration
      .getOptional[Configuration]("app.actorsystems.pressure")
      .map(_.underlying)
      .getOrElse(ConfigFactory.empty)
  )
  lazy val pressureExecutionContext: ExecutionContext = pressureActorSystem.dispatcher

  lazy val gatewayActorSystem = ActorSystem(
    "otoroshi-gateway-system",
    configuration
      .getOptional[Configuration]("app.actorsystems.gateway")
      .map(_.underlying)
      .getOrElse(ConfigFactory.empty)
  )

  lazy val gatewayExecutor     = gatewayActorSystem.dispatcher
  lazy val gatewayMaterializer = ActorMaterializer.create(gatewayActorSystem)

  lazy val gatewayClient = {
    val parser  = new WSConfigParser(configuration.underlying, environment.classLoader)
    val config  = new AhcWSClientConfig(wsClientConfig = parser.parse()).copy(keepAlive = true)
    val builder = new AhcConfigBuilder(config)
    // TODO : use it
    val ahcConfig: AsyncHttpClientConfig = builder
      .configure()
      .setCompressionEnforced(false)
      .setKeepAlive(true)
      .setHttpClientCodecMaxChunkSize(1024 * 100)
      .build()
    AhcWSClient(config.copy(wsClientConfig = config.wsClientConfig.copy(compressionEnabled = false)))(
      gatewayMaterializer
    )
  }

  lazy val kafkaActorSytem = ActorSystem(
    "otoroshi-kafka-system",
    configuration.getOptional[Configuration]("app.actorsystems.kafka").map(_.underlying).getOrElse(ConfigFactory.empty)
  )
  lazy val statsdActorSytem = ActorSystem(
    "otoroshi-statsd-system",
    configuration.getOptional[Configuration]("app.actorsystems.statsd").map(_.underlying).getOrElse(ConfigFactory.empty)
  )

  lazy val statsd = new StatsdWrapper(statsdActorSytem, this)

  lazy val mode   = environment.mode
  lazy val isDev  = mode == Mode.Dev
  lazy val isProd = !isDev
  lazy val notDev = !isDev
  lazy val hash   = s"${System.currentTimeMillis()}"

  lazy val privateAppsSessionExp = configuration.getOptional[Long]("app.privateapps.session.exp").get
  lazy val backOfficeSessionExp  = configuration.getOptional[Long]("app.backoffice.session.exp").get

  lazy val exposedRootScheme = configuration.getOptional[String]("app.rootScheme").getOrElse("https")

  def rootScheme               = if (isDev) "http://" else s"${exposedRootScheme}://"
  def exposedRootSchemeIsHttps = exposedRootScheme == "https"

  def Ws = wsClient

  lazy val snowflakeSeed      = configuration.getOptional[Long]("app.snowflake.seed").get
  lazy val snowflakeGenerator = IdGenerator(snowflakeSeed)
  lazy val redirections: Seq[String] =
    configuration.getOptional[Seq[String]]("app.redirections").map(_.toSeq).getOrElse(Seq.empty[String])

  lazy val crypto = ClaimCrypto(sharedKey)

  object Headers {
    lazy val OtoroshiVizFromLabel         = configuration.getOptional[String]("otoroshi.headers.trace.label").get
    lazy val OtoroshiVizFrom              = configuration.getOptional[String]("otoroshi.headers.trace.from").get
    lazy val OtoroshiGatewayParentRequest = configuration.getOptional[String]("otoroshi.headers.trace.parent").get
    lazy val OtoroshiAdminProfile         = configuration.getOptional[String]("otoroshi.headers.request.adminprofile").get
    lazy val OtoroshiClientId             = configuration.getOptional[String]("otoroshi.headers.request.clientid").get
    lazy val OtoroshiClientSecret         = configuration.getOptional[String]("otoroshi.headers.request.clientsecret").get
    lazy val OtoroshiRequestId            = configuration.getOptional[String]("otoroshi.headers.request.id").get
    lazy val OtoroshiRequestTimestamp     = configuration.getOptional[String]("otoroshi.headers.request.timestamp").get
    lazy val OtoroshiAuthorization        = configuration.getOptional[String]("otoroshi.headers.request.authorization").get
    lazy val OtoroshiProxiedHost          = configuration.getOptional[String]("otoroshi.headers.response.proxyhost").get
    lazy val OtoroshiGatewayError         = configuration.getOptional[String]("otoroshi.headers.response.error").get
    lazy val OtoroshiErrorMsg             = configuration.getOptional[String]("otoroshi.headers.response.errormsg").get
    lazy val OtoroshiProxyLatency         = configuration.getOptional[String]("otoroshi.headers.response.proxylatency").get
    lazy val OtoroshiUpstreamLatency =
      configuration.getOptional[String]("otoroshi.headers.response.upstreamlatency").get
    lazy val OtoroshiDailyCallsRemaining = configuration.getOptional[String]("otoroshi.headers.response.dailyquota").get
    lazy val OtoroshiMonthlyCallsRemaining =
      configuration.getOptional[String]("otoroshi.headers.response.monthlyquota").get
    lazy val OtoroshiState                = configuration.getOptional[String]("otoroshi.headers.comm.state").get
    lazy val OtoroshiStateResp            = configuration.getOptional[String]("otoroshi.headers.comm.stateresp").get
    lazy val OtoroshiClaim                = configuration.getOptional[String]("otoroshi.headers.comm.claim").get
    lazy val OtoroshiHealthCheckLogicTest = configuration.getOptional[String]("otoroshi.headers.healthcheck.test").get
    lazy val OtoroshiHealthCheckLogicTestResult =
      configuration.getOptional[String]("otoroshi.headers.healthcheck.testresult").get
    lazy val OtoroshiIssuer    = configuration.getOptional[String]("otoroshi.headers.jwt.issuer").get
    lazy val OtoroshiTrackerId = configuration.getOptional[String]("otoroshi.headers.canary.tracker").get
  }

  private def factory(of: String) = new ThreadFactory {
    val counter                                 = new AtomicInteger(0)
    override def newThread(r: Runnable): Thread = new Thread(r, s"$of-${counter.incrementAndGet()}")
  }

  logger.warn(s"Listening commands on $adminApiExposedHost ($port) for env ${env}")

  lazy val datastores: DataStores = {
    configuration.getOptional[String]("app.storage").getOrElse("redis") match {
      case "redis"     => new RedisDataStores(configuration, environment, lifecycle, this)
      case "inmemory"  => new InMemoryDataStores(configuration, environment, lifecycle, this)
      case "leveldb"   => new LevelDbDataStores(configuration, environment, lifecycle, this)
      case "cassandra" => new CassandraDataStores(configuration, environment, lifecycle, this)
      case e           => throw new RuntimeException(s"Bad storage value from conf: $e")
    }
  }

  if (useCache) logger.warn(s"Datastores will use cache to speed up operations")

  datastores.before(configuration, environment, lifecycle)
  lifecycle.addStopHook(() => {
    healthCheckerActor ! PoisonPill
    analyticsActor ! PoisonPill
    alertsActor ! PoisonPill
    internalActorSystem.terminate()
    // redisActorSystem.terminate()
    gatewayActorSystem.terminate()
    pressureActorSystem.terminate()
    kafkaActorSytem.terminate()
    statsdActorSytem.terminate()
    datastores.after(configuration, environment, lifecycle)
    FastFuture.successful(())
  })

  lazy val port =
    configuration
      .getOptional[Int]("play.server.http.port")
      .orElse(configuration.getOptional[Int]("http.port"))
      .getOrElse(9999)

  lazy val defaultConfig = GlobalConfig(
    perIpThrottlingQuota = 500,
    throttlingQuota = 100000
  )

  lazy val backOfficeGroup = ServiceGroup(
    id = backOfficeGroupId,
    name = "Otoroshi Admin Api group"
  )

  lazy val backOfficeApiKey = ApiKey(
    backOfficeApiKeyClientId,
    backOfficeApiKeyClientSecret,
    "Otoroshi Backoffice ApiKey",
    backOfficeGroupId
  )

  private lazy val backOfficeDescriptorHostHeader: String =
    if (isDev) s"$adminApiSubDomain.dev.$domain" else s"$adminApiSubDomain.$domain"

  lazy val backOfficeDescriptor = ServiceDescriptor(
    id = backOfficeServiceId,
    groupId = backOfficeGroupId,
    name = "otoroshi-admin-api",
    env = "prod",
    subdomain = adminApiExposedSubDomain,
    domain = domain,
    targets = Seq(
      Target(
        host = if (adminApiProxyUseLocal) s"127.0.0.1:$port" else s"$adminApiHost:$port",
        scheme = if (adminApiProxyHttps) "https" else "http"
      )
    ),
    redirectToLocal = isDev,
    localHost = s"127.0.0.1:$port",
    forceHttps = false,
    additionalHeaders = Map(
      "Host" -> backOfficeDescriptorHostHeader
    ),
    publicPatterns = Seq("/health")
  )

  lazy val otoroshiVersion     = "1.1.2"
  lazy val latestVersionHolder = new AtomicReference[JsValue](JsNull)
  lazy val checkForUpdates     = configuration.getOptional[Boolean]("app.checkForUpdates").getOrElse(true)

  timeout(300.millis).andThen {
    case _ =>
      implicit val ec = internalActorSystem.dispatcher

      datastores.globalConfigDataStore.isOtoroshiEmpty().andThen {
        case Success(true) => {
          logger.warn(s"The main datastore seems to be empty, registering some basic services")
          val login    = configuration.getOptional[String]("app.adminLogin").getOrElse("admin@otoroshi.io")
          val password = configuration.getOptional[String]("app.adminPassword").getOrElse(IdGenerator.token(32))
          val headers: Seq[(String, String)] = configuration
            .getOptional[Seq[String]]("app.importFromHeaders")
            .map(headers => headers.toSeq.map(h => h.split(":")).map(h => (h(0).trim, h(1).trim)))
            .getOrElse(Seq.empty[(String, String)])
          configuration.getOptional[String]("app.importFrom") match {
            case Some(url) if url.startsWith("http://") || url.startsWith("https://") => {
              logger.warn(s"Importing from URL: $url")
              wsClient.url(url).withHttpHeaders(headers: _*).get().fast.map { resp =>
                val json = resp.json.as[JsObject]
                datastores.globalConfigDataStore.fullImport(json)(ec, this)
              }
            }
            case Some(path) => {
              logger.warn(s"Importing from: $path")
              val source = Source.fromFile(path).getLines().mkString("\n")
              val json = Json.parse(source).as[JsObject]
              datastores.globalConfigDataStore.fullImport(json)(ec, this)
            }
            case _ => {
              val defaultGroup = ServiceGroup("default", "default-group", "The default service group")
              val defaultGroupApiKey = ApiKey("9HFCzZIPUQQvfxkq",
                "lmwAGwqtJJM7nOMGKwSAdOjC3CZExfYC7qXd4aPmmseaShkEccAnmpULvgnrt6tp",
                "default-apikey",
                "default")
              logger.warn(
                s"You can log into the Otoroshi admin console with the following credentials: $login / $password"
              )
              for {
                _ <- defaultConfig.save()(ec, this)
                _ <- backOfficeGroup.save()(ec, this)
                _ <- defaultGroup.save()(ec, this)
                _ <- backOfficeDescriptor.save()(ec, this)
                _ <- backOfficeApiKey.save()(ec, this)
                _ <- defaultGroupApiKey.save()(ec, this)
                _ <- datastores.simpleAdminDataStore
                  .registerUser(login, BCrypt.hashpw(password, BCrypt.gensalt()), "Otoroshi Admin", None)(ec, this)
              } yield ()
            }
          }
        }
      }.map { _ =>
        datastores.serviceDescriptorDataStore.findById(backOfficeServiceId)(ec, this).map {
          case Some(s) if !s.publicPatterns.contains("/health") =>
            logger.warn("Updating BackOffice service to handle health check ...")
            s.copy(publicPatterns = s.publicPatterns :+ "/health").save()(ec, this)
          case _ =>
        }
      }

      if (isProd && checkForUpdates) {
        internalActorSystem.scheduler.schedule(5.second, 24.hours) {
          datastores.globalConfigDataStore
            .singleton()(internalActorSystem.dispatcher, this)
            .map { globalConfig =>
              var cleanVersion: Double = otoroshiVersion.toLowerCase() match {
                case v if v.contains("-snapshot") =>
                  v.replace(".", "").replace("v", "").replace("-snapshot", "").toDouble - 0.5
                case v => v.replace(".", "").replace("v", "").replace("-snapshot", "").toDouble
              }
              wsClient
                .url("https://updates.otoroshi.io/api/versions/latest")
                .withRequestTimeout(10.seconds)
                .withHttpHeaders(
                  "Otoroshi-Version" -> otoroshiVersion,
                  "Otoroshi-Id"      -> globalConfig.otoroshiId
                )
                .get()
                .map { response =>
                  val body = response.json.as[JsObject]

                  val latestVersion      = (body \ "version_raw").as[String]
                  val latestVersionClean = (body \ "version_number").as[Double]
                  latestVersionHolder.set(
                    body ++ Json.obj(
                      "current_version_raw"    -> otoroshiVersion,
                      "current_version_number" -> cleanVersion,
                      "outdated"               -> (latestVersionClean > cleanVersion)
                    )
                  )
                  if (latestVersionClean > cleanVersion) {
                    logger.warn(
                      s"A new version of Otoroshi ($latestVersion, your version is $otoroshiVersion) is available. You can download it on https://maif.github.io/otoroshi/ or at https://github.com/MAIF/otoroshi/releases/tag/$latestVersion"
                    )
                  }
                }
            }
            .andThen {
              case Failure(e) => e.printStackTrace()
            }
        }
      }
      ()
  }(internalActorSystem.dispatcher)

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  lazy val sessionDomain = configuration.getOptional[String]("play.http.session.domain").get
  lazy val sessionMaxAge = configuration.getOptional[Int]("play.http.session.maxAge").getOrElse(86400)
  lazy val playSecret    = configuration.getOptional[String]("play.http.secret.key").get

  def sign(message: String): String =
    scala.util.Try {
      val mac = javax.crypto.Mac.getInstance("HmacSHA256")
      mac.init(new javax.crypto.spec.SecretKeySpec(playSecret.getBytes("utf-8"), "HmacSHA256"))
      org.apache.commons.codec.binary.Hex.encodeHexString(mac.doFinal(message.getBytes("utf-8")))
    } match {
      case scala.util.Success(s) => s
      case scala.util.Failure(e) => {
        logger.error(s"Error while signing: ${message}", e)
        throw e
      }
    }

  def extractPrivateSessionId(cookie: play.api.mvc.Cookie): Option[String] =
    cookie.value.split("::").toList match {
      case signature :: value :: Nil if sign(value) == signature => Some(value)
      case _                                                     => None
    }

  def signPrivateSessionId(id: String): String = {
    val signature = sign(id)
    s"$signature::$id"
  }

  def createPrivateSessionCookies(host: String, id: String): Seq[play.api.mvc.Cookie] =
    if (host.endsWith(sessionDomain)) {
      Seq(
        play.api.mvc.Cookie(
          name = "oto-papps",
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = "/",
          domain = Some(sessionDomain),
          httpOnly = false
        )
      )
    } else {
      Seq(
        play.api.mvc.Cookie(
          name = "oto-papps",
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = "/",
          domain = Some(host),
          httpOnly = false
        ),
        play.api.mvc.Cookie(
          name = "oto-papps",
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = "/",
          domain = Some(sessionDomain),
          httpOnly = false
        )
      )
    }

  def removePrivateSessionCookies(host: String): Seq[play.api.mvc.DiscardingCookie] =
    Seq(
      play.api.mvc.DiscardingCookie(
        name = "oto-papps",
        path = "/",
        domain = Some(host)
      ),
      play.api.mvc.DiscardingCookie(
        name = "oto-papps",
        path = "/",
        domain = Some(sessionDomain)
      )
    )
}
