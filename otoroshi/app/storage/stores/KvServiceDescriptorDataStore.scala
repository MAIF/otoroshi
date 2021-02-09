package otoroshi.storage.stores

import java.util.concurrent.atomic.AtomicReference

import akka.actor.Cancellable
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import env.Env
import models._
import play.api.Logger
import play.api.libs.json.Format
import play.api.mvc.RequestHeader
import otoroshi.storage.{RedisLike, RedisLikeStore}
import utils.RegexPool

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Success

class KvServiceDescriptorDataStore(redisCli: RedisLike, maxQueueSize: Int, _env: Env)
    extends ServiceDescriptorDataStore
    with RedisLikeStore[ServiceDescriptor] {

  lazy val logger = Logger("otoroshi-service-datatstore")

  private val updateRef = new AtomicReference[Cancellable]()

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def fmt: Format[ServiceDescriptor] = ServiceDescriptor._fmt

  override def key(id: String): Key = Key.Empty / _env.storageRoot / "desc" / id

  override def extractId(value: ServiceDescriptor): String = value.id

  private def serviceCallKey(name: String)      = s"${_env.storageRoot}:scall:$name"
  private def serviceCallStatsKey(name: String) = s"${_env.storageRoot}:scall:stats:$name"

  private def serviceCallDurationStatsKey(name: String) = s"${_env.storageRoot}:scalldur:stats:$name"
  private def serviceCallOverheadStatsKey(name: String) = s"${_env.storageRoot}:scallover:stats:$name"

  private def dataInGlobalKey()  = s"${_env.storageRoot}:data:global:in"
  private def dataOutGlobalKey() = s"${_env.storageRoot}:data:global:out"

  private def dataInForServiceKey(name: String)       = s"${_env.storageRoot}:data:$name:in"
  private def dataOutForServiceKey(name: String)      = s"${_env.storageRoot}:data:$name:out"
  private def dataInForServiceStatsKey(name: String)  = s"${_env.storageRoot}:data:$name:stats:in"
  private def dataOutForServiceStatsKey(name: String) = s"${_env.storageRoot}:data:$name:stats:out"

  override def set(value: ServiceDescriptor, pxMilliseconds: Option[Duration])(implicit ec: ExecutionContext,
                                                                               env: Env): Future[Boolean] = {
    if (env.staticExposedDomainEnabled && value.id != env.backOfficeServiceId) {
      val (_subdomain, _envir, _domain) = env.staticExposedDomain.map { v =>
        ServiceLocation.fullQuery(v, env.datastores.globalConfigDataStore.latest()(env.otoroshiExecutionContext, env)) match {
          case None           => (value.subdomain, value.env, value.domain)
          case Some(location) => (location.subdomain, location.env, location.domain)
        }
      } getOrElse (value.subdomain, value.env, value.domain)
      super.set(value.copy(
                  domain = _domain,
                  env = _envir,
                  subdomain = _subdomain,
                  hosts = Seq.empty
                ),
                pxMilliseconds)
    } else {
      super.set(value, pxMilliseconds)
    }
  }

  def startCleanup(env: Env): Unit = {
    updateRef.set(
      env.otoroshiScheduler.scheduleAtFixedRate(10.seconds, 5.minutes)(utils.SchedulerHelper.runnable(
        cleanupFastLookups()(env.otoroshiExecutionContext, env.otoroshiMaterializer, env)
      ))(env.otoroshiExecutionContext)
    )
  }

  def stopCleanup(): Unit = {
    Option(updateRef.get()).foreach(_.cancel())
  }

  override def cleanupFastLookups()(implicit ec: ExecutionContext, mat: Materializer, env: Env): Future[Long] = {
    redisCli
      .keys(s"${_env.storageRoot}:desclookup:*")
      .flatMap { keys =>
        Source(keys.toList)
          .mapAsync(1)(key => redisCli.pttl(key).map(ttl => (key, ttl)))
          .filter(_._2 == -1)
          .grouped(100)
          .mapAsync(1)(seq => redisCli.del(seq.map(_._1): _*))
          .runFold(0L)(_ + _)
      }
      .andThen {
        case Success(count) if count > 0L => logger.info(s"Cleaned up $count fast lookup keys without ttl")
        case _                            =>
      }
  }

  override def getFastLookups(query: ServiceDescriptorQuery)(implicit ec: ExecutionContext,
                                                             env: Env): Future[Seq[String]] =
    redisCli.smembers(query.asKey).map(_.map(_.utf8String))

  override def fastLookupExists(query: ServiceDescriptorQuery)(implicit ec: ExecutionContext,
                                                               env: Env): Future[Boolean] = {
    for {
      size <- redisCli.scard(query.asKey)
    } yield {
      size > 0L
    }
  }

  override def addFastLookups(
      query: ServiceDescriptorQuery,
      services: Seq[ServiceDescriptor]
  )(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    for {
      r <- redisCli.sadd(query.asKey, services.map(_.id): _*)
      _ <- redisCli.pexpire(query.asKey, 60000)
    } yield r > 0L

  override def removeFastLookups(
      query: ServiceDescriptorQuery,
      services: Seq[ServiceDescriptor]
  )(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    for {
      r <- redisCli.srem(query.asKey, services.map(_.id): _*)
    } yield r > 0L

  override def updateMetricsOnError(config: models.GlobalConfig)(implicit ec: ExecutionContext,
                                                                 env: Env): Future[Unit] = {
    if (config.enableEmbeddedMetrics) {
      val time = System.currentTimeMillis()
      val callsShiftGlobalTime = redisCli.lpushLong(serviceCallStatsKey("global"), time).flatMap { _ =>
        redisCli.ltrim(serviceCallStatsKey("global"), 0, maxQueueSize)
        redisCli.expire(serviceCallStatsKey("global"), 10)
      }
      val callsIncrementGlobalCalls = redisCli.incr(serviceCallKey("global"))
      for {
        _           <- callsShiftGlobalTime
        globalCalls <- callsIncrementGlobalCalls
      } yield {
        env.metrics.markLong(s"global.calls", globalCalls)
        ()
      }
    } else {
      FastFuture.successful(())
    }
  }

  override def updateMetrics(id: String,
                             callDuration: Long,
                             callOverhead: Long,
                             dataIn: Long,
                             dataOut: Long,
                             upstreamLatency: Long,
                             config: models.GlobalConfig)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    if (config.enableEmbeddedMetrics) {
      val time        = System.currentTimeMillis()
      val slugDataIn  = s"$dataIn:$time"
      val slugDataOut = s"$dataOut:$time"
      // Call everything in parallel
      // incrementCalls
      val callsShiftGlobalTime = redisCli.lpushLong(serviceCallStatsKey("global"), time).flatMap { _ =>
        redisCli.ltrim(serviceCallStatsKey("global"), 0, maxQueueSize)
        redisCli.expire(serviceCallStatsKey("global"), 10)
      }
      val callsShiftServiceTime = redisCli.lpushLong(serviceCallStatsKey(id), time).flatMap { _ =>
        redisCli.ltrim(serviceCallStatsKey(id), 0, maxQueueSize)
        redisCli.expire(serviceCallStatsKey(id), 10)
      }
      val callsIncrementGlobalCalls  = redisCli.incr(serviceCallKey("global"))
      val callsIncrementServiceCalls = redisCli.incr(serviceCallKey(id))
      // incrementCallsDuration
      val callDurationShiftGlobalDuration =
        redisCli.lpushLong(serviceCallDurationStatsKey("global"), callDuration).flatMap { _ =>
          redisCli.ltrim(serviceCallDurationStatsKey("global"), 0, maxQueueSize)
        }
      val callDurationShiftServiceDuration = redisCli.lpushLong(serviceCallDurationStatsKey(id), callDuration).flatMap {
        _ =>
          redisCli.ltrim(serviceCallDurationStatsKey(id), 0, maxQueueSize)
      }
      // incrementCallsOverhead
      val callOverheadShiftGlobalDuration =
        redisCli.lpushLong(serviceCallOverheadStatsKey("global"), callOverhead).flatMap { _ =>
          redisCli.ltrim(serviceCallOverheadStatsKey("global"), 0, maxQueueSize)
        }
      val callOverheadShiftServiceDuration = redisCli.lpushLong(serviceCallOverheadStatsKey(id), callOverhead).flatMap {
        _ =>
          redisCli.ltrim(serviceCallOverheadStatsKey(id), 0, maxQueueSize)
      }
      // incrementDataIn
      val dataInIncrementGlobal  = redisCli.incrby(dataInGlobalKey(), dataIn).map(_ => ())
      val dataInIncrementService = redisCli.incrby(dataInForServiceKey(id), dataIn).map(_ => ())
      val dataInShiftService = redisCli.lpush(dataInForServiceStatsKey(id), slugDataIn).flatMap { _ =>
        redisCli.ltrim(dataInForServiceStatsKey(id), 0, maxQueueSize)
        redisCli.expire(dataInForServiceStatsKey(id), 10)
      }
      val dataInShiftGlobal = redisCli.lpush(dataInForServiceStatsKey("global"), slugDataIn).flatMap { _ =>
        redisCli.ltrim(dataInForServiceStatsKey("global"), 0, maxQueueSize)
        redisCli.expire(dataInForServiceStatsKey("global"), 10)
      }
      // incrementDataOut
      val dataOutIncrementGlobal  = redisCli.incrby(dataOutGlobalKey(), dataOut).map(_ => ())
      val dataOutIncrementService = redisCli.incrby(dataOutForServiceKey(id), dataOut).map(_ => ())
      val dataOutShiftService = redisCli.lpush(dataOutForServiceStatsKey(id), slugDataOut).flatMap { _ =>
        redisCli.ltrim(dataOutForServiceStatsKey(id), 0, maxQueueSize)
        redisCli.expire(dataOutForServiceStatsKey(id), 10)
      }
      val dataOutShiftGlobal = redisCli.lpush(dataOutForServiceStatsKey("global"), slugDataOut).flatMap { _ =>
        redisCli.ltrim(dataOutForServiceStatsKey("global"), 0, maxQueueSize)
        redisCli.expire(dataOutForServiceStatsKey("global"), 10)
      }
      env.clusterAgent.incrementService(id, dataIn, dataOut)
      // now wait for all
      for {
        // incrementCalls
        _            <- callsShiftGlobalTime
        _            <- callsShiftServiceTime
        globalCalls  <- callsIncrementGlobalCalls
        serviceCalls <- callsIncrementServiceCalls
        // incrementCallsDuration
        _ <- callDurationShiftGlobalDuration
        _ <- callDurationShiftServiceDuration
        // incrementCallsOverhead
        _ <- callOverheadShiftGlobalDuration
        _ <- callOverheadShiftServiceDuration
        // incrementDataIn
        _ <- dataInIncrementGlobal
        _ <- dataInIncrementService
        _ <- dataInShiftService
        _ <- dataInShiftGlobal
        // incrementDataOut
        _ <- dataOutIncrementGlobal
        _ <- dataOutIncrementService
        _ <- dataOutShiftService
        _ <- dataOutShiftGlobal
        _ <- config.statsdConfig
              .map(
                _ =>
                  FastFuture.successful(
                    (
                      env.metrics.markLong(s"global.calls", globalCalls),
                      env.metrics.markLong(s"services.${id}.calls", serviceCalls),
                      env.metrics.markLong(s"global.duration", callDuration),
                      env.metrics.markLong(s"global.overhead", callOverhead),
                      env.metrics.markLong(s"global.data-in", dataIn),
                      env.metrics.markLong(s"global.data-out", dataOut),
                      env.metrics.markLong(s"global.upstream-latency", upstreamLatency),
                      env.metrics.markLong(s"services.${id}.duration", callDuration),
                      env.metrics.markLong(s"services.${id}.overhead", callOverhead),
                      env.metrics.markLong(s"services.${id}.data-in", dataIn),
                      env.metrics.markLong(s"services.${id}.data-out", dataOut),
                      env.metrics.markLong(s"services.${id}.upstream-latency", upstreamLatency)
                    )
                )
              )
              .getOrElse(FastFuture.successful(()))
      } yield ()
    } else {
      FastFuture.successful(())
    }
  }

  override def updateIncrementableMetrics(id: String,
                                          calls: Long,
                                          dataIn: Long,
                                          dataOut: Long,
                                          config: models.GlobalConfig)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    if (config.enableEmbeddedMetrics) {
      val time = System.currentTimeMillis()
      // Call everything in parallel
      // incrementCalls
      val callsIncrementGlobalCalls  = redisCli.incrby(serviceCallKey("global"), calls)
      val callsIncrementServiceCalls = redisCli.incrby(serviceCallKey(id), calls)
      // incrementCallsDuration
      // incrementDataIn
      val dataInIncrementGlobal  = redisCli.incrby(dataInGlobalKey(), dataIn).map(_ => ())
      val dataInIncrementService = redisCli.incrby(dataInForServiceKey(id), dataIn).map(_ => ())
      // incrementDataOut
      val dataOutIncrementGlobal  = redisCli.incrby(dataOutGlobalKey(), dataOut).map(_ => ())
      val dataOutIncrementService = redisCli.incrby(dataOutForServiceKey(id), dataOut).map(_ => ())
      // now wait for all
      for {
        // incrementCalls
        globalCalls  <- callsIncrementGlobalCalls
        serviceCalls <- callsIncrementServiceCalls
        // incrementDataIn
        _ <- dataInIncrementGlobal
        _ <- dataInIncrementService
        // incrementDataOut
        _ <- dataOutIncrementGlobal
        _ <- dataOutIncrementService
        _ <- config.statsdConfig
              .map(
                _ =>
                  FastFuture.successful(
                    (
                      env.metrics.markLong(s"global.calls", globalCalls),
                      env.metrics.markLong(s"services.${id}.calls", serviceCalls),
                      env.metrics.markLong(s"global.data-in", dataIn),
                      env.metrics.markLong(s"global.data-out", dataOut),
                      env.metrics.markLong(s"services.${id}.data-in", dataIn),
                      env.metrics.markLong(s"services.${id}.data-out", dataOut),
                    )
                )
              )
              .getOrElse(FastFuture.successful(()))
      } yield ()
    } else {
      FastFuture.successful(())
    }
  }

  override def dataInPerSecFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(dataInForServiceStatsKey(id), 0, maxQueueSize).map { values =>
      if (values.isEmpty) 0.0
      else {
        val items = values.map { v =>
          val parts = v.utf8String.split(":")
          (parts(0).toLong, parts(1).toLong)
        }
        val total   = items.map(_._1).foldLeft(0L)(_ + _).toDouble
        val minTime = if (items.isEmpty) 0L else items.map(_._2).min[Long]
        val maxTime = if (items.isEmpty) 0L else items.map(_._2).max[Long]
        val seconds = (maxTime - minTime) / 1000.0
        total / seconds
      }
    }

  override def dataOutPerSecFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(dataOutForServiceStatsKey(id), 0, maxQueueSize).map { values =>
      if (values.isEmpty) 0.0
      else {
        val items = values.map { v =>
          val parts = v.utf8String.split(":")
          (parts(0).toLong, parts(1).toLong)
        }
        val total   = items.map(_._1).foldLeft(0L)(_ + _).toDouble
        val minTime = if (items.isEmpty) 0L else items.map(_._2).min[Long]
        val maxTime = if (items.isEmpty) 0L else items.map(_._2).max[Long]
        val seconds = (maxTime - minTime) / 1000.0
        total / seconds
      }
    }

  override def globalCalls()(implicit ec: ExecutionContext, env: Env): Future[Long] = calls("global")

  override def globalCallsPerSec()(implicit ec: ExecutionContext, env: Env): Future[Double] = callsPerSec("global")

  override def globalCallsDuration()(implicit ec: ExecutionContext, env: Env): Future[Double] = callsDuration("global")

  override def globalCallsOverhead()(implicit ec: ExecutionContext, env: Env): Future[Double] = callsOverhead("global")

  override def calls(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(serviceCallKey(id)).map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def callsPerSec(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(serviceCallStatsKey(id), 0, maxQueueSize).map { values =>
      if (values.isEmpty) 0.0
      else {
        val times   = values.map(_.utf8String.toDouble)
        val minTime = if (times.isEmpty) 0.0 else times.min[Double]
        val maxTime = if (times.isEmpty) 0.0 else times.max[Double]
        val seconds = (maxTime - minTime) / 1000.0
        times.size.toDouble / seconds
      }
    }

  override def callsDuration(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(serviceCallDurationStatsKey(id), 0, maxQueueSize).map { values =>
      if (values.isEmpty) 0.0
      else
        values.map(_.utf8String.toDouble).foldLeft(0.0)(_ + _) / values.size.toDouble
    }

  override def callsOverhead(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(serviceCallOverheadStatsKey(id), 0, maxQueueSize).map { values =>
      if (values.isEmpty) 0.0
      else
        values.map(_.utf8String.toDouble).foldLeft(0.0)(_ + _) / values.size.toDouble
    }

  override def globalDataIn()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataInGlobalKey()).map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def globalDataOut()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataOutGlobalKey()).map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def dataInFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataInForServiceKey(id)).map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def dataOutFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataOutForServiceKey(id)).map(_.map(_.utf8String.toLong).getOrElse(0L))

  // TODO : rewrite with less naïve implem
  override def findByEnv(env: String)(implicit ec: ExecutionContext, _env: Env): Future[Seq[ServiceDescriptor]] = {
    if (redisCli.optimized) {
      redisCli.asOptimized.serviceDescriptors_findByEnv(env)
    } else {
      findAll().map(_.filter(_.env == env))
    }
  }

  // TODO : rewrite with less naïve implem
  override def findByGroup(id: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] = {
    if (redisCli.optimized) {
      redisCli.asOptimized.serviceDescriptors_findByGroup(id)
    } else {
      findAll().map(_.filter(_.groups.contains(id)))
    }
  }

  override def count()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.keys(key("*").key).map(_.size.toLong)
}
