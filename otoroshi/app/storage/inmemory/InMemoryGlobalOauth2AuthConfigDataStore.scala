package storage.inmemory

import auth.{GlobalOauth2AuthConfigDataStore, GlobalOauth2AuthModuleConfig}
import env.Env
import models._
import play.api.libs.json.Format
import storage.{RedisLike, RedisLikeStore}

class InMemoryGlobalOauth2AuthConfigDataStore(redisCli: RedisLike, _env: Env)
    extends GlobalOauth2AuthConfigDataStore
    with RedisLikeStore[GlobalOauth2AuthModuleConfig] {

  override def redisLike(implicit env: Env): RedisLike     = redisCli
  override def fmt: Format[GlobalOauth2AuthModuleConfig]              = GlobalOauth2AuthModuleConfig._fmt
  override def key(id: String): Key                        = Key.Empty / _env.storageRoot / "auth" / "configs" / id
  override def extractId(value: GlobalOauth2AuthModuleConfig): String = value.id
}
