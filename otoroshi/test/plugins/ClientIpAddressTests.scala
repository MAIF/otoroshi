package plugins

import functional.PluginsTestSpecBase
import org.apache.pekko.actor.{Actor, Props}
import org.joda.time.DateTime
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.events.{GatewayEvent, RevokedApiKeyUsageAlert}
import otoroshi.models.{ApiKey, GlobalConfig, IpAddressHash, ServiceGroupIdentifier, Target}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  AdditionalHeadersOut,
  IpAddressAllowedList,
  NgHeaderValuesConfig,
  NgIpAddressesConfig,
  OverrideHost
}
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.{ClientIpAddress, TypedMap}
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{AnyContentAsEmpty, Headers, RequestHeader}
import play.api.test.FakeRequest

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

// the client address of a request is resolved once, when the request enters the proxy engine, and
// every consumer of that request reads that one: the checks and the plugins taking a decision on it,
// the expression language, the load balancing, the events and the alerts. a change of the global
// config while the request is handled must not make them disagree. the spec runs with 10.0.0.0/8 as
// trusted proxies, which trusts the loopback too
class ClientIpAddressTests(parent: PluginsTestSpecBase) {
  import parent.*

  // a request that went through two trusted proxies, carrying an address the client prepended itself
  private val forwardedRequest: RequestHeader = FakeRequest(
    "GET",
    "/api",
    Headers(
      "Host"            -> "client-ip.oto.tools",
      "X-Forwarded-For" -> "9.9.9.9, 1.1.1.1, 10.0.0.2",
      "Forwarded"       -> "for=6.6.6.6, for=10.0.0.3"
    ),
    AnyContentAsEmpty,
    remoteAddress = "10.0.0.1"
  )

  // writes the global config, then waits for the cached copy the proxy reads, which is refreshed
  // asynchronously after the write. it asserts nothing, as it also runs on the backend side
  private def writeGlobalConfig(update: GlobalConfig => GlobalConfig)(applied: GlobalConfig => Boolean): Boolean = {
    val current  = env.datastores.globalConfigDataStore.latest()
    Await.result(env.datastores.globalConfigDataStore.set(update(current)), 10.seconds)
    val deadline = System.currentTimeMillis() + 10000
    while (!env.datastores.globalConfigDataStore.latestSafe.exists(applied) && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }
    env.datastores.globalConfigDataStore.latestSafe.exists(applied)
  }

  private def setTrustXForwarded(enabled: Boolean): Boolean = {
    writeGlobalConfig(_.copy(trustXForwarded = enabled))(_.trustXForwarded == enabled)
  }

  private def el(expression: String, request: RequestHeader): String = {
    GlobalExpressionLanguage.apply(
      value = expression,
      req = Some(request),
      service = None,
      route = None,
      apiKey = None,
      user = None,
      context = Map.empty,
      attrs = TypedMap.empty,
      env = env,
      plan = None,
      api = None
    )
  }

  private def revokedApiKeyAlert(request: RequestHeader): RevokedApiKeyUsageAlert = {
    RevokedApiKeyUsageAlert(
      "alert_client_ip",
      DateTime.now(),
      "test",
      request,
      ApiKey(
        clientId = "client-ip-apikey",
        clientSecret = "secret",
        clientName = "client-ip-apikey",
        authorizedEntities = Seq(ServiceGroupIdentifier("default"))
      ),
      None,
      env
    )
  }

  def resolveAgainstASingleGlobalConfig(): Unit = {
    val current = env.datastores.globalConfigDataStore.latest()
    // the policy is the one of the config handed over, not the one otoroshi currently runs with
    forwardedRequest.resolveClientIpAddress(Some(current)) mustBe ClientIpAddress(
      "1.1.1.1",
      "1.1.1.1",
      Seq("9.9.9.9", "1.1.1.1", "10.0.0.2")
    )
    forwardedRequest.resolveClientIpAddress(Some(current.copy(trustXForwarded = false))) mustBe ClientIpAddress(
      "10.0.0.1",
      "10.0.0.1",
      Seq.empty
    )
    forwardedRequest
      .resolveClientIpAddress(Some(current.copy(clientAddressHeader = "Forwarded")))
      .address mustBe "6.6.6.6"
    // 1.1.1.1 becomes a trusted proxy, the client is the hop before it
    forwardedRequest
      .resolveClientIpAddress(Some(current.copy(trustedProxies = Seq("1.1.1.1"))))
      .address mustBe "9.9.9.9"
    // the legacy resolution changes the address the request is handled with, not the safe one
    forwardedRequest.resolveClientIpAddress(Some(current.copy(useLegacyClientIpAddress = true))) mustBe ClientIpAddress(
      "9.9.9.9",
      "1.1.1.1",
      Seq("9.9.9.9", "1.1.1.1", "10.0.0.2")
    )
    // without a config, nothing but the connection is trusted
    forwardedRequest.resolveClientIpAddress(None).address mustBe "10.0.0.1"
    forwardedRequest.theIpAddress mustBe "1.1.1.1"
  }

  def keepTheAddressAttachedToTheRequest(): Unit = {
    val resolved                = forwardedRequest.clientIpAddressFor(env.datastores.globalConfigDataStore.latest())
    val attached: RequestHeader = forwardedRequest.addAttr(otoroshi.plugins.Keys.ClientIpAddressKey, resolved)
    val targets                 = (1 to 16).map(idx => Target(host = s"target-$idx.oto.tools"))
    // created before the change, exported after it
    val alertBeforeTheChange    = revokedApiKeyAlert(forwardedRequest)

    resolved.address mustBe "1.1.1.1"
    try {
      setTrustXForwarded(false) mustBe true

      // a request that does not carry its address follows the global config
      forwardedRequest.theIpAddress mustBe "10.0.0.1"
      el("${req.ip}", forwardedRequest) mustBe "10.0.0.1"

      // the one carrying it keeps the address it was handled with, whoever reads it
      attached.theIpAddress mustBe "1.1.1.1"
      attached.ipSafe mustBe "1.1.1.1"
      attached.theIpAddress(TypedMap.empty) mustBe "1.1.1.1"
      attached.ipSafe(TypedMap.empty) mustBe "1.1.1.1"
      // the proxy chain comes from the same resolution, although the forwarded headers are no longer
      // trusted
      attached.addressesSeen(TypedMap.empty) mustBe Seq("1.1.1.1", "9.9.9.9", "1.1.1.1", "10.0.0.2")
      el("${req.ip}", attached) mustBe "1.1.1.1"
      el("${req.ip_address}", attached) mustBe "1.1.1.1"
      el("${req.ip_safe}", attached) mustBe "1.1.1.1"
      IpAddressHash.select("req", "tracking", attached, targets, "route", 1) mustBe IpAddressHash.select(
        "req",
        "tracking",
        FakeRequest("GET", "/api", Headers(), AnyContentAsEmpty, remoteAddress = "1.1.1.1"),
        targets,
        "route",
        1
      )
      (revokedApiKeyAlert(attached).toJson(using env) \ "from").as[String] mustBe "1.1.1.1"
      (alertBeforeTheChange.toJson(using env) \ "from").as[String] mustBe "1.1.1.1"

      // entering the engine again does not resolve it a second time
      attached.clientIpAddressFor(env.datastores.globalConfigDataStore.latest()) mustBe resolved
    } finally {
      setTrustXForwarded(true) mustBe true
    }
  }

  def memoizeTheAddressInAttrs(): Unit = {
    val attrs = TypedMap.empty
    try {
      forwardedRequest.theIpAddress(attrs) mustBe "1.1.1.1"
      setTrustXForwarded(false) mustBe true
      forwardedRequest.theIpAddress mustBe "10.0.0.1"
      // the consumers given the same attrs read the first resolution
      forwardedRequest.theIpAddress(attrs) mustBe "1.1.1.1"
      forwardedRequest.ipSafe(attrs) mustBe "1.1.1.1"
      forwardedRequest.addressesSeen(attrs) mustBe Seq("1.1.1.1", "9.9.9.9", "1.1.1.1", "10.0.0.2")
    } finally {
      setTrustXForwarded(true) mustBe true
    }
  }

  // the backend stops trusting the forwarded headers while it handles the request: the plugin
  // letting the request in, the response header computed after the backend answered and the event
  // emitted at the end must all designate the same client
  def agreeOnTheAddressThroughoutTheRequest(): Unit = {
    val events       = new ConcurrentLinkedQueue[GatewayEvent]()
    val collector    = env.analyticsActorSystem.actorOf(Props(new ClientIpAddressTests.EventsCollector(events)))
    env.analyticsActorSystem.eventStream.subscribe(collector, classOf[GatewayEvent])
    val changeConfig = new AtomicBoolean(false)
    val configChanged = new AtomicBoolean(false)

    val route = createLocalRoute(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[IpAddressAllowedList],
          config = NgPluginInstanceConfig(NgIpAddressesConfig(Seq("1.1.1.1")).json.as[JsObject])
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
          config = NgPluginInstanceConfig(
            NgHeaderValuesConfig(Map("X-Client-Ip" -> "${req.ip}")).json.as[JsObject]
          )
        )
      ),
      result = _ => {
        if (changeConfig.compareAndSet(true, false)) {
          configChanged.set(setTrustXForwarded(false))
        }
        Json.obj("message" -> "hello")
      }
    ).futureValue
    val domain = route.frontend.domains.head.domain

    def call() = {
      ws.url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders("Host" -> domain, "X-Forwarded-For" -> "9.9.9.9, 1.1.1.1")
        .get()
        .futureValue
    }

    def eventWithStatus(status: Int): GatewayEvent = {
      val deadline = System.currentTimeMillis() + 10000
      def find()   = events.asScala.find(e => e.route.exists(_.id == route.id) && e.status == status)
      while (find().isEmpty && System.currentTimeMillis() < deadline) {
        Thread.sleep(50)
      }
      find().getOrElse(throw new RuntimeException(s"no gateway event with status $status for ${route.id}"))
    }

    try {
      setTrustXForwarded(true) mustBe true

      // the loopback is a trusted proxy, the client is the last untrusted hop of the chain
      changeConfig.set(true)
      val allowed = call()
      configChanged.get() mustBe true
      allowed.status mustBe Status.OK
      allowed.header("X-Client-Ip") mustBe Some("1.1.1.1")
      eventWithStatus(Status.OK).from mustBe "1.1.1.1"

      // the next request is handled with the new config: only the connection is read, and the
      // decision and the event still agree
      val denied = call()
      denied.status mustBe Status.FORBIDDEN
      eventWithStatus(Status.FORBIDDEN).from mustBe "127.0.0.1"
    } finally {
      env.analyticsActorSystem.eventStream.unsubscribe(collector)
      env.analyticsActorSystem.stop(collector)
      setTrustXForwarded(true) mustBe true
      deleteOtoroshiRoute(route).futureValue
    }
  }
}

object ClientIpAddressTests {
  class EventsCollector(events: ConcurrentLinkedQueue[GatewayEvent]) extends Actor {
    override def receive: Receive = {
      case event: GatewayEvent => events.add(event)
      case _                   => ()
    }
  }
}
