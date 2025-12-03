package plugins

import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.{Done, NotUsed}
import functional.{PluginsTestSpec, WebsocketBackend}
import io.otoroshi.wasm4s.scaladsl.{WasmSource, WasmSourceKind}
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Minutes, Seconds, Span}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgTarget}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  FrameFormatValidatorConfig,
  OverrideHost,
  RejectStrategy,
  WasmWebsocketTransformer,
  WebsocketContentValidatorIn
}
import otoroshi.utils.JsonPathValidator
import otoroshi.utils.syntax.implicits.BetterSyntax
import otoroshi.wasm.WasmConfig
import play.api.libs.json._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}

class WasmWebsocketTransformerTests(parent: PluginsTestSpec) {
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
        plugin = NgPluginHelper.pluginId[WasmWebsocketTransformer],
        config = NgPluginInstanceConfig(
          WasmConfig(
            source =
              WasmSource(WasmSourceKind.File, "./test/resources/wasm/websocket-transformer-1.0.0-dev.wasm", Json.obj()),
            config = Map.empty,
            wasi = true,
            allowedHosts = Seq.empty,
            allowedPaths = Map.empty
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
    if (counter.get == 5) {
      message.asScala.asTextMessage.getStrictText.startsWith("[RESPONSE]") mustBe true
      messagesPromise.trySuccess(counter.get)
    }
  }

  val messages = List(
    TextMessage("name"),
    TextMessage("name"),
    TextMessage("foo"),
    TextMessage("foo"),
    TextMessage("nothing")
  )

  val clientSource: Source[TextMessage, NotUsed] = Source(messages)
    .throttle(1, 100.millis)
    .concat(Source.maybe)

  val (_, _) = http.singleWebSocketRequest(
    WebSocketRequest(s"ws://127.0.0.1:$port/")
      .copy(extraHeaders = List(Host(route.frontend.domains.head.domainLowerCase))),
    Flow
      .fromSinkAndSourceMat(printSink, clientSource)(Keep.both)
      .alsoTo(Sink.onComplete { _ => })
  )

  val yesMessagesCounter = messagesPromise.future.futureValue(Timeout(Span(1, Minutes)))
  yesMessagesCounter == 5 mustBe true

  backend.await()
  http.shutdownAllConnectionPools()
  deleteOtoroshiRoute(route).futureValue
}
