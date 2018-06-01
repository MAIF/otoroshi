package functional

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Host, RawHeader}
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import models.{ApiKey, ServiceDescriptor, Target}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import otoroshi.api.Otoroshi
import play.api.Configuration
import play.core.server.ServerConfig

import scala.concurrent.Future
import scala.concurrent.duration._

class WebsocketSpec(name: String, configurationSpec: => Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

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

  s"[$name] Otoroshi" should {

    "support websockets" in {

      implicit val system = ActorSystem("otoroshi-test")
      implicit val mat    = ActorMaterializer.create(system)
      implicit val http   = Http()(system)

      val service = ServiceDescriptor(
        id = "ws-test",
        name = "ws-test",
        env = "prod",
        subdomain = "ws",
        domain = "foo.bar",
        targets = Seq(
          Target(
            host = s"echo.websocket.org",
            scheme = "http"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*")
      )

      val clientCounter = new AtomicInteger(0)

      val otoroshi = Otoroshi(
        ServerConfig(
          address = "0.0.0.0",
          port = Some(8888)
        )
      ).startAndStopOnShutdown()

      implicit val env = otoroshi.env

      getOtoroshiServices(Some(8888), otoroshi.ws).futureValue // WARM UP

      createOtoroshiService(service, Some(8888), otoroshi.ws).futureValue

      val printSink: Sink[Message, Future[Done]] = Sink.foreach { message =>
        clientCounter.incrementAndGet()
        println("client received: " + message.asScala.asTextMessage.getStrictText)
      }

      val nameSource: Source[Message, NotUsed] =
        Source
          .fromFuture(awaitF(1.second).map(_ => TextMessage("yo")))
          .concat(
            Source(
              List(
                TextMessage("mathieu"),
                TextMessage("alex"),
                TextMessage("chris"),
                TextMessage("francois"),
                TextMessage("aurelie"),
                TextMessage("loic"),
                TextMessage("pierre"),
                TextMessage("emmanuel"),
                TextMessage("frederic")
              )
            )
          )

      http.singleWebSocketRequest(
        WebSocketRequest(s"ws://127.0.0.1:$port")
          .copy(extraHeaders = List(Host("ws.foo.bar"))),
        Flow
          .fromSinkAndSourceMat(printSink, nameSource)(Keep.both)
          .alsoTo(Sink.onComplete { _ =>
            println(s"[WEBSOCKET] client flow stopped")
          })
      )

      awaitF(2.seconds).futureValue

      clientCounter.get mustBe 9

      otoroshi.stop()
      system.terminate()
    }
  }
}
