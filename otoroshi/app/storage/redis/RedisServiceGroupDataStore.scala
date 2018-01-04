package storage.redis

import env.Env
import models.{Key, ServiceGroup, ServiceGroupDataStore}
import play.api.libs.json.Format
import redis.RedisClientMasterSlaves
import env.Env

class RedisServiceGroupDataStore(redisCli: RedisClientMasterSlaves, _env: Env)
    extends ServiceGroupDataStore
    with RedisStore[ServiceGroup] {
  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def fmt: Format[ServiceGroup]                          = ServiceGroup._fmt
  override def key(id: String): Key                               = Key.Empty / _env.storageRoot / "sgroup" / id
  override def extractId(value: ServiceGroup): String             = value.id
}
