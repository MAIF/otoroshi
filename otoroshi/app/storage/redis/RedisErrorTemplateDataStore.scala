package storage.redis

import env.Env
import models._
import play.api.libs.json.Format
import redis.RedisClientMasterSlaves

class RedisErrorTemplateDataStore(redisCli: RedisClientMasterSlaves)
    extends ErrorTemplateDataStore
    with RedisStore[ErrorTemplate] {
  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def fmt: Format[ErrorTemplate]                         = ErrorTemplate.format
  override def key(id: String): Key                               = Key.Empty / "opun" / "users" / "private" / id
  override def extractId(value: ErrorTemplate): String            = value.serviceId
}
