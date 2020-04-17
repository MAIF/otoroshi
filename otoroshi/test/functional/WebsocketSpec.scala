package functional

import java.util.concurrent.atomic.AtomicInteger

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.typesafe.config.ConfigFactory
import models.{ServiceDescriptor, Target}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

import scala.concurrent.Future
import scala.concurrent.duration._

class WebsocketSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec {

  lazy val serviceHost = "websocket.oto.tools"

  override def getTestConfiguration(configuration: Configuration) = Configuration(
    ConfigFactory
      .parseString(s"""
                      |{
                      |}
       """.stripMargin)
      .resolve()
  ).withFallback(configurationSpec).withFallback(configuration)

  s"[$name] Otoroshi" should {

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().futureValue // WARM UP
    }

    "support websockets" in {

      implicit val system = ActorSystem("otoroshi-test")
      implicit val mat    = Materializer(system)
      implicit val http   = Http()(system)

      val service = ServiceDescriptor(
        id = "ws-test",
        name = "ws-test",
        env = "prod",
        subdomain = "ws",
        domain = "oto.tools",
        targets = Seq(
          Target(
            host = s"echo.websocket.org",
            scheme = "https"
          )
        ),
        forceHttps = false,
        enforceSecureCommunication = false,
        publicPatterns = Seq("/.*")
      )

      val clientCounter = new AtomicInteger(0)

      createOtoroshiService(service).futureValue

      val printSink: Sink[Message, Future[Done]] = Sink.foreach { message =>
        clientCounter.incrementAndGet()
        // println("client received: " + message.asScala.asTextMessage.getStrictText)
      }

      val names = List(
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

      val nameSource: Source[Message, NotUsed] =
        Source
          .future(awaitF(1.second).map(_ => TextMessage("yo")))
          .concat(
            Source.tick(1.second, 300.millis, ()).take(names.size).zipWith(Source(names))((_, b) => b)
          )

      http.singleWebSocketRequest(
        WebSocketRequest(s"ws://127.0.0.1:$port")
          .copy(extraHeaders = List(Host("ws.oto.tools"))),
        Flow
          .fromSinkAndSourceMat(printSink, nameSource)(Keep.both)
          .alsoTo(Sink.onComplete { _ =>
            println(s"[WEBSOCKET] client flow stopped")
          })
      )

      awaitF(10.seconds).futureValue

      clientCounter.get mustBe 9

      deleteOtoroshiService(service)

      system.terminate()
    }

    "shutdown" in {
      stopAll()
    }
  }
}

