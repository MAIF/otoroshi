package otoroshi.health

import java.util.concurrent.TimeUnit
import akka.actor.{Actor, Props}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.events.HealthCheckEvent
import otoroshi.gateway.Retry
import otoroshi.models.{SecComVersion, ServiceDescriptor, Target}
import org.joda.time.DateTime
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import play.api.Logger
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.cache.types.UnboundedTrieMap

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success}
import scala.concurrent.duration._
import otoroshi.utils.syntax.implicits._

case class StartHealthCheck()
case class ReStartHealthCheck()
case class CheckFirstService(startedAt: DateTime, services: Seq[ServiceDescriptor])

object HealthCheck {

  import otoroshi.utils.http.Implicits._

  val badHealth = new UnboundedTrieMap[String, Unit]()

  def checkTarget(desc: ServiceDescriptor, target: Target, logger: Logger)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Unit] = {
    Retry.retry(times = 3, delay = 20, ctx = "check-target-health") { tryCount =>
      val url        = s"${target.scheme}://${target.host}${desc.healthCheck.url}"
      val start      = System.currentTimeMillis()
      val stateValue = IdGenerator.extendedToken(128)
      val state      = desc.secComVersion match {
        case SecComVersion.V1 => stateValue
        case SecComVersion.V2 =>
          val jti = IdGenerator.uuid
          OtoroshiClaim(
            iss = env.Headers.OtoroshiIssuer,
            sub = env.Headers.OtoroshiIssuer,
            aud = desc.name,
            exp = DateTime
              .now()
              .plus(desc.secComTtl.toMillis)
              .toDate
              .getTime,
            iat = DateTime.now().toDate.getTime,
            jti = jti
          ).withClaim("state", stateValue).serialize(desc.algoChallengeFromOtoToBack)
      }
      val value      = env.snowflakeGenerator.nextIdStr()
      val claim      = desc
        .generateInfoToken(
          None,
          None,
          None,
          Some(env.Headers.OtoroshiIssuer),
          Some("HealthChecker")
        )
        .serialize(desc.algoInfoFromOtoToBack)(env)

      env.MtlsWs
        .url(url, target.mtlsConfig)
        .withRequestTimeout(Duration(desc.healthCheck.timeout, TimeUnit.MILLISECONDS))
        .withHttpHeaders(
          env.Headers.OtoroshiState                -> state,
          env.Headers.OtoroshiClaim                -> claim,
          env.Headers.OtoroshiHealthCheckLogicTest -> value
        )
        .withMaybeProxyServer(
          desc.clientConfig.proxy.orElse(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.services))
        )
        .get()
        .andThen {
          case Success(res)   => {
            val checkDone =
              res.header(env.Headers.OtoroshiHealthCheckLogicTestResult).exists(_.toLong == value.toLong + 42L)

            val useDefaultConfiguration =
              desc.healthCheck.healthyStatuses.isEmpty && desc.healthCheck.unhealthyStatuses.isEmpty

            val rawHealth = (res.status, checkDone) match {
              case (a, true) if a > 199 && a < 500  => Some("GREEN")
              case (a, false) if a > 199 && a < 500 => Some("YELLOW")
              case _                                => Some("RED")
            }

            val health = if (useDefaultConfiguration) {
              rawHealth
            } else {
              if (desc.healthCheck.unhealthyStatuses.contains(res.status)) {
                Some("RED")
              } else if (desc.healthCheck.healthyStatuses.contains(res.status)) {
                if (checkDone) {
                  Some("GREEN")
                } else {
                  Some("YELLOW")
                }
              } else { // if not contains in both list, just resolve with error
                Some("RED")
              }
            }

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
              health = health
            )
            hce.toAnalytics()
            hce.pushToRedis()
            if ((desc.healthCheck.blockOnRed || env.healtCheckBlockOnRed) && health.contains("RED")) {
              env.datastores.rawDataStore.set(
                s"${env.storageRoot}:targets:bad-health:${target.asCleanTarget}",
                ByteString(DateTime.now().toString()),
                Some(env.healtCheckTTL)
              )
            } else {
              HealthCheck.badHealth.remove(target.asCleanTarget)
              if (!env.healtCheckTTLOnly) {
                env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:targets:bad-health:${target.asCleanTarget}"))
              }
            }
            env.datastores.globalConfigDataStore.singleton().map { config =>
              env.metrics.markString(s"services.${desc.id}.health", hce.health.getOrElse("RED"))
            }
            res.ignore()
          }
          case Failure(error) => {
            // error.printStackTrace()
            logger.error(s"Error while checking health of service '${desc.name}' at '${url}': ${error.getMessage}")
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
            HealthCheck.badHealth.put(target.asCleanTarget, ())
            env.datastores.rawDataStore.set(
              s"${env.storageRoot}:targets:bad-health:${target.asCleanTarget}",
              ByteString(DateTime.now().toString()),
              Some(env.healtCheckTTL)
            )
            env.datastores.globalConfigDataStore.singleton().map { config =>
              env.metrics.markString(s"services.${desc.id}.health", hce.health.getOrElse("BLACK"))
            }
          }
        }
        .map(_ => ())
        .recover { case e =>
          ()
        }
    }(ec, env.otoroshiActorSystem.scheduler)
  }
}

object HealthCheckerActor {
  def props(implicit env: Env) = Props(new HealthCheckerActor())
}

class HealthCheckerActor()(implicit env: Env) extends Actor {

  implicit lazy val ec  = context.dispatcher
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-health-checker")

  def checkService(desc: ServiceDescriptor): Future[Unit] = {
    desc.exists().flatMap {
      case false => FastFuture.successful(())
      case true  => {
        Source(desc.targets.toList)
          .mapAsync(1)(target => HealthCheck.checkTarget(desc, target, logger))
          .toMat(Sink.ignore)(Keep.right)
          .run()
          .map(_ => ())
      }
    }
  }

  override def receive: Receive = {
    case CheckFirstService(startedAt, services) if services.isEmpty                        => {
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
        case Success(_)     => myself ! CheckFirstService(startedAt, Seq.empty[ServiceDescriptor])
        case Failure(error) => {
          logger.error(s"error while checking health on service ${services.head.name}", error)
          env.timeout(Duration(300, TimeUnit.MILLISECONDS)).map(_ => myself ! CheckFirstService(startedAt, services))
        }
      }
    }
    case CheckFirstService(startedAt, services) if services.nonEmpty                       => {
      val myself = self
      // logger.trace(s"CheckFirstService n")
      checkService(services.head).andThen {
        case Success(_)     => myself ! CheckFirstService(startedAt, services.tail)
        case Failure(error) => {
          logger.error(s"error while checking health on service ${services.head.name}", error)
          env.timeout(Duration(300, TimeUnit.MILLISECONDS)).map(_ => myself ! CheckFirstService(startedAt, services))
        }
      }
    }
    case StartHealthCheck()                                                                => {
      val myself            = self
      val date              = DateTime.now()
      if (logger.isTraceEnabled) logger.trace(s"StartHealthCheck at $date")
      val services          = env.proxyState.allServices()
      val routes            = env.proxyState.allRoutes()
      val routeCompositions = env.proxyState.allRouteCompositions()
      val descs             = services ++ routes.map(_.legacy) ++ routeCompositions.flatMap(_.toRoutes.map(_.legacy))
      myself ! CheckFirstService(date, descs.filter(_.healthCheck.enabled))
    }
    case ReStartHealthCheck()                                                              => {
      val myself            = self
      val date              = DateTime.now()
      if (logger.isTraceEnabled) logger.trace(s"StartHealthCheck at $date")
      val services          = env.proxyState.allServices()
      val routes            = env.proxyState.allRoutes()
      val routeCompositions = env.proxyState.allRouteCompositions()
      val descs             = services ++ routes.map(_.legacy) ++ routeCompositions.flatMap(_.toRoutes.map(_.legacy))
      myself ! CheckFirstService(date, descs.filter(_.healthCheck.enabled))
    }
    case e                                                                                 => if (logger.isTraceEnabled) logger.trace(s"Received unknown message $e")
  }
}

class HealthCheckJob extends Job {

  private val logger = Logger("otoroshi-healthcheck-job")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.health.HealthCheckJob")

  override def name: String = "Otoroshi health check job"

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 60.seconds.some

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    implicit val mat      = env.otoroshiMaterializer
    val parallelChecks    = env.healtCheckWorkers
    val services          = env.proxyState.allServices()
    val routes            = env.proxyState.allRawRoutes()
    val routeCompositions = env.proxyState.allRouteCompositions()
    val descs             = services ++ routes.map(_.legacy) ++ routeCompositions.flatMap(_.toRoutes.map(_.legacy))
    val targets           = descs
      .filter(_.healthCheck.enabled)
      .flatMap(s => s.targets.map(t => (t, s)))
      .distinct
      .toList

    Source(targets)
      .mapAsync(parallelChecks) { case (target, service) =>
        logger.debug(s"checking health of ${service.name} - ${target.asTargetStr}")
        HealthCheck.checkTarget(service, target, logger)
      }
      .runWith(Sink.ignore)
      .map(_ => ())
  }
}

class HealthCheckLocalCacheJob extends Job {

  private val logger = Logger("otoroshi-healthcheck-local-cache-job")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.health.HealthCheckLocalCacheJob")

  override def name: String = "Otoroshi health check local cache job"

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    env.datastores.rawDataStore.keys(s"${env.storageRoot}:targets:bad-health:*").map { keys =>
      HealthCheck.badHealth.clear()
      keys.foreach { key =>
        val target = key.replace(s"${env.storageRoot}:targets:bad-health:", "")
        HealthCheck.badHealth.put(target, ())
      }
    }
  }
}
