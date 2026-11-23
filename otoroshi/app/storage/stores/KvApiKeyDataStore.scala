package otoroshi.storage.stores

import org.apache.pekko.http.scaladsl.util.FastFuture.*
import org.apache.pekko.http.scaladsl.util.FastFuture
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.next.models.RouteApiRef
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Format
import otoroshi.storage.{RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

class KvApiKeyDataStore(redisCli: RedisLike, _env: Env) extends ApiKeyDataStore with RedisLikeStore[ApiKey] {

  lazy val logger = Logger("otoroshi-apikey-datastore")

  override def redisLike(using env: Env): RedisLike = redisCli

  override def fmt: Format[ApiKey] = ApiKey._fmt

  override def key(id: String): String = s"${_env.storageRoot}:apikey:coll:${id}"

  override def extractId(value: ApiKey): String = value.clientId

  def totalCallsKey(name: String): String = s"${_env.storageRoot}:apikey:quotas:global:$name"

  def dailyQuotaKey(name: String): String = s"${_env.storageRoot}:apikey:quotas:daily:$name"

  def monthlyQuotaKey(name: String): String = s"${_env.storageRoot}:apikey:quotas:monthly:$name"

  def throttlingKey(name: String): String = s"${_env.storageRoot}:apikey:quotas:second:$name"

  override def clearFastLookupByService(serviceId: String)(using ec: ExecutionContext, env: Env): Future[Long] = {
    redisCli.del(s"${env.storageRoot}:apikey:byservice:$serviceId")
  }

  override def deleteFastLookupByService(serviceId: String, apiKey: ApiKey)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Long] =
    redisCli.srem(s"${env.storageRoot}:apikey:byservice:$serviceId", apiKey.clientId)

  override def addFastLookupByService(serviceId: String, apiKey: ApiKey)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Long] = {
    val key = s"${env.storageRoot}:apikey:byservice:$serviceId"
    for {
      r <- redisCli.sadd(key, apiKey.clientId)
      _ <- redisCli.pttl(key).filter(_ > -1).recoverWith { case _ => redisCli.pexpire(key, 60000) }
    } yield r
  }

  override def deleteFastLookupByGroup(groupId: String, apiKey: ApiKey)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Long] =
    redisCli.srem(s"${env.storageRoot}:apikey:bygroup:$groupId", apiKey.clientId)

  override def clearFastLookupByGroup(groupId: String)(using ec: ExecutionContext, env: Env): Future[Long] = {
    redisCli.del(s"${env.storageRoot}:apikey:bygroup:$groupId")
  }

  override def addFastLookupByGroup(groupId: String, apiKey: ApiKey)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Long] = {
    val key = s"${env.storageRoot}:apikey:bygroup:$groupId"
    for {
      r <- redisCli.sadd(key, apiKey.clientId)
      _ <- redisCli.pttl(key).filter(_ > -1).recoverWith { case _ => redisCli.pexpire(key, 60000) }
    } yield r
  }

  override def remainingQuotas(apiKey: ApiKey)(using ec: ExecutionContext, env: Env): Future[RemainingQuotas] =
    for {
      throttlingCallsPerWindow <-
        redisCli.get(throttlingKey(apiKey.clientId)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
      dailyCalls               <- redisCli.get(dailyQuotaKey(apiKey.clientId)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
      monthlyCalls             <- redisCli.get(monthlyQuotaKey(apiKey.clientId)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
    } yield RemainingQuotas(
      authorizedCallsPerWindow = apiKey.throttlingQuota,
      throttlingCallsPerWindow = throttlingCallsPerWindow,
      remainingCallsPerWindow = Math.max(0, apiKey.throttlingQuota - throttlingCallsPerWindow),
      authorizedCallsPerDay = apiKey.dailyQuota,
      currentCallsPerDay = dailyCalls,
      remainingCallsPerDay = Math.max(0, apiKey.dailyQuota - dailyCalls),
      authorizedCallsPerMonth = apiKey.monthlyQuota,
      currentCallsPerMonth = monthlyCalls,
      remainingCallsPerMonth = Math.max(0, apiKey.monthlyQuota - monthlyCalls)
    )

  override def resetQuotas(apiKey: ApiKey)(using ec: ExecutionContext, env: Env): Future[RemainingQuotas] = {
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis
    for {
      _ <- redisCli.set(totalCallsKey(apiKey.clientId), "0")
      _ <- redisCli.pttl(throttlingKey(apiKey.clientId)).filter(_ > -1).recoverWith { case _ =>
             redisCli.expire(throttlingKey(apiKey.clientId), env.throttlingWindow)
           }
      _ <- redisCli.set(dailyQuotaKey(apiKey.clientId), "0")
      _ <- redisCli.pttl(dailyQuotaKey(apiKey.clientId)).filter(_ > -1).recoverWith { case _ =>
             redisCli.expire(dailyQuotaKey(apiKey.clientId), (toDayEnd / 1000).toInt)
           }
      _ <- redisCli.set(monthlyQuotaKey(apiKey.clientId), "0")
      _ <- redisCli.pttl(monthlyQuotaKey(apiKey.clientId)).filter(_ > -1).recoverWith { case _ =>
             redisCli.expire(monthlyQuotaKey(apiKey.clientId), (toMonthEnd / 1000).toInt)
           }
    } yield RemainingQuotas(
      authorizedCallsPerWindow = apiKey.throttlingQuota,
      throttlingCallsPerWindow = 0,
      remainingCallsPerWindow = 0,
      authorizedCallsPerDay = apiKey.dailyQuota,
      currentCallsPerDay = 0,
      remainingCallsPerDay = apiKey.dailyQuota - 0,
      authorizedCallsPerMonth = apiKey.monthlyQuota,
      currentCallsPerMonth = 0,
      remainingCallsPerMonth = apiKey.monthlyQuota - 0
    )
  }

  override def updateQuotas(apiKey: ApiKey, increment: Long = 1L)(using
      ec: ExecutionContext,
      env: Env
  ): Future[RemainingQuotas] = {
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis
    env.clusterAgent.incrementApi(apiKey.clientId, increment)
    for {
      secCalls <- redisCli.incrby(throttlingKey(apiKey.clientId), increment)
      secTtl   <- redisCli.pttl(throttlingKey(apiKey.clientId)).filter(_ > -1).recoverWith { case _ =>
                    redisCli.expire(throttlingKey(apiKey.clientId), env.throttlingWindow)
                  }

      dailyCalls <- redisCli.incrby(dailyQuotaKey(apiKey.clientId), increment)
      dailyTtl   <- redisCli.pttl(dailyQuotaKey(apiKey.clientId)).filter(_ > -1).recoverWith { case _ =>
                      redisCli.expire(dailyQuotaKey(apiKey.clientId), (toDayEnd / 1000).toInt)
                    }

      monthlyCalls <- redisCli.incrby(monthlyQuotaKey(apiKey.clientId), increment)
      monthlyTtl   <- redisCli.pttl(monthlyQuotaKey(apiKey.clientId)).filter(_ > -1).recoverWith { case _ =>
                        redisCli.expire(monthlyQuotaKey(apiKey.clientId), (toMonthEnd / 1000).toInt)
                      }
      _            <- redisCli.incrby(totalCallsKey(apiKey.clientId), increment)
    } yield {
      RemainingQuotas(
        authorizedCallsPerWindow = apiKey.throttlingQuota,
        throttlingCallsPerWindow = secCalls,
        remainingCallsPerWindow = (apiKey.throttlingQuota - secCalls).toInt,
        authorizedCallsPerDay = apiKey.dailyQuota,
        currentCallsPerDay = dailyCalls,
        remainingCallsPerDay = apiKey.dailyQuota - dailyCalls,
        authorizedCallsPerMonth = apiKey.monthlyQuota,
        currentCallsPerMonth = monthlyCalls,
        remainingCallsPerMonth = apiKey.monthlyQuota - monthlyCalls
      )
    }
  }

  override def updateQuotasAndCheck(apiKey: ApiKey, increment: Long = 1L)(using
      ec: ExecutionContext,
      env: Env
  ): Future[(RemainingQuotas, Boolean)] = {
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis
    env.clusterAgent.incrementApi(apiKey.clientId, increment)
    for {
      secCalls <- redisCli.incrby(throttlingKey(apiKey.clientId), increment)
      _        <- redisCli.pttl(throttlingKey(apiKey.clientId)).flatMap {
                    case -1 => redisCli.expire(throttlingKey(apiKey.clientId), env.throttlingWindow)
                    case _  => Future.successful(())
                  }

      dailyCalls <- redisCli.incrby(dailyQuotaKey(apiKey.clientId), increment)
      _          <- redisCli.pttl(dailyQuotaKey(apiKey.clientId)).flatMap {
                      case -1 => redisCli.expire(dailyQuotaKey(apiKey.clientId), (toDayEnd / 1000).toInt)
                      case _  => Future.successful(())
                    }

      monthlyCalls <- redisCli.incrby(monthlyQuotaKey(apiKey.clientId), increment)
      _            <- redisCli.pttl(monthlyQuotaKey(apiKey.clientId)).flatMap {
                        case -1 => redisCli.expire(monthlyQuotaKey(apiKey.clientId), (toMonthEnd / 1000).toInt)
                        case _  => Future.successful(())
                      }

      _ <- redisCli.incrby(totalCallsKey(apiKey.clientId), increment)
    } yield {
      val withinQuota = secCalls <= apiKey.throttlingQuota &&
        dailyCalls <= apiKey.dailyQuota &&
        monthlyCalls <= apiKey.monthlyQuota

      val quotas = RemainingQuotas(
        authorizedCallsPerWindow = apiKey.throttlingQuota,
        throttlingCallsPerWindow = secCalls,
        remainingCallsPerWindow = Math.max(0, apiKey.throttlingQuota - secCalls),
        authorizedCallsPerDay = apiKey.dailyQuota,
        currentCallsPerDay = dailyCalls,
        remainingCallsPerDay = Math.max(0, apiKey.dailyQuota - dailyCalls),
        authorizedCallsPerMonth = apiKey.monthlyQuota,
        currentCallsPerMonth = monthlyCalls,
        remainingCallsPerMonth = Math.max(0, apiKey.monthlyQuota - monthlyCalls)
      )

      (quotas, withinQuota)
    }
  }

  override def withingQuotas(apiKey: ApiKey)(using ec: ExecutionContext, env: Env): Future[Boolean] =
    for {
      sec <- withinThrottlingQuota(apiKey)
      day <- withinDailyQuota(apiKey)
      mon <- withinMonthlyQuota(apiKey)
    } yield sec && day && mon

  override def withinThrottlingQuota(apiKey: ApiKey)(using ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli
      .get(throttlingKey(apiKey.clientId))
      .fast
      .map(_.map(_.utf8String.toLong).getOrElse(0L) <= apiKey.throttlingQuota)

  override def withinDailyQuota(apiKey: ApiKey)(using ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli.get(dailyQuotaKey(apiKey.clientId)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L) < apiKey.dailyQuota)

  override def withinMonthlyQuota(apiKey: ApiKey)(using ec: ExecutionContext, env: Env): Future[Boolean] =
    redisCli
      .get(monthlyQuotaKey(apiKey.clientId))
      .fast
      .map(_.map(_.utf8String.toLong).getOrElse(0L) < apiKey.monthlyQuota)

  // optimized
  override def findByService(serviceId: String)(using ec: ExecutionContext, env: Env): Future[Seq[ApiKey]] = {
    env.datastores.serviceDescriptorDataStore.findOrRouteById(serviceId).flatMap {
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
      case None             =>
        // an api is neither a service descriptor nor a route: its keys are the ones authorized on it, or on its groups
        env.datastores.apiDataStore.findById(serviceId).flatMap {
          case Some(api) =>
            env.datastores.apiKeyDataStore.findAll().fast.map { keys =>
              keys.filter(key => key.authorizedOnApi(api.id) || key.authorizedOnOneGroupFrom(api.groups))
            }
          case None      => FastFuture.failed(new ServiceNotFoundException(serviceId))
        }
    }
  }

  // optimized
  override def findByGroup(groupId: String)(using ec: ExecutionContext, env: Env): Future[Seq[ApiKey]] = {
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
  override def findAuthorizeKeyFor(clientId: String, serviceId: String)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Option[ApiKey]] = {
    findById(clientId).flatMap {
      case opt @ Some(apiKey) if apiKey.authorizedEntities.contains(ServiceDescriptorIdentifier(serviceId)) =>
        opt.vfuture
      case opt @ Some(apiKey) if apiKey.authorizedEntities.contains(RouteIdentifier(serviceId))             => opt.vfuture
      case opt @ Some(apiKey) if apiKey.authorizedEntities.contains(RouteCompositionIdentifier(serviceId))  =>
        opt.vfuture
      case opt @ Some(apiKey) if apiKey.authorizedEntities.contains(ApiIdentifier(serviceId))               =>
        opt.vfuture
      case Some(apiKey)                                                                                     => {
        // unoptimized
        // apiKey.services.fast.map(services => services.find(_.id == serviceId).map(_ => apiKey))
        env.datastores.serviceDescriptorDataStore.findOrRouteById(serviceId).flatMap {
          case None          =>
            env.datastores.routeCompositionDataStore.findById(serviceId) map {
              case None            => None
              case Some(routeComp) => {
                val groups = routeComp.groups.map(ServiceGroupIdentifier.apply)
                groups.find(sgi => apiKey.authorizedEntities.contains(sgi)).map(_ => apiKey)
              }
            }
          case Some(service) => {
            val apikeyApiAuthorizations = apiKey.authorizedEntities.filter(_.isApi).map(_.id)
            // service is a legacy ServiceDescriptor here, the api ref only lives in its metadata
            service.metadata.get(RouteApiRef.metadataKey) match {
              case Some(apiRef) if apikeyApiAuthorizations.contains(apiRef) => apiKey.some.vfuture
              case _                                                        => {
                val identifiers = service.groups.map(ServiceGroupIdentifier.apply)
                identifiers.find(sgi => apiKey.authorizedEntities.contains(sgi)).map(_ => apiKey).vfuture
              }
            }
          }
        }
      }
      case _                                                                                                => FastFuture.successful(None)
    }
  }

  override def findAuthorizeKeyForFromCache(clientId: String, serviceId: String)(using env: Env): Option[ApiKey] = {
    env.proxyState.apikey(clientId) match {
      case opt @ Some(apiKey) if isAuthorizedFromCache(apiKey, serviceId) => opt
      case _                                                              => None
    }
  }

  override def isAuthorizedFromCache(apiKey: ApiKey, serviceId: String)(using env: Env): Boolean = {
    if (apiKey.authorizedEntities.contains(ServiceDescriptorIdentifier(serviceId))) true
    else if (apiKey.authorizedEntities.contains(RouteIdentifier(serviceId))) true
    else if (apiKey.authorizedEntities.contains(RouteCompositionIdentifier(serviceId))) true
    else if (apiKey.authorizedEntities.contains(ApiIdentifier(serviceId))) true
    else {
      env.proxyState.route(serviceId) match {
        case None          =>
          env.proxyState.routeComposition(serviceId) match {
            case None            => false
            case Some(routeComp) =>
              val groups = routeComp.groups.map(ServiceGroupIdentifier.apply)
              groups.exists(sgi => apiKey.authorizedEntities.contains(sgi))
          }
        case Some(service) => {
          val apikeyApiAuthorizations = apiKey.authorizedEntities.filter(_.isApi).map(_.id)
          service.apiRef match {
            case Some(apiRef) if apikeyApiAuthorizations.contains(apiRef.id) => true
            case _                                                           => {
              val identifiers = service.groups.map(ServiceGroupIdentifier.apply)
              identifiers.exists(sgi => apiKey.authorizedEntities.contains(sgi))
            }
          }
        }
      }
    }
  }

  // read only for a key found but not authorized on the route: a credential without alias costs one read of a map, a
  // call never reads the datastore
  override def findAliasForFromCache(credential: ApiKey, serviceId: String)(using env: Env): Option[ApiKey] = {
    // only a shared credential calls through alias keys
    if (credential.sharedCredential.isEmpty || !env.proxyState.hasApikeyAliases(credential.clientId)) None
    else {
      env.proxyState.route(serviceId).flatMap(_.apiRef) match {
        case None         => None
        case Some(apiRef) =>
          env.proxyState
            .apikeyAlias(credential.clientId, apiRef.id)
            .flatMap(aliasId => env.proxyState.apikey(aliasId))
            // the alias has to be authorized on the api itself, its apiRef alone authorizes it (authorizedOnApi), and
            // minted by a subscription that still shares the credential: an alias written by hand serves nothing, and
            // does not hide the one of the subscription
            .find(alias =>
              alias.apiRef.exists { ref =>
                ref.api == apiRef.id && ref.credential.contains(credential.clientId) &&
                env.proxyState
                  .apiSubscription(ref.subscription)
                  .exists(subscription => otoroshi.next.models.ApiSubscription.mintedAlias(subscription, alias))
              } &&
              alias.authorizedEntities.contains(ApiIdentifier(apiRef.id))
            )
      }
    }
  }

  // an alias key never outlives its credential: anyone could create a key under the client id of a deleted credential,
  // with a secret of its own, and call through its aliases. The aliases of the deleted keys go with them, read with one
  // scan of the keys per call (uncached: an alias is a key like any other, written a moment ago). That scan is paid by
  // every deletion of an api key, even one that has no alias, and once per key when they are deleted one by one
  // (ApiSubscription.onOwnKeys): it is accepted, deleting api keys is an admin operation, never on the path of a call
  override def delete(id: String)(using ec: ExecutionContext, env: Env): Future[Boolean] = deleteByIds(Seq(id))

  override def deleteByIds(ids: Seq[String])(using ec: ExecutionContext, env: Env): Future[Boolean] =
    super.deleteByIds(ids).flatMap(deleted => deleteAliasesOf(ids).map(_ => deleted))

  override def findAliasesOf(credentials: Seq[String])(using ec: ExecutionContext, env: Env): Future[Seq[ApiKey]] =
    if (credentials.isEmpty) Seq.empty.vfuture
    else {
      val ids = credentials.toSet
      findAll(force = true).map(_.filter(_.apiRef.exists(_.credential.exists(ids.contains))))
    }

  override def deleteAliasesOf(credentials: Seq[String])(using ec: ExecutionContext, env: Env): Future[Unit] =
    findAliasesOf(credentials).flatMap {
      case Seq()   => ().vfuture
      case aliases =>
        logger.info(s"deleting the alias keys ${aliases.map(_.clientId).mkString(", ")} of the deleted api keys")
        // a credential is never an alias: nothing more to follow
        super.deleteByIds(aliases.map(_.clientId)).map(_ => ())
    }

  override def findAuthorizeKeyForBearer(bearer: String, serviceId: String)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Option[ApiKey]] = {
    val clientId = OtoroshiBearerToken.extractClientId(bearer)
    // the client id is readable straight out of the bearer, so the signature is the only part proving
    // the caller knows the secret. same checks as the ng path in ApiKeyHelper.validateApikeyTuple
    findAuthorizeKeyForFromCache(clientId, serviceId).filter(apikey => apikey.enabled && apikey.checkBearer(bearer)).vfuture
  }
}
