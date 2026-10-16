package functional

import com.sun.net.httpserver.HttpServer
import otoroshi.env.Env
import otoroshi.events.impl.ElasticReadsAnalytics
import otoroshi.models.ElasticAnalyticsConfig
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

// the events and the statistics the admin api reads: the audit events filtered then paginated, one per line in ndjson,
// the fields of an event as written in its json, the pages of the health of the routes and the names of the slices of a
// pie chart
class EventsAndStatsSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  // an elasticsearch cluster that answers the same aggregations to every search, and keeps the searches
  private val searches = new ConcurrentLinkedQueue[String]()
  private val elastic  = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  elastic.createContext(
    "/",
    exchange => {
      val body   = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      if (exchange.getRequestURI.getPath.endsWith("_search")) searches.add(body)
      val answer = Json
        .obj(
          "hits"         -> Json.obj("total" -> Json.obj("value" -> 1), "hits" -> Json.arr()),
          "aggregations" -> Json.obj(
            "codes"    -> Json.obj(
              "buckets" -> Json.arr(Json.obj("key" -> "my-route", "doc_count" -> 3), Json.obj("key" -> 200, "doc_count" -> 5))
            ),
            "services" -> Json.obj("buckets" -> Json.arr())
          )
        )
        .stringify
        .getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, answer.length)
      exchange.getResponseBody.write(answer)
      exchange.close()
    }
  )

  private def elasticConfig = ElasticAnalyticsConfig(
    uris = Seq(s"http://127.0.0.1:${elastic.getAddress.getPort}"),
    version = Some("8.0.0")
  )

  private def admin(path: String, headers: (String, String)*): WSResponse = ws
    .url(s"http://127.0.0.1:$port$path")
    .withHttpHeaders(Seq("Host" -> "otoroshi-api.oto.tools") ++ headers*)
    .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
    .get()
    .futureValue

  "The events and the statistics" should {
    "warm up" in {
      startOtoroshi()
      elastic.start()
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
    "answer the health of the routes of the page asked, counted from 1" in {
      given env: Env             = otoroshiComponents.env
      given ec: ExecutionContext = env.otoroshiExecutionContext
      val config                 = env.datastores.globalConfigDataStore.singleton().futureValue
      config.copy(elasticReadsConfig = Some(elasticConfig)).save().futureValue
      val template               = admin("/apis/proxy.otoroshi.io/v1/routes/_template").json.as[JsObject]
      Seq("status-route-1", "status-route-2", "status-route-3").foreach { id =>
        val route = template ++ Json.obj(
          "id"       -> id,
          "name"     -> id,
          "frontend" -> ((template \ "frontend").as[JsObject] ++ Json.obj("domains" -> Json.arr(s"$id.oto.tools"))),
          "backend"  -> ((template \ "backend").as[JsObject] ++ Json.obj(
            "targets"      -> Json.arr(Json.obj("id" -> "target", "hostname" -> "127.0.0.1", "port" -> 1, "tls" -> false)),
            "health_check" -> Json.obj("enabled" -> true, "url" -> "/")
          ))
        )
        val resp  = ws
          .url(s"http://127.0.0.1:$port/apis/proxy.otoroshi.io/v1/routes")
          .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Content-Type" -> "application/json")
          .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
          .post(route.stringify)
          .futureValue
        withClue(resp.body[String])(resp.status mustBe 201)
      }
      // the routes the last search asked the health of
      def searched(path: String): Seq[String] = {
        searches.clear()
        val resp = admin(path)
        withClue(resp.body[String])(resp.header("X-Count") mustBe Some("3"))
        searches.asScala.toSeq.flatMap { s =>
          Seq("status-route-1", "status-route-2", "status-route-3").filter(id => s.contains(s"\"$id\""))
        }.distinct
      }
      // the proxy states take the routes a few seconds after
      val deadline = System.currentTimeMillis() + 20000L
      while (admin("/api/status/global").header("X-Count").isEmpty && System.currentTimeMillis() < deadline) {
        Thread.sleep(1000)
      }
      searched("/api/status/global") mustBe Seq("status-route-1", "status-route-2", "status-route-3")
      searched("/api/status/global?page=1&pageSize=2") mustBe Seq("status-route-1", "status-route-2")
      searched("/api/status/global?page=2&pageSize=2") mustBe Seq("status-route-3")
    }
    "name the slices of a pie chart without the quotes of their json" in {
      given env: Env             = otoroshiComponents.env
      given ec: ExecutionContext = env.otoroshiExecutionContext
      val pie                    = new ElasticReadsAnalytics(elasticConfig, env).piechart("@service", None, None, None).futureValue
      (pie \ "series" \ 0 \ "data").as[Seq[JsObject]].map(s => (s \ "name").as[String]) mustBe Seq("my-route", "200")
    }
    "shutdown" in {
      elastic.stop(0)
      stopAll()
    }
  }
}
