package otoroshi.plugins.quotas

import akka.http.scaladsl.util.FastFuture
import env.Env
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
            "callsPerDay" -> -1,
            "callsPerMonth" -> -1,
            "maxDescriptors" -> -1,
            "maxApiKeys" -> -1,
            "maxGroups" -> -1,
            "maxScripts" -> -1,
            "maxCertificates" -> -1,
            "maxVerifiers" -> -1,
            "maxAuthModules" -> -1,
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

  def canAccess(ctx: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val config = ctx.configFor("InstanceQuotas")
    if (ctx.descriptor.id == env.backOfficeServiceId) {
      if (ctx.request.method.toLowerCase == "POST".toLowerCase || ctx.request.method.toLowerCase == "PUT".toLowerCase || ctx.request.method.toLowerCase == "PATCH".toLowerCase) {
        for {
          maxDescriptors <- (config \ "maxDescriptors").asOpt[Int].filter(_ > 0).map(v => env.datastores.serviceDescriptorDataStore.countAll().map(c => c <= v)).getOrElse(FastFuture.successful(true))
          maxApiKeys <- (config \ "maxApiKeys").asOpt[Int].filter(_ > 0).map(v => env.datastores.apiKeyDataStore.countAll().map(c => c <= v)).getOrElse(FastFuture.successful(true))
          maxGroups <- (config \ "maxGroups").asOpt[Int].filter(_ > 0).map(v => env.datastores.serviceGroupDataStore.countAll().map(c => c <= v)).getOrElse(FastFuture.successful(true))
          maxScripts <- (config \ "maxScripts").asOpt[Int].filter(_ > 0).map(v => env.datastores.scriptDataStore.countAll().map(c => c <= v)).getOrElse(FastFuture.successful(true))
          maxCertificates <- (config \ "maxCertificates").asOpt[Int].filter(_ > 0).map(v => env.datastores.certificatesDataStore.countAll().map(c => c <= v)).getOrElse(FastFuture.successful(true))
          maxVerifiers <- (config \ "maxVerifiers").asOpt[Int].filter(_ > 0).map(v => env.datastores.globalJwtVerifierDataStore.countAll().map(c => c <= v)).getOrElse(FastFuture.successful(true))
          maxAuthModules <- (config \ "maxAuthModules").asOpt[Int].filter(_ > 0).map(v => env.datastores.authConfigsDataStore.countAll().map(c => c <= v)).getOrElse(FastFuture.successful(true))
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
      val callsPerDay = (config \ "callsPerDay").asOpt[Int].getOrElse(-1)
      val callsPerMonth = (config \ "callsPerMonth").asOpt[Int].getOrElse(-1)
      if (callsPerDay > -1 && callsPerMonth > -1) {
        val dayKey = s"${env.storageRoot}:plugins:quotas:instance-${DateTime.now().toString("yyyy-MM-dd")}"
        val monthKey = s"${env.storageRoot}:plugins:quotas:instance-${DateTime.now().toString("yyyy-MM")}"
        for {
          dayCount <- env.datastores.rawDataStore.incr(dayKey)
          monthCount <- env.datastores.rawDataStore.incr(monthKey)
          _ <- env.datastores.rawDataStore.pexpire(dayKey, 24 * 60 * 60 * 1000L)
          _ <- env.datastores.rawDataStore.pexpire(monthKey, 31 * 24 * 60 * 60 * 1000L)
        } yield {
          dayCount <= callsPerDay && monthCount <= callsPerMonth
        }
      } else {
        FastFuture.successful(true)
      }
    }
  }
}