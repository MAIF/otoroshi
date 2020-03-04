package health

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Props}
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Keep, Sink, Source}
import env.Env
import events.HealthCheckEvent
import models.{SecComVersion, ServiceDescriptor, Target}
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

  implicit lazy val ec  = context.dispatcher
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-health-checker")

  def checkTarget(desc: ServiceDescriptor, target: Target): Future[Unit] = {
    val url = s"${target.scheme}://${target.host}${desc.healthCheck.url}"
    val start = System.currentTimeMillis()
    val stateValue = IdGenerator.extendedToken(128)
    val state = desc.secComVersion match {
      case SecComVersion.V1 => stateValue
      case SecComVersion.V2 =>
        val jti = IdGenerator.uuid
        OtoroshiClaim(
          iss = env.Headers.OtoroshiIssuer,
          sub = env.Headers.OtoroshiIssuer,
          aud = desc.name,
          exp = DateTime
            .now()
            .plusSeconds(desc.secComTtl.toSeconds.toInt)
            .toDate
            .getTime,
          iat = DateTime.now().toDate.getTime,
          jti = jti
        ).withClaim("state", stateValue).serialize(desc.secComSettings)
    }
    val value = env.snowflakeGenerator.nextIdStr()
    val claim = desc.generateInfoToken(
      None,
      None,
      None,
      Some(env.Headers.OtoroshiIssuer),
      Some("HealthChecker")
    ).serialize(desc.secComSettings)(env)
    env.MtlsWs
      .url(url, target.mtlsConfig)
      .withRequestTimeout(Duration(30, TimeUnit.SECONDS))
      .withHttpHeaders(
        env.Headers.OtoroshiState -> state,
        env.Headers.OtoroshiClaim -> claim,
        env.Headers.OtoroshiHealthCheckLogicTest -> value
      )
      .withMaybeProxyServer(
        desc.clientConfig.proxy.orElse(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.services))
      )
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
              case (a, true) if a > 199 && a < 500 => Some("GREEN")
              case (a, false) if a > 199 && a < 500 => Some("YELLOW")
              case _ => Some("RED")
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
            health = Some("BLACK")
          )
          hce.toAnalytics()
          hce.pushToRedis()
          env.datastores.globalConfigDataStore.singleton().map { config =>
            env.metrics.markString(s"services.${desc.id}.health", hce.health.getOrElse("BLACK"))
          }
        }
      }
      .map(_ => ())
      .recover {
        case e => ()
      }
  }


  def checkService(desc: ServiceDescriptor): Future[Unit] = {
    desc.exists().flatMap {
      case false => FastFuture.successful(())
      case true => {
        Source(desc.targets.toList)
          .mapAsync(1)(target => checkTarget(desc, target))
          .toMat(Sink.ignore)(Keep.right)
          .run()
          .map(_ => ())
      }
    }
  }

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
