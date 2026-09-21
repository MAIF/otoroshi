package plugins

import functional.PluginsTestSpecBase
import otoroshi.models.{GlobalConfig, IpFiltering}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{IpAddressBlockList, NgIpAddressBlockListConfig, OverrideHost}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

// a blocked address hidden in the proxy chain, behind a client address that is not blocked. without
// trusted proxies, the client address is the leftmost entry of X-Forwarded-For
class IpAddressBlockListChainTests(parent: PluginsTestSpecBase) {
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

  def routeBlocking(matchForwardedChain: Boolean) = createLocalRoute(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[IpAddressBlockList],
        config = NgPluginInstanceConfig(
          NgIpAddressBlockListConfig(Seq("1.1.1.1"), matchForwardedChain).json.as[JsObject]
        )
      )
    ),
    result = _ => Json.obj("message" -> "hello")
  ).futureValue

  def call(domain: String, xForwardedFor: String): Int = {
    ws.url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders("Host" -> domain, "X-Forwarded-For" -> xForwardedFor)
      .get()
      .futureValue
      .status
  }

  val initial = env.datastores.globalConfigDataStore.latest()

  val onlyResolved = routeBlocking(matchForwardedChain = false)
  val wholeChain   = routeBlocking(matchForwardedChain = true)

  try {
    updateGlobalConfig(_.copy(trustXForwarded = true))(_.trustXForwarded)

    // the plugin
    call(onlyResolved.frontend.domains.head.domain, "5.5.5.5, 1.1.1.1") mustBe Status.OK
    call(onlyResolved.frontend.domains.head.domain, "1.1.1.1") mustBe Status.FORBIDDEN
    call(wholeChain.frontend.domains.head.domain, "5.5.5.5, 1.1.1.1") mustBe Status.FORBIDDEN
    call(wholeChain.frontend.domains.head.domain, "5.5.5.5, 2.2.2.2") mustBe Status.OK

    // the ip filtering of the global config, applied to every route
    updateGlobalConfig(_.copy(ipFiltering = IpFiltering(blacklist = Seq("3.3.3.3"))))(
      _.ipFiltering.blacklist == Seq("3.3.3.3")
    )
    call(onlyResolved.frontend.domains.head.domain, "5.5.5.5, 3.3.3.3") mustBe Status.OK
    updateGlobalConfig(
      _.copy(ipFiltering = IpFiltering(blacklist = Seq("3.3.3.3"), blacklistMatchesForwardedChain = true))
    )(_.ipFiltering.blacklistMatchesForwardedChain)
    call(onlyResolved.frontend.domains.head.domain, "5.5.5.5, 3.3.3.3") mustBe Status.FORBIDDEN
    call(onlyResolved.frontend.domains.head.domain, "5.5.5.5, 2.2.2.2") mustBe Status.OK

    // nothing but the connection is read when the forwarded headers are not trusted
    updateGlobalConfig(_.copy(trustXForwarded = false))(!_.trustXForwarded)
    call(onlyResolved.frontend.domains.head.domain, "5.5.5.5, 3.3.3.3") mustBe Status.OK
  } finally {
    updateGlobalConfig(_.copy(trustXForwarded = initial.trustXForwarded, ipFiltering = initial.ipFiltering))(c =>
      c.trustXForwarded == initial.trustXForwarded && c.ipFiltering == initial.ipFiltering
    )
    deleteOtoroshiRoute(onlyResolved).futureValue
    deleteOtoroshiRoute(wholeChain).futureValue
  }
}
