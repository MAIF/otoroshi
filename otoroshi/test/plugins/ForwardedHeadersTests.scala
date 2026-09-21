package plugins

import functional.PluginsTestSpecBase
import otoroshi.models.GlobalConfig
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{ForwardedHeader, OverrideHost, XForwardedHeaders}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSBodyReadables.given

class ForwardedHeadersTests(parent: PluginsTestSpecBase) {
  import parent.*

  // writes the global config, then waits for the cached copy the proxy reads, which is refreshed
  // asynchronously after the write
  private def updateGlobalConfig(update: GlobalConfig => GlobalConfig)(applied: GlobalConfig => Boolean): Unit = {
    val current  = env.datastores.globalConfigDataStore.latest()
    env.datastores.globalConfigDataStore.set(update(current)).futureValue
    val deadline = System.currentTimeMillis() + 10000
    while (!env.datastores.globalConfigDataStore.latestSafe.exists(applied) && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }
    env.datastores.globalConfigDataStore.latestSafe.exists(applied) mustBe true
  }

  val route = createLocalRoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ForwardedHeader]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[XForwardedHeaders]
      )
    ),
    result = req =>
      Json.obj(
        "forwarded"         -> req.headers.filter(_.is("forwarded")).map(_.value),
        "x-forwarded-for"   -> req.headers.filter(_.is("x-forwarded-for")).map(_.value),
        "x-forwarded-proto" -> req.headers.filter(_.is("x-forwarded-proto")).map(_.value)
      )
  ).futureValue

  val domain = route.frontend.domains.head.domain

  // every occurrence of the headers received by the backend
  def received(headers: (String, String)*): JsObject = {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(Seq("Host" -> domain) ++ headers*)
      .get()
      .futureValue
    resp.status mustBe Status.OK
    Json.parse(resp.body[String]).as[JsObject]
  }

  def forwarded(headers: (String, String)*): Seq[String] = (received(headers*) \ "forwarded").as[Seq[String]]

  val initial = env.datastores.globalConfigDataStore.latest()

  try {
    updateGlobalConfig(_.copy(trustXForwarded = true))(_.trustXForwarded)

    forwarded() mustBe Seq(s"for=127.0.0.1;host=$domain;proto=http")

    forwarded(
      "X-Forwarded-For"   -> "192.0.2.15, 2001:db8::7",
      "X-Forwarded-Proto" -> "https"
    ) mustBe Seq(s"""for=192.0.2.15;host=$domain;proto=https, for="[2001:db8::7]", for=127.0.0.1""")

    // X-Forwarded-For is the client address header: the Forwarded header of the client is not
    // kept, whatever the case of its name
    val fromXForwardedFor = received(
      "forwarded"       -> "for=192.0.2.60;proto=https",
      "X-Forwarded-For" -> "192.0.2.15"
    )
    (fromXForwardedFor \ "forwarded").as[Seq[String]] mustBe Seq(s"for=192.0.2.15;host=$domain;proto=http, for=127.0.0.1")
    (fromXForwardedFor \ "x-forwarded-for").as[Seq[String]] mustBe Seq("192.0.2.15, 127.0.0.1")

    // Forwarded is the client address header: its chain is kept, and the X-Forwarded-For of the
    // client is rebuilt from it
    updateGlobalConfig(_.copy(clientAddressHeader = "Forwarded"))(_.clientAddressHeader == "Forwarded")
    val fromForwarded = received(
      "forwarded"       -> "for=192.0.2.60;proto=https",
      "X-Forwarded-For" -> "192.0.2.15"
    )
    (fromForwarded \ "forwarded").as[Seq[String]] mustBe Seq("for=192.0.2.60;proto=https, for=127.0.0.1")
    (fromForwarded \ "x-forwarded-for").as[Seq[String]] mustBe Seq("192.0.2.60, 127.0.0.1")
    (fromForwarded \ "x-forwarded-proto").as[Seq[String]] mustBe Seq("https")
    updateGlobalConfig(_.copy(clientAddressHeader = "X-Forwarded-For"))(_.clientAddressHeader == "X-Forwarded-For")

    updateGlobalConfig(_.copy(trustXForwarded = false))(!_.trustXForwarded)

    // nothing the client sent is kept
    forwarded(
      "forwarded"         -> "for=192.0.2.15;proto=https",
      "X-Forwarded-For"   -> "192.0.2.15",
      "X-Forwarded-Proto" -> "https"
    ) mustBe Seq(s"for=127.0.0.1;host=$domain;proto=http")
  } finally {
    updateGlobalConfig(
      _.copy(trustXForwarded = initial.trustXForwarded, clientAddressHeader = initial.clientAddressHeader)
    )(c => c.trustXForwarded == initial.trustXForwarded && c.clientAddressHeader == initial.clientAddressHeader)
    deleteOtoroshiRoute(route).futureValue
  }
}
