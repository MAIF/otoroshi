package plugins

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import functional.PluginsTestSpec
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Minutes, Span}
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.{NgPluginHelper, YesWebsocketBackend}
import otoroshi.security.IdGenerator

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Future, Promise}

class YesWebsocketPluginTests(parent: PluginsTestSpec) {
  import parent._

  def sendYMessagesPeriodicallyToWebsocketClients() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[YesWebsocketBackend]
        )
      ),
      id = IdGenerator.uuid
    )

    implicit val system: ActorSystem = ActorSystem("otoroshi-test")
    implicit val mat: Materializer   = Materializer(system)
    implicit val http: HttpExt       = Http()(system)

    val yesCounter      = new AtomicInteger(0)
    val messagesPromise = Promise[Int]()

    val printSink: Sink[Message, Future[Done]] = Sink.foreach { message =>
      yesCounter.incrementAndGet()

      if (yesCounter.get() == 3)
        messagesPromise.trySuccess(yesCounter.get)
    }

    val clientSource: Source[Message, Promise[Option[Message]]] = Source.maybe[Message]

    val (_, _) = http.singleWebSocketRequest(
      WebSocketRequest(s"ws://127.0.0.1:$port/api")
        .copy(extraHeaders = List(Host(route.frontend.domains.head.domainLowerCase))),
      Flow
        .fromSinkAndSourceMat(printSink, clientSource)(Keep.both)
        .alsoTo(Sink.onComplete { _ => })
    )

    val yesMessagesCounter = messagesPromise.future.futureValue(Timeout(Span(1, Minutes)))
    yesMessagesCounter >= 3 mustBe true

    deleteOtoroshiRoute(route).futureValue
  }

  def rejectConnectionWithFailYesQueryParameter() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[YesWebsocketBackend]
        )
      ),
      id = IdGenerator.uuid
    )

    implicit val system: ActorSystem = ActorSystem("otoroshi-test")
    implicit val mat: Materializer   = Materializer(system)
    implicit val http: HttpExt       = Http()(system)

    val printSink: Sink[Message, Future[Done]] = Sink.foreach { _ => }

    val clientSource: Source[Message, Promise[Option[Message]]] = Source.maybe[Message]

    val (upgradeResponse, _) = http.singleWebSocketRequest(
      WebSocketRequest(s"ws://127.0.0.1:$port/api?fail=yes")
        .copy(extraHeaders = List(Host(route.frontend.domains.head.domainLowerCase))),
      Flow
        .fromSinkAndSourceMat(printSink, clientSource)(Keep.both)
        .alsoTo(Sink.onComplete { _ => })
    )

    upgradeResponse.futureValue.response.status.intValue() mustBe 500

    deleteOtoroshiRoute(route).futureValue
  }
}
