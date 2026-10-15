package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class UsersPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgHasAllowedUsersValidatorConfig" should {

    val format = NgHasAllowedUsersValidatorConfig.format

    val custom = NgHasAllowedUsersValidatorConfig(
      usernames = Seq("alice", "bob"),
      emails = Seq("alice@oto.tools"),
      emailDomains = Seq("oto.tools", "@maif.fr"),
      metadataMatch = Seq("$.team[?(@ == 'core')]"),
      metadataNotMatch = Seq("$.banned"),
      otoroshiDataMatch = Seq("admin"),
      otoroshiDataNotMatch = Seq("guest"),
      profileMatch = Seq("$.email_verified"),
      profileNotMatch = Seq("$.locked")
    )

    "write the default config as empty arrays under snake case keys" in {
      format.writes(NgHasAllowedUsersValidatorConfig()) mustBe Json.obj(
        "usernames"               -> Json.arr(),
        "emails"                  -> Json.arr(),
        "email_domains"           -> Json.arr(),
        "metadata_match"          -> Json.arr(),
        "metadata_not_match"      -> Json.arr(),
        "otoroshi_data_match"     -> Json.arr(),
        "otoroshi_data_not_match" -> Json.arr(),
        "profile_match"           -> Json.arr(),
        "profile_not_match"       -> Json.arr()
      )
    }

    "write every field under its own key" in {
      val json = written(format, custom)
      (json \ "email_domains").as[Seq[String]] mustBe Seq("oto.tools", "@maif.fr")
      (json \ "metadata_not_match").as[Seq[String]] mustBe Seq("$.banned")
      (json \ "otoroshi_data_match").as[Seq[String]] mustBe Seq("admin")
      (json \ "otoroshi_data_not_match").as[Seq[String]] mustBe Seq("guest")
      (json \ "profile_not_match").as[Seq[String]] mustBe Seq("$.locked")
    }

    "round trip the default config" in {
      roundTrip(format, NgHasAllowedUsersValidatorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, custom)
    }

    "round trip match rules alone" in {
      roundTrip(format, NgHasAllowedUsersValidatorConfig(usernames = Seq("alice"), profileMatch = Seq("$.admin")))
    }

    "round trip not match rules alone" in {
      roundTrip(
        format,
        NgHasAllowedUsersValidatorConfig(metadataNotMatch = Seq("$.a"), otoroshiDataNotMatch = Seq("b"), profileNotMatch = Seq("$.c"))
      )
    }

    "keep the order, the duplicates and the case of the values" in {
      roundTrip(format, NgHasAllowedUsersValidatorConfig(emails = Seq("B@oto.tools", "a@oto.tools", "B@oto.tools"))).emails mustBe Seq(
        "B@oto.tools",
        "a@oto.tools",
        "B@oto.tools"
      )
    }

    "read each key into its own field" in {
      readOk(format, Json.obj("usernames" -> Json.arr("u"))) mustBe NgHasAllowedUsersValidatorConfig(usernames = Seq("u"))
      readOk(format, Json.obj("emails" -> Json.arr("e"))) mustBe NgHasAllowedUsersValidatorConfig(emails = Seq("e"))
      readOk(format, Json.obj("email_domains" -> Json.arr("d"))) mustBe NgHasAllowedUsersValidatorConfig(emailDomains = Seq("d"))
      readOk(format, Json.obj("metadata_match" -> Json.arr("m"))) mustBe NgHasAllowedUsersValidatorConfig(
        metadataMatch = Seq("m")
      )
      readOk(format, Json.obj("metadata_not_match" -> Json.arr("m"))) mustBe NgHasAllowedUsersValidatorConfig(
        metadataNotMatch = Seq("m")
      )
      readOk(format, Json.obj("otoroshi_data_match" -> Json.arr("o"))) mustBe NgHasAllowedUsersValidatorConfig(
        otoroshiDataMatch = Seq("o")
      )
      readOk(format, Json.obj("otoroshi_data_not_match" -> Json.arr("o"))) mustBe NgHasAllowedUsersValidatorConfig(
        otoroshiDataNotMatch = Seq("o")
      )
      readOk(format, Json.obj("profile_match" -> Json.arr("p"))) mustBe NgHasAllowedUsersValidatorConfig(
        profileMatch = Seq("p")
      )
      readOk(format, Json.obj("profile_not_match" -> Json.arr("p"))) mustBe NgHasAllowedUsersValidatorConfig(
        profileNotMatch = Seq("p")
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgHasAllowedUsersValidatorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgHasAllowedUsersValidatorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("alice")) mustBe NgHasAllowedUsersValidatorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("alice")) mustBe NgHasAllowedUsersValidatorConfig()
    }

    "ignore a list written as a string and keep the others" in {
      readOk(format, Json.obj("usernames" -> "alice", "emails" -> Json.arr("alice@oto.tools"))) mustBe NgHasAllowedUsersValidatorConfig(
        emails = Seq("alice@oto.tools")
      )
    }

    "ignore a list holding something else than strings" in {
      readOk(format, Json.obj("email_domains" -> Json.arr("oto.tools", JsNull))) mustBe NgHasAllowedUsersValidatorConfig()
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("emailDomains" -> Json.arr("oto.tools"), "profileMatch" -> Json.arr("$.a"), "metadataNotMatch" -> Json.arr("$.b"))
      ) mustBe NgHasAllowedUsersValidatorConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, custom)
    }

    "be the default config of NgHasAllowedUsersValidator" in {
      defaultConfigReadsBack(new NgHasAllowedUsersValidator(), format) mustBe NgHasAllowedUsersValidatorConfig()
    }

    "match an email in the exact domain" in {
      NgHasAllowedUsersValidator.emailInDomain("alice@oto.tools", "oto.tools") mustBe true
    }

    "match an email in a subdomain" in {
      NgHasAllowedUsersValidator.emailInDomain("alice@eu.oto.tools", "oto.tools") mustBe true
    }

    "not match an email in a lookalike domain" in {
      NgHasAllowedUsersValidator.emailInDomain("eve@evil-oto.tools", "oto.tools") mustBe false
      NgHasAllowedUsersValidator.emailInDomain("eve@evilexample.com", "example.com") mustBe false
      NgHasAllowedUsersValidator.emailInDomain("eve@oto.tools.evil.com", "oto.tools") mustBe false
    }

    "accept a domain written with a leading @ and ignore the case" in {
      NgHasAllowedUsersValidator.emailInDomain("alice@OTO.tools", "@Oto.Tools") mustBe true
    }

    "take the domain after the last @" in {
      NgHasAllowedUsersValidator.emailInDomain("\"a@evil.com\"@oto.tools", "oto.tools") mustBe true
      NgHasAllowedUsersValidator.emailInDomain("alice@oto.tools@evil.com", "oto.tools") mustBe false
    }
  }

  "NgJwtUserExtractorConfig" should {

    val format = NgJwtUserExtractorConfig.format

    "be strict, not stripped and without paths by default" in {
      NgJwtUserExtractorConfig("v") mustBe NgJwtUserExtractorConfig("v", strict = true, strip = false, None, None, None)
    }

    "write the default config of the plugin" in {
      format.writes(NgJwtUserExtractorConfig("none")) mustBe Json.obj(
        "verifier"   -> "none",
        "strict"     -> true,
        "strip"      -> false,
        "name_path"  -> JsNull,
        "email_path" -> JsNull,
        "meta_path"  -> JsNull
      )
    }

    "write the paths under snake case keys" in {
      written(format, NgJwtUserExtractorConfig("v", namePath = Some("$.n"), emailPath = Some("$.e"), metaPath = Some("$.m"))) mustBe Json.obj(
        "verifier"   -> "v",
        "strict"     -> true,
        "strip"      -> false,
        "name_path"  -> "$.n",
        "email_path" -> "$.e",
        "meta_path"  -> "$.m"
      )
    }

    "round trip the default config of the plugin" in {
      roundTrip(format, NgJwtUserExtractorConfig("none"))
    }

    "round trip a lax and stripping config" in {
      roundTrip(format, NgJwtUserExtractorConfig("verifier-1", strict = false, strip = true))
    }

    "round trip a verifier id with special characters" in {
      roundTrip(format, NgJwtUserExtractorConfig("jwt_verifier_\"é\"/1")).verifier mustBe "jwt_verifier_\"é\"/1"
    }

    "fail to read an empty object" in {
      readError(format, Json.obj())
    }

    "fail to read a json null" in {
      readError(format, JsNull)
    }

    "fail to read a json string" in {
      readError(format, JsString("verifier-1"))
    }

    "fail to read a json array" in {
      readError(format, Json.arr(Json.obj("verifier" -> "v")))
    }

    "fail to read a verifier that is not a string" in {
      readError(format, Json.obj("verifier" -> 42))
    }

    "read a lone verifier and default the rest" in {
      readOk(format, Json.obj("verifier" -> "v")) mustBe NgJwtUserExtractorConfig("v")
    }

    "read the camel case path keys" in {
      readOk(
        format,
        Json.obj("verifier" -> "v", "namePath" -> "$.n", "emailPath" -> "$.e", "metaPath" -> "$.m")
      ) mustBe NgJwtUserExtractorConfig("v", namePath = Some("$.n"), emailPath = Some("$.e"), metaPath = Some("$.m"))
    }

    "ignore flags written as strings" in {
      readOk(format, Json.obj("verifier" -> "v", "strict" -> "false", "strip" -> "true")) mustBe NgJwtUserExtractorConfig("v")
    }

    "ignore paths that are not strings" in {
      readOk(format, Json.obj("verifier" -> "v", "namePath" -> 1, "metaPath" -> Json.arr("$.m"))) mustBe NgJwtUserExtractorConfig(
        "v"
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgJwtUserExtractorConfig("v", strict = false, strip = true, namePath = Some("$.n")))
    }

    "be the default config of NgJwtUserExtractor" in {
      defaultConfigReadsBack(new NgJwtUserExtractor(), format) mustBe NgJwtUserExtractorConfig("none")
    }
  }
}
