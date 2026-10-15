package plugins.configs

import otoroshi.models.RemainingQuotas
import otoroshi.next.plugins.*
import play.api.libs.json.*

class QuotasPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgServiceQuotasConfig" should {

    val format = NgServiceQuotasConfig.format

    "default every quota to the max quota value" in {
      RemainingQuotas.MaxValue mustBe 10000000L
      NgServiceQuotasConfig() mustBe NgServiceQuotasConfig(10000000L, 10000000L, 10000000L)
    }

    "write the default config" in {
      format.writes(NgServiceQuotasConfig()) mustBe Json.obj(
        "throttling_quota" -> 10000000L,
        "daily_quota"      -> 10000000L,
        "monthly_quota"    -> 10000000L
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgServiceQuotasConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, NgServiceQuotasConfig(throttlingQuota = 10, dailyQuota = 1000, monthlyQuota = 30000))
    }

    "keep the three quotas apart" in {
      val back = roundTrip(format, NgServiceQuotasConfig(1, 2, 3))
      back.throttlingQuota mustBe 1
      back.dailyQuota mustBe 2
      back.monthlyQuota mustBe 3
    }

    "round trip zero quotas" in {
      roundTrip(format, NgServiceQuotasConfig(0, 0, 0))
    }

    "round trip quotas above the max quota value" in {
      roundTrip(format, NgServiceQuotasConfig(Long.MaxValue, Long.MaxValue, Long.MaxValue))
    }

    "round trip negative quotas as is" in {
      roundTrip(format, NgServiceQuotasConfig(-1, -1, -1))
    }

    "read the throttling quota alone" in {
      readOk(format, Json.obj("throttling_quota" -> 5)) mustBe NgServiceQuotasConfig(throttlingQuota = 5)
    }

    "read the daily quota alone" in {
      readOk(format, Json.obj("daily_quota" -> 50)) mustBe NgServiceQuotasConfig(dailyQuota = 50)
    }

    "read the monthly quota alone" in {
      readOk(format, Json.obj("monthly_quota" -> 500)) mustBe NgServiceQuotasConfig(monthlyQuota = 500)
    }

    "ignore a quota written as a string" in {
      readOk(format, Json.obj("daily_quota" -> "50")) mustBe NgServiceQuotasConfig()
    }

    "ignore a quota that is not a whole number" in {
      readOk(format, Json.obj("throttling_quota" -> 1.5)) mustBe NgServiceQuotasConfig()
    }

    "ignore a quota that does not fit a long" in {
      readOk(format, Json.obj("monthly_quota" -> JsNumber(BigDecimal("99999999999999999999")))) mustBe NgServiceQuotasConfig()
    }

    "read a whole number written with a decimal part" in {
      readOk(format, Json.obj("monthly_quota" -> JsNumber(BigDecimal("100.0")))).monthlyQuota mustBe 100L
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgServiceQuotasConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgServiceQuotasConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("10")) mustBe NgServiceQuotasConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(1, 2, 3)) mustBe NgServiceQuotasConfig()
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("throttlingQuota" -> 1, "dailyQuota" -> 2, "monthlyQuota" -> 3)
      ) mustBe NgServiceQuotasConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgServiceQuotasConfig(1, 2, 3))
    }

    "be the default config of NgServiceQuotas" in {
      defaultConfigReadsBack(new NgServiceQuotas(), format) mustBe NgServiceQuotasConfig()
    }
  }

  "NgCustomQuotasConfig" should {

    val format = NgCustomQuotasConfig.format
    val full   = NgCustomQuotasConfig(
      dailyQuota = 100,
      monthlyQuota = 3000,
      perRoute = false,
      global = true,
      group = Some("${apikey.metadata.team}"),
      expression = "${apikey.clientId}"
    )

    "count per route and per ip address by default" in {
      NgCustomQuotasConfig() mustBe NgCustomQuotasConfig(
        dailyQuota = RemainingQuotas.MaxValue,
        monthlyQuota = RemainingQuotas.MaxValue,
        perRoute = true,
        global = false,
        group = None,
        expression = "${req.ip}"
      )
    }

    "write the default config" in {
      format.writes(NgCustomQuotasConfig()) mustBe Json.obj(
        "per_route"     -> true,
        "global"        -> false,
        "group"         -> JsNull,
        "expression"    -> "${req.ip}",
        "daily_quota"   -> 10000000L,
        "monthly_quota" -> 10000000L
      )
    }

    "write a group as a string" in {
      written(format, NgCustomQuotasConfig(group = Some("team-a"))).value("group") mustBe JsString("team-a")
    }

    "round trip the default config" in {
      roundTrip(format, NgCustomQuotasConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "keep the daily and monthly quotas apart" in {
      val back = roundTrip(format, NgCustomQuotasConfig(dailyQuota = 7, monthlyQuota = 70))
      back.dailyQuota mustBe 7
      back.monthlyQuota mustBe 70
    }

    "round trip a config that is not per route" in {
      roundTrip(format, NgCustomQuotasConfig(perRoute = false)).perRoute mustBe false
    }

    "round trip a global config" in {
      roundTrip(format, NgCustomQuotasConfig(global = true)).global mustBe true
    }

    "round trip a group" in {
      roundTrip(format, NgCustomQuotasConfig(group = Some("tenant-1"))).group mustBe Some("tenant-1")
    }

    "round trip an empty group" in {
      roundTrip(format, NgCustomQuotasConfig(group = Some(""))).group mustBe Some("")
    }

    "round trip an empty expression" in {
      roundTrip(format, NgCustomQuotasConfig(expression = "")).expression mustBe ""
    }

    "round trip zero quotas" in {
      roundTrip(format, NgCustomQuotasConfig(dailyQuota = 0, monthlyQuota = 0))
    }

    "read the per route flag alone" in {
      readOk(format, Json.obj("per_route" -> false)) mustBe NgCustomQuotasConfig(perRoute = false)
    }

    "read the global flag alone" in {
      readOk(format, Json.obj("global" -> true)) mustBe NgCustomQuotasConfig(global = true)
    }

    "read the group alone" in {
      readOk(format, Json.obj("group" -> "g")) mustBe NgCustomQuotasConfig(group = Some("g"))
    }

    "read the expression alone" in {
      readOk(format, Json.obj("expression" -> "${req.host}")) mustBe NgCustomQuotasConfig(expression = "${req.host}")
    }

    "read the daily quota alone" in {
      readOk(format, Json.obj("daily_quota" -> 12)) mustBe NgCustomQuotasConfig(dailyQuota = 12)
    }

    "read the monthly quota alone" in {
      readOk(format, Json.obj("monthly_quota" -> 120)) mustBe NgCustomQuotasConfig(monthlyQuota = 120)
    }

    "read a null group as no group" in {
      readOk(format, Json.obj("group" -> JsNull)).group mustBe None
    }

    "ignore flags written as strings" in {
      readOk(format, Json.obj("per_route" -> "false", "global" -> "true")) mustBe NgCustomQuotasConfig()
    }

    "ignore quotas written as strings" in {
      readOk(format, Json.obj("daily_quota" -> "12", "monthly_quota" -> "120")) mustBe NgCustomQuotasConfig()
    }

    "ignore a group that is not a string" in {
      readOk(format, Json.obj("group" -> 42)).group mustBe None
    }

    "ignore an expression that is not a string" in {
      readOk(format, Json.obj("expression" -> Json.obj("el" -> "${req.ip}"))).expression mustBe "${req.ip}"
    }

    "not read the camel case per route key" in {
      readOk(format, Json.obj("perRoute" -> false)).perRoute mustBe true
    }

    "not read the camel case quota keys" in {
      readOk(format, Json.obj("dailyQuota" -> 1, "monthlyQuota" -> 2)) mustBe NgCustomQuotasConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgCustomQuotasConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgCustomQuotasConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("${req.ip}")) mustBe NgCustomQuotasConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(Json.obj("per_route" -> false))) mustBe NgCustomQuotasConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgCustomQuotas" in {
      defaultConfigReadsBack(new NgCustomQuotas(), format) mustBe NgCustomQuotasConfig()
    }
  }

  "NgCustomThrottlingConfig" should {

    val format = NgCustomThrottlingConfig.format
    val full   = NgCustomThrottlingConfig(
      throttlingQuota = 5,
      perRoute = false,
      global = true,
      group = Some("${route.metadata.tier}"),
      expression = "${req.headers.X-User}"
    )

    "allow 100 calls per window, per route and per ip address by default" in {
      NgCustomThrottlingConfig() mustBe NgCustomThrottlingConfig(
        throttlingQuota = 100L,
        perRoute = true,
        global = false,
        group = None,
        expression = "${req.ip}"
      )
    }

    "write the default config" in {
      format.writes(NgCustomThrottlingConfig()) mustBe Json.obj(
        "per_route"        -> true,
        "global"           -> false,
        "group"            -> JsNull,
        "expression"       -> "${req.ip}",
        "throttling_quota" -> 100
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgCustomThrottlingConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a group" in {
      roundTrip(format, NgCustomThrottlingConfig(group = Some("tenant-1"))).group mustBe Some("tenant-1")
    }

    "round trip a config that is not per route" in {
      roundTrip(format, NgCustomThrottlingConfig(perRoute = false)).perRoute mustBe false
    }

    "round trip a global config" in {
      roundTrip(format, NgCustomThrottlingConfig(global = true)).global mustBe true
    }

    "round trip a zero quota" in {
      roundTrip(format, NgCustomThrottlingConfig(throttlingQuota = 0)).throttlingQuota mustBe 0L
    }

    "round trip the largest quota" in {
      roundTrip(format, NgCustomThrottlingConfig(throttlingQuota = Long.MaxValue))
    }

    "read the legacy perRoute key" in {
      readOk(format, Json.obj("perRoute" -> false)) mustBe NgCustomThrottlingConfig(perRoute = false)
    }

    "prefer per_route over perRoute" in {
      readOk(format, Json.obj("per_route" -> true, "perRoute" -> false)).perRoute mustBe true
      readOk(format, Json.obj("per_route" -> false, "perRoute" -> true)).perRoute mustBe false
    }

    "fall back to perRoute when per_route is not a boolean" in {
      readOk(format, Json.obj("per_route" -> "yes", "perRoute" -> false)).perRoute mustBe false
    }

    "rewrite the legacy perRoute key as per_route" in {
      val legacy = readOk(format, Json.obj("perRoute" -> false))
      val json   = written(format, legacy)
      json.value("per_route") mustBe JsBoolean(false)
      json.keys must not contain "perRoute"
    }

    "read the throttling quota alone" in {
      readOk(format, Json.obj("throttling_quota" -> 3)) mustBe NgCustomThrottlingConfig(throttlingQuota = 3)
    }

    "read the group alone" in {
      readOk(format, Json.obj("group" -> "g")) mustBe NgCustomThrottlingConfig(group = Some("g"))
    }

    "read the expression alone" in {
      readOk(format, Json.obj("expression" -> "${req.host}")) mustBe NgCustomThrottlingConfig(expression = "${req.host}")
    }

    "read the global flag alone" in {
      readOk(format, Json.obj("global" -> true)) mustBe NgCustomThrottlingConfig(global = true)
    }

    "ignore a quota written as a string" in {
      readOk(format, Json.obj("throttling_quota" -> "3")).throttlingQuota mustBe 100L
    }

    "ignore a quota that is not a whole number" in {
      readOk(format, Json.obj("throttling_quota" -> 2.5)).throttlingQuota mustBe 100L
    }

    "ignore a global flag written as a string" in {
      readOk(format, Json.obj("global" -> "true")).global mustBe false
    }

    "ignore a group that is not a string" in {
      readOk(format, Json.obj("group" -> Json.arr("g"))).group mustBe None
    }

    "not read the camel case quota key" in {
      readOk(format, Json.obj("throttlingQuota" -> 3)).throttlingQuota mustBe 100L
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgCustomThrottlingConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgCustomThrottlingConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("100")) mustBe NgCustomThrottlingConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(100)) mustBe NgCustomThrottlingConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgCustomThrottling" in {
      defaultConfigReadsBack(new NgCustomThrottling(), format) mustBe NgCustomThrottlingConfig()
    }
  }

  "the quotas plugins without configuration" should {

    "have no default config for GlobalPerIpAddressThrottling" in {
      hasNoDefaultConfig(new GlobalPerIpAddressThrottling())
    }

    "have no default config for GlobalThrottling" in {
      hasNoDefaultConfig(new GlobalThrottling())
    }

    "have no default config for ApikeyQuotas" in {
      hasNoDefaultConfig(new ApikeyQuotas())
    }
  }
}
