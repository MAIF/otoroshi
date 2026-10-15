package plugins.configs

import otoroshi.models.RemainingQuotas
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.*
import play.api.libs.json.*

class ClientCertPluginConfigsSpec extends PluginConfigFormatSpec {

  "NgHasClientCertMatchingValidatorConfig" should {

    val format = NgHasClientCertMatchingValidatorConfig.format

    val full = NgHasClientCertMatchingValidatorConfig(
      mandatory = false,
      serialNumbers = Seq("1a2b", "ff"),
      subjectDNs = Seq("CN=client.oto.tools, O=Oto"),
      issuerDNs = Seq("CN=Otoroshi Intermediate CA"),
      regexSubjectDNs = Seq("CN=.*\\.oto\\.tools"),
      regexIssuerDNs = Seq("CN=Otoroshi .* CA")
    )

    "be mandatory and match nothing by default" in {
      val config = NgHasClientCertMatchingValidatorConfig()
      config.mandatory mustBe true
      config.serialNumbers mustBe empty
      config.subjectDNs mustBe empty
      config.issuerDNs mustBe empty
      config.regexSubjectDNs mustBe empty
      config.regexIssuerDNs mustBe empty
    }

    "write the default config with snake case keys and empty lists" in {
      format.writes(NgHasClientCertMatchingValidatorConfig()) mustBe Json.obj(
        "mandatory"         -> true,
        "serial_numbers"    -> Json.arr(),
        "subject_dns"       -> Json.arr(),
        "issuer_dns"        -> Json.arr(),
        "regex_subject_dns" -> Json.arr(),
        "regex_issuer_dns"  -> Json.arr()
      )
    }

    "write every field of a full config" in {
      format.writes(full) mustBe Json.obj(
        "mandatory"         -> false,
        "serial_numbers"    -> Json.arr("1a2b", "ff"),
        "subject_dns"       -> Json.arr("CN=client.oto.tools, O=Oto"),
        "issuer_dns"        -> Json.arr("CN=Otoroshi Intermediate CA"),
        "regex_subject_dns" -> Json.arr("CN=.*\\.oto\\.tools"),
        "regex_issuer_dns"  -> Json.arr("CN=Otoroshi .* CA")
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgHasClientCertMatchingValidatorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a non mandatory config without any rule" in {
      roundTrip(format, NgHasClientCertMatchingValidatorConfig(mandatory = false)).mandatory mustBe false
    }

    "round trip several serial numbers and keep their order" in {
      roundTrip(
        format,
        NgHasClientCertMatchingValidatorConfig(serialNumbers = Seq("03", "01", "02"))
      ).serialNumbers mustBe Seq("03", "01", "02")
    }

    "round trip distinguished names holding commas, quotes and unicode" in {
      roundTrip(
        format,
        NgHasClientCertMatchingValidatorConfig(
          subjectDNs = Seq("CN=Jérôme, OU=\"R&D\", O=Oto, C=FR"),
          issuerDNs = Seq("CN=CA, O=Oto\\, Inc")
        )
      )
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgHasClientCertMatchingValidatorConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgHasClientCertMatchingValidatorConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("CN=foo")) mustBe NgHasClientCertMatchingValidatorConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("CN=foo")) mustBe NgHasClientCertMatchingValidatorConfig()
    }

    "read mandatory alone and default the lists" in {
      readOk(format, Json.obj("mandatory" -> false)) mustBe NgHasClientCertMatchingValidatorConfig(mandatory = false)
    }

    "read serial_numbers alone and stay mandatory" in {
      readOk(format, Json.obj("serial_numbers" -> Json.arr("ff"))) mustBe NgHasClientCertMatchingValidatorConfig(
        serialNumbers = Seq("ff")
      )
    }

    "read regex_issuer_dns alone" in {
      readOk(format, Json.obj("regex_issuer_dns" -> Json.arr("CN=.*"))) mustBe NgHasClientCertMatchingValidatorConfig(
        regexIssuerDNs = Seq("CN=.*")
      )
    }

    "keep the exact and the regex subject lists apart" in {
      val back = readOk(
        format,
        Json.obj("subject_dns" -> Json.arr("CN=exact"), "regex_subject_dns" -> Json.arr("CN=re.*"))
      )
      back.subjectDNs mustBe Seq("CN=exact")
      back.regexSubjectDNs mustBe Seq("CN=re.*")
    }

    "stay mandatory when mandatory is written as a string" in {
      readOk(format, Json.obj("mandatory" -> "false")).mandatory mustBe true
    }

    "stay mandatory when mandatory is null" in {
      readOk(format, Json.obj("mandatory" -> JsNull)).mandatory mustBe true
    }

    "ignore a serial_numbers that is a string" in {
      readOk(format, Json.obj("serial_numbers" -> "ff")).serialNumbers mustBe empty
    }

    "ignore a list holding something else than strings" in {
      readOk(format, Json.obj("issuer_dns" -> Json.arr("CN=ca", 42))).issuerDNs mustBe empty
    }

    "not read the camel case keys of the http validator payload" in {
      val json = Json.obj(
        "serialNumbers"   -> Json.arr("ff"),
        "subjectDNs"      -> Json.arr("CN=foo"),
        "issuerDNs"       -> Json.arr("CN=ca"),
        "regexSubjectDNs" -> Json.arr("CN=.*"),
        "regexIssuerDNs"  -> Json.arr("CN=.*")
      )
      readOk(format, json) mustBe NgHasClientCertMatchingValidatorConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("mandatory" -> false, "foo" -> "bar")) mustBe NgHasClientCertMatchingValidatorConfig(
        mandatory = false
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgHasClientCertMatchingValidator" in {
      defaultConfigReadsBack(
        new NgHasClientCertMatchingValidator(),
        format
      ) mustBe NgHasClientCertMatchingValidatorConfig()
    }
  }

  "NgClientCertChainHeaderConfig" should {

    val format = NgClientCertChainHeaderConfig.format

    val full = NgClientCertChainHeaderConfig(
      sendPem = true,
      pemHeaderName = "X-Pem",
      sendDns = true,
      dnsHeaderName = "X-Dns",
      sendChain = true,
      chainHeaderName = "X-Chain"
    )

    "send nothing by default" in {
      val config = NgClientCertChainHeaderConfig()
      config.sendPem mustBe false
      config.sendDns mustBe false
      config.sendChain mustBe false
    }

    "write the default config with the default header names" in {
      format.writes(NgClientCertChainHeaderConfig()) mustBe Json.obj(
        "send_pem"          -> false,
        "pem_header_name"   -> "X-Client-Cert-Pem",
        "send_dns"          -> false,
        "dns_header_name"   -> "X-Client-Cert-DNs",
        "send_chain"        -> false,
        "chain_header_name" -> "X-Client-Cert-Chain"
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgClientCertChainHeaderConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config that only sends the pem" in {
      roundTrip(format, NgClientCertChainHeaderConfig(sendPem = true))
    }

    "keep custom header names even when nothing is sent" in {
      val back = roundTrip(
        format,
        NgClientCertChainHeaderConfig(pemHeaderName = "X-P", dnsHeaderName = "X-D", chainHeaderName = "X-C")
      )
      back.pemHeaderName mustBe "X-P"
      back.dnsHeaderName mustBe "X-D"
      back.chainHeaderName mustBe "X-C"
    }

    "round trip an empty header name" in {
      roundTrip(format, NgClientCertChainHeaderConfig(sendDns = true, dnsHeaderName = "")).dnsHeaderName mustBe ""
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgClientCertChainHeaderConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgClientCertChainHeaderConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("X-Client-Cert-Pem")) mustBe NgClientCertChainHeaderConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(true)) mustBe NgClientCertChainHeaderConfig()
    }

    "read send_dns alone and default the rest" in {
      readOk(format, Json.obj("send_dns" -> true)) mustBe NgClientCertChainHeaderConfig(sendDns = true)
    }

    "read chain_header_name alone and default the rest" in {
      readOk(format, Json.obj("chain_header_name" -> "X-My-Chain")) mustBe NgClientCertChainHeaderConfig(
        chainHeaderName = "X-My-Chain"
      )
    }

    "ignore a flag written as a string" in {
      readOk(format, Json.obj("send_pem" -> "true")).sendPem mustBe false
    }

    "ignore a header name that is not a string" in {
      readOk(format, Json.obj("pem_header_name" -> 42)).pemHeaderName mustBe "X-Client-Cert-Pem"
    }

    "fall back to the default header name when it is null" in {
      readOk(format, Json.obj("dns_header_name" -> JsNull)).dnsHeaderName mustBe "X-Client-Cert-DNs"
    }

    "not read the camel case keys" in {
      readOk(format, Json.obj("sendPem" -> true, "pemHeaderName" -> "X-P")) mustBe NgClientCertChainHeaderConfig()
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("send_chain" -> true, "foo" -> "bar")) mustBe NgClientCertChainHeaderConfig(
        sendChain = true
      )
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgClientCertChainHeader" in {
      defaultConfigReadsBack(new NgClientCertChainHeader(), format) mustBe NgClientCertChainHeaderConfig()
    }
  }

  "NgCertificateAsApikeyConfig" should {

    val format = NgCertificateAsApikeyConfig.format

    val full = NgCertificateAsApikeyConfig(
      readOnly = true,
      allowClientIdOnly = true,
      throttlingQuota = 42,
      dailyQuota = 1000,
      monthlyQuota = 30000,
      constrainedServicesOnly = true,
      tags = Seq("mtls", "generated"),
      metadata = Map("team" -> "infra", "env" -> "prod")
    )

    "use the apikey quotas by default" in {
      val config = NgCertificateAsApikeyConfig()
      config.throttlingQuota mustBe 100L
      config.dailyQuota mustBe RemainingQuotas.MaxValue
      config.monthlyQuota mustBe RemainingQuotas.MaxValue
    }

    "write the default config with snake case keys" in {
      format.writes(NgCertificateAsApikeyConfig()) mustBe Json.obj(
        "read_only"                 -> false,
        "allow_client_id_only"      -> false,
        "throttling_quota"          -> 100,
        "daily_quota"               -> 10000000L,
        "monthly_quota"             -> 10000000L,
        "constrained_services_only" -> false,
        "tags"                      -> Json.arr(),
        "metadata"                  -> Json.obj()
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgCertificateAsApikeyConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip zero quotas" in {
      roundTrip(format, NgCertificateAsApikeyConfig(throttlingQuota = 0, dailyQuota = 0, monthlyQuota = 0))
    }

    "round trip the largest quotas" in {
      roundTrip(
        format,
        NgCertificateAsApikeyConfig(throttlingQuota = Long.MaxValue, dailyQuota = Long.MaxValue, monthlyQuota = Long.MaxValue)
      )
    }

    "round trip a negative quota as is" in {
      roundTrip(format, NgCertificateAsApikeyConfig(throttlingQuota = -1)).throttlingQuota mustBe -1L
    }

    "round trip tags and keep their order" in {
      roundTrip(format, NgCertificateAsApikeyConfig(tags = Seq("c", "a", "b"))).tags mustBe Seq("c", "a", "b")
    }

    "round trip metadata holding empty and expression language values" in {
      roundTrip(format, NgCertificateAsApikeyConfig(metadata = Map("empty" -> "", "dn" -> "${req.client_cert_dn}")))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgCertificateAsApikeyConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgCertificateAsApikeyConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("apikey")) mustBe NgCertificateAsApikeyConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr()) mustBe NgCertificateAsApikeyConfig()
    }

    "read read_only alone and default the rest" in {
      readOk(format, Json.obj("read_only" -> true)) mustBe NgCertificateAsApikeyConfig(readOnly = true)
    }

    "read daily_quota alone and keep the other quotas at their default" in {
      readOk(format, Json.obj("daily_quota" -> 50)) mustBe NgCertificateAsApikeyConfig(dailyQuota = 50)
    }

    "ignore a quota written as a string" in {
      readOk(format, Json.obj("throttling_quota" -> "42")).throttlingQuota mustBe 100L
    }

    "ignore a quota that is not a whole number" in {
      readOk(format, Json.obj("monthly_quota" -> 10.5)).monthlyQuota mustBe RemainingQuotas.MaxValue
    }

    "ignore a flag written as a string" in {
      readOk(format, Json.obj("constrained_services_only" -> "true")).constrainedServicesOnly mustBe false
    }

    "ignore tags holding something else than strings" in {
      readOk(format, Json.obj("tags" -> Json.arr("a", 1))).tags mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, Json.obj("metadata" -> Json.obj("a" -> "b", "count" -> 1))).metadata mustBe empty
    }

    "ignore metadata written as an array" in {
      readOk(format, Json.obj("metadata" -> Json.arr("a"))).metadata mustBe empty
    }

    "not read the camel case keys of the apikey entity" in {
      val json = Json.obj("readOnly" -> true, "throttlingQuota" -> 1, "allowClientIdOnly" -> true)
      readOk(format, json) mustBe NgCertificateAsApikeyConfig()
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgCertificateAsApikey" in {
      defaultConfigReadsBack(new NgCertificateAsApikey(), format) mustBe NgCertificateAsApikeyConfig()
    }
  }

  "NgHasClientCertMatchingHttpValidatorConfig" should {

    val format = NgHasClientCertMatchingHttpValidatorConfig.format

    val fullTls = NgTlsConfig(
      certs = Seq("client-cert"),
      trustedCerts = Seq("ca-cert"),
      enabled = true,
      loose = true,
      trustAll = true
    )

    val full = NgHasClientCertMatchingHttpValidatorConfig(
      url = "https://certs.oto.tools/allowed",
      method = "POST",
      timeout = 500,
      headers = Map("Authorization" -> "Bearer xxx", "Accept" -> "application/json"),
      tls = fullTls
    )

    "write the default config with a nested tls config" in {
      format.writes(NgHasClientCertMatchingHttpValidatorConfig()) mustBe Json.obj(
        "url"     -> "https://validate.foo.bar",
        "method"  -> "GET",
        "timeout" -> 2000,
        "headers" -> Json.obj(),
        "tls"     -> Json.obj(
          "certs"         -> Json.arr(),
          "trusted_certs" -> Json.arr(),
          "enabled"       -> false,
          "loose"         -> false,
          "trust_all"     -> false
        )
      )
    }

    "round trip the default config" in {
      roundTrip(format, NgHasClientCertMatchingHttpValidatorConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config with headers and the default tls config" in {
      roundTrip(format, NgHasClientCertMatchingHttpValidatorConfig(headers = Map("X-Api-Key" -> "secret")))
    }

    "keep the case of the method as written" in {
      roundTrip(format, NgHasClientCertMatchingHttpValidatorConfig(method = "post")).method mustBe "post"
    }

    "fail to read an empty object because the url is mandatory" in {
      readError(format, Json.obj())
    }

    "fail to read a json null" in {
      readError(format, JsNull)
    }

    "fail to read a json string" in {
      readError(format, JsString("https://validate.foo.bar"))
    }

    "fail to read a json array" in {
      readError(format, Json.arr("https://validate.foo.bar"))
    }

    "fail to read a url that is not a string" in {
      readError(format, Json.obj("url" -> 42))
    }

    "read the url alone and default the rest" in {
      readOk(format, Json.obj("url" -> "https://other.oto.tools")) mustBe NgHasClientCertMatchingHttpValidatorConfig(
        url = "https://other.oto.tools"
      )
    }

    "ignore a method that is not a string" in {
      readOk(format, Json.obj("url" -> "https://a.oto.tools", "method" -> 1)).method mustBe "GET"
    }

    "ignore a timeout written as a string" in {
      readOk(format, Json.obj("url" -> "https://a.oto.tools", "timeout" -> "500")).timeout mustBe 2000L
    }

    "ignore headers holding a non string value" in {
      readOk(
        format,
        Json.obj("url" -> "https://a.oto.tools", "headers" -> Json.obj("X-Count" -> 1))
      ).headers mustBe empty
    }

    "read a tls config that is not an object as the default tls config" in {
      readOk(format, Json.obj("url" -> "https://a.oto.tools", "tls" -> "enabled")).tls mustBe NgTlsConfig.default
    }

    "read a partial tls config and default its other fields" in {
      readOk(
        format,
        Json.obj("url" -> "https://a.oto.tools", "tls" -> Json.obj("enabled" -> true))
      ).tls mustBe NgTlsConfig(enabled = true)
    }

    "read the legacy cert_id key of the tls config and write it back as certs" in {
      val back = readOk(format, Json.obj("url" -> "https://a.oto.tools", "tls" -> Json.obj("cert_id" -> "my-cert")))
      back.tls.certs mustBe Seq("my-cert")
      (format.writes(back) \ "tls" \ "certs").as[Seq[String]] mustBe Seq("my-cert")
      (format.writes(back) \ "tls" \ "cert_id").toOption mustBe None
    }

    "drop the blank certificate ids of the tls config" in {
      val config = NgHasClientCertMatchingHttpValidatorConfig(tls = NgTlsConfig(certs = Seq(" ", "my-cert")))
      readOk(format, format.writes(config)).tls.certs mustBe Seq("my-cert")
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of NgHasClientCertMatchingHttpValidator" in {
      defaultConfigReadsBack(
        new NgHasClientCertMatchingHttpValidator(),
        format
      ) mustBe NgHasClientCertMatchingHttpValidatorConfig()
    }
  }

  "NgRfc9440ClientCertHeaderConfig" should {

    val format = NgRfc9440ClientCertHeaderConfig.format

    "send the chain by default" in {
      NgRfc9440ClientCertHeaderConfig().sendChain mustBe true
    }

    "write the default config as send_chain true" in {
      format.writes(NgRfc9440ClientCertHeaderConfig()) mustBe Json.obj("send_chain" -> true)
    }

    "write a disabled chain as send_chain false" in {
      format.writes(NgRfc9440ClientCertHeaderConfig(sendChain = false)) mustBe Json.obj("send_chain" -> false)
    }

    "round trip the default config" in {
      roundTrip(format, NgRfc9440ClientCertHeaderConfig())
    }

    "round trip a disabled chain" in {
      roundTrip(format, NgRfc9440ClientCertHeaderConfig(sendChain = false)).sendChain mustBe false
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe NgRfc9440ClientCertHeaderConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe NgRfc9440ClientCertHeaderConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("false")) mustBe NgRfc9440ClientCertHeaderConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr(false)) mustBe NgRfc9440ClientCertHeaderConfig()
    }

    "keep sending the chain when send_chain is written as a string" in {
      readOk(format, Json.obj("send_chain" -> "false")).sendChain mustBe true
    }

    "keep sending the chain when send_chain is null" in {
      readOk(format, Json.obj("send_chain" -> JsNull)).sendChain mustBe true
    }

    "not read the camel case key" in {
      readOk(format, Json.obj("sendChain" -> false)).sendChain mustBe true
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, NgRfc9440ClientCertHeaderConfig(sendChain = false))
    }

    "list send_chain in the config flow and schema of its plugin" in {
      val plugin = new NgRfc9440ClientCertHeader()
      plugin.configFlow mustBe Seq("send_chain")
      plugin.configSchema.get.keys must contain("send_chain")
    }

    "be the default config of NgRfc9440ClientCertHeader" in {
      defaultConfigReadsBack(new NgRfc9440ClientCertHeader(), format) mustBe NgRfc9440ClientCertHeaderConfig()
    }
  }

  "the client certificate plugins without configuration" should {

    "have no default config for NgHasClientCertValidator" in {
      hasNoDefaultConfig(new NgHasClientCertValidator())
    }

    "have no default config for NgHasClientCertMatchingApikeyValidator" in {
      hasNoDefaultConfig(new NgHasClientCertMatchingApikeyValidator())
    }
  }
}
