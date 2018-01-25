package storage.redis

import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.util.FastFuture
import env.Env
import models.SimpleAdminDataStore
import play.api.libs.json._
import play.api.libs.json.JodaReads._
import play.api.libs.json.JodaWrites._
import redis.RedisClientMasterSlaves

import scala.util.Success
import akka.util.ByteString
import org.joda.time.DateTime
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

class RedisSimpleAdminDataStore(redisCli: RedisClientMasterSlaves) extends SimpleAdminDataStore {

  lazy val logger = Logger("otoroshi-redis-simple-admin-datastore")

  def key(id: String)(implicit env: Env): String = s"${env.storageRoot}:admins:$id"

  override def findByUsername(username: String)(implicit ec: ExecutionContext, env: Env): Future[Option[JsValue]] =
    redisCli.get(key(username)).fast.map(_.map(v => Json.parse(v.utf8String)))

  override def findAll()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] =
    redisCli
      .keys(key("*"))
      .fast
      .flatMap(
        keys =>
          if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
          else redisCli.mget(keys: _*)
      )
      .map(seq => seq.filter(_.isDefined).map(_.get).map(v => Json.parse(v.utf8String)))

  override def deleteUser(username: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.del(key(username))

  override def registerUser(username: String, password: String, label: String)(implicit ec: ExecutionContext,
                                                                               env: Env): Future[Boolean] =
    redisCli.set(key(username),
                 ByteString(
                   Json.stringify(
                     Json.obj(
                       "username"  -> username,
                       "password"  -> password,
                       "label"     -> label,
                       "createdAt" -> DateTime.now()
                     )
                   )
                 ))

  override def hasAlreadyLoggedIn(username: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli.sismember(s"${env.storageRoot}:users:alreadyloggedin", username)

  override def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.sadd(s"${env.storageRoot}:users:alreadyloggedin", email)
}
