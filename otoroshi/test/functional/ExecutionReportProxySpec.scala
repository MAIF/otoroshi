package functional

import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.stream.Materializer
import org.scalatest.concurrent.Eventually
import otoroshi.env.Env
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgExecutionReport
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.{JsArray, JsNull, JsObject, JsValue, Json}
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse, writeableOf_String}
import play.api.mvc.Result

import java.util.Base64
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

// keeps the report of the last request it transformed, to look at it once the response is there
class ReportProbe extends NgRequestTransformer {
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory]           = Seq.empty
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def isTransformRequestAsync: Boolean            = false

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    ReportProbe.last.set(ctx.report)
    Right(ctx.otoroshiRequest)
  }
}

object ReportProbe {
  val last = new AtomicReference[NgExecutionReport](null)
}

// the execution report of real requests: the timers of each plugin come from the typed plugin sequences, a report
// that something reads keeps every context, one that nothing reads keeps the sequences only.
// sbt "testOnly functional.ExecutionReportProxySpec"
class ExecutionReportProxySpec extends PluginsTestSpecBase with Eventually {

  private val probe = NgPluginHelper.pluginId[ReportProbe]

  // the backend of the route counts the calls it gets
  private val backendCalls = new AtomicInteger(0)

  private def okBackend(): TargetService = TargetService.jsonFull(
    None,
    "/",
    (_: HttpRequest) => {
      backendCalls.incrementAndGet()
      (200, Json.obj("ok" -> true), List.empty)
    }
  )

  // answers the body it gets
  private def echoBackend(): TargetService = TargetService.streamed(
    None,
    "/",
    "application/json",
    (request: HttpRequest) => request.entity.dataBytes.mapMaterializedValue(_ => NotUsed)
  )

  private def withRoute[A](id: String, backend: () => TargetService = () => okBackend())(f: String => A): A = {
    val echo    = backend().await()
    val base    = NgFakeRoute.route(id)
    val created = base.copy(
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"$id.oto.tools"))),
      backend = base.backend.copy(
        targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = echo.port, tls = false)),
        root = "/"
      ),
      plugins = NgPlugins(
        Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]), NgPluginInstance(plugin = probe))
      )
    )
    createOtoroshiRoute(created).futureValue
    try f(s"$id.oto.tools")
    finally {
      deleteOtoroshiRoute(created).futureValue
      echo.stop()
    }
  }

  private def get(host: String, headers: (String, String)*): Int =
    ws.url(s"http://127.0.0.1:$port/").withHttpHeaders((("Host" -> host) +: headers)*).get().futureValue.status

  private def waitUntil(what: String)(cond: => Boolean): Unit = {
    val deadline = System.currentTimeMillis() + 10000
    while (!cond && System.currentTimeMillis() < deadline) await(100.millis)
    withClue(what)(cond mustBe true)
  }

  // a trial through the admin api, by default with the admin api key of the tests
  private def tryIt(
      path: String,
      body: String = "",
      clientId: String = "admin-api-apikey-id",
      clientSecret: String = "admin-api-apikey-secret"
  ): WSResponse =
    ws.url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders(
        "Host"         -> "otoroshi-api.oto.tools",
        "Accept"       -> "application/json",
        "Content-Type" -> "application/json"
      )
      .withAuth(clientId, clientSecret, WSAuthScheme.BASIC)
      .withFollowRedirects(false)
      .post(body)
      .futureValue

  private def tryItOn(id: String): String = s"/apis/proxy.otoroshi.io/v1/routes/$id/_tryit"

  private def trial(path: String, body: String = ""): JsValue = {
    val resp = tryIt(path, body)
    withClue(resp.body[String])(resp.status mustBe 200)
    resp.json
  }

  private def stepsOf(answer: JsValue): Map[String, JsObject] =
    (answer \ "report" \ "steps").as[Seq[JsObject]].map(step => (step \ "task").as[String] -> step).toMap

  // the id the trial enabled its report for, read from the curl command of the answer
  private def trialIdOf(answer: JsValue): String =
    "Otoroshi-Try-It-Request-Id: ([^\"]+)".r
      .findFirstMatchIn((answer \ "curl").as[String])
      .map(_.group(1))
      .getOrElse(fail(s"no trial id in ${answer.stringify}"))

  // a timer is exported under its key followed by its tags
  private def timerCount(key: String): Long = (env.metrics.jsonRawExport() \ "timers")
    .asOpt[JsObject]
    .flatMap(_.value.collectFirst {
      case (name, timer) if name.takeWhile(_ != ' ') == key => (timer \ "count").as[Long]
    })
    .getOrElse(0L)

  "The execution report of a request" should {

    "time each plugin of a phase" in {
      withRoute("report-timers") { host =>
        val timer  = s"ng-report-transform-request-$probe"
        val before = timerCount(timer)
        (1 to 3).map(_ => get(host)) mustBe Seq(200, 200, 200)
        eventually {
          timerCount(timer) must be >= (before + 3)
        }
      }
    }

    "keep every context when something reads it" in {
      withRoute("report-read") { host =>
        val id      = s"report-read-${System.nanoTime()}"
        env.proxyState.enableReportFor(id)
        get(host, "Otoroshi-Try-It-Request-Id" -> id) mustBe 200
        val json    = env.proxyState.report(id).map(_.json).getOrElse(JsNull)
        val steps   = (json \ "steps").as[Seq[JsObject]].map(step => (step \ "task").as[String] -> step).toMap
        (steps("find-route") \ "ctx" \ "found_route" \ "id").asOpt[String] mustBe Some("report-read")
        val plugins = (steps("transform-request") \ "ctx" \ "plugins").as[JsArray].value
        plugins.map(plugin => (plugin \ "plugin").as[String]) must contain(probe)
        plugins.map(plugin => (plugin \ "execution_debug" \ "out" \ "result" \ "kind").asOpt[String]) must contain(
          Some("successful")
        )
      }
    }

    "keep the plugin sequences only when nothing reads it" in {
      withRoute("report-unread") { host =>
        ReportProbe.last.set(null)
        get(host) mustBe 200
        val report   = ReportProbe.last.get()
        val sequence = report.getStep("transform-request").flatMap(_.sequence)
        report.getStep("find-route").map(_.ctx) mustBe Some(JsNull)
        sequence.map(_.plugins.map(_.plugin)).getOrElse(Seq.empty) must contain(probe)
        sequence.map(_.plugins.map(_.out)).getOrElse(Seq.empty).distinct mustBe Seq(JsNull)
      }
    }
  }

  "The trial of a route through the admin api" should {

    "answer the response and the report of the call, on both admin apis" in {
      withRoute("report-tryit") { _ =>
        Seq(tryItOn("report-tryit"), "/api/routes/report-tryit/_tryit").foreach { path =>
          withClue(path) {
            val answer  = trial(path, """{"method": "GET", "path": "/"}""")
            (answer \ "status").as[Int] mustBe 200
            val steps   = stepsOf(answer)
            (steps("find-route") \ "ctx" \ "found_route" \ "id").asOpt[String] mustBe Some("report-tryit")
            val plugins = (steps("transform-request") \ "ctx" \ "plugins").as[JsArray].value
            plugins.collect {
              case plugin if (plugin \ "plugin").as[String] == probe =>
                (plugin \ "execution_debug" \ "out" \ "result" \ "kind").asOpt[String]
            } mustBe Seq(Some("successful"))
          }
        }
      }
    }

    "send the method, the headers and the base64 body of the trial" in {
      withRoute("report-tryit-body", () => echoBackend()) { _ =>
        val answer = trial(
          tryItOn("report-tryit-body"),
          """{"method": "POST", "path": "/", "body": "eyJhIjoxfQ==", "base_64": true, "headers": {"Content-Type": "application/json"}}"""
        )
        (answer \ "status").as[Int] mustBe 200
        new String(Base64.getDecoder.decode((answer \ "body_base_64").as[String]), "UTF-8") mustBe """{"a":1}"""
      }
    }

    "send a GET on the path of the route without a body" in {
      withRoute("report-tryit-default") { _ =>
        val before = backendCalls.get()
        (trial(tryItOn("report-tryit-default")) \ "status").as[Int] mustBe 200
        backendCalls.get() mustBe (before + 1)
      }
    }

    "refuse an unknown route and a body it cannot read" in {
      withRoute("report-tryit-errors") { _ =>
        val unknown = tryIt(tryItOn("report-tryit-unknown"), "{}")
        unknown.status mustBe 404
        (unknown.json \ "error").as[String] mustBe "route not found"
        tryIt("/api/routes/report-tryit-unknown/_tryit", "{}").status mustBe 404
        val before  = backendCalls.get()
        Seq(
          "[]",
          "\"x\"",
          "not json",
          """{"headers": "x"}""",
          """{"headers": {"X-Count": 1}}""",
          """{"path": "no-slash"}""",
          """{"method": 42}""",
          """{"body": "%%%", "base_64": true}""",
          """{"cookies": [{"name": "c"}]}""",
          """{"client_cert": "report-tryit-no-such-cert"}"""
        ).foreach { body =>
          val resp = tryIt(tryItOn("report-tryit-errors"), body)
          withClue(s"$body: ${resp.body[String]}") {
            resp.status mustBe 400
            (resp.json \ "error").asOpt[String] must not be empty
          }
        }
        backendCalls.get() mustBe before
      }
    }

    "refuse a route the admin api key cannot read, without calling it" in {
      withRoute("report-tryit-rights") { _ =>
        val clientId = "report-tryit-scoped"
        val created  = otoroshiApiCall(
          "POST",
          "/api/apikeys",
          Json
            .obj(
              "clientId"           -> clientId,
              "clientSecret"       -> s"$clientId-secret",
              "clientName"         -> clientId,
              "authorizedEntities" -> Json.arr("group_admin-api-group"),
              "metadata"           -> Json.obj(
                "otoroshi-access-rights" -> """[{"tenant":"default:rw","teams":["report-other-team:rw"]}]"""
              ),
              "_loc"               -> Json.obj("tenant" -> "default", "teams" -> Json.arr("report-other-team"))
            )
            .some
        ).futureValue
        withClue(created._1.stringify)(created._2 mustBe 201)
        try {
          waitUntil("the gateway sees the scoped admin api key")(env.proxyState.apikey(clientId).isDefined)
          val before = backendCalls.get()
          Seq(tryItOn("report-tryit-rights"), "/api/routes/report-tryit-rights/_tryit").foreach { path =>
            withClue(path)(tryIt(path, "{}", clientId, s"$clientId-secret").status mustBe 403)
          }
          backendCalls.get() mustBe before
        } finally {
          otoroshiApiCall("DELETE", s"/api/apikeys/$clientId").futureValue
        }
      }
    }

    "record a report for each trial only" in {
      withRoute("report-tryit-twice") { host =>
        val first  = trial(tryItOn("report-tryit-twice"))
        val second = trial(tryItOn("report-tryit-twice"))
        (first \ "report" \ "id").as[String] must not be (second \ "report" \ "id").as[String]
        trialIdOf(first) must not be trialIdOf(second)
        // the id of a finished trial enables nothing anymore
        val id     = trialIdOf(first)
        get(host, "Otoroshi-Try-It-Request-Id" -> id) mustBe 200
        env.proxyState.report(id) mustBe None
      }
    }

    "send an audit event without the headers of the trial" in {
      withRoute("report-tryit-audit") { _ =>
        trial(tryItOn("report-tryit-audit"), """{"path": "/", "headers": {"X-Trial-Secret": "s3cr3t-trial-value"}}""")
        def events(): Seq[JsValue] = env.datastores.auditDataStore
          .findAllRaw()
          .futureValue
          .map(raw => Json.parse(raw.utf8String))
          .filter(e =>
            (e \ "action").asOpt[String].contains("ACCESS_SERVICE_TRYIT") &&
            (e \ "metadata" \ "routeId").asOpt[String].contains("report-tryit-audit")
          )
        waitUntil("the audit event of the trial is written")(events().nonEmpty)
        val event = events().head
        (event \ "metadata" \ "method").asOpt[String] mustBe Some("GET")
        (event \ "metadata" \ "path").asOpt[String] mustBe Some("/")
        event.stringify must not include "s3cr3t-trial-value"
      }
    }
  }
}
