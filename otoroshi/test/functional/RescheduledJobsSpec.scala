package functional

import com.sun.net.httpserver.HttpServer
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext

// the job of a workflow and the scheduling of a remote catalog are scheduled again when they change, without
// disabling and enabling them
class RescheduledJobsSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private def admin(method: String, path: String, body: Option[JsValue] = None): WSResponse = {
    val req = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Accept" -> "application/json")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withMethod(method)
    body
      .map(b => req.addHttpHeaders("Content-Type" -> "application/json").withBody(b.stringify))
      .getOrElse(req)
      .execute()
      .futureValue
  }

  private def save(path: String, entity: JsObject, status: Int): Unit = {
    val resp = admin(if (status == 201) "POST" else "PUT", path, Some(entity))
    withClue(resp.body[String])(resp.status mustBe status)
  }

  // scheduled every hour, first run in an hour, then every second, first run right away
  private def scheduling(soon: Boolean): JsObject = Json.obj(
    "enabled"       -> true,
    "kind"          -> "ScheduledEvery",
    "instantiation" -> "OneInstancePerOtoroshiInstance",
    "initial_delay" -> (if (soon) 100 else 3600000),
    "interval"      -> (if (soon) 1000 else 3600000)
  )

  private def eventually(condition: => Boolean): Boolean = {
    val deadline = System.currentTimeMillis() + 20000L
    while (!condition && System.currentTimeMillis() < deadline) Thread.sleep(500)
    condition
  }

  "A scheduled job" should {
    "warm up" in {
      startOtoroshi()
    }
    "be scheduled again when the job of its workflow changes" in {
      given env: Env             = otoroshiComponents.env
      given ec: ExecutionContext = env.otoroshiExecutionContext
      val key                    = s"${env.storageRoot}:rescheduled-jobs-test:workflow"
      val path                   = "/apis/plugins.otoroshi.io/v1/workflows"
      val template               = admin("GET", s"$path/_template").json.as[JsObject]
      def workflow(soon: Boolean) = template ++ Json.obj(
        "id"     -> "workflow-rescheduled",
        "config" -> Json.obj(
          "id"    -> "start",
          "kind"  -> "workflow",
          "steps" -> Json.arr(
            Json.obj("kind" -> "call", "function" -> "core.store_set", "args" -> Json.obj("key" -> key, "value" -> "ran"))
          )
        ),
        "job"    -> scheduling(soon)
      )
      save(path, workflow(soon = false), 201)
      // the states of the extension take the workflow and its job a few seconds after
      Thread.sleep(5000)
      env.datastores.rawDataStore.get(key).futureValue mustBe None
      save(s"$path/workflow-rescheduled", workflow(soon = true), 200)
      eventually(env.datastores.rawDataStore.get(key).futureValue.isDefined) mustBe true
      admin("DELETE", s"$path/workflow-rescheduled")
    }
    "be scheduled again when the scheduling of its remote catalog changes" in {
      val fetched = new AtomicInteger(0)
      val source  = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      source.createContext(
        "/",
        exchange => {
          fetched.incrementAndGet()
          val bytes = "[]".getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, bytes.length)
          exchange.getResponseBody.write(bytes)
          exchange.close()
        }
      )
      source.start()
      try {
        val path                   = "/apis/catalogs.otoroshi.io/v1/remote-catalogs"
        val template               = admin("GET", s"$path/_template").json.as[JsObject]
        def catalog(soon: Boolean) = template ++ Json.obj(
          "id"            -> "remote-catalog-rescheduled",
          "enabled"       -> true,
          "source_kind"   -> "http",
          "source_config" -> Json.obj("url" -> s"http://127.0.0.1:${source.getAddress.getPort}/catalog.json"),
          "scheduling"    -> scheduling(soon)
        )
        save(path, catalog(soon = false), 201)
        Thread.sleep(5000)
        fetched.get() mustBe 0
        save(s"$path/remote-catalog-rescheduled", catalog(soon = true), 200)
        eventually(fetched.get() > 0) mustBe true
        admin("DELETE", s"$path/remote-catalog-rescheduled")
      } finally {
        source.stop(0)
      }
    }
    "shutdown" in {
      stopAll()
    }
  }
}
