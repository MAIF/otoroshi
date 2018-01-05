package storage.redis

import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import env.Env
import models.{BackOfficeUser, BackOfficeUserDataStore, Key}
import play.api.libs.json.{Format, JsValue, Json}
import redis.RedisClientMasterSlaves

import scala.concurrent.{ExecutionContext, Future}

class RedisBackOfficeUserDataStore(redisCli: RedisClientMasterSlaves, _env: Env)
    extends BackOfficeUserDataStore
    with RedisStore[BackOfficeUser] {

  private val _fmt                                                = Json.format[BackOfficeUser]
  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def fmt: Format[BackOfficeUser]                        = _fmt
  override def key(id: String): Key                               = Key(s"${_env.storageRoot}:users:backoffice:$id")
  override def extractId(value: BackOfficeUser): String           = value.randomId

  override def blacklisted(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli.sismember(s"${env.storageRoot}:users:blacklist:backoffice", email)

  override def hasAlreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli.sismember(s"${env.storageRoot}:users:alreadyloggedin", email)

  override def alreadyLoggedIn(email: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.sadd(s"${env.storageRoot}:users:alreadyloggedin", email)

  override def sessions()(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] =
    redisCli
      .keys(s"${env.storageRoot}:users:backoffice:*")
      .fast
      .flatMap(
        keys =>
          if (keys.isEmpty) FastFuture.successful(Seq.empty[Option[ByteString]])
          else redisCli.mget(keys: _*)
      )
      .map { seq =>
        seq
          .filter(_.isDefined)
          .map(_.get)
          .map(v => Json.parse(v.utf8String))
      }

  override def discardSession(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.del(s"${env.storageRoot}:users:backoffice:$id")

  def discardAllSessions()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.keys(s"${env.storageRoot}:users:backoffice:*").flatMap { keys =>
      redisCli.del(keys: _*)
    }
}
