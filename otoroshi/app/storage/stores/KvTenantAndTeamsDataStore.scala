package otoroshi.storage.stores

import otoroshi.env.Env
import otoroshi.models.{Team, TeamId, Tenant, TenantId}
import otoroshi.storage.{RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits.BetterJsReadable
import play.api.libs.json.Format

class TenantDataStore(redisCli: RedisLike, env: Env) extends RedisLikeStore[Tenant] {
  override def fmt: Format[Tenant]                     = Tenant.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${env.storageRoot}:tenants:$id"
  override def extractId(value: Tenant): String        = value.id.value
  def template(env: Env): Tenant = {
    val defaultTenant = Tenant(
      id = TenantId("new-organization"),
      name = "New Organization",
      description = "A organization to do whatever you want",
      metadata = Map.empty
    )
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .tenant
      .map { template =>
        Tenant.format.reads(defaultTenant.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        defaultTenant
      }
  }
}

class TeamDataStore(redisCli: RedisLike, env: Env) extends RedisLikeStore[Team] {
  override def fmt: Format[Team]                       = Team.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${env.storageRoot}:teams:$id"
  override def extractId(value: Team): String          = s"${value.tenant.value}:${value.id.value}"
  def template(tenant: TenantId): Team = {
    val defaultTeam = Team(
      id = TeamId("new-team"),
      tenant = tenant,
      name = "New Team",
      description = "A team to do whatever you want",
      metadata = Map.empty
    )
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .team
      .map { template =>
        Team.format.reads(defaultTeam.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        defaultTeam
      }
  }
}
