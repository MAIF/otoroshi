package storage.stores

import env.Env
import models.Key
import otoroshi.models.{Team, Tenant}
import otoroshi.storage.{RedisLike, RedisLikeStore}
import play.api.libs.json.Format

class TenantDataStore(redisCli: RedisLike, env: Env) extends RedisLikeStore[Tenant] {
  override def fmt: Format[Tenant] = Tenant.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): Key = Key(s"${env.storageRoot}:tenants:$id")
  override def extractId(value: Tenant): String = value.id.value
}

class TeamDataStore(redisCli: RedisLike, env: Env) extends RedisLikeStore[Team] {
  override def fmt: Format[Team] = Team.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): Key = Key(s"${env.storageRoot}:teams:$id")
  override def extractId(value: Team): String = value.id.value
}
