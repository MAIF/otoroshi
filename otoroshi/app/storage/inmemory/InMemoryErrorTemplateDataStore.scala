package storage.inmemory

import env.Env
import models._
import play.api.libs.json.Format
import storage.{RedisLike, RedisLikeStore}

class InMemoryErrorTemplateDataStore(redisCli: RedisLike)
    extends ErrorTemplateDataStore
    with RedisLikeStore[ErrorTemplate] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[ErrorTemplate]              = ErrorTemplate.format
  override def key(id: String): Key                    = Key.Empty / "opun" / "templates" / id
  override def extractId(value: ErrorTemplate): String = value.serviceId
}
