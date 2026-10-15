package entities

import otoroshi.models.EntityLocation
import otoroshi.next.extensions.{HttpListener, HttpListenerConfig, HttpListenerNames}
import otoroshi.ssl.ClientAuth
import play.api.libs.json.*

class HttpListenerEntityFormatSpec extends EntityFormatSpec {

  private val defaultConfigJson = Json.obj(
    "enabled"     -> true,
    "exclusive"   -> false,
    "tls"         -> true,
    "http1"       -> true,
    "http2"       -> true,
    "h2c"         -> false,
    "http3"       -> false,
    "port"        -> 7890,
    "exposedPort" -> 7890,
    "host"        -> "0.0.0.0",
    "accessLog"   -> false,
    "clientAuth"  -> "None"
  )

  private val fullConfig = HttpListenerConfig(
    enabled = false,
    exclusive = true,
    tls = false,
    http1 = false,
    http2 = false,
    h2c = true,
    http3 = true,
    port = 8443,
    exposedPort = 443,
    host = "127.0.0.1",
    accessLog = true,
    clientAuth = ClientAuth.Need
  )

  private val minimal = HttpListener(
    location = EntityLocation(),
    id = "http-listener-1",
    name = "Listener 1",
    description = "",
    tags = Seq.empty,
    config = HttpListenerConfig.default,
    metadata = Map.empty
  )

  private val full = HttpListener(
    location = customLocation,
    id = "http-listener-1",
    name = "Listener 1",
    description = "the listener of team 1",
    tags = Seq("internal", "mtls"),
    config = fullConfig,
    metadata = Map("owner" -> "ops", "zone" -> "dmz")
  )

  /** the json of a listener with the given fields, the rest taken from the minimal listener */
  private def minimalJson(fields: (String, Json.JsValueWrapper)*): JsObject =
    written(HttpListener.format, minimal) ++ Json.obj(fields*)

  "HttpListenerNames" should {

    "name the experimental and the standard listeners" in {
      HttpListenerNames.Experimental mustBe "experimental-listener"
      HttpListenerNames.Standard mustBe "standard-listener"
    }
  }

  "HttpListener" should {

    val format = HttpListener.format

    "write a minimal listener" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"        -> defaultLocationJson,
        "id"          -> "http-listener-1",
        "name"        -> "Listener 1",
        "description" -> "",
        "config"      -> defaultConfigJson,
        "metadata"    -> Json.obj(),
        "tags"        -> Json.arr()
      )
    }

    "write a listener where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "_loc"        -> customLocationJson,
        "id"          -> "http-listener-1",
        "name"        -> "Listener 1",
        "description" -> "the listener of team 1",
        "config"      -> Json.obj(
          "enabled"     -> false,
          "exclusive"   -> true,
          "tls"         -> false,
          "http1"       -> false,
          "http2"       -> false,
          "h2c"         -> true,
          "http3"       -> true,
          "port"        -> 8443,
          "exposedPort" -> 443,
          "host"        -> "127.0.0.1",
          "accessLog"   -> true,
          "clientAuth"  -> "Need"
        ),
        "metadata"    -> Json.obj("owner" -> "ops", "zone" -> "dmz"),
        "tags"        -> Json.arr("internal", "mtls")
      )
    }

    "round trip a minimal listener" in {
      roundTrip(format, minimal)
    }

    "round trip a listener where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read a listener without _loc in the default location" in {
      readOk(format, minimalJson() - "_loc").location mustBe EntityLocation()
    }

    "read a lone id, name, description and config with empty tags and metadata" in {
      readOk(
        format,
        Json.obj("id" -> "l", "name" -> "L", "description" -> "", "config" -> defaultConfigJson)
      ) mustBe HttpListener(EntityLocation(), "l", "L", "", Seq.empty, HttpListenerConfig.default, Map.empty)
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a listener without id" in {
      readError(format, minimalJson() - "id")
    }

    "not read a listener without name" in {
      readError(format, minimalJson() - "name")
    }

    "not read a listener without description" in {
      readError(format, minimalJson() - "description")
    }

    "not read a listener without config" in {
      readError(format, minimalJson() - "config")
    }

    "not read an id that is not a string" in {
      readError(format, minimalJson("id" -> 7890))
    }

    "read a null config as a disabled listener with the default ports" in {
      readOk(format, minimalJson("config" -> JsNull)).config mustBe HttpListenerConfig.default.copy(enabled = false)
    }

    "read a config that is not an object as a disabled listener" in {
      readOk(format, minimalJson("config" -> "https://0.0.0.0:7890")).config.enabled mustBe false
    }

    "read a listener without tags nor metadata with empty ones" in {
      val back = readOk(format, written(format, full) - "tags" - "metadata")
      back.tags mustBe empty
      back.metadata mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, minimalJson("metadata" -> Json.obj("a" -> "b", "n" -> 1))).metadata mustBe Map.empty
    }

    "ignore tags given as a single string" in {
      readOk(format, minimalJson("tags" -> "internal")).tags mustBe empty
    }

    "ignore unknown keys" in {
      readOk(format, minimalJson("foo" -> "bar")) mustBe minimal
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "http-listener-1", "Listener 1", "the listener of team 1", Seq("internal", "mtls"), Map("owner" -> "ops", "zone" -> "dmz"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }
  }

  "HttpListenerConfig" should {

    val format = HttpListenerConfig.format

    "be an enabled tls http1 and http2 listener on port 7890 by default" in {
      HttpListenerConfig.default mustBe HttpListenerConfig(
        enabled = true,
        exclusive = false,
        tls = true,
        http1 = true,
        http2 = true,
        h2c = false,
        http3 = false,
        port = 7890,
        exposedPort = 7890,
        host = "0.0.0.0",
        accessLog = false,
        clientAuth = ClientAuth.None
      )
    }

    "write the default config" in {
      format.writes(HttpListenerConfig.default) mustBe defaultConfigJson
    }

    "expose through json what the format writes" in {
      fullConfig.json mustBe format.writes(fullConfig)
    }

    "round trip the default config" in {
      roundTrip(format, HttpListenerConfig.default)
    }

    "round trip a config where every field is set" in {
      roundTrip(format, fullConfig)
    }

    "round trip each client auth" in {
      ClientAuth.values.foreach { clientAuth =>
        roundTrip(format, fullConfig.copy(clientAuth = clientAuth)).clientAuth mustBe clientAuth
      }
    }

    "write the client auth with its capitalized name" in {
      (format.writes(fullConfig.copy(clientAuth = ClientAuth.Dynamic)) \ "clientAuth").as[String] mustBe "Dynamic"
      (format.writes(fullConfig.copy(clientAuth = ClientAuth.Want)) \ "clientAuth").as[String] mustBe "Want"
    }

    "round trip an exposed port different from the port" in {
      val back = roundTrip(format, HttpListenerConfig.default.copy(port = 10443, exposedPort = 443))
      back.port mustBe 10443
      back.exposedPort mustBe 443
    }

    "read an empty object as a disabled listener, unlike the default config" in {
      readOk(format, Json.obj()) mustBe HttpListenerConfig.default.copy(enabled = false)
    }

    "read a json null as a disabled listener" in {
      readOk(format, JsNull) mustBe HttpListenerConfig.default.copy(enabled = false)
    }

    "read a json string as a disabled listener" in {
      readOk(format, JsString("0.0.0.0:7890")) mustBe HttpListenerConfig.default.copy(enabled = false)
    }

    "read the defaults of each missing protocol flag" in {
      val back = readOk(format, Json.obj("enabled" -> true, "port" -> 8080))
      back.tls mustBe true
      back.http1 mustBe true
      back.http2 mustBe true
      back.h2c mustBe false
      back.http3 mustBe false
      back.exclusive mustBe false
      back.accessLog mustBe false
    }

    "read a missing host as every interface" in {
      readOk(format, Json.obj("enabled" -> true)).host mustBe "0.0.0.0"
    }

    "read a missing exposed port as the port" in {
      readOk(format, Json.obj("port" -> 8080)).exposedPort mustBe 8080
    }

    "read the exposed port before the port" in {
      readOk(format, Json.obj("port" -> 8080, "exposedPort" -> 80)).exposedPort mustBe 80
    }

    "read a missing port and exposed port as 7890" in {
      val back = readOk(format, Json.obj("enabled" -> true))
      back.port mustBe 7890
      back.exposedPort mustBe 7890
    }

    "not read the exposed port under a snake case key" in {
      readOk(format, Json.obj("port" -> 8080, "exposed_port" -> 80)).exposedPort mustBe 8080
    }

    "not read the access log under a snake case key" in {
      readOk(format, Json.obj("access_log" -> true)).accessLog mustBe false
    }

    "read a port written as a string as the default port" in {
      readOk(format, Json.obj("port" -> "8080")).port mustBe 7890
    }

    "read a port that is not an integer as the default port" in {
      readOk(format, Json.obj("port" -> 80.5)).port mustBe 7890
    }

    "read an exposed port written as a string as the port" in {
      readOk(format, Json.obj("port" -> 8080, "exposedPort" -> "80")).exposedPort mustBe 8080
    }

    "read a tls flag written as a string as the default tls flag" in {
      readOk(format, Json.obj("tls" -> "false")).tls mustBe true
    }

    "read an enabled flag written as a string as disabled" in {
      readOk(format, Json.obj("enabled" -> "true")).enabled mustBe false
    }

    "read the client auth whatever its case" in {
      readOk(format, Json.obj("clientAuth" -> "need")).clientAuth mustBe ClientAuth.Need
      readOk(format, Json.obj("clientAuth" -> "WANT")).clientAuth mustBe ClientAuth.Want
      readOk(format, Json.obj("clientAuth" -> "Dynamic")).clientAuth mustBe ClientAuth.Dynamic
    }

    "read an unknown client auth as no client auth" in {
      readOk(format, Json.obj("clientAuth" -> "required")).clientAuth mustBe ClientAuth.None
    }

    "read a client auth that is not a string as no client auth" in {
      readOk(format, Json.obj("clientAuth" -> true)).clientAuth mustBe ClientAuth.None
    }

    "not read the client auth under a snake case key" in {
      readOk(format, Json.obj("client_auth" -> "Need")).clientAuth mustBe ClientAuth.None
    }

    "ignore unknown keys" in {
      readOk(format, defaultConfigJson ++ Json.obj("id" -> "static-1")) mustBe HttpListenerConfig.default
    }
  }
}
