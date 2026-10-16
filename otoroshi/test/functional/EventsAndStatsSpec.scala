package functional

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse}

import scala.concurrent.ExecutionContext

// the events the admin api reads: the audit events filtered then paginated, one per line in ndjson and the fields of an
// event as written in its json
class EventsAndStatsSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private def admin(path: String, headers: (String, String)*): WSResponse = ws
    .url(s"http://127.0.0.1:$port$path")
    .withHttpHeaders(Seq("Host" -> "otoroshi-api.oto.tools") ++ headers*)
    .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
    .get()
    .futureValue

  "The events and the statistics" should {
    "warm up" in {
      startOtoroshi()
    }
    "filter the audit events, then paginate them" in {
      given env: Env             = otoroshiComponents.env
      given ec: ExecutionContext = env.otoroshiExecutionContext
      (1 to 6).foreach { i =>
        val kind = if (i % 2 == 0) "spec-even" else "spec-odd"
        env.datastores.auditDataStore.push(Json.obj("@id" -> s"event-$i", "kind" -> kind)).futureValue
      }
      def ids(resp: WSResponse): Seq[String] = resp.json.as[Seq[JsObject]].map(e => (e \ "@id").as[String])
      // the most recent first
      ids(admin("/api/audit/events?filter.kind=spec-odd&page=1&pageSize=2")) mustBe Seq("event-5", "event-3")
      ids(admin("/api/audit/events?filter.kind=spec-odd&page=2&pageSize=2")) mustBe Seq("event-1")
      // without filter too
      admin("/api/audit/events?page=1&pageSize=2").json.as[Seq[JsObject]].size mustBe 2
      // one event per line
      val ndjson = admin("/api/audit/events?filter.kind=spec-odd", "Accept" -> "application/x-ndjson").body[String]
      ndjson.split("\n").toSeq.map(l => (Json.parse(l) \ "@id").as[String]) mustBe Seq("event-5", "event-3", "event-1")
      ndjson must endWith("\n")
    }
    "list the fields of an event as they are written in its json" in {
      val fields = admin("/api/events/_template?eventType=GatewayEvent").json.as[Seq[String]]
      fields must contain("@id")
      fields must contain("@timestamp")
      fields.filter(_.contains("$")) mustBe empty
    }
    "shutdown" in {
      stopAll()
    }
  }
}
