package storage.inmemory

import auth.{AuthConfigsDataStore, AuthModuleConfig}
import env.Env
import models._
import play.api.libs.json.Format
import storage.{RedisLike, RedisLikeStore}

class InMemoryAuthConfigsDataStore(redisCli: RedisLike, _env: Env)
    extends AuthConfigsDataStore
    with RedisLikeStore[AuthModuleConfig] {

  override def redisLike(implicit env: Env): RedisLike     = redisCli
  override def fmt: Format[AuthModuleConfig]               = AuthModuleConfig._fmt
  override def key(id: String): Key                        = Key.Empty / _env.storageRoot / "auth" / "configs" / id
  override def extractId(value: AuthModuleConfig): String  = value.id
}
