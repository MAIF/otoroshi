package health

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Props}
import env.Env
import events.HealthCheckEvent
import models.ServiceDescriptor
import org.joda.time.DateTime
import play.api.Logger
import security.{IdGenerator, OtoroshiClaim}

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

case class StartHealthCheck()
case class ReStartHealthCheck()
case class CheckFirstService(startedAt: DateTime, services: Seq[ServiceDescriptor])

object HealthCheckerActor {
  def props(implicit env: Env) = Props(new HealthCheckerActor())
}

class HealthCheckerActor()(implicit env: Env) extends Actor {

  import utils.http.Implicits._

  implicit lazy val ec = context.dispatcher
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-health-checker")

  def checkService(desc: ServiceDescriptor): Future[Unit] =
    Future
      .sequence(desc.targets.map(t => s"${t.scheme}://${t.host}${desc.healthCheck.url}").map { url =>
        val start = System.currentTimeMillis()
        val state = IdGenerator.extendedToken(128)
        val value = env.snowflakeGenerator.nextIdStr()
        val claim = OtoroshiClaim(
          iss = env.Headers.OtoroshiIssuer,
          sub = "HealthChecker",
          aud = desc.name,
          exp = DateTime.now().plusSeconds(30).toDate.getTime,
          iat = DateTime.now().toDate.getTime,
          jti = IdGenerator.uuid
        ).serialize(desc.secComSettings)(env)
        env.Ws
          .url(url)
          .withRequestTimeout(Duration(30, TimeUnit.SECONDS))
          .withHttpHeaders(
            env.Headers.OtoroshiState                -> state,
            env.Headers.OtoroshiClaim                -> claim,
            env.Headers.OtoroshiHealthCheckLogicTest -> value
          )
          .withMaybeProxyServer(desc.clientConfig.proxy.orElse(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.services)))
          .get()
          .andThen {
            case Success(res) => {
              val checkDone =
                res.header(env.Headers.OtoroshiHealthCheckLogicTestResult).exists(_.toLong == value.toLong + 42L)
              val hce = HealthCheckEvent(
                `@id` = value,
                `@timestamp` = DateTime.now(),
                `@serviceId` = desc.id,
                `@service` = desc.name,
                `@product` = desc.metadata.getOrElse("product", "--"),
                url = url,
                duration = System.currentTimeMillis() - start,
                status = res.status,
                logicCheck = checkDone,
                error = None,
                health = (res.status, checkDone) match {
                  case (a, true) if a > 199 && a < 500  => Some("GREEN")
                  case (a, false) if a > 199 && a < 500 => Some("YELLOW")
                  case _                                => Some("RED")
                }
              )
              hce.toAnalytics()
              hce.pushToRedis()
              env.datastores.globalConfigDataStore.singleton().map { config =>
                env.metrics.markString(s"services.${desc.id}.health", hce.health.getOrElse("RED"))
              }
              res.ignore()
            }
            case Failure(error) => {
              // error.printStackTrace()
              logger.error(s"Error while checking health of service '${desc.name}' at '${url}'", error)
              val hce = HealthCheckEvent(
                `@id` = value,
                `@timestamp` = DateTime.now(),
                `@serviceId` = desc.id,
                `@service` = desc.name,
                `@product` = desc.metadata.getOrElse("product", "--"),
                url = url,
                duration = System.currentTimeMillis() - start,
                status = 0,
                logicCheck = false,
                error = Some(error.getMessage),
                health = Some("RED")
              )
              hce.toAnalytics()
              hce.pushToRedis()
              env.datastores.globalConfigDataStore.singleton().map { config =>
                env.metrics.markString(s"services.${desc.id}.health", hce.health.getOrElse("RED"))
              }
            }
          }
      })
      .map(_ => ())

  override def receive: Receive = {
    case CheckFirstService(startedAt, services) if services.isEmpty => {
      val myself = self
      logger.trace(
        s"HealthCheck round started at $startedAt finished after ${System.currentTimeMillis() - startedAt.getMillis} ms. Starting a new one soon ..."
      )
      env.timeout(Duration(60000, TimeUnit.MILLISECONDS)).map(_ => myself ! ReStartHealthCheck())
    }
    case CheckFirstService(startedAt, services) if services.nonEmpty && services.size == 1 => {
      val myself = self
      // logger.trace(s"CheckFirstService 1")
      checkService(services.head).andThen {
        case Success(_) => myself ! CheckFirstService(startedAt, Seq.empty[ServiceDescriptor])
        case Failure(error) => {
          logger.error(s"error while checking health on service ${services.head.name}", error)
          env.timeout(Duration(300, TimeUnit.MILLISECONDS)).map(_ => myself ! CheckFirstService(startedAt, services))
        }
      }
    }
    case CheckFirstService(startedAt, services) if services.nonEmpty => {
      val myself = self
      // logger.trace(s"CheckFirstService n")
      checkService(services.head).andThen {
        case Success(_) => myself ! CheckFirstService(startedAt, services.tail)
        case Failure(error) => {
          logger.error(s"error while checking health on service ${services.head.name}", error)
          env.timeout(Duration(300, TimeUnit.MILLISECONDS)).map(_ => myself ! CheckFirstService(startedAt, services))
        }
      }
    }
    case StartHealthCheck() => {
      val myself = self
      val date   = DateTime.now()
      logger.trace(s"StartHealthCheck at $date")
      env.datastores.serviceDescriptorDataStore.findAll().andThen {
        case Success(descs) => myself ! CheckFirstService(date, descs.filter(_.healthCheck.enabled))
        case Failure(error) => myself ! ReStartHealthCheck()
      }
    }
    case ReStartHealthCheck() => {
      val myself = self
      val date   = DateTime.now()
      logger.trace(s"StartHealthCheck at $date")
      env.datastores.serviceDescriptorDataStore.findAll().andThen {
        case Success(descs) => myself ! CheckFirstService(date, descs.filter(_.healthCheck.enabled))
        case Failure(error) => myself ! ReStartHealthCheck()
      }
    }
    case e => logger.trace(s"Received unknown message $e")
  }
}
