package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class ImgPluginConfigsSpec extends PluginConfigFormatSpec {

  "ImageReplacerConfig" should {

    val format = ImageReplacerConfig.format

    "default to the otoroshi logo" in {
      ImageReplacerConfig().url mustBe "https://raw.githubusercontent.com/MAIF/otoroshi/master/resources/otoroshi-logo.png"
      ImageReplacerConfig().url mustBe ImageReplacerConfig.defaultUrl
    }

    "write the default config with the logo url" in {
      format.writes(ImageReplacerConfig()) mustBe Json.obj("url" -> ImageReplacerConfig.defaultUrl)
    }

    "write the url under url" in {
      format.writes(ImageReplacerConfig("https://img.oto.tools/cat.png")) mustBe Json.obj(
        "url" -> "https://img.oto.tools/cat.png"
      )
    }

    "round trip the default config" in {
      roundTrip(format, ImageReplacerConfig())
    }

    "round trip another url" in {
      roundTrip(format, ImageReplacerConfig("https://img.oto.tools/cat.png"))
    }

    "round trip a url with a query string and an encoded path" in {
      roundTrip(format, ImageReplacerConfig("http://localhost:8080/images/a%20b.jpg?size=small&format=webp"))
    }

    "round trip an empty url as an empty string, not as the default url" in {
      roundTrip(format, ImageReplacerConfig("")).url mustBe ""
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe ImageReplacerConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe ImageReplacerConfig()
    }

    "read a json string as the default config, not as the url" in {
      readOk(format, JsString("https://img.oto.tools/cat.png")) mustBe ImageReplacerConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("https://img.oto.tools/cat.png")) mustBe ImageReplacerConfig()
    }

    "read a null url as the default url" in {
      readOk(format, Json.obj("url" -> JsNull)) mustBe ImageReplacerConfig()
    }

    "ignore a url that is not a string" in {
      readOk(format, Json.obj("url" -> 42)) mustBe ImageReplacerConfig()
    }

    "ignore a url given as an object" in {
      readOk(format, Json.obj("url" -> Json.obj("href" -> "https://img.oto.tools/cat.png"))) mustBe ImageReplacerConfig()
    }

    "not read an image_url key" in {
      readOk(format, Json.obj("image_url" -> "https://img.oto.tools/cat.png")) mustBe ImageReplacerConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("url" -> "https://img.oto.tools/cat.png", "foo" -> "bar")) mustBe ImageReplacerConfig(
        "https://img.oto.tools/cat.png"
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, ImageReplacerConfig("https://img.oto.tools/cat.png"))
    }

    "be the default config of ImageReplacer" in {
      defaultConfigReadsBack(new ImageReplacer(), format) mustBe ImageReplacerConfig()
    }
  }
}
