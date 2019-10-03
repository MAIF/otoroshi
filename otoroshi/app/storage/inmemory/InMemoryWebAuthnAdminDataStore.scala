package storage.inmemory

import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import com.fasterxml.jackson.databind.ObjectMapper
import env.Env
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import utils.JsonImplicits._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class WebAuthnAdminDataStore() {

  lazy val logger = Logger("otoroshi-webauthn-admin-datastore")

  def key(id: String)(implicit env: Env): String = s"${env.storageRoot}:webauthn:admins:$id"

  def setRegistrationRequest(requestId: String, request: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore.set(s"${env.storageRoot}:webauthn:regreq:$requestId", ByteString(Json.stringify(request)), Some(15.minutes.toMillis)).map(_ => ())
  }

  def getRegistrationRequest(requestId: String)(implicit ec: ExecutionContext, env: Env): Future[Option[JsValue]] = {
    env.datastores.rawDataStore.get(s"${env.storageRoot}:webauthn:regreq:$requestId").map(_.map(_.utf8String).map(Json.parse))
  }

  def deleteRegistrationRequest(requestId: String)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:webauthn:regreq:$requestId")).map(_ => ())
  }

  def setChallenge(challengeId: String, challenge: String)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore.set(s"${env.storageRoot}:webauthn:challenges:$challengeId", ByteString(challenge), Some(15.minutes.toMillis)).map(_ => ())
  }

  def getChallenge(challengeId: String)(implicit ec: ExecutionContext, env: Env): Future[Option[String]] = {
    env.datastores.rawDataStore.get(s"${env.storageRoot}:webauthn:challenges:$challengeId").map(_.map(_.utf8String))
  }

  def deleteChallenge(challengeId: String)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:webauthn:challenges:$challengeId")).map(_ => ())
  }

  def findByUsername(username: String)(implicit ec: ExecutionContext, env: Env): Future[Option[JsValue]] =
    env.datastores.rawDataStore.get(key(username)).map(_.map(v => Json.parse(v.utf8String)))

  def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] =
    env.datastores.rawDataStore
      .keys(key("*"))
      .flatMap(
        keys =>
          if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
          else env.datastores.rawDataStore.mget(keys)
      )
      .map(seq => seq.filter(_.isDefined).map(_.get).map(v => Json.parse(v.utf8String)))

  def deleteUser(username: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    env.datastores.rawDataStore.del(Seq(key(username)))

  def registerUser(username: String, password: String, label: String, authorizedGroup: Option[String], credential: JsValue, handle: String)(
    implicit ec: ExecutionContext,
    env: Env
  ): Future[Boolean] = {
    val group: JsValue = authorizedGroup match {
      case Some(g) => JsString(g)
      case None    => JsNull
    }
    env.datastores.rawDataStore.set(key(username),
      ByteString(Json.stringify(
        Json.obj(
          "username"        -> username,
          "password"        -> password,
          "label"           -> label,
          "authorizedGroup" -> group,
          "createdAt"       -> DateTime.now(),
          "credential"      -> credential,
          "handle"          -> handle
        )
      )), None)
  }
}

object WebAuthnJsonHelper {

  import com.webauthn4j.authenticator.{Authenticator, AuthenticatorImpl}
  import com.webauthn4j.converter.util.{CborConverter, JsonConverter}
  import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
  import com.webauthn4j.data.attestation.statement.AttestationStatement

  lazy val module1 = new com.webauthn4j.converter.jackson.WebAuthnJSONModule(new JsonConverter(), new CborConverter())
  lazy val module2 = new com.webauthn4j.converter.jackson.WebAuthnCBORModule(new JsonConverter(), new CborConverter())
  lazy val mapper = new ObjectMapper().registerModules(module1) //

  def authenticatorToJson(authenticator: Authenticator): JsValue = {
    // val json2 = mapper.writeValueAsString(authenticator)
    // println(json2)
    // val auth2 =  mapper.readValue(json2, classOf[Authenticator])
    // println(auth2)
    // val cbor = new CborConverter().writeValueAsBytes(authenticator)
    // println(cbor)
    // val auth1 = new CborConverter().readValue(cbor, classOf[Authenticator])
    // println(auth1)
    // val json = new JsonConverter().writeValueAsString(authenticator)
    // println(json)
    // val auth = new JsonConverter().readValue(json, classOf[Authenticator])
    // println(auth)
    // Json.parse(json)
    Json.parse(mapper.writeValueAsString(authenticator))
  }

  def jsonToAuthenticator(json: JsValue): Try[Authenticator] = {
    // Try {
    //   // new CborConverter().
    //   val auth = new JsonConverter().readValue(Json.stringify(json), classOf[Authenticator])
    //   println(auth)
    //   val attestedCredentialData: AttestedCredentialData = mapper.readValue(Json.stringify((json \ "attestedCredentialData").as[JsObject]), classOf[AttestedCredentialData])
    //   val attestationStatement: AttestationStatement = mapper.readValue(Json.stringify((json \ "attestationStatement").as[JsObject]), classOf[AttestationStatement])
    //   val counter = (json \ "counter").as[Long]
    //   //val transports: Seq[AuthenticatorTransport] = (json \ "transports").as[JsArray].value.map { item =>
    //   //  mapper.readValue(Json.stringify(item), classOf[AuthenticatorTransport])
    //   //}
    //   //val clientExtensions: Map[String, RegistrationExtensionClientOutput[_]] = (json \ "clientExtensions").as[JsObject].value.map {
    //   //  case (key, item) => (key, mapper.readValue(Json.stringify(item), classOf[RegistrationExtensionClientOutput[_]]))
    //   //}.toMap
    //   //val authenticatorExtensions: Map[String, RegistrationExtensionAuthenticatorOutput[_]] = (json \ "authenticatorExtensions").as[JsObject].value.map {
    //   //  case (key, item) => (key, mapper.readValue(Json.stringify(item), classOf[RegistrationExtensionAuthenticatorOutput[_]]))
    //   //}.toMap
    //   new AuthenticatorImpl(
    //     attestedCredentialData,
    //     attestationStatement,
    //     counter
    //   )
    // }
    Try(mapper.readValue(Json.stringify(json), classOf[Authenticator]))
  }
}
