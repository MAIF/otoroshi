package plugins.configs

import otoroshi.next.plugins.*
import otoroshi.plugins.biscuit.BiscuitConfig
import play.api.libs.json.*

class BiscuitPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgBiscuitConfig" should {

    val format = NgBiscuitConfig.format

    val defaultLegacy = BiscuitConfig(
      publicKey = None,
      checks = Seq.empty,
      facts = Seq.empty,
      resources = Seq.empty,
      rules = Seq.empty,
      revocation_ids = Seq.empty,
      extractor = "header",
      extractorName = "Authorization",
      enforce = false
    )

    val full = NgBiscuitConfig(
      BiscuitConfig(
        publicKey = Some("a1b2c3d4e5f6"),
        checks = Seq("check if resource(\"file1\")", "check if time($t), $t < 2030-01-01T00:00:00Z"),
        facts = Seq("user(\"alice\")"),
        resources = Seq("file1", "file2"),
        rules = Seq("allowed($r) <- resource($r)"),
        revocation_ids = Seq("rev-1", "rev-2"),
        extractor = "query",
        extractorName = "biscuit_token",
        enforce = true
      )
    )

    "read the token from the authorization header without enforcing by default" in {
      NgBiscuitConfig().legacy mustBe defaultLegacy
    }

    "write the default config" in {
      format.writes(NgBiscuitConfig()) mustBe Json.obj(
        "public_key"     -> JsNull,
        "checks"         -> Json.arr(),
        "facts"          -> Json.arr(),
        "resources"      -> Json.arr(),
        "rules"          -> Json.arr(),
        "revocation_ids" -> Json.arr(),
        "extractor"      -> Json.obj("name" -> "Authorization", "type" -> "header"),
        "enforce"        -> false
      )
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "public_key"     -> "a1b2c3d4e5f6",
        "checks"         -> Json.arr("check if resource(\"file1\")", "check if time($t), $t < 2030-01-01T00:00:00Z"),
        "facts"          -> Json.arr("user(\"alice\")"),
        "resources"      -> Json.arr("file1", "file2"),
        "rules"          -> Json.arr("allowed($r) <- resource($r)"),
        "revocation_ids" -> Json.arr("rev-1", "rev-2"),
        "extractor"      -> Json.obj("name" -> "biscuit_token", "type" -> "query"),
        "enforce"        -> true
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgBiscuitConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config without public key" in {
      roundTrip(format, full.copy(legacy = full.legacy.copy(publicKey = None))).legacy.publicKey mustBe None
    }

    "round trip a header extractor" in {
      roundTrip(format, NgBiscuitConfig(defaultLegacy.copy(extractor = "header", extractorName = "X-Biscuit")))
    }

    "round trip a query extractor" in {
      roundTrip(format, NgBiscuitConfig(defaultLegacy.copy(extractor = "query", extractorName = "token")))
    }

    "round trip a cookie extractor" in {
      roundTrip(format, NgBiscuitConfig(defaultLegacy.copy(extractor = "cookie", extractorName = "session")))
    }

    "round trip an unknown extractor type as is" in {
      roundTrip(format, NgBiscuitConfig(defaultLegacy.copy(extractor = "body"))).legacy.extractor mustBe "body"
    }

    "keep the order of the checks, facts, resources, rules and revocation ids" in {
      val config = NgBiscuitConfig(
        defaultLegacy.copy(
          checks = Seq("c2", "c1"),
          facts = Seq("f2", "f1"),
          resources = Seq("r2", "r1"),
          rules = Seq("u2", "u1"),
          revocation_ids = Seq("i2", "i1")
        )
      )
      roundTrip(format, config).legacy mustBe config.legacy
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgBiscuitConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgBiscuitConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("a1b2c3")) mustBe NgBiscuitConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("a1b2c3")) mustBe NgBiscuitConfig()
    }

    "read the enforce flag alone" in {
      readOk(format, Json.obj("enforce" -> true)) mustBe NgBiscuitConfig(defaultLegacy.copy(enforce = true))
    }

    "read the extractor type alone and keep the default extractor name" in {
      readOk(format, Json.obj("extractor" -> Json.obj("type" -> "cookie"))).legacy mustBe defaultLegacy.copy(
        extractor = "cookie"
      )
    }

    "read the extractor name alone and keep the default extractor type" in {
      readOk(format, Json.obj("extractor" -> Json.obj("name" -> "X-Token"))).legacy mustBe defaultLegacy.copy(
        extractorName = "X-Token"
      )
    }

    "ignore an extractor that is not an object" in {
      readOk(format, Json.obj("extractor" -> "query")) mustBe NgBiscuitConfig()
    }

    "not read the flat extractorName key" in {
      readOk(format, Json.obj("extractorName" -> "X-Token")) mustBe NgBiscuitConfig()
    }

    "read the legacy publicKey key" in {
      readOk(format, Json.obj("publicKey" -> "legacy-key")).legacy.publicKey mustBe Some("legacy-key")
    }

    "prefer publicKey over public_key" in {
      readOk(format, Json.obj("publicKey" -> "legacy-key", "public_key" -> "new-key")).legacy.publicKey mustBe Some(
        "legacy-key"
      )
    }

    "fall back to public_key when publicKey is not a string" in {
      readOk(format, Json.obj("publicKey" -> 42, "public_key" -> "new-key")).legacy.publicKey mustBe Some("new-key")
    }

    "rewrite the legacy publicKey key as public_key" in {
      val json = written(format, readOk(format, Json.obj("publicKey" -> "legacy-key")))
      json \ "public_key" mustBe JsDefined(JsString("legacy-key"))
      json.keys must not contain "publicKey"
    }

    "read the legacy example config" in {
      readOk(format, BiscuitConfig.example).legacy mustBe defaultLegacy.copy(publicKey = Some("xxxxxx"))
    }

    "read a null public key as no public key" in {
      readOk(format, Json.obj("public_key" -> JsNull)).legacy.publicKey mustBe None
    }

    "ignore a public key that is not a string" in {
      readOk(format, Json.obj("public_key" -> Json.obj("key" -> "a1b2"))).legacy.publicKey mustBe None
    }

    "ignore checks written as a string" in {
      readOk(format, Json.obj("checks" -> "check if true")).legacy.checks mustBe empty
    }

    "ignore a whole list as soon as one item is not a string" in {
      readOk(format, Json.obj("revocation_ids" -> Json.arr("rev-1", 2))).legacy.revocation_ids mustBe empty
    }

    "ignore an enforce flag written as a string" in {
      readOk(format, Json.obj("enforce" -> "true")).legacy.enforce mustBe false
    }

    "ignore an extractor type that is not a string" in {
      readOk(format, Json.obj("extractor" -> Json.obj("type" -> 1, "name" -> "tok"))).legacy mustBe defaultLegacy.copy(
        extractorName = "tok"
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
      jsonMatchesFormat(format, NgBiscuitConfig())
    }

    "be the default config of NgBiscuitExtractor" in {
      defaultConfigReadsBack(new NgBiscuitExtractor(), format) mustBe NgBiscuitConfig()
    }

    "be the default config of NgBiscuitValidator" in {
      defaultConfigReadsBack(new NgBiscuitValidator(), format) mustBe NgBiscuitConfig()
    }
  }
}
