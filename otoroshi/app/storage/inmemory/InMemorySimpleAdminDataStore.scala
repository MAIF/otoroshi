package storage.inmemory

import akka.http.scaladsl.util.FastFuture
import env.Env
import models.SimpleAdminDataStore
import play.api.libs.json._
import utils.JsonImplicits._

import scala.util.Success
import akka.util.ByteString
import storage.RedisLike
import org.joda.time.DateTime
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

class InMemorySimpleAdminDataStore(redisCli: RedisLike, _env: Env) extends SimpleAdminDataStore {

  lazy val logger = Logger("otoroshi-in-memory-simple-admin-datastore")

  def key(id: String): String = s"${_env.storageRoot}:admins:$id"

  override def findByUsername(username: String)(implicit ec: ExecutionContext, env: Env): Future[Option[JsValue]] =
    redisCli.get(key(username)).map(_.map(v => Json.parse(v.utf8String)))

  override def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] =
    redisCli
      .keys(key("*"))
      .flatMap(
        keys =>
          if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
          else redisCli.mget(keys: _*)
      )
      .map(seq => seq.filter(_.isDefined).map(_.get).map(v => Json.parse(v.utf8String)))

  override def deleteUser(username: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.del(key(username))

  override def registerUser(username: String, password: String, label: String, authorizedGroup: Option[String])(implicit ec: ExecutionContext,
                                                                               env: Env): Future[Boolean] = {
    // logger.warn(password)
    val group: JsValue = authorizedGroup match {
      case Some(g) => JsString(g)
      case None => JsNull
    }
    redisCli.set(key(username),
                 Json.stringify(
                   Json.obj(
                     "username"  -> username,
                     "password"  -> password,
                     "label"     -> label,
                     "authorizedGroup" -> group,
                     "createdAt" -> DateTime.now()
                   )
                 ))
  }

  override def hasAlreadyLoggedIn(username: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli.sismember(s"${env.storageRoot}:users:alreadyloggedin", username)

  override def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.sadd(s"${env.storageRoot}:users:alreadyloggedin", email)
}
