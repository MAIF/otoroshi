package otoroshi.storage.stores

import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import env.Env
import org.joda.time.DateTime
import otoroshi.models._
import play.api.Logger
import play.api.libs.json._
import otoroshi.utils.json.JsonImplicits._
import otoroshi.utils.syntax.implicits._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class WebAuthnRegistrationsDataStore() {

  lazy val logger = Logger("otoroshi-webauthn-admin-datastore")

  def setRegistrationRequest(requestId: String, request: JsValue)(implicit ec: ExecutionContext,
                                                                  env: Env): Future[Unit] = {
    env.datastores.rawDataStore
      .set(s"${env.storageRoot}:webauthn:regreq:$requestId",
           ByteString(Json.stringify(request)),
           Some(15.minutes.toMillis))
      .map(_ => ())
  }

  def getRegistrationRequest(requestId: String)(implicit ec: ExecutionContext, env: Env): Future[Option[JsValue]] = {
    env.datastores.rawDataStore
      .get(s"${env.storageRoot}:webauthn:regreq:$requestId")
      .map(_.map(_.utf8String).map(Json.parse))
  }

  def deleteRegistrationRequest(requestId: String)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:webauthn:regreq:$requestId")).map(_ => ())
  }
}

class KvWebAuthnAdminDataStore extends WebAuthnAdminDataStore {

  lazy val logger = Logger("otoroshi-webauthn-admin-datastore")

  def key(id: String)(implicit env: Env): String = s"${env.storageRoot}:webauthn:admins:$id"

  def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.rawDataStore.sismember(s"${env.storageRoot}:users:alreadyloggedin", ByteString(email))

  def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    env.datastores.rawDataStore.sadd(s"${env.storageRoot}:users:alreadyloggedin", Seq(ByteString(email)))

  def findByUsername(username: String)(implicit ec: ExecutionContext, env: Env): Future[Option[WebAuthnOtoroshiAdmin]] =
    env.datastores.rawDataStore.get(key(username)).map(_.map(v => Json.parse(v.utf8String)).flatMap { user =>
      WebAuthnOtoroshiAdmin.reads(user).asOpt
    })

  def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[WebAuthnOtoroshiAdmin]] =
    env.datastores.rawDataStore
      .keys(key("*"))
      .flatMap(
        keys =>
          if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
          else env.datastores.rawDataStore.mget(keys)
      )
      .map(seq => seq.filter(_.isDefined).map(_.get).map(v => Json.parse(v.utf8String)).flatMap { user =>
        WebAuthnOtoroshiAdmin.reads(user).asOpt
      })

  def deleteUser(username: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    env.datastores.rawDataStore.del(Seq(key(username)))

  def save(payload: WebAuthnOtoroshiAdmin)(
    implicit ec: ExecutionContext,
    env: Env
  ): Future[Boolean] = {
    env.datastores.rawDataStore.set(key(payload.username), payload.json.stringify.byteString, None)
  }

  override def deleteUsers(usernames: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (usernames.isEmpty) {
      FastFuture.successful(0L)
    } else {
      env.datastores.rawDataStore.del(usernames.map(key))
    }
  }

  override def registerUser(user: WebAuthnOtoroshiAdmin)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = save(user)
}
