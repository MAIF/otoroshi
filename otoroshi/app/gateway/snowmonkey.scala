package gateway

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import env.Env
import events._
import models._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Result, Results}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

case class SnowMonkeyContext(trailingRequestBodyStream: Source[ByteString, _],
                             trailingResponseBodyStream: Source[ByteString, _],
                             trailingRequestBodySize: Int = 0,
                             trailingResponseBodySize: Int = 0)

class SnowMonkey(implicit env: Env) {

  private val logger = Logger("otoroshi-snowmonkey")
  private val random = new scala.util.Random
  private val spaces = ByteString.fromString("        ")

  private def durationToHumanReadable(fdur: FiniteDuration): String = {
    val duration     = fdur.toMillis
    val milliseconds = duration % 1000L
    val seconds      = (duration / 1000L) % 60L
    val minutes      = (duration / (1000L * 60L)) % 60L
    val hours        = (duration / (1000L * 3600L)) % 24L
    val days         = (duration / (1000L * 86400L)) % 7L
    val weeks        = (duration / (1000L * 604800L)) % 4L
    val months       = (duration / (1000L * 2592000L)) % 52L
    val years        = (duration / (1000L * 31556952L)) % 10L
    val decades      = (duration / (1000L * 31556952L * 10L)) % 10L
    val centuries    = (duration / (1000L * 31556952L * 100L)) % 100L
    val millenniums  = (duration / (1000L * 31556952L * 1000L)) % 1000L
    val megaannums   = duration / (1000L * 31556952L * 1000000L)

    val sb = new scala.collection.mutable.StringBuilder()

    if (megaannums > 0) sb.append(megaannums + " megaannums ")
    if (millenniums > 0) sb.append(millenniums + " millenniums ")
    if (centuries > 0) sb.append(centuries + " centuries ")
    if (decades > 0) sb.append(decades + " decades ")
    if (years > 0) sb.append(years + " years ")
    if (months > 0) sb.append(months + " months ")
    if (weeks > 0) sb.append(weeks + " weeks ")
    if (days > 0) sb.append(days + " days ")
    if (hours > 0) sb.append(hours + " hours ")
    if (minutes > 0) sb.append(minutes + " minutes ")
    if (seconds > 0) sb.append(seconds + " seconds ")
    if (minutes < 1 && hours < 1 && days < 1) {
      if (sb.nonEmpty) sb.append(" ")
      sb.append(milliseconds + " milliseconds")
    }
    sb.toString().trim
  }

  private def inRatio(ratio: Double, counter: Long): Boolean = {
    val left       = Math.abs(counter) % 100
    val percentage = ((ratio - 0.1) * 100).toInt + 1
    left <= percentage
  }

  private def applyChaosConfig(reqNumber: Long, config: ChaosConfig, hasBody: Boolean)(
      f: SnowMonkeyContext => Future[Result]
  )(implicit ec: ExecutionContext): Future[Result] = {
    config.latencyInjectionFaultConfig
      .filter(c => inRatio(c.ratio, reqNumber))
      .map { conf =>
        val bound =
          if (conf.to.toMillis.toInt == conf.from.toMillis.toInt) conf.to.toMillis.toInt
          else conf.to.toMillis.toInt - conf.from.toMillis.toInt
        val latency =
          if (conf.to.toMillis.toInt == 0) 0.millis
          else (conf.from.toMillis + random.nextInt(bound)).millis
        env.timeout(latency).map(_ => latency.toMillis)
      }
      .getOrElse(FastFuture.successful(0))
      .flatMap { latency =>
        val (requestTrailingBodySize, requestTrailingBody) = config.largeRequestFaultConfig
          .filter(c => c.additionalRequestSize > 8)
          .filter(_ => hasBody)
          .filter(c => inRatio(c.ratio, reqNumber))
          .map(c => (c.additionalRequestSize, Source.repeat(spaces).take(c.additionalRequestSize / 8)))
          .getOrElse((0, Source.empty[ByteString]))
        val (responseTrailingBodySize, responseTrailingBody) = config.largeResponseFaultConfig
          .filter(c => c.additionalResponseSize > 8)
          .filter(c => inRatio(c.ratio, reqNumber))
          .map(c => (c.additionalResponseSize, Source.repeat(spaces).take(c.additionalResponseSize / 8)))
          .getOrElse((0, Source.empty[ByteString]))
        val context = SnowMonkeyContext(requestTrailingBody,
                                        responseTrailingBody,
                                        requestTrailingBodySize,
                                        responseTrailingBodySize)
        config.badResponsesFaultConfig
          .filter(c => inRatio(c.ratio, reqNumber))
          .map { conf =>
            val index = reqNumber % (if (conf.responses.nonEmpty) conf.responses.size else 1)
            val response = conf.responses.applyOrElse(
              index.toInt,
              (i: Int) => BadResponse(status = 500, body = "error", Map("Content-Type" -> "text/plain"))
            )
            // error
            FastFuture.successful(
              Results
                .Status(response.status)
                .apply(response.body)
                .withHeaders(
                  (response.headers.toSeq :+ ("SnowMonkey-Latency" -> latency.toString))
                    .filterNot(_._1.toLowerCase() == "content-type"): _*
                )
                .as(response.headers.getOrElse("Content-Type", "text/plain"))
            )
          }
          .getOrElse {
            // pass here
            f(context)
              .map { response =>
                response.withHeaders("SnowMonkey-Latency" -> latency.toString)
              }
              .recover {
                case e =>
                  e.printStackTrace()
                  Results.InternalServerError(Json.obj("error" -> e.getMessage))
              }
          }
      }
  }

  private def isCurrentOutage(descriptor: ServiceDescriptor,
                              conf: SnowMonkeyConfig)(implicit ec: ExecutionContext): Future[Boolean] = {
    env.datastores.chaosDataStore.serviceAlreadyOutage(descriptor.id)
  }

  private def needMoreOutageForToday(isCurrentOutage: Boolean, descriptor: ServiceDescriptor, conf: SnowMonkeyConfig)(
      implicit ec: ExecutionContext
  ): Future[Boolean] = {
    if (isCurrentOutage) {
      FastFuture.successful(true)
    } else {
      conf.outageStrategy match {
        case OneServicePerGroup =>
          env.datastores.chaosDataStore.groupOutages(descriptor.groupId).flatMap {
            case count if count < conf.timesPerDay =>
              env.datastores.chaosDataStore
                .registerOutage(descriptor, conf)
                .andThen {
                  case Success(duration) =>
                    val event = SnowMonkeyOutageRegisteredEvent(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      "SNOWMONKEY_OUTAGE_REGISTERED",
                      s"Snow monkey outage registered",
                      conf,
                      descriptor,
                      conf.dryRun
                    )
                    Audit.send(event)
                    Alerts.send(
                      SnowMonkeyOutageRegisteredAlert(
                        env.snowflakeGenerator.nextIdStr(),
                        env.env,
                        event
                      )
                    )
                    logger.warn(
                      s"Registering outage on ${descriptor.name} (${descriptor.id}) for ${durationToHumanReadable(duration)} - from ${DateTime
                        .now()} to ${DateTime.now().plusMillis(duration.toMillis.toInt)}"
                    )
                }
                .map(_ => true)
            case _ => FastFuture.successful(false)
          }
        case AllServicesPerGroup =>
          env.datastores.chaosDataStore.serviceOutages(descriptor.id).flatMap {
            case count if count < conf.timesPerDay =>
              env.datastores.chaosDataStore
                .registerOutage(descriptor, conf)
                .andThen {
                  case Success(duration) =>
                    val event = SnowMonkeyOutageRegisteredEvent(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      "SNOWMONKEY_OUTAGE_REGISTERED",
                      s"User started snowmonkey",
                      conf,
                      descriptor,
                      conf.dryRun
                    )
                    Audit.send(event)
                    Alerts.send(
                      SnowMonkeyOutageRegisteredAlert(
                        env.snowflakeGenerator.nextIdStr(),
                        env.env,
                        event
                      )
                    )
                    logger.warn(
                      s"Registering outage on ${descriptor.name} (${descriptor.id}) for ${durationToHumanReadable(duration)} - from ${DateTime
                        .now()} to ${DateTime.now().plusMillis(duration.toMillis.toInt)}"
                    )
                }
                .map(_ => true)
            case _ => FastFuture.successful(false)
          }
      }
    }
  }

  private def isOutage(descriptor: ServiceDescriptor,
                       config: SnowMonkeyConfig)(implicit ec: ExecutionContext): Future[Boolean] = {
    for {
      isCurrentOutage        <- isCurrentOutage(descriptor, config)
      needMoreOutageForToday <- needMoreOutageForToday(isCurrentOutage, descriptor, config)
    } yield {
      if ((config.targetGroups.isEmpty || config.targetGroups.contains(descriptor.groupId)) && descriptor.id != env.backOfficeServiceId) {
        isCurrentOutage || needMoreOutageForToday
      } else {
        false
      }
    }
  }

  private def betweenDates(config: SnowMonkeyConfig): Boolean = {
    val time = DateTime.now().toLocalTime
    time.isAfter(config.startTime) && time.isBefore(config.stopTime)
  }

  private def introduceServiceDefinedChaos(reqNumber: Long, desc: ServiceDescriptor, hasBody: Boolean)(
      f: SnowMonkeyContext => Future[Result]
  )(implicit ec: ExecutionContext): Future[Result] = {
    applyChaosConfig(reqNumber, desc.chaosConfig, hasBody)(f)
  }

  private def notUserFacing(descriptor: ServiceDescriptor): Boolean =
    !descriptor.userFacing && !descriptor.publicPatterns.contains("/.*")

  private def introduceSnowMonkeyDefinedChaos(reqNumber: Long,
                                              config: SnowMonkeyConfig,
                                              desc: ServiceDescriptor,
                                              hasBody: Boolean)(
      f: SnowMonkeyContext => Future[Result]
  )(implicit ec: ExecutionContext): Future[Result] = {
    isOutage(desc, config).flatMap {
      case true if !config.dryRun && config.includeUserFacingDescriptors =>
        applyChaosConfig(reqNumber, config.chaosConfig, hasBody)(f)
      case true if !config.dryRun && !config.includeUserFacingDescriptors && notUserFacing(desc) =>
        applyChaosConfig(reqNumber, config.chaosConfig, hasBody)(f)
      case _ =>
        f(
          SnowMonkeyContext(
            Source.empty[ByteString],
            Source.empty[ByteString]
          )
        )
    }
  }

  def introduceChaos(reqNumber: Long, config: GlobalConfig, desc: ServiceDescriptor, hasBody: Boolean)(
      f: SnowMonkeyContext => Future[Result]
  )(implicit ec: ExecutionContext): Future[Result] = {
    if (desc.id == env.backOfficeServiceId) {
      f(
        SnowMonkeyContext(
          Source.empty[ByteString],
          Source.empty[ByteString]
        )
      )
    } else if (config.snowMonkeyConfig.enabled && betweenDates(config.snowMonkeyConfig)) {
      // logger.warn(Json.prettyPrint(config.snowMonkeyConfig.asJson))
      introduceSnowMonkeyDefinedChaos(reqNumber, config.snowMonkeyConfig, desc, hasBody)(f)
    } else {
      if (desc.chaosConfig.enabled) {
        introduceServiceDefinedChaos(reqNumber, desc, hasBody)(f)
      } else {
        f(
          SnowMonkeyContext(
            Source.empty[ByteString],
            Source.empty[ByteString]
          )
        )
      }
    }
  }
}
