package otoroshi.storage.stores

import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.storage.{RedisLike, RedisLikeStore}
import play.api.libs.json.Format

class KvErrorTemplateDataStore(redisCli: RedisLike, _env: Env)
    extends ErrorTemplateDataStore
    with RedisLikeStore[ErrorTemplate] {
  override def redisLike(using env: Env): RedisLike = redisCli
  override def fmt: Format[ErrorTemplate]              = ErrorTemplate.format
  override def key(id: String): String                 = s"${_env.storageRoot}:templates:$id"
  override def extractId(value: ErrorTemplate): String = value.serviceId
}
