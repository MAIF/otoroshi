package otoroshi.storage.stores

import akka.http.scaladsl.util.FastFuture
import otoroshi.auth.{AuthConfigsDataStore, AuthModuleConfig}
import otoroshi.env.Env
import otoroshi.models._
import play.api.libs.json.{Format, JsValue, Json}
import otoroshi.security.IdGenerator
import otoroshi.storage.{RedisLike, RedisLikeStore}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class KvAuthConfigsDataStore(redisCli: RedisLike, _env: Env)
    extends AuthConfigsDataStore
    with RedisLikeStore[AuthModuleConfig] {

  override def redisLike(implicit env: Env): RedisLike    = redisCli
  override def fmt: Format[AuthModuleConfig]              = AuthModuleConfig._fmt
  override def key(id: String): Key                       = Key.Empty / _env.storageRoot / "auth" / "configs" / id
  override def extractId(value: AuthModuleConfig): String = value.id

  override def generateLoginToken(
      maybeTokenValue: Option[String] = None
  )(implicit ec: ExecutionContext): Future[String] = {
    val token = maybeTokenValue.getOrElse(IdGenerator.token(128))
    if (_env.clusterConfig.mode.isWorker) {
      for {
        _ <- redisCli.set(s"${_env.storageRoot}:auth:tokens:$token", token, pxMilliseconds = Some(5.minutes.toMillis))
        _ <- _env.clusterAgent.createLoginToken(token)
      } yield token
    } else {
      redisCli
        .set(s"${_env.storageRoot}:auth:tokens:$token", token, pxMilliseconds = Some(5.minutes.toMillis))
        .map(_ => token)
    }
  }
  override def validateLoginToken(token: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    if (_env.clusterConfig.mode.isWorker) {
      redisCli.exists(s"${_env.storageRoot}:auth:tokens:$token").flatMap {
        case true  =>
          redisCli.del(s"${_env.storageRoot}:auth:tokens:$token")
          FastFuture.successful(true)
        case false => _env.clusterAgent.isLoginTokenValid(token)
      }
    } else {
      redisCli.exists(s"${_env.storageRoot}:auth:tokens:$token").andThen {
        case _ => redisCli.del(s"${_env.storageRoot}:auth:tokens:$token")
      }
    }
  }

  override def setUserForToken(token: String, user: JsValue)(implicit ec: ExecutionContext): Future[Unit] = {
    if (_env.clusterConfig.mode.isWorker) {
      for {
        _ <- redisCli.set(
               s"${_env.storageRoot}:auth:tokens:$token:user",
               Json.stringify(user),
               pxMilliseconds = Some(5.minutes.toMillis)
             )
        _ <- _env.clusterAgent.setUserToken(token, user)
      } yield ()
    } else {
      redisCli
        .set(
          s"${_env.storageRoot}:auth:tokens:$token:user",
          Json.stringify(user),
          pxMilliseconds = Some(5.minutes.toMillis)
        )
        .map(_ => ())
    }
  }

  override def getUserForToken(token: String)(implicit ec: ExecutionContext): Future[Option[JsValue]] = {
    if (_env.clusterConfig.mode.isWorker) {
      redisCli
        .get(s"${_env.storageRoot}:auth:tokens:$token:user")
        .map { bs =>
          bs.map(a => Json.parse(a.utf8String))
        }
        .flatMap {
          case Some(user) =>
            redisCli.del(s"${_env.storageRoot}:auth:tokens:$token:user")
            FastFuture.successful(Some(user))
          case None       => _env.clusterAgent.getUserToken(token)
        }
    } else {
      redisCli
        .get(s"${_env.storageRoot}:auth:tokens:$token:user")
        .map { bs =>
          bs.map(a => Json.parse(a.utf8String))
        }
        .andThen {
          case _ => redisCli.del(s"${_env.storageRoot}:auth:tokens:$token:user")
        }
    }
  }
}
