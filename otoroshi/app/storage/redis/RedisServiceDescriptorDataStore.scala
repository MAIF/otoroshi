package storage.redis

import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.util.FastFuture
import env.Env
import models.{Key, ServiceDescriptor, ServiceDescriptorDataStore, ServiceDescriptorQuery}
import play.api.Logger
import play.api.libs.json.Format
import redis.RedisClientMasterSlaves

import utils.RegexPool

import scala.concurrent.{ExecutionContext, Future}

class RedisServiceDescriptorDataStore(redisCli: RedisClientMasterSlaves, maxQueueSize: Int, _env: Env)
    extends ServiceDescriptorDataStore
    with RedisStore[ServiceDescriptor] {

  lazy val logger = Logger("otoroshi-redis-service-datastore")

  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli

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
    val time        = System.currentTimeMillis()
    val slugDataIn  = s"$dataIn:$time"
    val slugDataOut = s"$dataOut:$time"
    // Call everything in parallel
    // incrementCalls
    val callsShiftGlobalTime = redisCli.lpush(serviceCallStatsKey("global"), time).flatMap { _ =>
      redisCli.ltrim(serviceCallStatsKey("global"), 0, maxQueueSize)
      redisCli.expire(serviceCallStatsKey("global"), 10)
    }
    val callsShiftServiceTime = redisCli.lpush(serviceCallStatsKey(id), time).flatMap { _ =>
      redisCli.ltrim(serviceCallStatsKey(id), 0, maxQueueSize)
      redisCli.expire(serviceCallStatsKey(id), 10)
    }
    val callsIncrementGlobalCalls  = redisCli.incr(serviceCallKey("global"))
    val callsIncrementServiceCalls = redisCli.incr(serviceCallKey(id))
    // incrementCallsDuration
    val callDurationShiftGlobalDuration = redisCli.lpush(serviceCallDurationStatsKey("global"), callDuration).flatMap {
      _ =>
        redisCli.ltrim(serviceCallDurationStatsKey("global"), 0, maxQueueSize)
    }
    val callDurationShiftServiceDuration = redisCli.lpush(serviceCallDurationStatsKey(id), callDuration).flatMap { _ =>
      redisCli.ltrim(serviceCallDurationStatsKey(id), 0, maxQueueSize)
    }
    // incrementCallsOverhead
    val callOverheadShiftGlobalDuration = redisCli.lpush(serviceCallOverheadStatsKey("global"), callOverhead).flatMap {
      _ =>
        redisCli.ltrim(serviceCallOverheadStatsKey("global"), 0, maxQueueSize)
    }
    val callOverheadShiftServiceDuration = redisCli.lpush(serviceCallOverheadStatsKey(id), callOverhead).flatMap { _ =>
      redisCli.ltrim(serviceCallOverheadStatsKey(id), 0, maxQueueSize)
    }
    // incrementDataIn
    val dataInIncrementGlobal  = redisCli.incrby(dataInGlobalKey(), dataIn).fast.map(_ => ())
    val dataInIncrementService = redisCli.incrby(dataInForServiceKey(id), dataIn).fast.map(_ => ())
    val dataInShiftService = redisCli.lpush(dataInForServiceStatsKey(id), slugDataIn).flatMap { _ =>
      redisCli.ltrim(dataInForServiceStatsKey(id), 0, maxQueueSize)
      redisCli.expire(dataInForServiceStatsKey(id), 10)
    }
    val dataInShiftGlobal = redisCli.lpush(dataInForServiceStatsKey("global"), slugDataIn).flatMap { _ =>
      redisCli.ltrim(dataInForServiceStatsKey("global"), 0, maxQueueSize)
      redisCli.expire(dataInForServiceStatsKey("global"), 10)
    }
    // incrementDataOut
    val dataOutIncrementGlobal  = redisCli.incrby(dataOutGlobalKey(), dataOut).fast.map(_ => ())
    val dataOutIncrementService = redisCli.incrby(dataOutForServiceKey(id), dataOut).fast.map(_ => ())
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
                    env.statsd.meter(s"global.calls", globalCalls.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"services.${id}.calls", serviceCalls.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"global.duration", callDuration.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"global.overhead", callOverhead.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"global.data-in", dataIn.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"global.data-out", dataOut.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"global.upstream-latency", upstreamLatency.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"services.${id}.duration", callDuration.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"services.${id}.overhead", callOverhead.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"services.${id}.data-in", dataIn.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"services.${id}.data-out", dataOut.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"services.${id}.upstream-latency", upstreamLatency.toDouble)(config.statsdConfig)
                  )
              )
            )
            .getOrElse(FastFuture.successful(()))
    } yield ()
  }

  override def updateIncrementableMetrics(id: String,
                                          calls: Long,
                                          dataIn: Long,
                                          dataOut: Long,
                                          config: models.GlobalConfig)(
      implicit ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
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
                    env.statsd.meter(s"global.calls", globalCalls.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"services.${id}.calls", serviceCalls.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"global.data-in", dataIn.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"global.data-out", dataOut.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"services.${id}.data-in", dataIn.toDouble)(config.statsdConfig),
                    env.statsd.meter(s"services.${id}.data-out", dataOut.toDouble)(config.statsdConfig),
                  )
              )
            )
            .getOrElse(FastFuture.successful(()))
    } yield ()
  }

  override def dataInPerSecFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(dataInForServiceStatsKey(id), 0, maxQueueSize).fast.map { values =>
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
    redisCli.lrange(dataOutForServiceStatsKey(id), 0, maxQueueSize).fast.map { values =>
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
    redisCli.get(serviceCallKey(id)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def callsPerSec(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(serviceCallStatsKey(id), 0, maxQueueSize).fast.map { values =>
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
    redisCli.lrange(serviceCallDurationStatsKey(id), 0, maxQueueSize).fast.map { values =>
      if (values.isEmpty) 0.0
      else
        values.map(_.utf8String.toDouble).foldLeft(0.0)(_ + _) / values.size.toDouble
    }

  override def callsOverhead(id: String)(implicit ec: ExecutionContext, env: Env): Future[Double] =
    redisCli.lrange(serviceCallOverheadStatsKey(id), 0, maxQueueSize).fast.map { values =>
      if (values.isEmpty) 0.0
      else
        values.map(_.utf8String.toDouble).foldLeft(0.0)(_ + _) / values.size.toDouble
    }

  override def globalDataIn()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataInGlobalKey()).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def globalDataOut()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataOutGlobalKey()).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def dataInFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataInForServiceKey(id)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))

  override def dataOutFor(id: String)(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.get(dataOutForServiceKey(id)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))

  @inline
  def matchAllHeaders(sr: ServiceDescriptor, query: ServiceDescriptorQuery): Boolean = {
    val headersSeq: Map[String, String] = query.matchingHeaders.filterNot(_._1.trim.isEmpty)
    val allHeadersMatched: Boolean =
      sr.matchingHeaders.filterNot(_._1.trim.isEmpty).forall {
        case (key, value) =>
          val regex = RegexPool.regex(value)
          headersSeq.get(key).exists(h => regex.matches(h))
      }
    allHeadersMatched
  }

  @inline
  def sortServices(services: Seq[ServiceDescriptor], query: ServiceDescriptorQuery): Seq[ServiceDescriptor] = {
    // val sers = services
    //   .sortWith {
    //     case (a, b) if a.matchingRoot.isDefined && b.matchingRoot.isDefined =>
    //       a.matchingRoot.get.size > b.matchingRoot.get.size
    //     case (a, b) if a.matchingRoot.isDefined && !b.matchingRoot.isDefined => true
    //     case (a, b) if b.matchingRoot.isDefined && !a.matchingRoot.isDefined => true
    //     case _                                                               => false
    //   }
    //   .filter { sr =>
    //     val allHeadersMatched = matchAllHeaders(sr, query)
    //     val rootMatched = sr.matchingRoot match {
    //       case Some(matchingRoot) => query.root.startsWith(matchingRoot) //matchingRoot == query.root
    //       case None               => true
    //     }
    //     allHeadersMatched && rootMatched
    //   }
    val allSers = services
      .filter { sr =>
        val allHeadersMatched = matchAllHeaders(sr, query)
        val rootMatched = sr.matchingRoot match {
          case Some(matchingRoot) => query.root.startsWith(matchingRoot) //matchingRoot == query.root
          case None               => true
        }
        allHeadersMatched && rootMatched
      }
    val sersWithoutMatchingRoot = allSers.filter(_.matchingRoot.isEmpty)
    val sersWithMatchingRoot = allSers.filter(_.matchingRoot.isDefined).sortWith {
      case (a, b) => a.matchingRoot.get.size > b.matchingRoot.get.size
    }
    sersWithMatchingRoot ++ sersWithoutMatchingRoot
    // val sers = (sersWithMatchingRoot ++ sersWithoutMatchingRoot)
    // logger.debug(s"for query $query, services are :\n\n${sers.map(a => "  * " + a.name).mkString("\n")}\n\n")
    // sers
  }

  // TODO : prefill ServiceDescriptorQuery lookup set when crud service descriptors
  override def find(query: ServiceDescriptorQuery)(implicit ec: ExecutionContext,
                                                   env: Env): Future[Option[ServiceDescriptor]] = {
    val start = System.currentTimeMillis()
    query.exists().flatMap {
      case true => {
        logger.debug(s"Service descriptors for $query")
        query
          .getServices()
          .fast
          .map(services => sortServices(services, query))
      }
      case false => {
        logger.debug("Full scan of services, should not pass here anymore ...")
        findAll().fast.map { descriptors =>
          val validDescriptors = descriptors.filter { sr =>
            if (env.redirectToDev) {
              utils.RegexPool(sr.toDevHost).matches(query.toDevHost)
            } else {
              utils.RegexPool(sr.toHost).matches(query.toHost)
            }
          }
          query.addServices(validDescriptors)
          sortServices(validDescriptors, query)
        }
      }
    } map { filteredDescriptors =>
      if (env.redirectToDev) {
        filteredDescriptors.sortWith { (a, b) =>
          // TODO : do not use hardcoded stuff
          (a.env, b.env) match {
            case ("dev", _)                     => true
            case ("preprod", "dev")             => false
            case ("preprod", "experiments")     => false
            case ("preprod", "prod")            => true
            case ("preprod", "preprod")         => true
            case ("experiments", "prod")        => true
            case ("experiments", "preprod")     => false
            case ("experiments", "dev")         => false
            case ("experiments", "experiments") => false
            case _                              => false
          }
        }.headOption
      } else {
        filteredDescriptors.headOption
      }
    } andThen {
      case _ => logger.debug(s"Found microservice in ${System.currentTimeMillis() - start} ms.")
    }
  }

  // TODO : rewrite with less naïve implem
  override def findByEnv(env: String)(implicit ec: ExecutionContext, _env: Env): Future[Seq[ServiceDescriptor]] =
    findAll().fast.map(_.filter(_.env == env))

  // TODO : rewrite with less naïve implem
  override def findByGroup(id: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[ServiceDescriptor]] =
    findAll().fast.map(_.filter(_.groupId == id))

  override def count()(implicit ec: ExecutionContext, env: Env): Future[Long] =
    redisCli.keys(key("*").key).fast.map(_.size.toLong)
}
