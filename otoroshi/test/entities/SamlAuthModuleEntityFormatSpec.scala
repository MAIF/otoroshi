package entities

import otoroshi.auth.*
import otoroshi.models.*
import otoroshi.next.models.NgTlsConfig
import otoroshi.utils.JsonPathValidator
import play.api.libs.json.*

import scala.concurrent.duration.*

class SamlAuthModuleEntityFormatSpec extends EntityFormatSpec {

  "NameIDFormat" should {

    "read every name whatever its case" in {
      NameIDFormat("persistent") mustBe Some(NameIDFormat.Persistent)
      NameIDFormat("Transient") mustBe Some(NameIDFormat.Transient)
      NameIDFormat("KERBEROS") mustBe Some(NameIDFormat.Kerberos)
      NameIDFormat("entity") mustBe Some(NameIDFormat.Entity)
      NameIDFormat("emailAddress") mustBe Some(NameIDFormat.EmailAddress)
      NameIDFormat(" unspecified ") mustBe Some(NameIDFormat.Unspecified)
    }

    "read an unknown name as unspecified" in {
      NameIDFormat("foo") mustBe Some(NameIDFormat.Unspecified)
    }

    "read back its own name" in {
      Seq(
        NameIDFormat.Persistent,
        NameIDFormat.Transient,
        NameIDFormat.Kerberos,
        NameIDFormat.Entity,
        NameIDFormat.EmailAddress,
        NameIDFormat.Unspecified
      ).map(f => NameIDFormat(f.name)) mustBe Seq(
        Some(NameIDFormat.Persistent),
        Some(NameIDFormat.Transient),
        Some(NameIDFormat.Kerberos),
        Some(NameIDFormat.Entity),
        Some(NameIDFormat.EmailAddress),
        Some(NameIDFormat.Unspecified)
      )
    }

    "carry the saml urn of the format" in {
      NameIDFormat.EmailAddress.value mustBe "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress"
      NameIDFormat.Persistent.value mustBe "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent"
    }
  }

  "SAMLProtocolBinding" should {

    "read post and redirect whatever their case" in {
      SAMLProtocolBinding("POST") mustBe SAMLProtocolBinding.Post
      SAMLProtocolBinding("redirect") mustBe SAMLProtocolBinding.Redirect
    }

    "read the saml binding uris" in {
      SAMLProtocolBinding(SAMLProtocolBinding.Post.value) mustBe SAMLProtocolBinding.Post
      SAMLProtocolBinding(SAMLProtocolBinding.Redirect.value) mustBe SAMLProtocolBinding.Redirect
    }

    "read an unknown binding as redirect" in {
      SAMLProtocolBinding("artifact") mustBe SAMLProtocolBinding.Redirect
    }
  }

  "SAMLSignatureAlgorithm and SAMLCanocalizationMethod" should {

    "read every algorithm whatever its case" in {
      SAMLSignatureAlgorithm("RSA_SHA512") mustBe Some(SAMLSignatureAlgorithm.RSA_SHA512)
      SAMLSignatureAlgorithm("rsa_sha256") mustBe Some(SAMLSignatureAlgorithm.RSA_SHA256)
      SAMLSignatureAlgorithm("rsa_sha1") mustBe Some(SAMLSignatureAlgorithm.RSA_SHA1)
      SAMLSignatureAlgorithm("dsa_sha1") mustBe Some(SAMLSignatureAlgorithm.DSA_SHA1)
    }

    "throw on an unknown algorithm instead of returning none" in {
      a[MatchError] must be thrownBy SAMLSignatureAlgorithm("hmac")
    }

    "read every canonicalization method" in {
      SAMLCanocalizationMethod("exclusive") mustBe Some(SAMLCanocalizationMethod.Exclusive)
      SAMLCanocalizationMethod("WITH_COMMENTS") mustBe Some(SAMLCanocalizationMethod.ExclusiveWithComments)
    }

    "throw on an unknown canonicalization method instead of returning none" in {
      a[MatchError] must be thrownBy SAMLCanocalizationMethod("inclusive")
    }
  }

  "Credential" should {

    val format = Credential.fmt

    "write missing values as null" in {
      format.writes(Credential()) mustBe Json.obj(
        "certificate"            -> JsNull,
        "privateKey"             -> JsNull,
        "certId"                 -> JsNull,
        "useOtoroshiCertificate" -> false
      )
    }

    "round trip an empty credential" in {
      roundTrip(format, Credential())
    }

    "round trip a credential where every field is set" in {
      roundTrip(format, Credential(Some("-----BEGIN CERTIFICATE-----"), Some("-----BEGIN PRIVATE KEY-----"), Some("cert-1"), true))
    }

    "read an empty object as an empty credential" in {
      readOk(format, Json.obj()) mustBe Credential()
    }

    "ignore a certificate that is not a string" in {
      readOk(format, Json.obj("certificate" -> 1)).certificate mustBe None
    }
  }

  "SAMLCredentials" should {

    val format = SAMLCredentials.fmt

    val full = SAMLCredentials(
      signingKey = Credential(certId = Some("cert-1"), useOtoroshiCertificate = true),
      encryptionKey = Credential(certificate = Some("cert"), privateKey = Some("key")),
      signedDocuments = true,
      encryptedAssertions = true
    )

    "write its keys and its flags" in {
      format.writes(SAMLCredentials(Credential(), Credential())) mustBe Json.obj(
        "signingKey"          -> Credential.fmt.writes(Credential()),
        "encryptionKey"       -> Credential.fmt.writes(Credential()),
        "signedDocuments"     -> false,
        "encryptedAssertions" -> false
      )
    }

    "round trip credentials where every field is set" in {
      roundTrip(format, full)
    }

    "read missing flags as false" in {
      readOk(format, Json.obj("signingKey" -> Json.obj(), "encryptionKey" -> Json.obj())) mustBe SAMLCredentials(Credential(), Credential())
    }

    "not read credentials without signing key" in {
      readError(format, Json.obj("encryptionKey" -> Json.obj()))
    }

    "not read credentials without encryption key" in {
      readError(format, Json.obj("signingKey" -> Json.obj()))
    }
  }

  "SAMLSignature" should {

    val format = SAMLSignature.fmt

    "write the names of its algorithm and of its canonicalization method" in {
      format.writes(SAMLSignature(SAMLSignatureAlgorithm.RSA_SHA512, SAMLCanocalizationMethod.ExclusiveWithComments)) mustBe Json.obj(
        "algorithm"            -> "rsa_sha512",
        "canocalizationMethod" -> "with_comments"
      )
    }

    "round trip every algorithm" in {
      Seq(
        SAMLSignatureAlgorithm.RSA_SHA512,
        SAMLSignatureAlgorithm.RSA_SHA256,
        SAMLSignatureAlgorithm.RSA_SHA1,
        SAMLSignatureAlgorithm.DSA_SHA1
      ).map { algo =>
        roundTrip(format, SAMLSignature(algo, SAMLCanocalizationMethod.Exclusive)).algorithm
      } mustBe Seq(
        SAMLSignatureAlgorithm.RSA_SHA512,
        SAMLSignatureAlgorithm.RSA_SHA256,
        SAMLSignatureAlgorithm.RSA_SHA1,
        SAMLSignatureAlgorithm.DSA_SHA1
      )
    }

    "round trip a signature with comments" in {
      roundTrip(format, SAMLSignature(SAMLSignatureAlgorithm.RSA_SHA1, SAMLCanocalizationMethod.ExclusiveWithComments))
    }

    "read an empty object as rsa sha256 exclusive" in {
      readOk(format, Json.obj()) mustBe SAMLSignature(SAMLSignatureAlgorithm.RSA_SHA256, SAMLCanocalizationMethod.Exclusive)
    }

    "not read an unknown algorithm" in {
      readError(format, Json.obj("algorithm" -> "hmac"))
    }

    "not read an unknown canonicalization method" in {
      readError(format, Json.obj("canocalizationMethod" -> "inclusive"))
    }
  }

  "SamlAuthModuleConfig" should {

    val format = SamlAuthModuleConfig._fmt

    val minimal = SamlAuthModuleConfig(
      id = "auth_mod_saml",
      name = "Saml",
      desc = "a saml module",
      clientSideSessionEnabled = true,
      singleSignOnUrl = "https://idp.oto.tools/sso",
      singleLogoutUrl = None,
      tags = Seq.empty,
      metadata = Map.empty,
      issuer = "https://otoroshi.oto.tools",
      sessionCookieValues = SessionCookieValues()
    )

    val full = SamlAuthModuleConfig(
      id = "auth_mod_saml",
      name = "Saml",
      desc = "a saml module",
      clientSideSessionEnabled = false,
      sessionMaxAge = 1800,
      userValidators = Seq(JsonPathValidator("$.email", JsString("Contains(@oto.tools)"), Some("bad email"))),
      remoteValidators = Seq(RemoteUserValidatorSettings("https://validator.oto.tools", Map.empty, 1.second, NgTlsConfig())),
      singleSignOnUrl = "https://idp.oto.tools/sso",
      singleLogoutUrl = Some("https://idp.oto.tools/slo"),
      ssoProtocolBinding = SAMLProtocolBinding.Post,
      singleLogoutProtocolBinding = SAMLProtocolBinding.Post,
      credentials = SAMLCredentials(
        Credential(certId = Some("cert-1"), useOtoroshiCertificate = true),
        Credential(certificate = Some("cert"), privateKey = Some("key")),
        signedDocuments = true,
        encryptedAssertions = true
      ),
      signature = SAMLSignature(SAMLSignatureAlgorithm.RSA_SHA512, SAMLCanocalizationMethod.ExclusiveWithComments),
      nameIDFormat = NameIDFormat.EmailAddress,
      tags = Seq("sso"),
      metadata = Map("owner" -> "security"),
      extraMetadata = Json.obj("provider" -> "adfs"),
      issuer = "https://otoroshi.oto.tools",
      location = customLocation,
      validatingCertificates = List("MIIC...1", "MIIC...2"),
      validateSignature = false,
      validateAssertions = false,
      validateConditions = false,
      clockSkewSeconds = 5L,
      usedNameIDAsEmail = false,
      emailAttributeName = Some("mail"),
      sessionCookieValues = SessionCookieValues(httpOnly = false, secure = true),
      adminEntityValidatorsOverride = Map("john@oto.tools" -> Map("route" -> Seq(JsonPathValidator("$.id", JsString("x"))))),
      allowedUsers = Seq("john@oto.tools"),
      deniedUsers = Seq("Wildcard(*@external.com)")
    )

    "validate everything it receives by default" in {
      minimal.validateSignature mustBe true
      minimal.validateAssertions mustBe true
      minimal.validateConditions mustBe true
      minimal.clockSkewSeconds mustBe 60L
    }

    "write a minimal module" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"                          -> defaultLocationJson,
        "type"                          -> "saml",
        "id"                            -> "auth_mod_saml",
        "name"                          -> "Saml",
        "desc"                          -> "a saml module",
        "sessionMaxAge"                 -> 86400,
        "clientSideSessionEnabled"      -> true,
        "userValidators"                -> Json.arr(),
        "remoteValidators"              -> Json.arr(),
        "allowedUsers"                  -> Json.arr(),
        "deniedUsers"                   -> Json.arr(),
        "singleSignOnUrl"               -> "https://idp.oto.tools/sso",
        "singleLogoutUrl"               -> JsNull,
        "credentials"                   -> SAMLCredentials.fmt.writes(SAMLCredentials(Credential(), Credential())),
        "tags"                          -> Json.arr(),
        "metadata"                      -> Json.obj(),
        "extraMetadata"                 -> Json.obj(),
        "sessionCookieValues"           -> Json.obj("httpOnly" -> true, "secure" -> true),
        "issuer"                        -> "https://otoroshi.oto.tools",
        "validatingCertificates"        -> Json.arr(),
        "validateSignature"             -> true,
        "validateAssertions"            -> true,
        "validateConditions"            -> true,
        "clockSkewSeconds"              -> 60,
        "signature"                     -> Json.obj("algorithm" -> "rsa_sha256", "canocalizationMethod" -> "exclusive"),
        "nameIDFormat"                  -> "unspecified",
        "ssoProtocolBinding"            -> "redirect",
        "singleLogoutProtocolBinding"   -> "redirect",
        "usedNameIDAsEmail"             -> true,
        "emailAttributeName"            -> "Email",
        "adminEntityValidatorsOverride" -> Json.obj()
      )
    }

    "write the names of its bindings and of its name id format" in {
      val json = written(format, full)
      (json \ "ssoProtocolBinding").as[String] mustBe "post"
      (json \ "singleLogoutProtocolBinding").as[String] mustBe "post"
      (json \ "nameIDFormat").as[String] mustBe "emailAddress"
    }

    "round trip a minimal module" in {
      roundTrip(format, minimal)
    }

    "round trip a module where every field is set, with every validation disabled" in {
      val back = roundTrip(format, full)
      back.location mustBe customLocation
      back.validateSignature mustBe false
      back.validateAssertions mustBe false
      back.validateConditions mustBe false
    }

    "round trip a module in another tenant and teams" in {
      roundTrip(format, minimal.copy(location = customLocation)).location mustBe customLocation
    }

    "read its location from _loc" in {
      readOk(format, written(format, minimal) ++ Json.obj("_loc" -> customLocationJson)).location mustBe customLocation
    }

    "keep its location through a write and a read, as the users it logs in get their rights from it" in {
      val back = readOk(format, format.writes(minimal.copy(location = customLocation)))
      back.location.tenant mustBe customLocation.tenant
      back.location.teams mustBe customLocation.teams
    }

    "round trip a module without email attribute name" in {
      roundTrip(format, minimal.copy(emailAttributeName = None)).emailAttributeName mustBe None
    }

    "round trip every name id format" in {
      Seq(
        NameIDFormat.Persistent,
        NameIDFormat.Transient,
        NameIDFormat.Kerberos,
        NameIDFormat.Entity,
        NameIDFormat.EmailAddress,
        NameIDFormat.Unspecified
      ).foreach { f =>
        roundTrip(format, minimal.copy(nameIDFormat = f)).nameIDFormat mustBe f
      }
    }

    "read the default location without _loc" in {
      readOk(format, written(format, minimal) - "_loc").location mustBe EntityLocation()
    }

    val mandatory = Json.obj(
      "id"                 -> "auth_mod_saml",
      "name"               -> "Saml",
      "singleSignOnUrl"    -> "https://idp.oto.tools/sso",
      "credentials"        -> Json.obj("signingKey" -> Json.obj(), "encryptionKey" -> Json.obj()),
      "issuer"             -> "https://otoroshi.oto.tools",
      "validateSignature"  -> true,
      "validateAssertions" -> true,
      "signature"          -> Json.obj()
    )

    "read the defaults of a module with only its mandatory fields" in {
      readOk(format, mandatory) mustBe minimal.copy(
        desc = "--",
        emailAttributeName = None
      )
    }

    "read an unspecified name id format when it is missing, like a new module" in {
      minimal.nameIDFormat mustBe NameIDFormat.Unspecified
      readOk(format, mandatory).nameIDFormat mustBe NameIDFormat.Unspecified
    }

    "read no email attribute name when it is missing, the Email attribute being read then" in {
      minimal.emailAttributeName mustBe Some("Email")
      readOk(format, mandatory).emailAttributeName mustBe None
    }

    "not read a module without one of its mandatory fields" in {
      Seq("id", "name", "singleSignOnUrl", "credentials", "issuer", "validateSignature", "validateAssertions", "signature")
        .foreach { key =>
          withClue(s"without $key: ") {
            readError(format, mandatory - key)
          }
        }
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a validation flag that is not a boolean" in {
      readError(format, mandatory ++ Json.obj("validateSignature" -> "false"))
    }

    "validate the conditions when the flag is missing or not a boolean" in {
      readOk(format, mandatory ++ Json.obj("validateConditions" -> "false")).validateConditions mustBe true
    }

    "read an empty single logout url as missing" in {
      readOk(format, mandatory ++ Json.obj("singleLogoutUrl" -> "")).singleLogoutUrl mustBe None
    }

    "read an unknown name id format as unspecified" in {
      readOk(format, mandatory ++ Json.obj("nameIDFormat" -> "foo")).nameIDFormat mustBe NameIDFormat.Unspecified
    }

    "read the bindings from their saml uris" in {
      val back = readOk(
        format,
        mandatory ++ Json.obj(
          "ssoProtocolBinding"          -> SAMLProtocolBinding.Post.value,
          "singleLogoutProtocolBinding" -> SAMLProtocolBinding.Redirect.value
        )
      )
      back.ssoProtocolBinding mustBe SAMLProtocolBinding.Post
      back.singleLogoutProtocolBinding mustBe SAMLProtocolBinding.Redirect
    }

    "not read a module with an unknown signature algorithm" in {
      readError(format, mandatory ++ Json.obj("signature" -> Json.obj("algorithm" -> "hmac")))
    }

    "ignore validating certificates given as a single string" in {
      readOk(format, mandatory ++ Json.obj("validatingCertificates" -> "MIIC")).validatingCertificates mustBe empty
    }

    "fall back to the default clock skew when it is not a number" in {
      readOk(format, mandatory ++ Json.obj("clockSkewSeconds" -> "5")).clockSkewSeconds mustBe 60L
    }

    "not read a module whose admin entity validators override of a user is not an object" in {
      readError(format, mandatory ++ Json.obj("adminEntityValidatorsOverride" -> Json.obj("john" -> "x")))
    }

    "give the read error as a left" in {
      SamlAuthModuleConfig.fromJson(Json.obj("id" -> "a")).isLeft mustBe true
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(full, "auth_mod_saml", "Saml", "a saml module", Seq("sso"), Map("owner" -> "security"))
    }

    "describe its type" in {
      full.`type` mustBe "saml"
      full.humanName mustBe "SAML v2 provider"
      full.form mustBe None
    }

    "name its cookie after its id" in {
      full.cookieSuffix(null) mustBe "saml-auth-auth_mod_saml"
    }

    "change its location only with withLocation" in {
      minimal.withLocation(customLocation) mustBe minimal.copy(location = customLocation)
    }
  }
}
