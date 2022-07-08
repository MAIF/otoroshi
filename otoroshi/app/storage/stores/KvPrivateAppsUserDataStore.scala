package otoroshi.storage.stores

import otoroshi.env.Env
import otoroshi.models.{PrivateAppsUser, PrivateAppsUserDataStore}
import otoroshi.storage.{RedisLike, RedisLikeStore}
import play.api.libs.json.Format

class KvPrivateAppsUserDataStore(redisCli: RedisLike, _env: Env)
    extends PrivateAppsUserDataStore
    with RedisLikeStore[PrivateAppsUser] {
  private val _fmt                                       = PrivateAppsUser.fmt
  override def redisLike(implicit env: Env): RedisLike   = redisCli
  override def fmt: Format[PrivateAppsUser]              = _fmt
  override def key(id: String): String                   = s"${_env.storageRoot}:users:private:${id}"
  override def extractId(value: PrivateAppsUser): String = value.randomId
}
