package plugins

import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.{Done, NotUsed}
import functional.{PluginsTestSpec, WebsocketBackend}
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Seconds, Span}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgTarget}
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.libs.json._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}

class WebsocketSizeValidatorTests(parent: PluginsTestSpec) {
  import parent._

  implicit val http: HttpExt = Http()(system)

  val backend = new WebsocketBackend().await()

  val route = createLocalRoute(
    frontendPath = "/",
    plugins = Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[WebsocketSizeValidator],
        config = NgPluginInstanceConfig(
          WebsocketSizeValidatorConfig(
            clientMaxPayload = 10,
            upstreamMaxPayload = 20,
            rejectStrategy = RejectStrategy.Drop
          ).json.as[JsObject]
        )
      )
    ),
    target = NgTarget(
      hostname = "127.0.0.1",
      port = backend.backendPort,
      id = "local.target",
      tls = false
    ).some
  )

  val messagesPromise = Promise[Int]()
  val counter         = new AtomicInteger(0)

  val printSink: Sink[Message, Future[Done]] = Sink.foreach { message =>
    counter.incrementAndGet()
    if (counter.get == 4)
      messagesPromise.trySuccess(counter.get)
    else if (counter.get > 4)
      messagesPromise.tryFailure(new RuntimeException("Failure, but lost track of exception :-("))
  }

  val messages = List(
    TextMessage("name"),
    TextMessage("name"),
    TextMessage(Json.obj("foo" -> "barbarbarbar").stringify),
    TextMessage(Json.obj("foo" -> "barbarbarbarbar").stringify),
    TextMessage("nothing"),
    TextMessage("nothing"),
    TextMessage(Json.obj("foo" -> "barbarbarbarbar").stringify)
  )

  val clientSource: Source[TextMessage, NotUsed] = Source(messages)
    .throttle(1, 200.millis)

  val (_, _) = http.singleWebSocketRequest(
    WebSocketRequest(s"ws://127.0.0.1:$port/")
      .copy(extraHeaders = List(Host(route.frontend.domains.head.domainLowerCase))),
    Flow
      .fromSinkAndSourceMat(printSink, clientSource)(Keep.both)
      .watchTermination()(Keep.both)
  )

  val yesMessagesCounter = messagesPromise.future.futureValue(Timeout(Span(25, Seconds)))
  yesMessagesCounter mustBe 4

  backend.await()
  http.shutdownAllConnectionPools()
  deleteOtoroshiRoute(route).futureValue
}
