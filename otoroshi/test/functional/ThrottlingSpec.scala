package functional

package functional

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.{`Set-Cookie`, Host, HttpCookie, RawHeader}
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.{Materializer, ThrottleMode}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import ch.qos.logback.core.AppenderBase
import com.typesafe.config.ConfigFactory
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Minutes, Span}
import org.slf4j.LoggerFactory
import otoroshi.auth.{BasicAuthModuleConfig, BasicAuthUser, SessionCookieValues}
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.api.{NgPluginHelper, YesWebsocketBackend}
import otoroshi.next.plugins._
import otoroshi.plugins.hmac.HMACUtils
import otoroshi.security.IdGenerator
import otoroshi.utils.crypto.Signatures
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterJsValueReader, BetterSyntax}
import play.api.http.Status
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.{DefaultWSCookie, WSRequest}
import play.api.{Configuration, Logger}

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationDouble, DurationInt}
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success}

class ThrottlingTestSpec extends OtoroshiSpec with BeforeAndAfterAll {

  implicit lazy val mat = otoroshiComponents.materializer
  implicit lazy val env = otoroshiComponents.env

  def configurationSpec: Configuration = Configuration.empty

  implicit val system = ActorSystem("otoroshi-test")

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString("{}".stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  override def beforeAll(): Unit = {
    startOtoroshi()
    getOtoroshiRoutes().futureValue // WARM UP
  }

  override def afterAll(): Unit = {
    system.terminate()
    stopAll()
  }

  val DOMAIN = "quotas.oto.tools"

  s"throttling" should {
    def createLocalRoute() = {
      val target = TargetService
        .jsonFull(
          Some(DOMAIN),
          "/",
          r => (200, Json.obj(), List.empty)
        )
        .await()

      val newRoute = NgRoute(
        location = EntityLocation.default,
        id = s"route_${IdGenerator.uuid}",
        name = "local-route",
        description = "local-route",
        enabled = true,
        debugFlow = false,
        capture = false,
        exportReporting = false,
        frontend = NgFrontend(
          domains = Seq(NgDomainAndPath(DOMAIN)),
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
              port = target.port,
              id = "local.target",
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
              plugin = NgPluginHelper.pluginId[ApikeyCalls]
            )
          )
        ),
        tags = Seq.empty,
        metadata = Map.empty
      )

      val resp = createOtoroshiRoute(newRoute).futureValue

      if (resp._2 == Status.CREATED) {
        newRoute
      } else {
        throw new RuntimeException("failed to create a new local route")
      }
    }

    val apiKey = ApiKey(
      clientId = "1-apikey-throttling",
      clientSecret = "1234",
      clientName = "apikey-test",
      authorizedEntities = Seq(ServiceGroupIdentifier("default")),
      throttlingQuota = 30,
      dailyQuota = 1000000,
      monthlyQuota = 1000000
    )

    val basicApikey = Base64.getUrlEncoder.encodeToString(s"${apiKey.clientId}:${apiKey.clientSecret}".getBytes)

    def call(auth: String, route: NgRoute) = {
      ws.url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"                   -> route.frontend.domains.head.domain,
          "Otoroshi-Authorization" -> s"Basic $auth"
        )
        .get()
        .futureValue
    }

    "storage=file" in {
      val route = createLocalRoute()
      createOtoroshiApiKey(apiKey).futureValue

      val counter200    = new AtomicInteger(0)
      val counter429    = new AtomicInteger(0)
      val totalRequests = new AtomicInteger(0)

      val timestamps200 = new java.util.concurrent.ConcurrentLinkedQueue[Long]()

      val concurrency      = 29
      val queriesPerSecond = 29
      val duration         = 30.seconds

      println(s"- Throttling: $concurrency")
      println(s"- Duration: $duration")

      val resp2 = call(basicApikey, route)
      resp2.status mustBe Status.OK

      val startTime = System.currentTimeMillis()
      val endTime   = startTime + duration.toMillis

      val loadTestStream = Source
        .repeat(())
        .throttle(queriesPerSecond, 1.second, queriesPerSecond, ThrottleMode.Shaping)
        .takeWhile(_ => System.currentTimeMillis() < endTime)
        .mapAsync(concurrency) { _ =>
          Future {
            val resp  = call(basicApikey, route)
            val now   = System.currentTimeMillis()
            val total = totalRequests.incrementAndGet()

            if (resp.status == 200) {
              counter200.incrementAndGet()
              timestamps200.add(now)
            }
            if (resp.status != 200) counter429.incrementAndGet()

            val elapsed = (now - startTime) / 1000.0
            val rps     = total / elapsed
            println(
              f"Progress: $total requêtes - ${rps}%.2f req/s - 200: ${counter200.get()}, 429: ${counter429.get()}"
            )

            resp
          }
        }
        .runWith(Sink.ignore)

      Await.result(loadTestStream, duration + 10.seconds)

      val elapsed = (System.currentTimeMillis() - startTime) / 1000.0

      println("\n=== Results ===")
      println(f"Duration: ${elapsed}%.2f seconds")
      println(s"Requests: ${totalRequests.get()}")
      println(s"Success (200): ${counter200.get()}")
      println(s"Errors (429): ${counter429.get()}")
      println(f"Rate : ${totalRequests.get() / elapsed}%.2f req/s")

      val windowSize    = 10000L
      import scala.jdk.CollectionConverters._
      val allTimestamps = timestamps200.asScala.toArray.sorted

      var maxIn10s        = 0
      var violationsCount = 0
      val windows         = scala.collection.mutable.ArrayBuffer[(Long, Int)]()

      if (allTimestamps.nonEmpty) {
        var windowStart = startTime
        val endOfTest   = allTimestamps.last

        while (windowStart <= endOfTest) {
          val windowEnd     = windowStart + windowSize
          val countInWindow = allTimestamps.count(ts => ts >= windowStart && ts < windowEnd)

          windows += ((windowStart, countInWindow))

          if (countInWindow > maxIn10s) maxIn10s = countInWindow
          if (countInWindow > 30) {
            violationsCount += 1
            val windowNum = ((windowStart - startTime) / windowSize).toInt + 1
            println(
              f"  ⚠️  Window #$windowNum (${(windowStart - startTime) / 1000.0}%.1fs - ${(windowEnd - startTime) / 1000.0}%.1fs): $countInWindow requests 200 (> 30)"
            )
          }

          windowStart += windowSize
        }
      }

      println(s"\nRequest max 200 in window of 10s: $maxIn10s")
      println(s"Invalid window (> 30): $violationsCount")

      if (violationsCount == 0) {
        println("No window exceeded 30 req/10s")
      } else {
        println(s"$violationsCount window(s) have exceeded 30 req/10s")
      }

      maxIn10s must be <= 33

      deleteOtoroshiApiKey(apiKey)
      deleteOtoroshiRoute(route)
    }
  }
}
