package otoroshi.storage.stores

import akka.http.scaladsl.util.FastFuture
import env.Env
import otoroshi.storage.RedisLike
import models._
import otoroshi.utils.future.Implicits._
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

class KvCanaryDataStore(redisCli: RedisLike, _env: Env) extends CanaryDataStore {

  lazy val logger = Logger("otoroshi-datastore")

  def canaryCountKey(id: String): Key   = Key.Empty / _env.storageRoot / "canary" / id / "count" / "canary"
  def standardCountKey(id: String): Key = Key.Empty / _env.storageRoot / "canary" / id / "count" / "standard"

  override def destroyCanarySession(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    for {
      _ <- redisCli.del(canaryCountKey(serviceId).key)
      _ <- redisCli.del(standardCountKey(serviceId).key)
    } yield true
  }

  override def isCanary(serviceId: String, trackingId: String, traffic: Double, reqNumber: Int, config: GlobalConfig)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {
    val hash: Int = Math.abs(scala.util.hashing.MurmurHash3.stringHash(trackingId))
    if (hash % 100 < (traffic * 100)) {
      redisCli.incr(canaryCountKey(serviceId).key).map { c =>
        env.metrics.markLong(s"services.$serviceId.users.canary", c)
      }
      FastFuture.successful(true)
    } else {
      redisCli.incr(standardCountKey(serviceId).key).map { c =>
        env.metrics.markLong(s"services.$serviceId.users.default", c)
      }
      FastFuture.successful(false)
    }
  }

  def canaryCampaign(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[ServiceCanaryCampaign] = {
    for {
      canary   <- redisCli.get(canaryCountKey(serviceId).key).map(_.map(_.utf8String.toLong).getOrElse(0L))
      standard <- redisCli.get(standardCountKey(serviceId).key).map(_.map(_.utf8String.toLong).getOrElse(0L))
    } yield {
      ServiceCanaryCampaign(canary, standard)
    }
  }
}
