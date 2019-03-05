package utils

import java.lang.management.ManagementFactory

import akka.actor.Cancellable
import akka.http.scaladsl.util.FastFuture
import cluster.StatsView
import com.codahale.metrics.jmx.JmxReporter
import com.codahale.metrics.jvm._
import com.codahale.metrics.{Meter, Timer}
import env.Env
import javax.management.{Attribute, ObjectName}
import play.api.libs.json.JsValue

import scala.concurrent.duration.FiniteDuration

//object Metrics {
//val metrics = new MetricRegistry()
//}

import java.io.StringWriter
import java.util
import java.util.concurrent.TimeUnit

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.json.MetricsModule
import com.codahale.metrics.jvm.{MemoryUsageGaugeSet, ThreadStatesGaugeSet}
import com.fasterxml.jackson.databind.ObjectMapper
import io.prometheus.client.dropwizard.DropwizardExports
import io.prometheus.client.exporter.common.TextFormat
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json

class Metrics(env: Env, applicationLifecycle: ApplicationLifecycle) {

  private implicit val ev =  env
  private implicit val ec =  env.otoroshiExecutionContext
  private val metricRegistry: MetricRegistry = new MetricRegistry
  private val mbs = ManagementFactory.getPlatformMBeanServer
  private val rt  = Runtime.getRuntime

  metricRegistry.register("jvm.memory", new MemoryUsageGaugeSet())
  metricRegistry.register("jvm.thread", new ThreadStatesGaugeSet())
  metricRegistry.register("jvm.buffer", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()))
  metricRegistry.register("jvm.classloading", new ClassLoadingGaugeSet())
  metricRegistry.register("jvm.gc", new GarbageCollectorMetricSet())
  metricRegistry.register("jvm.files", new FileDescriptorRatioGauge())
  metricRegistry.register("jvm.attr", new JvmAttributeGaugeSet())

  private val objectMapper = new ObjectMapper()
  objectMapper.registerModule(new MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, true))
  private val prometheus = new DropwizardExports(metricRegistry)

  def prometheusExport: String = {
    val writer = new StringWriter()
    TextFormat.write004(writer, new SimpleEnum(prometheus.collect()))
    writer.toString
  }

  def jsonExport: String =
    objectMapper.writeValueAsString(metricRegistry)

  def defaultHttpFormat: String = defaultFormat("json")

  def defaultFormat(format: String): String = format match {
    case "json"       => jsonExport
    case "prometheus" => prometheusExport
    case _            => jsonExport
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
    metricRegistry.meter("jvm.otoroshi.cpu_usage").mark((getProcessCpuLoad() * 100).toLong)
    metricRegistry.meter("jvm.otoroshi.heap_used").mark((rt.totalMemory() - rt.freeMemory()) / 1024 / 1024)
    metricRegistry.meter("jvm.otoroshi.heap_size").mark(rt.totalMemory() / 1024 / 1024)
    metricRegistry.meter("jvm.otoroshi.live_threads").mark(ManagementFactory.getThreadMXBean.getThreadCount)
    metricRegistry.meter("jvm.otoroshi.live_peak_threads").mark(ManagementFactory.getThreadMXBean.getPeakThreadCount)
    metricRegistry.meter("jvm.otoroshi.daemon_threads").mark(ManagementFactory.getThreadMXBean.getDaemonThreadCount)
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
      metricRegistry.meter("otoroshi.calls").mark(calls)
      metricRegistry.meter("otoroshi.dataIn").mark(dataIn)
      metricRegistry.meter("otoroshi.dataOut").mark(dataOut)
      metricRegistry.meter("otoroshi.rate").mark(sumDouble(rate, _.rate, membersStats).toLong)
      metricRegistry.meter("otoroshi.duration").mark(avgDouble(duration, _.duration, membersStats).toLong)
      metricRegistry.meter("otoroshi.overhead").mark(avgDouble(overhead, _.overhead, membersStats).toLong)
      metricRegistry.meter("otoroshi.dataInRate").mark(sumDouble(dataInRate, _.dataInRate, membersStats).toLong)
      metricRegistry.meter("otoroshi.dataOutRate").mark(sumDouble(dataOutRate, _.dataOutRate, membersStats).toLong)
      metricRegistry.meter("otoroshi.concurrentHandledRequests").mark(
        sumDouble(concurrentHandledRequests.toDouble,
        _.concurrentHandledRequests.toDouble,
        membersStats).toLong
      )
      ()
    }
  }

  private val update: Option[Cancellable] = {
    val cancellable = env.otoroshiScheduler.schedule(
      FiniteDuration(10, TimeUnit.SECONDS),
      FiniteDuration(10, TimeUnit.SECONDS),
      new Runnable {
        override def run(): Unit = updateMetrics()
      }
    )
    Some(cancellable)
  }

  private val jmx: Option[JmxReporter] = {
    val reporter: JmxReporter = JmxReporter
      .forRegistry(metricRegistry)
      .convertRatesTo(TimeUnit.SECONDS)
      .convertDurationsTo(TimeUnit.MILLISECONDS)
      .build
    reporter.start()
    Some(reporter)
  }

  applicationLifecycle.addStopHook { () =>
    update.foreach(_.cancel())
    jmx.foreach(_.stop())
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
