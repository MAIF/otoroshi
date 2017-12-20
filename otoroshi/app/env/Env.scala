package env

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory, TimeUnit}

import akka.actor.{ActorSystem, PoisonPill}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.io.IO
import akka.pattern.ask
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import dns.DnsHandlerActor
import events._
import gateway.CircuitBreakersHolder
import health.{HealthCheckerActor, StartHealthCheck}
import models._
import play.api._
import play.api.libs.ws._
import play.api.libs.ws.ahc._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsObject, Json}
import security.{Auth0Config, ClaimCrypto, IdGenerator}
import storage.DataStores
import storage.cassandra.CassandraDataStores
import storage.inmemory.InMemoryDataStores
import storage.leveldb.LevelDbDataStores
import storage.redis.RedisDataStores
import org.mindrot.jbcrypt.BCrypt

import scala.collection.JavaConversions._
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.io.Source
import scala.util.Success

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
      configuration.getConfig("app.actorsystems.internal").map(_.underlying).getOrElse(ConfigFactory.empty)
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
    configuration.getConfig("app.actorsystems.websockets").map(_.underlying).getOrElse(ConfigFactory.empty)
  )

  lazy val maxWebhookSize: Int = configuration.getInt("app.webhooks.size").getOrElse(100)

  //lazy val middleFingers: Boolean = configuration.getBoolean("app.middleFingers").getOrElse(false)
  //lazy val maxLocalLogsSize: Int = configuration.getInt("app.events.maxSize").getOrElse(1000)

  lazy val useRedisScan: Boolean          = configuration.getBoolean("app.redis.useScan").getOrElse(false)
  lazy val commitId: String               = configuration.getString("app.commitId").getOrElse("HEAD")
  lazy val secret: String                 = configuration.getString("play.crypto.secret").get
  lazy val sharedKey: String              = configuration.getString("app.claim.sharedKey").get
  lazy val env: String                    = configuration.getString("app.env").getOrElse("prod")
  lazy val adminApiProxyHttps: Boolean    = configuration.getBoolean("app.adminapi.proxy.https").getOrElse(false)
  lazy val adminApiProxyUseLocal: Boolean = configuration.getBoolean("app.adminapi.proxy.local").getOrElse(true)
  lazy val redirectToDev: Boolean = env
    .toLowerCase() == "dev" && configuration.getBoolean("app.redirectToDev").getOrElse(false)
  lazy val envInUrl: String = configuration.getString("app.env").filterNot(_ == "prod").map(v => s"$v.").getOrElse("")
  lazy val domain: String   = configuration.getString("app.domain").getOrElse("foo.bar")
  lazy val adminApiSubDomain: String =
    configuration.getString("app.adminapi.targetSubdomain").getOrElse("otoroshi-admin-internal-api")
  lazy val adminApiExposedSubDomain: String =
    configuration.getString("app.adminapi.exposedDubdomain").getOrElse("otoroshi-api")
  lazy val backOfficeSubDomain: String  = configuration.getString("app.backoffice.subdomain").getOrElse("otoroshi")
  lazy val privateAppsSubDomain: String = configuration.getString("app.privateapps.subdomain").getOrElse("privateapps")
  lazy val retries: Int                 = configuration.getInt("app.retries").getOrElse(5)

  lazy val backOfficeServiceId      = configuration.getString("app.adminapi.defaultValues.backOfficeServiceId").get
  lazy val backOfficeGroupId        = configuration.getString("app.adminapi.defaultValues.backOfficeGroupId").get
  lazy val backOfficeApiKeyClientId = configuration.getString("app.adminapi.defaultValues.backOfficeApiKeyClientId").get
  lazy val backOfficeApiKeyClientSecret =
    configuration.getString("app.adminapi.defaultValues.backOfficeApiKeyClientSecret").get

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
      Executors.newFixedThreadPool(procNbr + 1, factory("otoroshi-opun-requests"))
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

  lazy val gatewayActorSystem = ActorSystem(
    "otoroshi-gateway-system",
    configuration.getConfig("app.actorsystems.gateway").map(_.underlying).getOrElse(ConfigFactory.empty)
  )

  lazy val gatewayExecutor     = gatewayActorSystem.dispatcher
  lazy val gatewayMaterializer = ActorMaterializer.create(gatewayActorSystem)

  lazy val gatewayClient = {
    val parser  = new WSConfigParser(configuration, environment)
    val config  = new AhcWSClientConfig(wsClientConfig = parser.parse())
    val builder = new AhcConfigBuilder(config)
    val gwWsClient = AhcWSClient(
      builder
        .configure()
        //.setKeepAlive(true)
        //.setHttpClientCodecMaxChunkSize(200000)
        .build()
    )(gatewayMaterializer)
    wsClient
    //gwWsClient
  }

  lazy val kafkaActorSytem = ActorSystem(
    "otoroshi-kafka-system",
    configuration.getConfig("app.actorsystems.kafka").map(_.underlying).getOrElse(ConfigFactory.empty)
  )
  lazy val statsdActorSytem = ActorSystem(
    "otoroshi-statsd-system",
    configuration.getConfig("app.actorsystems.statsd").map(_.underlying).getOrElse(ConfigFactory.empty)
  )

  lazy val statsd = new StatsdWrapper(statsdActorSytem, this)

  lazy val mode   = environment.mode
  lazy val isDev  = mode == Mode.Dev
  lazy val isProd = !isDev
  lazy val notDev = !isDev
  lazy val hash   = s"${System.currentTimeMillis()}"

  lazy val privateAppsSessionExp = configuration.getLong("app.privateapps.session.exp").get
  lazy val backOfficeSessionExp  = configuration.getLong("app.backoffice.session.exp").get

  lazy val exposedRootScheme = configuration.getString("app.rootScheme").getOrElse("https")

  def rootScheme               = if (isDev) "http://" else s"${exposedRootScheme}://"
  def exposedRootSchemeIsHttps = exposedRootScheme == "https"

  def Ws = wsClient

  lazy val snowflakeSeed      = configuration.getLong("app.snowflake.seed").get
  lazy val snowflakeGenerator = IdGenerator(snowflakeSeed)
  lazy val redirections: Seq[String] =
    configuration.getStringList("app.redirections").map(_.toSeq).getOrElse(Seq.empty[String])

  lazy val crypto = ClaimCrypto(sharedKey)

  object Headers {
    lazy val OpunVizFromLabel               = configuration.getString("otoroshi.headers.trace.label").get
    lazy val OpunVizFrom                    = configuration.getString("otoroshi.headers.trace.from").get
    lazy val OpunGatewayParentRequest       = configuration.getString("otoroshi.headers.trace.parent").get
    lazy val OpunAdminProfile               = configuration.getString("otoroshi.headers.request.adminprofile").get
    lazy val OpunClientId                   = configuration.getString("otoroshi.headers.request.clientid").get
    lazy val OpunClientSecret               = configuration.getString("otoroshi.headers.request.clientsecret").get
    lazy val OpunGatewayRequestId           = configuration.getString("otoroshi.headers.request.id").get
    lazy val OpunProxiedHost                = configuration.getString("otoroshi.headers.response.proxyhost").get
    lazy val OpunGatewayError               = configuration.getString("otoroshi.headers.response.error").get
    lazy val OpunGatewayErrorMsg            = configuration.getString("otoroshi.headers.response.errormsg").get
    lazy val OpunGatewayProxyLatency        = configuration.getString("otoroshi.headers.response.proxylatency").get
    lazy val OpunGatewayUpstreamLatency     = configuration.getString("otoroshi.headers.response.upstreamlatency").get
    lazy val OpunDailyCallsRemaining        = configuration.getString("otoroshi.headers.response.dailyquota").get
    lazy val OpunMonthlyCallsRemaining      = configuration.getString("otoroshi.headers.response.monthlyquota").get
    lazy val OpunGatewayState               = configuration.getString("otoroshi.headers.comm.state").get
    lazy val OpunGatewayStateResp           = configuration.getString("otoroshi.headers.comm.stateresp").get
    lazy val OpunGatewayClaim               = configuration.getString("otoroshi.headers.comm.claim").get
    lazy val OpunHealthCheckLogicTest       = configuration.getString("otoroshi.headers.healthcheck.test").get
    lazy val OpunHealthCheckLogicTestResult = configuration.getString("otoroshi.headers.healthcheck.testresult").get
    lazy val OpunGateway                    = configuration.getString("otoroshi.headers.jwt.issuer").get
    lazy val OpunTrackerId                  = configuration.getString("otoroshi.headers.canary.tracker").get
  }

  private def factory(of: String) = new ThreadFactory {
    val counter                                 = new AtomicInteger(0)
    override def newThread(r: Runnable): Thread = new Thread(r, s"$of-${counter.incrementAndGet()}")
  }

  lazy val datastores: DataStores = {
    configuration.getString("app.storage").getOrElse("redis") match {
      case "redis"     => new RedisDataStores(configuration, environment, lifecycle)
      case "inmemory"  => new InMemoryDataStores(configuration, environment, lifecycle)
      case "leveldb"   => new LevelDbDataStores(configuration, environment, lifecycle)
      case "cassandra" => new CassandraDataStores(configuration, environment, lifecycle)
      case e           => throw new RuntimeException(s"Bad storage value from conf: $e")
    }
  }

  datastores.before(configuration, environment, lifecycle)
  lifecycle.addStopHook(() => {
    healthCheckerActor ! PoisonPill
    analyticsActor ! PoisonPill
    alertsActor ! PoisonPill
    internalActorSystem.terminate()
    // redisActorSystem.terminate()
    gatewayActorSystem.terminate()
    kafkaActorSytem.terminate()
    statsdActorSytem.terminate()
    datastores.after(configuration, environment, lifecycle)
    FastFuture.successful(())
  })

  {
    if (env.toLowerCase == "dev") {
      // https://github.com/mkroli/dns4s/blob/master/dsl.md
      import akka.util.Timeout
      import com.github.mkroli.dns4s.akka.Dns
      implicit val system  = ActorSystem("DnsServer")
      implicit val ec      = system.dispatcher
      implicit val timeout = Timeout(5 seconds)
      val props            = DnsHandlerActor.props(this)
      IO(Dns) ? Dns.Bind(system.actorOf(props), configuration.getInt("app.dnsport").getOrElse(5354))
      lifecycle.addStopHook(() => {
        system.terminate().map(_ => ())
      })
    }
  }

  lazy val port =
    configuration.getInt("play.server.http.port").orElse(configuration.getInt("http.port")).getOrElse(9999)

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
        host = s"127.0.0.1:$port",
        scheme = exposedRootScheme
      )
    ),
    redirectToLocal = isDev,
    localHost = s"127.0.0.1:$port",
    forceHttps = false,
    additionalHeaders = Map(
      "Host" -> backOfficeDescriptorHostHeader
    )
  )

  timeout(300.millis).andThen {
    case _ =>
      implicit val ec = internalActorSystem.dispatcher

      import collection.JavaConversions._

      datastores.globalConfigDataStore.isOtoroshiEmpty().andThen {
        case Success(true) => {
          logger.warn(s"The main datastore seems to be empty, registering some basic services")
          val password = IdGenerator.token(32)
          val headers: Seq[(String, String)] = configuration
            .getStringList("app.importFromHeaders")
            .map(headers => headers.toSeq.map(h => h.split(":")).map(h => (h(0).trim, h(1).trim)))
            .getOrElse(Seq.empty[(String, String)])
          configuration.getString("app.importFrom") match {
            case Some(url) if url.startsWith("http://") || url.startsWith("https://") => {
              logger.warn(s"Importing from URL: $url")
              wsClient.url(url).withHeaders(headers: _*).get().fast.map { resp =>
                val json = resp.json.as[JsObject]
                datastores.globalConfigDataStore.fullImport(json)(ec, this)
              }
            }
            case Some(path) => {
              logger.warn(s"Importing from: $path")
              val source = Source.fromFile(path).getLines().mkString("\n")
              val json   = Json.parse(source).as[JsObject]
              datastores.globalConfigDataStore.fullImport(json)(ec, this)
            }
            case _ => {
              val defaultGroup = ServiceGroup("default", "default-group", "The default service group")
              val defaultGroupApiKey = ApiKey("9HFCzZIPUQQvfxkq",
                                              "lmwAGwqtJJM7nOMGKwSAdOjC3CZExfYC7qXd4aPmmseaShkEccAnmpULvgnrt6tp",
                                              "default-apikey",
                                              "default")
              logger.warn(
                s"You can log into the Otoroshi admin console with the following credentials: admin@otoroshi.io / $password"
              )
              for {
                _ <- defaultConfig.save()(ec, this)
                _ <- backOfficeGroup.save()(ec, this)
                _ <- defaultGroup.save()(ec, this)
                _ <- backOfficeDescriptor.save()(ec, this)
                _ <- backOfficeApiKey.save()(ec, this)
                _ <- defaultGroupApiKey.save()(ec, this)
                _ <- datastores.simpleAdminDataStore.registerUser("admin@otoroshi.io",
                                                                  BCrypt.hashpw(password, BCrypt.gensalt()),
                                                                  "Otoroshi Admin")(ec, this)
              } yield ()
            }
          }
        }
      }
      ()
  }(internalActorSystem.dispatcher)

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  lazy val sessionDomain = configuration.getString("play.http.session.domain").get
  lazy val sessionMaxAge = configuration.getInt("play.http.session.maxAge").getOrElse(86400)
  lazy val playSecret    = configuration.getString("play.crypto.secret").get

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
