package gateway

import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import env.Env
import models._
import org.joda.time.DateTime
import play.api.mvc.{Result, Results}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class SnowMonkeyContext(trailingRequestBodyStream: Source[ByteString, NotUsed], trailingResponseBodyStream: Source[ByteString, NotUsed])

class SnowMonkey(implicit env: Env) {

  private val random = new scala.util.Random
  private val spaces = ByteString.fromString("        ")

  private def inRatio(ratio: Double, counter: Long): Boolean = {
    val left = Math.abs(counter) % 10
    val percentage = ((ratio - 0.1) * 10).toInt + 1
    left <= percentage
  }

  private def applyChaosConfig(reqNumber: Long, config: ChaosConfig)(f: SnowMonkeyContext => Future[Result])(implicit ec: ExecutionContext): Future[Result] = {
    config.latencyInjectionFaultConfig.filter(c => inRatio(c.ratio, reqNumber)).map { conf =>
      val latency = (conf.from.toMillis + random.nextInt(conf.to.toMillis.toInt - conf.from.toMillis.toInt)).millis
      env.timeout(latency).map(_ => latency.toMillis)
    }.getOrElse(FastFuture.successful(0)).flatMap { latency =>
      val requestTrailingBody = config.largeRequestFaultConfig.map(c => Source.repeat(spaces).limit(c.additionalRequestSize)).getOrElse(Source.empty[ByteString])
      val responseTrailingBody = config.largeResponseFaultConfig.map(c => Source.repeat(spaces).limit(c.additionalResponseSize)).getOrElse(Source.empty[ByteString])
      val context = SnowMonkeyContext(requestTrailingBody, responseTrailingBody)
      config.badResponsesFaultConfig.filter(c => inRatio(c.ratio, reqNumber)).map { conf =>
        val index = reqNumber % (if (conf.responses.nonEmpty) conf.responses.size else 1)
        val response = conf.responses.apply(index.toInt)
        // error
        FastFuture.successful(Results.Status(response.status)
          .apply(response.body)
          .withHeaders((response.headers.toSeq :+ ("SnowMonkey-Latency" -> latency.toString)): _*)
          .as(response.headers.getOrElse("Content-Type", "text/plain")))
      }.getOrElse {
        // pass here
        f(context).map(_.withHeaders("SnowMonkey-Latency" -> latency.toString))
      }
    }
  }

  private def isCurrentOutage(descriptor: ServiceDescriptor, conf: SnowMonkeyConfig)(implicit ec: ExecutionContext): Future[Boolean] = {
    env.datastores.chaosDataStore.serviceAlreadyOutage(descriptor.id)
  }

  private def needMoreOutageForToday(descriptor: ServiceDescriptor, conf: SnowMonkeyConfig)(implicit ec: ExecutionContext): Future[Boolean] = {
    conf.outageStrategy match {
      case OneServicePerGroup => env.datastores.chaosDataStore.groupOutages(descriptor.groupId).flatMap {
        case count if count < conf.timesPerDay => env.datastores.chaosDataStore.registerOutage(descriptor, conf).map(_ => true)
        case _ => FastFuture.successful(false)
      }
      case AllServicesPerGroup => env.datastores.chaosDataStore.serviceOutages(descriptor.id).flatMap {
        case count if count < conf.timesPerDay => env.datastores.chaosDataStore.registerOutage(descriptor, conf).map(_ => true)
        case _ => FastFuture.successful(false)
      }
    }
  }

  private def isOutage(descriptor: ServiceDescriptor, config: SnowMonkeyConfig)(implicit ec: ExecutionContext): Future[Boolean] = {
    for {
      needMoreOutageForToday <- needMoreOutageForToday(descriptor, config)
      isCurrentOutage <- isCurrentOutage(descriptor, config)
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

  private def introduceServiceDefinedChaos(reqNumber: Long, desc: ServiceDescriptor)(f: SnowMonkeyContext => Future[Result])(implicit ec: ExecutionContext): Future[Result] = {
    applyChaosConfig(reqNumber, desc.chaosConfig)(f)
  }

  private def notFrontend(descriptor: ServiceDescriptor): Boolean = !descriptor.publicPatterns.contains("/.*")

  private def introduceSnowMonkeyDefinedChaos(reqNumber: Long, config: SnowMonkeyConfig, desc: ServiceDescriptor)(f: SnowMonkeyContext => Future[Result])(implicit ec: ExecutionContext): Future[Result] = {
    isOutage(desc, config).flatMap {
      case true if config.includeFrontends =>
        applyChaosConfig(reqNumber, config.chaosConfig)(f)
      case true if !config.includeFrontends && notFrontend(desc) =>
        applyChaosConfig(reqNumber, config.chaosConfig)(f)
      case _ => f(SnowMonkeyContext(
        Source.empty[ByteString],
        Source.empty[ByteString]
      ))
    }
  }

  def introduceChaos(reqNumber: Long, config: GlobalConfig, desc: ServiceDescriptor)(f: SnowMonkeyContext => Future[Result])(implicit ec: ExecutionContext): Future[Result] = {
    if (desc.id == env.backOfficeServiceId) {
      f(SnowMonkeyContext(
        Source.empty[ByteString],
        Source.empty[ByteString]
      ))
    } else if (config.snowMonkeyConfig.enabled && betweenDates(config.snowMonkeyConfig)) {
      introduceSnowMonkeyDefinedChaos(reqNumber, config.snowMonkeyConfig, desc)(f)
    } else {
      if (desc.chaosConfig.enabled) {
        introduceServiceDefinedChaos(reqNumber, desc)(f)
      } else {
        f(SnowMonkeyContext(
          Source.empty[ByteString],
          Source.empty[ByteString]
        ))
      }
    }
  }
}


