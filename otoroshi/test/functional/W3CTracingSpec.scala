package functional

import org.apache.pekko.http.scaladsl.model.HttpRequest
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgRoute}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{OverrideHost, W3CTracing, W3CTracingConfig, W3CTracingConfigKind}
import play.api.http.Status
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.Future

// the W3C Trace Context plugin: sbt "testOnly functional.W3CTracingSpec"
class W3CTracingSpec extends PluginsTestSpecBase {

  // the backend answers the trace context it receives
  private def echo(req: HttpRequest): JsValue = Json.obj(
    "traceparent" -> req.headers.find(_.is("traceparent")).map(_.value()),
    "baggage"     -> req.headers.find(_.is("baggage")).map(_.value())
  )

  private def tracedRoute(baggage: Map[String, String] = Map.empty): NgRoute =
    createLocalRoute(
      Seq(
        NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[W3CTracing],
          config = NgPluginInstanceConfig(
            W3CTracingConfig(kind = W3CTracingConfigKind.Noop, baggage = baggage).json.as[JsObject]
          )
        )
      ),
      result = echo
    ).futureValue

  private def call(route: NgRoute, headers: (String, String)*): Future[JsValue] =
    ws.url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders((Seq("Host" -> route.frontend.domains.head.domain) ++ headers)*)
      .get()
      .map { resp =>
        resp.status mustBe Status.OK
        resp.json
      }

  // 00-<trace id>-<parent span id>-<flags>
  private def traceparentOf(json: JsValue): (String, String) = {
    val traceparent = (json \ "traceparent").as[String]
    traceparent.split("-").toSeq match {
      case Seq("00", traceId, spanId, _) => (traceId, spanId)
      case _                             => fail(s"bad traceparent: $traceparent")
    }
  }

  "the W3C Trace Context plugin" should {

    // the span of a request was left current on the thread it started on, and the next request handled there took it
    // as its parent (#2970)
    "start a new trace for each request without traceparent" in {
      val route      = tracedRoute()
      val sequential = (1 to 30).map(_ => call(route).futureValue)
      val parallel   = Future.sequence((1 to 30).map(_ => call(route))).futureValue
      val traceIds   = (sequential ++ parallel).map(traceparentOf).map(_._1)
      traceIds.distinct must have size 60
      traceIds.foreach(id => id must not be "0" * 32)
      deleteOtoroshiRoute(route).futureValue
    }

    "continue the trace of the incoming traceparent" in {
      val route    = tracedRoute()
      val traceId  = "4bf92f3577b34da6a3ce929d0e0e4736"
      val parentId = "00f067aa0ba902b7"
      (1 to 5).foreach { _ =>
        val (forwardedTraceId, forwardedParentId) =
          traceparentOf(call(route, "traceparent" -> s"00-$traceId-$parentId-01").futureValue)
        forwardedTraceId mustBe traceId
        forwardedParentId must not be parentId
      }
      // and the next request without traceparent is not part of it
      traceparentOf(call(route).futureValue)._1 must not be traceId
      deleteOtoroshiRoute(route).futureValue
    }

    "forward the incoming baggage with the one of its config" in {
      val route    = tracedRoute(baggage = Map("from" -> "otoroshi"))
      val baggage  = (call(route, "baggage" -> "caller=client").futureValue \ "baggage").as[String]
      baggage.split(",").map(_.trim).toSet mustBe Set("caller=client", "from=otoroshi")
      // the baggage of a request does not go to the next one
      (call(route).futureValue \ "baggage").as[String].split(",").map(_.trim).toSet mustBe Set("from=otoroshi")
      deleteOtoroshiRoute(route).futureValue
    }
  }
}
