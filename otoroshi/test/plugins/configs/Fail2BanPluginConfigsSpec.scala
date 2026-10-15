package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

import scala.concurrent.duration.*

class Fail2BanPluginConfigsSpec extends PluginConfigFormatSpec {

  "Fail2BanConfig" should {

    val format = Fail2BanConfig.format

    val defaultRanges =
      Seq(StatusCodeRange(400), StatusCodeRange(401), StatusCodeRange(403, 499), StatusCodeRange(500, 599))

    // what reads uses when status_codes is missing, which is not the ranges of the default config
    val missingRanges =
      Seq(StatusCodeRange(401), StatusCodeRange(403), StatusCodeRange(429), StatusCodeRange(500, 599))

    val full = Fail2BanConfig(
      identifier = "${apikey.clientId}",
      detectTimeMs = 30.seconds,
      banTimeMs = 2.hours,
      maxRetry = 10,
      urlRegex = Seq(RegexRule("/public/.*", "block"), RegexRule("/api/.*", "allow")),
      statusMatchers = Seq(StatusCodeRange(401), StatusCodeRange(500, 504)),
      ignored = Seq("Ip(10.0.0.1)", "Cidr(192.168.0.0/16)"),
      blocked = Seq("6.6.6.6", "bad-client-*")
    )

    def statusCodes(json: JsValue): Seq[StatusCodeRange] =
      readOk(format, Json.obj("status_codes" -> json)).statusMatchers

    def detectTime(json: JsValue): FiniteDuration =
      readOk(format, Json.obj("detect_time" -> json)).detectTimeMs

    "ban a client for 3 hours after 4 failures in 10 minutes by default" in {
      val config = Fail2BanConfig.default
      config.identifier mustBe "${route.id}-${req.ip}"
      config.detectTimeMs mustBe 10.minutes
      config.banTimeMs mustBe 3.hours
      config.maxRetry mustBe 4
      config.urlRegex mustBe empty
      config.statusMatchers mustBe defaultRanges
      config.ignored mustBe empty
      config.blocked mustBe empty
    }

    "write the default config with durations in milliseconds and status codes as strings" in {
      format.writes(Fail2BanConfig.default) mustBe Json.obj(
        "identifier"   -> "${route.id}-${req.ip}",
        "detect_time"  -> 600000,
        "ban_time"     -> 10800000,
        "max_retry"    -> 4,
        "url_regex"    -> Json.arr(),
        "status_codes" -> Json.arr("400", "401", "403-499", "500-599"),
        "ignored"      -> Json.arr(),
        "blocked"      -> Json.arr()
      )
    }

    "write the url rules as pattern and mode objects" in {
      (format.writes(full) \ "url_regex").get mustBe Json.arr(
        Json.obj("pattern" -> "/public/.*", "mode" -> "block"),
        Json.obj("pattern" -> "/api/.*", "mode"    -> "allow")
      )
    }

    "round trip the default config" in {
      roundTrip(format, Fail2BanConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip url rules and keep their order" in {
      val rules = Seq(RegexRule("/c", "allow"), RegexRule("/a", "block"), RegexRule("/b", "allow"))
      roundTrip(format, Fail2BanConfig(urlRegex = rules)).urlRegex mustBe rules
    }

    "round trip a url rule mode as written" in {
      roundTrip(format, Fail2BanConfig(urlRegex = Seq(RegexRule("/x", "deny")))).urlRegex.head.mode mustBe "deny"
    }

    "round trip an empty status code list" in {
      roundTrip(format, Fail2BanConfig(statusMatchers = Seq.empty)).statusMatchers mustBe empty
    }

    "round trip a reversed status code range as is" in {
      roundTrip(format, Fail2BanConfig(statusMatchers = Seq(StatusCodeRange(599, 500)))).statusMatchers mustBe Seq(
        StatusCodeRange(599, 500)
      )
    }

    "round trip durations with a millisecond precision" in {
      val back = roundTrip(format, Fail2BanConfig(detectTimeMs = 1500.millis, banTimeMs = 1.millis))
      back.detectTimeMs mustBe 1500.millis
      back.banTimeMs mustBe 1.millis
    }

    "round trip a zero max retry" in {
      roundTrip(format, Fail2BanConfig(maxRetry = 0)).maxRetry mustBe 0
    }

    "read an empty object as a config that is not the default one" in {
      readOk(format, Json.obj()) mustBe Fail2BanConfig(
        identifier = "${req.ip}",
        detectTimeMs = 1.minute,
        banTimeMs = 15.minutes,
        maxRetry = 5,
        statusMatchers = missingRanges
      )
    }

    "read a json null like an empty object" in {
      readOk(format, JsNull) mustBe readOk(format, Json.obj())
    }

    "read a json string like an empty object" in {
      readOk(format, JsString("${req.ip}")) mustBe readOk(format, Json.obj())
    }

    "read a json array like an empty object" in {
      readOk(format, Json.arr()) mustBe readOk(format, Json.obj())
    }

    "read the identifier alone" in {
      readOk(format, Json.obj("identifier" -> "${user.email}")).identifier mustBe "${user.email}"
    }

    "read max_retry alone" in {
      val back = readOk(format, Json.obj("max_retry" -> 2))
      back.maxRetry mustBe 2
      back.banTimeMs mustBe 15.minutes
    }

    "read a duration in milliseconds from a number" in {
      detectTime(JsNumber(2500)) mustBe 2500.millis
    }

    "drop the decimal part of a duration number" in {
      detectTime(JsNumber(BigDecimal("2500.9"))) mustBe 2500.millis
    }

    "read a duration string in milliseconds" in {
      detectTime(JsString("500ms")) mustBe 500.millis
    }

    "read a duration string in seconds" in {
      detectTime(JsString("30s")) mustBe 30.seconds
    }

    "read a duration string in minutes" in {
      readOk(format, Json.obj("ban_time" -> "5m")).banTimeMs mustBe 5.minutes
    }

    "read a duration string in hours" in {
      readOk(format, Json.obj("ban_time" -> "2h")).banTimeMs mustBe 2.hours
    }

    "read a duration string whatever its case and spaces" in {
      detectTime(JsString(" 2 H ")) mustBe 2.hours
    }

    "read a duration string without unit as milliseconds" in {
      detectTime(JsString("1500")) mustBe 1500.millis
    }

    "read a duration string with an unknown unit as one minute" in {
      detectTime(JsString("1d")) mustBe 1.minute
    }

    "read a ban time of another type as one minute, not as fifteen minutes" in {
      readOk(format, Json.obj("ban_time" -> true)).banTimeMs mustBe 1.minute
    }

    "fail to read a duration whose number is not a whole number" in {
      readError(format, Json.obj("ban_time" -> "1.5h"))
    }

    "fail to read a duration with a unit but no number" in {
      readError(format, Json.obj("detect_time" -> "fives"))
    }

    "read status codes and ranges with spaces" in {
      statusCodes(Json.arr(" 404 ", " 500 - 504 ")) mustBe Seq(StatusCodeRange(404), StatusCodeRange(500, 504))
    }

    "read an empty status code list as no failed status" in {
      statusCodes(Json.arr()) mustBe empty
    }

    "read the ranges of the default config when a status code is invalid" in {
      statusCodes(Json.arr("404", "abc")) mustBe defaultRanges
    }

    "read the ranges of the default config when a range misses a bound" in {
      statusCodes(Json.arr("500-")) mustBe defaultRanges
    }

    "ignore status codes written as numbers" in {
      statusCodes(Json.arr(404, 500)) mustBe missingRanges
    }

    "read a url rule without pattern as matching everything" in {
      readOk(format, Json.obj("url_regex" -> Json.arr(Json.obj("mode" -> "block")))).urlRegex mustBe Seq(
        RegexRule(".*", "block")
      )
    }

    "read a url rule with a null mode as an allow rule" in {
      readOk(
        format,
        Json.obj("url_regex" -> Json.arr(Json.obj("pattern" -> "/a", "mode" -> JsNull)))
      ).urlRegex mustBe Seq(RegexRule("/a", "allow"))
    }

    "fail to read a url rule without mode" in {
      readError(format, Json.obj("url_regex" -> Json.arr(Json.obj("pattern" -> "/a"))))
    }

    "ignore url rules that are not objects" in {
      readOk(format, Json.obj("url_regex" -> Json.arr("/a"))).urlRegex mustBe empty
    }

    "ignore an identifier that is not a string" in {
      readOk(format, Json.obj("identifier" -> 42)).identifier mustBe "${req.ip}"
    }

    "ignore a max_retry written as a string" in {
      readOk(format, Json.obj("max_retry" -> "3")).maxRetry mustBe 5
    }

    "ignore an ignored list holding something else than strings" in {
      readOk(format, Json.obj("ignored" -> Json.arr("10.0.0.1", 42))).ignored mustBe empty
    }

    "not read the camel case keys" in {
      val back = readOk(format, Json.obj("maxRetry" -> 1, "detectTime" -> 1000, "banTime" -> 1000))
      back mustBe readOk(format, Json.obj())
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "list in its config flow only keys of its config schema" in {
      Fail2BanConfig.configFlow.foreach { key =>
        Fail2BanConfig.configSchema.keys must contain(key)
      }
    }

    "expose its config flow and schema through its plugin" in {
      val plugin = new Fail2BanPlugin()
      plugin.configFlow mustBe Fail2BanConfig.configFlow
      plugin.configSchema mustBe Some(Fail2BanConfig.configSchema)
    }

    "be the default config of Fail2BanPlugin" in {
      defaultConfigReadsBack(new Fail2BanPlugin(), format) mustBe Fail2BanConfig.default
    }

    "put every url in scope when there is no url rule" in {
      Fail2BanConfig().isUrlInScope("/anything?foo=bar") mustBe true
    }

    "let the first matching url rule decide the scope" in {
      val config = Fail2BanConfig(urlRegex = Seq(RegexRule("/public/.*", "block"), RegexRule("/.*", "allow")))
      config.isUrlInScope("/public/index.html") mustBe false
      config.isUrlInScope("/api/users") mustBe true
    }

    "match url rule modes whatever their case" in {
      Fail2BanConfig(urlRegex = Seq(RegexRule("/admin", "BLOCK"))).isUrlInScope("/admin") mustBe false
    }

    "keep in scope a url matched by a rule of unknown mode or by no rule" in {
      val config = Fail2BanConfig(urlRegex = Seq(RegexRule("/a", "deny")))
      config.isUrlInScope("/a") mustBe true
      config.isUrlInScope("/b") mustBe true
    }

    "count the statuses of the default ranges as failures" in {
      val config = Fail2BanConfig.default
      Seq(400, 401, 403, 404, 499, 500, 599).foreach(status => config.isFailedStatus(status) mustBe true)
      Seq(200, 302, 402, 600).foreach(status => config.isFailedStatus(status) mustBe false)
    }

    "count no status as a failure without status code" in {
      Fail2BanConfig(statusMatchers = Seq.empty).isFailedStatus(500) mustBe false
    }

    "ignore and block nothing without rule" in {
      Fail2BanConfig().isIgnored("10.0.0.1") mustBe false
      Fail2BanConfig().isBlocked("10.0.0.1") mustBe false
    }

    "match ip identifiers against Ip and Cidr rules" in {
      val config = Fail2BanConfig(ignored = Seq("Ip(10.0.0.1)", "Cidr(192.168.0.0/16)"))
      config.isIgnored("10.0.0.1") mustBe true
      config.isIgnored("192.168.12.34") mustBe true
      config.isIgnored("10.0.0.2") mustBe false
    }

    "match ip identifiers whatever the spelling of the address" in {
      val config = Fail2BanConfig(blocked = Seq("2001:db8::1"))
      config.isBlocked("2001:db8:0:0:0:0:0:1") mustBe true
      config.isBlocked("[2001:db8::1]:8443") mustBe true
    }

    "match other identifiers as wildcards and never against a Cidr rule" in {
      val config = Fail2BanConfig(blocked = Seq("bad-client-*", "Cidr(10.0.0.0/8)"))
      config.isBlocked("bad-client-42") mustBe true
      config.isBlocked("good-client") mustBe false
      config.isBlocked("10.0.0.0/8") mustBe false
    }
  }
}
