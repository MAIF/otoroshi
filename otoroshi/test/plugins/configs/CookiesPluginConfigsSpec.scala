package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*
import play.api.mvc.Cookie.SameSite

class CookiesPluginConfigsSpec extends PluginConfigFormatSpec {

  "AdditionalCookieOutConfig" should {

    val format = AdditionalCookieOutConfig.format

    val full = AdditionalCookieOutConfig(
      name = "session",
      value = "${req.id}",
      domain = Some("oto.tools"),
      path = Some("/api"),
      maxAge = Some(3600L),
      secure = true,
      httpOnly = true,
      sameSite = Some(SameSite.Strict)
    )

    "default to a plain cookie named cookie" in {
      AdditionalCookieOutConfig.default mustBe AdditionalCookieOutConfig(
        "cookie",
        "value",
        None,
        None,
        None,
        secure = false,
        httpOnly = false,
        sameSite = None
      )
    }

    "write the default config with nulls for the missing attributes" in {
      format.writes(AdditionalCookieOutConfig.default) mustBe Json.obj(
        "name"     -> "cookie",
        "value"    -> "value",
        "domain"   -> JsNull,
        "path"     -> JsNull,
        "maxAge"   -> JsNull,
        "secure"   -> false,
        "httpOnly" -> false,
        "sameSite" -> JsNull
      )
    }

    "write every attribute under its camel case key" in {
      format.writes(full) mustBe Json.obj(
        "name"     -> "session",
        "value"    -> "${req.id}",
        "domain"   -> "oto.tools",
        "path"     -> "/api",
        "maxAge"   -> 3600,
        "secure"   -> true,
        "httpOnly" -> true,
        "sameSite" -> "Strict"
      )
    }

    "round trip the default config" in {
      roundTrip(format, AdditionalCookieOutConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip the lax same site policy" in {
      roundTrip(format, full.copy(sameSite = Some(SameSite.Lax))).sameSite mustBe Some(SameSite.Lax)
    }

    "round trip the none same site policy" in {
      roundTrip(format, full.copy(sameSite = Some(SameSite.None))).sameSite mustBe Some(SameSite.None)
    }

    "write the same site policies with their cookie attribute value" in {
      (format.writes(full.copy(sameSite = Some(SameSite.Lax))) \ "sameSite").as[String] mustBe "Lax"
      (format.writes(full.copy(sameSite = Some(SameSite.None))) \ "sameSite").as[String] mustBe "None"
    }

    "round trip a max age larger than an int" in {
      roundTrip(format, full.copy(maxAge = Some(Int.MaxValue.toLong * 10L))).maxAge mustBe Some(21474836470L)
    }

    "round trip a zero max age" in {
      roundTrip(format, full.copy(maxAge = Some(0L))).maxAge mustBe Some(0L)
    }

    "round trip an empty value" in {
      roundTrip(format, AdditionalCookieOutConfig("empty", ""))
    }

    "read name and value alone and default the other fields" in {
      readOk(format, Json.obj("name" -> "foo", "value" -> "bar")) mustBe AdditionalCookieOutConfig("foo", "bar")
    }

    "read the same site policy whatever its case" in {
      readOk(format, Json.obj("name" -> "foo", "value" -> "bar", "sameSite" -> "lax")).sameSite mustBe Some(
        SameSite.Lax
      )
      readOk(format, Json.obj("name" -> "foo", "value" -> "bar", "sameSite" -> "STRICT")).sameSite mustBe Some(
        SameSite.Strict
      )
    }

    "ignore an unknown same site policy" in {
      readOk(format, Json.obj("name" -> "foo", "value" -> "bar", "sameSite" -> "Sometimes")).sameSite mustBe None
    }

    "ignore a max age written as a string" in {
      readOk(format, Json.obj("name" -> "foo", "value" -> "bar", "maxAge" -> "3600")).maxAge mustBe None
    }

    "ignore a secure flag written as a string" in {
      readOk(format, Json.obj("name" -> "foo", "value" -> "bar", "secure" -> "true")).secure mustBe false
    }

    "ignore a domain that is not a string" in {
      readOk(format, Json.obj("name" -> "foo", "value" -> "bar", "domain" -> 42)).domain mustBe None
    }

    "not read the snake case keys" in {
      val json = Json.obj("name" -> "foo", "value" -> "bar", "max_age" -> 10, "http_only" -> true, "same_site" -> "Lax")
      readOk(format, json) mustBe AdditionalCookieOutConfig("foo", "bar")
    }

    "fail to read a config without name" in {
      readError(format, Json.obj("value" -> "bar"))
    }

    "fail to read a config without value" in {
      readError(format, Json.obj("name" -> "foo"))
    }

    "fail to read a name that is not a string" in {
      readError(format, Json.obj("name" -> 42, "value" -> "bar"))
    }

    "fail to read an empty object" in {
      readError(format, Json.obj())
    }

    "fail to read a json null" in {
      readError(format, JsNull)
    }

    "fail to read a json string" in {
      readError(format, JsString("foo=bar"))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "list in its config schema every field of its config flow" in {
      AdditionalCookieOutConfig.configSchema.get.keys must contain allElementsOf AdditionalCookieOutConfig.configFlow
    }

    "offer the three same site policies in its config schema" in {
      val options = (AdditionalCookieOutConfig.configSchema.get \ "sameSite" \ "props" \ "options").as[Seq[JsObject]]
      options.map(o => (o \ "value").as[String]).flatMap(SameSite.parse) mustBe Seq(
        SameSite.Lax,
        SameSite.Strict,
        SameSite.None
      )
    }

    "be the default config of AdditionalCookieOut" in {
      defaultConfigReadsBack(new AdditionalCookieOut(), format) mustBe AdditionalCookieOutConfig.default
    }

    "be the default config of MissingCookieOut" in {
      defaultConfigReadsBack(new MissingCookieOut(), format) mustBe AdditionalCookieOutConfig.default
    }

    "give its config flow and schema to the out plugins" in {
      new AdditionalCookieOut().configFlow mustBe AdditionalCookieOutConfig.configFlow
      new MissingCookieOut().configSchema mustBe AdditionalCookieOutConfig.configSchema
    }
  }

  "AdditionalCookieInConfig" should {

    val format = AdditionalCookieInConfig.format

    "default to a cookie named cookie" in {
      AdditionalCookieInConfig.default mustBe AdditionalCookieInConfig("cookie", "value")
    }

    "write the default config" in {
      format.writes(AdditionalCookieInConfig.default) mustBe Json.obj("name" -> "cookie", "value" -> "value")
    }

    "round trip the default config" in {
      roundTrip(format, AdditionalCookieInConfig.default)
    }

    "round trip a cookie holding expression language" in {
      roundTrip(format, AdditionalCookieInConfig("${req.headers.X-Name}", "${user.email}"))
    }

    "round trip an empty value" in {
      roundTrip(format, AdditionalCookieInConfig("flag", ""))
    }

    "ignore the attributes of an out cookie" in {
      val json = AdditionalCookieOutConfig.format.writes(
        AdditionalCookieOutConfig("foo", "bar", domain = Some("oto.tools"), secure = true)
      )
      readOk(format, json) mustBe AdditionalCookieInConfig("foo", "bar")
    }

    "fail to read a config without name" in {
      readError(format, Json.obj("value" -> "bar"))
    }

    "fail to read a config without value" in {
      readError(format, Json.obj("name" -> "foo"))
    }

    "fail to read a value that is not a string" in {
      readError(format, Json.obj("name" -> "foo", "value" -> 42))
    }

    "fail to read an empty object" in {
      readError(format, Json.obj())
    }

    "fail to read a json null" in {
      readError(format, JsNull)
    }

    "fail to read a json array" in {
      readError(format, Json.arr("foo", "bar"))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, AdditionalCookieInConfig("foo", "bar"))
    }

    "list name and value in its config flow and schema" in {
      AdditionalCookieInConfig.configFlow mustBe Seq("name", "value")
      AdditionalCookieInConfig.configSchema.get.keys must contain allElementsOf AdditionalCookieInConfig.configFlow
    }

    "be the default config of AdditionalCookieIn" in {
      defaultConfigReadsBack(new AdditionalCookieIn(), format) mustBe AdditionalCookieInConfig.default
    }

    "read the default config of MissingCookieIn as the default in config" in {
      readOk(format, new MissingCookieIn().defaultConfig.get) mustBe AdditionalCookieInConfig.default
    }

    "give its config flow and schema to the in plugins" in {
      new AdditionalCookieIn().configFlow mustBe AdditionalCookieInConfig.configFlow
      new MissingCookieIn().configSchema mustBe AdditionalCookieInConfig.configSchema
    }
  }

  "RemoveCookiesInConfig" should {

    val format = RemoveCookiesInConfig.format

    "default to no name" in {
      RemoveCookiesInConfig.default.names mustBe empty
    }

    "write the default config as an empty names array" in {
      format.writes(RemoveCookiesInConfig.default) mustBe Json.obj("names" -> Json.arr())
    }

    "write the names" in {
      format.writes(RemoveCookiesInConfig(Seq("session", "tracking"))) mustBe Json.obj(
        "names" -> Json.arr("session", "tracking")
      )
    }

    "round trip the default config" in {
      roundTrip(format, RemoveCookiesInConfig.default)
    }

    "round trip several names and keep their order" in {
      roundTrip(format, RemoveCookiesInConfig(Seq("c", "a", "b"))).names mustBe Seq("c", "a", "b")
    }

    "keep duplicated names" in {
      roundTrip(format, RemoveCookiesInConfig(Seq("a", "a"))).names mustBe Seq("a", "a")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe RemoveCookiesInConfig.default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe RemoveCookiesInConfig.default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("session")) mustBe RemoveCookiesInConfig.default
    }

    "ignore names written as a single string" in {
      readOk(format, Json.obj("names" -> "session")) mustBe RemoveCookiesInConfig.default
    }

    "ignore a names array holding something else than strings" in {
      readOk(format, Json.obj("names" -> Json.arr("session", 1))) mustBe RemoveCookiesInConfig.default
    }

    "not read the singular name key" in {
      readOk(format, Json.obj("name" -> Json.arr("session"))) mustBe RemoveCookiesInConfig.default
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, RemoveCookiesInConfig(Seq("session")))
    }

    "list names in its config flow and schema" in {
      RemoveCookiesInConfig.configFlow mustBe Seq("names")
      RemoveCookiesInConfig.configSchema.get.keys must contain("names")
    }

    "be the default config of RemoveCookiesIn" in {
      defaultConfigReadsBack(new RemoveCookiesIn(), format) mustBe RemoveCookiesInConfig.default
    }

    "be the default config of RemoveCookiesOut" in {
      defaultConfigReadsBack(new RemoveCookiesOut(), format) mustBe RemoveCookiesInConfig.default
    }
  }

  "CookiesValidationConfig" should {

    val format = CookiesValidationConfig.format

    "default to no cookie" in {
      CookiesValidationConfig().cookies mustBe empty
    }

    "write the default config as an empty cookies object" in {
      format.writes(CookiesValidationConfig()) mustBe Json.obj("cookies" -> Json.obj())
    }

    "write the expected cookies" in {
      format.writes(CookiesValidationConfig(Map("session" -> "abc"))) mustBe Json.obj(
        "cookies" -> Json.obj("session" -> "abc")
      )
    }

    "round trip the default config" in {
      roundTrip(format, CookiesValidationConfig())
    }

    "round trip several cookies" in {
      roundTrip(format, CookiesValidationConfig(Map("session" -> "abc", "lang" -> "fr", "empty" -> "")))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe CookiesValidationConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe CookiesValidationConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("session=abc")) mustBe CookiesValidationConfig()
    }

    "ignore cookies written as an array" in {
      readOk(format, Json.obj("cookies" -> Json.arr("session"))) mustBe CookiesValidationConfig()
    }

    "ignore the whole map as soon as a value is not a string" in {
      readOk(format, Json.obj("cookies" -> Json.obj("session" -> "abc", "count" -> 1))) mustBe CookiesValidationConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, CookiesValidationConfig(Map("session" -> "abc")))
    }

    "list cookies in its config flow and schema" in {
      CookiesValidationConfig.configFlow mustBe Seq("cookies")
      CookiesValidationConfig.configSchema.get.keys must contain("cookies")
    }

    "be the default config of CookiesValidation" in {
      defaultConfigReadsBack(new CookiesValidation(), format) mustBe CookiesValidationConfig()
    }
  }
}
