package otoroshi.storage.stores

import otoroshi.env.Env
import models.Key
import otoroshi.models.{Team, TeamId, Tenant, TenantId}
import otoroshi.storage.{RedisLike, RedisLikeStore}
import play.api.libs.json.Format

class TenantDataStore(redisCli: RedisLike, env: Env) extends RedisLikeStore[Tenant] {
  override def fmt: Format[Tenant] = Tenant.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): Key = Key(s"${env.storageRoot}:tenants:$id")
  override def extractId(value: Tenant): String = value.id.value
  val template = Tenant(
    id = TenantId("new-organization"),
    name = "New Organization",
    description = "A organization to do whatever you want",
    metadata = Map.empty
  )
}

class TeamDataStore(redisCli: RedisLike, env: Env) extends RedisLikeStore[Team] {
  override def fmt: Format[Team] = Team.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): Key = Key(s"${env.storageRoot}:teams:$id")
  override def extractId(value: Team): String = s"${value.tenant.value}:${value.id.value}"
  def template(tenant: TenantId) = Team(
    id = TeamId("new-team"),
    tenant = tenant,
    name = "New Team",
    description = "A team to do whatever you want",
    metadata = Map.empty
  )
}
