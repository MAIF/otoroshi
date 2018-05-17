package storage.inmemory

import akka.http.scaladsl.util.FastFuture
import env.Env
import storage.RedisLike
import models._
import utils.future.Implicits._
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

class InMemoryCanaryDataStore(redisCli: RedisLike, _env: Env) extends CanaryDataStore {

  lazy val logger = Logger("otoroshi-in-memory-canary-datastore")

  def canaryCountKey(id: String): Key      = Key.Empty / _env.storageRoot / "canary" / id / "count" / "canary"
  def standardCountKey(id: String): Key    = Key.Empty / _env.storageRoot / "canary" / id / "count" / "standard"
  def canarySessionsKey(id: String): Key   = Key.Empty / _env.storageRoot / "canary" / id / "sessions" / "canary"
  def standardSessionsKey(id: String): Key = Key.Empty / _env.storageRoot / "canary" / id / "sessions" / "standard"

  override def destroyCanarySession(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    for {
      _ <- redisCli.del(canaryCountKey(serviceId).key)
      _ <- redisCli.del(standardCountKey(serviceId).key)
      _ <- redisCli.del(canarySessionsKey(serviceId).key)
      _ <- redisCli.del(standardSessionsKey(serviceId).key)
    } yield true

  override def isCanary(serviceId: String, trackingId: String, traffic: Double)(implicit ec: ExecutionContext,
                                                                                env: Env): Future[Boolean] = {
    for {
      _              <- FastFuture.successful(())
      fcanarycount   = redisCli.get(canaryCountKey(serviceId).key).map(_.map(_.utf8String.toLong).getOrElse(0L))
      fstandardCount = redisCli.get(standardCountKey(serviceId).key).map(_.map(_.utf8String.toLong).getOrElse(0L))
      falreadyCanary = redisCli.sismember(canarySessionsKey(serviceId).key, trackingId)
      falreadyStd    = redisCli.sismember(standardSessionsKey(serviceId).key, trackingId)
      canaryCount    <- fcanarycount
      standardCount  <- fstandardCount
      alreadyCanary  <- falreadyCanary
      alreadyStd     <- falreadyStd
      currentPercent <- FastFuture.successful(
                         canaryCount.toDouble / (canaryCount.toDouble + standardCount.toDouble + 1.0)
                       )
      isNowCanary <- if (!alreadyCanary && !alreadyStd && currentPercent < traffic) {
                      redisCli.sadd(canarySessionsKey(serviceId).key, trackingId).flatMap { nbr =>
                        redisCli.incr(canaryCountKey(serviceId).key).map { count =>
                          env.datastores.globalConfigDataStore.singleton().map { config =>
                            env.statsd
                              .counter(s"services.${serviceId}.users.canary", count.toDouble)(config.statsdConfig)
                          }
                          true
                        }
                      }
                    } else if (alreadyCanary) {
                      FastFuture.successful(true)
                    } else if (alreadyStd) {
                      FastFuture.successful(false)
                    } else {
                      redisCli.sadd(standardSessionsKey(serviceId).key, trackingId).flatMap { nbr =>
                        redisCli.incr(standardCountKey(serviceId).key).map { count =>
                          env.datastores.globalConfigDataStore.singleton().map { config =>
                            env.statsd
                              .counter(s"services.${serviceId}.users.default", count.toDouble)(config.statsdConfig)
                          }
                          false
                        }
                      }
                    }
    } yield {
      logger.warn(
        s"current canary req: $canaryCount, current standard req: $standardCount, current percentage: $currentPercent"
      )
      logger.warn(s"user already canary: $alreadyCanary, user became canary: $isNowCanary")
      if (alreadyCanary) true else isNowCanary
    }
  } 

  def canaryCampaign(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[ServiceCanaryCampaign] =
    for {
      canary   <- redisCli.get(canaryCountKey(serviceId).key).map(_.map(_.utf8String.toLong).getOrElse(0L))
      standard <- redisCli.get(standardCountKey(serviceId).key).map(_.map(_.utf8String.toLong).getOrElse(0L))
      _ <- env.datastores.globalConfigDataStore.singleton().map { config =>
            env.statsd.counter(s"services.${serviceId}.users.canary", 0.0)(config.statsdConfig)
            env.statsd.counter(s"services.${serviceId}.users.default", 0.0)(config.statsdConfig)
          }
    } yield {
      ServiceCanaryCampaign(canary, standard)
    }
}
