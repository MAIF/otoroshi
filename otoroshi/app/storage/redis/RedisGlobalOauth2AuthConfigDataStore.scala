package storage.redis

import auth.{GlobalOauth2AuthConfigDataStore, GlobalOauth2AuthModuleConfig}
import env.Env
import models._
import play.api.libs.json.Format
import redis.RedisClientMasterSlaves

class RedisGlobalOauth2AuthConfigDataStore(redisCli: RedisClientMasterSlaves, _env: Env)
    extends GlobalOauth2AuthConfigDataStore
    with RedisStore[GlobalOauth2AuthModuleConfig] {

  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def fmt: Format[GlobalOauth2AuthModuleConfig]                     = GlobalOauth2AuthModuleConfig._fmt
  override def key(id: String): Key                               = Key.Empty / _env.storageRoot / "jwt" / "verifiers" / id
  override def extractId(value: GlobalOauth2AuthModuleConfig): String        = value.id
}
