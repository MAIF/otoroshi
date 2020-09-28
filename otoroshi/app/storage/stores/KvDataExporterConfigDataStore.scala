package storage.stores

import env.Env
import models.{DataExporterConfig, Key}
import otoroshi.storage.{RedisLike, RedisLikeStore}
import play.api.libs.json.Format

class DataExporterConfigDataStore(redisCli: RedisLike, env: Env) extends RedisLikeStore[DataExporterConfig] {
  override def fmt: Format[DataExporterConfig] = DataExporterConfig.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): Key = Key(s"${env.storageRoot}:data-exporters:$id")
  override def extractId(value: DataExporterConfig): String = value.id
}