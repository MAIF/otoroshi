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
import play.api.Configuration

import scala.concurrent.Future
import scala.concurrent.duration._

class WebsocketSpec(name: String, configurationSpec: => Configuration)
  extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "ws.foo.bar"
  lazy val ws = otoroshiComponents.wsClient
  implicit val system = ActorSystem("otoroshi-test")
  implicit val mat = ActorMaterializer.create(system)

  override def getConfiguration(configuration: Configuration) = configuration ++ configurationSpec ++ Configuration(
    ConfigFactory
      .parseString(s"""
                      |{
                      |  http.port=$port
                      |  play.server.http.port=$port
                      |}
       """.stripMargin).resolve()
  )

  s"[$name] Otoroshi" should {

    val counter = new AtomicInteger(0)
    val server = new WebsocketServer(counter).await()
    val service = ServiceDescriptor(
      id = "ws-test",
      name = "ws-test",
      env = "prod",
      subdomain = "ws",
      domain = "foo.bar",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${server.port}",
          scheme = "http"
        )
      ),
      forceHttps = false,
      enforceSecureCommunication = false,
      publicPatterns = Seq("/.*")
    )
    
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    "support websockets" in {
      createOtoroshiService(service).futureValue

      val printSink: Sink[Message, Future[Done]] =
        Sink.foreach { message =>
          println("client received: " + message.asScala.asTextMessage.getStrictText)
        }

      val nameSource: Source[Message, NotUsed] =
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

      val flow: Flow[Message, Message, Future[Done]] =
        Flow.fromSinkAndSourceMat(printSink, nameSource)(Keep.left)

      val (upgradeResponse, closed) =
      Http().singleWebSocketRequest(WebSocketRequest(s"ws://127.0.0.1:$port/ws").copy(extraHeaders = List(Host("ws.foo.bar"))), flow)

      val connected = upgradeResponse.map { upgrade =>
        if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
          Done
        } else {
          val body = upgrade.response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).futureValue.utf8String
          throw new RuntimeException(s"Connection failed: ${upgrade.response.status} :: $body")
        }
      }


      connected.onComplete(println)

      await(10.seconds)
      closed.foreach(_ => println("closed"))

      counter.get mustBe 9

      deleteOtoroshiService(service).futureValue
    }

    "stop servers" in {
      server.stop()
      system.terminate()
    }
  }
}