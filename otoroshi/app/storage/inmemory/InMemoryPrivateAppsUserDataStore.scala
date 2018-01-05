package storage.inmemory

import env.Env
import models.{Key, PrivateAppsUser, PrivateAppsUserDataStore}
import play.api.libs.json.{Format, Json}
import storage.{RedisLike, RedisLikeStore}

class InMemoryPrivateAppsUserDataStore(redisCli: RedisLike, _env: Env)
    extends PrivateAppsUserDataStore
    with RedisLikeStore[PrivateAppsUser] {
  private val _fmt                                       = Json.format[PrivateAppsUser]
  override def redisLike(implicit env: Env): RedisLike   = redisCli
  override def fmt: Format[PrivateAppsUser]              = _fmt
  override def key(id: String): Key                      = Key.Empty / _env.storageRoot / "users" / "private" / id
  override def extractId(value: PrivateAppsUser): String = value.randomId
}
