package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

class DeferPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgDeferPluginConfig" should {

    val format = NgDeferPluginConfig.format

    "not defer by default" in {
      NgDeferPluginConfig().duration mustBe FiniteDuration(0, TimeUnit.MILLISECONDS)
    }

    "write the default config" in {
      format.writes(NgDeferPluginConfig()) mustBe Json.obj("duration" -> 0)
    }

    "write the duration in milliseconds" in {
      format.writes(NgDeferPluginConfig(2.seconds)) mustBe Json.obj("duration" -> 2000)
    }

    "round trip the default config" in {
      roundTrip(format, NgDeferPluginConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, NgDeferPluginConfig(1500.millis)).duration.toMillis mustBe 1500L
    }

    "round trip a duration expressed in another unit" in {
      roundTrip(format, NgDeferPluginConfig(1.minute)).duration mustBe 60000.millis
    }

    "round trip the five minutes cap of the plugin" in {
      roundTrip(format, NgDeferPluginConfig(300000.millis))
    }

    "round trip a negative duration as is" in {
      roundTrip(format, NgDeferPluginConfig(-10.millis)).duration.toMillis mustBe -10L
    }

    "truncate a duration below the millisecond" in {
      val json = format.writes(NgDeferPluginConfig(1500.micros))
      json mustBe Json.obj("duration" -> 1)
      readOk(format, json).duration mustBe 1.millis
    }

    "read the duration as milliseconds" in {
      readOk(format, Json.obj("duration" -> 250)).duration mustBe 250.millis
    }

    "ignore a duration written as a string" in {
      readOk(format, Json.obj("duration" -> "250")) mustBe NgDeferPluginConfig()
    }

    "ignore a duration written as a finite duration string" in {
      readOk(format, Json.obj("duration" -> "250 milliseconds")) mustBe NgDeferPluginConfig()
    }

    "ignore a duration that is not a whole number" in {
      readOk(format, Json.obj("duration" -> 1.5)) mustBe NgDeferPluginConfig()
    }

    "read a whole number written with a decimal part" in {
      readOk(format, Json.obj("duration" -> JsNumber(BigDecimal("100.0")))).duration mustBe 100.millis
    }

    "read a null duration as no defer" in {
      readOk(format, Json.obj("duration" -> JsNull)) mustBe NgDeferPluginConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgDeferPluginConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgDeferPluginConfig()
    }

    "read a json number as the default config" in {
      readOk(format, JsNumber(250)) mustBe NgDeferPluginConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(250)) mustBe NgDeferPluginConfig()
    }

    "not read a delay key" in {
      readOk(format, Json.obj("delay" -> 250)) mustBe NgDeferPluginConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgDeferPluginConfig(42.millis))
    }

    "be the default config of NgDeferPlugin" in {
      defaultConfigReadsBack(new NgDeferPlugin(), format) mustBe NgDeferPluginConfig()
    }
  }
}
