package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class SecurityPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgSecurityTxtConfig" should {

    val format = NgSecurityTxtConfig.format

    val full = NgSecurityTxtConfig(
      contact = Seq("mailto:security@oto.tools", "https://oto.tools/security"),
      expires = Some("2030-01-01T00:00:00Z"),
      acknowledgments = Some("https://oto.tools/hall-of-fame"),
      preferredLanguages = Some("en, fr"),
      policy = Some("https://oto.tools/policy"),
      hiring = Some("https://oto.tools/jobs"),
      encryption = Some("https://oto.tools/pgp.asc"),
      csaf = Some("https://oto.tools/.well-known/csaf/provider-metadata.json"),
      autoExpires = true,
      expiresYears = 3
    )

    "have a placeholder contact, no optional field and a one year manual expiry by default" in {
      val config = NgSecurityTxtConfig()
      config.contact mustBe Seq("contact@foo.bar")
      config.expires mustBe None
      config.acknowledgments mustBe None
      config.preferredLanguages mustBe None
      config.policy mustBe None
      config.hiring mustBe None
      config.encryption mustBe None
      config.csaf mustBe None
      config.autoExpires mustBe false
      config.expiresYears mustBe 1
    }

    "write the default config without any optional field" in {
      format.writes(NgSecurityTxtConfig()) mustBe Json.obj(
        "contact"       -> Json.arr("contact@foo.bar"),
        "auto_expires"  -> false,
        "expires_years" -> 1
      )
    }

    "write every field under its snake case key" in {
      format.writes(full) mustBe Json.obj(
        "contact"             -> Json.arr("mailto:security@oto.tools", "https://oto.tools/security"),
        "auto_expires"        -> true,
        "expires_years"       -> 3,
        "expires"             -> "2030-01-01T00:00:00Z",
        "acknowledgments"     -> "https://oto.tools/hall-of-fame",
        "preferred_languages" -> "en, fr",
        "policy"              -> "https://oto.tools/policy",
        "hiring"              -> "https://oto.tools/jobs",
        "encryption"          -> "https://oto.tools/pgp.asc",
        "csaf"                -> "https://oto.tools/.well-known/csaf/provider-metadata.json"
      )
    }

    "write only the optional fields that are set, without nulls" in {
      writtenKeys(format, NgSecurityTxtConfig(policy = Some("p"), csaf = Some("c"))) mustBe Set(
        "contact",
        "auto_expires",
        "expires_years",
        "policy",
        "csaf"
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgSecurityTxtConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config with some of the optional fields" in {
      roundTrip(format, NgSecurityTxtConfig(expires = Some("2031-12-31T23:59:59Z"), hiring = Some("https://jobs")))
    }

    "round trip several contacts and keep their order" in {
      roundTrip(format, NgSecurityTxtConfig(contact = Seq("tel:+33123456789", "mailto:a@b.c"))).contact mustBe Seq(
        "tel:+33123456789",
        "mailto:a@b.c"
      )
    }

    "round trip an empty contact list, without falling back to the placeholder contact" in {
      roundTrip(format, NgSecurityTxtConfig(contact = Seq.empty)).contact mustBe empty
    }

    "round trip an automatic expiry over several years" in {
      roundTrip(format, NgSecurityTxtConfig(autoExpires = true, expiresYears = 5))
    }

    "round trip an empty optional field as an empty string" in {
      roundTrip(format, NgSecurityTxtConfig(encryption = Some(""))).encryption mustBe Some("")
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgSecurityTxtConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgSecurityTxtConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("mailto:security@oto.tools")) mustBe NgSecurityTxtConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("mailto:security@oto.tools")) mustBe NgSecurityTxtConfig()
    }

    "read contacts alone" in {
      readOk(format, Json.obj("contact" -> Json.arr("mailto:a@b.c"))) mustBe NgSecurityTxtConfig(
        contact = Seq("mailto:a@b.c")
      )
    }

    "read auto_expires alone" in {
      readOk(format, Json.obj("auto_expires" -> true)) mustBe NgSecurityTxtConfig(autoExpires = true)
    }

    "read expires_years alone" in {
      readOk(format, Json.obj("expires_years" -> 2)) mustBe NgSecurityTxtConfig(expiresYears = 2)
    }

    "read preferred_languages alone" in {
      readOk(format, Json.obj("preferred_languages" -> "fr")) mustBe NgSecurityTxtConfig(preferredLanguages = Some("fr"))
    }

    "fall back to the placeholder contact when contact is a single string" in {
      readOk(format, Json.obj("contact" -> "mailto:a@b.c")).contact mustBe Seq("contact@foo.bar")
    }

    "fall back to the placeholder contact when contact holds something else than strings" in {
      readOk(format, Json.obj("contact" -> Json.arr("mailto:a@b.c", 42))).contact mustBe Seq("contact@foo.bar")
    }

    "ignore an expires_years written as a string" in {
      readOk(format, Json.obj("expires_years" -> "2")).expiresYears mustBe 1
    }

    "ignore an expires_years that is not a whole number" in {
      readOk(format, Json.obj("expires_years" -> 1.5)).expiresYears mustBe 1
    }

    "ignore an auto_expires written as a string" in {
      readOk(format, Json.obj("auto_expires" -> "true")).autoExpires mustBe false
    }

    "read null optional fields as none" in {
      readOk(format, Json.obj("expires" -> JsNull, "policy" -> JsNull, "csaf" -> JsNull)) mustBe NgSecurityTxtConfig()
    }

    "ignore an optional field that is not a string" in {
      readOk(format, Json.obj("policy" -> 42, "hiring" -> Json.arr("https://jobs"))) mustBe NgSecurityTxtConfig()
    }

    "not read the camel case keys" in {
      readOk(
        format,
        Json.obj("preferredLanguages" -> "fr", "autoExpires" -> true, "expiresYears" -> 2)
      ) mustBe NgSecurityTxtConfig()
    }

    "not read the rfc field names" in {
      readOk(
        format,
        Json.obj("Contact" -> Json.arr("mailto:a@b.c"), "Preferred-Languages" -> "fr", "acknowledgements" -> "x")
      ) mustBe NgSecurityTxtConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("auto_expires" -> true, "foo" -> "bar")) mustBe NgSecurityTxtConfig(autoExpires = true)
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgSecurityTxt" in {
      defaultConfigReadsBack(new NgSecurityTxt(), format) mustBe NgSecurityTxtConfig()
    }
  }

  "SecurityHeadersPluginConfig" should {

    val format  = SecurityHeadersPluginConfig.format
    val default = SecurityHeadersPluginConfig.default

    val full = SecurityHeadersPluginConfig(
      frameOptions = FrameOptions.DENY,
      xssProtection = XssProtection.BLOCK,
      contentTypeOptions = true,
      hsts = HstsConf(enabled = true, includeSubdomains = true, maxAge = 31536000L, preload = true, onHttp = true),
      csp = CspConf(CspMode.ENABLED, "default-src 'self'"),
      referrerPolicy = ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN,
      permissionsPolicy = PermissionsPolicyConf(enabled = true, policy = "geolocation=()")
    )

    // the three top level fields, all disabled
    val mandatory = Json.obj(
      "frame_options"        -> "DISABLED",
      "xss_protection"       -> "DISABLED",
      "content_type_options" -> false
    )

    "disable every header by default" in {
      default.frameOptions mustBe FrameOptions.DISABLED
      default.xssProtection mustBe XssProtection.DISABLED
      default.contentTypeOptions mustBe false
      default.hsts mustBe HstsConf(false, false, 3600L, false, false)
      default.csp mustBe CspConf(CspMode.DISABLED, "")
      default.referrerPolicy mustBe ReferrerPolicy.DISABLED
      default.permissionsPolicy mustBe PermissionsPolicyConf(false, "")
    }

    "write the default config" in {
      format.writes(default) mustBe Json.obj(
        "frame_options"        -> "DISABLED",
        "xss_protection"       -> "DISABLED",
        "content_type_options" -> false,
        "hsts"                 -> Json.obj(
          "enabled"            -> false,
          "include_subdomains" -> false,
          "max_age"            -> 3600,
          "preload"            -> false,
          "on_http"            -> false
        ),
        "csp"                  -> Json.obj("mode" -> "DISABLED", "csp" -> ""),
        "referrer_policy"      -> "DISABLED",
        "permissions_policy"   -> Json.obj("enabled" -> false, "policy" -> "")
      )
    }

    "write every field under its snake case key" in {
      format.writes(full) mustBe Json.obj(
        "frame_options"        -> "DENY",
        "xss_protection"       -> "BLOCK",
        "content_type_options" -> true,
        "hsts"                 -> Json.obj(
          "enabled"            -> true,
          "include_subdomains" -> true,
          "max_age"            -> 31536000L,
          "preload"            -> true,
          "on_http"            -> true
        ),
        "csp"                  -> Json.obj("mode" -> "ENABLED", "csp" -> "default-src 'self'"),
        "referrer_policy"      -> "strict-origin-when-cross-origin",
        "permissions_policy"   -> Json.obj("enabled" -> true, "policy" -> "geolocation=()")
      )
    }

    "round trip the default config" in {
      roundTrip(format, default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip every frame option" in {
      roundTrip(format, default.copy(frameOptions = FrameOptions.DENY))
      roundTrip(format, default.copy(frameOptions = FrameOptions.SAMEORIGIN))
      roundTrip(format, default.copy(frameOptions = FrameOptions.DISABLED))
    }

    "round trip every xss protection" in {
      roundTrip(format, default.copy(xssProtection = XssProtection.OFF))
      roundTrip(format, default.copy(xssProtection = XssProtection.ON))
      roundTrip(format, default.copy(xssProtection = XssProtection.BLOCK))
      roundTrip(format, default.copy(xssProtection = XssProtection.DISABLED))
    }

    "round trip every content security policy mode" in {
      roundTrip(format, default.copy(csp = CspConf(CspMode.ENABLED, "default-src 'none'")))
      roundTrip(format, default.copy(csp = CspConf(CspMode.REPORT_ONLY, "default-src 'none'; report-uri /csp")))
      roundTrip(format, default.copy(csp = CspConf(CspMode.DISABLED, "default-src 'none'")))
    }

    "round trip every referrer policy" in {
      Seq(
        ReferrerPolicy.NO_REFERRER,
        ReferrerPolicy.NO_REFERRER_WHEN_DOWNGRADE,
        ReferrerPolicy.ORIGIN,
        ReferrerPolicy.ORIGIN_WHEN_CROSS_ORIGIN,
        ReferrerPolicy.SAME_ORIGIN,
        ReferrerPolicy.STRICT_ORIGIN,
        ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN,
        ReferrerPolicy.UNSAFE_URL,
        ReferrerPolicy.DISABLED
      ).foreach { policy =>
        roundTrip(format, default.copy(referrerPolicy = policy)).referrerPolicy mustBe policy
      }
    }

    "write the referrer policies with their header value, and DISABLED in upper case" in {
      ReferrerPolicy.NO_REFERRER.json mustBe JsString("no-referrer")
      ReferrerPolicy.NO_REFERRER_WHEN_DOWNGRADE.json mustBe JsString("no-referrer-when-downgrade")
      ReferrerPolicy.ORIGIN.json mustBe JsString("origin")
      ReferrerPolicy.ORIGIN_WHEN_CROSS_ORIGIN.json mustBe JsString("origin-when-cross-origin")
      ReferrerPolicy.SAME_ORIGIN.json mustBe JsString("same-origin")
      ReferrerPolicy.STRICT_ORIGIN.json mustBe JsString("strict-origin")
      ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN.json mustBe JsString("strict-origin-when-cross-origin")
      ReferrerPolicy.UNSAFE_URL.json mustBe JsString("unsafe-url")
      ReferrerPolicy.DISABLED.json mustBe JsString("DISABLED")
    }

    "round trip an hsts config with only some flags" in {
      roundTrip(format, default.copy(hsts = HstsConf(true, false, 600L, false, true)))
    }

    "round trip a permissions policy that is set but disabled" in {
      roundTrip(format, default.copy(permissionsPolicy = PermissionsPolicyConf(false, "camera=()")))
    }

    "read the three mandatory fields alone, the others take their defaults" in {
      readOk(format, mandatory) mustBe default
    }

    "read the enum values whatever their case" in {
      val config = readOk(
        format,
        mandatory ++ Json.obj("frame_options" -> "sameorigin", "xss_protection" -> "Block", "referrer_policy" -> "NO-REFERRER")
      )
      config.frameOptions mustBe FrameOptions.SAMEORIGIN
      config.xssProtection mustBe XssProtection.BLOCK
      config.referrerPolicy mustBe ReferrerPolicy.NO_REFERRER
    }

    "read the content security policy mode whatever its case" in {
      readOk(format, mandatory ++ Json.obj("csp" -> Json.obj("mode" -> "report_only", "csp" -> "x"))).csp mustBe CspConf(
        CspMode.REPORT_ONLY,
        "x"
      )
    }

    "read an unknown frame option as disabled" in {
      readOk(format, mandatory ++ Json.obj("frame_options" -> "ALLOW-FROM https://a.b")).frameOptions mustBe FrameOptions.DISABLED
    }

    "read an unknown xss protection as disabled" in {
      readOk(format, mandatory ++ Json.obj("xss_protection" -> "1; mode=block")).xssProtection mustBe XssProtection.DISABLED
    }

    "read an unknown referrer policy as disabled" in {
      readOk(format, mandatory ++ Json.obj("referrer_policy" -> "everything")).referrerPolicy mustBe ReferrerPolicy.DISABLED
    }

    "not read the scala names of the referrer policies" in {
      readOk(format, mandatory ++ Json.obj("referrer_policy" -> "NO_REFERRER")).referrerPolicy mustBe ReferrerPolicy.DISABLED
    }

    "ignore a referrer policy that is not a string" in {
      readOk(format, mandatory ++ Json.obj("referrer_policy" -> 1)).referrerPolicy mustBe ReferrerPolicy.DISABLED
    }

    "read an unknown content security policy mode as disabled, keeping the policy" in {
      readOk(format, mandatory ++ Json.obj("csp" -> Json.obj("mode" -> "ON", "csp" -> "x"))).csp mustBe CspConf(
        CspMode.DISABLED,
        "x"
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe default
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe default
    }

    "read a json string as the default config" in {
      readOk(format, JsString("DENY")) mustBe default
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("DENY")) mustBe default
    }

    "read a config without frame_options with frame options disabled" in {
      readOk(format, mandatory - "frame_options").frameOptions mustBe FrameOptions.DISABLED
    }

    "read a config without xss_protection with xss protection disabled" in {
      readOk(format, mandatory - "xss_protection").xssProtection mustBe XssProtection.DISABLED
    }

    "read a config without content_type_options without content type options" in {
      readOk(format, mandatory - "content_type_options").contentTypeOptions mustBe false
    }

    "ignore a content_type_options written as a string" in {
      readOk(format, mandatory ++ Json.obj("content_type_options" -> "true")).contentTypeOptions mustBe false
    }

    "ignore a frame_options that is not a string" in {
      readOk(format, mandatory ++ Json.obj("frame_options" -> true)).frameOptions mustBe FrameOptions.DISABLED
    }

    "read a lone hsts section and default the rest" in {
      val hsts = HstsConf(enabled = true, includeSubdomains = true, maxAge = 600L, preload = false, onHttp = false)
      readOk(format, Json.obj("hsts" -> HstsConf.format.writes(hsts))) mustBe default.copy(hsts = hsts)
    }

    "read a lone content security policy and default the rest" in {
      val csp = CspConf(CspMode.REPORT_ONLY, "default-src 'self'")
      readOk(format, Json.obj("csp" -> CspConf.format.writes(csp))) mustBe default.copy(csp = csp)
    }

    "read a lone frame_options and default the rest" in {
      readOk(format, Json.obj("frame_options" -> "DENY")) mustBe default.copy(frameOptions = FrameOptions.DENY)
    }

    "read a lone content_type_options and default the rest" in {
      readOk(format, Json.obj("content_type_options" -> true)) mustBe default.copy(contentTypeOptions = true)
    }

    "fill the missing hsts fields with their defaults" in {
      readOk(format, mandatory ++ Json.obj("hsts" -> Json.obj("enabled" -> true))).hsts mustBe HstsConf(
        enabled = true,
        includeSubdomains = false,
        maxAge = 3600L,
        preload = false,
        onHttp = false
      )
    }

    "ignore an hsts max age written as a string" in {
      readOk(format, mandatory ++ Json.obj("hsts" -> Json.obj("max_age" -> "60"))).hsts.maxAge mustBe 3600L
    }

    "use the default hsts config when hsts is not an object" in {
      readOk(format, mandatory ++ Json.obj("hsts" -> true)).hsts mustBe default.hsts
    }

    "use the default content security policy when the policy string is missing" in {
      readOk(format, mandatory ++ Json.obj("csp" -> Json.obj("mode" -> "ENABLED"))).csp mustBe default.csp
    }

    "use the default content security policy when the mode is missing" in {
      readOk(format, mandatory ++ Json.obj("csp" -> Json.obj("csp" -> "default-src 'self'"))).csp mustBe default.csp
    }

    "use the default content security policy when csp is a string" in {
      readOk(format, mandatory ++ Json.obj("csp" -> "default-src 'self'")).csp mustBe default.csp
    }

    "fill the missing permissions policy fields with their defaults" in {
      readOk(format, mandatory ++ Json.obj("permissions_policy" -> Json.obj("enabled" -> true))).permissionsPolicy mustBe
        PermissionsPolicyConf(enabled = true, policy = "")
    }

    "use the default permissions policy when it is not an object" in {
      readOk(format, mandatory ++ Json.obj("permissions_policy" -> "camera=()")).permissionsPolicy mustBe default.permissionsPolicy
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("frameOptions" -> "DENY", "xssProtection" -> "ON", "contentTypeOptions" -> true)) mustBe default
    }

    "ignore unknown keys" in {
      readOk(format, mandatory ++ Json.obj("foo" -> "bar")) mustBe default
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "list every written key in its config flow and schema" in {
      SecurityHeadersPluginConfig.configFlow.toSet mustBe writtenKeys(format, default)
      SecurityHeadersPluginConfig.configSchema.get.keys mustBe SecurityHeadersPluginConfig.configFlow.toSet
    }

    "describe in its nested schemas the keys the nested formats write" in {
      val schema = SecurityHeadersPluginConfig.configSchema.get
      def nested(key: String): (Set[String], Set[String]) =
        ((schema \ key \ "flow").as[Seq[String]].toSet, (schema \ key \ "schema").as[JsObject].keys.toSet)
      nested("hsts") mustBe ((writtenKeys(HstsConf.format, default.hsts), writtenKeys(HstsConf.format, default.hsts)))
      nested("csp") mustBe ((writtenKeys(CspConf.format, default.csp), writtenKeys(CspConf.format, default.csp)))
      nested("permissions_policy") mustBe ((
        writtenKeys(PermissionsPolicyConf.format, default.permissionsPolicy),
        writtenKeys(PermissionsPolicyConf.format, default.permissionsPolicy)
      ))
    }

    "offer in its schema the values the enums write" in {
      val schema                          = SecurityHeadersPluginConfig.configSchema.get
      def options(path: JsLookupResult)   = (path \ "props" \ "options").as[Seq[JsObject]].map(o => (o \ "value").as[String]).toSet
      options(schema \ "frame_options") mustBe Set("DENY", "SAMEORIGIN", "DISABLED")
      options(schema \ "xss_protection") mustBe Set("OFF", "ON", "BLOCK", "DISABLED")
      options(schema \ "csp" \ "schema" \ "mode") mustBe Set("ENABLED", "REPORT_ONLY", "DISABLED")
      options(schema \ "referrer_policy") mustBe Set(
        ReferrerPolicy.NO_REFERRER,
        ReferrerPolicy.NO_REFERRER_WHEN_DOWNGRADE,
        ReferrerPolicy.ORIGIN,
        ReferrerPolicy.ORIGIN_WHEN_CROSS_ORIGIN,
        ReferrerPolicy.SAME_ORIGIN,
        ReferrerPolicy.STRICT_ORIGIN,
        ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN,
        ReferrerPolicy.UNSAFE_URL,
        ReferrerPolicy.DISABLED
      ).map(_.json.as[String])
    }

    "be the default config of SecurityHeadersPlugin" in {
      defaultConfigReadsBack(new SecurityHeadersPlugin(), format) mustBe default
    }

    "be the config flow and schema of SecurityHeadersPlugin" in {
      val plugin = new SecurityHeadersPlugin()
      plugin.configFlow mustBe SecurityHeadersPluginConfig.configFlow
      plugin.configSchema mustBe SecurityHeadersPluginConfig.configSchema
    }
  }

  "HstsConf" should {

    val format = HstsConf.format

    "write every flag and the max age under snake case keys" in {
      format.writes(HstsConf(true, true, 60L, true, true)) mustBe Json.obj(
        "enabled"            -> true,
        "include_subdomains" -> true,
        "max_age"            -> 60,
        "preload"            -> true,
        "on_http"            -> true
      )
    }

    "round trip a config where every field is set" in {
      roundTrip(format, HstsConf(true, true, 63072000L, true, true))
    }

    "read an empty object as a disabled one hour policy" in {
      readOk(format, Json.obj()) mustBe HstsConf(false, false, 3600L, false, false)
    }

    "read a json null as a disabled one hour policy" in {
      readOk(format, JsNull) mustBe HstsConf(false, false, 3600L, false, false)
    }
  }

  "CspConf" should {

    val format = CspConf.format

    "round trip a report only policy" in {
      roundTrip(format, CspConf(CspMode.REPORT_ONLY, "script-src 'self'"))
    }

    "not read an empty object, the mode and the policy are mandatory" in {
      readError(format, Json.obj())
    }
  }

  "PermissionsPolicyConf" should {

    val format = PermissionsPolicyConf.format

    "round trip an enabled policy" in {
      roundTrip(format, PermissionsPolicyConf(enabled = true, policy = "camera=(), microphone=()"))
    }

    "read an empty object as a disabled empty policy" in {
      readOk(format, Json.obj()) mustBe PermissionsPolicyConf(enabled = false, policy = "")
    }
  }
}
