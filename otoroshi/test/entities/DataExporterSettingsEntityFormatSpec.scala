package entities

import otoroshi.events.{KafkaConfig, PulsarConfig, SaslConfig}
import otoroshi.models.*
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.mailer.*
import play.api.libs.json.*

class DataExporterSettingsEntityFormatSpec extends EntityFormatSpec {

  private val mtls = MtlsConfig(Seq("cert-1"), Seq("ca-1"), mtls = true, loose = true, trustAll = true)

  "ElasticAnalyticsConfig" should {

    val format = ElasticAnalyticsConfig.format

    val full = ElasticAnalyticsConfig(
      uris = Seq("http://es1:9200", "http://es2:9200"),
      index = Some("otoroshi-events"),
      `type` = Some("event"),
      user = Some("elastic"),
      password = Some("changeme"),
      headers = Map("X-Tenant" -> "prod"),
      indexSettings = IndexSettings(false, 3, 2, "create", IndexSettingsInterval.Year),
      mtlsConfig = mtls,
      applyTemplate = false,
      version = Some("8.0.0"),
      maxBulkSize = 50,
      sendWorkers = 2
    )

    "write a config with a single uri" in {
      format.writes(ElasticAnalyticsConfig(Seq("http://es:9200"))) mustBe Json.obj(
        "clusterUri"    -> "http://es:9200",
        "uris"          -> Json.arr("http://es:9200"),
        "index"         -> JsNull,
        "type"          -> JsNull,
        "user"          -> JsNull,
        "password"      -> JsNull,
        "headers"       -> Json.obj(),
        "indexSettings" -> Json.obj(
          "action"           -> "index",
          "clientSide"       -> true,
          "interval"         -> "Day",
          "numberOfShards"   -> 1,
          "numberOfReplicas" -> 1
        ),
        "mtlsConfig"    -> MtlsConfig.default.json,
        "applyTemplate" -> true,
        "version"       -> JsNull,
        "maxBulkSize"   -> 100,
        "sendWorkers"   -> 4
      )
    }

    "write the first uri as the cluster uri" in {
      (written(format, full) \ "clusterUri").as[String] mustBe "http://es1:9200"
      (written(format, full) \ "uris").as[Seq[String]] mustBe Seq("http://es1:9200", "http://es2:9200")
    }

    "expose through toJson what the format writes" in {
      full.toJson mustBe format.writes(full)
    }

    "round trip a config with a single uri" in {
      roundTrip(format, ElasticAnalyticsConfig(Seq("http://es:9200")))
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "read a config from its legacy cluster uri alone" in {
      readOk(format, Json.obj("clusterUri" -> " http://es:9200 ")) mustBe ElasticAnalyticsConfig(Seq("http://es:9200"))
    }

    "read the cluster uri before the uris and drop the duplicates" in {
      readOk(format, Json.obj("clusterUri" -> "http://a", "uris" -> Json.arr("http://b", "http://a"))).uris mustBe Seq(
        "http://a",
        "http://b"
      )
    }

    "split comma separated uris" in {
      readOk(format, Json.obj("clusterUri" -> "http://a, http://b", "uris" -> Json.arr("http://c,http://d"))).uris mustBe Seq(
        "http://a",
        "http://b",
        "http://c",
        "http://d"
      )
    }

    "not read a config without any uri" in {
      readError(format, Json.obj("index" -> "events"))
      readError(format, Json.obj("clusterUri" -> " ", "uris" -> Json.arr()))
    }

    "not read back a config written without uri" in {
      readError(format, format.writes(ElasticAnalyticsConfig(Seq.empty)))
    }

    "trim the index, type, user and password and ignore the blank ones" in {
      val back = readOk(
        format,
        Json.obj("uris" -> Json.arr("http://a"), "index" -> " events ", "type" -> " ", "user" -> " elastic", "password" -> " secret ")
      )
      back.index mustBe Some("events")
      back.`type` mustBe None
      back.user mustBe Some("elastic")
      back.password mustBe Some("secret")
    }

    "ignore a blank version without trimming a set one" in {
      readOk(format, Json.obj("uris" -> Json.arr("http://a"), "version" -> "  ")).version mustBe None
      readOk(format, Json.obj("uris" -> Json.arr("http://a"), "version" -> " 7.10 ")).version mustBe Some(" 7.10 ")
    }

    "read the defaults of the missing fields" in {
      readOk(format, Json.obj("uris" -> Json.arr("http://a"))) mustBe ElasticAnalyticsConfig(Seq("http://a"))
    }

    "fall back to the defaults for fields of the wrong type" in {
      val back = readOk(
        format,
        Json.obj("uris" -> Json.arr("http://a"), "applyTemplate" -> "no", "maxBulkSize" -> "10", "headers" -> Json.arr())
      )
      back.applyTemplate mustBe true
      back.maxBulkSize mustBe 100
      back.headers mustBe empty
    }

    "read the mtls config from its legacy keys" in {
      readOk(format, Json.obj("uris" -> Json.arr("http://a"), "mtlsConfig" -> Json.obj("certId" -> "cert-1", "tls" -> true))).mtlsConfig mustBe
        MtlsConfig(certs = Seq("cert-1"), mtls = true)
    }

    "be read as an option" in {
      ElasticAnalyticsConfig.read(full.toJson) mustBe Some(full)
      ElasticAnalyticsConfig.read(Json.obj()) mustBe None
    }
  }

  "IndexSettings" should {

    val format = IndexSettings.format

    "write the default settings" in {
      format.writes(IndexSettings()) mustBe Json.obj(
        "action"           -> "index",
        "clientSide"       -> true,
        "interval"         -> "Day",
        "numberOfShards"   -> 1,
        "numberOfReplicas" -> 1
      )
    }

    "round trip settings with each interval" in {
      roundTrip(format, IndexSettings(false, 2, 0, "create", IndexSettingsInterval.Day))
      roundTrip(format, IndexSettings(false, 2, 0, "create", IndexSettingsInterval.Week))
      roundTrip(format, IndexSettings(false, 2, 0, "create", IndexSettingsInterval.Month))
      roundTrip(format, IndexSettings(false, 2, 0, "create", IndexSettingsInterval.Year))
    }

    "read an empty object as the default settings" in {
      readOk(format, Json.obj()) mustBe IndexSettings()
    }

    "read an interval whatever its case and its surrounding spaces" in {
      readOk(format, Json.obj("interval" -> " MONTH ")).interval mustBe IndexSettingsInterval.Month
    }

    "read an unknown interval as a daily one" in {
      readOk(format, Json.obj("interval" -> "hour")).interval mustBe IndexSettingsInterval.Day
    }

    "be read from an optional json" in {
      IndexSettings.read(None) mustBe IndexSettings()
      IndexSettings.read(Some(Json.obj("numberOfShards" -> 5))) mustBe IndexSettings(numberOfShards = 5)
    }

    "parse the intervals" in {
      IndexSettingsInterval.parse("day") mustBe Some(IndexSettingsInterval.Day)
      IndexSettingsInterval.parse("Week") mustBe Some(IndexSettingsInterval.Week)
      IndexSettingsInterval.parse("year") mustBe Some(IndexSettingsInterval.Year)
      IndexSettingsInterval.parse("minute") mustBe None
    }

    "write an interval as its name" in {
      IndexSettingsInterval.Week.json mustBe JsString("Week")
    }
  }

  "Webhook" should {

    val format = Webhook.format

    "write a webhook" in {
      format.writes(Webhook("http://hook", Map("X-A" -> "b"), mtls)) mustBe Json.obj(
        "url"        -> "http://hook",
        "headers"    -> Json.obj("X-A" -> "b"),
        "mtlsConfig" -> mtls.json
      )
    }

    "expose through toJson what the format writes" in {
      Webhook("http://hook").toJson mustBe format.writes(Webhook("http://hook"))
    }

    "round trip a minimal webhook" in {
      roundTrip(format, Webhook("http://hook"))
    }

    "round trip a webhook where every field is set" in {
      roundTrip(format, Webhook("http://hook", Map("X-A" -> "b"), mtls))
    }

    "read a lone url with the default headers and mtls config" in {
      readOk(format, Json.obj("url" -> "http://hook")) mustBe Webhook("http://hook", Map.empty, MtlsConfig.default)
    }

    "not read a webhook without url" in {
      readError(format, Json.obj("headers" -> Json.obj()))
      readError(format, Json.obj("url" -> 42))
    }

    "ignore headers holding a non string value" in {
      readOk(format, Json.obj("url" -> "http://hook", "headers" -> Json.obj("a" -> 1))).headers mustBe empty
    }
  }

  "KafkaConfig" should {

    val format = KafkaConfig.format

    val full = KafkaConfig(
      servers = Seq("kafka1:9092", "kafka2:9092"),
      keyPass = Some("keypass"),
      keystore = Some("/keystore.jks"),
      truststore = Some("/truststore.jks"),
      sendEvents = true,
      hostValidation = false,
      topic = "events",
      mtlsConfig = mtls,
      securityProtocol = "SASL_SSL",
      saslConfig = Some(SaslConfig("user", "password", "SCRAM-SHA-512", Some("jaas"))),
      properties = Some(Map("acks" -> "all", "linger.ms" -> "5"))
    )

    "write a minimal config" in {
      format.writes(KafkaConfig(Seq("kafka:9092"))) mustBe Json.obj(
        "servers"          -> Json.arr("kafka:9092"),
        "keyPass"          -> JsNull,
        "keystore"         -> JsNull,
        "truststore"       -> JsNull,
        "topic"            -> "otoroshi-events",
        "sendEvents"       -> false,
        "hostValidation"   -> true,
        "mtlsConfig"       -> MtlsConfig().json,
        "securityProtocol" -> "PLAINTEXT",
        "saslConfig"       -> JsNull,
        "properties"       -> JsNull
      )
    }

    "write a config where every field is set" in {
      val json = written(format, full)
      (json \ "saslConfig").as[JsValue] mustBe Json.obj(
        "username"   -> "user",
        "password"   -> "password",
        "mechanism"  -> "SCRAM-SHA-512",
        "jaasConfig" -> "jaas"
      )
      (json \ "properties").as[JsValue] mustBe Json.obj("acks" -> "all", "linger.ms" -> "5")
      (json \ "keystore").as[String] mustBe "/keystore.jks"
    }

    "expose through json and toJson what the format writes" in {
      full.json mustBe format.writes(full)
      full.toJson mustBe format.writes(full)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a config with sasl settings without jaas config" in {
      roundTrip(format, full.copy(saslConfig = Some(SaslConfig("user", "password"))))
    }

    "read an empty object as a config without server" in {
      readOk(format, Json.obj()) mustBe KafkaConfig(Seq.empty)
    }

    "read empty sasl settings with the default credentials" in {
      readOk(format, Json.obj("saslConfig" -> Json.obj())).saslConfig mustBe Some(SaslConfig("foo", "bar", "PLAIN", None))
    }

    "round trip a config without sasl settings" in {
      roundTrip(format, full.copy(saslConfig = None)).saslConfig mustBe None
    }

    "read null sasl settings as no sasl settings" in {
      readOk(format, Json.obj("saslConfig" -> JsNull)).saslConfig mustBe None
    }

    "ignore properties holding a non string value" in {
      readOk(format, Json.obj("properties" -> Json.obj("acks" -> 1))).properties mustBe None
    }

    "fall back to the defaults for fields of the wrong type" in {
      val back = readOk(format, Json.obj("servers" -> "kafka:9092", "sendEvents" -> "yes", "topic" -> 42))
      back.servers mustBe empty
      back.sendEvents mustBe false
      back.topic mustBe "otoroshi-events"
    }
  }

  "SaslConfig" should {

    val format = SaslConfig.format

    "write its fields" in {
      format.writes(SaslConfig("user", "password")) mustBe Json.obj(
        "username"   -> "user",
        "password"   -> "password",
        "mechanism"  -> "PLAIN",
        "jaasConfig" -> JsNull
      )
    }

    "round trip settings with and without jaas config" in {
      roundTrip(format, SaslConfig("user", "password"))
      roundTrip(format, SaslConfig("user", "password", "SCRAM-SHA-256", Some("jaas")))
    }

    "read an empty object with the default credentials and mechanism" in {
      readOk(format, Json.obj()) mustBe SaslConfig("foo", "bar", "PLAIN", None)
    }
  }

  "PulsarConfig" should {

    val format = PulsarConfig.format

    val minimal = PulsarConfig("pulsar://pulsar:6650", None, "public", "default", "events", None, None, None)

    "write a minimal config" in {
      format.writes(minimal) mustBe Json.obj(
        "uri"                   -> "pulsar://pulsar:6650",
        "tlsTrustCertsFilePath" -> JsNull,
        "tenant"                -> "public",
        "namespace"             -> "default",
        "topic"                 -> "events",
        "token"                 -> JsNull,
        "username"              -> JsNull,
        "password"              -> JsNull,
        "mtlsConfig"            -> MtlsConfig().json
      )
    }

    "round trip a minimal config" in {
      roundTrip(format, minimal)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, PulsarConfig("pulsar+ssl://pulsar:6651", Some("/ca.pem"), "t", "ns", "events", Some("token"), Some("user"), Some("password"), mtls))
    }

    "not read a config missing one of its required fields" in {
      val json = written(format, minimal)
      readError(format, json - "uri")
      readError(format, json - "tenant")
      readError(format, json - "namespace")
      readError(format, json - "topic")
    }

    "ignore a blank token, user name or password" in {
      val back = readOk(format, written(format, minimal) ++ Json.obj("token" -> " ", "username" -> "", "password" -> " "))
      back.token mustBe None
      back.username mustBe None
      back.password mustBe None
    }
  }

  "MailerSettings" should {

    val format = MailerSettings.format

    val to = Seq(EmailLocation("Ops", "ops@oto.tools"), EmailLocation("dev@oto.tools", "dev@oto.tools"))

    "write each kind of mailer" in {
      format.writes(NoneMailerSettings()) mustBe Json.obj("type" -> "none")
      format.writes(ConsoleMailerSettings()) mustBe Json.obj("type" -> "console")
      format.writes(GenericMailerSettings("http://mailer", Map("X-A" -> "b"), to.take(1))) mustBe Json.obj(
        "type"    -> "generic",
        "url"     -> "http://mailer",
        "headers" -> Json.obj("X-A" -> "b"),
        "to"      -> Json.arr(Json.obj("name" -> "Ops", "email" -> "ops@oto.tools"))
      )
      format.writes(MailgunSettings(eu = true, "key", "mg.oto.tools", Seq.empty)) mustBe Json.obj(
        "type"   -> "mailgun",
        "eu"     -> true,
        "apiKey" -> "key",
        "domain" -> "mg.oto.tools",
        "to"     -> Json.arr()
      )
      format.writes(MailjetSettings("public", "private", Seq.empty)) mustBe Json.obj(
        "type"          -> "mailjet",
        "apiKeyPublic"  -> "public",
        "apiKeyPrivate" -> "private",
        "to"            -> Json.arr()
      )
      format.writes(SendgridSettings("key", Seq.empty)) mustBe Json.obj("type" -> "sendgrid", "apiKey" -> "key", "to" -> Json.arr())
      format.writes(ScalewayTEMSettings("secret", "project", "nl-ams", Seq.empty)) mustBe Json.obj(
        "type"      -> "scaleway",
        "secretKey" -> "secret",
        "projectId" -> "project",
        "region"    -> "nl-ams",
        "to"        -> Json.arr()
      )
      format.writes(MailPaceSettings("token", Seq.empty)) mustBe Json.obj("type" -> "mailpace", "serverToken" -> "token", "to" -> Json.arr())
    }

    "round trip each kind of mailer" in {
      roundTrip(format, NoneMailerSettings())
      roundTrip(format, ConsoleMailerSettings())
      roundTrip(format, GenericMailerSettings("http://mailer", Map("X-A" -> "b"), to))
      roundTrip(format, MailgunSettings(eu = true, "key", "mg.oto.tools", to))
      roundTrip(format, MailgunSettings(eu = false, "key", "mg.oto.tools", Seq.empty))
      roundTrip(format, MailjetSettings("public", "private", to))
      roundTrip(format, SendgridSettings("key", to))
      roundTrip(format, ScalewayTEMSettings("secret", "project", "fr-par", to))
      roundTrip(format, MailPaceSettings("token", to))
    }

    "expose through json and toJson what the format writes" in {
      val mailer = MailgunSettings(eu = true, "key", "mg.oto.tools", to)
      mailer.json mustBe format.writes(mailer)
      mailer.toJson mustBe format.writes(mailer)
    }

    "read a mailer without type as no mailer" in {
      readOk(format, Json.obj()) mustBe NoneMailerSettings()
    }

    "read a mailer of an unknown type as a console mailer" in {
      readOk(format, Json.obj("type" -> "smtp", "url" -> "smtp://mail")) mustBe ConsoleMailerSettings()
    }

    "not read a mailer missing its credentials" in {
      readError(format, Json.obj("type" -> "mailgun", "apiKey" -> "key"))
      readError(format, Json.obj("type" -> "mailgun", "domain" -> "mg.oto.tools"))
      readError(format, Json.obj("type" -> "mailjet", "apiKeyPublic" -> "public"))
      readError(format, Json.obj("type" -> "sendgrid"))
      readError(format, Json.obj("type" -> "scaleway", "secretKey" -> "secret"))
      readError(format, Json.obj("type" -> "mailpace"))
      readError(format, Json.obj("type" -> "generic", "headers" -> Json.obj()))
    }

    "read the defaults of the optional fields" in {
      readOk(format, Json.obj("type" -> "mailgun", "apiKey" -> "key", "domain" -> "d")) mustBe MailgunSettings(eu = false, "key", "d", Seq.empty)
      readOk(format, Json.obj("type" -> "scaleway", "secretKey" -> "s", "projectId" -> "p")) mustBe ScalewayTEMSettings("s", "p", "fr-par", Seq.empty)
      readOk(format, Json.obj("type" -> "generic", "url" -> "http://mailer")) mustBe GenericMailerSettings("http://mailer", Map.empty, Seq.empty)
    }

    "trim the credentials" in {
      readOk(format, Json.obj("type" -> "sendgrid", "apiKey" -> " key ")) mustBe SendgridSettings("key", Seq.empty)
      readOk(format, Json.obj("type" -> "mailjet", "apiKeyPublic" -> " pub", "apiKeyPrivate" -> "priv ")) mustBe
        MailjetSettings("pub", "priv", Seq.empty)
    }

    "read recipients given as plain email addresses" in {
      readOk(format, Json.obj("type" -> "mailpace", "serverToken" -> "t", "to" -> Json.arr("ops@oto.tools"))).to mustBe Seq(
        EmailLocation("ops@oto.tools", "ops@oto.tools")
      )
    }

    "skip the recipients that cannot be read" in {
      readOk(
        format,
        Json.obj("type" -> "sendgrid", "apiKey" -> "k", "to" -> Json.arr(42, Json.obj("name" -> "n"), Json.obj("name" -> "Ops", "email" -> "ops@oto.tools")))
      ).to mustBe Seq(EmailLocation("Ops", "ops@oto.tools"))
    }

    "have no recipient for the none and console mailers" in {
      NoneMailerSettings().to mustBe empty
      ConsoleMailerSettings().to mustBe empty
    }

    "name the type of each kind of mailer" in {
      NoneMailerSettings().typ mustBe "none"
      ConsoleMailerSettings().typ mustBe "console"
      GenericMailerSettings("u", Map.empty, Seq.empty).typ mustBe "generic"
      MailgunSettings(eu = false, "k", "d", Seq.empty).typ mustBe "mailgun"
      MailjetSettings("p", "s", Seq.empty).typ mustBe "mailjet"
      SendgridSettings("k", Seq.empty).typ mustBe "sendgrid"
      ScalewayTEMSettings("s", "p", "r", Seq.empty).typ mustBe "scaleway"
      MailPaceSettings("t", Seq.empty).typ mustBe "mailpace"
    }

    "give access to the settings of its own kind only" in {
      val mailgun = MailgunSettings(eu = false, "k", "d", Seq.empty)
      mailgun.mailgunSettings mustBe Some(mailgun)
      mailgun.genericSettings mustBe None
      mailgun.consoleSettings mustBe None
      mailgun.mailjetSettings mustBe None
      mailgun.sendgridSettings mustBe None
      mailgun.scalewayTEMSettings mustBe None
      mailgun.mailPaceSettings mustBe None
      val generic = GenericMailerSettings("u", Map.empty, Seq.empty)
      generic.genericSettings mustBe Some(generic)
      ConsoleMailerSettings().consoleSettings mustBe Some(ConsoleMailerSettings())
      MailPaceSettings("t", Seq.empty).mailPaceSettings mustBe Some(MailPaceSettings("t", Seq.empty))
      ScalewayTEMSettings("s", "p", "r", Seq.empty).scalewayTEMSettings mustBe Some(ScalewayTEMSettings("s", "p", "r", Seq.empty))
    }
  }

  "EmailLocation" should {

    val format = EmailLocation.format

    "write a name and an email" in {
      format.writes(EmailLocation("Ops", "ops@oto.tools")) mustBe Json.obj("name" -> "Ops", "email" -> "ops@oto.tools")
    }

    "round trip a location" in {
      roundTrip(format, EmailLocation("Ops", "ops@oto.tools"))
    }

    "read a plain string as both the name and the email" in {
      readOk(format, JsString("ops@oto.tools")) mustBe EmailLocation("ops@oto.tools", "ops@oto.tools")
    }

    "not read an object missing the name or the email" in {
      readError(format, Json.obj("email" -> "ops@oto.tools"))
      readError(format, Json.obj("name" -> "Ops"))
    }

    "not read a value that is neither a string nor an object" in {
      readError(format, JsNumber(1))
      readError(format, JsNull)
    }

    "be written as an email string" in {
      EmailLocation("Ops", "ops@oto.tools").toEmailString mustBe "Ops <ops@oto.tools>"
    }
  }
}
