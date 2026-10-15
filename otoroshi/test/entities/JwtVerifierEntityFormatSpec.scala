package entities

import com.auth0.jwt.JWT
import com.auth0.jwt.exceptions.InvalidClaimException
import com.nimbusds.jose.jwk.KeyType
import org.apache.commons.codec.digest.DigestUtils
import otoroshi.models.*
import otoroshi.utils.http.MtlsConfig
import play.api.libs.json.*
import play.api.libs.ws.DefaultWSProxyServer
import play.api.mvc.Cookie
import play.api.test.FakeRequest

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.*
import scala.util.{Failure, Try}

class JwtVerifierEntityFormatSpec extends EntityFormatSpec {

  // keys generated once with openssl: a 1024 bits RSA public key, and an EC P-256 key pair
  private val rsaPublicKey =
    """-----BEGIN PUBLIC KEY-----
      |MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCneYO01Rslja+Kp51GZI9YlO/O
      |pYBQXc5i8gMLdPik2VRO4ENqEGgwB6x/1atc4PXkYeggpkS21MP4+hZxTNFaZwCI
      |3UpeMpAKhGG2rHbForPRvO6brqUGLrQx7ZymmAD5mhFu7V7Ha2Ch3tAS2cMKJ8rk
      |JKso6pNUlymhpcTroQIDAQAB
      |-----END PUBLIC KEY-----""".stripMargin

  private val ecPublicKey =
    """-----BEGIN PUBLIC KEY-----
      |MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9Z3J3tnq46OTkbzCe0hiPLzEPPBU
      |dlnNwylk+JYnCehVeWW3iLTCcUodJ5C98sTIDDUCa0GcWFCPEKKAk+J+vQ==
      |-----END PUBLIC KEY-----""".stripMargin

  private val ecPrivateKey =
    """-----BEGIN PRIVATE KEY-----
      |MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg8XhNH+dq6vTKK1uh
      |uhHuXAKSQigA+aZujNZ0hcY1M5qhRANCAAT1ncne2erjo5ORvMJ7SGI8vMQ88FR2
      |Wc3DKWT4licJ6FV5ZbeItMJxSh0nkL3yxMgMNQJrQZxYUI8QooCT4n69
      |-----END PRIVATE KEY-----""".stripMargin

  /** an unsigned token, enough for JWT.decode */
  private def token(payload: JsObject): String = {
    def enc(json: JsObject) =
      Base64.getUrlEncoder.withoutPadding.encodeToString(Json.stringify(json).getBytes(StandardCharsets.UTF_8))
    s"${enc(Json.obj("alg" -> "HS256", "typ" -> "JWT"))}.${enc(payload)}.c2lnbmF0dXJl"
  }

  private val rawToken = token(Json.obj("sub" -> "john", "roles" -> Json.arr("admin", "user"), "levels" -> Json.arr(1, true)))
  private val decoded  = JWT.decode(rawToken)

  /** reads the json with a fromJson function, fails the test if it cannot */
  private def fromJsonOk[A](fromJson: JsValue => Either[Throwable, A], json: JsValue): A = fromJson(json) match {
    case Right(value) => value
    case Left(e)      => fail(s"could not read ${Json.stringify(json)}: ${e.getMessage}")
  }

  /** checks that a fromJson function cannot read the json */
  private def fromJsonError[A](fromJson: JsValue => Either[Throwable, A], json: JsValue): Unit = {
    val result = fromJson(json)
    withClue(s"${Json.stringify(json)} was read as $result: ") {
      result.isLeft mustBe true
    }
  }

  /** writes the value with asJson, reads it back with fromJson, and checks that nothing moved */
  private def asJsonRoundTrip[A <: AsJson](fromJson: JsValue => Either[Throwable, A], value: A): A = {
    val json = value.asJson
    val back = fromJsonOk(fromJson, json)
    withClue(s"${Json.stringify(json)}: ") {
      back mustBe value
      back.asJson mustBe json
    }
    back
  }

  private val verificationSettings = VerificationSettings(
    fields = Map("iss" -> "otoroshi", "aud" -> "Regex(api-.*)"),
    arrayFields = Map("roles" -> "admin,user")
  )

  private val mappingSettings = MappingSettings(
    map = Map("sub" -> "user", "email" -> "mail"),
    values = Json.obj("foo" -> 123, "bar" -> Json.obj("baz" -> true)),
    remove = Seq("secret", "internal")
  )

  private val proxy = DefaultWSProxyServer(
    host = "proxy.oto.tools",
    port = 3128,
    protocol = Some("https"),
    principal = Some("user"),
    password = Some("pass"),
    ntlmDomain = Some("domain"),
    encoding = Some("UTF-8"),
    nonProxyHosts = Some(Seq("localhost", "*.internal"))
  )

  private val mtlsConfig = MtlsConfig(
    certs = Seq("client-cert"),
    trustedCerts = Seq("ca-cert"),
    mtls = true,
    loose = true,
    trustAll = true
  )

  private val minimalJwks = JWKSAlgoSettings(
    url = "https://idp.oto.tools/.well-known/jwks.json",
    headers = Map.empty,
    timeout = 2000.millis,
    ttl = 1.hour,
    kty = KeyType.RSA,
    proxy = None,
    tlsConfig = MtlsConfig()
  )

  private val fullJwks = JWKSAlgoSettings(
    url = "https://idp.oto.tools/.well-known/jwks.json",
    headers = Map("Authorization" -> "Basic Zm9vOmJhcg=="),
    timeout = 5.seconds,
    ttl = 10.minutes,
    kty = KeyType.EC,
    proxy = Some(proxy),
    tlsConfig = mtlsConfig
  )

  private val allAlgoSettings: Seq[AlgoSettings] = Seq(
    HSAlgoSettings(384, "${config.app.secret}", base64 = true),
    RSAlgoSettings(512, rsaPublicKey, None),
    ESAlgoSettings(256, ecPublicKey, Some(ecPrivateKey)),
    fullJwks,
    RSAKPAlgoSettings(256, "cert-rsa"),
    ESKPAlgoSettings(384, "cert-ec"),
    KidAlgoSettings(onlyExposedCerts = true)
  )

  private val transformSettings = TransformSettings(InQueryParam("access_token"), mappingSettings)

  private val allStrategies: Seq[VerifierStrategy] = Seq(
    PassThrough(verificationSettings),
    Sign(verificationSettings, ESAlgoSettings(512, ecPublicKey, Some(ecPrivateKey))),
    Transform(verificationSettings, transformSettings, RSAKPAlgoSettings(384, "cert-rsa")),
    DefaultToken(strict = false, token = Json.obj("sub" -> "anonymous"), verificationSettings = verificationSettings)
  )

  "JwtTokenLocation" should {

    val read = JwtTokenLocation.fromJson

    "write a header location with the value prefix to remove" in {
      InHeader("Authorization", "Bearer ").asJson mustBe Json.obj(
        "type"   -> "InHeader",
        "name"   -> "Authorization",
        "remove" -> "Bearer "
      )
    }

    "write a query param location" in {
      InQueryParam("access_token").asJson mustBe Json.obj("type" -> "InQueryParam", "name" -> "access_token")
    }

    "write a cookie location" in {
      InCookie("jwt").asJson mustBe Json.obj("type" -> "InCookie", "name" -> "jwt")
    }

    "round trip every kind of location" in {
      asJsonRoundTrip(read, InHeader("Authorization", "Bearer "))
      asJsonRoundTrip(read, InHeader("X-JWT-Token"))
      asJsonRoundTrip(read, InQueryParam("access_token"))
      asJsonRoundTrip(read, InCookie("jwt"))
    }

    "read a header location without remove as removing nothing" in {
      fromJsonOk(read, Json.obj("type" -> "InHeader", "name" -> "Authorization")) mustBe InHeader("Authorization", "")
    }

    "read the kind of location from its type only" in {
      fromJsonOk(read, Json.obj("type" -> "InCookie", "name" -> "jwt", "remove" -> "Bearer ")) mustBe InCookie("jwt")
    }

    "not read a location without type" in {
      fromJsonError(read, Json.obj("name" -> "Authorization"))
    }

    "not read a location of an unknown type" in {
      fromJsonError(read, Json.obj("type" -> "InBody", "name" -> "token"))
    }

    "not read a type with another case" in {
      fromJsonError(read, Json.obj("type" -> "inheader", "name" -> "Authorization"))
    }

    "not read a location without name, whatever its kind" in {
      fromJsonError(read, Json.obj("type" -> "InHeader", "remove" -> "Bearer "))
      fromJsonError(read, Json.obj("type" -> "InQueryParam"))
      fromJsonError(read, Json.obj("type" -> "InCookie"))
    }

    "not read a name that is not a string" in {
      fromJsonError(read, Json.obj("type" -> "InHeader", "name" -> 42))
    }

    "not read a json null" in {
      fromJsonError(read, JsNull)
    }

    "take the token from the header, without the prefix to remove" in {
      val request = FakeRequest("GET", "/api").withHeaders("Authorization" -> s"Bearer $rawToken")
      InHeader("Authorization", "Bearer ").token(request) mustBe Some(rawToken)
      InHeader("Authorization").token(request) mustBe Some(s"Bearer $rawToken")
      InHeader("X-JWT-Token").token(request) mustBe None
    }

    "remove from the header value what matches the remove value as a regular expression" in {
      val request = FakeRequest("GET", "/api").withHeaders("Authorization" -> s"Bearer  $rawToken")
      InHeader("Authorization", "B.arer\\s+").token(request) mustBe Some(rawToken)
    }

    "take the token from the query string" in {
      val request = FakeRequest("GET", s"/api?access_token=$rawToken")
      InQueryParam("access_token").token(request) mustBe Some(rawToken)
      InQueryParam("token").token(request) mustBe None
    }

    "take the token from the cookie" in {
      val request = FakeRequest("GET", "/api").withCookies(Cookie("jwt", rawToken))
      InCookie("jwt").token(request) mustBe Some(rawToken)
      InCookie("other").token(request) mustBe None
    }

    "inject a new token in the header, with the removed prefix" in {
      val injection = InHeader("Authorization", "Bearer ").asJwtInjection(decoded, "new-token")
      injection.decodedToken.map(_.getToken) mustBe Some(rawToken)
      injection.additionalHeaders mustBe Map("Authorization" -> "Bearer new-token")
      injection.additionalCookies mustBe empty
    }

    "inject a new token in the cookie" in {
      val injection = InCookie("jwt").asJwtInjection(decoded, "new-token")
      injection.decodedToken.map(_.getToken) mustBe Some(rawToken)
      injection.additionalCookies mustBe Map("jwt" -> "new-token")
      injection.additionalHeaders mustBe empty
    }

    "inject nothing, not even the decoded token, in the query string" in {
      val injection = InQueryParam("access_token").asJwtInjection(decoded, "new-token")
      injection.decodedToken mustBe None
      injection.additionalHeaders mustBe empty
      injection.additionalCookies mustBe empty
    }
  }

  "JwtInjection" should {

    val injection = JwtInjection(
      decodedToken = Some(decoded),
      additionalHeaders = Map("Authorization" -> "Bearer new"),
      removeHeaders = Seq("X-Old"),
      additionalCookies = Map("jwt" -> "new"),
      removeCookies = Seq("old")
    )

    "write an empty injection" in {
      JwtInjection().asJson mustBe Json.obj(
        "token"             -> JsNull,
        "additionalHeaders" -> Json.obj(),
        "removeHeaders"     -> Json.arr(),
        "additionalCookies" -> Json.obj(),
        "removeCookies"     -> Json.arr()
      )
    }

    "write the raw token of the decoded token" in {
      injection.json mustBe Json.obj(
        "token"             -> rawToken,
        "additionalHeaders" -> Json.obj("Authorization" -> "Bearer new"),
        "removeHeaders"     -> Json.arr("X-Old"),
        "additionalCookies" -> Json.obj("jwt" -> "new"),
        "removeCookies"     -> Json.arr("old")
      )
    }

    "read back the headers and cookies to add and remove" in {
      val back = fromJsonOk(JwtInjection.fromJson, injection.asJson)
      back.additionalHeaders mustBe injection.additionalHeaders
      back.removeHeaders mustBe injection.removeHeaders
      back.additionalCookies mustBe injection.additionalCookies
      back.removeCookies mustBe injection.removeCookies
    }

    "read a raw token from the decodedToken key" in {
      val json = JwtInjection().asJson.as[JsObject] ++ Json.obj("decodedToken" -> rawToken)
      fromJsonOk(JwtInjection.fromJson, json).decodedToken.map(_.getSubject) mustBe Some("john")
    }

    "not read an injection without the headers and cookies to add and remove" in {
      fromJsonError(JwtInjection.fromJson, Json.obj())
      fromJsonError(JwtInjection.fromJson, JwtInjection().asJson.as[JsObject] - "removeCookies")
    }
  }

  "HSAlgoSettings" should {

    "write its size, its secret and whether it is base64 encoded" in {
      HSAlgoSettings(512, "secret").asJson mustBe Json.obj(
        "type"   -> "HSAlgoSettings",
        "size"   -> 512,
        "secret" -> "secret",
        "base64" -> false
      )
    }

    "round trip a base64 encoded secret" in {
      asJsonRoundTrip(HSAlgoSettings.fromJson, HSAlgoSettings(256, "c2VjcmV0", base64 = true))
    }

    "read a secret without base64 flag as not encoded" in {
      fromJsonOk(HSAlgoSettings.fromJson, Json.obj("size" -> 256, "secret" -> "s")).base64 mustBe false
    }

    "not read settings without size or without secret" in {
      fromJsonError(HSAlgoSettings.fromJson, Json.obj("secret" -> "s"))
      fromJsonError(HSAlgoSettings.fromJson, Json.obj("size" -> 256))
    }

    "not read a size given as a string" in {
      fromJsonError(HSAlgoSettings.fromJson, Json.obj("size" -> "256", "secret" -> "s"))
    }

    "read a base64 flag given as a string as not encoded" in {
      fromJsonOk(HSAlgoSettings.fromJson, Json.obj("size" -> 256, "secret" -> "s", "base64" -> "true")).base64 mustBe false
    }
  }

  "RSAlgoSettings and ESAlgoSettings" should {

    "write a public key without private key" in {
      RSAlgoSettings(256, rsaPublicKey, None).asJson mustBe Json.obj(
        "type"       -> "RSAlgoSettings",
        "size"       -> 256,
        "publicKey"  -> rsaPublicKey,
        "privateKey" -> JsNull
      )
      ESAlgoSettings(256, ecPublicKey, None).asJson mustBe Json.obj(
        "type"       -> "ESAlgoSettings",
        "size"       -> 256,
        "publicKey"  -> ecPublicKey,
        "privateKey" -> JsNull
      )
    }

    "round trip settings with and without private key" in {
      asJsonRoundTrip(RSAlgoSettings.fromJson, RSAlgoSettings(384, rsaPublicKey, None))
      asJsonRoundTrip(RSAlgoSettings.fromJson, RSAlgoSettings(512, rsaPublicKey, Some("rsa-private-key")))
      asJsonRoundTrip(ESAlgoSettings.fromJson, ESAlgoSettings(384, ecPublicKey, None))
      asJsonRoundTrip(ESAlgoSettings.fromJson, ESAlgoSettings(256, ecPublicKey, Some(ecPrivateKey)))
    }

    "round trip an empty private key as an empty private key" in {
      asJsonRoundTrip(ESAlgoSettings.fromJson, ESAlgoSettings(256, ecPublicKey, Some(""))).privateKey mustBe Some("")
    }

    "read settings without private key" in {
      fromJsonOk(RSAlgoSettings.fromJson, Json.obj("size" -> 256, "publicKey" -> rsaPublicKey)).privateKey mustBe None
      fromJsonOk(ESAlgoSettings.fromJson, Json.obj("size" -> 256, "publicKey" -> ecPublicKey)).privateKey mustBe None
    }

    "not read settings without public key" in {
      fromJsonError(RSAlgoSettings.fromJson, Json.obj("size" -> 256, "privateKey" -> "k"))
      fromJsonError(ESAlgoSettings.fromJson, Json.obj("size" -> 256, "privateKey" -> "k"))
    }

    "not read settings without size" in {
      fromJsonError(RSAlgoSettings.fromJson, Json.obj("publicKey" -> rsaPublicKey))
      fromJsonError(ESAlgoSettings.fromJson, Json.obj("publicKey" -> ecPublicKey))
    }

    "parse a pem public key" in {
      val rsa = RSAlgoSettings(256, rsaPublicKey, None)
      rsa.getPublicKey(rsaPublicKey).getModulus.bitLength mustBe 1024
      val es  = ESAlgoSettings(256, ecPublicKey, None)
      es.getPublicKey(ecPublicKey).getAlgorithm mustBe "EC"
    }

    "parse a pem private key" in {
      val es = ESAlgoSettings(256, ecPublicKey, Some(ecPrivateKey))
      es.getPrivateKey(ecPrivateKey).getAlgorithm mustBe "EC"
    }

    "give no private key for a blank one" in {
      RSAlgoSettings(256, rsaPublicKey, None).getPrivateKey("  ") mustBe null
      ESAlgoSettings(256, ecPublicKey, None).getPrivateKey("") mustBe null
    }
  }

  "JWKSAlgoSettings" should {

    "write its url, timeouts, key type, proxy and tls config" in {
      minimalJwks.asJson mustBe Json.obj(
        "type"       -> "JWKSAlgoSettings",
        "url"        -> "https://idp.oto.tools/.well-known/jwks.json",
        "timeout"    -> 2000,
        "headers"    -> Json.obj(),
        "ttl"        -> 3600000,
        "kty"        -> "RSA",
        "proxy"      -> JsNull,
        "tls_config" -> MtlsConfig().json,
        "mtlsConfig" -> MtlsConfig().json
      )
    }

    "write its proxy and its tls config under both keys" in {
      val json = fullJwks.asJson
      (json \ "proxy").as[JsObject] mustBe Json.obj(
        "host"          -> "proxy.oto.tools",
        "port"          -> 3128,
        "protocol"      -> "https",
        "principal"     -> "user",
        "password"      -> "pass",
        "ntlmDomain"    -> "domain",
        "encoding"      -> "UTF-8",
        "nonProxyHosts" -> Json.arr("localhost", "*.internal")
      )
      (json \ "tls_config").as[JsValue] mustBe mtlsConfig.json
      (json \ "mtlsConfig").as[JsValue] mustBe mtlsConfig.json
    }

    "round trip minimal settings" in {
      asJsonRoundTrip(JWKSAlgoSettings.fromJson, minimalJwks)
    }

    "round trip settings where every field is set" in {
      asJsonRoundTrip(JWKSAlgoSettings.fromJson, fullJwks)
    }

    "read a lone url with the defaults" in {
      fromJsonOk(JWKSAlgoSettings.fromJson, Json.obj("url" -> "https://idp.oto.tools/.well-known/jwks.json")) mustBe minimalJwks
    }

    "read the timeouts as millis" in {
      val back = fromJsonOk(JWKSAlgoSettings.fromJson, Json.obj("url" -> "u", "timeout" -> 1500, "ttl" -> 60000))
      back.timeout mustBe 1500.millis
      back.ttl mustBe 1.minute
    }

    "read timeouts given as strings as the default ones" in {
      val back = fromJsonOk(JWKSAlgoSettings.fromJson, Json.obj("url" -> "u", "timeout" -> "1500", "ttl" -> "60000"))
      back.timeout mustBe 2000.millis
      back.ttl mustBe 1.hour
    }

    "read the key types" in {
      fromJsonOk(JWKSAlgoSettings.fromJson, Json.obj("url" -> "u", "kty" -> "EC")).kty mustBe KeyType.EC
      fromJsonOk(JWKSAlgoSettings.fromJson, Json.obj("url" -> "u", "kty" -> "oct")).kty mustBe KeyType.OCT
      fromJsonOk(JWKSAlgoSettings.fromJson, Json.obj("url" -> "u", "kty" -> "OKP")).kty mustBe KeyType.OKP
    }

    "round trip an unknown key type" in {
      asJsonRoundTrip(JWKSAlgoSettings.fromJson, minimalJwks.copy(kty = KeyType.parse("FOO"))).kty.getValue mustBe "FOO"
    }

    "read the tls config from the mtlsConfig key first" in {
      val json = Json.obj(
        "url"        -> "u",
        "mtlsConfig" -> Json.obj("certs" -> Json.arr("a")),
        "tlsConfig"  -> Json.obj("certs" -> Json.arr("b")),
        "tls_config" -> Json.obj("certs" -> Json.arr("c"))
      )
      fromJsonOk(JWKSAlgoSettings.fromJson, json).tlsConfig.certs mustBe Seq("a")
    }

    "read the tls config from the legacy tlsConfig key, then from the tls_config key" in {
      val both = Json.obj(
        "url"        -> "u",
        "tlsConfig"  -> Json.obj("certs" -> Json.arr("b")),
        "tls_config" -> Json.obj("certs" -> Json.arr("c"))
      )
      fromJsonOk(JWKSAlgoSettings.fromJson, both).tlsConfig.certs mustBe Seq("b")
      fromJsonOk(JWKSAlgoSettings.fromJson, both - "tlsConfig").tlsConfig.certs mustBe Seq("c")
    }

    "read a json null tls config as the default one" in {
      fromJsonOk(JWKSAlgoSettings.fromJson, Json.obj("url" -> "u", "mtlsConfig" -> JsNull)).tlsConfig mustBe MtlsConfig()
    }

    "read a proxy without host or port as no proxy" in {
      fromJsonOk(JWKSAlgoSettings.fromJson, Json.obj("url" -> "u", "proxy" -> Json.obj("host" -> "", "port" -> 3128))).proxy mustBe None
      fromJsonOk(JWKSAlgoSettings.fromJson, Json.obj("url" -> "u", "proxy" -> Json.obj("host" -> "proxy"))).proxy mustBe None
    }

    "read a proxy with only a host and a port" in {
      fromJsonOk(
        JWKSAlgoSettings.fromJson,
        Json.obj("url" -> "u", "proxy" -> Json.obj("host" -> "proxy", "port" -> 3128))
      ).proxy mustBe Some(DefaultWSProxyServer("proxy", 3128))
    }

    "ignore headers holding a non string value" in {
      fromJsonOk(JWKSAlgoSettings.fromJson, Json.obj("url" -> "u", "headers" -> Json.obj("a" -> "b", "n" -> 1))).headers mustBe empty
    }

    "not read settings without url" in {
      fromJsonError(JWKSAlgoSettings.fromJson, Json.obj("ttl" -> 1000))
    }
  }

  "RSAKPAlgoSettings, ESKPAlgoSettings and KidAlgoSettings" should {

    "write the certificate holding the key pair" in {
      RSAKPAlgoSettings(256, "cert-rsa").asJson mustBe Json.obj("type" -> "RSAKPAlgoSettings", "size" -> 256, "certId" -> "cert-rsa")
      ESKPAlgoSettings(384, "cert-ec").asJson mustBe Json.obj("type" -> "ESKPAlgoSettings", "size" -> 384, "certId" -> "cert-ec")
    }

    "write whether only exposed certificates can be used" in {
      KidAlgoSettings(onlyExposedCerts = true).asJson mustBe Json.obj("type" -> "KidAlgoSettings", "onlyExposedCerts" -> true)
    }

    "round trip key pair settings" in {
      asJsonRoundTrip(RSAKPAlgoSettings.fromJson, RSAKPAlgoSettings(512, "cert-rsa"))
      asJsonRoundTrip(ESKPAlgoSettings.fromJson, ESKPAlgoSettings(256, "cert-ec"))
    }

    "keep the restriction to exposed certificates on a round trip" in {
      asJsonRoundTrip(KidAlgoSettings.fromJson, KidAlgoSettings(onlyExposedCerts = true)).onlyExposedCerts mustBe true
      asJsonRoundTrip(KidAlgoSettings.fromJson, KidAlgoSettings(onlyExposedCerts = false)).onlyExposedCerts mustBe false
    }

    "read kid settings without restriction as allowing every certificate" in {
      fromJsonOk(KidAlgoSettings.fromJson, Json.obj()) mustBe KidAlgoSettings(false)
      fromJsonOk(KidAlgoSettings.fromJson, Json.obj("onlyExposedCerts" -> "true")) mustBe KidAlgoSettings(false)
    }

    "not read key pair settings without certificate or size" in {
      fromJsonError(RSAKPAlgoSettings.fromJson, Json.obj("size" -> 256))
      fromJsonError(ESKPAlgoSettings.fromJson, Json.obj("size" -> 256))
      fromJsonError(RSAKPAlgoSettings.fromJson, Json.obj("certId" -> "c"))
      fromJsonError(ESKPAlgoSettings.fromJson, Json.obj("certId" -> "c"))
    }
  }

  "AlgoSettings" should {

    val read = AlgoSettings.fromJson

    "round trip every kind of algo settings from its type" in {
      allAlgoSettings.foreach(settings => asJsonRoundTrip(read, settings))
    }

    "be named after its type" in {
      allAlgoSettings.map(_.name) mustBe allAlgoSettings.map(s => (s.asJson \ "type").as[String])
    }

    "have a key id only for key pair settings" in {
      allAlgoSettings.map(_.keyId) mustBe Seq(None, None, None, None, Some("cert-rsa"), Some("cert-ec"), None)
    }

    "be synchronous, except jwks settings whose keys were never fetched" in {
      allAlgoSettings.map(_.isAsync) mustBe Seq(false, false, false, true, false, false, false)
    }

    "not read settings without type" in {
      fromJsonError(read, Json.obj("size" -> 512, "secret" -> "secret"))
    }

    "not read settings of an unknown type" in {
      fromJsonError(read, Json.obj("type" -> "NoneAlgoSettings"))
    }

    "not read a json null" in {
      fromJsonError(read, JsNull)
    }

    "use a plain secret as is, without evaluating it" in {
      var evaluated = false
      AlgoSettings.fromCacheOrNot("plain-secret", { evaluated = true; "evaluated" }) mustBe "plain-secret"
      evaluated mustBe false
    }

    "evaluate an expression secret once and cache it" in {
      val key = s"$${spec.jwt.secret.${System.nanoTime()}}"
      AlgoSettings.fromCacheOrNot(key, "first") mustBe "first"
      AlgoSettings.fromCacheOrNot(key, "second") mustBe "first"
    }
  }

  "MappingSettings" should {

    "write empty settings" in {
      MappingSettings().asJson mustBe Json.obj("map" -> Json.obj(), "values" -> Json.obj(), "remove" -> Json.arr())
    }

    "write the claims to rename, add and remove" in {
      mappingSettings.asJson mustBe Json.obj(
        "map"    -> Json.obj("sub" -> "user", "email" -> "mail"),
        "values" -> Json.obj("foo" -> 123, "bar" -> Json.obj("baz" -> true)),
        "remove" -> Json.arr("secret", "internal")
      )
    }

    "round trip settings where every field is set" in {
      asJsonRoundTrip(MappingSettings.fromJson, mappingSettings)
    }

    "read an empty object as empty settings" in {
      fromJsonOk(MappingSettings.fromJson, Json.obj()) mustBe MappingSettings()
    }

    "read fields of the wrong type as empty" in {
      fromJsonOk(
        MappingSettings.fromJson,
        Json.obj("map" -> Json.obj("a" -> 1), "values" -> Json.arr(1), "remove" -> "secret")
      ) mustBe MappingSettings()
    }
  }

  "TransformSettings" should {

    "write its location and its mapping" in {
      transformSettings.asJson mustBe Json.obj(
        "location"        -> Json.obj("type" -> "InQueryParam", "name" -> "access_token"),
        "mappingSettings" -> mappingSettings.asJson
      )
    }

    "round trip settings where every field is set" in {
      asJsonRoundTrip(TransformSettings.fromJson, transformSettings)
    }

    "read settings without mapping with an empty mapping" in {
      fromJsonOk(TransformSettings.fromJson, Json.obj("location" -> InCookie("jwt").asJson)) mustBe TransformSettings(
        InCookie("jwt"),
        MappingSettings()
      )
    }

    "not read settings without location" in {
      fromJsonError(TransformSettings.fromJson, Json.obj("mappingSettings" -> mappingSettings.asJson))
    }

    "not read settings with a location that cannot be read" in {
      fromJsonError(TransformSettings.fromJson, Json.obj("location" -> Json.obj("type" -> "InBody", "name" -> "t")))
    }
  }

  "VerificationSettings" should {

    "write empty settings" in {
      VerificationSettings().asJson mustBe Json.obj("fields" -> Json.obj(), "arrayFields" -> Json.obj())
    }

    "write the claims to check" in {
      verificationSettings.asJson mustBe Json.obj(
        "fields"      -> Json.obj("iss" -> "otoroshi", "aud" -> "Regex(api-.*)"),
        "arrayFields" -> Json.obj("roles" -> "admin,user")
      )
    }

    "round trip settings where every field is set" in {
      asJsonRoundTrip(VerificationSettings.fromJson, verificationSettings)
    }

    "read an empty object as empty settings" in {
      fromJsonOk(VerificationSettings.fromJson, Json.obj()) mustBe VerificationSettings()
    }

    "read claims holding a non string value as no claim to check" in {
      fromJsonOk(
        VerificationSettings.fromJson,
        Json.obj("fields" -> Json.obj("iss" -> "otoroshi", "exp" -> 1), "arrayFields" -> Json.arr("roles"))
      ) mustBe VerificationSettings()
    }

    "read a json null as empty settings" in {
      fromJsonOk(VerificationSettings.fromJson, JsNull) mustBe VerificationSettings()
    }

    "accept a token whose array claim holds the expected value" in {
      VerificationSettings(arrayFields = Map("roles" -> "admin")).additionalVerification(decoded) mustBe decoded
    }

    "accept a token whose array claim holds every comma separated expected value" in {
      VerificationSettings(arrayFields = Map("roles" -> "admin, user")).additionalVerification(decoded) mustBe decoded
    }

    "compare the numbers and booleans of an array claim as strings" in {
      VerificationSettings(arrayFields = Map("levels" -> "1,true")).additionalVerification(decoded) mustBe decoded
    }

    "reject a token whose array claim misses an expected value" in {
      an[InvalidClaimException] must be thrownBy VerificationSettings(arrayFields = Map("roles" -> "admin,root"))
        .additionalVerification(decoded)
    }

    "fail on a token without the array claim" in {
      a[JsResultException] must be thrownBy VerificationSettings(arrayFields = Map("groups" -> "admin"))
        .additionalVerification(decoded)
    }

    "not check the simple claims in the additional verification" in {
      VerificationSettings(fields = Map("sub" -> "jane")).additionalVerification(decoded) mustBe decoded
    }
  }

  "VerifierStrategy" should {

    val read = VerifierStrategy.fromJson

    "write a pass through strategy" in {
      PassThrough(verificationSettings).asJson mustBe Json.obj(
        "type"                 -> "PassThrough",
        "verificationSettings" -> verificationSettings.asJson
      )
    }

    "write a sign strategy" in {
      Sign(VerificationSettings(), HSAlgoSettings(256, "s")).asJson mustBe Json.obj(
        "type"                 -> "Sign",
        "verificationSettings" -> VerificationSettings().asJson,
        "algoSettings"         -> HSAlgoSettings(256, "s").asJson
      )
    }

    "write a transform strategy" in {
      Transform(VerificationSettings(), transformSettings, HSAlgoSettings(256, "s")).asJson mustBe Json.obj(
        "type"                 -> "Transform",
        "verificationSettings" -> VerificationSettings().asJson,
        "transformSettings"    -> transformSettings.asJson,
        "algoSettings"         -> HSAlgoSettings(256, "s").asJson
      )
    }

    "write a default token strategy" in {
      DefaultToken(token = Json.obj("sub" -> "anonymous")).asJson mustBe Json.obj(
        "type"                 -> "DefaultToken",
        "strict"               -> true,
        "token"                -> Json.obj("sub" -> "anonymous"),
        "verificationSettings" -> VerificationSettings().asJson
      )
    }

    "round trip every kind of strategy from its type" in {
      allStrategies.foreach(strategy => asJsonRoundTrip(read, strategy))
    }

    "round trip a sign and a transform strategy with every kind of algo settings" in {
      allAlgoSettings.foreach { algo =>
        asJsonRoundTrip(read, Sign(VerificationSettings(), algo))
        asJsonRoundTrip(read, Transform(VerificationSettings(), transformSettings, algo))
      }
    }

    "be named after its kind" in {
      allStrategies.map(_.name) mustBe Seq("pass_through", "sign", "transform", "default_token")
    }

    "read a default token strategy without strict as strict" in {
      fromJsonOk(read, Json.obj("type" -> "DefaultToken", "verificationSettings" -> Json.obj())) mustBe DefaultToken(
        strict = true,
        token = Json.obj(),
        verificationSettings = VerificationSettings()
      )
    }

    "read a default token given as a string as is" in {
      val back = fromJsonOk(read, Json.obj("type" -> "DefaultToken", "token" -> rawToken, "verificationSettings" -> Json.obj()))
      back mustBe DefaultToken(strict = true, token = JsString(rawToken))
    }

    "not read a strategy without verification settings, whatever its kind" in {
      fromJsonError(read, Json.obj("type" -> "PassThrough"))
      fromJsonError(read, Json.obj("type" -> "Sign", "algoSettings" -> HSAlgoSettings(256, "s").asJson))
      fromJsonError(
        read,
        Json.obj("type" -> "Transform", "transformSettings" -> transformSettings.asJson, "algoSettings" -> HSAlgoSettings(256, "s").asJson)
      )
      fromJsonError(read, Json.obj("type" -> "DefaultToken", "token" -> Json.obj()))
    }

    "not read a sign strategy without algo settings" in {
      fromJsonError(read, Json.obj("type" -> "Sign", "verificationSettings" -> Json.obj()))
    }

    "not read a transform strategy without transform settings" in {
      fromJsonError(
        read,
        Json.obj("type" -> "Transform", "verificationSettings" -> Json.obj(), "algoSettings" -> HSAlgoSettings(256, "s").asJson)
      )
    }

    "not read a strategy without type or of an unknown type" in {
      fromJsonError(read, Json.obj("verificationSettings" -> Json.obj()))
      fromJsonError(read, Json.obj("type" -> "Drop", "verificationSettings" -> Json.obj()))
    }
  }

  "GlobalJwtVerifier" should {

    val format = GlobalJwtVerifier._fmt

    val minimal = GlobalJwtVerifier(id = "jwt-1", name = "jwt 1", desc = "")

    val full = GlobalJwtVerifier(
      id = "jwt-full",
      name = "Full verifier",
      desc = "every field is set",
      strict = false,
      source = InCookie("jwt"),
      algoSettings = RSAlgoSettings(384, rsaPublicKey, Some("rsa-private-key")),
      strategy = Transform(verificationSettings, transformSettings, ESAlgoSettings(256, ecPublicKey, Some(ecPrivateKey))),
      tags = Seq("prod", "idp"),
      metadata = Map("owner" -> "security", "env" -> "prod"),
      location = customLocation
    )

    val minimalJson = Json.obj(
      "id"           -> "jwt-1",
      "name"         -> "jwt 1",
      "source"       -> InHeader("X-JWT-Token").asJson,
      "algoSettings" -> HSAlgoSettings(512, "secret").asJson,
      "strategy"     -> PassThrough(VerificationSettings()).asJson
    )

    "write a minimal verifier with its defaults" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"         -> defaultLocationJson,
        "type"         -> "global",
        "id"           -> "jwt-1",
        "name"         -> "jwt 1",
        "desc"         -> "",
        "strict"       -> true,
        "source"       -> Json.obj("type" -> "InHeader", "name" -> "X-JWT-Token", "remove" -> ""),
        "algoSettings" -> Json.obj("type" -> "HSAlgoSettings", "size" -> 512, "secret" -> "secret", "base64" -> false),
        "strategy"     -> Json.obj(
          "type"                 -> "PassThrough",
          "verificationSettings" -> Json.obj("fields" -> Json.obj(), "arrayFields" -> Json.obj())
        ),
        "metadata"     -> Json.obj(),
        "tags"         -> Json.arr()
      )
    }

    "write every field of a full verifier" in {
      format.writes(full) mustBe Json.obj(
        "_loc"         -> customLocationJson,
        "type"         -> "global",
        "id"           -> "jwt-full",
        "name"         -> "Full verifier",
        "desc"         -> "every field is set",
        "strict"       -> false,
        "source"       -> InCookie("jwt").asJson,
        "algoSettings" -> full.algoSettings.asJson,
        "strategy"     -> full.strategy.asJson,
        "metadata"     -> Json.obj("owner" -> "security", "env" -> "prod"),
        "tags"         -> Json.arr("prod", "idp")
      )
    }

    "round trip a minimal verifier" in {
      roundTrip(format, minimal)
    }

    "round trip a verifier where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a verifier with every kind of token location" in {
      Seq(InHeader("Authorization", "Bearer "), InQueryParam("access_token"), InCookie("jwt")).foreach { source =>
        roundTrip(format, minimal.copy(source = source))
      }
    }

    "round trip a verifier with every kind of algo settings" in {
      allAlgoSettings.foreach(algo => roundTrip(format, minimal.copy(algoSettings = algo)))
    }

    "round trip a verifier with every kind of strategy" in {
      allStrategies.foreach(strategy => roundTrip(format, minimal.copy(strategy = strategy)))
    }

    "keep the order of the tags" in {
      roundTrip(format, minimal.copy(tags = Seq("z", "a", "m"))).tags mustBe Seq("z", "a", "m")
    }

    "round trip a verifier in a custom location" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read a verifier without _loc in the default location" in {
      readOk(format, minimalJson).location mustBe EntityLocation()
    }

    "read a minimal json with the defaults" in {
      readOk(format, minimalJson) mustBe minimal.copy(desc = "--", strict = false)
    }

    "read a verifier without strict as lenient, unlike the constructor default" in {
      minimal.strict mustBe true
      readOk(format, minimalJson).strict mustBe false
    }

    "read fields of the wrong type as their defaults" in {
      val back = readOk(
        format,
        minimalJson ++ Json.obj(
          "desc"     -> 42,
          "strict"   -> "true",
          "tags"     -> "prod",
          "metadata" -> Json.obj("a" -> "b", "n" -> 1)
        )
      )
      back.desc mustBe "--"
      back.strict mustBe false
      back.tags mustBe empty
      back.metadata mustBe empty
    }

    "read a verifier whatever its type key" in {
      readOk(format, minimalJson ++ Json.obj("type" -> "local")) mustBe readOk(format, minimalJson)
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a verifier without id" in {
      readError(format, minimalJson - "id")
    }

    "not read an id that is not a string" in {
      readError(format, minimalJson ++ Json.obj("id" -> 1))
    }

    "not read a verifier without name" in {
      readError(format, minimalJson - "name")
    }

    "not read a verifier without source" in {
      readError(format, minimalJson - "source")
    }

    "not read a verifier without algo settings" in {
      readError(format, minimalJson - "algoSettings")
    }

    "not read a verifier without strategy" in {
      readError(format, minimalJson - "strategy")
    }

    "not read a verifier whose algo settings cannot be read" in {
      readError(format, minimalJson ++ Json.obj("algoSettings" -> Json.obj("type" -> "HSAlgoSettings", "size" -> 512)))
    }

    "not read a verifier whose strategy cannot be read" in {
      readError(format, minimalJson ++ Json.obj("strategy" -> Json.obj("type" -> "Sign", "verificationSettings" -> Json.obj())))
    }

    "be always enabled, never a reference, and global" in {
      full.enabled mustBe true
      full.isRef mustBe false
      (full: JwtVerifier).asGlobal mustBe full
    }

    "be asynchronous only when both its algo settings and the ones of its strategy are" in {
      val jwks = minimalJwks.copy(url = "https://never-fetched.oto.tools/b")
      minimal.isAsync mustBe false
      minimal.copy(strategy = Sign(VerificationSettings(), jwks)).isAsync mustBe false
      minimal.copy(algoSettings = jwks).isAsync mustBe false
      minimal.copy(algoSettings = jwks, strategy = Sign(VerificationSettings(), jwks)).isAsync mustBe true
      minimal.copy(algoSettings = jwks, strategy = Transform(VerificationSettings(), transformSettings, jwks)).isAsync mustBe true
      minimal.copy(algoSettings = jwks, strategy = DefaultToken(token = Json.obj())).isAsync mustBe false
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "jwt-full", "Full verifier", "every field is set", Seq("prod", "idp"), Map("owner" -> "security", "env" -> "prod"))
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
      full.asJson mustBe format.writes(full)
    }

    "be built from its json" in {
      GlobalJwtVerifier.fromJsons(full.json) mustBe full
      GlobalJwtVerifier.fromJson(full.json) mustBe Right(full)
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy GlobalJwtVerifier.fromJsons(minimalJson - "id")
      GlobalJwtVerifier.fromJson(minimalJson - "id").isLeft mustBe true
    }
  }

  "JwtVerifier" should {

    val format = JwtVerifier.fmt

    val local = LocalJwtVerifier(
      enabled = true,
      strict = false,
      excludedPatterns = Seq("/public/.*", "/health"),
      source = InHeader("Authorization", "Bearer "),
      algoSettings = KidAlgoSettings(onlyExposedCerts = true),
      strategy = Sign(verificationSettings, ESKPAlgoSettings(256, "cert-ec"))
    )

    val ref = RefJwtVerifier(ids = Seq("jwt-1", "jwt-2"), enabled = true, excludedPatterns = Seq("/public/.*"))

    "read a global verifier from its type" in {
      val global = GlobalJwtVerifier(id = "jwt-1", name = "jwt 1", desc = "d", location = customLocation)
      roundTrip(format, global) mustBe global
    }

    "write a local verifier" in {
      format.writes(local) mustBe Json.obj(
        "type"             -> "local",
        "enabled"          -> true,
        "strict"           -> false,
        "excludedPatterns" -> Json.arr("/public/.*", "/health"),
        "source"           -> local.source.asJson,
        "algoSettings"     -> local.algoSettings.asJson,
        "strategy"         -> local.strategy.asJson
      )
    }

    "round trip a local verifier" in {
      roundTrip(format, local)
      roundTrip(format, LocalJwtVerifier())
    }

    "read a local verifier without flags as disabled and lenient" in {
      val back = readOk(
        format,
        Json.obj(
          "type"         -> "local",
          "source"       -> InHeader("X-JWT-Token").asJson,
          "algoSettings" -> HSAlgoSettings(512, "secret").asJson,
          "strategy"     -> PassThrough(VerificationSettings()).asJson
        )
      )
      back mustBe LocalJwtVerifier(enabled = false, strict = false)
    }

    "write a reference verifier with its first id for compatibility" in {
      format.writes(ref) mustBe Json.obj(
        "type"             -> "ref",
        "ids"              -> Json.arr("jwt-1", "jwt-2"),
        "id"               -> "jwt-1",
        "enabled"          -> true,
        "excludedPatterns" -> Json.arr("/public/.*")
      )
    }

    "write a reference verifier without id" in {
      (format.writes(RefJwtVerifier()) \ "id").get mustBe JsNull
    }

    "round trip a reference verifier" in {
      roundTrip(format, ref)
      roundTrip(format, RefJwtVerifier())
    }

    "read the legacy single id of a reference verifier" in {
      readOk(format, Json.obj("type" -> "ref", "id" -> "jwt-1")) mustBe RefJwtVerifier(ids = Seq("jwt-1"))
    }

    "prefer the ids over the legacy single id" in {
      readOk(format, Json.obj("type" -> "ref", "ids" -> Json.arr("jwt-2"), "id" -> "jwt-1")).asInstanceOf[RefJwtVerifier].ids mustBe Seq(
        "jwt-2"
      )
    }

    "not read reference ids that are not strings" in {
      readError(format, Json.obj("type" -> "ref", "ids" -> Json.arr(1)))
    }

    "be a reference that has no settings of its own" in {
      ref.isRef mustBe true
      local.isRef mustBe false
      a[RuntimeException] must be thrownBy ref.strict
      a[RuntimeException] must be thrownBy ref.source
    }

    "not read a verifier without type or of an unknown type" in {
      readError(format, Json.obj("ids" -> Json.arr("jwt-1")))
      readError(format, Json.obj("type" -> "remote", "ids" -> Json.arr("jwt-1")))
    }

    "key its verdict cache with the verifier id and the hash of the whole token" in {
      JwtVerifier.verificationCacheKey("jwt-1", rawToken) mustBe s"jwt-1-${DigestUtils.sha256Hex(rawToken)}"
      JwtVerifier.verificationCacheKey("jwt-1", rawToken) must not be JwtVerifier.verificationCacheKey("jwt-1", rawToken + "x")
    }

    "cache the verification of claims without expression" in {
      val key   = s"spec-cached-${System.nanoTime()}"
      var calls = 0
      def verify: Try[com.auth0.jwt.interfaces.DecodedJWT] = { calls += 1; Failure(new RuntimeException("invalid")) }
      JwtVerifier.cachedVerification(key, verificationSettings)(verify)
      JwtVerifier.cachedVerification(key, verificationSettings)(verify)
      calls mustBe 1
    }

    "never cache the verification of claims with an expression" in {
      val key      = s"spec-not-cached-${System.nanoTime()}"
      val settings = VerificationSettings(fields = Map("aud" -> "${req.host}"))
      var calls    = 0
      def verify: Try[com.auth0.jwt.interfaces.DecodedJWT] = { calls += 1; Failure(new RuntimeException("invalid")) }
      JwtVerifier.cachedVerification(key, settings)(verify)
      JwtVerifier.cachedVerification(key, settings)(verify)
      calls mustBe 2
    }
  }
}
