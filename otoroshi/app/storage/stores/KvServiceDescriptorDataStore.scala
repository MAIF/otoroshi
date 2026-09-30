package otoroshi.storage.stores

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicReference, LongAdder}
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.http.scaladsl.util.FastFuture.*
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import otoroshi.env.Env
import otoroshi.models.*
import play.api.Logger
import play.api.libs.json.Format
import play.api.mvc.RequestHeader
import otoroshi.storage.{RedisLike, RedisLikeStore}
import otoroshi.utils.{RegexPool, SchedulerHelper}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Success, Try}

object KvServiceDescriptorDataStore {

  // a rate over the samples of a list, newest first: each sample is an amount and the time it was written
  // ("amount:time"), or the time alone of a single call, as the calls were written before they were gathered per
  // second. The amount of the oldest sample was counted before the span the samples cover, so it is left out; a single
  // sample is taken as the amount of a second
  def ratePerSecond(samples: Seq[String]): Double = {
    if (samples.isEmpty) 0.0
    else {
      val parsed = samples.map { sample =>
        val separator = sample.indexOf(':')
        if (separator < 0) (1L, sample.toDouble.toLong)
        else (sample.substring(0, separator).toLong, sample.substring(separator + 1).toLong)
      }
      val oldest = parsed.minBy(_._2)
      val span   = (parsed.map(_._2).max - oldest._2) / 1000.0
      val total  = parsed.foldLeft(0L)(_ + _._1)
      if (span <= 0.0) total.toDouble else (total - oldest._1) / span
    }
  }
}

class KvServiceDescriptorDataStore(redisCli: RedisLike, maxQueueSize: Int, _env: Env)
    extends ServiceDescriptorDataStore
    with RedisLikeStore[ServiceDescriptor] {

  lazy val logger = Logger("otoroshi-service-datatstore")

  private val updateRef = new AtomicReference[Cancellable]()
  private val flushRef  = new AtomicReference[Cancellable]()

  override def redisLike(using env: Env): RedisLike = redisCli

  override def fmt: Format[ServiceDescriptor] = ServiceDescriptor._fmt

  override def key(id: String): String = s"${_env.storageRoot}:desc:${id}"

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

  override def set(value: ServiceDescriptor, pxMilliseconds: Option[Duration])(using
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {
    if (env.staticExposedDomainEnabled && value.id != env.backOfficeServiceId) {
      val (_subdomain, _envir, _domain) = env.staticExposedDomain.map { v =>
        ServiceLocation.fullQuery(
          v,
          env.datastores.globalConfigDataStore.latest()(using env.otoroshiExecutionContext, env)
        ) match {
          case None           => (value.subdomain, value.env, value.domain)
          case Some(location) => (location.subdomain, location.env, location.domain)
        }
      } getOrElse (value.subdomain, value.env, value.domain)
      super.set(
        value.copy(
          domain = _domain,
          env = _envir,
          subdomain = _subdomain,
          hosts = Seq.empty
        ),
        pxMilliseconds
      )
    } else {
      super.set(value, pxMilliseconds)
    }
  }

  def startCleanup(env: Env): Unit = {
    updateRef.set(
      env.otoroshiScheduler.scheduleAtFixedRate(10.seconds, 5.minutes)(
        SchedulerHelper.runnable(
          cleanupFastLookups()(using env.otoroshiExecutionContext, env.otoroshiMaterializer, env)
        )
      )(using env.otoroshiExecutionContext)
    )
    flushRef.set(
      env.otoroshiScheduler.scheduleAtFixedRate(1.second, 1.second)(
        SchedulerHelper.runnable(Try(flushMetrics()(using env.analyticsExecutionContext, env)))
      )(using env.analyticsExecutionContext)
    )
  }

  def stopCleanup(): Unit = {
    Option(updateRef.get()).foreach(_.cancel())
    Option(flushRef.get()).foreach(_.cancel())
  }

  override def cleanupFastLookups()(using ec: ExecutionContext, mat: Materializer, env: Env): Future[Long] = {
    redisCli
      .keys(s"${_env.storageRoot}:desclookup:*")
      .flatMap { keys =>
        Source(keys.toList)
          .mapAsync(1)(key => redisCli.pttl(key).map(ttl => (key, ttl)))
          .filter(_._2 == -1)
          .grouped(100)
          .mapAsync(1)(seq => redisCli.del(seq.map(_._1)*))
          .runFold(0L)(_ + _)
      }
      .andThen {
        case Success(count) if count > 0L =>
          if (logger.isDebugEnabled) logger.debug(s"Cleaned up $count fast lookup keys without ttl")
        case _                            =>
      }
  }

  override def getFastLookups(
      query: ServiceDescriptorQuery
  )(using ec: ExecutionContext, env: Env): Future[Seq[String]] =
    redisCli.smembers(query.asKey).map(_.map(_.utf8String))

  override def fastLookupExists(
      query: ServiceDescriptorQuery
  )(using ec: ExecutionContext, env: Env): Future[Boolean] = {
    for {
      size <- redisCli.scard(query.asKey)
    } yield {
      size > 0L
    }
  }

  override def addFastLookups(
      query: ServiceDescriptorQuery,
      services: Seq[ServiceDescriptor]
  )(using ec: ExecutionContext, env: Env): Future[Boolean] =
    for {
      r <- redisCli.sadd(query.asKey, services.map(_.id)*)
      _ <- redisCli.pexpire(query.asKey, 60000)
    } yield r > 0L

  override def removeFastLookups(
      query: ServiceDescriptorQuery,
      services: Seq[ServiceDescriptor]
  )(using ec: ExecutionContext, env: Env): Future[Boolean] =
    for {
      r <- redisCli.srem(query.asKey, services.map(_.id)*)
    } yield r > 0L

  // the embedded metrics are gathered in memory, per route and for all of them ("global"), and written once per second
  // by flushMetrics: a request used to cost about thirty datastore commands, through a single actor that did not keep
  // up with the traffic. Each list gets one sample per second and per instance: an amount and its time for the calls
  // and the data, which ratePerSecond reads, and the average of the second for the durations and the overheads
  private final class PendingMetrics {
    val calls    = new LongAdder()
    // the calls with a duration, which the errors counted by updateMetricsOnError do not have
    val timed    = new LongAdder()
    val duration = new LongAdder()
    val overhead = new LongAdder()
    val upstream = new LongAdder()
    val dataIn   = new LongAdder()
    val dataOut  = new LongAdder()
  }

  private val pendingMetrics = new ConcurrentHashMap[String, PendingMetrics]()

  private def pending(id: String): PendingMetrics = {
    val existing = pendingMetrics.get(id)
    if (existing ne null) existing else pendingMetrics.computeIfAbsent(id, _ => new PendingMetrics())
  }

  private def record(
      id: String,
      timed: Boolean,
      duration: Long,
      overhead: Long,
      upstream: Long,
      dataIn: Long,
      dataOut: Long
  ): Unit = {
    val metrics = pending(id)
    metrics.calls.increment()
    if (timed) {
      metrics.timed.increment()
      metrics.duration.add(duration)
      metrics.overhead.add(overhead)
      metrics.upstream.add(upstream)
    }
    metrics.dataIn.add(dataIn)
    metrics.dataOut.add(dataOut)
  }

  override def updateMetricsOnError(
      config: otoroshi.models.GlobalConfig
  )(using ec: ExecutionContext, env: Env): Future[Unit] = {
    if (config.enableEmbeddedMetrics) {
      record("global", timed = false, 0L, 0L, 0L, 0L, 0L)
    }
    FastFuture.successful(())
  }

  override def updateMetrics(
      id: String,
      callDuration: Long,
      callOverhead: Long,
      dataIn: Long,
      dataOut: Long,
      upstreamLatency: Long,
      config: otoroshi.models.GlobalConfig
  )(using
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    if (config.enableEmbeddedMetrics) {
      record(id, timed = true, callDuration, callOverhead, upstreamLatency, dataIn, dataOut)
      record("global", timed = true, callDuration, callOverhead, upstreamLatency, dataIn, dataOut)
      env.clusterAgent.incrementService(
        id,
        1L,
        dataIn,
        dataOut,
        callOverhead,
        callDuration,
        callDuration - upstreamLatency,
        0L,
        0L
      )
    }
    FastFuture.successful(())
  }

  private def flushMetrics()(using ec: ExecutionContext, env: Env): Future[Unit] = {
    val statsd = env.datastores.globalConfigDataStore.latestSafe.exists(_.statsdConfig.isDefined)
    val now    = System.currentTimeMillis()
    Future
      .sequence(pendingMetrics.asScala.toSeq.map { case (id, metrics) => flushMetricsOf(id, metrics, now, statsd) })
      .map(_ => ())
  }

  private def flushMetricsOf(id: String, metrics: PendingMetrics, now: Long, statsd: Boolean)(using
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    val calls    = metrics.calls.sumThenReset()
    val timed    = metrics.timed.sumThenReset()
    val duration = metrics.duration.sumThenReset()
    val overhead = metrics.overhead.sumThenReset()
    val upstream = metrics.upstream.sumThenReset()
    val dataIn   = metrics.dataIn.sumThenReset()
    val dataOut  = metrics.dataOut.sumThenReset()
    if (calls == 0L && dataIn == 0L && dataOut == 0L) FastFuture.successful(())
    else {
      def sample(key: String, value: String, ttl: Boolean): Future[Unit] =
        redisCli.lpush(key, value).flatMap { _ =>
          val trimmed = redisCli.ltrim(key, 0, maxQueueSize)
          val expired = if (ttl) redisCli.expire(key, 10) else FastFuture.successful(true)
          trimmed.flatMap(_ => expired).map(_ => ())
        }
      val totalCalls = redisCli.incrby(serviceCallKey(id), calls)
      val writes     = Seq(
        redisCli.incrby(dataInForServiceKey(id), dataIn).map(_ => ()),
        redisCli.incrby(dataOutForServiceKey(id), dataOut).map(_ => ()),
        sample(serviceCallStatsKey(id), s"$calls:$now", ttl = true),
        sample(dataInForServiceStatsKey(id), s"$dataIn:$now", ttl = true),
        sample(dataOutForServiceStatsKey(id), s"$dataOut:$now", ttl = true)
      ) ++ (if (timed > 0L)
              Seq(
                sample(serviceCallDurationStatsKey(id), (duration / timed).toString, ttl = false),
                sample(serviceCallOverheadStatsKey(id), (overhead / timed).toString, ttl = false)
              )
            else Seq.empty)
      for {
        total <- totalCalls
        _     <- Future.sequence(writes)
      } yield {
        if (statsd) {
          val prefix = if (id == "global") "global" else s"services.$id"
          env.metrics.markLong(s"$prefix.calls", total)
          env.metrics.markLong(s"$prefix.data-in", dataIn)
          env.metrics.markLong(s"$prefix.data-out", dataOut)
          if (timed > 0L) {
            env.metrics.markLong(s"$prefix.duration", duration / timed)
            env.metrics.markLong(s"$prefix.overhead", overhead / timed)
            env.metrics.markLong(s"$prefix.upstream-latency", upstream / timed)
          }
        }
      }
    }
  }

  override def updateIncrementableMetrics(
      id: String,
      calls: Long,
      dataIn: Long,
      dataOut: Long,
      config: otoroshi.models.GlobalConfig
  )(using
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    if (config.enableEmbeddedMetrics) {
      val time                       = System.currentTimeMillis()
      // Call everything in parallel
      // incrementCalls
      val callsIncrementGlobalCalls  = redisCli.incrby(serviceCallKey("global"), calls)
      val callsIncrementServiceCalls = redisCli.incrby(serviceCallKey(id), calls)
      // incrementCallsDuration
      // incrementDataIn
      val dataInIncrementGlobal      = redisCli.incrby(dataInGlobalKey(), dataIn).map(_ => ())
      val dataInIncrementService     = redisCli.incrby(dataInForServiceKey(id), dataIn).map(_ => ())
      // incrementDataOut
      val dataOutIncrementGlobal     = redisCli.incrby(dataOutGlobalKey(), dataOut).map(_ => ())
      val dataOutIncrementService    = redisCli.incrby(dataOutForServiceKey(id), dataOut).map(_ => ())
      // now wait for all
      for {
        // incrementCalls
        globalCalls  <- callsIncrementGlobalCalls
        serviceCalls <- callsIncrementServiceCalls
        // incrementDataIn
        _            <- dataInIncrementGlobal
        _            <- dataInIncrementService
        // incrementDataOut
        _            <- dataOutIncrementGlobal
        _            <- dataOutIncrementService
        _            <- config.statsdConfig
                          .map(_ =>
                            FastFuture.successful(
                              (
                                env.metrics.markLong(s"global.calls", globalCalls),
                                env.metrics.markLong(s"services.${id}.calls", serviceCalls),
                                env.metrics.markLong(s"global.data-in", dataIn),
                                env.metrics.markLong(s"global.data-out", dataOut),
                                env.metrics.markLong(s"services.${id}.data-in", dataIn),
                                env.metrics.markLong(s"services.${id}.data-out", dataOut)
                              )
                            )
                          )
                          .getOrElse(FastFuture.successful(()))
      } yield ()
    } else {
      FastFuture.successful(())
    }
  }

  override def dataInPerSecFor(id: String)(using ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(dataInForServiceStatsKey(id), 0, maxQueueSize).map(values => KvServiceDescriptorDataStore.ratePerSecond(values.map(_.utf8String)))

  override def dataOutPerSecFor(id: String)(using ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(dataOutForServiceStatsKey(id), 0, maxQueueSize).map(values => KvServiceDescriptorDataStore.ratePerSecond(values.map(_.utf8String)))

  override def globalCalls()(using ec: ExecutionContext, env: Env): Future[Long] = calls("global")

  override def globalCallsPerSec()(using ec: ExecutionContext, env: Env): Future[Double] = callsPerSec("global")

  override def globalCallsDuration()(using ec: ExecutionContext, env: Env): Future[Double] = callsDuration("global")

  override def globalCallsOverhead()(using ec: ExecutionContext, env: Env): Future[Double] = callsOverhead("global")

  override def calls(id: String)(using ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(serviceCallKey(id)).map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def callsPerSec(id: String)(using ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(serviceCallStatsKey(id), 0, maxQueueSize).map(values => KvServiceDescriptorDataStore.ratePerSecond(values.map(_.utf8String)))

  override def callsDuration(id: String)(using ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(serviceCallDurationStatsKey(id), 0, maxQueueSize).map { values =>
      if (values.isEmpty) 0.0
      else
        values.map(_.utf8String.toDouble).foldLeft(0.0)(_ + _) / values.size.toDouble
    }

  override def callsOverhead(id: String)(using ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(serviceCallOverheadStatsKey(id), 0, maxQueueSize).map { values =>
      if (values.isEmpty) 0.0
      else
        values.map(_.utf8String.toDouble).foldLeft(0.0)(_ + _) / values.size.toDouble
    }

  override def globalDataIn()(using ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataInGlobalKey()).map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def globalDataOut()(using ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataOutGlobalKey()).map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def dataInFor(id: String)(using ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataInForServiceKey(id)).map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def dataOutFor(id: String)(using ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataOutForServiceKey(id)).map(_.map(_.utf8String.toLong).getOrElse(0L))

  // TODO : rewrite with less naïve implem
  override def findByEnv(env: String)(using ec: ExecutionContext, _env: Env): Future[Seq[ServiceDescriptor]] = {
    if (redisCli.optimized) {
      redisCli.asOptimized.serviceDescriptors_findByEnv(env)
    } else {
      findAll().map(_.filter(_.env == env))
    }
  }

  // TODO : rewrite with less naïve implem
  override def findByGroup(id: String)(using ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] = {
    if (redisCli.optimized) {
      redisCli.asOptimized.serviceDescriptors_findByGroup(id)
    } else {
      findAll().map(_.filter(_.groups.contains(id)))
    }
  }

  override def count()(using ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.keys(key("*")).map(_.size.toLong)
}
