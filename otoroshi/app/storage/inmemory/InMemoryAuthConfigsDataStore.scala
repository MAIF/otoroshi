package storage.inmemory

import auth.{AuthConfigsDataStore, AuthModuleConfig}
import env.Env
import models._
import play.api.libs.json.{Format, JsValue, Json}
import security.IdGenerator
import storage.{RedisLike, RedisLikeStore}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class InMemoryAuthConfigsDataStore(redisCli: RedisLike, _env: Env)
    extends AuthConfigsDataStore
    with RedisLikeStore[AuthModuleConfig] {

  override def redisLike(implicit env: Env): RedisLike    = redisCli
  override def fmt: Format[AuthModuleConfig]              = AuthModuleConfig._fmt
  override def key(id: String): Key                       = Key.Empty / _env.storageRoot / "auth" / "configs" / id
  override def extractId(value: AuthModuleConfig): String = value.id

  override def generateLoginToken()(implicit ec: ExecutionContext): Future[String] = {
    val token = IdGenerator.token(128)
    redisCli
      .set(s"${_env.storageRoot}:auth:tokens:$token", token, pxMilliseconds = Some(5.minutes.toMillis))
      .map(_ => token)
  }
  override def validateLoginToken(token: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    redisCli.exists(s"${_env.storageRoot}:auth:tokens:$token").andThen {
      case _ => redisCli.del(s"${_env.storageRoot}:auth:tokens:$token")
    }
  }

  override def setUserForToken(token: String, user: JsValue)(implicit ec: ExecutionContext): Future[Unit] = {
    redisCli
      .set(s"${_env.storageRoot}:auth:tokens:$token:user", Json.stringify(user), pxMilliseconds = Some(5.minutes.toMillis))
      .map(_ => ())
  }

  override def getUserForToken(token: String)(implicit ec: ExecutionContext): Future[Option[JsValue]] = {
    redisCli.get(s"${_env.storageRoot}:auth:tokens:$token:user").map { bs =>
      bs.map(a => Json.parse(a.utf8String))
    }.andThen {
      case _ => redisCli.del(s"${_env.storageRoot}:auth:tokens:$token:user")
    }
  }
}
