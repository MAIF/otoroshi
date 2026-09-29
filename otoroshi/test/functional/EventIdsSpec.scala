package functional

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.events.DataExporter.RetryEvent
import otoroshi.next.events.TrafficCaptureEvent
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.api.{NgPluginHttpRequest, NgPluginHttpResponse}
import otoroshi.next.proxy.{NgExecutionReport, RequestFlowReport}
import otoroshi.utils.TypedMap
import play.api.libs.json.Json
import play.api.test.FakeRequest

// an event keeps the same id from one read to the next: exporters read it several times (the event itself, the
// json they send, the retries of a failed export)
class EventIdsSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  private val route = NgFakeRoute.route("event-ids")

  "An event" should {

    "keep its id: a request flow report" in {
      val event = RequestFlowReport(NgExecutionReport("report-id", true), route)
      event.`@id` mustBe event.`@id`
    }

    "keep its id: a traffic capture" in {
      val request  = NgPluginHttpRequest(
        url = "http://backend.oto.tools/api",
        method = "GET",
        headers = Map.empty,
        version = "HTTP/1.1",
        clientCertificateChain = () => None,
        body = Source.empty,
        backend = None
      )
      val response = NgPluginHttpResponse(status = 200, headers = Map.empty, body = Source.empty)
      val event    = TrafficCaptureEvent(route, FakeRequest(), request, response, response, ByteString.empty, TypedMap.empty)
      event.`@id` mustBe event.`@id`
    }

    "keep its id and timestamp: an export retry of an event without them" in {
      val event = RetryEvent(Json.obj("@type" -> "GatewayEvent"))
      event.`@id` mustBe event.`@id`
      event.`@timestamp` mustBe event.`@timestamp`
      RetryEvent(Json.obj("@id" -> "given")).`@id` mustBe "given"
    }
  }
}
