package storage.redis

import auth.{AuthConfigsDataStore, AuthModuleConfig}
import env.Env
import models._
import play.api.libs.json.Format
import redis.RedisClientMasterSlaves

class RedisAuthConfigsDataStore(redisCli: RedisClientMasterSlaves, _env: Env)
    extends AuthConfigsDataStore
    with RedisStore[AuthModuleConfig] {

  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def fmt: Format[AuthModuleConfig]                      = AuthModuleConfig._fmt
  override def key(id: String): Key                               = Key.Empty / _env.storageRoot / "auth" / "configs" / id
  override def extractId(value: AuthModuleConfig): String         = value.id
}
