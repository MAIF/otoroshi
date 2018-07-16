package storage.redis

import env.Env
import models._
import play.api.libs.json.Format
import redis.RedisClientMasterSlaves

class RedisGlobalJwtVerifierDataStore(redisCli: RedisClientMasterSlaves, _env: Env)
  extends GlobalJwtVerifierDataStore
    with RedisStore[GlobalJwtVerifier] {

  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def fmt: Format[GlobalJwtVerifier]          = GlobalJwtVerifier._fmt
  override def key(id: String): Key                    = Key.Empty / _env.storageRoot / "jwt" / "verifiers" / id
  override def extractId(value: GlobalJwtVerifier): String  = value.id
}

