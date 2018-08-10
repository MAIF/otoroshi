package storage.redis

import auth.{GlobalOauth2AuthConfigDataStore, GenericOauth2ModuleConfig}
import env.Env
import models._
import play.api.libs.json.Format
import redis.RedisClientMasterSlaves

class RedisGlobalOauth2AuthConfigDataStore(redisCli: RedisClientMasterSlaves, _env: Env)
    extends GlobalOauth2AuthConfigDataStore
    with RedisStore[GenericOauth2ModuleConfig] {

  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def fmt: Format[GenericOauth2ModuleConfig]                     = GenericOauth2ModuleConfig._fmt
  override def key(id: String): Key                               = Key.Empty / _env.storageRoot / "jwt" / "verifiers" / id
  override def extractId(value: GenericOauth2ModuleConfig): String        = value.id
}
