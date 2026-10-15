package entities

import org.apache.pekko.stream.TLSClientAuth
import otoroshi.models.EntityLocation
import otoroshi.ssl.ClientAuth
import otoroshi.tcp.*
import play.api.libs.json.*

class TcpServiceEntityFormatSpec extends EntityFormatSpec {

  val defaultForwardTarget: TcpTarget = TcpTarget("127.0.0.1", None, 8080, false)

  val defaultForwardTargetJson: JsObject = Json.obj("host" -> "127.0.0.1", "ip" -> JsNull, "port" -> 8080, "tls" -> false)

  val defaultSniJson: JsObject = Json.obj(
    "enabled"          -> false,
    "forwardIfNoMatch" -> false,
    "forwardsTo"       -> defaultForwardTargetJson
  )

  val target1: TcpTarget = TcpTarget("db-1.internal", Some("10.0.0.1"), 5432, true)
  val target2: TcpTarget = TcpTarget("db-2.internal", None, 5433, false)

  val fullSni: SniSettings = SniSettings(
    enabled = true,
    forwardIfNoMatch = true,
    forwardsTo = TcpTarget("fallback.internal", Some("10.0.0.9"), 9443, true)
  )

  val minimal: TcpService = TcpService(
    id = "tcp-1",
    name = "TCP 1",
    description = "",
    enabled = false,
    tls = TlsMode.Disabled,
    sni = SniSettings(false, false),
    clientAuth = ClientAuth.None,
    port = 4200,
    rules = Seq.empty,
    tags = Seq.empty,
    metadata = Map.empty
  )

  val full: TcpService = TcpService(
    id = "tcp-2",
    name = "TCP 2",
    description = "the second tcp service",
    enabled = true,
    tls = TlsMode.PassThrough,
    sni = fullSni,
    clientAuth = ClientAuth.Need,
    port = 5432,
    interface = "127.0.0.1",
    rules = Seq(TcpRule("*.db.internal", Seq(target1, target2)), TcpRule("*", Seq(target2))),
    tags = Seq("db", "prod"),
    metadata = Map("owner" -> "dba", "cost-center" -> "42"),
    location = customLocation
  )

  "TcpTarget" should {

    val format = TcpTarget.fmt

    "write a target without ip as a json null ip" in {
      format.writes(target2) mustBe Json.obj("host" -> "db-2.internal", "ip" -> JsNull, "port" -> 5433, "tls" -> false)
    }

    "write a target with an ip" in {
      format.writes(target1) mustBe Json.obj("host" -> "db-1.internal", "ip" -> "10.0.0.1", "port" -> 5432, "tls" -> true)
    }

    "round trip a target with an ip over tls" in {
      roundTrip(format, target1)
    }

    "round trip a target without ip" in {
      roundTrip(format, target2)
    }

    "read a lone host on port 8080 without tls" in {
      readOk(format, Json.obj("host" -> "db.internal")) mustBe TcpTarget("db.internal", None, 8080, false)
    }

    "not read a target without host" in {
      readError(format, Json.obj("port" -> 5432))
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a host that is not a string" in {
      readError(format, Json.obj("host" -> 42))
    }

    "ignore a port and a tls flag that have a wrong type" in {
      readOk(format, Json.obj("host" -> "h", "port" -> "5432", "tls" -> "true")) mustBe TcpTarget("h", None, 8080, false)
    }

    "ignore an ip that is not a string" in {
      readOk(format, Json.obj("host" -> "h", "ip" -> 10)).ip mustBe None
    }

    "keep a blank ip" in {
      roundTrip(format, TcpTarget("h", Some(""), 80, false)).ip mustBe Some("")
    }

    "describe itself for the analytics with and without ip" in {
      target1.toAnalyticsString mustBe "db-1.internal/10.0.0.1:5432"
      target2.toAnalyticsString mustBe "db-2.internal:5433"
    }

    "expose through json what the format writes" in {
      target1.json mustBe format.writes(target1)
    }
  }

  "TcpRule" should {

    val format = TcpRule.fmt

    val rule = TcpRule("*.db.internal", Seq(target1, target2))

    "write its domain and its targets" in {
      format.writes(rule) mustBe Json.obj("domain" -> "*.db.internal", "targets" -> Json.arr(target1.json, target2.json))
    }

    "round trip a rule with several targets" in {
      roundTrip(format, rule)
    }

    "round trip a rule without target" in {
      roundTrip(format, TcpRule("db.internal", Seq.empty))
    }

    "keep the order of the targets" in {
      roundTrip(format, TcpRule("*", Seq(target2, target1))).targets mustBe Seq(target2, target1)
    }

    "read an empty object as a wildcard rule without target" in {
      readOk(format, Json.obj()) mustBe TcpRule("*", Seq.empty)
    }

    "read a json null as a wildcard rule without target" in {
      readOk(format, JsNull) mustBe TcpRule("*", Seq.empty)
    }

    "read a domain that is not a string as the wildcard domain" in {
      readOk(format, Json.obj("domain" -> 42)).domain mustBe "*"
    }

    "drop every target when one of them cannot be read" in {
      readOk(format, Json.obj("domain" -> "d", "targets" -> Json.arr(target1.json, Json.obj("port" -> 1)))) mustBe TcpRule(
        "d",
        Seq.empty
      )
    }

    "ignore targets that are not an array" in {
      readOk(format, Json.obj("domain" -> "d", "targets" -> target1.json)).targets mustBe empty
    }

    "expose through json what the format writes" in {
      rule.json mustBe format.writes(rule)
    }
  }

  "SniSettings" should {

    val format = SniSettings.fmt

    "forward to 127.0.0.1 on port 8080 by default" in {
      SniSettings(false, false).forwardsTo mustBe defaultForwardTarget
    }

    "write the default settings" in {
      format.writes(SniSettings(false, false)) mustBe defaultSniJson
    }

    "round trip settings where every field is set" in {
      roundTrip(format, fullSni)
    }

    "round trip the default settings" in {
      roundTrip(format, SniSettings(false, false))
    }

    "read an empty object as disabled settings forwarding to the default target" in {
      readOk(format, Json.obj()) mustBe SniSettings(false, false, defaultForwardTarget)
    }

    "read a json null as disabled settings" in {
      readOk(format, JsNull) mustBe SniSettings(false, false)
    }

    "read a forward target without host as the default target" in {
      readOk(format, Json.obj("enabled" -> true, "forwardsTo" -> Json.obj("port" -> 9000))).forwardsTo mustBe defaultForwardTarget
    }

    "ignore flags that are not booleans" in {
      readOk(format, Json.obj("enabled" -> "true", "forwardIfNoMatch" -> 1)) mustBe SniSettings(false, false)
    }

    "not read the snake case keys" in {
      readOk(format, Json.obj("enabled" -> true, "forward_if_no_match" -> true)).forwardIfNoMatch mustBe false
    }

    "expose through json what the format writes" in {
      fullSni.json mustBe format.writes(fullSni)
    }
  }

  "TlsMode" should {

    "be named Disabled, Enabled and PassThrough" in {
      TlsMode.Disabled.name mustBe "Disabled"
      TlsMode.Enabled.name mustBe "Enabled"
      TlsMode.PassThrough.name mustBe "PassThrough"
    }

    "be read from its name and from its lower cased name" in {
      TlsMode("Disabled") mustBe Some(TlsModeDisabled)
      TlsMode("enabled") mustBe Some(TlsModeEnabled)
      TlsMode("PassThrough") mustBe Some(TlsModePassThrough)
      TlsMode("passthrough") mustBe Some(TlsModePassThrough)
    }

    "be read back from its name" in {
      Seq(TlsMode.Disabled, TlsMode.Enabled, TlsMode.PassThrough).map(m => TlsMode(m.name)) mustBe Seq(
        Some(TlsModeDisabled),
        Some(TlsModeEnabled),
        Some(TlsModePassThrough)
      )
    }

    "not be read from any other case or name" in {
      TlsMode("ENABLED") mustBe None
      TlsMode("Passthrough") mustBe None
      TlsMode("tls") mustBe None
    }
  }

  "ClientAuth" should {

    "be named None, Want, Need and Dynamic" in {
      ClientAuth.values.map(_.name) mustBe Seq("None", "Want", "Need", "Dynamic")
    }

    "be read from its name whatever its case" in {
      ClientAuth("NONE") mustBe Some(ClientAuth.None)
      ClientAuth("Want") mustBe Some(ClientAuth.Want)
      ClientAuth("need") mustBe Some(ClientAuth.Need)
      ClientAuth("DyNaMiC") mustBe Some(ClientAuth.Dynamic)
    }

    "not be read from an unknown name" in {
      ClientAuth("required") mustBe None
    }

    "need a client certificate at the tls level when dynamic" in {
      ClientAuth.None.toAkkaClientAuth mustBe TLSClientAuth.None
      ClientAuth.Want.toAkkaClientAuth mustBe TLSClientAuth.Want
      ClientAuth.Need.toAkkaClientAuth mustBe TLSClientAuth.Need
      ClientAuth.Dynamic.toAkkaClientAuth mustBe TLSClientAuth.Need
    }
  }

  "TcpService" should {

    val format = TcpService.fmt

    "write a minimal service with its location" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"        -> defaultLocationJson,
        "id"          -> "tcp-1",
        "name"        -> "TCP 1",
        "description" -> "",
        "enabled"     -> false,
        "tls"         -> "Disabled",
        "sni"         -> defaultSniJson,
        "clientAuth"  -> "None",
        "port"        -> 4200,
        "interface"   -> "0.0.0.0",
        "rules"       -> Json.arr(),
        "metadata"    -> Json.obj(),
        "tags"        -> Json.arr()
      )
    }

    "write the tls mode, the client auth and the rules of a full service" in {
      val json = written(format, full)
      (json \ "tls").as[String] mustBe "PassThrough"
      (json \ "clientAuth").as[String] mustBe "Need"
      (json \ "interface").as[String] mustBe "127.0.0.1"
      (json \ "rules").as[JsArray] mustBe JsArray(full.rules.map(_.json))
      (json \ "sni").as[JsObject] mustBe fullSni.json
      (json \ "_loc").as[JsObject] mustBe customLocationJson
    }

    "round trip a minimal service" in {
      roundTrip(format, minimal)
    }

    "round trip a service where every field is set" in {
      roundTrip(format, full)
    }

    "round trip each tls mode" in {
      Seq(TlsMode.Disabled, TlsMode.Enabled, TlsMode.PassThrough).foreach { mode =>
        roundTrip(format, full.copy(tls = mode)).tls mustBe mode
      }
    }

    "round trip each client auth" in {
      ClientAuth.values.foreach { auth =>
        roundTrip(format, full.copy(clientAuth = auth)).clientAuth mustBe auth
      }
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read the default location when there is no _loc" in {
      readOk(format, written(format, full) - "_loc").location mustBe EntityLocation()
    }

    "keep the order of the rules and of the tags" in {
      val back = roundTrip(format, full.copy(rules = full.rules.reverse, tags = Seq("z", "a", "m")))
      back.rules.map(_.domain) mustBe Seq("*", "*.db.internal")
      back.tags mustBe Seq("z", "a", "m")
    }

    "read a lone id, name, description and port with the defaults" in {
      readOk(format, Json.obj("id" -> "tcp-1", "name" -> "TCP 1", "description" -> "", "port" -> 4200)) mustBe minimal
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a service without id" in {
      readError(format, written(format, full) - "id")
    }

    "not read a service without name" in {
      readError(format, written(format, full) - "name")
    }

    "not read a service without description" in {
      readError(format, written(format, full) - "description")
    }

    "not read a service without port" in {
      readError(format, written(format, full) - "port")
    }

    "not read a port that is not a number" in {
      readError(format, written(format, full) ++ Json.obj("port" -> "5432"))
    }

    "not read an id that is not a string" in {
      readError(format, written(format, full) ++ Json.obj("id" -> 42))
    }

    "read an unknown tls mode as disabled" in {
      readOk(format, written(format, full) ++ Json.obj("tls" -> "Strict")).tls mustBe TlsMode.Disabled
    }

    "read a tls mode that is not a string as disabled" in {
      readOk(format, written(format, full) ++ Json.obj("tls" -> true)).tls mustBe TlsMode.Disabled
    }

    "read the lower cased tls modes" in {
      readOk(format, written(format, full) ++ Json.obj("tls" -> "passthrough")).tls mustBe TlsMode.PassThrough
    }

    "read an upper cased tls mode as disabled" in {
      readOk(format, written(format, full) ++ Json.obj("tls" -> "ENABLED")).tls mustBe TlsMode.Disabled
    }

    "read the client auth whatever its case" in {
      readOk(format, written(format, full) ++ Json.obj("clientAuth" -> "WANT")).clientAuth mustBe ClientAuth.Want
    }

    "read an unknown client auth as none" in {
      readOk(format, written(format, full) ++ Json.obj("clientAuth" -> "required")).clientAuth mustBe ClientAuth.None
    }

    "not read the snake case client_auth key" in {
      readOk(format, written(format, minimal) ++ Json.obj("client_auth" -> "Need")).clientAuth mustBe ClientAuth.None
    }

    "read a json null sni as disabled sni settings" in {
      readOk(format, written(format, full) ++ Json.obj("sni" -> JsNull)).sni mustBe SniSettings(false, false)
    }

    "read a rule that is not an object as a wildcard rule without target" in {
      readOk(format, written(format, full) ++ Json.obj("rules" -> Json.arr("db.internal"))).rules mustBe Seq(
        TcpRule("*", Seq.empty)
      )
    }

    "ignore rules that are not an array" in {
      readOk(format, written(format, full) ++ Json.obj("rules" -> full.rules.head.json)).rules mustBe empty
    }

    "lose every target of a rule when one of them cannot be read" in {
      val rules = Json.arr(Json.obj("domain" -> "d", "targets" -> Json.arr(target1.json, Json.obj("ip" -> "10.0.0.2"))))
      readOk(format, written(format, full) ++ Json.obj("rules" -> rules)).rules mustBe Seq(TcpRule("d", Seq.empty))
    }

    "ignore an enabled flag and an interface that have a wrong type" in {
      val back = readOk(format, written(format, full) ++ Json.obj("enabled" -> "true", "interface" -> 127))
      back.enabled mustBe false
      back.interface mustBe "0.0.0.0"
    }

    "ignore metadata holding a non string value" in {
      readOk(format, written(format, full) ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe Map.empty
    }

    "ignore tags given as a single string" in {
      readOk(format, written(format, full) ++ Json.obj("tags" -> "db")).tags mustBe empty
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "tcp-2", "TCP 2", "the second tcp service", Seq("db", "prod"), Map("owner" -> "dba", "cost-center" -> "42"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "be built from its json" in {
      TcpService.fromJsons(full.json) mustBe full
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy TcpService.fromJsons(written(format, full) - "id")
    }

    "be built safely from its json" in {
      TcpService.fromJsonSafe(full.json) mustBe Right(full)
    }

    "give the json errors when built safely from a json without port" in {
      TcpService.fromJsonSafe(written(format, full) - "port").isLeft mustBe true
    }

    "be named TCP Proxy, listen on every interface and live in the default location by default" in {
      val service = TcpService(
        enabled = true,
        tls = TlsMode.Enabled,
        sni = SniSettings(false, false),
        clientAuth = ClientAuth.None,
        port = 1234,
        rules = Seq.empty,
        tags = Seq.empty,
        metadata = Map.empty
      )
      service.name mustBe "TCP Proxy"
      service.description mustBe "A TCP Proxy"
      service.interface mustBe "0.0.0.0"
      service.location mustBe EntityLocation()
    }

    "get a random 64 characters id by default" in {
      def build() = TcpService(
        enabled = true,
        tls = TlsMode.Enabled,
        sni = SniSettings(false, false),
        clientAuth = ClientAuth.None,
        port = 1234,
        rules = Seq.empty,
        tags = Seq.empty,
        metadata = Map.empty
      )
      val first = build()
      first.id.length mustBe 64
      first.id must not be build().id
    }

    "match the sni domains with wildcards" in {
      TcpService.domainMatch("*.db.internal", "pg.db.internal") mustBe true
      TcpService.domainMatch("*", "anything.example.com") mustBe true
      TcpService.domainMatch("*.db.internal", "pg.db.internal.evil.com") mustBe false
    }

    "match the dots of an sni domain literally" in {
      TcpService.domainMatch("db.internal", "db.internal") mustBe true
      TcpService.domainMatch("db.internal", "dbxinternal") mustBe false
    }
  }
}
