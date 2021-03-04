package otoroshi.plugins.quotas

import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import otoroshi.env.Env
import otoroshi.models.{RemainingQuotas, ServiceDescriptor}
import org.joda.time.DateTime
import otoroshi.script.{AccessContext, AccessValidator}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.{ExecutionContext, Future}

class InstanceQuotas extends AccessValidator {

  override def name: String = "Instance quotas"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "InstanceQuotas" -> Json.obj(
          "callsPerDay"     -> -1,
          "callsPerMonth"   -> -1,
          "maxDescriptors"  -> -1,
          "maxApiKeys"      -> -1,
          "maxGroups"       -> -1,
          "maxScripts"      -> -1,
          "maxCertificates" -> -1,
          "maxVerifiers"    -> -1,
          "maxAuthModules"  -> -1
        )
      )
    )

  override def description: Option[String] = Some(s"""This plugin will enforce global quotas on the current instance
                                                    |
                                                    |This plugin can accept the following configuration
                                                    |
                                                    |```json
                                                    |{
                                                    |  "InstanceQuotas": {
                                                    |    "callsPerDay": -1,     // max allowed api calls per day
                                                    |    "callsPerMonth": -1,   // max allowed api calls per month
                                                    |    "maxDescriptors": -1,  // max allowed service descriptors
                                                    |    "maxApiKeys": -1,      // max allowed apikeys
                                                    |    "maxGroups": -1,       // max allowed service groups
                                                    |    "maxScripts": -1,      // max allowed apikeys
                                                    |    "maxCertificates": -1, // max allowed certificates
                                                    |    "maxVerifiers": -1,    // max allowed jwt verifiers
                                                    |    "maxAuthModules": -1,  // max allowed auth modules
                                                    |  }
                                                    |}
                                                    |```
                                                  """.stripMargin)

  override def canAccess(ctx: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val config = ctx.configFor("InstanceQuotas")
    if (ctx.descriptor.id == env.backOfficeServiceId) {
      if (
        ctx.request.method.toLowerCase == "POST".toLowerCase || ctx.request.method.toLowerCase == "PUT".toLowerCase || ctx.request.method.toLowerCase == "PATCH".toLowerCase
      ) {
        for {
          maxDescriptors  <- (config \ "maxDescriptors")
                               .asOpt[Int]
                               .filter(_ > 0)
                               .map(v => env.datastores.serviceDescriptorDataStore.countAll().map(c => c <= v))
                               .getOrElse(FastFuture.successful(true))
          maxApiKeys      <- (config \ "maxApiKeys")
                               .asOpt[Int]
                               .filter(_ > 0)
                               .map(v => env.datastores.apiKeyDataStore.countAll().map(c => c <= v))
                               .getOrElse(FastFuture.successful(true))
          maxGroups       <- (config \ "maxGroups")
                               .asOpt[Int]
                               .filter(_ > 0)
                               .map(v => env.datastores.serviceGroupDataStore.countAll().map(c => c <= v))
                               .getOrElse(FastFuture.successful(true))
          maxScripts      <- (config \ "maxScripts")
                               .asOpt[Int]
                               .filter(_ > 0)
                               .map(v => env.datastores.scriptDataStore.countAll().map(c => c <= v))
                               .getOrElse(FastFuture.successful(true))
          maxCertificates <- (config \ "maxCertificates")
                               .asOpt[Int]
                               .filter(_ > 0)
                               .map(v => env.datastores.certificatesDataStore.countAll().map(c => c <= v))
                               .getOrElse(FastFuture.successful(true))
          maxVerifiers    <- (config \ "maxVerifiers")
                               .asOpt[Int]
                               .filter(_ > 0)
                               .map(v => env.datastores.globalJwtVerifierDataStore.countAll().map(c => c <= v))
                               .getOrElse(FastFuture.successful(true))
          maxAuthModules  <- (config \ "maxAuthModules")
                               .asOpt[Int]
                               .filter(_ > 0)
                               .map(v => env.datastores.authConfigsDataStore.countAll().map(c => c <= v))
                               .getOrElse(FastFuture.successful(true))
        } yield {
          maxDescriptors &&
          maxApiKeys &&
          maxGroups &&
          maxScripts &&
          maxCertificates &&
          maxVerifiers &&
          maxAuthModules
        }
      } else {
        FastFuture.successful(true)
      }
    } else {
      val callsPerDay   = (config \ "callsPerDay").asOpt[Int].getOrElse(-1)
      val callsPerMonth = (config \ "callsPerMonth").asOpt[Int].getOrElse(-1)
      if (callsPerDay > -1 && callsPerMonth > -1) {
        val dayKey   = s"${env.storageRoot}:plugins:quotas:instance-${DateTime.now().toString("yyyy-MM-dd")}"
        val monthKey = s"${env.storageRoot}:plugins:quotas:instance-${DateTime.now().toString("yyyy-MM")}"
        for {
          dayCount   <- env.datastores.rawDataStore.incr(dayKey)
          monthCount <- env.datastores.rawDataStore.incr(monthKey)
          _          <- env.datastores.rawDataStore.pexpire(dayKey, 24 * 60 * 60 * 1000L)
          _          <- env.datastores.rawDataStore.pexpire(monthKey, 31 * 24 * 60 * 60 * 1000L)
        } yield {
          dayCount <= callsPerDay && monthCount <= callsPerMonth
        }
      } else {
        FastFuture.successful(true)
      }
    }
  }
}

case class ServiceQuotasConfig(
    throttlingQuota: Long = RemainingQuotas.MaxValue,
    dailyQuota: Long = RemainingQuotas.MaxValue,
    monthlyQuota: Long = RemainingQuotas.MaxValue
)

class ServiceQuotas extends AccessValidator {

  override def name: String = "Public quotas"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "ServiceQuotas" -> Json.obj(
          "throttlingQuota" -> 100,
          "dailyQuota"      -> RemainingQuotas.MaxValue,
          "monthlyQuota"    -> RemainingQuotas.MaxValue
        )
      )
    )

  override def description: Option[String] = Some(s"""This plugin will enforce public quotas on the current service
                                                     |
                                                     |This plugin can accept the following configuration
                                                     |
                                                     |```json
                                                     |{
                                                     |  "ServiceQuotas": {
                                                     |    "throttlingQuota": 100,
                                                     |    "dailyQuota": 10000000,
                                                     |    "monthlyQuota": 10000000
                                                     |  }
                                                     |}
                                                     |```""")

  private def totalCallsKey(name: String)(implicit env: Env): String   =
    s"${env.storageRoot}:plugins:services-public-quotas:global:$name"
  private def dailyQuotaKey(name: String)(implicit env: Env): String   =
    s"${env.storageRoot}:plugins:services-public-quotas:daily:$name"
  private def monthlyQuotaKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:plugins:services-public-quotas:monthly:$name"
  private def throttlingKey(name: String)(implicit env: Env): String   =
    s"${env.storageRoot}:plugins:services-public-quotas:second:$name"

  private def updateQuotas(descriptor: ServiceDescriptor, qconf: ServiceQuotasConfig, increment: Long = 1L)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis
    env.clusterAgent.incrementApi(descriptor.id, increment)
    for {
      _            <- env.datastores.rawDataStore.incrby(totalCallsKey(descriptor.id), increment)
      secCalls     <- env.datastores.rawDataStore.incrby(throttlingKey(descriptor.id), increment)
      secTtl       <- env.datastores.rawDataStore.pttl(throttlingKey(descriptor.id)).filter(_ > -1).recoverWith {
                        case _ =>
                          env.datastores.rawDataStore.pexpire(throttlingKey(descriptor.id), env.throttlingWindow * 1000)
                      }
      dailyCalls   <- env.datastores.rawDataStore.incrby(dailyQuotaKey(descriptor.id), increment)
      dailyTtl     <- env.datastores.rawDataStore.pttl(dailyQuotaKey(descriptor.id)).filter(_ > -1).recoverWith {
                        case _ => env.datastores.rawDataStore.pexpire(dailyQuotaKey(descriptor.id), toDayEnd.toInt)
                      }
      monthlyCalls <- env.datastores.rawDataStore.incrby(monthlyQuotaKey(descriptor.id), increment)
      monthlyTtl   <- env.datastores.rawDataStore.pttl(monthlyQuotaKey(descriptor.id)).filter(_ > -1).recoverWith {
                        case _ => env.datastores.rawDataStore.pexpire(monthlyQuotaKey(descriptor.id), toMonthEnd.toInt)
                      }
    } yield ()
    // RemainingQuotas(
    //   authorizedCallsPerSec = qconf.throttlingQuota,
    //   currentCallsPerSec = (secCalls / env.throttlingWindow).toInt,
    //   remainingCallsPerSec = qconf.throttlingQuota - (secCalls / env.throttlingWindow).toInt,
    //   authorizedCallsPerDay = qconf.dailyQuota,
    //   currentCallsPerDay = dailyCalls,
    //   remainingCallsPerDay = qconf.dailyQuota - dailyCalls,
    //   authorizedCallsPerMonth = qconf.monthlyQuota,
    //   currentCallsPerMonth = monthlyCalls,
    //   remainingCallsPerMonth = qconf.monthlyQuota - monthlyCalls
    // )
  }

  private def withingQuotas(descriptor: ServiceDescriptor, qconf: ServiceQuotasConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] =
    for {
      sec <- withinThrottlingQuota(descriptor, qconf)
      day <- withinDailyQuota(descriptor, qconf)
      mon <- withinMonthlyQuota(descriptor, qconf)
    } yield sec && day && mon

  private def withinThrottlingQuota(
      descriptor: ServiceDescriptor,
      qconf: ServiceQuotasConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.rawDataStore
      .get(throttlingKey(descriptor.id))
      .fast
      .map(_.map(_.utf8String.toLong).getOrElse(0L) <= (qconf.throttlingQuota * env.throttlingWindow))

  private def withinDailyQuota(descriptor: ServiceDescriptor, qconf: ServiceQuotasConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] =
    env.datastores.rawDataStore
      .get(dailyQuotaKey(descriptor.id))
      .fast
      .map(_.map(_.utf8String.toLong).getOrElse(0L) < qconf.dailyQuota)

  private def withinMonthlyQuota(descriptor: ServiceDescriptor, qconf: ServiceQuotasConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] =
    env.datastores.rawDataStore
      .get(monthlyQuotaKey(descriptor.id))
      .fast
      .map(_.map(_.utf8String.toLong).getOrElse(0L) < qconf.monthlyQuota)

  override def canAccess(ctx: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val config = ctx.configFor("ServiceQuotas")
    val qconf  = ServiceQuotasConfig(
      throttlingQuota = (config \ "throttlingQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
      dailyQuota = (config \ "dailyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
      monthlyQuota = (config \ "monthlyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue)
    )
    withingQuotas(ctx.descriptor, qconf).flatMap {
      case true  => updateQuotas(ctx.descriptor, qconf).map(_ => true)
      case false => FastFuture.successful(false)
    }
  }
}
