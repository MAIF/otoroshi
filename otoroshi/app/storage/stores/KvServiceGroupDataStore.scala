package otoroshi.storage.stores

import otoroshi.env.Env
import otoroshi.models.{ServiceGroup, ServiceGroupDataStore}
import otoroshi.storage.{RedisLike, RedisLikeStore}
import play.api.libs.json.Format

class KvServiceGroupDataStore(redisCli: RedisLike, _env: Env)
    extends ServiceGroupDataStore
    with RedisLikeStore[ServiceGroup] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[ServiceGroup]               = ServiceGroup._fmt
  override def key(id: String): String                 = s"${_env.storageRoot}:sgroup:${id}"
  override def extractId(value: ServiceGroup): String  = value.id
}
