package otoroshi.auth

import com.yubico.webauthn.*
import com.yubico.webauthn.data.*
import org.apache.pekko.http.scaladsl.model.Uri
import otoroshi.env.Env
import otoroshi.models.WebAuthnOtoroshiAdmin
import otoroshi.security.IdGenerator
import play.api.Logger
import play.api.libs.json.*

import java.security.SecureRandom
import java.util
import java.util.Optional
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * The otoroshi representation of a registered webauthn credential.
 *
 * Credentials are stored as json in `WebAuthnOtoroshiAdmin.credentials` and in `WebAuthnDetails.credentials`.
 * Historically otoroshi stored the jackson serialization of `com.yubico.webauthn.RegistrationResult` there, which is an
 * internal model of the webauthn library: each time the library changed that model, previously registered credentials
 * became unreadable and users were locked out. This type is the otoroshi model, read and written explicitly, and it is
 * able to read every format otoroshi has written so far (webauthn-server-core 1.7.0 and 2.x results).
 */
case class WebAuthnCredential(
    credentialId: ByteArray,
    publicKeyCose: ByteArray,
    signatureCount: Long = 0L,
    transports: Set[AuthenticatorTransport] = Set.empty,
    aaguid: Option[ByteArray] = None,
    backupEligible: Option[Boolean] = None,
    backedUp: Option[Boolean] = None,
    discoverable: Option[Boolean] = None,
    attestationType: Option[String] = None
) {

  // the key used to store this credential in the credentials map of an user
  def id: String = credentialId.getBase64Url

  def json: JsObject = Json.obj(
    "keyId"          -> Json.obj(
      "id"         -> credentialId.getBase64Url,
      "type"       -> PublicKeyCredentialType.PUBLIC_KEY.getId,
      "transports" -> JsArray(transports.toSeq.map(_.getId).sorted.map(JsString.apply))
    ),
    "publicKeyCose"  -> publicKeyCose.getBase64Url,
    "signatureCount" -> signatureCount
  ) ++ JsObject(
    Seq(
      aaguid.map(v => "aaguid"                   -> JsString(v.getBase64Url)),
      backupEligible.map(v => "backupEligible"   -> JsBoolean(v)),
      backedUp.map(v => "backedUp"               -> JsBoolean(v)),
      discoverable.map(v => "discoverable"       -> JsBoolean(v)),
      attestationType.map(v => "attestationType" -> JsString(v))
    ).flatten
  )

  def descriptor: PublicKeyCredentialDescriptor = {
    val builder = PublicKeyCredentialDescriptor.builder().id(credentialId)
    (if (transports.isEmpty) builder else builder.transports(transports.asJava)).build()
  }

  // the transports and backup state apis of the webauthn library are flagged as deprecated because they are
  // experimental, but the library javadoc tells us to store and provide them, so we do
  @nowarn("cat=deprecation")
  def registeredCredential(userHandle: ByteArray): RegisteredCredential = {
    val builder        = RegisteredCredential
      .builder()
      .credentialId(credentialId)
      .userHandle(userHandle)
      .publicKeyCose(publicKeyCose)
      .signatureCount(signatureCount)
    val withTransports = if (transports.isEmpty) builder else builder.transports(transports.asJava)
    val withEligible   =
      backupEligible.map(v => withTransports.backupEligible(java.lang.Boolean.valueOf(v))).getOrElse(withTransports)
    val withState      =
      backedUp.map(v => withEligible.backupState(java.lang.Boolean.valueOf(v))).getOrElse(withEligible)
    withState.build()
  }
}

object WebAuthnCredential {

  @nowarn("cat=deprecation")
  def fromRegistrationResult(result: RegistrationResult): WebAuthnCredential = WebAuthnCredential(
    credentialId = result.getKeyId.getId,
    publicKeyCose = result.getPublicKeyCose,
    signatureCount = result.getSignatureCount,
    transports = result.getKeyId.getTransports.toScala.map(_.asScala.toSet).getOrElse(Set.empty),
    aaguid = Option(result.getAaguid),
    backupEligible = Some(result.isBackupEligible),
    backedUp = Some(result.isBackedUp),
    discoverable = result.isDiscoverable.toScala.map(_.booleanValue()),
    attestationType = Option(result.getAttestationType).map(_.name())
  )

  def fromJson(json: JsValue): Try[WebAuthnCredential] = Try {
    WebAuthnCredential(
      credentialId = ByteArray.fromBase64Url((json \ "keyId" \ "id").as[String]),
      publicKeyCose = ByteArray.fromBase64Url((json \ "publicKeyCose").as[String]),
      // credentials registered with webauthn-server-core 1.7.0 have no signature counter, 0 disables the check
      signatureCount = (json \ "signatureCount").asOpt[Long].getOrElse(0L),
      transports = (json \ "keyId" \ "transports")
        .asOpt[Seq[String]]
        .getOrElse(Seq.empty)
        .map(AuthenticatorTransport.of)
        .toSet,
      aaguid = (json \ "aaguid").asOpt[String].flatMap(v => Try(ByteArray.fromBase64Url(v)).toOption),
      backupEligible = (json \ "backupEligible").asOpt[Boolean],
      backedUp = (json \ "backedUp").asOpt[Boolean],
      discoverable = (json \ "discoverable").asOpt[Boolean],
      attestationType = (json \ "attestationType").asOpt[String]
    )
  }
}

/**
 * The result of a successful webauthn registration ceremony: the credential itself and the handle of the user it has
 * been registered for.
 */
case class WebAuthnRegistration(handle: String, credential: WebAuthnCredential)

/**
 * The webauthn library view of the otoroshi users able to log in with a webauthn device. Users are passed in
 * (from the webauthn admins datastore or from the users of an auth. module) as this repository is created for
 * each ceremony.
 */
class LocalCredentialRepository(users: Seq[WebAuthnOtoroshiAdmin]) extends CredentialRepository {

  private def handleOf(user: WebAuthnOtoroshiAdmin): Option[ByteArray] =
    Try(ByteArray.fromBase64Url(user.handle)).toOption

  private lazy val credentials: Seq[(String, ByteArray, WebAuthnCredential)] = users.flatMap { user =>
    handleOf(user).toSeq.flatMap { handle =>
      user.credentials.values.toSeq
        .flatMap(credential => WebAuthnCredential.fromJson(credential).toOption)
        .map(credential => (user.username, handle, credential))
    }
  }

  override def getCredentialIdsForUsername(username: String): util.Set[PublicKeyCredentialDescriptor] = {
    credentials.collect { case (name, _, credential) if name == username => credential.descriptor }.toSet.asJava
  }

  override def getUserHandleForUsername(username: String): Optional[ByteArray] = {
    users.find(_.username == username).flatMap(handleOf).toJava
  }

  override def getUsernameForUserHandle(userHandle: ByteArray): Optional[String] = {
    users.find(user => handleOf(user).contains(userHandle)).map(_.username).toJava
  }

  override def lookup(credentialId: ByteArray, userHandle: ByteArray): Optional[RegisteredCredential] = {
    credentials
      .find { case (_, handle, credential) => handle == userHandle && credential.credentialId == credentialId }
      .map { case (_, handle, credential) => credential.registeredCredential(handle) }
      .toJava
  }

  override def lookupAll(credentialId: ByteArray): util.Set[RegisteredCredential] = {
    credentials.collect {
      case (_, handle, credential) if credential.credentialId == credentialId => credential.registeredCredential(handle)
    }.toSet.asJava
  }
}

/**
 * The webauthn ceremonies (registration and authentication), shared between the otoroshi admins (`U2FController`) and
 * the users of a basic auth. module (`BasicAuthModule`). Everything that is not webauthn itself (password checks,
 * user lookup, session creation, etc.) stays in the callers.
 */
object WebAuthnSupport {

  private val logger = Logger("otoroshi-webauthn")
  private val random = new SecureRandom()

  // the rp id is the domain of the origin the ceremony is done from, so that a key registered on one otoroshi
  // subdomain can be used on the others
  def relyingParty(users: Seq[WebAuthnOtoroshiAdmin], origin: String): RelyingParty = {
    val originHost           = Uri(origin).authority.host.address()
    val originDomain: String = originHost.split("\\.").toList.reverse match {
      case tld :: domain :: _ => s"$domain.$tld"
      case value              => value.mkString(".")
    }
    RelyingParty.builder
      .identity(RelyingPartyIdentity.builder.id(originDomain).name("Otoroshi").build)
      .credentialRepository(new LocalCredentialRepository(users))
      .origins(Set(origin, originDomain).asJava)
      .build
  }

  // the ceremony options, as stored by `registrationStart` / `loginStart`
  private def pendingRequest(rawRequest: JsValue): String = Json.stringify((rawRequest \ "request").as[JsValue])

  /**
   * Reads the pending ceremony stored by `registrationStart` / `loginStart` and consumes it, so that a challenge
   * cannot be used twice.
   */
  private def consumeRequest(
      requestId: String
  )(using env: Env, ec: ExecutionContext): Future[Option[JsValue]] = {
    env.datastores.webAuthnRegistrationsDataStore.getRegistrationRequest(requestId).flatMap {
      case None          => Future.successful(None)
      case Some(request) =>
        env.datastores.webAuthnRegistrationsDataStore.deleteRegistrationRequest(requestId).map(_ => Some(request))
    }
  }

  /**
   * Starts a registration ceremony. `existingHandle` must be the handle of the user when it already has one, as an user
   * has one handle for all its credentials: generating a new one would make its previous credentials unusable.
   */
  def registrationStart(
      users: Seq[WebAuthnOtoroshiAdmin],
      username: String,
      label: String,
      origin: String,
      existingHandle: Option[String] = None
  )(using env: Env, ec: ExecutionContext): Future[JsValue] = {
    val handle                                      = existingHandle
      .flatMap(h => Try(ByteArray.fromBase64Url(h)).toOption)
      .getOrElse {
        val userHandle = new Array[Byte](64)
        random.nextBytes(userHandle)
        new ByteArray(userHandle)
      }
    val request: PublicKeyCredentialCreationOptions = relyingParty(users, origin).startRegistration(
      StartRegistrationOptions.builder
        .user(
          UserIdentity.builder
            .name(username)
            .displayName(label)
            .id(handle)
            .build
        )
        .build
    )
    val requestId                                   = IdGenerator.token(32)
    val finalRequest                                = Json.obj(
      "requestId" -> requestId,
      "request"   -> Json.parse(request.toJson),
      "username"  -> username,
      "label"     -> label,
      "handle"    -> handle.getBase64Url
    )
    env.datastores.webAuthnRegistrationsDataStore.setRegistrationRequest(requestId, finalRequest).map(_ => finalRequest)
  }

  def registrationFinish(
      users: Seq[WebAuthnOtoroshiAdmin],
      body: JsValue
  )(using env: Env, ec: ExecutionContext): Future[Either[String, WebAuthnRegistration]] = {
    val origin    = (body \ "otoroshi" \ "origin").as[String]
    val requestId = (body \ "requestId").as[String]
    val response  = Json.stringify((body \ "webauthn").as[JsValue])
    consumeRequest(requestId).map {
      case None             => Left("bad request")
      case Some(rawRequest) =>
        Try {
          val request = PublicKeyCredentialCreationOptions.fromJson(pendingRequest(rawRequest))
          val pkc     = PublicKeyCredential.parseRegistrationResponseJson(response)
          relyingParty(users, origin).finishRegistration(
            FinishRegistrationOptions
              .builder()
              .request(request)
              .response(pkc)
              .build()
          )
        } match {
          case Failure(e)      =>
            logger.error("error while finishing webauthn registration", e)
            Left("bad request")
          case Success(result) =>
            // the handle comes from the request built by `registrationStart`, not from the client
            Right(
              WebAuthnRegistration(
                handle = (rawRequest \ "handle").as[String],
                credential = WebAuthnCredential.fromRegistrationResult(result)
              )
            )
        }
    }
  }

  def loginStart(
      users: Seq[WebAuthnOtoroshiAdmin],
      username: String,
      origin: String
  )(using env: Env, ec: ExecutionContext): Future[JsValue] = {
    val request: AssertionRequest = relyingParty(users, origin)
      .startAssertion(StartAssertionOptions.builder.username(Optional.of(username)).build)
    val requestId                 = IdGenerator.token(32)
    val finalRequest              = Json.obj(
      "requestId" -> requestId,
      "request"   -> Json.parse(request.toJson),
      "username"  -> username,
      "label"     -> "--"
    )
    env.datastores.webAuthnRegistrationsDataStore.setRegistrationRequest(requestId, finalRequest).map(_ => finalRequest)
  }

  def loginFinish(
      users: Seq[WebAuthnOtoroshiAdmin],
      body: JsValue
  )(using env: Env, ec: ExecutionContext): Future[Either[String, AssertionResult]] = {
    val origin    = (body \ "otoroshi" \ "origin").as[String]
    val requestId = (body \ "requestId").as[String]
    val response  = Json.stringify((body \ "webauthn").as[JsValue])
    consumeRequest(requestId).map {
      case None             => Left("bad request")
      case Some(rawRequest) =>
        Try {
          val request = AssertionRequest.fromJson(pendingRequest(rawRequest))
          val pkc     = PublicKeyCredential.parseAssertionResponseJson(response)
          relyingParty(users, origin).finishAssertion(
            FinishAssertionOptions
              .builder()
              .request(request)
              .response(pkc)
              .build()
          )
        } match {
          case Failure(e)                          =>
            logger.error("error while finishing webauthn authentication", e)
            Left("bad request")
          case Success(result) if result.isSuccess => Right(result)
          case Success(_)                          => Left("bad request")
        }
    }
  }

  /**
   * Stores the signature counter reported by the authenticator, so that the next ceremony can detect a cloned
   * authenticator (a counter that does not move forward). Failing to store it never fails the login.
   */
  def updateSignatureCount(
      user: WebAuthnOtoroshiAdmin,
      result: AssertionResult
  )(using env: Env, ec: ExecutionContext): Future[Unit] = {
    val credentialId = result.getCredential.getCredentialId.getBase64Url
    user.credentials.get(credentialId).flatMap(json => WebAuthnCredential.fromJson(json).toOption) match {
      case Some(credential) if credential.signatureCount != result.getSignatureCount => {
        val updated = credential.copy(signatureCount = result.getSignatureCount)
        env.datastores.webAuthnAdminDataStore
          .registerUser(user.copy(credentials = user.credentials + (credentialId -> updated.json)))
          .map(_ => ())
          .recover { case e =>
            logger.error(s"error while updating webauthn signature counter for '${user.username}'", e)
            ()
          }
      }
      case _                                                                        => Future.successful(())
    }
  }
}
