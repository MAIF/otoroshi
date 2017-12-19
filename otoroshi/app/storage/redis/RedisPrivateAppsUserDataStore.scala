package storage.redis

import env.Env
import models.{Key, PrivateAppsUser, PrivateAppsUserDataStore}
import play.api.libs.json.{Format, Json}
import redis.RedisClientMasterSlaves

class RedisPrivateAppsUserDataStore(redisCli: RedisClientMasterSlaves)
    extends PrivateAppsUserDataStore
    with RedisStore[PrivateAppsUser] {
  private val _fmt                                                = Json.format[PrivateAppsUser]
  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def fmt: Format[PrivateAppsUser]                       = _fmt
  override def key(id: String): Key                               = Key(s"opun:users:private:$id")
  override def extractId(value: PrivateAppsUser): String          = value.randomId
}
