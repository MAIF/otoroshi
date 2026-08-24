package functional

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.yubico.webauthn.*
import com.yubico.webauthn.data.*
import org.joda.time.DateTime
import org.scalatest.OptionValues
import otoroshi.auth.{LocalCredentialRepository, WebAuthnCredential, WebAuthnSupport}
import otoroshi.models.{EntityLocation, OtoroshiAdminType, UserRights, WebAuthnOtoroshiAdmin}
import play.api.libs.json.*

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator, MessageDigest, SecureRandom, Signature}
import java.util.Base64
import scala.jdk.CollectionConverters.*

/**
 * A minimal software authenticator, enough to run complete webauthn ceremonies against a `RelyingParty` without any
 * hardware key: it builds the attestation object of a registration ("none" attestation) and signs the assertions of
 * the authentications with an ES256 key.
 */
class SoftwareAuthenticator(rpId: String, origin: String) {

  private val encoder                  = Base64.getUrlEncoder.withoutPadding()
  private val cbor                     = new ObjectMapper(new CBORFactory())
  private val keyPair: KeyPair         = {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(new ECGenParameterSpec("secp256r1"))
    generator.generateKeyPair()
  }
  private var counter: Int             = 0
  val credentialId: ByteArray          = {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    new ByteArray(bytes)
  }

  private def sha256(bytes: Array[Byte]): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(bytes)

  private def coordinate(value: BigInteger): Array[Byte] = {
    val bytes  = value.toByteArray
    val result = new Array[Byte](32)
    if (bytes.length > 32) System.arraycopy(bytes, bytes.length - 32, result, 0, 32)
    else System.arraycopy(bytes, 0, result, 32 - bytes.length, bytes.length)
    result
  }

  private def publicKeyCose: Array[Byte] = {
    val point = keyPair.getPublic.asInstanceOf[ECPublicKey].getW
    val raw   = Array[Byte](0x04) ++ coordinate(point.getAffineX) ++ coordinate(point.getAffineY)
    CredentialRecord.cosePublicKeyFromEs256Raw(new ByteArray(raw)).getBytes
  }

  private def clientData(typ: String, challenge: ByteArray): Array[Byte] = Json
    .stringify(
      Json.obj(
        "type"        -> typ,
        "challenge"   -> challenge.getBase64Url,
        "origin"      -> origin,
        "crossOrigin" -> false
      )
    )
    .getBytes(StandardCharsets.UTF_8)

  private def authenticatorData(flags: Byte, attestedCredentialData: Array[Byte]): Array[Byte] = {
    counter = counter + 1
    val count = Array[Byte]((counter >> 24).toByte, (counter >> 16).toByte, (counter >> 8).toByte, counter.toByte)
    sha256(rpId.getBytes(StandardCharsets.UTF_8)) ++ Array(flags) ++ count ++ attestedCredentialData
  }

  def register(options: PublicKeyCredentialCreationOptions): String = {
    val id                = credentialId.getBytes
    val attestedData      = new Array[Byte](16) ++                                 // aaguid
      Array[Byte]((id.length >> 8).toByte, id.length.toByte) ++ id ++ publicKeyCose
    val authData          = authenticatorData(0x45, attestedData)                  // user present, user verified, attested data
    val attestationObject = cbor.writeValueAsBytes(
      Map[String, AnyRef](
        "fmt"      -> "none",
        "attStmt"  -> Map.empty[String, String].asJava,
        "authData" -> authData
      ).asJava
    )
    Json.stringify(
      Json.obj(
        "type"                   -> "public-key",
        "id"                     -> credentialId.getBase64Url,
        "response"               -> Json.obj(
          "attestationObject" -> encoder.encodeToString(attestationObject),
          "clientDataJSON"    -> encoder.encodeToString(clientData("webauthn.create", options.getChallenge))
        ),
        "clientExtensionResults" -> Json.obj()
      )
    )
  }

  def authenticate(request: AssertionRequest, userHandle: ByteArray): String = {
    val authData  = authenticatorData(0x05, Array.empty)                           // user present, user verified
    val data      = clientData("webauthn.get", request.getPublicKeyCredentialRequestOptions.getChallenge)
    val signer    = Signature.getInstance("SHA256withECDSA")
    signer.initSign(keyPair.getPrivate)
    signer.update(authData ++ sha256(data))
    val signature = signer.sign()
    Json.stringify(
      Json.obj(
        "type"                   -> "public-key",
        "id"                     -> credentialId.getBase64Url,
        "response"               -> Json.obj(
          "authenticatorData" -> encoder.encodeToString(authData),
          "clientDataJSON"    -> encoder.encodeToString(data),
          "signature"         -> encoder.encodeToString(signature),
          "userHandle"        -> userHandle.getBase64Url
        ),
        "clientExtensionResults" -> Json.obj()
      )
    )
  }
}

class WebAuthnSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers with OptionValues {

  val origin   = "https://otoroshi.example.com"
  val rpId     = "example.com"
  val username = "admin@otoroshi.io"
  val handle   = {
    val bytes = new Array[Byte](64)
    new SecureRandom().nextBytes(bytes)
    new ByteArray(bytes)
  }

  def user(credentials: Map[String, JsValue], userHandle: String = handle.getBase64Url): WebAuthnOtoroshiAdmin =
    WebAuthnOtoroshiAdmin(
      username = username,
      password = "password",
      label = "admin",
      handle = userHandle,
      credentials = credentials,
      createdAt = DateTime.now(),
      typ = OtoroshiAdminType.WebAuthnAdmin,
      metadata = Map.empty,
      rights = UserRights.superAdmin,
      location = EntityLocation(),
      adminEntityValidators = Map.empty
    )

  // what otoroshi stored when it was using webauthn-server-core 1.7.0: the jackson serialization of the
  // RegistrationResult of that version, without signature counter, aaguid nor transports
  def legacy170Format(credential: WebAuthnCredential): JsValue = Json.obj(
    "keyId"              -> Json.obj(
      "id"   -> credential.credentialId.getBase64Url,
      "type" -> "public-key"
    ),
    "attestationTrusted" -> false,
    "attestationType"    -> "BASIC",
    "publicKeyCose"      -> credential.publicKeyCose.getBase64Url,
    "warnings"           -> Json.arr()
  )

  // what otoroshi stored when it was using webauthn-server-core 2.1.0
  def legacy210Format(credential: WebAuthnCredential): JsValue = Json.obj(
    "keyId"                         -> Json.obj(
      "id"         -> credential.credentialId.getBase64Url,
      "type"       -> "public-key",
      "transports" -> Json.arr()
    ),
    "aaguid"                        -> "AAAAAAAAAAAAAAAAAAAAAA",
    "publicKeyCose"                 -> credential.publicKeyCose.getBase64Url,
    "signatureCount"                -> 1,
    "userVerified"                  -> true,
    "attestationTrusted"            -> false,
    "attestationType"               -> "NONE",
    "attestationTrustPath"          -> Json.arr(),
    "clientExtensionOutputs"        -> Json.obj("credProps" -> Json.obj()),
    "authenticatorExtensionOutputs" -> Json.obj()
  )

  // registers a credential the way `WebAuthnSupport` does, going through the json serialization of the request as it
  // is stored in the datastore between the two calls of the ceremony
  def register(users: Seq[WebAuthnOtoroshiAdmin], authenticator: SoftwareAuthenticator): WebAuthnCredential = {
    val rp      = WebAuthnSupport.relyingParty(users, origin)
    val options = rp.startRegistration(
      StartRegistrationOptions.builder
        .user(UserIdentity.builder.name(username).displayName("admin").id(handle).build)
        .build
    )
    val request = PublicKeyCredentialCreationOptions.fromJson(options.toJson)
    val result  = rp.finishRegistration(
      FinishRegistrationOptions
        .builder()
        .request(request)
        .response(PublicKeyCredential.parseRegistrationResponseJson(authenticator.register(request)))
        .build()
    )
    WebAuthnCredential.fromRegistrationResult(result)
  }

  def authenticate(users: Seq[WebAuthnOtoroshiAdmin], authenticator: SoftwareAuthenticator): AssertionResult = {
    val rp        = WebAuthnSupport.relyingParty(users, origin)
    val assertion = rp.startAssertion(StartAssertionOptions.builder.username(java.util.Optional.of(username)).build)
    val request   = AssertionRequest.fromJson(assertion.toJson)
    rp.finishAssertion(
      FinishAssertionOptions
        .builder()
        .request(request)
        .response(PublicKeyCredential.parseAssertionResponseJson(authenticator.authenticate(request, handle)))
        .build()
    )
  }

  "WebAuthnCredential" should {

    "read the credentials registered with webauthn-server-core 1.7.0" in {
      val authenticator = new SoftwareAuthenticator(rpId, origin)
      val credential    = register(Seq.empty, authenticator)
      val read          = WebAuthnCredential.fromJson(legacy170Format(credential)).get
      read.credentialId mustBe credential.credentialId
      read.publicKeyCose mustBe credential.publicKeyCose
      read.signatureCount mustBe 0L
      read.transports mustBe Set.empty
      read.aaguid mustBe None
    }

    "read the credentials registered with webauthn-server-core 2.1.0" in {
      val authenticator = new SoftwareAuthenticator(rpId, origin)
      val credential    = register(Seq.empty, authenticator)
      val read          = WebAuthnCredential.fromJson(legacy210Format(credential)).get
      read.credentialId mustBe credential.credentialId
      read.publicKeyCose mustBe credential.publicKeyCose
      read.signatureCount mustBe 1L
    }

    "read back the credentials it writes" in {
      val authenticator = new SoftwareAuthenticator(rpId, origin)
      val credential    = register(Seq.empty, authenticator)
      WebAuthnCredential.fromJson(credential.json).get mustBe credential
    }

    "not read a credential without a public key" in {
      WebAuthnCredential.fromJson(Json.obj("keyId" -> Json.obj("id" -> "AAAA"))).isFailure mustBe true
    }
  }

  "LocalCredentialRepository" should {

    "find the credentials of an user" in {
      val authenticator = new SoftwareAuthenticator(rpId, origin)
      val credential    = register(Seq.empty, authenticator)
      val repository    = new LocalCredentialRepository(Seq(user(Map(credential.id -> credential.json))))
      repository.getCredentialIdsForUsername(username).asScala.map(_.getId).mustBe(Set(credential.credentialId))
      repository.getCredentialIdsForUsername("unknown@otoroshi.io").asScala mustBe empty
      repository.getUserHandleForUsername(username).get() mustBe handle
      repository.getUserHandleForUsername("unknown@otoroshi.io").isPresent mustBe false
      repository.getUsernameForUserHandle(handle).get() mustBe username
      repository.lookup(credential.credentialId, handle).get().getPublicKeyCose mustBe credential.publicKeyCose
      repository.lookup(credential.credentialId, new ByteArray(new Array[Byte](64))).isPresent mustBe false
      repository.lookupAll(credential.credentialId).asScala.map(_.getCredentialId).mustBe(Set(credential.credentialId))
    }

    "support the handles stored with base64 padding" in {
      val authenticator = new SoftwareAuthenticator(rpId, origin)
      val credential    = register(Seq.empty, authenticator)
      val padded        = Base64.getUrlEncoder.encodeToString(handle.getBytes)
      val repository    =
        new LocalCredentialRepository(Seq(user(Map(credential.id -> credential.json), userHandle = padded)))
      repository.getUserHandleForUsername(username).get() mustBe handle
      repository.lookup(credential.credentialId, handle).isPresent mustBe true
    }

    "ignore the credentials it cannot read" in {
      val repository = new LocalCredentialRepository(Seq(user(Map("bad" -> Json.obj("keyId" -> "nope")))))
      repository.getCredentialIdsForUsername(username).asScala mustBe empty
    }
  }

  "WebAuthn ceremonies" should {

    "authenticate an user with a credential registered by the current version" in {
      val authenticator = new SoftwareAuthenticator(rpId, origin)
      val credential    = register(Seq.empty, authenticator)
      val users         = Seq(user(Map(credential.id -> credential.json)))
      val result        = authenticate(users, authenticator)
      result.isSuccess mustBe true
      result.getUsername mustBe username
      result.isSignatureCounterValid mustBe true
      result.getSignatureCount must be > credential.signatureCount
    }

    "authenticate an user with a credential registered with webauthn-server-core 1.7.0" in {
      val authenticator = new SoftwareAuthenticator(rpId, origin)
      val credential    = register(Seq.empty, authenticator)
      val users         = Seq(user(Map(credential.id -> legacy170Format(credential))))
      authenticate(users, authenticator).isSuccess mustBe true
    }

    "authenticate an user with a credential registered with webauthn-server-core 2.1.0" in {
      val authenticator = new SoftwareAuthenticator(rpId, origin)
      val credential    = register(Seq.empty, authenticator)
      val users         = Seq(user(Map(credential.id -> legacy210Format(credential))))
      authenticate(users, authenticator).isSuccess mustBe true
    }

    "not authenticate an user with the key of another device" in {
      val authenticator = new SoftwareAuthenticator(rpId, origin)
      val credential    = register(Seq.empty, authenticator)
      val users         = Seq(user(Map(credential.id -> credential.json)))
      an[Exception] must be thrownBy authenticate(users, new SoftwareAuthenticator(rpId, origin))
    }

    "exclude the credentials already registered by an user" in {
      val authenticator = new SoftwareAuthenticator(rpId, origin)
      val credential    = register(Seq.empty, authenticator)
      val users         = Seq(user(Map(credential.id -> credential.json)))
      val options       = WebAuthnSupport
        .relyingParty(users, origin)
        .startRegistration(
          StartRegistrationOptions.builder
            .user(UserIdentity.builder.name(username).displayName("admin").id(handle).build)
            .build
        )
      options.getExcludeCredentials.get().asScala.map(_.getId).mustBe(Set(credential.credentialId))
    }
  }
}
