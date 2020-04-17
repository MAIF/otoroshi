package otoroshi.storage.stores

import env.Env
import models.{Key, ServiceGroup, ServiceGroupDataStore}
import play.api.libs.json.Format
import otoroshi.storage.{RedisLike, RedisLikeStore}

class KvServiceGroupDataStore(redisCli: RedisLike, _env: Env)
    extends ServiceGroupDataStore
    with RedisLikeStore[ServiceGroup] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[ServiceGroup]               = ServiceGroup._fmt
  override def key(id: String): Key                    = Key.Empty / _env.storageRoot / "sgroup" / id
  override def extractId(value: ServiceGroup): String  = value.id
}
