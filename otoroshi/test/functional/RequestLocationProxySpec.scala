package functional

import org.apache.pekko.http.scaladsl.model.{HttpRequest, IllegalUriException, Uri}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.models.GlobalConfig
import otoroshi.next.models.*
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.ProxyEngine
import otoroshi.script.RequestHandler
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.http.RequestLocation
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.Json
import play.api.mvc.{AnyContentAsEmpty, Headers, RequestHeader, Result, Results}
import play.api.test.FakeRequest

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.util.Try

// keeps the location of the last request it transformed
class LocationProbe extends NgRequestTransformer {
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def categories: Seq[NgPluginCategory]           = Seq.empty
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def isTransformRequestAsync: Boolean            = false

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    LocationProbe.last.set(ctx.request.attrs.get(otoroshi.plugins.Keys.RequestLocationKey).orNull)
    Right(ctx.otoroshiRequest)
  }
}

object LocationProbe {
  val last = new AtomicReference[RequestLocation](null)
}

// the location of a request, resolved once when it comes in: it holds what the helpers resolve on each call without
// it, the request handler attaches it to every request and the proxy engine to a request handed to it directly, and a
// request built from another one gets its own. sbt "testOnly functional.RequestLocationProxySpec"
class RequestLocationProxySpec extends PluginsTestSpecBase {

  private def fake(uri: String, secure: Boolean, headers: (String, String)*): RequestHeader =
    FakeRequest("GET", uri, Headers(headers*), AnyContentAsEmpty, secure = secure)

  // what the helpers resolved on each call before the location: a parse of the uri for the path and another one for
  // the relative uri, the host, the domain and the secured flag from the headers and the connection
  private def before(request: RequestHeader): RequestLocation = {
    val raw = request.uri
    RequestLocation(
      Uri(raw).path.toString(),
      Try(Uri(raw).toRelative.toString()).getOrElse(raw),
      request.theHost,
      request.theDomain,
      request.theSecured
    )
  }

  // writes the global config, then waits for the cached copy the helpers read, which is refreshed asynchronously
  private def updateGlobalConfig(update: GlobalConfig => GlobalConfig)(applied: GlobalConfig => Boolean): Unit = {
    val current  = env.datastores.globalConfigDataStore.latest()
    env.datastores.globalConfigDataStore.set(update(current)).futureValue
    val deadline = System.currentTimeMillis() + 10000
    while (!env.datastores.globalConfigDataStore.latestSafe.exists(applied) && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }
    env.datastores.globalConfigDataStore.latestSafe.exists(applied) mustBe true
  }

  private def withRoute[A](id: String)(f: String => A): A = {
    val echo    = TargetService.jsonFull(None, "/", (_: HttpRequest) => (200, Json.obj("ok" -> true), List.empty)).await()
    val base    = NgFakeRoute.route(id)
    val created = base.copy(
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"$id.oto.tools"))),
      backend = base.backend.copy(
        targets = Seq(NgTarget(id = "echo", hostname = "127.0.0.1", port = echo.port, tls = false)),
        root = "/"
      ),
      plugins = NgPlugins(
        Seq(
          NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost]),
          NgPluginInstance(plugin = NgPluginHelper.pluginId[LocationProbe])
        )
      )
    )
    createOtoroshiRoute(created).futureValue
    try f(s"$id.oto.tools")
    finally {
      deleteOtoroshiRoute(created).futureValue
      echo.stop()
    }
  }

  "The location of a request" should {

    "hold what the helpers resolve on each call without it, whatever the uri and the host of the request" in {
      val requests = Seq(
        fake("/api/users?page=1&size=20", secure = false, "Host" -> "location.oto.tools"),
        fake("/api/caf%C3%A9/%7Euser?q=a%20b", secure = false, "Host" -> "location.oto.tools:8080"),
        fake("http://absolute.oto.tools:9090/api/abs?x=1", secure = false, "Host" -> "other.oto.tools"),
        fake("//double/slash?x=1", secure = false, "Host" -> "location.oto.tools"),
        fake("http://absolute.oto.tools//double/slash", secure = false, "Host" -> "location.oto.tools"),
        fake("/a/./b/../c", secure = false, "Host" -> "location.oto.tools"),
        fake("*", secure = false, "Host" -> "location.oto.tools"),
        fake("/", secure = true, "Host" -> "LOCATION.oto.tools"),
        fake(
          "/api?x=1",
          secure = false,
          "Host"              -> "location.oto.tools",
          "X-Forwarded-Host"  -> "forwarded.oto.tools:8443",
          "X-Forwarded-Proto" -> "https"
        )
      )
      requests.foreach { request =>
        val located = RequestLocation.attachTo(request)
        located.attrs.get(otoroshi.plugins.Keys.RequestLocationKey) mustBe Some(before(request))
        (located.thePath, located.relativeUri, located.theHost, located.theDomain, located.theSecured) mustBe
        (request.thePath, request.relativeUri, request.theHost, request.theDomain, request.theSecured)
        (located.theUrl, located.theProtocol) mustBe (request.theUrl, request.theProtocol)
      }
    }

    "take the host and the protocol from the forwarded headers only when they are trusted" in {
      val request = fake(
        "/api?x=1",
        secure = false,
        "Host"              -> "location.oto.tools",
        "X-Forwarded-Host"  -> "forwarded.oto.tools:8443",
        "X-Forwarded-Proto" -> "https"
      )
      val initial = env.datastores.globalConfigDataStore.latest().trustXForwarded
      try {
        updateGlobalConfig(_.copy(trustXForwarded = false))(!_.trustXForwarded)
        RequestLocation.of(request) mustBe
        RequestLocation("/api", "/api?x=1", "location.oto.tools", "location.oto.tools", false)
        updateGlobalConfig(_.copy(trustXForwarded = true))(_.trustXForwarded)
        RequestLocation.of(request) mustBe
        RequestLocation("/api", "/api?x=1", "forwarded.oto.tools:8443", "forwarded.oto.tools", true)
      } finally {
        updateGlobalConfig(_.copy(trustXForwarded = initial))(_.trustXForwarded == initial)
      }
    }

    "be resolved again for a request built from another one, and not at all for an uri that does not parse" in {
      val request = fake("/fresh?q=1", secure = false, "Host" -> "fresh.oto.tools")
      val stale   = request.addAttr(
        otoroshi.plugins.Keys.RequestLocationKey,
        RequestLocation("/stale", "/stale", "stale.oto.tools", "stale.oto.tools", true)
      )
      RequestLocation.attachTo(stale).attrs.get(otoroshi.plugins.Keys.RequestLocationKey) mustBe
      Some(RequestLocation("/fresh", "/fresh?q=1", "fresh.oto.tools", "fresh.oto.tools", false))
      // the helpers keep resolving the values of a request that has no location, as before
      val broken  = fake("/%zz", secure = false, "Host" -> "broken.oto.tools")
      (RequestLocation.attachTo(broken) eq broken) mustBe true
      an[IllegalUriException] must be thrownBy broken.thePath
      (broken.relativeUri, broken.theHost) mustBe ("/%zz", "broken.oto.tools")
    }

    "be attached by the request handler to the request it hands over, whatever the server" in {
      val (handed, _) = env.handlerRef.get().handlerForRequest(fake("/handed?q=3", secure = false, "Host" -> "handed.oto.tools"))
      handed.attrs.get(otoroshi.plugins.Keys.RequestLocationKey) mustBe
      Some(RequestLocation("/handed", "/handed?q=3", "handed.oto.tools", "handed.oto.tools", false))
    }

    "be read by the plugins of a request" in {
      withRoute("location-probe") { host =>
        LocationProbe.last.set(null)
        ws.url(s"http://127.0.0.1:$port/probe/path?q=1")
          .withHttpHeaders("Host" -> host)
          .get()
          .futureValue
          .status mustBe 200
        LocationProbe.last.get() mustBe RequestLocation("/probe/path", "/probe/path?q=1", host, host, false)
      }
    }

    "be resolved by the proxy engine for a request handed to it directly" in {
      withRoute("location-engine") { host =>
        LocationProbe.last.set(null)
        val engine  = env.scriptManager.getAnyScript[RequestHandler](NgPluginHelper.pluginId[ProxyEngine]).toOption.get
        val request = FakeRequest("GET", "/direct/path?q=2", Headers("Host" -> host), Source.empty[ByteString])
        engine
          .handle(request, _ => Results.InternalServerError("bad default routing").vfuture)(using
            env.otoroshiExecutionContext,
            env
          )
          .futureValue
          .header
          .status mustBe 200
        LocationProbe.last.get() mustBe RequestLocation("/direct/path", "/direct/path?q=2", host, host, false)
      }
    }
  }
}
