package plugins

import functional.PluginsTestSpecBase
import otoroshi.models.GlobalConfig
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{ForwardedHeader, OverrideHost}
import play.api.http.Status
import play.api.libs.json.Json
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
      )
    ),
    result = req => Json.obj("forwarded" -> req.headers.filter(_.is("forwarded")).map(_.value))
  ).futureValue

  val domain = route.frontend.domains.head.domain

  // every occurrence of the header received by the backend
  def forwarded(headers: (String, String)*): Seq[String] = {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(Seq("Host" -> domain) ++ headers*)
      .get()
      .futureValue
    resp.status mustBe Status.OK
    (Json.parse(resp.body[String]) \ "forwarded").as[Seq[String]]
  }

  val initialTrustXForwarded = env.datastores.globalConfigDataStore.latest().trustXForwarded

  try {
    updateGlobalConfig(_.copy(trustXForwarded = true))(_.trustXForwarded)

    forwarded() mustBe Seq(s"for=127.0.0.1;host=$domain;proto=http")

    forwarded(
      "X-Forwarded-For"   -> "192.0.2.15, 2001:db8::7",
      "X-Forwarded-Proto" -> "https"
    ) mustBe Seq(s"""for=192.0.2.15;host=$domain;proto=https, for="[2001:db8::7]", for=127.0.0.1""")

    // the chain of the proxies in front of otoroshi is kept, whatever the case of the header name
    forwarded(
      "forwarded"       -> "for=192.0.2.15;proto=https",
      "X-Forwarded-For" -> "192.0.2.15"
    ) mustBe Seq("for=192.0.2.15;proto=https, for=127.0.0.1")

    updateGlobalConfig(_.copy(trustXForwarded = false))(!_.trustXForwarded)

    // nothing the client sent is kept
    forwarded(
      "forwarded"         -> "for=192.0.2.15;proto=https",
      "X-Forwarded-For"   -> "192.0.2.15",
      "X-Forwarded-Proto" -> "https"
    ) mustBe Seq(s"for=127.0.0.1;host=$domain;proto=http")
  } finally {
    updateGlobalConfig(_.copy(trustXForwarded = initialTrustXForwarded))(_.trustXForwarded == initialTrustXForwarded)
    deleteOtoroshiRoute(route).futureValue
  }
}
