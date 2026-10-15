package plugins.configs

import otoroshi.models.RemainingQuotas
import otoroshi.next.plugins.*
import play.api.libs.json.*

class RateLimitingPluginConfigsSpec extends PluginConfigFormatSpec {

  private val max = RemainingQuotas.MaxValue

  private val unlimitedQuotaJson = Json.obj("window" -> max, "daily" -> max, "monthly" -> max)

  private val customQuota = AllowedQuota(window = 10L, daily = 1000L, monthly = 20000L)

  private val customQuotaJson = Json.obj("window" -> 10, "daily" -> 1000, "monthly" -> 20000)

  "AllowedQuota" should {

    val format = AllowedQuota.fmt

    "allow ten million calls per window, day and month by default" in {
      AllowedQuota() mustBe AllowedQuota(10000000L, 10000000L, 10000000L)
      RemainingQuotas.MaxValue mustBe 10000000L
    }

    "write the default quota" in {
      format.writes(AllowedQuota()) mustBe unlimitedQuotaJson
    }

    "write a custom quota" in {
      format.writes(customQuota) mustBe customQuotaJson
    }

    "write through json what the format writes" in {
      customQuota.json mustBe format.writes(customQuota)
    }

    "round trip the default quota" in {
      roundTrip(format, AllowedQuota())
    }

    "round trip a custom quota" in {
      roundTrip(format, customQuota)
    }

    "round trip a zero quota" in {
      roundTrip(format, AllowedQuota(0L, 0L, 0L))
    }

    "round trip the largest quota" in {
      roundTrip(format, AllowedQuota(Long.MaxValue, Long.MaxValue, Long.MaxValue))
    }

    "round trip a negative quota as is" in {
      roundTrip(format, AllowedQuota(window = -1L)).window mustBe -1L
    }

    "read an empty object as the default quota" in {
      readOk(format, Json.obj()) mustBe AllowedQuota()
    }

    "read a json null as the default quota" in {
      readOk(format, JsNull) mustBe AllowedQuota()
    }

    "read a json number as the default quota" in {
      readOk(format, JsNumber(5)) mustBe AllowedQuota()
    }

    "read the window alone and default the rest" in {
      readOk(format, Json.obj("window" -> 10)) mustBe AllowedQuota(window = 10L)
    }

    "read the daily quota alone and default the rest" in {
      readOk(format, Json.obj("daily" -> 100)) mustBe AllowedQuota(daily = 100L)
    }

    "read the monthly quota alone and default the rest" in {
      readOk(format, Json.obj("monthly" -> 1000)) mustBe AllowedQuota(monthly = 1000L)
    }

    "ignore quotas written as strings" in {
      readOk(format, Json.obj("window" -> "10", "daily" -> "100", "monthly" -> "1000")) mustBe AllowedQuota()
    }

    "ignore quotas that are not whole numbers" in {
      readOk(format, Json.obj("window" -> 10.5)) mustBe AllowedQuota()
    }

    "not read the legacy quota key names" in {
      readOk(format, Json.obj("throttlingQuota" -> 10, "dailyQuota" -> 100, "monthlyQuota" -> 1000)) mustBe AllowedQuota()
    }
  }

  "LocalTokensBucketStrategyConfig" should {

    val format = LocalTokensBucketStrategyConfig.format

    val full = LocalTokensBucketStrategyConfig(
      bucketKey = "${apikey.clientId}",
      capacity = 1000L,
      refillRequestIntervalMs = 200L,
      refillRequestedTokens = 20L,
      quota = customQuota
    )

    "hold 300 tokens refilled by 50 every 50 milliseconds by default" in {
      val config = LocalTokensBucketStrategyConfig()
      config.capacity mustBe 300L
      config.refillRequestIntervalMs mustBe 50L
      config.refillRequestedTokens mustBe 50L
      config.quota mustBe AllowedQuota()
    }

    "use a random bucket key by default" in {
      val a = LocalTokensBucketStrategyConfig()
      val b = LocalTokensBucketStrategyConfig()
      a.bucketKey must not be empty
      a.bucketKey must not be b.bucketKey
    }

    "be identified as LocalTokensBucketStrategyConfig" in {
      LocalTokensBucketStrategyConfig().id mustBe "LocalTokensBucketStrategyConfig"
    }

    "write the default config with its bucket key" in {
      val config = LocalTokensBucketStrategyConfig(bucketKey = "bucket")
      format.writes(config) mustBe Json.obj(
        "id"                      -> "LocalTokensBucketStrategyConfig",
        "bucketKey"               -> "bucket",
        "capacity"                -> 300,
        "refillRequestIntervalMs" -> 50,
        "refillRequestedTokens"   -> 50,
        "quota"                   -> unlimitedQuotaJson
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "id"                      -> "LocalTokensBucketStrategyConfig",
        "bucketKey"               -> "${apikey.clientId}",
        "capacity"                -> 1000,
        "refillRequestIntervalMs" -> 200,
        "refillRequestedTokens"   -> 20,
        "quota"                   -> customQuotaJson
      )
    }

    "round trip the default config, random bucket key included" in {
      val config = LocalTokensBucketStrategyConfig()
      roundTrip(format, config).bucketKey mustBe config.bucketKey
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a bucket key holding expression language" in {
      roundTrip(format, full.copy(bucketKey = "${req.ip_address}-${req.path}")).bucketKey mustBe "${req.ip_address}-${req.path}"
    }

    "round trip a zero capacity" in {
      roundTrip(format, full.copy(capacity = 0L)).capacity mustBe 0L
    }

    "round trip a large capacity" in {
      roundTrip(format, full.copy(capacity = Long.MaxValue)).capacity mustBe Long.MaxValue
    }

    "round trip a custom quota alone" in {
      roundTrip(format, LocalTokensBucketStrategyConfig(bucketKey = "b", quota = customQuota)).quota mustBe customQuota
    }

    "read an empty object as the default config with a fresh bucket key" in {
      val back = readOk(format, Json.obj())
      back.bucketKey must not be empty
      back mustBe LocalTokensBucketStrategyConfig(bucketKey = back.bucketKey)
    }

    "draw a new bucket key each time the key is missing" in {
      readOk(format, Json.obj()).bucketKey must not be readOk(format, Json.obj()).bucketKey
    }

    "read a json null as the default config" in {
      val back = readOk(format, JsNull)
      back mustBe LocalTokensBucketStrategyConfig(bucketKey = back.bucketKey)
    }

    "read a json string as the default config" in {
      val back = readOk(format, JsString("bucket"))
      back mustBe LocalTokensBucketStrategyConfig(bucketKey = back.bucketKey)
    }

    "read the capacity alone and default the rest" in {
      val back = readOk(format, Json.obj("bucketKey" -> "b", "capacity" -> 10))
      back mustBe LocalTokensBucketStrategyConfig(bucketKey = "b", capacity = 10L)
    }

    "read the refill settings alone and default the rest" in {
      val back = readOk(format, Json.obj("bucketKey" -> "b", "refillRequestIntervalMs" -> 1000, "refillRequestedTokens" -> 1))
      back mustBe LocalTokensBucketStrategyConfig(bucketKey = "b", refillRequestIntervalMs = 1000L, refillRequestedTokens = 1L)
    }

    "read a partial quota and default its other limits" in {
      readOk(format, Json.obj("bucketKey" -> "b", "quota" -> Json.obj("daily" -> 5))).quota mustBe AllowedQuota(daily = 5L)
    }

    "read a missing quota as the unlimited quota" in {
      readOk(format, Json.obj("bucketKey" -> "b")).quota mustBe AllowedQuota()
    }

    "read a quota that is not an object as the unlimited quota" in {
      readOk(format, Json.obj("bucketKey" -> "b", "quota" -> 5)).quota mustBe AllowedQuota()
    }

    "ignore a capacity written as a string" in {
      readOk(format, Json.obj("bucketKey" -> "b", "capacity" -> "10")).capacity mustBe 300L
    }

    "ignore refill settings that are not whole numbers" in {
      val back = readOk(format, Json.obj("bucketKey" -> "b", "refillRequestIntervalMs" -> 1.5, "refillRequestedTokens" -> "2"))
      back.refillRequestIntervalMs mustBe 50L
      back.refillRequestedTokens mustBe 50L
    }

    "draw a bucket key when it is not a string" in {
      val back = readOk(format, Json.obj("bucketKey" -> 42))
      back.bucketKey must not be "42"
      back.bucketKey must not be empty
    }

    "not check the id it reads" in {
      readOk(format, Json.obj("id" -> "FixedWindowStrategyConfig", "bucketKey" -> "b")) mustBe LocalTokensBucketStrategyConfig(
        bucketKey = "b"
      )
    }

    "not read the snake case keys" in {
      val back = readOk(format, Json.obj("bucket_key" -> "b", "refill_requested_tokens" -> 1))
      back.bucketKey must not be "b"
      back.refillRequestedTokens mustBe 50L
    }

    "refill a thousand tokens per second by default" in {
      LocalTokensBucketStrategyConfig().refillRatePerSecond mustBe 1000.0
    }

    "compute its refill rate per second" in {
      LocalTokensBucketStrategyConfig(refillRequestIntervalMs = 1000L, refillRequestedTokens = 1L).refillRatePerSecond mustBe 1.0
      LocalTokensBucketStrategyConfig(refillRequestIntervalMs = 100L, refillRequestedTokens = 10L).refillRatePerSecond mustBe 100.0
      LocalTokensBucketStrategyConfig(refillRequestIntervalMs = 2000L, refillRequestedTokens = 1L).refillRatePerSecond mustBe 0.5
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "expose the format as its strategy format" in {
      full.fmt.writes(full) mustBe format.writes(full)
      full.fmt.reads(format.writes(full)) mustBe JsSuccess(full)
    }

    "be the default config of LocalTokenBucket, but for its random bucket key" in {
      val plugin = new LocalTokenBucket()
      val json   = plugin.defaultConfig.get
      val back   = readOk(format, json)
      back.bucketKey mustBe (json \ "bucketKey").as[String]
      back mustBe LocalTokensBucketStrategyConfig(bucketKey = back.bucketKey)
    }
  }

  "DistributedRedisThrottlingStrategyConfig" should {

    val format = DistributedRedisThrottlingStrategyConfig.format

    val full = DistributedRedisThrottlingStrategyConfig(bucketKey = Some("${apikey.clientId}"), quota = customQuota)

    "have no bucket key and an unlimited quota by default" in {
      DistributedRedisThrottlingStrategyConfig() mustBe DistributedRedisThrottlingStrategyConfig(None, AllowedQuota())
    }

    "be identified as DistributedRedisThrottlingStrategyConfig" in {
      DistributedRedisThrottlingStrategyConfig().id mustBe "DistributedRedisThrottlingStrategyConfig"
    }

    "write the default config" in {
      format.writes(DistributedRedisThrottlingStrategyConfig()) mustBe Json.obj(
        "id"        -> "DistributedRedisThrottlingStrategyConfig",
        "quota"     -> unlimitedQuotaJson,
        "bucketKey" -> JsNull
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "id"        -> "DistributedRedisThrottlingStrategyConfig",
        "quota"     -> customQuotaJson,
        "bucketKey" -> "${apikey.clientId}"
      )
    }

    "round trip the default config" in {
      roundTrip(format, DistributedRedisThrottlingStrategyConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a bucket key alone" in {
      roundTrip(format, DistributedRedisThrottlingStrategyConfig(bucketKey = Some("k"))).bucketKey mustBe Some("k")
    }

    "round trip a quota alone" in {
      roundTrip(format, DistributedRedisThrottlingStrategyConfig(quota = customQuota)).quota mustBe customQuota
    }

    "not read an empty object, the quota is mandatory" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a config without quota" in {
      readError(format, Json.obj("bucketKey" -> "k"))
    }

    "read a null quota as the unlimited quota" in {
      readOk(format, Json.obj("quota" -> JsNull)) mustBe DistributedRedisThrottlingStrategyConfig()
    }

    "read a quota alone without bucket key" in {
      readOk(format, Json.obj("quota" -> customQuotaJson)) mustBe DistributedRedisThrottlingStrategyConfig(quota = customQuota)
    }

    "ignore a bucket key that is not a string" in {
      readOk(format, Json.obj("quota" -> customQuotaJson, "bucketKey" -> 42)).bucketKey mustBe None
    }

    "read a null bucket key as no bucket key" in {
      readOk(format, Json.obj("quota" -> customQuotaJson, "bucketKey" -> JsNull)).bucketKey mustBe None
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "expose the format as its strategy format" in {
      full.fmt.reads(format.writes(full)) mustBe JsSuccess(full)
    }

    "be the default config of DistributedRedisThrottling" in {
      defaultConfigReadsBack(new DistributedRedisThrottling(), format) mustBe DistributedRedisThrottlingStrategyConfig()
    }
  }

  "LuaDistributedRedisThrottlingStrategyConfig" should {

    val format = LuaDistributedRedisThrottlingStrategyConfig.format

    val full = LuaDistributedRedisThrottlingStrategyConfig(bucketKey = Some("${req.ip_address}"), quota = customQuota)

    "have no bucket key and an unlimited quota by default" in {
      LuaDistributedRedisThrottlingStrategyConfig() mustBe LuaDistributedRedisThrottlingStrategyConfig(None, AllowedQuota())
    }

    "be identified as LuaDistributedRedisThrottlingStrategyConfig" in {
      LuaDistributedRedisThrottlingStrategyConfig().id mustBe "LuaDistributedRedisThrottlingStrategyConfig"
    }

    "write the default config" in {
      format.writes(LuaDistributedRedisThrottlingStrategyConfig()) mustBe Json.obj(
        "id"        -> "LuaDistributedRedisThrottlingStrategyConfig",
        "quota"     -> unlimitedQuotaJson,
        "bucketKey" -> JsNull
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "id"        -> "LuaDistributedRedisThrottlingStrategyConfig",
        "quota"     -> customQuotaJson,
        "bucketKey" -> "${req.ip_address}"
      )
    }

    "round trip the default config" in {
      roundTrip(format, LuaDistributedRedisThrottlingStrategyConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a bucket key alone" in {
      roundTrip(format, LuaDistributedRedisThrottlingStrategyConfig(bucketKey = Some("k"))).bucketKey mustBe Some("k")
    }

    "not read an empty object, the quota is mandatory" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "read a null quota as the unlimited quota" in {
      readOk(format, Json.obj("quota" -> JsNull)) mustBe LuaDistributedRedisThrottlingStrategyConfig()
    }

    "read a partial quota and default its other limits" in {
      readOk(format, Json.obj("quota" -> Json.obj("monthly" -> 3))).quota mustBe AllowedQuota(monthly = 3L)
    }

    "ignore a bucket key that is not a string" in {
      readOk(format, Json.obj("quota" -> customQuotaJson, "bucketKey" -> Json.arr("k"))).bucketKey mustBe None
    }

    "read the json of the redis strategy as a lua strategy, ids are not checked" in {
      val redis = DistributedRedisThrottlingStrategyConfig(bucketKey = Some("k"), quota = customQuota)
      readOk(format, redis.json) mustBe LuaDistributedRedisThrottlingStrategyConfig(bucketKey = Some("k"), quota = customQuota)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "expose the format as its strategy format" in {
      full.fmt.reads(format.writes(full)) mustBe JsSuccess(full)
    }

    "be the default config of LuaDistributedRedisThrottling" in {
      defaultConfigReadsBack(new LuaDistributedRedisThrottling(), format) mustBe LuaDistributedRedisThrottlingStrategyConfig()
    }
  }

  "FixedWindowStrategyConfig" should {

    val format = FixedWindowStrategyConfig.format

    val full = FixedWindowStrategyConfig(bucketKey = Some("${apikey.clientId}"), windowDurationMs = 60000L, quota = customQuota)

    "use a ten seconds window, no bucket key and an unlimited quota by default" in {
      FixedWindowStrategyConfig() mustBe FixedWindowStrategyConfig(None, 10000L, AllowedQuota())
    }

    "be identified as FixedWindowStrategyConfig" in {
      FixedWindowStrategyConfig().id mustBe "FixedWindowStrategyConfig"
    }

    "write the default config" in {
      format.writes(FixedWindowStrategyConfig()) mustBe Json.obj(
        "id"               -> "FixedWindowStrategyConfig",
        "quota"            -> unlimitedQuotaJson,
        "windowDurationMs" -> 10000,
        "bucketKey"        -> JsNull
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "id"               -> "FixedWindowStrategyConfig",
        "quota"            -> customQuotaJson,
        "windowDurationMs" -> 60000,
        "bucketKey"        -> "${apikey.clientId}"
      )
    }

    "round trip the default config" in {
      roundTrip(format, FixedWindowStrategyConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a window duration alone" in {
      roundTrip(format, FixedWindowStrategyConfig(windowDurationMs = 1L)).windowDurationMs mustBe 1L
    }

    "round trip a one day window" in {
      roundTrip(format, FixedWindowStrategyConfig(windowDurationMs = 86400000L)).windowDurationMs mustBe 86400000L
    }

    "not read an empty object, the quota is mandatory" in {
      readError(format, Json.obj())
    }

    "not read a config without quota" in {
      readError(format, Json.obj("windowDurationMs" -> 1000))
    }

    "read a quota alone and default the window" in {
      readOk(format, Json.obj("quota" -> customQuotaJson)) mustBe FixedWindowStrategyConfig(quota = customQuota)
    }

    "read the window duration with a quota" in {
      readOk(format, Json.obj("quota" -> JsNull, "windowDurationMs" -> 1000)) mustBe FixedWindowStrategyConfig(
        windowDurationMs = 1000L
      )
    }

    "ignore a window duration written as a string" in {
      readOk(format, Json.obj("quota" -> JsNull, "windowDurationMs" -> "1000")).windowDurationMs mustBe 10000L
    }

    "ignore a window duration that is not a whole number" in {
      readOk(format, Json.obj("quota" -> JsNull, "windowDurationMs" -> 1000.5)).windowDurationMs mustBe 10000L
    }

    "not read the snake case window key" in {
      readOk(format, Json.obj("quota" -> JsNull, "window_duration_ms" -> 1000)).windowDurationMs mustBe 10000L
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "expose the format as its strategy format" in {
      full.fmt.reads(format.writes(full)) mustBe JsSuccess(full)
    }

    "be the default config of FixedWindow" in {
      defaultConfigReadsBack(new FixedWindow(), format) mustBe FixedWindowStrategyConfig()
    }
  }

  "LegacyThrottlingStrategyConfig" should {

    val format = LegacyThrottlingStrategyConfig.format

    "have an unlimited quota by default" in {
      LegacyThrottlingStrategyConfig().quota mustBe AllowedQuota()
    }

    "be identified as LegacyThrottlingStrategyConfig" in {
      LegacyThrottlingStrategyConfig().id mustBe "LegacyThrottlingStrategyConfig"
    }

    "write the default config" in {
      format.writes(LegacyThrottlingStrategyConfig()) mustBe Json.obj(
        "quota" -> unlimitedQuotaJson,
        "id"    -> "LegacyThrottlingStrategyConfig"
      )
    }

    "round trip the default config" in {
      roundTrip(format, LegacyThrottlingStrategyConfig())
    }

    "round trip a custom quota" in {
      roundTrip(format, LegacyThrottlingStrategyConfig(customQuota)).quota mustBe customQuota
    }

    "not read an empty object, the quota is mandatory" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "read a null quota as the unlimited quota" in {
      readOk(format, Json.obj("quota" -> JsNull)) mustBe LegacyThrottlingStrategyConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, LegacyThrottlingStrategyConfig(customQuota))
    }

    "expose the format as its strategy format" in {
      val config = LegacyThrottlingStrategyConfig(customQuota)
      config.fmt.reads(format.writes(config)) mustBe JsSuccess(config)
    }
  }

  "ThrottlingStrategyConfig" should {

    val format = ThrottlingStrategyConfig.fmt

    "read a local token bucket config from its id" in {
      val config = LocalTokensBucketStrategyConfig(bucketKey = "b", capacity = 10L)
      readOk(format, config.json) mustBe config
    }

    "read a fixed window config from its id" in {
      val config = FixedWindowStrategyConfig(windowDurationMs = 5000L, quota = customQuota)
      readOk(format, config.json) mustBe config
    }

    "read a legacy throttling config from its id" in {
      val config = LegacyThrottlingStrategyConfig(customQuota)
      readOk(format, config.json) mustBe config
    }

    "read a distributed redis config from its id" in {
      val config = DistributedRedisThrottlingStrategyConfig(bucketKey = Some("k"), quota = customQuota)
      readOk(format, config.json) mustBe config
    }

    "read a lua distributed redis config from its id" in {
      val config = LuaDistributedRedisThrottlingStrategyConfig(bucketKey = Some("k"), quota = customQuota)
      readOk(format, config.json) mustBe config
    }

    "round trip every strategy config" in {
      val configs: Seq[ThrottlingStrategyConfig] = Seq(
        LocalTokensBucketStrategyConfig(bucketKey = "b"),
        FixedWindowStrategyConfig(),
        LegacyThrottlingStrategyConfig(),
        DistributedRedisThrottlingStrategyConfig(),
        LuaDistributedRedisThrottlingStrategyConfig()
      )
      configs.foreach(config => roundTrip(format, config))
    }

    "write each strategy config with its own json" in {
      val config = FixedWindowStrategyConfig(bucketKey = Some("k"))
      format.writes(config) mustBe config.json
    }

    "tell the strategies apart only by their id" in {
      val json = FixedWindowStrategyConfig(quota = customQuota).json.as[JsObject] ++ Json.obj(
        "id" -> "LegacyThrottlingStrategyConfig"
      )
      readOk(format, json) mustBe LegacyThrottlingStrategyConfig(customQuota)
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a config without id" in {
      readError(format, Json.obj("quota" -> unlimitedQuotaJson))
    }

    "not read a config with an unknown id" in {
      readError(format, Json.obj("id" -> "SlidingWindowStrategyConfig", "quota" -> unlimitedQuotaJson))
    }

    "not read an id that is not a string" in {
      readError(format, Json.obj("id" -> 1, "quota" -> unlimitedQuotaJson))
    }

    "not read a strategy config whose own format fails" in {
      readError(format, Json.obj("id" -> "FixedWindowStrategyConfig"))
    }

    "read the default config of every rate limiting plugin" in {
      readOk(format, new LocalTokenBucket().defaultConfig.get) mustBe a[LocalTokensBucketStrategyConfig]
      readOk(format, new FixedWindow().defaultConfig.get) mustBe FixedWindowStrategyConfig()
      readOk(format, new DistributedRedisThrottling().defaultConfig.get) mustBe DistributedRedisThrottlingStrategyConfig()
      readOk(format, new LuaDistributedRedisThrottling().defaultConfig.get) mustBe LuaDistributedRedisThrottlingStrategyConfig()
    }
  }
}
