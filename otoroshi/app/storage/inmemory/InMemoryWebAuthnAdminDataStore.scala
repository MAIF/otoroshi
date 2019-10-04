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
          "handle"          -> handle,
          "type"            -> "WEBAUTHN"
        )
      )), None)
  }
}