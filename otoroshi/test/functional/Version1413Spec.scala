package functional

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.hash.Hashing
import com.typesafe.config.ConfigFactory
import env.Env
import models._
import org.joda.time.DateTime
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import otoroshi.script
import otoroshi.script._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{Result, Results}
import security.IdGenerator

import scala.concurrent.{ExecutionContext, Future}
import scala.math.BigDecimal.RoundingMode
import scala.util.Try

class Version1413Spec(name: String, configurationSpec: => Configuration)
  extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  implicit lazy val ws = otoroshiComponents.wsClient
  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import scala.concurrent.duration._

  override def getConfiguration(configuration: Configuration) = configuration ++ configurationSpec ++ Configuration(
    ConfigFactory
      .parseString(s"""
                      |{
                      |  http.port=$port
                      |  play.server.http.port=$port
                      |}
       """.stripMargin)
      .resolve()
  )

  s"[$name] Otoroshi service descriptors" should {

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    "support missing header (#364)" in {
      val counterBar = new AtomicInteger(0)
      val counterKix = new AtomicInteger(0)
      val (_, port1, _, call1) = testServer("missingheaders.oto.tools", port, validate = req => {
        val header = req.getHeader("foo").get().value()
        if (header == "bar") {
          counterBar.incrementAndGet()
        }
        if (header == "kix") {
          counterKix.incrementAndGet()
        }
        true
      })
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

    "be able to validate access (#360)" in {
      val (_, port1, counter1, call1) = testServer("accessvalidator.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "accessvalidator",
        name = "accessvalidator",
        env = "prod",
        subdomain = "accessvalidator",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"127.0.0.1:${port1}",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        accessValidator = AccessValidatorRef(
          enabled = true,
          refs = Seq(
            "cp:otoroshi.script.HasAllowedApiKeyValidator",
            "cp:cp:functional.Validator1"
          ),
          config = Json.obj(
            "tags" -> Json.arr("foo")
          )
        )
      )
      val validApiKey = ApiKey(
        clientName = "apikey1",
        authorizedGroup = "default",
        tags = Seq("foo", "bar")
      )
      val invalidApiKey = ApiKey(
        clientName = "apikey2",
        authorizedGroup = "default",
        tags = Seq("kix")
      )

      createOtoroshiService(service1).futureValue
      createOtoroshiApiKey(validApiKey).futureValue
      createOtoroshiApiKey(invalidApiKey).futureValue

      TransformersCounters.counterValidator.get() mustBe 0

      val resp1 = call1(
        Map(
          "Otoroshi-Client-Id" -> validApiKey.clientId,
          "Otoroshi-Client-Secret" -> validApiKey.clientSecret
        )
      )

      TransformersCounters.counterValidator.get() mustBe 1

      val resp2 = call1(
        Map(
          "Otoroshi-Client-Id" -> invalidApiKey.clientId,
          "Otoroshi-Client-Secret" -> invalidApiKey.clientSecret
        )
      )

      TransformersCounters.counterValidator.get() mustBe 1

      resp1.status mustBe 200
      counter1.get() mustBe 1

      resp2.status mustBe 400
      counter1.get() mustBe 1

      deleteOtoroshiService(service1).futureValue
      deleteOtoroshiApiKey(validApiKey).futureValue
      deleteOtoroshiApiKey(invalidApiKey).futureValue

      stopServers()
    }

    "be able to chain transformers (#366)" in {
      val (_, port1, counter1, call1) = testServer("reqtrans.oto.tools", port)
      val service1 = ServiceDescriptor(
        id = "reqtrans",
        name = "reqtrans",
        env = "prod",
        subdomain = "reqtrans",
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
        transformerRefs = Seq(
          "cp:functional.Transformer1",
          "cp:functional.Transformer2",
          "cp:functional.Transformer3"
        )
      )
      createOtoroshiService(service1).futureValue

      TransformersCounters.counter.get() mustBe 0
      TransformersCounters.counter3.get() mustBe 0
      TransformersCounters.attrsCounter.get() mustBe 0
      counter1.get() mustBe 0

      val resp1 = call1(Map.empty)

      TransformersCounters.counter.get() mustBe 3
      TransformersCounters.counter3.get() mustBe 1
      TransformersCounters.attrsCounter.get() mustBe 2
      counter1.get() mustBe 1
      resp1.status mustBe 200


      val resp2 = ws.url(s"http://127.0.0.1:${port}/hello")
        .withHttpHeaders("Host" -> "reqtrans.oto.tools")
        .get()
        .futureValue

      TransformersCounters.counter.get() mustBe 7
      TransformersCounters.counter3.get() mustBe 1
      TransformersCounters.attrsCounter.get() mustBe 3
      counter1.get() mustBe 1
      resp2.status mustBe 201

      deleteOtoroshiService(service1).futureValue

      stopServers()
    }
  }
}

object TransformersCounters {
  val attrsCounter = new AtomicInteger(0)
  val counterValidator = new AtomicInteger(0)
  val counter = new AtomicInteger(0)
  val counter3 = new AtomicInteger(0)
}

case class FakeUser(username: String)

object Attrs {
  val CurrentUserKey = TypedKey[FakeUser]("current-user")
}

class Transformer1 extends RequestTransformer {
  override def transformRequestWithCtx(context: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, script.HttpRequest]] = {
    TransformersCounters.counter.incrementAndGet()
    context.attrs.put(Attrs.CurrentUserKey -> FakeUser("bobby"))
    FastFuture.successful(Right(context.otoroshiRequest.copy(headers = context.otoroshiRequest.headers ++ Map(
      "foo" -> "bar"
    ))))
  }
}

class Transformer2 extends RequestTransformer {
  override def transformRequestWithCtx(context: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, script.HttpRequest]] = {
    TransformersCounters.counter.incrementAndGet()
    context.attrs.get(Attrs.CurrentUserKey) match {
      case Some(FakeUser("bobby")) => TransformersCounters.attrsCounter.incrementAndGet()
      case None =>
    }
    if (context.otoroshiRequest.headers.get("foo").contains("bar")) {
      TransformersCounters.counter.incrementAndGet()
    }
    if (context.otoroshiRequest.path == "/hello") {
      TransformersCounters.counter.incrementAndGet()
      FastFuture.successful(Left(Results.Created(Json.obj("message" -> "hello world!"))))
    } else {
      FastFuture.successful(Right(context.otoroshiRequest))
    }
  }
}

class Transformer3 extends RequestTransformer {
  override def transformRequestWithCtx(context: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, script.HttpRequest]] = {
    TransformersCounters.counter3.incrementAndGet()
    context.attrs.get(Attrs.CurrentUserKey) match {
      case Some(FakeUser("bobby")) => TransformersCounters.attrsCounter.incrementAndGet()
      case _ =>
    }
    FastFuture.successful(Right(context.otoroshiRequest))
  }
}

class Validator1 extends AccessValidator {
  override def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    TransformersCounters.counterValidator.incrementAndGet()
    FastFuture.successful(true)
  }
}