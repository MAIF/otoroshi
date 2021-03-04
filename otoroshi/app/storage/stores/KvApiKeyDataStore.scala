package otoroshi.storage.stores

import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.util.FastFuture
import otoroshi.env.Env
import otoroshi.models._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Format
import otoroshi.storage.{RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

class KvApiKeyDataStore(redisCli: RedisLike, _env: Env) extends ApiKeyDataStore with RedisLikeStore[ApiKey] {

  lazy val logger = Logger("otoroshi-apikey-datastore")

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def fmt: Format[ApiKey] = ApiKey._fmt

  override def key(id: String): Key = Key.Empty / _env.storageRoot / "apikey" / "coll" / id

  override def extractId(value: ApiKey): String = value.clientId

  def totalCallsKey(name: String): String = s"${_env.storageRoot}:apikey:quotas:global:$name"

  def dailyQuotaKey(name: String): String = s"${_env.storageRoot}:apikey:quotas:daily:$name"

  def monthlyQuotaKey(name: String): String = s"${_env.storageRoot}:apikey:quotas:monthly:$name"

  def throttlingKey(name: String): String = s"${_env.storageRoot}:apikey:quotas:second:$name"

  override def clearFastLookupByService(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    redisCli.del(s"${env.storageRoot}:apikey:byservice:$serviceId")
  }

  override def deleteFastLookupByService(serviceId: String, apiKey: ApiKey)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Long] =
    redisCli.srem(s"${env.storageRoot}:apikey:byservice:$serviceId", apiKey.clientId)

  override def addFastLookupByService(serviceId: String, apiKey: ApiKey)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Long] = {
    val key = s"${env.storageRoot}:apikey:byservice:$serviceId"
    for {
      r <- redisCli.sadd(key, apiKey.clientId)
      _ <- redisCli.pttl(key).filter(_ > -1).recoverWith { case _ => redisCli.pexpire(key, 60000) }
    } yield r
  }

  override def deleteFastLookupByGroup(groupId: String, apiKey: ApiKey)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Long] =
    redisCli.srem(s"${env.storageRoot}:apikey:bygroup:$groupId", apiKey.clientId)

  override def clearFastLookupByGroup(groupId: String)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    redisCli.del(s"${env.storageRoot}:apikey:bygroup:$groupId")
  }

  override def addFastLookupByGroup(groupId: String, apiKey: ApiKey)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Long] = {
    val key = s"${env.storageRoot}:apikey:bygroup:$groupId"
    for {
      r <- redisCli.sadd(key, apiKey.clientId)
      _ <- redisCli.pttl(key).filter(_ > -1).recoverWith { case _ => redisCli.pexpire(key, 60000) }
    } yield r
  }

  override def remainingQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas] =
    for {
      secCalls     <- redisCli.get(throttlingKey(apiKey.clientId)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
      dailyCalls   <- redisCli.get(dailyQuotaKey(apiKey.clientId)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
      monthlyCalls <- redisCli.get(monthlyQuotaKey(apiKey.clientId)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
    } yield RemainingQuotas(
      authorizedCallsPerSec = apiKey.throttlingQuota,
      currentCallsPerSec = (secCalls / env.throttlingWindow).toInt,
      remainingCallsPerSec = apiKey.throttlingQuota - (secCalls / env.throttlingWindow).toInt,
      authorizedCallsPerDay = apiKey.dailyQuota,
      currentCallsPerDay = dailyCalls,
      remainingCallsPerDay = apiKey.dailyQuota - dailyCalls,
      authorizedCallsPerMonth = apiKey.monthlyQuota,
      currentCallsPerMonth = monthlyCalls,
      remainingCallsPerMonth = apiKey.monthlyQuota - monthlyCalls
    )

  override def resetQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[RemainingQuotas] = {
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis
    for {
      _ <- redisCli.set(totalCallsKey(apiKey.clientId), "0")
      _ <- redisCli.pttl(throttlingKey(apiKey.clientId)).filter(_ > -1).recoverWith {
             case _ => redisCli.expire(throttlingKey(apiKey.clientId), env.throttlingWindow)
           }
      _ <- redisCli.set(dailyQuotaKey(apiKey.clientId), "0")
      _ <- redisCli.pttl(dailyQuotaKey(apiKey.clientId)).filter(_ > -1).recoverWith {
             case _ => redisCli.expire(dailyQuotaKey(apiKey.clientId), (toDayEnd / 1000).toInt)
           }
      _ <- redisCli.set(monthlyQuotaKey(apiKey.clientId), "0")
      _ <- redisCli.pttl(monthlyQuotaKey(apiKey.clientId)).filter(_ > -1).recoverWith {
             case _ => redisCli.expire(monthlyQuotaKey(apiKey.clientId), (toMonthEnd / 1000).toInt)
           }
    } yield RemainingQuotas(
      authorizedCallsPerSec = apiKey.throttlingQuota,
      currentCallsPerSec = (0L / env.throttlingWindow).toInt,
      remainingCallsPerSec = apiKey.throttlingQuota - (0L / env.throttlingWindow).toInt,
      authorizedCallsPerDay = apiKey.dailyQuota,
      currentCallsPerDay = 0,
      remainingCallsPerDay = apiKey.dailyQuota - 0,
      authorizedCallsPerMonth = apiKey.monthlyQuota,
      currentCallsPerMonth = 0,
      remainingCallsPerMonth = apiKey.monthlyQuota - 0
    )
  }

  override def updateQuotas(apiKey: ApiKey, increment: Long = 1L)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[RemainingQuotas] = {
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis
    env.clusterAgent.incrementApi(apiKey.clientId, increment)
    for {
      _            <- redisCli.incrby(totalCallsKey(apiKey.clientId), increment)
      secCalls     <- redisCli.incrby(throttlingKey(apiKey.clientId), increment)
      secTtl       <- redisCli.pttl(throttlingKey(apiKey.clientId)).filter(_ > -1).recoverWith {
                        case _ => redisCli.expire(throttlingKey(apiKey.clientId), env.throttlingWindow)
                      }
      dailyCalls   <- redisCli.incrby(dailyQuotaKey(apiKey.clientId), increment)
      dailyTtl     <- redisCli.pttl(dailyQuotaKey(apiKey.clientId)).filter(_ > -1).recoverWith {
                        case _ => redisCli.expire(dailyQuotaKey(apiKey.clientId), (toDayEnd / 1000).toInt)
                      }
      monthlyCalls <- redisCli.incrby(monthlyQuotaKey(apiKey.clientId), increment)
      monthlyTtl   <- redisCli.pttl(monthlyQuotaKey(apiKey.clientId)).filter(_ > -1).recoverWith {
                        case _ => redisCli.expire(monthlyQuotaKey(apiKey.clientId), (toMonthEnd / 1000).toInt)
                      }
    } yield RemainingQuotas(
      authorizedCallsPerSec = apiKey.throttlingQuota,
      currentCallsPerSec = (secCalls / env.throttlingWindow).toInt,
      remainingCallsPerSec = apiKey.throttlingQuota - (secCalls / env.throttlingWindow).toInt,
      authorizedCallsPerDay = apiKey.dailyQuota,
      currentCallsPerDay = dailyCalls,
      remainingCallsPerDay = apiKey.dailyQuota - dailyCalls,
      authorizedCallsPerMonth = apiKey.monthlyQuota,
      currentCallsPerMonth = monthlyCalls,
      remainingCallsPerMonth = apiKey.monthlyQuota - monthlyCalls
    )
  }

  override def withingQuotas(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    for {
      sec <- withinThrottlingQuota(apiKey)
      day <- withinDailyQuota(apiKey)
      mon <- withinMonthlyQuota(apiKey)
    } yield sec && day && mon

  override def withinThrottlingQuota(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli
      .get(throttlingKey(apiKey.clientId))
      .fast
      .map(_.map(_.utf8String.toLong).getOrElse(0L) <= (apiKey.throttlingQuota * env.throttlingWindow))

  override def withinDailyQuota(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli.get(dailyQuotaKey(apiKey.clientId)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L) < apiKey.dailyQuota)

  override def withinMonthlyQuota(apiKey: ApiKey)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli
      .get(monthlyQuotaKey(apiKey.clientId))
      .fast
      .map(_.map(_.utf8String.toLong).getOrElse(0L) < apiKey.monthlyQuota)

  // optimized
  override def findByService(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ApiKey]] = {
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case Some(descriptor) => {
        if (redisCli.optimized) {
          redisCli.asOptimized.apiKeys_findByService(descriptor)
        } else {
          env.datastores.apiKeyDataStore.findAll().fast.map { keys =>
            keys.filter { key =>
              key.authorizedOnService(descriptor.id) || key.authorizedOnOneGroupFrom(descriptor.groups)
            }
          }
        }
      }
      case None             => FastFuture.failed(new ServiceNotFoundException(serviceId))
    }
  }

  // optimized
  override def findByGroup(groupId: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ApiKey]] = {
    if (redisCli.optimized) {
      redisCli.asOptimized.apiKeys_findByGroup(groupId)
    } else {
      env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
        case Some(group) => {
          env.datastores.apiKeyDataStore.findAll().fast.map { keys =>
            keys.filter { key =>
              key.authorizedOnGroup(group.id)
            }
          }
        }
        case None        => FastFuture.failed(new GroupNotFoundException(groupId))
      }
    }
  }

  // optimized
  override def findAuthorizeKeyFor(clientId: String, serviceId: String)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Option[ApiKey]] = {
    findById(clientId).flatMap {
      case opt @ Some(apiKey) if apiKey.authorizedEntities.contains(ServiceDescriptorIdentifier(serviceId)) =>
        opt.future
      case Some(apiKey)                                                                                     => {
        // unoptimized
        // apiKey.services.fast.map(services => services.find(_.id == serviceId).map(_ => apiKey))
        env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
          case None          => None
          case Some(service) => {
            val identifiers = service.groups.map(ServiceGroupIdentifier.apply)
            identifiers.find(sgi => apiKey.authorizedEntities.contains(sgi)).map(_ => apiKey)
          }
        }
      }
      case None                                                                                             => FastFuture.successful(None)
    }
  }
}
