package plugins

import functional.PluginsTestSpec
import otoroshi.next.plugins.*
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap

// the counters must only ever move for a call that went through. a call turned away by the rate
// limit or by a quota counts nothing at all: without that, a consumer hammering an api it is no
// longer allowed on burns a whole day of quota without one call being served
class RateLimitingTests(parent: PluginsTestSpec) {
  import parent.*

  // every test needs counters of its own: the datastore lives as long as the spec does
  private def keyFor(name: String): String = s"rl-$name-${IdGenerator.uuid}"

  // the default strategy, the one an apikey without any throttling settings gets
  def deniedCallIsNeverCounted(): Unit = {
    val key      = keyFor("window")
    val quota    = AllowedQuota(window = 2, daily = 5, monthly = 10)
    val strategy = env.rateLimiter.getOrCreate(key, TypedMap.empty, None)

    strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe true
    strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe true

    val denied = strategy.checkAndIncrement(key, 1, quota, 60).futureValue
    denied.allowed mustBe false
    // the window holds what went through, and nothing else
    denied.quotas.window.consumed mustBe 2
    denied.quotas.daily.consumed mustBe 2

    // hammering a window that is already full cannot burn the quotas of the whole day either
    (1 to 5).foreach(_ => strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe false)
    val state = strategy.check(key, quota).futureValue
    state.allowed mustBe false
    state.quotas.window.consumed mustBe 2
    state.quotas.daily.consumed mustBe 2
  }

  def deniedByDailyQuotaCountsNothing(): Unit = {
    val key      = keyFor("daily")
    val quota    = AllowedQuota(window = 100, daily = 2, monthly = 100)
    val strategy = env.rateLimiter.getOrCreate(key, TypedMap.empty, None)

    strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe true
    strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe true

    val denied = strategy.checkAndIncrement(key, 1, quota, 60).futureValue
    denied.allowed mustBe false
    denied.quotas.daily.consumed mustBe 2
    // the rate limit of the window did not take that call either
    denied.quotas.window.consumed mustBe 2

    // check answers the very same thing, so the plugins that only check turn away the same calls
    strategy.check(key, quota).futureValue.allowed mustBe false
  }

  // the fixed window strategy used to answer allowed for every call its window let through, whatever
  // the daily and the monthly counters said
  def fixedWindowEnforcesDailyQuota(): Unit = {
    val key      = keyFor("fixedwindow-daily")
    val quota    = AllowedQuota(window = 100, daily = 2, monthly = 100)
    val strategy = ThrottlingStrategy(FixedWindowStrategyConfig(windowDurationMs = 60000, quota = quota), key)

    strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe true
    strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe true

    val denied = strategy.checkAndIncrement(key, 1, quota, 60).futureValue
    denied.allowed mustBe false
    // a call the daily quota turns away does not take a slot in the window
    denied.quotas.window.consumed mustBe 2
    strategy.check(key, quota).futureValue.allowed mustBe false
  }

  def fixedWindowServesExactlyItsWindow(): Unit = {
    val key      = keyFor("fixedwindow-window")
    val quota    = AllowedQuota(window = 2, daily = 100, monthly = 100)
    val strategy = ThrottlingStrategy(FixedWindowStrategyConfig(windowDurationMs = 60000, quota = quota), key)

    // the very first call of a window goes through, and the window holds exactly what it allows
    strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe true
    val second = strategy.checkAndIncrement(key, 1, quota, 60).futureValue
    second.allowed mustBe true
    second.quotas.window.consumed mustBe 2

    strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe false
    // and the calls the window turned away are not part of the day of that consumer
    strategy.check(key, quota).futureValue.quotas.daily.consumed mustBe 2
  }

  // the local bucket never asks the datastore whether the call fits in the window, so its tokens are
  // what a call turned away by a quota must not consume
  def tokenBucketKeepsItsTokensForServedCalls(): Unit = {
    val key      = keyFor("bucket")
    val quota    = AllowedQuota(window = 100, daily = 1, monthly = 100)
    val strategy = ThrottlingStrategy(
      LocalTokensBucketStrategyConfig(capacity = 3, refillRequestIntervalMs = 60000, quota = quota),
      key
    )

    strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe true
    // the daily quota is spent, so the next calls are turned away without eating a token
    strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe false
    strategy.checkAndIncrement(key, 1, quota, 60).futureValue.allowed mustBe false

    // two tokens are left, exactly the calls the bucket never served
    val larger = AllowedQuota(window = 100, daily = 100, monthly = 100)
    strategy.checkAndIncrement(key, 1, larger, 60).futureValue.allowed mustBe true
    strategy.checkAndIncrement(key, 1, larger, 60).futureValue.allowed mustBe true
    strategy.checkAndIncrement(key, 1, larger, 60).futureValue.allowed mustBe false
  }
}
