package storage.inmemory

import env.Env
import models.{Key, ServiceGroup, ServiceGroupDataStore}
import play.api.libs.json.Format
import storage.{RedisLike, RedisLikeStore}

class InMemoryServiceGroupDataStore(redisCli: RedisLike)
    extends ServiceGroupDataStore
    with RedisLikeStore[ServiceGroup] {
  override def _findAllCached                          = true
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[ServiceGroup]               = ServiceGroup._fmt
  override def key(id: String): Key                    = Key.Empty / "opun" / "sgroup" / id
  override def extractId(value: ServiceGroup): String  = value.id
}
