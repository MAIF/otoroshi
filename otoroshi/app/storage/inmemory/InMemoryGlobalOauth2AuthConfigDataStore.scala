package storage.inmemory

import auth.{GlobalOauth2AuthConfigDataStore, GenericOauth2ModuleConfig}
import env.Env
import models._
import play.api.libs.json.Format
import storage.{RedisLike, RedisLikeStore}

class InMemoryGlobalOauth2AuthConfigDataStore(redisCli: RedisLike, _env: Env)
    extends GlobalOauth2AuthConfigDataStore
    with RedisLikeStore[GenericOauth2ModuleConfig] {

  override def redisLike(implicit env: Env): RedisLike     = redisCli
  override def fmt: Format[GenericOauth2ModuleConfig]              = GenericOauth2ModuleConfig._fmt
  override def key(id: String): Key                        = Key.Empty / _env.storageRoot / "auth" / "configs" / id
  override def extractId(value: GenericOauth2ModuleConfig): String = value.id
}
