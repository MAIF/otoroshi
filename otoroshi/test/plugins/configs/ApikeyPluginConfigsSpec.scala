package plugins.configs

import otoroshi.models.{
  ApiKeyConstraints,
  ApiKeyRouteMatcher,
  BasicAuthConstraints,
  ClientIdAuthConstraints,
  CustomHeadersAuthConstraints,
  JwtAuthConstraints,
  OtoBearerConstraints
}
import otoroshi.next.plugins.*
import play.api.libs.json.*

class ApikeyPluginConfigsSpec extends PluginConfigFormatSpec {

  private val emptyLocation = Json.obj(
    "enabled"     -> true,
    "header_name" -> JsNull,
    "query_name"  -> JsNull,
    "cookie_name" -> JsNull,
    "path_name"   -> JsNull
  )

  private val defaultCallsJson = Json.obj(
    "extractors"           -> Json.obj(
      "basic"          -> emptyLocation,
      "custom_headers" -> Json.obj(
        "enabled"                   -> true,
        "client_id_header_name"     -> JsNull,
        "client_secret_header_name" -> JsNull
      ),
      "client_id"      -> emptyLocation,
      "jwt"            -> (emptyLocation ++ Json.obj(
        "secret_signed"         -> true,
        "keypair_signed"        -> true,
        "include_request_attrs" -> false,
        "max_jwt_lifespan_sec"  -> JsNull
      )),
      "oto_bearer"     -> emptyLocation
    ),
    "routing"              -> Json.obj("enabled" -> false),
    "validate"             -> true,
    "mandatory"            -> true,
    "pass_with_user"       -> false,
    "wipe_backend_request" -> true,
    "update_quotas"        -> true
  )

  // every field set to something else than its default
  private val customExtractors = NgApikeyExtractors(
    basic = NgApikeyExtractorBasic(
      enabled = false,
      headerName = Some("X-Basic"),
      queryName = Some("basic"),
      cookieName = Some("basic-cookie"),
      pathName = Some("basic-path")
    ),
    customHeaders = NgApikeyExtractorCustomHeaders(
      enabled = false,
      clientIdHeaderName = Some("X-Client-Id"),
      clientSecretHeaderName = Some("X-Client-Secret")
    ),
    clientId = NgApikeyExtractorClientId(
      enabled = false,
      headerName = Some("X-Id"),
      queryName = Some("id"),
      cookieName = Some("id-cookie"),
      pathName = Some("id-path")
    ),
    jwt = NgApikeyExtractorJwt(
      enabled = false,
      secretSigned = false,
      keyPairSigned = false,
      includeRequestAttrs = true,
      maxJwtLifespanSec = Some(3600L),
      headerName = Some("X-Jwt"),
      queryName = Some("jwt"),
      cookieName = Some("jwt-cookie"),
      pathName = Some("jwt-path")
    ),
    otoBearer = NgApikeyExtractorOtoBearer(
      enabled = false,
      headerName = Some("X-Bearer"),
      queryName = Some("bearer"),
      cookieName = Some("bearer-cookie"),
      pathName = Some("bearer-path")
    )
  )

  private val customRouting = NgApikeyMatcher(
    enabled = true,
    noneTagIn = Seq("none-a"),
    oneTagIn = Seq("one-a", "one-b"),
    allTagsIn = Seq("all-a"),
    noneMetaIn = Map("none" -> "meta"),
    oneMetaIn = Map("one" -> "meta"),
    allMetaIn = Map("all" -> "meta", "other" -> "value"),
    noneMetaKeysIn = Seq("none-key"),
    oneMetaKeyIn = Seq("one-key"),
    allMetaKeysIn = Seq("all-key-a", "all-key-b")
  )

  private val customCalls = NgApikeyCallsConfig(
    extractors = customExtractors,
    routing = customRouting,
    wipeBackendRequest = false,
    validate = false,
    mandatory = false,
    passWithUser = true,
    updateQuotas = false
  )

  "NgApikeyCallsConfig" should {

    val format = NgApikeyCallsConfig.format

    "write the default config" in {
      format.writes(NgApikeyCallsConfig()) mustBe defaultCallsJson
    }

    "round trip the default config" in {
      roundTrip(format, NgApikeyCallsConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, customCalls)
    }

    "round trip each top level flag flipped alone" in {
      roundTrip(format, NgApikeyCallsConfig(wipeBackendRequest = false)).wipeBackendRequest mustBe false
      roundTrip(format, NgApikeyCallsConfig(validate = false)).validate mustBe false
      roundTrip(format, NgApikeyCallsConfig(mandatory = false)).mandatory mustBe false
      roundTrip(format, NgApikeyCallsConfig(passWithUser = true)).passWithUser mustBe true
      roundTrip(format, NgApikeyCallsConfig(updateQuotas = false)).updateQuotas mustBe false
    }

    "write the flags under snake case keys" in {
      val json = written(format, customCalls)
      (json \ "wipe_backend_request").as[Boolean] mustBe false
      (json \ "pass_with_user").as[Boolean] mustBe true
      (json \ "update_quotas").as[Boolean] mustBe false
    }

    "write the extractors under snake case keys" in {
      writtenKeys(NgApikeyExtractors.format, NgApikeyExtractors()) mustBe Set(
        "basic",
        "custom_headers",
        "client_id",
        "jwt",
        "oto_bearer"
      )
    }

    "round trip a jwt extractor with every field set" in {
      roundTrip(format, NgApikeyCallsConfig(extractors = NgApikeyExtractors(jwt = customExtractors.jwt)))
    }

    "write the jwt extractor flags and lifespan" in {
      val json = written(NgApikeyExtractorJwt.format, customExtractors.jwt)
      json mustBe Json.obj(
        "enabled"               -> false,
        "secret_signed"         -> false,
        "keypair_signed"        -> false,
        "include_request_attrs" -> true,
        "max_jwt_lifespan_sec"  -> 3600,
        "header_name"           -> "X-Jwt",
        "query_name"            -> "jwt",
        "cookie_name"           -> "jwt-cookie",
        "path_name"             -> "jwt-path"
      )
    }

    "keep a zero jwt lifespan" in {
      roundTrip(NgApikeyExtractorJwt.format, NgApikeyExtractorJwt(maxJwtLifespanSec = Some(0L))).maxJwtLifespanSec mustBe Some(0L)
    }

    "round trip the largest jwt lifespan" in {
      roundTrip(NgApikeyExtractorJwt.format, NgApikeyExtractorJwt(maxJwtLifespanSec = Some(Long.MaxValue)))
    }

    "read a negative jwt lifespan as no lifespan" in {
      readOk(NgApikeyExtractorJwt.format, Json.obj("max_jwt_lifespan_sec" -> -1)).maxJwtLifespanSec mustBe None
      readOk(NgApikeyExtractorJwt.format, Json.obj("max_jwt_lifespan_sec" -> -3600)).maxJwtLifespanSec mustBe None
    }

    "ignore a jwt lifespan written as a string" in {
      readOk(NgApikeyExtractorJwt.format, Json.obj("max_jwt_lifespan_sec" -> "3600")).maxJwtLifespanSec mustBe None
    }

    "round trip an oto bearer extractor with every field set" in {
      roundTrip(format, NgApikeyCallsConfig(extractors = NgApikeyExtractors(otoBearer = customExtractors.otoBearer)))
    }

    "round trip a custom headers extractor with every field set" in {
      roundTrip(
        format,
        NgApikeyCallsConfig(extractors = NgApikeyExtractors(customHeaders = customExtractors.customHeaders))
      )
    }

    "write blank custom header names as null" in {
      val json = written(
        NgApikeyExtractorCustomHeaders.format,
        NgApikeyExtractorCustomHeaders(clientIdHeaderName = Some(" "), clientSecretHeaderName = Some(""))
      )
      (json \ "client_id_header_name").get mustBe JsNull
      (json \ "client_secret_header_name").get mustBe JsNull
    }

    "read blank extractor names as no name" in {
      val json = Json.obj("header_name" -> "", "query_name" -> "  ", "cookie_name" -> "c", "path_name" -> "\t")
      readOk(NgApikeyExtractorBasic.format, json) mustBe NgApikeyExtractorBasic(cookieName = Some("c"))
      readOk(NgApikeyExtractorClientId.format, json) mustBe NgApikeyExtractorClientId(cookieName = Some("c"))
      readOk(NgApikeyExtractorOtoBearer.format, json) mustBe NgApikeyExtractorOtoBearer(cookieName = Some("c"))
    }

    "read an extractor without enabled flag as enabled" in {
      readOk(NgApikeyExtractorBasic.format, Json.obj()).enabled mustBe true
      readOk(NgApikeyExtractorJwt.format, Json.obj()) mustBe NgApikeyExtractorJwt()
    }

    "write the routing as its enabled flag alone when it has no constraint" in {
      writtenKeys(NgApikeyMatcher.format, NgApikeyMatcher(enabled = true)) mustBe Set("enabled")
    }

    "write only the routing constraints that are not empty" in {
      writtenKeys(NgApikeyMatcher.format, NgApikeyMatcher(oneTagIn = Seq("a"), allMetaIn = Map("k" -> "v"))) mustBe Set(
        "enabled",
        "one_tag_in",
        "all_meta_in"
      )
    }

    "round trip an enabled routing without constraint" in {
      roundTrip(format, NgApikeyCallsConfig(routing = NgApikeyMatcher(enabled = true))).routing.enabled mustBe true
    }

    "read the none_tag_in routing key" in {
      readOk(NgApikeyMatcher.format, Json.obj("none_tag_in" -> Json.arr("banned"))).noneTagIn mustBe Seq("banned")
    }

    "write the none tag constraint under none_tag_in, the key the ui forms use" in {
      written(NgApikeyMatcher.format, NgApikeyMatcher(noneTagIn = Seq("banned"))) mustBe Json.obj(
        "enabled"     -> false,
        "none_tag_in" -> Json.arr("banned")
      )
    }

    "round trip a none tag constraint alone" in {
      roundTrip(NgApikeyMatcher.format, NgApikeyMatcher(enabled = true, noneTagIn = Seq("banned", "legacy"))).noneTagIn mustBe Seq(
        "banned",
        "legacy"
      )
    }

    "round trip a none tag constraint through the whole config" in {
      roundTrip(format, NgApikeyCallsConfig(routing = NgApikeyMatcher(enabled = true, noneTagIn = Seq("banned")))).routing.noneTagIn mustBe Seq(
        "banned"
      )
    }

    "still read the none_tagIn key written by older versions" in {
      readOk(NgApikeyMatcher.format, Json.obj("none_tagIn" -> Json.arr("banned"))).noneTagIn mustBe Seq("banned")
    }

    "prefer none_tag_in over the older none_tagIn key" in {
      readOk(
        NgApikeyMatcher.format,
        Json.obj("none_tag_in" -> Json.arr("new"), "none_tagIn" -> Json.arr("old"))
      ).noneTagIn mustBe Seq("new")
    }

    "keep the none tag constraint of converted legacy constraints through a round trip" in {
      val converted = NgApikeyCallsConfig.fromLegacy(ApiKeyConstraints(routing = ApiKeyRouteMatcher(noneTagIn = Seq("banned"))))
      roundTrip(format, converted).routing.noneTagIn mustBe Seq("banned")
    }

    "read every routing key" in {
      val json = Json.obj(
        "enabled"           -> true,
        "none_tag_in"       -> Json.arr("n"),
        "one_tag_in"        -> Json.arr("o"),
        "all_tags_in"       -> Json.arr("a"),
        "none_meta_in"      -> Json.obj("nk" -> "nv"),
        "one_meta_in"       -> Json.obj("ok" -> "ov"),
        "all_meta_in"       -> Json.obj("ak" -> "av"),
        "none_meta_keys_in" -> Json.arr("nmk"),
        "one_meta_key_in"   -> Json.arr("omk"),
        "all_meta_keys_in"  -> Json.arr("amk")
      )
      readOk(NgApikeyMatcher.format, json) mustBe NgApikeyMatcher(
        enabled = true,
        noneTagIn = Seq("n"),
        oneTagIn = Seq("o"),
        allTagsIn = Seq("a"),
        noneMetaIn = Map("nk" -> "nv"),
        oneMetaIn = Map("ok" -> "ov"),
        allMetaIn = Map("ak" -> "av"),
        noneMetaKeysIn = Seq("nmk"),
        oneMetaKeyIn = Seq("omk"),
        allMetaKeysIn = Seq("amk")
      )
    }

    "ignore a routing constraint of the wrong type" in {
      readOk(
        NgApikeyMatcher.format,
        Json.obj("one_tag_in" -> "a", "all_meta_in" -> Json.obj("k" -> 1))
      ) mustBe NgApikeyMatcher()
    }

    "tell whether the routing has constraints" in {
      NgApikeyMatcher().hasNoRoutingConstraints mustBe true
      NgApikeyMatcher().isActive mustBe false
      NgApikeyMatcher(enabled = true).isActive mustBe false
      NgApikeyMatcher(noneMetaKeysIn = Seq("k")).isActive mustBe true
      NgApikeyMatcher(noneTagIn = Seq("t")).hasNoRoutingConstraints mustBe false
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgApikeyCallsConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgApikeyCallsConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("apikey")) mustBe NgApikeyCallsConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(defaultCallsJson)) mustBe NgApikeyCallsConfig()
    }

    "read a lone flag and default the rest" in {
      readOk(format, Json.obj("mandatory" -> false)) mustBe NgApikeyCallsConfig(mandatory = false)
      readOk(format, Json.obj("pass_with_user" -> true)) mustBe NgApikeyCallsConfig(passWithUser = true)
    }

    "read a lone extractor and default the others" in {
      val back = readOk(format, Json.obj("extractors" -> Json.obj("jwt" -> Json.obj("enabled" -> false))))
      back.extractors mustBe NgApikeyExtractors(jwt = NgApikeyExtractorJwt(enabled = false))
    }

    "ignore flags written as strings" in {
      readOk(format, Json.obj("validate" -> "false", "update_quotas" -> "false")) mustBe NgApikeyCallsConfig()
    }

    "ignore extractors and routing that are not objects" in {
      readOk(format, Json.obj("extractors" -> "basic", "routing" -> Json.arr("a"))) mustBe NgApikeyCallsConfig()
    }

    "not read the camel case flags" in {
      readOk(format, Json.obj("passWithUser" -> true, "wipeBackendRequest" -> false)) mustBe NgApikeyCallsConfig()
    }

    "convert legacy apikey constraints" in {
      val legacy = ApiKeyConstraints(
        basicAuth = BasicAuthConstraints(enabled = false, headerName = Some("X-Basic")),
        customHeadersAuth = CustomHeadersAuthConstraints(clientIdHeaderName = Some("X-Id")),
        clientIdAuth = ClientIdAuthConstraints(queryName = Some("cid")),
        jwtAuth = JwtAuthConstraints(includeRequestAttributes = true, maxJwtLifespanSecs = Some(60L)),
        otoBearerAuth = OtoBearerConstraints(cookieName = Some("bearer")),
        routing = ApiKeyRouteMatcher(oneTagIn = Seq("tag"))
      )
      NgApikeyCallsConfig.fromLegacy(legacy) mustBe NgApikeyCallsConfig(
        extractors = NgApikeyExtractors(
          basic = NgApikeyExtractorBasic(enabled = false, headerName = Some("X-Basic")),
          customHeaders = NgApikeyExtractorCustomHeaders(clientIdHeaderName = Some("X-Id")),
          clientId = NgApikeyExtractorClientId(queryName = Some("cid")),
          jwt = NgApikeyExtractorJwt(includeRequestAttrs = true, maxJwtLifespanSec = Some(60L)),
          otoBearer = NgApikeyExtractorOtoBearer(cookieName = Some("bearer"))
        ),
        routing = NgApikeyMatcher(enabled = true, oneTagIn = Seq("tag"))
      )
    }

    "keep the routing of converted legacy constraints disabled when it has no constraint" in {
      NgApikeyCallsConfig.fromLegacy(ApiKeyConstraints()).routing.enabled mustBe false
      NgApikeyCallsConfig.fromLegacy(ApiKeyConstraints()) mustBe NgApikeyCallsConfig()
    }

    "expose its extractors and routing as legacy constraints" in {
      val legacy = customCalls.legacy
      legacy.basicAuth mustBe BasicAuthConstraints(false, Some("X-Basic"), Some("basic"), Some("basic-cookie"), Some("basic-path"))
      legacy.jwtAuth.includeRequestAttributes mustBe true
      legacy.jwtAuth.maxJwtLifespanSecs mustBe Some(3600L)
      legacy.customHeadersAuth.clientSecretHeaderName mustBe Some("X-Client-Secret")
      legacy.otoBearerAuth.pathName mustBe Some("bearer-path")
      legacy.routing.allMetaKeysIn mustBe Seq("all-key-a", "all-key-b")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, customCalls)
    }

    "be the default config of ApikeyCalls" in {
      defaultConfigReadsBack(new ApikeyCalls(), format) mustBe NgApikeyCallsConfig()
    }
  }

  "NgLegacyApikeyCallConfig" should {

    val format = NgLegacyApikeyCallConfig.format

    "write the patterns next to the flattened apikey config" in {
      format.writes(NgLegacyApikeyCallConfig.default) mustBe (Json.obj(
        "public_patterns"  -> Json.arr(),
        "private_patterns" -> Json.arr()
      ) ++ defaultCallsJson)
    }

    "not nest the apikey config under a config key" in {
      writtenKeys(format, NgLegacyApikeyCallConfig.default) must not contain "config"
    }

    "have no pattern and the default apikey config by default" in {
      NgLegacyApikeyCallConfig.default mustBe NgLegacyApikeyCallConfig(Seq.empty, Seq.empty, NgApikeyCallsConfig())
    }

    "round trip the default config" in {
      roundTrip(format, NgLegacyApikeyCallConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(
        format,
        NgLegacyApikeyCallConfig(
          publicPatterns = Seq("/public/.*", "/health"),
          privatePatterns = Seq("/public/admin/.*"),
          config = customCalls
        )
      )
    }

    "keep the order of the patterns" in {
      val back = roundTrip(format, NgLegacyApikeyCallConfig(Seq("/b", "/a", "/c"), Seq("/z", "/y"), NgApikeyCallsConfig()))
      back.publicPatterns mustBe Seq("/b", "/a", "/c")
      back.privatePatterns mustBe Seq("/z", "/y")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgLegacyApikeyCallConfig.default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgLegacyApikeyCallConfig.default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("/public")) mustBe NgLegacyApikeyCallConfig.default
    }

    "read lone patterns with the default apikey config" in {
      readOk(format, Json.obj("public_patterns" -> Json.arr("/.*"))) mustBe NgLegacyApikeyCallConfig(
        Seq("/.*"),
        Seq.empty,
        NgApikeyCallsConfig()
      )
    }

    "read the apikey flags from the top level object" in {
      readOk(format, Json.obj("mandatory" -> false)).config mustBe NgApikeyCallsConfig(mandatory = false)
    }

    "ignore an apikey config nested under a config key" in {
      readOk(format, Json.obj("config" -> Json.obj("mandatory" -> false))) mustBe NgLegacyApikeyCallConfig.default
    }

    "ignore patterns written as a string" in {
      readOk(format, Json.obj("public_patterns" -> "/.*", "private_patterns" -> "/admin")) mustBe NgLegacyApikeyCallConfig.default
    }

    "ignore patterns holding something else than strings" in {
      readOk(format, Json.obj("private_patterns" -> Json.arr("/admin", 1))).privatePatterns mustBe empty
    }

    "not read the camel case pattern keys" in {
      readOk(format, Json.obj("publicPatterns" -> Json.arr("/.*"))) mustBe NgLegacyApikeyCallConfig.default
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgLegacyApikeyCallConfig(Seq("/public"), Seq("/private"), customCalls))
    }

    "be the default config of NgLegacyApikeyCall" in {
      defaultConfigReadsBack(new NgLegacyApikeyCall(), format) mustBe NgLegacyApikeyCallConfig.default
    }
  }

  "ApikeyAuthModuleConfig" should {

    val format = ApikeyAuthModuleConfig.format

    val fullMatcher = ApiKeyRouteMatcher(
      noneTagIn = Seq("none"),
      oneTagIn = Seq("one"),
      allTagsIn = Seq("all-a", "all-b"),
      noneMetaIn = Map("nk" -> "nv"),
      oneMetaIn = Map("ok" -> "ov"),
      allMetaIn = Map("ak" -> "av"),
      noneMetaKeysIn = Seq("nmk"),
      oneMetaKeyIn = Seq("omk"),
      allMetaKeysIn = Seq("amk")
    )

    "have a realm and no matcher by default" in {
      ApikeyAuthModuleConfig() mustBe ApikeyAuthModuleConfig(Some("apikey-auth-module-realm"), None)
    }

    "write the default config with a null matcher" in {
      format.writes(ApikeyAuthModuleConfig()) mustBe Json.obj(
        "realm"   -> "apikey-auth-module-realm",
        "matcher" -> JsNull
      )
    }

    "write the matcher with its camel case keys" in {
      written(format, ApikeyAuthModuleConfig(matcher = Some(fullMatcher))) mustBe Json.obj(
        "realm"   -> "apikey-auth-module-realm",
        "matcher" -> Json.obj(
          "noneTagIn"      -> Json.arr("none"),
          "oneTagIn"       -> Json.arr("one"),
          "allTagsIn"      -> Json.arr("all-a", "all-b"),
          "noneMetaIn"     -> Json.obj("nk" -> "nv"),
          "oneMetaIn"      -> Json.obj("ok" -> "ov"),
          "allMetaIn"      -> Json.obj("ak" -> "av"),
          "noneMetaKeysIn" -> Json.arr("nmk"),
          "oneMetaKeyIn"   -> Json.arr("omk"),
          "allMetaKeysIn"  -> Json.arr("amk")
        )
      )
    }

    "write an empty matcher with every key" in {
      (written(format, ApikeyAuthModuleConfig(matcher = Some(ApiKeyRouteMatcher()))) \ "matcher").as[JsObject].keys mustBe Set(
        "noneTagIn",
        "oneTagIn",
        "allTagsIn",
        "noneMetaIn",
        "oneMetaIn",
        "allMetaIn",
        "noneMetaKeysIn",
        "oneMetaKeyIn",
        "allMetaKeysIn"
      )
    }

    "round trip a config where every field is set" in {
      roundTrip(format, ApikeyAuthModuleConfig(realm = Some("my-realm"), matcher = Some(fullMatcher)))
    }

    "round trip an empty matcher" in {
      roundTrip(format, ApikeyAuthModuleConfig(matcher = Some(ApiKeyRouteMatcher())))
    }

    "round trip a config without realm" in {
      roundTrip(format, ApikeyAuthModuleConfig(realm = None, matcher = Some(fullMatcher))).realm mustBe None
    }

    "write a missing realm as null" in {
      (written(format, ApikeyAuthModuleConfig(realm = None)) \ "realm").get mustBe JsNull
    }

    "round trip a realm with quotes and spaces" in {
      roundTrip(format, ApikeyAuthModuleConfig(realm = Some("my \"quoted\" realm"), matcher = Some(fullMatcher)))
    }

    "read an empty object without realm" in {
      readOk(format, Json.obj()).realm mustBe None
    }

    "read a json null without realm" in {
      readOk(format, JsNull).realm mustBe None
    }

    "read a json string without realm" in {
      readOk(format, JsString("realm")).realm mustBe None
    }

    "read a lone realm" in {
      readOk(format, Json.obj("realm" -> "lone")).realm mustBe Some("lone")
    }

    "ignore a realm that is not a string" in {
      readOk(format, Json.obj("realm" -> 42)).realm mustBe None
    }

    "read a partial matcher and default its other constraints" in {
      readOk(format, Json.obj("matcher" -> Json.obj("oneTagIn" -> Json.arr("a")))).matcher mustBe Some(
        ApiKeyRouteMatcher(oneTagIn = Seq("a"))
      )
    }

    "not read the snake case matcher keys" in {
      readOk(format, Json.obj("matcher" -> Json.obj("one_tag_in" -> Json.arr("a")))).matcher mustBe Some(
        ApiKeyRouteMatcher()
      )
    }

    "ignore a matcher constraint of the wrong type" in {
      readOk(
        format,
        Json.obj("matcher" -> Json.obj("allTagsIn" -> "a", "oneMetaIn" -> Json.arr("b"), "noneTagIn" -> Json.arr("c")))
      ).matcher mustBe Some(ApiKeyRouteMatcher(noneTagIn = Seq("c")))
    }

    "read a matcher that is not an object as no matcher" in {
      readOk(format, Json.obj("matcher" -> "tags")).matcher mustBe None
      readOk(format, Json.obj("matcher" -> Json.arr("tags"))).matcher mustBe None
    }

    "round trip the default config" in {
      roundTrip(format, ApikeyAuthModuleConfig())
    }

    "round trip a config without matcher" in {
      roundTrip(format, ApikeyAuthModuleConfig(realm = Some("my-realm"))).matcher mustBe None
    }

    "read a null matcher as no matcher" in {
      readOk(format, Json.obj("realm" -> "r", "matcher" -> JsNull)).matcher mustBe None
    }

    "read a missing matcher as no matcher" in {
      readOk(format, Json.obj("realm" -> "r")).matcher mustBe None
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, ApikeyAuthModuleConfig(realm = Some("r"), matcher = Some(fullMatcher)))
    }

    "be the default config of ApikeyAuthModule" in {
      defaultConfigReadsBack(new ApikeyAuthModule(), format) mustBe ApikeyAuthModuleConfig()
    }
  }

  "NgApikeyMandatoryTagsConfig" should {

    val format = NgApikeyMandatoryTagsConfig.format

    "write the tags under tags" in {
      format.writes(NgApikeyMandatoryTagsConfig(Seq("gold", "internal"))) mustBe Json.obj(
        "tags" -> Json.arr("gold", "internal")
      )
    }

    "write the default config as an empty tags array" in {
      format.writes(NgApikeyMandatoryTagsConfig()) mustBe Json.obj("tags" -> Json.arr())
    }

    "round trip the default config" in {
      roundTrip(format, NgApikeyMandatoryTagsConfig())
    }

    "round trip several tags and keep their order" in {
      roundTrip(format, NgApikeyMandatoryTagsConfig(Seq("c", "a", "b"))).tags mustBe Seq("c", "a", "b")
    }

    "keep duplicated tags and their case" in {
      roundTrip(format, NgApikeyMandatoryTagsConfig(Seq("Gold", "gold", "gold"))).tags mustBe Seq("Gold", "gold", "gold")
    }

    "round trip an empty tag" in {
      roundTrip(format, NgApikeyMandatoryTagsConfig(Seq("")))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgApikeyMandatoryTagsConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgApikeyMandatoryTagsConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("gold")) mustBe NgApikeyMandatoryTagsConfig()
    }

    "ignore tags written as a string" in {
      readOk(format, Json.obj("tags" -> "gold")) mustBe NgApikeyMandatoryTagsConfig()
    }

    "ignore tags holding something else than strings" in {
      readOk(format, Json.obj("tags" -> Json.arr("gold", 1))) mustBe NgApikeyMandatoryTagsConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("tags" -> Json.arr("gold"), "metadata" -> Json.obj("a" -> "b"))) mustBe NgApikeyMandatoryTagsConfig(
        Seq("gold")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgApikeyMandatoryTagsConfig(Seq("gold")))
    }

    "be the default config of NgApikeyMandatoryTags" in {
      defaultConfigReadsBack(new NgApikeyMandatoryTags(), format) mustBe NgApikeyMandatoryTagsConfig()
    }
  }

  "NgApikeyMandatoryMetadataConfig" should {

    val format = NgApikeyMandatoryMetadataConfig.format

    "write the metadata under metadata" in {
      format.writes(NgApikeyMandatoryMetadataConfig(Map("tier" -> "gold"))) mustBe Json.obj(
        "metadata" -> Json.obj("tier" -> "gold")
      )
    }

    "write the default config as an empty metadata object" in {
      format.writes(NgApikeyMandatoryMetadataConfig()) mustBe Json.obj("metadata" -> Json.obj())
    }

    "round trip the default config" in {
      roundTrip(format, NgApikeyMandatoryMetadataConfig())
    }

    "round trip several entries" in {
      roundTrip(format, NgApikeyMandatoryMetadataConfig(Map("tier" -> "gold", "team" -> "core", "env" -> "prod")))
    }

    "round trip an empty value" in {
      roundTrip(format, NgApikeyMandatoryMetadataConfig(Map("flag" -> "")))
    }

    "keep the case of the keys" in {
      roundTrip(format, NgApikeyMandatoryMetadataConfig(Map("Tier" -> "a", "tier" -> "b"))).metadata.keySet mustBe Set(
        "Tier",
        "tier"
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgApikeyMandatoryMetadataConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgApikeyMandatoryMetadataConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("tier")) mustBe NgApikeyMandatoryMetadataConfig()
    }

    "ignore metadata written as an array" in {
      readOk(format, Json.obj("metadata" -> Json.arr("tier"))) mustBe NgApikeyMandatoryMetadataConfig()
    }

    "ignore the whole metadata as soon as a value is not a string" in {
      readOk(format, Json.obj("metadata" -> Json.obj("tier" -> "gold", "level" -> 3))) mustBe NgApikeyMandatoryMetadataConfig()
    }

    "not read the tags of a tags config" in {
      readOk(format, Json.obj("tags" -> Json.arr("gold"))) mustBe NgApikeyMandatoryMetadataConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgApikeyMandatoryMetadataConfig(Map("tier" -> "gold")))
    }

    "be the default config of NgApikeyMandatoryMetadata" in {
      defaultConfigReadsBack(new NgApikeyMandatoryMetadata(), format) mustBe NgApikeyMandatoryMetadataConfig()
    }
  }
}
