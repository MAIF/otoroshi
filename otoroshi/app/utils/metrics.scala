package utils

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.{Timer => _, _}

import akka.actor.Cancellable
import akka.http.scaladsl.util.FastFuture
import cluster.{ClusterMode, StatsView}
import com.codahale.metrics._
import com.codahale.metrics.jmx.JmxReporter
import com.codahale.metrics.jvm._
import env.Env
import events.StatsDReporter
import javax.management.{Attribute, ObjectName}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import scala.concurrent.duration.FiniteDuration
import java.io.StringWriter
import java.util
import java.util.concurrent.TimeUnit

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.MetricRegistry.MetricSupplier
import com.codahale.metrics.json.MetricsModule
import com.codahale.metrics.jvm.{MemoryUsageGaugeSet, ThreadStatesGaugeSet}
import com.fasterxml.jackson.databind.ObjectMapper
import io.prometheus.client.Collector
import io.prometheus.client.dropwizard.DropwizardExports
import io.prometheus.client.exporter.common.TextFormat
import play.api.inject.ApplicationLifecycle

import scala.concurrent.{ExecutionContext, Future}

trait TimerMetrics {
  def withTimer[T](name: String)(f: => T): T = f
  def withTimerAsync[T](name: String)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = f
}

object FakeTimerMetrics extends TimerMetrics

trait HasMetrics {
  def metrics: TimerMetrics
}

object FakeHasMetrics extends HasMetrics {
  val metrics: TimerMetrics = FakeTimerMetrics
}

class Metrics(env: Env, applicationLifecycle: ApplicationLifecycle) extends TimerMetrics {

  private implicit val ev                    = env
  private implicit val ec                    = env.otoroshiExecutionContext
  private val metricRegistry: MetricRegistry = new MetricRegistry
  private val mbs                            = ManagementFactory.getPlatformMBeanServer
  private val rt                             = Runtime.getRuntime

  private val appEnv         = Option(System.getenv("APP_ENV")).getOrElse("--")
  private val commitId       = Option(System.getenv("COMMIT_ID")).getOrElse("--")
  private val instanceNumber = Option(System.getenv("INSTANCE_NUMBER")).getOrElse("--")
  private val appId          = Option(System.getenv("APP_ID")).getOrElse("--")
  private val instanceId     = Option(System.getenv("INSTANCE_ID")).getOrElse("--")

  private val lastcalls                     = new AtomicLong(0L)
  private val lastdataIn                    = new AtomicLong(0L)
  private val lastdataOut                   = new AtomicLong(0L)
  private val lastrate                      = new AtomicLong(0L)
  private val lastduration                  = new AtomicLong(0L)
  private val lastoverhead                  = new AtomicLong(0L)
  private val lastdataInRate                = new AtomicLong(0L)
  private val lastdataOutRate               = new AtomicLong(0L)
  private val lastconcurrentHandledRequests = new AtomicLong(0L)
  private val lastData                      = new ConcurrentHashMap[String, AtomicReference[Any]]()

  // metricRegistry.register("jvm.buffer", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()))
  // metricRegistry.register("jvm.classloading", new ClassLoadingGaugeSet())
  // metricRegistry.register("jvm.files", new FileDescriptorRatioGauge())
  metricRegistry.register("jvm.memory", new MemoryUsageGaugeSet())
  metricRegistry.register("jvm.thread", new ThreadStatesGaugeSet())
  metricRegistry.register("jvm.gc", new GarbageCollectorMetricSet())
  metricRegistry.register("jvm.attr", new JvmAttributeGaugeSet())
  metricRegistry.register(
    "attr",
    new MetricSet {
      override def getMetrics: util.Map[String, Metric] = {
        val gauges = new util.HashMap[String, Metric]
        gauges.put("jvm.cpu.usage", internalGauge((getProcessCpuLoad() * 100).toLong))
        gauges.put("jvm.heap.used", internalGauge((rt.totalMemory() - rt.freeMemory()) / 1024 / 1024))
        gauges.put("jvm.heap.size", internalGauge(rt.totalMemory() / 1024 / 1024))
        gauges.put("instance.env", internalGauge(appEnv))
        gauges.put("instance.id", internalGauge(instanceId))
        gauges.put("instance.number", internalGauge(instanceNumber))
        gauges.put("app.id", internalGauge(appId))
        gauges.put("app.commit", internalGauge(commitId))
        gauges.put("cluster.mode", internalGauge(env.clusterConfig.mode.name))
        gauges.put(
          "cluster.name",
          internalGauge(env.clusterConfig.mode match {
            case ClusterMode.Worker => env.clusterConfig.worker.name
            case ClusterMode.Leader => env.clusterConfig.leader.name
            case ClusterMode.Off    => "--"
          })
        )
        Collections.unmodifiableMap(gauges)
      }
    }
  )

  private def mark[T](name: String, value: Any): Unit = {
    lastData.computeIfAbsent(name, (t: String) => new AtomicReference[Any](value))
    lastData.getOrDefault(name, new AtomicReference[Any](value)).set(value)
    metricRegistry.gauge("otoroshi.internals." + name,
                         () => internalGauge(lastData.getOrDefault(name, new AtomicReference[Any](value)).get()))
  }

  def markString(name: String, value: String): Unit = mark(name, value)
  def markLong(name: String, value: Long): Unit     = mark(name, value)
  def markDouble(name: String, value: Double): Unit = mark(name, value)
  def counter(name: String): Counter                = metricRegistry.counter(name)
  def histogram(name: String): Histogram            = metricRegistry.histogram(name)
  def timer(name: String): Timer                    = metricRegistry.timer(name)
  override def withTimer[T](name: String)(f: => T): T = {
    val ctx = metricRegistry.timer(name).time()
    try {
      val res = f
      ctx.close()
      res
    } catch {
      case e: Throwable =>
        ctx.close()
        metricRegistry.counter(name + ".errors").inc()
        throw e
    }
  }
  override def withTimerAsync[T](name: String)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val ctx = metricRegistry.timer(name).time()
    f.andThen {
      case r =>
        ctx.close()
        if (r.isFailure) {
          metricRegistry.counter(name + ".errors").inc()
        }
    }
  }

  private def internalGauge[T](f: => T): Gauge[T] = {
    new Gauge[T] {
      override def getValue: T = f
    }
  }

  private val objectMapper = new ObjectMapper()
  objectMapper.registerModule(new MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, false))
  private val prometheus = new DropwizardExports(metricRegistry)

  def prometheusExport(filter: Option[String] = None): String = {
    filter match {
      case None => {
        val writer = new StringWriter()
        TextFormat.write004(writer, new SimpleEnum(prometheus.collect()))
        writer.toString
      }
      case Some(path) => {
        val processedPath = path.replace(".", "_")
        val writer        = new StringWriter()
        TextFormat.write004(writer, new SimpleEnum(prometheus.collect()))
        writer.toString.split("\n").toSeq.filter(line => RegexPool(processedPath).matches(line)).mkString("\n")
      }
    }
  }

  def jsonExport(filter: Option[String] = None): String = {
    filter match {
      case None => objectMapper.writeValueAsString(metricRegistry)
      case Some(path) => {
        val jsonRaw = objectMapper.writeValueAsString(metricRegistry)
        val json    = Json.parse(jsonRaw)
        Json.stringify(
          JsArray(
            (json \ "gauges")
              .as[JsObject]
              .value
              .toSeq
              .filter(t => RegexPool(path).matches(t._1))
              .map(tuple => Json.obj("type" -> "gauge", "name" -> tuple._1) ++ tuple._2.as[JsObject]) ++
            (json \ "counters")
              .as[JsObject]
              .value
              .toSeq
              .filter(t => RegexPool(path).matches(t._1))
              .map(tuple => Json.obj("type" -> "counter", "name" -> tuple._1) ++ tuple._2.as[JsObject]) ++
            (json \ "histograms")
              .as[JsObject]
              .value
              .toSeq
              .filter(t => RegexPool(path).matches(t._1))
              .map(tuple => Json.obj("type" -> "histogram", "name" -> tuple._1) ++ tuple._2.as[JsObject]) ++
            (json \ "meters")
              .as[JsObject]
              .value
              .toSeq
              .filter(t => RegexPool(path).matches(t._1))
              .map(tuple => Json.obj("type" -> "meter", "name" -> tuple._1) ++ tuple._2.as[JsObject]) ++
            (json \ "timers")
              .as[JsObject]
              .value
              .toSeq
              .filter(t => RegexPool(path).matches(t._1))
              .map(tuple => Json.obj("type" -> "timer", "name" -> tuple._1) ++ tuple._2.as[JsObject])
          )
        )
      }
    }
  }

  def defaultHttpFormat(filter: Option[String] = None): String = defaultFormat("json")

  def defaultFormat(format: String, filter: Option[String] = None): String = format match {
    case "json"       => jsonExport(filter)
    case "prometheus" => prometheusExport(filter)
    case _            => jsonExport(filter)
  }

  private def getProcessCpuLoad(): Double = {
    val name = ObjectName.getInstance("java.lang:type=OperatingSystem")
    val list = mbs.getAttributes(name, Array("ProcessCpuLoad"))
    if (list.isEmpty) return 0.0
    val att   = list.get(0).asInstanceOf[Attribute]
    val value = att.getValue.asInstanceOf[Double]
    if (value == -1.0) return 0.0
    (value * 1000) / 10.0
    // ManagementFactory.getOperatingSystemMXBean.getSystemLoadAverage
  }

  private def sumDouble(value: Double, extractor: StatsView => Double, stats: Seq[StatsView]): Double = {
    stats.map(extractor).:+(value).fold(0.0)(_ + _)
  }

  private def avgDouble(value: Double, extractor: StatsView => Double, stats: Seq[StatsView]): Double = {
    stats.map(extractor).:+(value).fold(0.0)(_ + _) / (stats.size + 1)
  }

  private def updateMetrics(): Unit = {
    for {
      calls                     <- env.datastores.serviceDescriptorDataStore.globalCalls()
      dataIn                    <- env.datastores.serviceDescriptorDataStore.globalDataIn()
      dataOut                   <- env.datastores.serviceDescriptorDataStore.globalDataOut()
      rate                      <- env.datastores.serviceDescriptorDataStore.globalCallsPerSec()
      duration                  <- env.datastores.serviceDescriptorDataStore.globalCallsDuration()
      overhead                  <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
      dataInRate                <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor("global")
      dataOutRate               <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor("global")
      concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
      membersStats              <- env.datastores.clusterStateDataStore.getMembers().map(_.map(_.statsView))
    } yield {
      lastcalls.set(calls)
      lastdataIn.set(dataIn)
      lastdataOut.set(dataOut)
      lastrate.set(sumDouble(rate, _.rate, membersStats).toLong)
      lastduration.set(avgDouble(duration, _.duration, membersStats).toLong)
      lastoverhead.set(avgDouble(overhead, _.overhead, membersStats).toLong)
      lastdataInRate.set(sumDouble(dataInRate, _.dataInRate, membersStats).toLong)
      lastdataOutRate.set(sumDouble(dataOutRate, _.dataOutRate, membersStats).toLong)
      lastconcurrentHandledRequests.set(
        sumDouble(concurrentHandledRequests.toDouble, _.concurrentHandledRequests.toDouble, membersStats).toLong
      )
      ()
    }
  }

  private val update: Option[Cancellable] = {
    Some(env.metricsEnabled).filter(_ == true).map { _ =>
      val cancellable = env.otoroshiScheduler.schedule(
        FiniteDuration(5, TimeUnit.SECONDS),
        env.metricsEvery,
        new Runnable {
          override def run(): Unit = updateMetrics()
        }
      )
      cancellable
    }
  }

  private val jmx: Option[JmxReporter] = {
    Some(env.metricsEnabled).filter(_ == true).map { _ =>
      val reporter: JmxReporter = JmxReporter
        .forRegistry(metricRegistry)
        .convertRatesTo(TimeUnit.SECONDS)
        .convertDurationsTo(TimeUnit.MILLISECONDS)
        .build
      reporter.start()
      reporter
    }
  }

  private val statsd: Option[StatsDReporter] = {
    Some(env.metricsEnabled).filter(_ == true).map { _ =>
      new StatsDReporter(metricRegistry, env).start()
    }
  }

  applicationLifecycle.addStopHook { () =>
    update.foreach(_.cancel())
    jmx.foreach(_.stop())
    statsd.foreach(_.stop())
    FastFuture.successful(())
  }
}

class SimpleEnum[T](l: util.List[T]) extends util.Enumeration[T] {
  private val it                        = l.iterator()
  override def hasMoreElements: Boolean = it.hasNext
  override def nextElement(): T         = it.next()
}

case class MeterView(count: Long,
                     meanRate: Double,
                     oneMinuteRate: Double,
                     fiveMinuteRate: Double,
                     fifteenMinuteRate: Double) {
  def toJson: JsValue = Json.obj(
    "count"             -> count,
    "meanRate"          -> meanRate,
    "oneMinuteRate"     -> oneMinuteRate,
    "fiveMinuteRate"    -> fiveMinuteRate,
    "fifteenMinuteRate" -> fifteenMinuteRate
  )
}

object MeterView {
  def apply(meter: Meter): MeterView =
    new MeterView(meter.getCount,
                  meter.getMeanRate,
                  meter.getOneMinuteRate,
                  meter.getFiveMinuteRate,
                  meter.getFifteenMinuteRate)
}

case class TimerView(count: Long,
                     meanRate: Double,
                     oneMinuteRate: Double,
                     fiveMinuteRate: Double,
                     fifteenMinuteRate: Double) {
  def toJson: JsValue = Json.obj(
    "count"             -> count,
    "meanRate"          -> meanRate,
    "oneMinuteRate"     -> oneMinuteRate,
    "fiveMinuteRate"    -> fiveMinuteRate,
    "fifteenMinuteRate" -> fifteenMinuteRate
  )
}

object TimerView {
  def apply(meter: Timer): TimerView =
    new TimerView(meter.getCount,
                  meter.getMeanRate,
                  meter.getOneMinuteRate,
                  meter.getFiveMinuteRate,
                  meter.getFifteenMinuteRate)
}
