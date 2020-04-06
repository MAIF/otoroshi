package otoroshi.storage.stores

import env.Env
import models._
import play.api.libs.json.Format
import otoroshi.storage.{RedisLike, RedisLikeStore}

class InMemoryGlobalJwtVerifierDataStore(redisCli: RedisLike, _env: Env)
    extends GlobalJwtVerifierDataStore
    with RedisLikeStore[GlobalJwtVerifier] {

  override def redisLike(implicit env: Env): RedisLike     = redisCli
  override def fmt: Format[GlobalJwtVerifier]              = GlobalJwtVerifier._fmt
  override def key(id: String): Key                        = Key.Empty / _env.storageRoot / "jwt" / "verifiers" / id
  override def extractId(value: GlobalJwtVerifier): String = value.id
}
