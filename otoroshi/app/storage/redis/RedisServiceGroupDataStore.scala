package storage.redis

import env.Env
import models.{Key, ServiceGroup, ServiceGroupDataStore}
import play.api.libs.json.Format
import redis.RedisClientMasterSlaves

class RedisServiceGroupDataStore(redisCli: RedisClientMasterSlaves)
    extends ServiceGroupDataStore
    with RedisStore[ServiceGroup] {
  override def _findAllCached                                     = true
  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def fmt: Format[ServiceGroup]                          = ServiceGroup._fmt
  override def key(id: String): Key                               = Key.Empty / "opun" / "sgroup" / id
  override def extractId(value: ServiceGroup): String             = value.id
}
