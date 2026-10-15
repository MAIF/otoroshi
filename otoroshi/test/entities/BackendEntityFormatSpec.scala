package entities

import org.apache.pekko.stream.OverflowStrategy
import otoroshi.models.*
import otoroshi.next.models.*
import otoroshi.utils.http.{CacheConnectionSettings, MtlsConfig}
import play.api.libs.json.*
import play.api.libs.ws.DefaultWSProxyServer

import java.util.concurrent.atomic.AtomicInteger

class BackendEntityFormatSpec extends EntityFormatSpec {

  // the hash load balancings keep their header, cookie or query name in a private field
  private def hashedName(lb: LoadBalancing): String = {
    val field = lb.getClass.getDeclaredFields.find(_.getType == classOf[String]).get
    field.setAccessible(true)
    field.get(lb).asInstanceOf[String]
  }

  val defaultTlsConfigJson: JsObject = Json.obj(
    "certs"         -> Json.arr(),
    "trusted_certs" -> Json.arr(),
    "enabled"       -> false,
    "loose"         -> false,
    "trust_all"     -> false
  )

  val defaultClientJson: JsObject = Json.obj(
    "retries"                   -> 1,
    "max_errors"                -> 20,
    "retry_initial_delay"       -> 50,
    "backoff_factor"            -> 2,
    "call_timeout"              -> 30000,
    "call_and_stream_timeout"   -> 120000,
    "connection_timeout"        -> 10000,
    "idle_timeout"              -> 60000,
    "global_timeout"            -> 30000,
    "sample_interval"           -> 2000,
    "proxy"                     -> Json.obj(),
    "custom_timeouts"           -> Json.arr(),
    "cache_connection_settings" -> Json.obj("enabled" -> false, "queue_size" -> 2048)
  )

  val fullProxy: DefaultWSProxyServer = DefaultWSProxyServer(
    host = "proxy.example.com",
    port = 3128,
    protocol = Some("https"),
    principal = Some("user"),
    password = Some("secret"),
    ntlmDomain = Some("corp"),
    encoding = Some("UTF-8"),
    nonProxyHosts = Some(Seq("localhost", "*.internal"))
  )

  val fullCustomTimeouts: NgCustomTimeouts = NgCustomTimeouts(
    path = "/slow/*",
    connectionTimeout = 1000,
    idleTimeout = 2000,
    callAndStreamTimeout = 3000,
    callTimeout = 4000,
    globalTimeout = 5000
  )

  val fullClient: NgClientConfig = NgClientConfig(
    retries = 3,
    maxErrors = 10,
    retryInitialDelay = 100,
    backoffFactor = 3,
    connectionTimeout = 5000,
    idleTimeout = 30000,
    callAndStreamTimeout = 60000,
    callTimeout = 15000,
    globalTimeout = 20000,
    sampleInterval = 1000,
    proxy = Some(fullProxy),
    customTimeouts = Seq(fullCustomTimeouts, NgCustomTimeouts()),
    cacheConnectionSettings = NgCacheConnectionSettings(enabled = true, queueSize = 512)
  )

  val fullTlsConfig: NgTlsConfig = NgTlsConfig(
    certs = Seq("cert-1", "cert-2"),
    trustedCerts = Seq("ca-1"),
    enabled = true,
    loose = true,
    trustAll = true
  )

  val fullHealthCheck: HealthCheck = HealthCheck(
    enabled = true,
    url = "https://api.example.com/health",
    timeout = 2000,
    healthyStatuses = Seq(200, 204),
    unhealthyStatuses = Seq(500),
    blockOnRed = true,
    logicCheck = false,
    healthyRegexChecks = Seq("ok"),
    unhealthyRegexChecks = Seq("ko")
  )

  val minimalTarget: NgTarget = NgTarget(id = "target-1", hostname = "api.example.com", port = 443, tls = true)

  val fullTarget: NgTarget = NgTarget(
    id = "target-2",
    hostname = "backup.example.com",
    port = 8443,
    tls = true,
    weight = 3,
    protocol = HttpProtocols.HTTP_2_0,
    predicate = NetworkLocationMatch(Some("aws"), Some("eu-west-1"), Some("eu-west-1a"), Some("dc1"), Some("rack-1")),
    ipAddress = Some("10.0.0.1"),
    tlsConfig = fullTlsConfig,
    backup = true
  )

  val fullBackend: NgBackend = NgBackend(
    targets = Seq(minimalTarget, fullTarget),
    root = "/api",
    rewrite = true,
    loadBalancing = WeightedBestResponseTime(0.7),
    healthCheck = Some(fullHealthCheck),
    client = fullClient
  )

  "NgCustomTimeouts" should {

    val format = NgCustomTimeouts.format

    "write every timeout under its snake case key" in {
      format.writes(NgCustomTimeouts()) mustBe Json.obj(
        "path"                    -> "/*",
        "call_timeout"            -> 30000,
        "call_and_stream_timeout" -> 3600000,
        "connection_timeout"      -> 10000,
        "idle_timeout"            -> 60000,
        "global_timeout"          -> 30000
      )
    }

    "round trip the default timeouts" in {
      roundTrip(format, NgCustomTimeouts())
    }

    "round trip timeouts where every field is set" in {
      roundTrip(format, fullCustomTimeouts)
    }

    "read an empty object with the default timeouts and the * path, not the /* default path" in {
      readOk(format, Json.obj()) mustBe NgCustomTimeouts(path = "*")
    }

    "read a blank path as the * path" in {
      readOk(format, Json.obj("path" -> "  ")).path mustBe "*"
    }

    "read a json null with the default timeouts" in {
      readOk(format, JsNull) mustBe NgCustomTimeouts(path = "*")
    }

    "ignore timeouts that are not numbers" in {
      readOk(format, Json.obj("path" -> "/a", "call_timeout" -> "1000", "idle_timeout" -> true)) mustBe NgCustomTimeouts(
        path = "/a"
      )
    }

    "not read the camel case keys of the legacy custom timeouts" in {
      readOk(format, Json.obj("path" -> "/a", "callTimeout" -> 1)).callTimeout mustBe 30000
    }

    "expose through json what the format writes" in {
      fullCustomTimeouts.json mustBe format.writes(fullCustomTimeouts)
    }

    "convert to the legacy custom timeouts and back" in {
      fullCustomTimeouts.legacy mustBe CustomTimeouts("/slow/*", 1000, 2000, 3000, 4000, 5000)
      NgCustomTimeouts.fromLegacy(fullCustomTimeouts.legacy) mustBe fullCustomTimeouts
    }
  }

  "NgCacheConnectionSettings and NgOverflowStrategy" should {

    "write the enabled flag and the queue size only" in {
      NgCacheConnectionSettings().json mustBe Json.obj("enabled" -> false, "queue_size" -> 2048)
    }

    "drop the tail of the queue by default" in {
      NgCacheConnectionSettings().strategy mustBe NgOverflowStrategy.dropTail
    }

    "convert to the legacy settings and back" in {
      val settings = NgCacheConnectionSettings(enabled = true, queueSize = 10, strategy = NgOverflowStrategy.backpressure)
      settings.legacy mustBe CacheConnectionSettings(true, 10, NgOverflowStrategy.backpressure)
      NgCacheConnectionSettings.fromLegacy(settings.legacy) mustBe settings
    }

    "map each strategy to the pekko one" in {
      NgOverflowStrategy.dropHead.toAkka mustBe OverflowStrategy.dropHead
      NgOverflowStrategy.dropTail.toAkka mustBe OverflowStrategy.dropTail
      NgOverflowStrategy.dropBuffer.toAkka mustBe OverflowStrategy.dropBuffer
      NgOverflowStrategy.backpressure.toAkka mustBe OverflowStrategy.backpressure
      NgOverflowStrategy.fail.toAkka mustBe OverflowStrategy.fail
    }
  }

  "NgClientConfig" should {

    val format = NgClientConfig.format

    "write the default client config" in {
      format.writes(NgClientConfig.default) mustBe defaultClientJson
    }

    "be the default config when built without argument" in {
      NgClientConfig() mustBe NgClientConfig.default
    }

    "round trip the default client config" in {
      roundTrip(format, NgClientConfig.default)
    }

    "round trip a client config where every field is set" in {
      roundTrip(format, fullClient)
    }

    "write a proxy with every field" in {
      (format.writes(fullClient) \ "proxy").as[JsObject] mustBe Json.obj(
        "host"          -> "proxy.example.com",
        "port"          -> 3128,
        "protocol"      -> "https",
        "principal"     -> "user",
        "password"      -> "secret",
        "ntlmDomain"    -> "corp",
        "encoding"      -> "UTF-8",
        "nonProxyHosts" -> Json.arr("localhost", "*.internal")
      )
    }

    "write the unset fields of a proxy as json nulls" in {
      (format.writes(NgClientConfig(proxy = Some(DefaultWSProxyServer("p", 80)))) \ "proxy").as[JsObject] mustBe Json.obj(
        "host"          -> "p",
        "port"          -> 80,
        "protocol"      -> JsNull,
        "principal"     -> JsNull,
        "password"      -> JsNull,
        "ntlmDomain"    -> JsNull,
        "encoding"      -> JsNull,
        "nonProxyHosts" -> JsNull
      )
    }

    "round trip a proxy with only a host and a port" in {
      roundTrip(format, NgClientConfig(proxy = Some(DefaultWSProxyServer("p", 80)))).proxy mustBe Some(
        DefaultWSProxyServer("p", 80)
      )
    }

    "round trip a proxy without non proxy host" in {
      roundTrip(format, NgClientConfig(proxy = Some(fullProxy.copy(nonProxyHosts = Some(Seq.empty)))))
    }

    "read an empty object as the default client config" in {
      readOk(format, Json.obj()) mustBe NgClientConfig.default
    }

    "read a json null as the default client config" in {
      readOk(format, JsNull) mustBe NgClientConfig.default
    }

    "ignore numbers given as strings" in {
      readOk(format, Json.obj("retries" -> "3", "max_errors" -> "10", "call_timeout" -> "1")) mustBe NgClientConfig.default
    }

    "read a proxy without port as no proxy" in {
      readOk(format, Json.obj("proxy" -> Json.obj("host" -> "p"))).proxy mustBe None
    }

    "read a proxy with a blank host as no proxy" in {
      readOk(format, Json.obj("proxy" -> Json.obj("host" -> " ", "port" -> 80))).proxy mustBe None
    }

    "read the blank optional fields of a proxy as unset" in {
      val json = Json.obj("proxy" -> Json.obj("host" -> "p", "port" -> 80, "protocol" -> "", "principal" -> " "))
      readOk(format, json).proxy mustBe Some(DefaultWSProxyServer("p", 80))
    }

    "read the cache connection settings with the drop tail strategy whatever the json says" in {
      val json = Json.obj("cache_connection_settings" -> Json.obj("enabled" -> true, "queue_size" -> 8, "strategy" -> "dropHead"))
      readOk(format, json).cacheConnectionSettings mustBe NgCacheConnectionSettings(true, 8, NgOverflowStrategy.dropTail)
    }

    "read custom timeouts that are not objects with the default timeouts" in {
      readOk(format, Json.obj("custom_timeouts" -> Json.arr("x"))).customTimeouts mustBe Seq(NgCustomTimeouts(path = "*"))
    }

    "ignore custom timeouts that are not an array" in {
      readOk(format, Json.obj("custom_timeouts" -> Json.obj("path" -> "/a"))).customTimeouts mustBe empty
    }

    "keep the order of the custom timeouts" in {
      val timeouts = Seq(NgCustomTimeouts(path = "/b"), NgCustomTimeouts(path = "/a"))
      roundTrip(format, NgClientConfig(customTimeouts = timeouts)).customTimeouts.map(_.path) mustBe Seq("/b", "/a")
    }

    "not read the camel case keys of the legacy client config" in {
      readOk(format, Json.obj("maxErrors" -> 1, "callTimeout" -> 1)) mustBe NgClientConfig.default
    }

    "expose through json what the format writes" in {
      fullClient.json mustBe format.writes(fullClient)
    }

    "convert to the legacy client config and back" in {
      val legacy = fullClient.legacy
      legacy.retries mustBe 3
      legacy.proxy mustBe Some(fullProxy)
      legacy.customTimeouts mustBe fullClient.customTimeouts.map(_.legacy)
      legacy.useCircuitBreaker mustBe true
      NgClientConfig.fromLegacy(legacy) mustBe fullClient
    }
  }

  "NgTlsConfig" should {

    val format = NgTlsConfig.format

    "write the default tls config" in {
      format.writes(NgTlsConfig.default) mustBe defaultTlsConfigJson
    }

    "round trip the default tls config" in {
      roundTrip(format, NgTlsConfig())
    }

    "round trip a tls config where every field is set" in {
      roundTrip(format, fullTlsConfig)
    }

    "read an empty object as the default tls config" in {
      readOk(format, Json.obj()) mustBe NgTlsConfig.default
    }

    "read a json null as the default tls config" in {
      readOk(format, JsNull) mustBe NgTlsConfig.default
    }

    "read a legacy certId as the only cert" in {
      readOk(format, Json.obj("certId" -> "cert-1")).certs mustBe Seq("cert-1")
    }

    "read a legacy cert_id as the only cert" in {
      readOk(format, Json.obj("cert_id" -> "cert-1")).certs mustBe Seq("cert-1")
    }

    "prefer certs over certId, and certId over cert_id" in {
      readOk(format, Json.obj("certs" -> Json.arr("a"), "certId" -> "b", "cert_id" -> "c")).certs mustBe Seq("a")
      readOk(format, Json.obj("certId" -> "b", "cert_id" -> "c")).certs mustBe Seq("b")
    }

    "write the certs read from a legacy key under the certs key" in {
      val json = format.writes(readOk(format, Json.obj("certId" -> "cert-1")))
      (json \ "certs").as[Seq[String]] mustBe Seq("cert-1")
      (json \ "certId").isDefined mustBe false
    }

    "drop the blank cert ids" in {
      val back = readOk(format, Json.obj("certs" -> Json.arr("", "a", " "), "trusted_certs" -> Json.arr(" ", "b")))
      back.certs mustBe Seq("a")
      back.trustedCerts mustBe Seq("b")
    }

    "ignore certs given as a single string" in {
      readOk(format, Json.obj("certs" -> "cert-1")).certs mustBe empty
    }

    "ignore flags that are not booleans" in {
      readOk(format, Json.obj("enabled" -> "true", "loose" -> 1, "trust_all" -> "yes")) mustBe NgTlsConfig.default
    }

    "be legit with certs, trusted certs, loose or trust all, but not when only enabled" in {
      NgTlsConfig().legit mustBe false
      NgTlsConfig(enabled = true).legit mustBe false
      NgTlsConfig(certs = Seq("a")).legit mustBe true
      NgTlsConfig(trustedCerts = Seq("a")).legit mustBe true
      NgTlsConfig(loose = true).legit mustBe true
      NgTlsConfig(trustAll = true).legit mustBe true
    }

    "expose through json what the format writes" in {
      fullTlsConfig.json mustBe format.writes(fullTlsConfig)
    }

    "convert to the legacy mtls config and back" in {
      fullTlsConfig.legacy mustBe MtlsConfig(Seq("cert-1", "cert-2"), Seq("ca-1"), mtls = true, loose = true, trustAll = true)
      NgTlsConfig.fromLegacy(fullTlsConfig.legacy) mustBe fullTlsConfig
    }
  }

  "HttpProtocols" should {

    "parse the known protocols whatever their case" in {
      HttpProtocols.parse("http/1.0") mustBe HttpProtocols.HTTP_1_0
      HttpProtocols.parse("HTTP/1.1") mustBe HttpProtocols.HTTP_1_1
      HttpProtocols.parse(" Http/2.0 ") mustBe HttpProtocols.HTTP_2_0
      HttpProtocols.parse("HTTP/3.0") mustBe HttpProtocols.HTTP_3_0
    }

    "parse an unknown protocol as http 1.1" in {
      HttpProtocols.parseSafe("HTTP/2") mustBe None
      HttpProtocols.parse("HTTP/2") mustBe HttpProtocols.HTTP_1_1
    }

    "tell the http version of a protocol" in {
      HttpProtocols.HTTP_1_0.isHttp1 mustBe true
      HttpProtocols.HTTP_1_1.isHttp2OrHttp3 mustBe false
      HttpProtocols.HTTP_2_0.isHttp2 mustBe true
      HttpProtocols.HTTP_3_0.isHttp3 mustBe true
      HttpProtocols.HTTP_3_0.isHttp2OrHttp3 mustBe true
    }

    "map http 3 to the pekko http 2 protocol" in {
      HttpProtocols.HTTP_3_0.asAkka mustBe org.apache.pekko.http.scaladsl.model.HttpProtocols.`HTTP/2.0`
      HttpProtocol("whatever").asAkka mustBe org.apache.pekko.http.scaladsl.model.HttpProtocols.`HTTP/1.1`
    }

    "write a protocol as its value" in {
      HttpProtocols.HTTP_2_0.json mustBe JsString("HTTP/2.0")
    }
  }

  "TargetPredicate" should {

    val format = TargetPredicate.format

    "write and round trip always match" in {
      format.writes(AlwaysMatch) mustBe Json.obj("type" -> "AlwaysMatch")
      roundTrip(format, AlwaysMatch)
    }

    "write the positions of a geolocation match as lat:lng:radius" in {
      format.writes(GeolocationMatch(Seq(GeoPositionRadius(48.8566, 2.3522, 10.0)))) mustBe Json.obj(
        "type"      -> "GeolocationMatch",
        "positions" -> Json.arr("48.8566:2.3522:10.0")
      )
    }

    "round trip a geolocation match with several positions" in {
      roundTrip(format, GeolocationMatch(Seq(GeoPositionRadius(48.8566, 2.3522, 10.0), GeoPositionRadius(-33.86, 151.2, 2.5))))
    }

    "round trip a geolocation match without position" in {
      roundTrip(format, GeolocationMatch(Seq.empty))
    }

    "read the legacy lat;lng;radius positions" in {
      readOk(format, Json.obj("type" -> "GeolocationMatch", "positions" -> Json.arr("1.5;2.5;3"))) mustBe GeolocationMatch(
        Seq(GeoPositionRadius(1.5, 2.5, 3.0))
      )
    }

    "skip the positions that do not have three parts" in {
      readOk(format, Json.obj("type" -> "GeolocationMatch", "positions" -> Json.arr("1:2", "1:2:3:4", "1:2:3"))) mustBe
        GeolocationMatch(Seq(GeoPositionRadius(1.0, 2.0, 3.0)))
    }

    "throw on a position that is not numeric" in {
      a[NumberFormatException] must be thrownBy format.reads(Json.obj("type" -> "GeolocationMatch", "positions" -> Json.arr("a:b:c")))
    }

    "round trip a network location match where every field is set" in {
      roundTrip(format, NetworkLocationMatch(Some("aws"), Some("eu-west-1"), Some("a"), Some("dc1"), Some("r1")))
    }

    "write the unset fields of a network location match as json nulls, the data center under dc" in {
      format.writes(NetworkLocationMatch(dataCenter = Some("dc1"))) mustBe Json.obj(
        "type"     -> "NetworkLocationMatch",
        "provider" -> JsNull,
        "region"   -> JsNull,
        "zone"     -> JsNull,
        "dc"       -> "dc1",
        "rack"     -> JsNull
      )
    }

    "round trip a network location match without any field" in {
      roundTrip(format, NetworkLocationMatch())
    }

    "read an unknown type as always match" in {
      readOk(format, Json.obj("type" -> "Whatever")) mustBe AlwaysMatch
    }

    "read the region, zone, data center, provider and rack matches as always match" in {
      readOk(format, RegionMatch("eu").toJson) mustBe AlwaysMatch
      readOk(format, ZoneMatch("a").toJson) mustBe AlwaysMatch
      readOk(format, DataCenterMatch("dc1").toJson) mustBe AlwaysMatch
      readOk(format, InfraProviderMatch("aws").toJson) mustBe AlwaysMatch
      readOk(format, RackMatch("r1").toJson) mustBe AlwaysMatch
    }

    "throw instead of failing on a predicate without type" in {
      a[JsResultException] must be thrownBy format.reads(Json.obj())
    }

    "expose through json what toJson writes" in {
      val predicate = NetworkLocationMatch(Some("aws"))
      predicate.json mustBe predicate.toJson
    }
  }

  "LoadBalancing" should {

    val format = LoadBalancing.format

    "write each load balancing as its type" in {
      format.writes(RoundRobin) mustBe Json.obj("type" -> "RoundRobin")
      format.writes(Failover) mustBe Json.obj("type" -> "NaiveFailover")
      format.writes(PowerOfTwoRandomChoices) mustBe Json.obj("type" -> "PowerOfTwoRandomChoices")
      format.writes(LeastConnections) mustBe Json.obj("type" -> "LeastConnections")
      format.writes(otoroshi.models.Random) mustBe Json.obj("type" -> "Random")
      format.writes(Sticky) mustBe Json.obj("type" -> "Sticky")
      format.writes(IpAddressHash) mustBe Json.obj("type" -> "IpAddressHash")
      format.writes(BestResponseTime) mustBe Json.obj("type" -> "BestResponseTime")
    }

    "round trip every load balancing without parameter" in {
      Seq(
        RoundRobin,
        Failover,
        PowerOfTwoRandomChoices,
        LeastConnections,
        otoroshi.models.Random,
        Sticky,
        IpAddressHash,
        BestResponseTime
      ).foreach(lb => roundTrip(format, lb))
    }

    "write and round trip the ratio of the weighted best response time" in {
      format.writes(WeightedBestResponseTime(0.7)) mustBe Json.obj("type" -> "WeightedBestResponseTime", "ratio" -> 0.7)
      roundTrip(format, WeightedBestResponseTime(0.7))
    }

    "read a weighted best response time without ratio with a 0.5 ratio" in {
      readOk(format, Json.obj("type" -> "WeightedBestResponseTime")) mustBe WeightedBestResponseTime(0.5)
    }

    "read a weighted best response time with a ratio that is not a number with a 0.5 ratio" in {
      readOk(format, Json.obj("type" -> "WeightedBestResponseTime", "ratio" -> "0.7")) mustBe WeightedBestResponseTime(0.5)
    }

    "read the header, cookie and query names of the hash load balancings" in {
      val header = readOk(format, Json.obj("type" -> "HeaderHash", "header_name" -> "x-user"))
      val cookie = readOk(format, Json.obj("type" -> "CookieHash", "cookie_name" -> "session"))
      val query  = readOk(format, Json.obj("type" -> "QueryHash", "query_name" -> "user"))
      header mustBe a[HeaderHash]
      cookie mustBe a[CookieHash]
      query mustBe a[QueryHash]
      hashedName(header) mustBe "x-user"
      hashedName(cookie) mustBe "session"
      hashedName(query) mustBe "user"
    }

    "hash on session-id when the hash load balancings have no name" in {
      hashedName(readOk(format, Json.obj("type" -> "HeaderHash"))) mustBe "session-id"
      hashedName(readOk(format, Json.obj("type" -> "CookieHash"))) mustBe "session-id"
      hashedName(readOk(format, Json.obj("type" -> "QueryHash"))) mustBe "session-id"
    }

    "write the type of the hash load balancings" in {
      (format.writes(new HeaderHash("x-user")) \ "type").as[String] mustBe "HeaderHash"
      (format.writes(new CookieHash("session")) \ "type").as[String] mustBe "CookieHash"
      (format.writes(new QueryHash("user")) \ "type").as[String] mustBe "QueryHash"
    }

    "write the header, cookie and query names of the hash load balancings" in {
      format.writes(new HeaderHash("x-user")) mustBe Json.obj("type" -> "HeaderHash", "header_name" -> "x-user")
      format.writes(new CookieHash("session")) mustBe Json.obj("type" -> "CookieHash", "cookie_name" -> "session")
      format.writes(new QueryHash("user")) mustBe Json.obj("type" -> "QueryHash", "query_name" -> "user")
    }

    "keep the header, cookie and query names of the hash load balancings on a round trip" in {
      hashedName(readOk(format, format.writes(new HeaderHash("x-user")))) mustBe "x-user"
      hashedName(readOk(format, format.writes(new CookieHash("session")))) mustBe "session"
      hashedName(readOk(format, format.writes(new QueryHash("user")))) mustBe "user"
    }

    "keep the name of a hash load balancing through a backend round trip" in {
      val backend = NgBackend.empty.copy(loadBalancing = new HeaderHash("x-tenant"))
      hashedName(readOk(NgBackend.fmt, NgBackend.fmt.writes(backend)).loadBalancing) mustBe "x-tenant"
    }

    "read an unknown or a missing type as round robin" in {
      readOk(format, Json.obj("type" -> "Whatever")) mustBe RoundRobin
      readOk(format, Json.obj()) mustBe RoundRobin
    }

    "read the type case sensitively" in {
      readOk(format, Json.obj("type" -> "sticky")) mustBe RoundRobin
    }

    "read a json that is not an object as round robin" in {
      readOk(format, JsString("Sticky")) mustBe RoundRobin
      readOk(format, JsNull) mustBe RoundRobin
    }

    "be built from its type name" in {
      LoadBalancing.fromString("NaiveFailover") mustBe Failover
      LoadBalancing.fromString("Failover") mustBe RoundRobin
      LoadBalancing.fromString("WeightedBestResponseTime") mustBe WeightedBestResponseTime(0.5)
    }

    "need a tracking cookie only for sticky" in {
      Sticky.needTrackingCookie mustBe true
      RoundRobin.needTrackingCookie mustBe false
      IpAddressHash.needTrackingCookie mustBe false
      new CookieHash("c").needTrackingCookie mustBe false
    }

    "need the inflight requests only for least connections and power of two random choices" in {
      LeastConnections.needsInflightRequests mustBe true
      PowerOfTwoRandomChoices.needsInflightRequests mustBe true
      RoundRobin.needsInflightRequests mustBe false
      BestResponseTime.needsInflightRequests mustBe false
    }

    "keep the next index among the targets once the counter went past the max int" in {
      val counter = new AtomicInteger(Int.MaxValue)
      val index   = LoadBalancing.nextIndex(counter, 3)
      index must be >= 0
      index must be < 3
      LoadBalancing.nextIndex(new AtomicInteger(0), 3) mustBe 1
    }

    "give the index 0 when there is no target" in {
      LoadBalancing.nextIndex(new AtomicInteger(41), 0) mustBe 0
    }
  }

  "HealthCheck" should {

    val format = HealthCheck.format

    "write every field under its camel case key" in {
      format.writes(fullHealthCheck) mustBe Json.obj(
        "enabled"              -> true,
        "url"                  -> "https://api.example.com/health",
        "timeout"              -> 2000,
        "healthyStatuses"      -> Json.arr(200, 204),
        "unhealthyStatuses"    -> Json.arr(500),
        "blockOnRed"           -> true,
        "logicCheck"           -> false,
        "healthyRegexChecks"   -> Json.arr("ok"),
        "unhealthyRegexChecks" -> Json.arr("ko")
      )
    }

    "round trip a health check where every field is set" in {
      roundTrip(format, fullHealthCheck)
    }

    "round trip the empty health check" in {
      roundTrip(format, HealthCheck.empty) mustBe HealthCheck(false, "/")
    }

    "read an empty object as a disabled health check without url, with logic check" in {
      readOk(format, Json.obj()) mustBe HealthCheck(enabled = false, url = "", timeout = 5000, logicCheck = true)
    }

    "ignore fields with a wrong type" in {
      readOk(format, Json.obj("enabled" -> "true", "timeout" -> "1000", "healthyStatuses" -> Json.arr("200"))) mustBe
        HealthCheck(enabled = false, url = "")
    }
  }

  "NgTarget" should {

    val format = NgTarget.fmt

    "write a minimal target" in {
      format.writes(minimalTarget) mustBe Json.obj(
        "id"         -> "target-1",
        "hostname"   -> "api.example.com",
        "port"       -> 443,
        "tls"        -> true,
        "weight"     -> 1,
        "backup"     -> false,
        "predicate"  -> Json.obj("type" -> "AlwaysMatch"),
        "protocol"   -> "HTTP/1.1",
        "ip_address" -> JsNull,
        "tls_config" -> defaultTlsConfigJson
      )
    }

    "round trip a minimal target" in {
      roundTrip(format, minimalTarget)
    }

    "round trip a target where every field is set" in {
      roundTrip(format, fullTarget)
    }

    "round trip a target with a geolocation predicate" in {
      roundTrip(format, minimalTarget.copy(predicate = GeolocationMatch(Seq(GeoPositionRadius(1.0, 2.0, 3.0)))))
    }

    "read a lone hostname and port with the defaults" in {
      readOk(format, Json.obj("hostname" -> "api.example.com", "port" -> 8080)) mustBe NgTarget(
        id = "http://api.example.com:8080",
        hostname = "api.example.com",
        port = 8080,
        tls = false,
        weight = 1,
        protocol = HttpProtocols.HTTP_1_1,
        predicate = AlwaysMatch,
        ipAddress = None,
        tlsConfig = NgTlsConfig(),
        backup = false
      )
    }

    "build the missing id from the scheme, the hostname, the ip address and the port" in {
      readOk(format, Json.obj("hostname" -> "api.example.com", "port" -> 443, "tls" -> true, "ip_address" -> "10.0.0.1")).id mustBe
        "https://api.example.com@10.0.0.1:443"
    }

    "read a blank ip address as no ip address" in {
      readOk(format, Json.obj("hostname" -> "h", "port" -> 80, "ip_address" -> " ")).ipAddress mustBe None
    }

    "read a blank or an unknown protocol as http 1.1" in {
      readOk(format, Json.obj("hostname" -> "h", "port" -> 80, "protocol" -> "")).protocol mustBe HttpProtocols.HTTP_1_1
      readOk(format, Json.obj("hostname" -> "h", "port" -> 80, "protocol" -> "h2c")).protocol mustBe HttpProtocols.HTTP_1_1
    }

    "read the protocol whatever its case" in {
      readOk(format, Json.obj("hostname" -> "h", "port" -> 80, "protocol" -> "http/2.0")).protocol mustBe HttpProtocols.HTTP_2_0
    }

    "ignore the tls flag, the weight and the backup flag when they have a wrong type" in {
      val back = readOk(format, Json.obj("hostname" -> "h", "port" -> 80, "tls" -> "true", "weight" -> "2", "backup" -> 1))
      back.tls mustBe false
      back.weight mustBe 1
      back.backup mustBe false
    }

    "throw instead of failing on a target without hostname" in {
      a[JsResultException] must be thrownBy format.reads(Json.obj("port" -> 80))
    }

    "throw instead of failing on a target without port" in {
      a[JsResultException] must be thrownBy format.reads(Json.obj("hostname" -> "h"))
    }

    "throw instead of failing on a port that is not a number" in {
      a[JsResultException] must be thrownBy format.reads(Json.obj("hostname" -> "h", "port" -> "80"))
    }

    "expose through json what the format writes" in {
      fullTarget.json mustBe format.writes(fullTarget)
    }

    "point to www.otoroshi.io over tls by default" in {
      NgTarget.default mustBe NgTarget("www.otoroshi.io", "www.otoroshi.io", 443, true)
    }

    "be primary unless it is a backup" in {
      minimalTarget.isPrimary mustBe true
      minimalTarget.isSecondary mustBe false
      fullTarget.isPrimary mustBe false
      fullTarget.isSecondary mustBe true
    }

    "be parsed from an https url without port" in {
      NgTarget.parse("https://api.example.com/v1") mustBe NgTarget("api.example.com", "api.example.com", 443, true)
    }

    "be parsed from an http url with a port" in {
      NgTarget.parse("http://api.example.com:8080") mustBe NgTarget("api.example.com", "api.example.com", 8080, false)
    }

    "be parsed from an http url without port on port 80" in {
      NgTarget.parse("http://api.example.com").port mustBe 80
    }

    "build its base url from the ip address, without the default ports" in {
      minimalTarget.baseUrl mustBe "https://api.example.com"
      fullTarget.baseUrl mustBe "https://10.0.0.1:8443"
      NgTarget("t", "h", 80, false).baseUrl mustBe "http://h"
    }

    "convert to a legacy target" in {
      val target = fullTarget.toTarget
      target.host mustBe "backup.example.com:8443"
      target.scheme mustBe "https"
      target.weight mustBe 3
      target.protocol mustBe HttpProtocols.HTTP_2_0
      target.predicate mustBe fullTarget.predicate
      target.ipAddress mustBe Some("10.0.0.1")
      target.mtlsConfig mustBe fullTlsConfig.legacy
      target.tags mustBe Seq("target-2")
      target.backup mustBe true
      fullTarget.legacy mustBe target
    }

    "convert to a legacy target and back" in {
      NgTarget.fromTarget(fullTarget.toTarget) mustBe fullTarget
      NgTarget.fromLegacy(minimalTarget.legacy) mustBe minimalTarget
    }

    "take the host of a legacy target without tag as id" in {
      NgTarget.fromTarget(Target(host = "h:8080", scheme = "http")) mustBe NgTarget("h:8080", "h", 8080, false)
    }

    "leave the default port of the scheme out of the legacy target" in {
      NgTarget("t", "h", 443, true).toTarget.host mustBe "h"
      NgTarget("t", "h", 80, false).toTarget.host mustBe "h"
    }

    "keep in the legacy target a port that is not the default one of the scheme" in {
      NgTarget("t", "h", 80, true).toTarget.host mustBe "h:80"
      NgTarget("t", "h", 443, false).toTarget.host mustBe "h:443"
    }

    "keep in the base url a port that is not the default one of the scheme" in {
      NgTarget("t", "h", 80, true).baseUrl mustBe "https://h:80"
      NgTarget("t", "h", 443, false).baseUrl mustBe "http://h:443"
      NgTarget("t", "h", 443, true).baseUrl mustBe "https://h"
    }

    "call an https target on port 80 on port 80" in {
      NgTarget("t", "h", 80, true).toTarget.thePort mustBe 80
      NgTarget("t", "h", 443, false).toTarget.thePort mustBe 443
    }
  }

  "NgBackend" should {

    val format = NgBackend.fmt

    "write the empty backend" in {
      format.writes(NgBackend.empty) mustBe Json.obj(
        "targets"        -> Json.arr(),
        "root"           -> "/",
        "rewrite"        -> false,
        "load_balancing" -> Json.obj("type" -> "RoundRobin"),
        "client"         -> defaultClientJson,
        "health_check"   -> JsNull
      )
    }

    "round trip a backend where every field is set" in {
      roundTrip(format, fullBackend)
    }

    "round trip a backend with a disabled health check" in {
      roundTrip(format, NgBackend.empty.copy(healthCheck = Some(HealthCheck.empty)))
    }

    "round trip a backend with each load balancing" in {
      Seq(Failover, Sticky, LeastConnections, BestResponseTime).foreach { lb =>
        roundTrip(format, fullBackend.copy(loadBalancing = lb)).loadBalancing mustBe lb
      }
    }

    "write the health check when there is one" in {
      (format.writes(fullBackend) \ "health_check").as[JsObject] mustBe fullHealthCheck.toJson
    }

    "keep the order of the targets" in {
      roundTrip(format, fullBackend.copy(targets = Seq(fullTarget, minimalTarget))).targets.map(_.id) mustBe Seq(
        "target-2",
        "target-1"
      )
    }

    "read an empty object as the empty backend" in {
      readOk(format, Json.obj()) mustBe NgBackend.empty
    }

    "read a json that is not an object as the empty backend" in {
      readOk(format, JsNull) mustBe NgBackend.empty
      readOk(format, JsString("https://api.example.com")) mustBe NgBackend.empty
    }

    "read a url as a single target and its path as root" in {
      readOk(format, Json.obj("url" -> "https://api.example.com:8443/v1")) mustBe NgBackend.empty.copy(
        targets = Seq(NgTarget("api.example.com", "api.example.com", 8443, true)),
        root = "/v1"
      )
    }

    "read the target and target_url aliases of url" in {
      readOk(format, Json.obj("target" -> "http://a.example.com/a")).targets.map(_.hostname) mustBe Seq("a.example.com")
      readOk(format, Json.obj("target_url" -> "http://b.example.com/b")).root mustBe "/b"
    }

    "prefer url over target, and target over target_url" in {
      val all = Json.obj("url" -> "http://a.com/a", "target" -> "http://b.com/b", "target_url" -> "http://c.com/c")
      readOk(format, all).targets.map(_.hostname) mustBe Seq("a.com")
      readOk(format, all - "url").targets.map(_.hostname) mustBe Seq("b.com")
    }

    "let a url replace the targets and the root" in {
      val json = Json.obj("url" -> "http://a.com/a", "root" -> "/b", "targets" -> Json.arr(minimalTarget.json))
      val back = readOk(format, json)
      back.targets.map(_.hostname) mustBe Seq("a.com")
      back.root mustBe "/a"
    }

    "read a url without path as an empty root, ignoring the root key" in {
      readOk(format, Json.obj("url" -> "http://a.com", "root" -> "/b")).root mustBe ""
    }

    "write a backend read from a url with its targets and root" in {
      val json = format.writes(readOk(format, Json.obj("url" -> "http://a.com:8080/v1", "rewrite" -> true)))
      (json \ "url").isDefined mustBe false
      (json \ "root").as[String] mustBe "/v1"
      (json \ "targets" \ 0 \ "port").as[Int] mustBe 8080
      (json \ "rewrite").as[Boolean] mustBe true
    }

    "ignore fields with a wrong type" in {
      val json = Json.obj(
        "targets"        -> Json.obj("hostname" -> "h", "port" -> 80),
        "root"           -> 42,
        "rewrite"        -> "true",
        "load_balancing" -> "Sticky",
        "client"         -> "none"
      )
      readOk(format, json) mustBe NgBackend.empty
    }

    "throw instead of failing on a target without hostname" in {
      a[JsResultException] must be thrownBy format.reads(Json.obj("targets" -> Json.arr(Json.obj("port" -> 80))))
    }

    "list its distinct targets" in {
      fullBackend.copy(targets = Seq(minimalTarget, fullTarget, minimalTarget)).allTargets mustBe Seq(minimalTarget, fullTarget)
    }

    "build its minimal backend and back" in {
      val minimal = fullBackend.minimalBackend
      minimal mustBe NgMinimalBackend(fullBackend.targets, "/api", true, WeightedBestResponseTime(0.7))
      minimal.toBackend(fullClient, Some(fullHealthCheck)) mustBe fullBackend
    }

    "be read back from what readFrom reads" in {
      NgBackend.readFrom(Json.obj("backend" -> fullBackend.json) \ "backend") mustBe fullBackend
    }
  }

  "NgMinimalBackend" should {

    val minimal = NgMinimalBackend(Seq(minimalTarget), "/api", true, Sticky)

    "write its targets, root, rewrite flag and load balancing" in {
      minimal.json mustBe Json.obj(
        "targets"        -> Json.arr(minimalTarget.json),
        "root"           -> "/api",
        "rewrite"        -> true,
        "load_balancing" -> Json.obj("type" -> "Sticky")
      )
    }

    "be read back from its json" in {
      NgMinimalBackend.readFromJson(minimal.json) mustBe minimal
    }

    "read a json that is not an object as the empty minimal backend" in {
      NgMinimalBackend.readFromJson(JsNull) mustBe NgMinimalBackend.empty
    }

    "not read a url as a target" in {
      NgMinimalBackend.readFromJson(Json.obj("url" -> "http://a.com/a")) mustBe NgMinimalBackend(Seq.empty, "/", false, RoundRobin)
    }
  }

  "StoredNgBackend" should {

    val format = StoredNgBackend.format

    val minimal = StoredNgBackend(
      location = EntityLocation(),
      id = "backend-1",
      name = "Backend 1",
      description = "",
      tags = Seq.empty,
      metadata = Map.empty,
      backend = NgBackend.empty
    )

    val full = StoredNgBackend(
      location = customLocation,
      id = "backend-2",
      name = "Backend 2",
      description = "the second backend",
      tags = Seq("prod", "eu"),
      metadata = Map("owner" -> "ops", "cost-center" -> "42"),
      backend = fullBackend
    )

    "write a minimal backend with its location" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"        -> defaultLocationJson,
        "id"          -> "backend-1",
        "name"        -> "Backend 1",
        "description" -> "",
        "tags"        -> Json.arr(),
        "metadata"    -> Json.obj(),
        "backend"     -> NgBackend.empty.json
      )
    }

    "round trip a backend where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a minimal backend with a health check" in {
      roundTrip(format, minimal.copy(backend = NgBackend.empty.copy(healthCheck = Some(HealthCheck.empty))))
    }

    "round trip a custom location" in {
      roundTrip(format, full).location mustBe customLocation
    }

    "read the default location when there is no _loc" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "read a lone id, name and backend with the defaults" in {
      readOk(format, Json.obj("id" -> "backend-1", "name" -> "Backend 1", "backend" -> Json.obj())) mustBe minimal
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a backend without id" in {
      readError(format, written(format, full) - "id")
    }

    "not read a backend without name" in {
      readError(format, written(format, full) - "name")
    }

    "not read a backend without backend" in {
      readError(format, written(format, full) - "backend")
    }

    "not read an id or a name that is not a string" in {
      readError(format, written(format, full) ++ Json.obj("id" -> 42))
      readError(format, written(format, full) ++ Json.obj("name" -> 42))
    }

    "not read a backend whose target has no hostname" in {
      readError(format, written(format, full) ++ Json.obj("backend" -> Json.obj("targets" -> Json.arr(Json.obj("port" -> 80)))))
    }

    "not read a backend whose target predicate has no type" in {
      val target = minimalTarget.json.as[JsObject] ++ Json.obj("predicate" -> Json.obj())
      readError(format, written(format, full) ++ Json.obj("backend" -> Json.obj("targets" -> Json.arr(target))))
    }

    "read a json null backend as the empty backend" in {
      readOk(format, written(format, full) ++ Json.obj("backend" -> JsNull)).backend mustBe NgBackend.empty
    }

    "read a backend given as a url" in {
      readOk(format, written(format, full) ++ Json.obj("backend" -> Json.obj("url" -> "https://a.com/v1"))).backend mustBe
        NgBackend.empty.copy(targets = Seq(NgTarget.parse("https://a.com")), root = "/v1")
    }

    "ignore a description that is not a string" in {
      readOk(format, written(format, full) ++ Json.obj("description" -> 42)).description mustBe ""
    }

    "ignore metadata holding a non string value" in {
      readOk(format, written(format, full) ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe Map.empty
    }

    "ignore tags given as a single string" in {
      readOk(format, written(format, full) ++ Json.obj("tags" -> "prod")).tags mustBe empty
    }

    "keep the order of the tags" in {
      roundTrip(format, full.copy(tags = Seq("z", "a", "m"))).tags mustBe Seq("z", "a", "m")
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "backend-2", "Backend 2", "the second backend", Seq("prod", "eu"), Map("owner" -> "ops", "cost-center" -> "42"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "be built from its json" in {
      StoredNgBackend.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy StoredNgBackend.fromJsons(Json.obj("name" -> "B", "backend" -> Json.obj()))
    }
  }
}
