package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class OidcPluginConfigsSpec extends PluginConfigFormatSpec {

  "OIDCHeadersConfig" should {

    val format = OIDCHeadersConfig.format

    val custom = OIDCHeadersConfig(
      profile = OIDCProfileHeader(send = true, headerName = "X-Profile"),
      idtoken = OIDCIDTokenHeader(send = true, name = "my_id_token", headerName = "X-Id", jwt = false),
      accessToken = OIDCAccessTokenHeader(send = true, name = "my_access_token", headerName = "X-Access", jwt = false)
    )

    "write the default config" in {
      format.writes(OIDCHeadersConfig()) mustBe Json.obj(
        "profile"     -> Json.obj("send" -> false, "headerName" -> "X-OIDC-User"),
        "idToken"     -> Json.obj(
          "send"       -> false,
          "name"       -> "id_token",
          "headerName" -> "X-OIDC-Id-Token",
          "jwt"        -> true
        ),
        "accessToken" -> Json.obj(
          "send"       -> false,
          "name"       -> "access_token",
          "headerName" -> "X-OIDC-Access-Token",
          "jwt"        -> true
        )
      )
    }

    "write the id token section under idToken" in {
      writtenKeys(format, OIDCHeadersConfig()) mustBe Set("profile", "idToken", "accessToken")
    }

    "round trip the default config" in {
      roundTrip(format, OIDCHeadersConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, custom)
    }

    "round trip a profile section alone" in {
      roundTrip(format, OIDCHeadersConfig(profile = custom.profile)).profile mustBe custom.profile
    }

    "round trip an id token section alone" in {
      roundTrip(format, OIDCHeadersConfig(idtoken = custom.idtoken)).idtoken mustBe custom.idtoken
    }

    "round trip an access token section alone" in {
      roundTrip(format, OIDCHeadersConfig(accessToken = custom.accessToken)).accessToken mustBe custom.accessToken
    }

    "round trip opaque tokens sent as is" in {
      val config = OIDCHeadersConfig(
        idtoken = OIDCIDTokenHeader(send = true, jwt = false),
        accessToken = OIDCAccessTokenHeader(send = true, jwt = false)
      )
      roundTrip(format, config)
    }

    // the reads send the profile by default, where the case class does not
    "read an empty object as the default config sending the profile" in {
      readOk(format, Json.obj()) mustBe OIDCHeadersConfig(profile = OIDCProfileHeader(send = true))
    }

    "read a json null as the default config sending the profile" in {
      readOk(format, JsNull) mustBe OIDCHeadersConfig(profile = OIDCProfileHeader(send = true))
    }

    "read a json string as the default config sending the profile" in {
      readOk(format, JsString("profile")) mustBe OIDCHeadersConfig(profile = OIDCProfileHeader(send = true))
    }

    "fail to read a json array" in {
      readError(format, Json.arr(Json.obj("profile" -> Json.obj("send" -> false))))
    }

    "fail to read a section that is an array" in {
      readError(format, Json.obj("idToken" -> Json.arr(true)))
    }

    "read a lone id token flag and default the rest" in {
      readOk(format, Json.obj("idToken" -> Json.obj("send" -> true))) mustBe OIDCHeadersConfig(
        profile = OIDCProfileHeader(send = true),
        idtoken = OIDCIDTokenHeader(send = true)
      )
    }

    "read a lone access token header name and default the rest of the section" in {
      readOk(format, Json.obj("accessToken" -> Json.obj("headerName" -> "X-AT"))).accessToken mustBe OIDCAccessTokenHeader(
        headerName = "X-AT"
      )
    }

    "read a profile that is not sent" in {
      readOk(format, Json.obj("profile" -> Json.obj("send" -> false))).profile mustBe OIDCProfileHeader()
    }

    "ignore flags written as strings" in {
      val back = readOk(
        format,
        Json.obj("profile" -> Json.obj("send" -> "false"), "idToken" -> Json.obj("jwt" -> "false", "send" -> "true"))
      )
      back.profile.send mustBe true
      back.idtoken mustBe OIDCIDTokenHeader()
    }

    "ignore header names that are not strings" in {
      readOk(format, Json.obj("profile" -> Json.obj("headerName" -> 42))).profile.headerName mustBe "X-OIDC-User"
    }

    "ignore a section that is a string" in {
      readOk(format, Json.obj("accessToken" -> "X-AT")).accessToken mustBe OIDCAccessTokenHeader()
    }

    "not read the snake case section keys" in {
      val back = readOk(
        format,
        Json.obj("id_token" -> Json.obj("send" -> true), "access_token" -> Json.obj("send" -> true))
      )
      back.idtoken.send mustBe false
      back.accessToken.send mustBe false
    }

    "not read the idtoken field name as a section key" in {
      readOk(format, Json.obj("idtoken" -> Json.obj("send" -> true))).idtoken.send mustBe false
    }

    "not read the snake case header name keys" in {
      readOk(format, Json.obj("profile" -> Json.obj("header_name" -> "X-P"))).profile.headerName mustBe "X-OIDC-User"
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, custom)
    }

    "be the default config of OIDCHeaders" in {
      defaultConfigReadsBack(new OIDCHeaders(), format) mustBe OIDCHeadersConfig()
    }
  }

  "OIDCAccessTokenConfig" should {

    val format = OIDCAccessTokenConfig.format

    val thirdParty = Json.obj("type" -> "OIDC", "oidcConfigRef" -> "auth-module-1", "enabled" -> true)

    "be enabled without third party config by default" in {
      OIDCAccessTokenConfig() mustBe OIDCAccessTokenConfig(enabled = true, atLeastOne = false, config = None)
    }

    "write the default config with a null third party config" in {
      format.writes(OIDCAccessTokenConfig()) mustBe Json.obj(
        "enabled"    -> true,
        "atLeastOne" -> false,
        "config"     -> JsNull
      )
    }

    "write the third party config as is" in {
      format.writes(OIDCAccessTokenConfig(enabled = false, atLeastOne = true, config = Some(thirdParty))) mustBe Json.obj(
        "enabled"    -> false,
        "atLeastOne" -> true,
        "config"     -> thirdParty
      )
    }

    "round trip a config where every field is set" in {
      roundTrip(format, OIDCAccessTokenConfig(enabled = false, atLeastOne = true, config = Some(thirdParty)))
    }

    "round trip a third party config object" in {
      roundTrip(format, OIDCAccessTokenConfig(config = Some(thirdParty))).config mustBe Some(thirdParty)
    }

    "round trip an array of third party configs" in {
      val configs = Json.arr(thirdParty, thirdParty ++ Json.obj("oidcConfigRef" -> "auth-module-2"))
      roundTrip(format, OIDCAccessTokenConfig(config = Some(configs))).config mustBe Some(configs)
    }

    "round trip an empty array of third party configs" in {
      roundTrip(format, OIDCAccessTokenConfig(config = Some(Json.arr())))
    }

    "round trip the at least one mode" in {
      roundTrip(format, OIDCAccessTokenConfig(atLeastOne = true, config = Some(thirdParty))).atLeastOne mustBe true
    }

    "round trip a disabled config" in {
      roundTrip(format, OIDCAccessTokenConfig(enabled = false, config = Some(thirdParty))).enabled mustBe false
    }

    // the reads disable the plugin by default, where the case class enables it
    "read an empty object as a disabled config" in {
      readOk(format, Json.obj()) mustBe OIDCAccessTokenConfig(enabled = false)
    }

    "read a json null as a disabled config" in {
      readOk(format, JsNull) mustBe OIDCAccessTokenConfig(enabled = false)
    }

    "read a json string as a disabled config" in {
      readOk(format, JsString("OIDC")) mustBe OIDCAccessTokenConfig(enabled = false)
    }

    "read a json array as a disabled config" in {
      readOk(format, Json.arr(thirdParty)) mustBe OIDCAccessTokenConfig(enabled = false)
    }

    "read a lone third party config as disabled" in {
      readOk(format, Json.obj("config" -> thirdParty)) mustBe OIDCAccessTokenConfig(enabled = false, config = Some(thirdParty))
    }

    "read a lone enabled flag" in {
      readOk(format, Json.obj("enabled" -> true)) mustBe OIDCAccessTokenConfig()
    }

    "ignore flags written as strings" in {
      readOk(format, Json.obj("enabled" -> "true", "atLeastOne" -> "true")) mustBe OIDCAccessTokenConfig(enabled = false)
    }

    "keep a third party config of any json type" in {
      readOk(format, Json.obj("config" -> "ref")).config mustBe Some(JsString("ref"))
    }

    "not read the snake case at_least_one key" in {
      readOk(format, Json.obj("enabled" -> true, "at_least_one" -> true)).atLeastOne mustBe false
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, OIDCAccessTokenConfig(atLeastOne = true, config = Some(thirdParty)))
    }

    "point to an oidc auth module in the default config of the plugins" in {
      val config = new OIDCAccessTokenValidator().defaultConfigObject.get.asInstanceOf[OIDCAccessTokenConfig]
      config.enabled mustBe true
      (config.config.get \ "type").as[String] mustBe "OIDC"
      (config.config.get \ "oidcConfigRef").as[String] mustBe "some-oidc-auth-module-id"
      new OIDCAccessTokenAsApikey().defaultConfigObject mustBe new OIDCAccessTokenValidator().defaultConfigObject
    }

    "be the default config of OIDCAccessTokenValidator" in {
      defaultConfigReadsBack(new OIDCAccessTokenValidator(), format).enabled mustBe true
    }

    "be the default config of OIDCAccessTokenAsApikey" in {
      defaultConfigReadsBack(new OIDCAccessTokenAsApikey(), format).enabled mustBe true
    }
  }

  "OIDCAuthTokenConfig" should {

    val format = OIDCAuthTokenConfig.format

    val custom = OIDCAuthTokenConfig(
      ref = "auth-module-1",
      opaque = false,
      fetchUserProfile = false,
      validateAudience = true,
      headerName = "X-Token",
      useIntrospection = true
    )

    "have opaque tokens, profile fetching and the Authorization header by default" in {
      OIDCAuthTokenConfig.default mustBe OIDCAuthTokenConfig(
        ref = "",
        opaque = true,
        fetchUserProfile = true,
        validateAudience = false,
        headerName = "Authorization",
        useIntrospection = false
      )
    }

    "write the default config" in {
      format.writes(OIDCAuthTokenConfig.default) mustBe Json.obj(
        "ref"                -> "",
        "opaque"             -> true,
        "fetch_user_profile" -> true,
        "validate_audience"  -> false,
        "header_name"        -> "Authorization",
        "use_introspection"  -> false
      )
    }

    "round trip the default config" in {
      roundTrip(format, OIDCAuthTokenConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, custom)
    }

    "round trip each flag flipped alone" in {
      roundTrip(format, OIDCAuthTokenConfig.default.copy(opaque = false)).opaque mustBe false
      roundTrip(format, OIDCAuthTokenConfig.default.copy(fetchUserProfile = false)).fetchUserProfile mustBe false
      roundTrip(format, OIDCAuthTokenConfig.default.copy(validateAudience = true)).validateAudience mustBe true
      roundTrip(format, OIDCAuthTokenConfig.default.copy(useIntrospection = true)).useIntrospection mustBe true
    }

    "round trip an empty header name" in {
      roundTrip(format, custom.copy(headerName = "")).headerName mustBe ""
    }

    "fail to read an empty object" in {
      readError(format, Json.obj())
    }

    "fail to read a json null" in {
      readError(format, JsNull)
    }

    "fail to read a json string" in {
      readError(format, JsString("auth-module-1"))
    }

    "fail to read a json array" in {
      readError(format, Json.arr(Json.obj("ref" -> "auth-module-1")))
    }

    "fail to read a ref that is not a string" in {
      readError(format, Json.obj("ref" -> 42))
    }

    // a missing flag is read as false, even the ones that are true in the default config
    "read a lone ref with every flag off" in {
      readOk(format, Json.obj("ref" -> "auth-module-1")) mustBe OIDCAuthTokenConfig(
        ref = "auth-module-1",
        opaque = false,
        fetchUserProfile = false,
        validateAudience = false,
        headerName = "Authorization",
        useIntrospection = false
      )
    }

    "ignore flags written as strings" in {
      val back = readOk(
        format,
        Json.obj("ref" -> "r", "opaque" -> "true", "fetch_user_profile" -> "true", "use_introspection" -> "true")
      )
      back.opaque mustBe false
      back.fetchUserProfile mustBe false
      back.useIntrospection mustBe false
    }

    "ignore a header name that is not a string" in {
      readOk(format, Json.obj("ref" -> "r", "header_name" -> 1)).headerName mustBe "Authorization"
    }

    "not read the camel case keys" in {
      val back = readOk(
        format,
        Json.obj("ref" -> "r", "fetchUserProfile" -> true, "validateAudience" -> true, "headerName" -> "X-T")
      )
      back.fetchUserProfile mustBe false
      back.validateAudience mustBe false
      back.headerName mustBe "Authorization"
    }

    "ignore unknown keys" in {
      readOk(format, format.writes(custom).as[JsObject] ++ Json.obj("foo" -> "bar")) mustBe custom
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, custom)
    }

    "list its config flow fields in its config schema" in {
      OIDCAuthTokenConfig.configFlow mustBe Seq(
        "ref",
        "header_name",
        "opaque",
        "fetch_user_profile",
        "validate_audience",
        "use_introspection"
      )
      OIDCAuthTokenConfig.configFlow.foreach { field =>
        OIDCAuthTokenConfig.configSchema.get.keys must contain(field)
      }
    }

    "use the flow and schema keys the format writes" in {
      OIDCAuthTokenConfig.configFlow.toSet mustBe writtenKeys(format, custom)
    }

    "give its config flow and schema to OIDCAuthToken" in {
      val plugin = new OIDCAuthToken()
      plugin.configFlow mustBe OIDCAuthTokenConfig.configFlow
      plugin.configSchema mustBe OIDCAuthTokenConfig.configSchema
    }

    "be the default config of OIDCAuthToken" in {
      defaultConfigReadsBack(new OIDCAuthToken(), format) mustBe OIDCAuthTokenConfig.default
    }
  }
}
