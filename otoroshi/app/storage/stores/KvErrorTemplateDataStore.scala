package otoroshi.storage.stores

import otoroshi.env.Env
import models._
import play.api.libs.json.Format
import otoroshi.storage.{RedisLike, RedisLikeStore}

class KvErrorTemplateDataStore(redisCli: RedisLike, _env: Env)
    extends ErrorTemplateDataStore
    with RedisLikeStore[ErrorTemplate] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[ErrorTemplate]              = ErrorTemplate.format
  override def key(id: String): Key                    = Key.Empty / _env.storageRoot / "templates" / id
  override def extractId(value: ErrorTemplate): String = value.serviceId
}
