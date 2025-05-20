package functional

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.api._
import otoroshi.next.plugins.{ApikeyCalls, NgApikeyCallsConfig, NgApikeyMatcher}
import otoroshi.security.IdGenerator
import play.api.Configuration
import play.api.libs.json.{JsObject, Json}
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{Result, Results}

import scala.util.{Failure, Success, Try}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext

class Version1413Spec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec {

  implicit val system   = ActorSystem("otoroshi-test")
  implicit lazy val env = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                       |{
                       |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  s"[$name] Otoroshi service descriptors" should {

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().futureValue // WARM UP
    }

    "support missing header (#364)" in {

      val counterBar = new AtomicInteger(0)
      val counterKix = new AtomicInteger(0)

      val (_, port1, _, call1) = testServer(
        "missingheaders.oto.tools",
        port,
        validate = req => {
          val header = req.getHeader("foo").get().value()
          if (header == "bar") {
            counterBar.incrementAndGet()
          }
          if (header == "kix") {
            counterKix.incrementAndGet()
          }
          true
        }
      )

      val service1 = ServiceDescriptor(
        id = "missingheaders",
        name = "missingheaders",
        env = "prod",
        subdomain = "missingheaders",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        missingOnlyHeadersIn = Map(
          "foo" -> "kix"
        )
      )

      createOtoroshiService(service1).futureValue

      val resp1 = call1(
        Map(
          "foo" -> "bar"
        )
      )

      val resp2 = call1(
        Map.empty
      )

      resp1.status mustBe 200
      resp2.status mustBe 200

      counterBar.get() mustBe 1
      counterKix.get() mustBe 1

      deleteOtoroshiService(service1).futureValue

      stopServers()
    }

    "support override header (#364)" in {

      val counterCanal02  = new AtomicInteger(0)
      val counterCanalBar = new AtomicInteger(0)

      val (_, port1, _, call) = testServer(
        "overrideheader.oto.tools",
        port,
        validate = req => {
          val header = req.getHeader("MAIF_CANAL").get().value()
          if (header == "02") {
            counterCanal02.incrementAndGet()
          }
          if (header == "bar") {
            counterCanalBar.incrementAndGet()
          }
          true
        }
      )

      val service1 = ServiceDescriptor(
        id = "overrideheader",
        name = "overrideheader",
        env = "prod",
        subdomain = "overrideheader",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        additionalHeaders = Map(
          "MAIF_CANAL" -> "02"
        )
      )

      createOtoroshiService(service1).futureValue

      val resp1 = call(
        Map(
          "MAIF_CANAL" -> "bar"
        )
      )

      val resp2 = call(
        Map.empty
      )

      resp1.status mustBe 200
      resp2.status mustBe 200

      counterCanal02.get() mustBe 2
      counterCanalBar.get() mustBe 0

      deleteOtoroshiService(service1).futureValue

      stopServers()
    }

    "support override header case insensitive (#364)" in {

      val counterCanal02  = new AtomicInteger(0)
      val counterCanalBar = new AtomicInteger(0)

      val (_, port1, _, call) = testServer(
        "overrideheader.oto.tools",
        port,
        validate = req => {
          val header = req.getHeader("MAIF_CANAL").get().value()
          if (header == "02") {
            counterCanal02.incrementAndGet()
          }
          if (header == "bar") {
            counterCanalBar.incrementAndGet()
          }
          true
        }
      )

      val service1 = ServiceDescriptor(
        id = "overrideheader",
        name = "overrideheader",
        env = "prod",
        subdomain = "overrideheader",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        additionalHeaders = Map(
          "MAIF_CANAL" -> "02"
        )
      )

      createOtoroshiService(service1).futureValue

      val resp1 = call(
        Map(
          "maif_canal" -> "bar"
        )
      )

      val resp2 = call(
        Map.empty
      )

      resp1.status mustBe 200
      resp2.status mustBe 200

      counterCanal02.get() mustBe 2
      counterCanalBar.get() mustBe 0

      deleteOtoroshiService(service1).futureValue

      stopServers()
    }

    "be able to validate access (#360)" in {
      val serviceHost                 = "accessvalidatorhost.oto.tools"
      val (_, port1, counter1, call1) = testServer("foo.oto.tools", TargetService.freePort)
      val route                       = NgRoute(
        location = EntityLocation.default,
        id = "accessvalidator",
        name = "accessvalidator",
        description = "accessvalidator",
        tags = Seq(),
        metadata = Map(),
        enabled = true,
        debugFlow = false,
        capture = false,
        exportReporting = false,
        frontend = NgFrontend(
          domains = Seq(NgDomainAndPath(serviceHost)),
          headers = Map(),
          cookies = Map(),
          query = Map(),
          methods = Seq(),
          stripPath = true,
          exact = false
        ),
        backend = NgBackend(
          targets = Seq(
            NgTarget(
              hostname = "127.0.0.1",
              port = port1,
              id = "accessvalidator-target",
              tls = false
            )
          ),
          root = "/",
          rewrite = false,
          loadBalancing = RoundRobin,
          client = NgClientConfig.default
        ),
        plugins = NgPlugins(
          Seq(
            NgPluginInstance(
              plugin = NgPluginHelper.pluginId[ApikeyCalls],
              config = NgPluginInstanceConfig(
                NgApikeyCallsConfig(
                  routing = NgApikeyMatcher(
                    enabled = true,
                    oneTagIn = Seq("foo")
                  )
                ).json.as[JsObject]
              )
            ),
            NgPluginInstance(
              plugin = "cp:functional.Validator1"
            )
          )
        )
      )

      createOtoroshiRoute(route).futureValue

      val validApiKey   = ApiKey(
        clientId = IdGenerator.token(16),
        clientSecret = IdGenerator.token(64),
        clientName = "apikey1",
        authorizedEntities = Seq(ServiceGroupIdentifier("default")),
        tags = Seq("foo", "bar")
      )
      val invalidApiKey = ApiKey(
        clientId = IdGenerator.token(16),
        clientSecret = IdGenerator.token(64),
        clientName = "apikey2",
        authorizedEntities = Seq(ServiceGroupIdentifier("default")),
        tags = Seq("kix")
      )

      createOtoroshiApiKey(validApiKey).futureValue
      createOtoroshiApiKey(invalidApiKey).futureValue

      TransformersCounters.counterValidator.get() mustBe 0

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Otoroshi-Client-Id"     -> validApiKey.clientId,
          "Otoroshi-Client-Secret" -> validApiKey.clientSecret,
          "Host"                   -> serviceHost
        )
        .get()
        .futureValue

      TransformersCounters.counterValidator.get() mustBe 1

      val resp2 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Otoroshi-Client-Id"     -> invalidApiKey.clientId,
          "Otoroshi-Client-Secret" -> invalidApiKey.clientSecret,
          "Host"                   -> serviceHost
        )
        .get()
        .futureValue

      TransformersCounters.counterValidator.get() mustBe 1

      resp1.status mustBe 200
      counter1.get() mustBe 1

      resp2.status mustBe 404
      counter1.get() mustBe 1

      deleteOtoroshiRoute(route).futureValue
      deleteOtoroshiApiKey(validApiKey).futureValue
      deleteOtoroshiApiKey(invalidApiKey).futureValue

      stopServers()
    }

    "be able to chain transformers (#366)" in {
      val serviceHost             = "reqtrans-frontend.oto.tools"
      val (_, port1, counter1, _) = testServer("reqtrans.oto.tools", port)
      val route                   = NgRoute(
        location = EntityLocation.default,
        id = "reqtrans",
        name = "reqtrans",
        description = "reqtrans",
        tags = Seq(),
        metadata = Map(),
        enabled = true,
        debugFlow = false,
        capture = false,
        exportReporting = false,
        frontend = NgFrontend(
          domains = Seq(NgDomainAndPath(serviceHost)),
          headers = Map(),
          cookies = Map(),
          query = Map(),
          methods = Seq(),
          stripPath = true,
          exact = false
        ),
        backend = NgBackend(
          targets = Seq(
            NgTarget(
              hostname = "127.0.0.1",
              port = port1,
              id = "reqtrans-target",
              tls = false
            )
          ),
          root = "/",
          rewrite = false,
          loadBalancing = RoundRobin,
          client = NgClientConfig.default
        ),
        plugins = NgPlugins(
          Seq(
            NgPluginInstance(
              plugin = "cp:functional.Transformer1"
            ),
            NgPluginInstance(
              plugin = "cp:functional.Transformer2"
            ),
            NgPluginInstance(
              plugin = "cp:functional.Transformer3"
            )
          )
        )
      )
      createOtoroshiRoute(route).futureValue

      TransformersCounters.counter.get() mustBe 0
      TransformersCounters.counter3.get() mustBe 0
      TransformersCounters.attrsCounter.get() mustBe 0
      counter1.get() mustBe 0

      val resp1 = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue
      //val resp1 = call1(Map.empty)

      TransformersCounters.counter.get() mustBe 3
      TransformersCounters.counter3.get() mustBe 1
      TransformersCounters.attrsCounter.get() mustBe 2
      counter1.get() mustBe 1
      resp1.status mustBe 200

      val resp2 = ws
        .url(s"http://127.0.0.1:${port}/hello")
        .withHttpHeaders("Host" -> serviceHost)
        .get()
        .futureValue

      TransformersCounters.counter.get() mustBe 7
      TransformersCounters.counter3.get() mustBe 1
      TransformersCounters.attrsCounter.get() mustBe 3
      counter1.get() mustBe 1
      resp2.status mustBe 201

      deleteOtoroshiRoute(route).futureValue

      stopServers()
    }

    "support DefaultToken strategy in JWT Verifiers (#373)" in {

      val algorithm = Algorithm.HMAC512("secret")

      val jwtVerifier = GlobalJwtVerifier(
        id = "jwtVerifier",
        name = "jwtVerifier",
        desc = "jwtVerifier",
        strict = true,
        source = InHeader(name = "X-JWT-Token"),
        algoSettings = HSAlgoSettings(512, "secret"),
        strategy = DefaultToken(
          true,
          Json.obj(
            "user"   -> "bobby",
            "rights" -> Json.arr(
              "admin"
            )
          )
        )
      )

      val jwtVerifier2 = GlobalJwtVerifier(
        id = "jwtVerifier2",
        name = "jwtVerifier2",
        desc = "jwtVerifier2",
        strict = true,
        source = InHeader(name = "X-JWT-Token"),
        algoSettings = HSAlgoSettings(512, "secret"),
        strategy = DefaultToken(
          false,
          Json.obj(
            "user"   -> "bobby",
            "rights" -> Json.arr(
              "admin"
            )
          )
        )
      )

      val (_, port1, counter1, call1) = testServer(
        "defaulttoken.oto.tools",
        port,
        validate = req => {
          val header = req.getHeader("X-JWT-Token").get().value()
          Try(JWT.require(algorithm).build().verify(header)) match {
            case Success(_) => true
            case Failure(_) => false
          }
        }
      )
      val (_, port2, counter2, call2) = testServer(
        "defaulttoken2.oto.tools",
        port,
        validate = req => {
          val maybeHeader = req.getHeader("X-JWT-Token")
          if (maybeHeader.isPresent) {
            Try(JWT.require(algorithm).build().verify(maybeHeader.get().value())) match {
              case Success(_) => true
              case Failure(_) => false
            }
          } else {
            true
          }
        }
      )

      val service1 = ServiceDescriptor(
        id = "defaulttoken",
        name = "defaulttoken",
        env = "prod",
        subdomain = "defaulttoken",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        jwtVerifier = RefJwtVerifier(
          enabled = true,
          ids = Seq("jwtVerifier")
        )
      )

      val service2 = ServiceDescriptor(
        id = "defaulttoken2",
        name = "defaulttoken2",
        env = "prod",
        subdomain = "defaulttoken2",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port2}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*"),
        jwtVerifier = RefJwtVerifier(
          enabled = true,
          ids = Seq("jwtVerifier2")
        )
      )

      createOtoroshiVerifier(jwtVerifier).futureValue
      createOtoroshiVerifier(jwtVerifier2).futureValue
      createOtoroshiService(service1).futureValue
      createOtoroshiService(service2).futureValue

      counter1.get() mustBe 0
      counter2.get() mustBe 0

      val resp1 = call1(
        Map.empty
      )

      val resp2 = call1(
        Map(
          "X-JWT-Token" -> JWT
            .create()
            .withIssuer("mathieu")
            .withClaim("bar", "yo")
            .sign(algorithm)
        )
      )

      resp1.status mustBe 200
      resp2.status mustBe 400
      counter1.get() mustBe 1

      val resp3 = call2(
        Map.empty
      )

      val resp4 = call2(
        Map(
          "X-JWT-Token" -> JWT
            .create()
            .withIssuer("mathieu")
            .withClaim("bar", "yo")
            .sign(algorithm)
        )
      )

      resp3.status mustBe 200
      resp4.status mustBe 200
      counter2.get() mustBe 2

      deleteOtoroshiService(service1).futureValue
      deleteOtoroshiService(service2).futureValue
      deleteOtoroshiVerifier(jwtVerifier).futureValue
      deleteOtoroshiVerifier(jwtVerifier2).futureValue

      stopServers()
    }

    "shutdown" in {
      stopAll()
    }
  }
}

object TransformersCounters {
  val attrsCounter     = new AtomicInteger(0)
  val counterValidator = new AtomicInteger(0)
  val counter          = new AtomicInteger(0)
  val counter3         = new AtomicInteger(0)
}

case class FakeUser(username: String)

object Attrs {
  val CurrentUserKey = TypedKey[FakeUser]("current-user")
}

class Transformer1 extends NgRequestTransformer {
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def isTransformRequestAsync                     = false

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    TransformersCounters.counter.incrementAndGet()
    ctx.attrs.put(Attrs.CurrentUserKey -> FakeUser("bobby"))

    Right(
      ctx.otoroshiRequest.copy(
        headers = ctx.otoroshiRequest.headers ++ Map(
          "foo" -> "bar"
        )
      )
    )
  }
}

class Transformer2 extends NgRequestTransformer {
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def isTransformRequestAsync                     = false
  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    TransformersCounters.counter.incrementAndGet()
    ctx.attrs.get(Attrs.CurrentUserKey) match {
      case Some(FakeUser("bobby")) => TransformersCounters.attrsCounter.incrementAndGet()
      case _                       =>
    }
    if (ctx.otoroshiRequest.headers.get("foo").contains("bar")) {
      TransformersCounters.counter.incrementAndGet()
    }
    if (ctx.otoroshiRequest.path == "/hello") {
      TransformersCounters.counter.incrementAndGet()
      Left(Results.Created(Json.obj("message" -> "hello world!")))
    } else {
      Right(ctx.otoroshiRequest)
    }
  }
}

class Transformer3 extends NgRequestTransformer {
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def isTransformRequestAsync                     = false

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    TransformersCounters.counter3.incrementAndGet()
    ctx.attrs.get(Attrs.CurrentUserKey) match {
      case Some(FakeUser("bobby")) => TransformersCounters.attrsCounter.incrementAndGet()
      case _                       =>
    }
    Right(ctx.otoroshiRequest)
  }
}

class Validator1 extends NgAccessValidator {
  override def isAccessAsync = false

  override def accessSync(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): NgAccess = {
    TransformersCounters.counterValidator.incrementAndGet()
    NgAccess.NgAllowed
  }

  override def multiInstance: Boolean = true

  override def defaultConfigObject: Option[NgPluginConfig] = None
}
