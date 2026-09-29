package functional

import otoroshi.utils.http.WSProxyServerUtils
import play.api.libs.ws.DefaultWSProxyServer

// the proxy of the pekko client and of websockets: the configured one for every host, except its non proxy hosts
class WSProxyServerUtilsSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  private val proxy = DefaultWSProxyServer(
    host = "proxy.oto.tools",
    port = 3128,
    nonProxyHosts = Some(Seq("internal.oto.tools", "*.local"))
  )

  "WSProxyServerUtils.proxyFor" should {

    "use the proxy for a host that is not one of its non proxy hosts" in {
      WSProxyServerUtils.proxyFor(Some(proxy), "api.oto.tools") mustBe Some(proxy)
    }

    "not use the proxy for its non proxy hosts" in {
      WSProxyServerUtils.proxyFor(Some(proxy), "internal.oto.tools") mustBe None
      WSProxyServerUtils.proxyFor(Some(proxy), "db.local") mustBe None
    }

    "use a proxy without non proxy hosts for every host" in {
      val everywhere = proxy.copy(nonProxyHosts = None)
      WSProxyServerUtils.proxyFor(Some(everywhere), "internal.oto.tools") mustBe Some(everywhere)
    }

    "use no proxy when none is configured" in {
      WSProxyServerUtils.proxyFor(None, "api.oto.tools") mustBe None
    }
  }
}
