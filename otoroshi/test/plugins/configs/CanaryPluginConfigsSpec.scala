package plugins.configs

import org.joda.time.{DateTime, DateTimeZone, LocalDate}
import otoroshi.models.{AlwaysMatch, HttpProtocols, NetworkLocationMatch}
import otoroshi.next.models.{NgTarget, NgTlsConfig}
import otoroshi.next.plugins.*
import play.api.libs.json.*

class CanaryPluginConfigsSpec extends PluginConfigFormatSpec {

  private val plainTarget = NgTarget(
    id = "canary-1",
    hostname = "canary.oto.tools",
    port = 8080,
    tls = false
  )

  private val fullTarget = NgTarget(
    id = "canary-2",
    hostname = "canary-secure.oto.tools",
    port = 8443,
    tls = true,
    weight = 3,
    protocol = HttpProtocols.HTTP_2_0,
    predicate = NetworkLocationMatch(region = Some("eu-west-1"), zone = Some("a")),
    ipAddress = Some("10.0.0.12"),
    tlsConfig = NgTlsConfig(certs = Seq("client-cert"), trustedCerts = Seq("ca"), enabled = true),
    backup = true
  )

  "NgCanarySettings" should {

    val format = NgCanarySettings.format

    val full = NgCanarySettings(traffic = 0.5, targets = Seq(plainTarget, fullTarget), root = "/canary")

    "send 20 percent of the traffic to no target on / by default" in {
      val config = NgCanarySettings()
      config.traffic mustBe 0.2
      config.targets mustBe empty
      config.root mustBe "/"
    }

    "write the default config" in {
      format.writes(NgCanarySettings()) mustBe Json.obj("traffic" -> 0.2, "targets" -> Json.arr(), "root" -> "/")
    }

    "write the targets with the json of the route targets" in {
      (format.writes(NgCanarySettings(targets = Seq(plainTarget))) \ "targets").get mustBe Json.arr(
        Json.obj(
          "id"         -> "canary-1",
          "hostname"   -> "canary.oto.tools",
          "port"       -> 8080,
          "tls"        -> false,
          "weight"     -> 1,
          "backup"     -> false,
          "predicate"  -> Json.obj("type" -> "AlwaysMatch"),
          "protocol"   -> "HTTP/1.1",
          "ip_address" -> JsNull,
          "tls_config" -> Json.obj(
            "certs"         -> Json.arr(),
            "trusted_certs" -> Json.arr(),
            "enabled"       -> false,
            "loose"         -> false,
            "trust_all"     -> false
          )
        )
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgCanarySettings())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a target where every field is set" in {
      roundTrip(format, NgCanarySettings(targets = Seq(fullTarget))).targets mustBe Seq(fullTarget)
    }

    "round trip several targets and keep their order" in {
      roundTrip(format, NgCanarySettings(targets = Seq(fullTarget, plainTarget))).targets.map(_.id) mustBe Seq(
        "canary-2",
        "canary-1"
      )
    }

    "round trip a zero traffic" in {
      roundTrip(format, NgCanarySettings(traffic = 0.0)).traffic mustBe 0.0
    }

    "round trip a full traffic" in {
      roundTrip(format, NgCanarySettings(traffic = 1.0)).traffic mustBe 1.0
    }

    "round trip a traffic above one as is" in {
      roundTrip(format, NgCanarySettings(traffic = 1.5)).traffic mustBe 1.5
    }

    "round trip a precise traffic ratio" in {
      roundTrip(format, NgCanarySettings(traffic = 0.123456789)).traffic mustBe 0.123456789
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgCanarySettings()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgCanarySettings()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("0.5")) mustBe NgCanarySettings()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr()) mustBe NgCanarySettings()
    }

    "read the traffic alone" in {
      readOk(format, Json.obj("traffic" -> 0.75)) mustBe NgCanarySettings(traffic = 0.75)
    }

    "read the root alone" in {
      readOk(format, Json.obj("root" -> "/v2")) mustBe NgCanarySettings(root = "/v2")
    }

    "read a whole number traffic" in {
      readOk(format, Json.obj("traffic" -> 1)).traffic mustBe 1.0
    }

    "ignore a traffic written as a string" in {
      readOk(format, Json.obj("traffic" -> "0.5")).traffic mustBe 0.2
    }

    "ignore a root that is not a string" in {
      readOk(format, Json.obj("root" -> 42)).root mustBe "/"
    }

    "ignore targets that are not an array" in {
      readOk(format, Json.obj("targets" -> Json.obj("hostname" -> "foo.oto.tools", "port" -> 80))).targets mustBe empty
    }

    "read a minimal target and build its id from its address" in {
      val back = readOk(format, Json.obj("targets" -> Json.arr(Json.obj("hostname" -> "foo.oto.tools", "port" -> 8080))))
      back.targets mustBe Seq(
        NgTarget(id = "http://foo.oto.tools:8080", hostname = "foo.oto.tools", port = 8080, tls = false)
      )
    }

    "build the id of a tls target with an ip address from its address" in {
      val target = Json.obj("hostname" -> "foo.oto.tools", "port" -> 443, "tls" -> true, "ip_address" -> "10.0.0.1")
      readOk(format, Json.obj("targets" -> Json.arr(target))).targets.head.id mustBe "https://foo.oto.tools@10.0.0.1:443"
    }

    "read a blank target ip address as no ip address" in {
      val target = Json.obj("id" -> "t", "hostname" -> "foo.oto.tools", "port" -> 80, "ip_address" -> "  ")
      readOk(format, Json.obj("targets" -> Json.arr(target))).targets.head.ipAddress mustBe None
    }

    "read an unknown target protocol as http 1.1" in {
      val target = Json.obj("id" -> "t", "hostname" -> "foo.oto.tools", "port" -> 80, "protocol" -> "SPDY/3")
      readOk(format, Json.obj("targets" -> Json.arr(target))).targets.head.protocol mustBe HttpProtocols.HTTP_1_1
    }

    "read an unknown target predicate as always match" in {
      val target = Json.obj("id" -> "t", "hostname" -> "foo.oto.tools", "port" -> 80, "predicate" -> Json.obj("type" -> "Foo"))
      readOk(format, Json.obj("targets" -> Json.arr(target))).targets.head.predicate mustBe AlwaysMatch
    }

    "fail to read a target without hostname" in {
      readError(format, Json.obj("targets" -> Json.arr(Json.obj("port" -> 80))))
    }

    "fail to read a target without port" in {
      readError(format, Json.obj("targets" -> Json.arr(Json.obj("hostname" -> "foo.oto.tools"))))
    }

    "turn into an enabled legacy canary" in {
      val legacy = full.legacy
      legacy.enabled mustBe true
      legacy.traffic mustBe 0.5
      legacy.root mustBe "/canary"
      legacy.targets.map(_.host) mustBe Seq("canary.oto.tools:8080", "canary-secure.oto.tools:8443")
    }

    "come back from its legacy canary" in {
      NgCanarySettings.fromLegacy(full.legacy) mustBe full
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of CanaryMode" in {
      defaultConfigReadsBack(new CanaryMode(), format) mustBe NgCanarySettings()
    }
  }

  "TimeControlledCanaryModeConfig" should {

    val format = TimeControlledCanaryModeConfig.format

    val start = new DateTime(2026, 1, 1, 10, 0, DateTimeZone.UTC)
    val stop  = new DateTime(2026, 1, 2, 10, 0, DateTimeZone.UTC)

    val full = TimeControlledCanaryModeConfig(
      targets = Seq(plainTarget, fullTarget),
      root = "/canary",
      start = start,
      stop = stop,
      incrementPercent = 5.0
    )

    // the start and the stop of the default config are taken from the clock
    def readsBackInstants(config: TimeControlledCanaryModeConfig): TimeControlledCanaryModeConfig = {
      val json = format.writes(config)
      val back = readOk(format, json)
      back.start.getMillis mustBe config.start.getMillis
      back.stop.getMillis mustBe config.stop.getMillis
      back.copy(start = config.start, stop = config.stop) mustBe config
      format.writes(back) mustBe json
      back
    }

    "roll out from now to tomorrow, one percent at a time, by default" in {
      val config = TimeControlledCanaryModeConfig()
      config.targets mustBe empty
      config.root mustBe "/"
      config.incrementPercent mustBe 1.0
      val window = config.stop.getMillis - config.start.getMillis
      window must be >= 24L * 60 * 60 * 1000
      window must be < 24L * 60 * 60 * 1000 + 1000
    }

    "write the dates as iso 8601 strings" in {
      format.writes(TimeControlledCanaryModeConfig(start = start, stop = stop)) mustBe Json.obj(
        "start"             -> "2026-01-01T10:00:00.000Z",
        "stop"              -> "2026-01-02T10:00:00.000Z",
        "increment_percent" -> 1.0,
        "targets"           -> Json.arr(),
        "root"              -> "/"
      )
    }

    "write the offset of the dates" in {
      val withOffset = new DateTime(2026, 6, 1, 8, 30, DateTimeZone.forOffsetHours(2))
      (format.writes(TimeControlledCanaryModeConfig(start = withOffset, stop = withOffset)) \ "start")
        .as[String] mustBe "2026-06-01T08:30:00.000+02:00"
    }

    "keep the instants of the default config" in {
      readsBackInstants(TimeControlledCanaryModeConfig())
    }

    "round trip dates in utc" in {
      roundTrip(format, TimeControlledCanaryModeConfig(start = start, stop = stop))
    }

    "round trip dates with a fixed offset" in {
      val offset = DateTimeZone.forOffsetHoursMinutes(5, 30)
      roundTrip(
        format,
        TimeControlledCanaryModeConfig(start = start.withZone(offset), stop = stop.withZone(offset))
      )
    }

    "keep the instant of a date in a region zone but only its offset" in {
      val paris  = new DateTime(2026, 3, 1, 10, 0, DateTimeZone.forID("Europe/Paris"))
      val config = TimeControlledCanaryModeConfig(start = paris, stop = paris.plusDays(1))
      val back   = readsBackInstants(config)
      back.start.getZone mustBe DateTimeZone.forOffsetHours(1)
      back must not be config
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a fractional increment" in {
      roundTrip(format, TimeControlledCanaryModeConfig(start = start, stop = stop, incrementPercent = 0.5))
    }

    "round trip a stop before the start as is" in {
      roundTrip(format, TimeControlledCanaryModeConfig(start = stop, stop = start)).stop mustBe start
    }

    "fail to read an empty object because the dates are mandatory" in {
      readError(format, Json.obj())
    }

    "fail to read a json null" in {
      readError(format, JsNull)
    }

    "fail to read a json string" in {
      readError(format, JsString("2026-01-01T10:00:00.000Z"))
    }

    "fail to read a json array" in {
      readError(format, Json.arr("2026-01-01T10:00:00.000Z", "2026-01-02T10:00:00.000Z"))
    }

    "fail to read a config without stop" in {
      readError(format, Json.obj("start" -> "2026-01-01T10:00:00.000Z"))
    }

    "fail to read a config without start" in {
      readError(format, Json.obj("stop" -> "2026-01-02T10:00:00.000Z"))
    }

    "fail to read a date that is not iso 8601" in {
      readError(format, Json.obj("start" -> "tomorrow", "stop" -> "2026-01-02T10:00:00.000Z"))
    }

    "fail to read a date written as epoch millis" in {
      readError(format, Json.obj("start" -> start.getMillis, "stop" -> stop.getMillis))
    }

    "read the dates alone and default the rest" in {
      val back = readOk(format, Json.obj("start" -> "2026-01-01T10:00:00.000Z", "stop" -> "2026-01-02T10:00:00.000Z"))
      back mustBe TimeControlledCanaryModeConfig(start = start, stop = stop)
    }

    "read a date without time as the start of that day" in {
      val back = readOk(format, Json.obj("start" -> "2026-01-01", "stop" -> "2026-01-02"))
      back.start.toLocalDate mustBe new LocalDate(2026, 1, 1)
      back.start.getMillisOfDay mustBe 0
    }

    "ignore an increment written as a string" in {
      val json = format.writes(TimeControlledCanaryModeConfig(start = start, stop = stop)).as[JsObject]
      readOk(format, json ++ Json.obj("increment_percent" -> "5")).incrementPercent mustBe 1.0
    }

    "ignore a root that is not a string" in {
      val json = format.writes(TimeControlledCanaryModeConfig(start = start, stop = stop)).as[JsObject]
      readOk(format, json ++ Json.obj("root" -> false)).root mustBe "/"
    }

    "ignore targets that are not an array" in {
      val json = format.writes(full).as[JsObject]
      readOk(format, json ++ Json.obj("targets" -> "canary.oto.tools")).targets mustBe empty
    }

    "fail to read a target without hostname" in {
      val json = format.writes(TimeControlledCanaryModeConfig(start = start, stop = stop)).as[JsObject]
      readError(format, json ++ Json.obj("targets" -> Json.arr(Json.obj("port" -> 80))))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of TimeControlledCanaryMode, with a window of one day" in {
      val plugin = new TimeControlledCanaryMode()
      plugin.defaultConfigObject.get mustBe a[TimeControlledCanaryModeConfig]
      val back   = readOk(format, plugin.defaultConfig.get)
      back.targets mustBe empty
      back.root mustBe "/"
      back.incrementPercent mustBe 1.0
      val window = back.stop.getMillis - back.start.getMillis
      window must be >= 24L * 60 * 60 * 1000
      window must be < 24L * 60 * 60 * 1000 + 1000
    }

    "have its plugin send everything to the canary once the window is over" in {
      val now = DateTime.now()
      new TimeControlledCanaryMode().progress(now.minusDays(2), now.minusDays(1), 1.0) mustBe 1.0
    }

    "have its plugin send nothing to the canary before the window" in {
      val now = DateTime.now()
      new TimeControlledCanaryMode().progress(now.plusDays(1), now.plusDays(2), 1.0) mustBe 0.0
    }

    "have its plugin round the progress to the increment" in {
      val now = DateTime.now()
      new TimeControlledCanaryMode().progress(now.minusHours(50), now.plusHours(50), 10.0) mustBe 0.5
    }

    "have its plugin consider an empty window as fully rolled out" in {
      val later = DateTime.now().plusDays(1)
      new TimeControlledCanaryMode().progress(later, later, 1.0) mustBe 1.0
    }
  }
}
