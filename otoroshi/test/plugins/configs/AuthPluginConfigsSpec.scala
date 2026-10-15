package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class AuthPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgAuthModuleConfig" should {

    val format = NgAuthModuleConfig.format

    "have no module and not pass with an apikey by default" in {
      NgAuthModuleConfig().module mustBe None
      NgAuthModuleConfig().passWithApikey mustBe false
    }

    "write the default config with a null auth_module" in {
      format.writes(NgAuthModuleConfig()) mustBe Json.obj(
        "pass_with_apikey" -> false,
        "auth_module"      -> JsNull
      )
    }

    "write the module under auth_module" in {
      format.writes(NgAuthModuleConfig(Some("auth_mod_1"), passWithApikey = true)) mustBe Json.obj(
        "pass_with_apikey" -> true,
        "auth_module"      -> "auth_mod_1"
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgAuthModuleConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, NgAuthModuleConfig(Some("auth_mod_1"), passWithApikey = true))
    }

    "round trip a module alone" in {
      roundTrip(format, NgAuthModuleConfig(module = Some("auth_mod_1")))
    }

    "round trip pass_with_apikey alone" in {
      roundTrip(format, NgAuthModuleConfig(passWithApikey = true))
    }

    "read an empty module back as no module" in {
      val json = format.writes(NgAuthModuleConfig(module = Some("")))
      json mustBe Json.obj("pass_with_apikey" -> false, "auth_module" -> "")
      readOk(format, json) mustBe NgAuthModuleConfig()
    }

    "read the legacy module key" in {
      readOk(format, Json.obj("module" -> "auth_mod_legacy")) mustBe NgAuthModuleConfig(Some("auth_mod_legacy"))
    }

    "prefer auth_module over module" in {
      readOk(format, Json.obj("auth_module" -> "auth_mod_1", "module" -> "auth_mod_2")) mustBe NgAuthModuleConfig(
        Some("auth_mod_1")
      )
    }

    "fall back to module when auth_module is null" in {
      readOk(format, Json.obj("auth_module" -> JsNull, "module" -> "auth_mod_2")) mustBe NgAuthModuleConfig(
        Some("auth_mod_2")
      )
    }

    "fall back to module when auth_module is not a string" in {
      readOk(format, Json.obj("auth_module" -> 42, "module" -> "auth_mod_2")) mustBe NgAuthModuleConfig(
        Some("auth_mod_2")
      )
    }

    "not fall back to module when auth_module is an empty string" in {
      readOk(format, Json.obj("auth_module" -> "", "module" -> "auth_mod_2")) mustBe NgAuthModuleConfig()
    }

    "read an empty legacy module as no module" in {
      readOk(format, Json.obj("module" -> "")) mustBe NgAuthModuleConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgAuthModuleConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgAuthModuleConfig()
    }

    "read a json string as the default config, not as the module" in {
      readOk(format, JsString("auth_mod_1")) mustBe NgAuthModuleConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("auth_mod_1")) mustBe NgAuthModuleConfig()
    }

    "ignore a pass_with_apikey written as a string" in {
      readOk(format, Json.obj("pass_with_apikey" -> "true")) mustBe NgAuthModuleConfig()
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("authModule" -> "auth_mod_1", "passWithApikey" -> true)) mustBe NgAuthModuleConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("auth_module" -> "auth_mod_1", "foo" -> "bar")) mustBe NgAuthModuleConfig(
        Some("auth_mod_1")
      )
    }

    "rewrite the legacy module key as auth_module" in {
      val legacy = readOk(format, Json.obj("module" -> "auth_mod_legacy"))
      format.writes(legacy) mustBe Json.obj("pass_with_apikey" -> false, "auth_module" -> "auth_mod_legacy")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgAuthModuleConfig(Some("auth_mod_1"), passWithApikey = true))
    }

    "be the default config of AuthModule" in {
      defaultConfigReadsBack(new AuthModule(), format) mustBe NgAuthModuleConfig()
    }
  }

  "NgLegacyAuthModuleCallConfig" should {

    val format  = NgLegacyAuthModuleCallConfig.format
    val default = NgLegacyAuthModuleCallConfig.default

    val full = NgLegacyAuthModuleCallConfig(
      publicPatterns = Seq("/public/.*", "/health"),
      privatePatterns = Seq("/public/admin/.*"),
      config = NgAuthModuleConfig(Some("auth_mod_1"), passWithApikey = true)
    )

    "have no pattern and the default auth module config by default" in {
      default mustBe NgLegacyAuthModuleCallConfig(Seq.empty, Seq.empty, NgAuthModuleConfig())
    }

    "write the default config with the auth module fields at the top level" in {
      format.writes(default) mustBe Json.obj(
        "public_patterns"  -> Json.arr(),
        "private_patterns" -> Json.arr(),
        "pass_with_apikey" -> false,
        "auth_module"      -> JsNull
      )
    }

    "write every field, without nesting the auth module config" in {
      format.writes(full) mustBe Json.obj(
        "public_patterns"  -> Json.arr("/public/.*", "/health"),
        "private_patterns" -> Json.arr("/public/admin/.*"),
        "pass_with_apikey" -> true,
        "auth_module"      -> "auth_mod_1"
      )
    }

    "round trip the default config" in {
      roundTrip(format, default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip public patterns alone and keep their order" in {
      roundTrip(format, default.copy(publicPatterns = Seq("/b", "/a"))).publicPatterns mustBe Seq("/b", "/a")
    }

    "round trip private patterns alone" in {
      roundTrip(format, default.copy(privatePatterns = Seq("/private/.*")))
    }

    "round trip an auth module config alone" in {
      roundTrip(format, default.copy(config = NgAuthModuleConfig(Some("auth_mod_1"))))
    }

    "read the legacy module key of the auth module config" in {
      readOk(format, Json.obj("module" -> "auth_mod_legacy")).config mustBe NgAuthModuleConfig(Some("auth_mod_legacy"))
    }

    "not read an auth module config nested under config" in {
      readOk(format, Json.obj("config" -> Json.obj("auth_module" -> "auth_mod_1"))) mustBe default
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("/public/.*")) mustBe default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("/public/.*")) mustBe default
    }

    "read pass_with_apikey alone" in {
      readOk(format, Json.obj("pass_with_apikey" -> true)) mustBe default.copy(config =
        NgAuthModuleConfig(passWithApikey = true)
      )
    }

    "ignore public patterns written as a single string" in {
      readOk(format, Json.obj("public_patterns" -> "/public/.*")) mustBe default
    }

    "ignore a private patterns array holding something else than strings" in {
      readOk(format, Json.obj("private_patterns" -> Json.arr("/private", 1))) mustBe default
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("publicPatterns" -> Json.arr("/a"), "privatePatterns" -> Json.arr("/b"))
      ) mustBe default
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("public_patterns" -> Json.arr("/a"), "foo" -> "bar")) mustBe default.copy(
        publicPatterns = Seq("/a")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgLegacyAuthModuleCall" in {
      defaultConfigReadsBack(new NgLegacyAuthModuleCall(), format) mustBe default
    }
  }

  "NgMultiAuthModuleConfig" should {

    val format = NgMultiAuthModuleConfig.format

    val full = NgMultiAuthModuleConfig(
      modules = Seq("auth_mod_1", "auth_mod_2"),
      passWithApikey = true,
      useEmailPrompt = true,
      usersGroups = Json.obj("auth_mod_1" -> Json.arr("*@oto.tools"))
    )

    "have no module, no apikey pass, no email prompt and no user group by default" in {
      NgMultiAuthModuleConfig() mustBe NgMultiAuthModuleConfig(Seq.empty, false, false, Json.obj())
    }

    "write the default config" in {
      format.writes(NgMultiAuthModuleConfig()) mustBe Json.obj(
        "pass_with_apikey" -> false,
        "auth_modules"     -> Json.arr(),
        "use_email_prompt" -> false,
        "users_groups"     -> Json.obj()
      )
    }

    "write every field under its snake case key" in {
      format.writes(full) mustBe Json.obj(
        "pass_with_apikey" -> true,
        "auth_modules"     -> Json.arr("auth_mod_1", "auth_mod_2"),
        "use_email_prompt" -> true,
        "users_groups"     -> Json.obj("auth_mod_1" -> Json.arr("*@oto.tools"))
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgMultiAuthModuleConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip several modules and keep their order" in {
      roundTrip(format, NgMultiAuthModuleConfig(modules = Seq("c", "a", "b"))).modules mustBe Seq("c", "a", "b")
    }

    "round trip a single module" in {
      roundTrip(format, NgMultiAuthModuleConfig(modules = Seq("auth_mod_1")))
    }

    "round trip the email prompt alone" in {
      roundTrip(format, NgMultiAuthModuleConfig(useEmailPrompt = true))
    }

    "round trip nested user groups as is" in {
      val groups = Json.obj(
        "auth_mod_1" -> Json.obj("emails" -> Json.arr("a@oto.tools"), "domains" -> Json.arr("oto.tools")),
        "auth_mod_2" -> Json.arr()
      )
      roundTrip(format, NgMultiAuthModuleConfig(usersGroups = groups)).usersGroups mustBe groups
    }

    "drop the empty module ids" in {
      readOk(format, Json.obj("auth_modules" -> Json.arr("", "auth_mod_1", ""))).modules mustBe Seq("auth_mod_1")
    }

    "read the legacy modules key" in {
      readOk(format, Json.obj("modules" -> Json.arr("auth_mod_legacy"))) mustBe NgMultiAuthModuleConfig(
        modules = Seq("auth_mod_legacy")
      )
    }

    "prefer auth_modules over modules" in {
      readOk(
        format,
        Json.obj("auth_modules" -> Json.arr("auth_mod_1"), "modules" -> Json.arr("auth_mod_2"))
      ).modules mustBe Seq("auth_mod_1")
    }

    "fall back to modules when auth_modules is not an array of strings" in {
      readOk(
        format,
        Json.obj("auth_modules" -> "auth_mod_1", "modules" -> Json.arr("auth_mod_2"))
      ).modules mustBe Seq("auth_mod_2")
    }

    "not fall back to modules when auth_modules is an empty array" in {
      readOk(
        format,
        Json.obj("auth_modules" -> Json.arr(), "modules" -> Json.arr("auth_mod_2"))
      ).modules mustBe empty
    }

    "rewrite the legacy modules key as auth_modules" in {
      val legacy = readOk(format, Json.obj("modules" -> Json.arr("auth_mod_legacy")))
      written(format, legacy).keys must not contain "modules"
      (format.writes(legacy) \ "auth_modules").as[Seq[String]] mustBe Seq("auth_mod_legacy")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgMultiAuthModuleConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgMultiAuthModuleConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("auth_mod_1")) mustBe NgMultiAuthModuleConfig()
    }

    "read a json array as the default config, not as the modules" in {
      readOk(format, Json.arr("auth_mod_1")) mustBe NgMultiAuthModuleConfig()
    }

    "read use_email_prompt alone" in {
      readOk(format, Json.obj("use_email_prompt" -> true)) mustBe NgMultiAuthModuleConfig(useEmailPrompt = true)
    }

    "read pass_with_apikey alone" in {
      readOk(format, Json.obj("pass_with_apikey" -> true)) mustBe NgMultiAuthModuleConfig(passWithApikey = true)
    }

    "ignore booleans written as strings" in {
      readOk(
        format,
        Json.obj("pass_with_apikey" -> "true", "use_email_prompt" -> "true")
      ) mustBe NgMultiAuthModuleConfig()
    }

    "ignore user groups written as an array" in {
      readOk(format, Json.obj("users_groups" -> Json.arr("a@oto.tools"))) mustBe NgMultiAuthModuleConfig()
    }

    "ignore user groups written as a string" in {
      readOk(format, Json.obj("users_groups" -> "{}")) mustBe NgMultiAuthModuleConfig()
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj(
          "authModules"    -> Json.arr("a"),
          "passWithApikey" -> true,
          "useEmailPrompt" -> true,
          "usersGroups"    -> Json.obj("a" -> 1)
        )
      ) mustBe NgMultiAuthModuleConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("auth_modules" -> Json.arr("a"), "foo" -> "bar")) mustBe NgMultiAuthModuleConfig(
        modules = Seq("a")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of MultiAuthModule" in {
      defaultConfigReadsBack(new MultiAuthModule(), format) mustBe NgMultiAuthModuleConfig()
    }
  }

  "NgAuthModuleUserExtractorConfig" should {

    val format = NgAuthModuleUserExtractorConfig.format

    "have no module by default" in {
      NgAuthModuleUserExtractorConfig().module mustBe None
    }

    "write the default config with a null auth_module" in {
      format.writes(NgAuthModuleUserExtractorConfig()) mustBe Json.obj("auth_module" -> JsNull)
    }

    "write the module under auth_module, without pass_with_apikey" in {
      format.writes(NgAuthModuleUserExtractorConfig(Some("auth_mod_1"))) mustBe Json.obj("auth_module" -> "auth_mod_1")
    }

    "round trip the default config" in {
      roundTrip(format, NgAuthModuleUserExtractorConfig())
    }

    "round trip a module" in {
      roundTrip(format, NgAuthModuleUserExtractorConfig(Some("auth_mod_1")))
    }

    "read an empty module back as no module" in {
      readOk(format, format.writes(NgAuthModuleUserExtractorConfig(Some("")))) mustBe NgAuthModuleUserExtractorConfig()
    }

    "read the legacy module key" in {
      readOk(format, Json.obj("module" -> "auth_mod_legacy")) mustBe NgAuthModuleUserExtractorConfig(
        Some("auth_mod_legacy")
      )
    }

    "prefer auth_module over module" in {
      readOk(
        format,
        Json.obj("auth_module" -> "auth_mod_1", "module" -> "auth_mod_2")
      ) mustBe NgAuthModuleUserExtractorConfig(Some("auth_mod_1"))
    }

    "fall back to module when auth_module is null" in {
      readOk(
        format,
        Json.obj("auth_module" -> JsNull, "module" -> "auth_mod_2")
      ) mustBe NgAuthModuleUserExtractorConfig(Some("auth_mod_2"))
    }

    "not fall back to module when auth_module is an empty string" in {
      readOk(format, Json.obj("auth_module" -> "", "module" -> "auth_mod_2")) mustBe NgAuthModuleUserExtractorConfig()
    }

    "rewrite the legacy module key as auth_module" in {
      format.writes(readOk(format, Json.obj("module" -> "auth_mod_legacy"))) mustBe Json.obj(
        "auth_module" -> "auth_mod_legacy"
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgAuthModuleUserExtractorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgAuthModuleUserExtractorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("auth_mod_1")) mustBe NgAuthModuleUserExtractorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("auth_mod_1")) mustBe NgAuthModuleUserExtractorConfig()
    }

    "ignore a module that is not a string" in {
      readOk(format, Json.obj("auth_module" -> Json.obj("id" -> "auth_mod_1"))) mustBe NgAuthModuleUserExtractorConfig()
    }

    "read the module of an auth module config and drop its pass_with_apikey" in {
      val json = NgAuthModuleConfig(Some("auth_mod_1"), passWithApikey = true).json
      readOk(format, json) mustBe NgAuthModuleUserExtractorConfig(Some("auth_mod_1"))
    }

    "not read the camel case authModule key" in {
      readOk(format, Json.obj("authModule" -> "auth_mod_1")) mustBe NgAuthModuleUserExtractorConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("auth_module" -> "auth_mod_1", "foo" -> "bar")) mustBe NgAuthModuleUserExtractorConfig(
        Some("auth_mod_1")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgAuthModuleUserExtractorConfig(Some("auth_mod_1")))
    }

    "be the default config of NgAuthModuleUserExtractor" in {
      defaultConfigReadsBack(new NgAuthModuleUserExtractor(), format) mustBe NgAuthModuleUserExtractorConfig()
    }
  }

  "NgAuthModuleExpectedUserConfig" should {

    val format = NgAuthModuleExpectedUserConfig.format

    "accept users from any module by default" in {
      NgAuthModuleExpectedUserConfig().onlyFrom mustBe empty
    }

    "write the default config as an empty only_from array" in {
      format.writes(NgAuthModuleExpectedUserConfig()) mustBe Json.obj("only_from" -> Json.arr())
    }

    "write the modules under only_from" in {
      format.writes(NgAuthModuleExpectedUserConfig(Seq("auth_mod_1", "auth_mod_2"))) mustBe Json.obj(
        "only_from" -> Json.arr("auth_mod_1", "auth_mod_2")
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgAuthModuleExpectedUserConfig())
    }

    "round trip a single module" in {
      roundTrip(format, NgAuthModuleExpectedUserConfig(Seq("auth_mod_1")))
    }

    "round trip several modules and keep their order" in {
      roundTrip(format, NgAuthModuleExpectedUserConfig(Seq("c", "a", "b"))).onlyFrom mustBe Seq("c", "a", "b")
    }

    "keep empty and duplicated module ids" in {
      roundTrip(format, NgAuthModuleExpectedUserConfig(Seq("", "a", "a"))).onlyFrom mustBe Seq("", "a", "a")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgAuthModuleExpectedUserConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgAuthModuleExpectedUserConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("auth_mod_1")) mustBe NgAuthModuleExpectedUserConfig()
    }

    "read a json array as the default config, not as the modules" in {
      readOk(format, Json.arr("auth_mod_1")) mustBe NgAuthModuleExpectedUserConfig()
    }

    "ignore an only_from written as a single string" in {
      readOk(format, Json.obj("only_from" -> "auth_mod_1")) mustBe NgAuthModuleExpectedUserConfig()
    }

    "ignore an only_from array holding something else than strings" in {
      readOk(format, Json.obj("only_from" -> Json.arr("auth_mod_1", JsNull))) mustBe NgAuthModuleExpectedUserConfig()
    }

    "not read the camel case onlyFrom key" in {
      readOk(format, Json.obj("onlyFrom" -> Json.arr("auth_mod_1"))) mustBe NgAuthModuleExpectedUserConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("only_from" -> Json.arr("a"), "foo" -> "bar")) mustBe NgAuthModuleExpectedUserConfig(
        Seq("a")
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgAuthModuleExpectedUserConfig(Seq("auth_mod_1")))
    }

    "be the default config of NgAuthModuleExpectedUser" in {
      defaultConfigReadsBack(new NgAuthModuleExpectedUser(), format) mustBe NgAuthModuleExpectedUserConfig()
    }
  }

  "BasicAuthCallerConfig" should {

    val format = BasicAuthCallerConfig.format

    val full = BasicAuthCallerConfig(
      username = Some("bob"),
      password = Some("s3cr3t:with:colons"),
      headerName = "Proxy-Authorization",
      headerValueFormat = "Basic realm=api %s"
    )

    "have no credentials and send a basic authorization header by default" in {
      BasicAuthCallerConfig() mustBe BasicAuthCallerConfig(None, None, "Authorization", "Basic %s")
    }

    "write the default config with null credentials" in {
      format.writes(BasicAuthCallerConfig()) mustBe Json.obj(
        "username"          -> JsNull,
        "password"          -> JsNull,
        "headerName"        -> "Authorization",
        "headerValueFormat" -> "Basic %s"
      )
    }

    "write every field, the header ones under camel case keys" in {
      format.writes(full) mustBe Json.obj(
        "username"          -> "bob",
        "password"          -> "s3cr3t:with:colons",
        "headerName"        -> "Proxy-Authorization",
        "headerValueFormat" -> "Basic realm=api %s"
      )
    }

    "round trip the default config" in {
      roundTrip(format, BasicAuthCallerConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip credentials alone" in {
      roundTrip(format, BasicAuthCallerConfig(username = Some("bob"), password = Some("pwd")))
    }

    "round trip a username without password" in {
      roundTrip(format, BasicAuthCallerConfig(username = Some("bob")))
    }

    "round trip a custom header name alone" in {
      roundTrip(format, BasicAuthCallerConfig(headerName = "X-Auth"))
    }

    "round trip a custom header value format alone" in {
      roundTrip(format, BasicAuthCallerConfig(headerValueFormat = "Credentials %s"))
    }

    "read empty credentials back as no credentials" in {
      val json = format.writes(BasicAuthCallerConfig(username = Some(""), password = Some("")))
      (json \ "username").as[String] mustBe ""
      readOk(format, json) mustBe BasicAuthCallerConfig()
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe BasicAuthCallerConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe BasicAuthCallerConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("bob:pwd")) mustBe BasicAuthCallerConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("bob", "pwd")) mustBe BasicAuthCallerConfig()
    }

    "read a password alone" in {
      readOk(format, Json.obj("password" -> "pwd")) mustBe BasicAuthCallerConfig(password = Some("pwd"))
    }

    "read the header name alone" in {
      readOk(format, Json.obj("headerName" -> "X-Auth")) mustBe BasicAuthCallerConfig(headerName = "X-Auth")
    }

    "ignore a username that is not a string" in {
      readOk(format, Json.obj("username" -> 42)) mustBe BasicAuthCallerConfig()
    }

    "ignore a header name that is not a string" in {
      readOk(format, Json.obj("headerName" -> true)) mustBe BasicAuthCallerConfig()
    }

    "keep an empty header name as is" in {
      readOk(format, Json.obj("headerName" -> "")).headerName mustBe ""
    }

    "not read the snake case header keys" in {
      readOk(
        format,
        Json.obj("header_name" -> "X-Auth", "header_value_format" -> "Token %s")
      ) mustBe BasicAuthCallerConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("username" -> "bob", "foo" -> "bar")) mustBe BasicAuthCallerConfig(username = Some("bob"))
    }

    "place the encoded token in the header value format" in {
      BasicAuthCallerConfig().headerValueFormat.format("Ym9iOnB3ZA==") mustBe "Basic Ym9iOnB3ZA=="
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of BasicAuthCaller" in {
      defaultConfigReadsBack(new BasicAuthCaller(), format) mustBe BasicAuthCallerConfig()
    }
  }

  "SimpleBasicAuthConfig" should {

    val format = SimpleBasicAuthConfig.format

    val full = SimpleBasicAuthConfig(
      realm = "my-api",
      users = Map(
        "alice" -> "clear-text",
        "bob"   -> "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
      )
    )

    "use the authentication realm without any user by default" in {
      SimpleBasicAuthConfig() mustBe SimpleBasicAuthConfig("authentication", Map.empty)
    }

    "write the default config" in {
      format.writes(SimpleBasicAuthConfig()) mustBe Json.obj(
        "realm" -> "authentication",
        "users" -> Json.obj()
      )
    }

    "write the users as an object of passwords" in {
      format.writes(full) mustBe Json.obj(
        "realm" -> "my-api",
        "users" -> Json.obj(
          "alice" -> "clear-text",
          "bob"   -> "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        )
      )
    }

    "round trip the default config" in {
      roundTrip(format, SimpleBasicAuthConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a realm alone" in {
      roundTrip(format, SimpleBasicAuthConfig(realm = "backoffice"))
    }

    "round trip passwords holding colons and unicode" in {
      roundTrip(format, SimpleBasicAuthConfig(users = Map("bob" -> "a:b:c été")))
    }

    "round trip an empty password" in {
      roundTrip(format, SimpleBasicAuthConfig(users = Map("bob" -> ""))).users mustBe Map("bob" -> "")
    }

    "round trip an empty realm as is" in {
      roundTrip(format, SimpleBasicAuthConfig(realm = "")).realm mustBe ""
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe SimpleBasicAuthConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe SimpleBasicAuthConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("bob:pwd")) mustBe SimpleBasicAuthConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("bob:pwd")) mustBe SimpleBasicAuthConfig()
    }

    "read users alone" in {
      readOk(format, Json.obj("users" -> Json.obj("bob" -> "pwd"))) mustBe SimpleBasicAuthConfig(
        users = Map("bob" -> "pwd")
      )
    }

    "ignore a realm that is not a string" in {
      readOk(format, Json.obj("realm" -> 42)) mustBe SimpleBasicAuthConfig()
    }

    "ignore the whole users map as soon as a password is not a string" in {
      readOk(format, Json.obj("users" -> Json.obj("alice" -> "pwd", "bob" -> 1234))) mustBe SimpleBasicAuthConfig()
    }

    "ignore users written as an array of credentials" in {
      readOk(format, Json.obj("users" -> Json.arr("bob:pwd"))) mustBe SimpleBasicAuthConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("realm" -> "my-api", "foo" -> "bar")) mustBe SimpleBasicAuthConfig(realm = "my-api")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "list every written key in its config flow and schema" in {
      SimpleBasicAuthConfig.configFlow mustBe Seq("realm", "users")
      SimpleBasicAuthConfig.configFlow.toSet mustBe writtenKeys(format, SimpleBasicAuthConfig())
      SimpleBasicAuthConfig.configSchema.get.keys mustBe SimpleBasicAuthConfig.configFlow.toSet
    }

    "let the ui hash the passwords in its schema" in {
      (SimpleBasicAuthConfig.configSchema.get \ "users" \ "props" \ "bcryptable").as[Boolean] mustBe true
    }

    "be the default config of SimpleBasicAuth" in {
      defaultConfigReadsBack(new SimpleBasicAuth(), format) mustBe SimpleBasicAuthConfig()
    }

    "be the config flow and schema of SimpleBasicAuth" in {
      val plugin = new SimpleBasicAuth()
      plugin.configFlow mustBe SimpleBasicAuthConfig.configFlow
      plugin.configSchema mustBe SimpleBasicAuthConfig.configSchema
    }
  }

  "BasicAuthWithAuthModuleConfig" should {

    val format = BasicAuthWithAuthModuleConfig.format

    "have an empty reference and add the authenticate header by default" in {
      BasicAuthWithAuthModuleConfig() mustBe BasicAuthWithAuthModuleConfig("", addAuthenticateHeader = true)
    }

    "write the default config with an empty reference" in {
      format.writes(BasicAuthWithAuthModuleConfig()) mustBe Json.obj(
        "ref"                     -> "",
        "add_authenticate_header" -> true
      )
    }

    "write every field under its snake case key" in {
      format.writes(BasicAuthWithAuthModuleConfig("auth_mod_1", addAuthenticateHeader = false)) mustBe Json.obj(
        "ref"                     -> "auth_mod_1",
        "add_authenticate_header" -> false
      )
    }

    "round trip the default config" in {
      roundTrip(format, BasicAuthWithAuthModuleConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, BasicAuthWithAuthModuleConfig("auth_mod_1", addAuthenticateHeader = false))
    }

    "round trip a reference alone" in {
      roundTrip(format, BasicAuthWithAuthModuleConfig(ref = "auth_mod_1"))
    }

    "round trip a config without authenticate header alone" in {
      roundTrip(format, BasicAuthWithAuthModuleConfig(addAuthenticateHeader = false))
    }

    "read a reference alone, adding the authenticate header" in {
      readOk(format, Json.obj("ref" -> "auth_mod_1")) mustBe BasicAuthWithAuthModuleConfig("auth_mod_1", true)
    }

    "not read an empty object, the reference is mandatory" in {
      readError(format, Json.obj())
    }

    "not read a config holding only add_authenticate_header" in {
      readError(format, Json.obj("add_authenticate_header" -> false))
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json string, even holding a reference" in {
      readError(format, JsString("auth_mod_1"))
    }

    "not read a json array" in {
      readError(format, Json.arr("auth_mod_1"))
    }

    "not read a reference that is not a string" in {
      readError(format, Json.obj("ref" -> 42))
    }

    "not read a null reference" in {
      readError(format, Json.obj("ref" -> JsNull))
    }

    "ignore an add_authenticate_header written as a string" in {
      readOk(format, Json.obj("ref" -> "a", "add_authenticate_header" -> "false")) mustBe BasicAuthWithAuthModuleConfig(
        "a",
        true
      )
    }

    "not read the camel case addAuthenticateHeader key" in {
      readOk(format, Json.obj("ref" -> "a", "addAuthenticateHeader" -> false)) mustBe BasicAuthWithAuthModuleConfig(
        "a",
        true
      )
    }

    "not read the auth_module key of the other auth plugins" in {
      readError(format, Json.obj("auth_module" -> "auth_mod_1"))
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("ref" -> "a", "foo" -> "bar")) mustBe BasicAuthWithAuthModuleConfig("a")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, BasicAuthWithAuthModuleConfig("auth_mod_1", addAuthenticateHeader = false))
    }

    "list every written key in its config flow and schema" in {
      BasicAuthWithAuthModuleConfig.configFlow mustBe Seq("ref", "add_authenticate_header")
      BasicAuthWithAuthModuleConfig.configFlow.toSet mustBe writtenKeys(format, BasicAuthWithAuthModuleConfig())
      BasicAuthWithAuthModuleConfig.configSchema.get.keys mustBe BasicAuthWithAuthModuleConfig.configFlow.toSet
    }

    "only offer basic and ldap auth modules in its schema" in {
      (BasicAuthWithAuthModuleConfig.configSchema.get \ "ref" \ "props" \ "optionsFrom")
        .as[String] mustBe "/bo/api/proxy/api/auths?types=basic&types=ldap"
    }

    "be the default config of BasicAuthWithAuthModule" in {
      defaultConfigReadsBack(new BasicAuthWithAuthModule(), format) mustBe BasicAuthWithAuthModuleConfig()
    }

    "be the config flow and schema of BasicAuthWithAuthModule" in {
      val plugin = new BasicAuthWithAuthModule()
      plugin.configFlow mustBe BasicAuthWithAuthModuleConfig.configFlow
      plugin.configSchema mustBe BasicAuthWithAuthModuleConfig.configSchema
    }
  }

  "the auth plugins without configuration" should {

    "have no default config for NgExpectedConsumer" in {
      hasNoDefaultConfig(new NgExpectedConsumer())
    }
  }
}
