package utils

import com.codahale.metrics._
import com.spotify.metrics.core.{MetricId, SemanticMetricRegistry}
import io.prometheus.client.Collector.{MetricFamilySamples, Type}
import io.prometheus.client.dropwizard.samplebuilder.{DefaultSampleBuilder, SampleBuilder}

import java.util
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.mapAsScalaMapConverter

class CustomCollector(registry: SemanticMetricRegistry)
  extends io.prometheus.client.Collector
    with io.prometheus.client.Collector.Describable {

  private val sampleBuilder: SampleBuilder = new DefaultSampleBuilder

  def fromCounter(entry: util.Map.Entry[MetricId, Counter]): MetricFamilySamples = {
    val sample = getSample(entry.getKey,  entry.getValue.getCount.doubleValue)
    new MetricFamilySamples(sample.name, Type.GAUGE, "", util.Arrays.asList(sample))
  }

  private def getSample(metric: MetricId, value: Double, suffix: String = ""): MetricFamilySamples.Sample = {
    sampleBuilder.createSample(
      metric.getKey,
      suffix,
      new util.ArrayList[String](metric.getTags.keySet),
      new util.ArrayList[String](metric.getTags.values),
      value
    )
  }

  def fromGauge(entry: util.Map.Entry[MetricId, Gauge[_]]): MetricFamilySamples = {
    val obj = entry.getValue.getValue
    var value = .0

    obj match {
      case number: Number => value = number.doubleValue
      case bool: Boolean => value = if (bool) 1 else 0
      case _ => return null
    }

    val sample = getSample(entry.getKey, value)
    new MetricFamilySamples(sample.name, Type.GAUGE, "", util.Arrays.asList(sample))
  }

  private def combineValueAndList(value: String, l: util.Collection[String]) = {
    val half =  new util.ArrayList[String](){{ value }}
    half.addAll(new util.ArrayList[String](l))
    half
  }

  def fromSnapshotAndCount(name: String, snapshot: Snapshot, count: Long, factor: Double, tags: util.Map[String, String]): MetricFamilySamples = {
    val quantile = new util.ArrayList[String](){{ "quantile" }}
    quantile.addAll(new util.ArrayList[String](tags.keySet))

    val samples = util.Arrays.asList(
      sampleBuilder.createSample(
        name,
        "",
        quantile,
        combineValueAndList("0.5", tags.values),
        snapshot.getMedian * factor
      ),
      sampleBuilder.createSample(
        name,
        "",
        quantile,
        combineValueAndList("0.75", tags.values),
        snapshot.get75thPercentile * factor
      ),
      sampleBuilder.createSample(
        name,
        "",
        quantile,
        combineValueAndList("0.95", tags.values),
        snapshot.get95thPercentile * factor
      ),
      sampleBuilder.createSample(
        name,
        "",
        quantile,
        combineValueAndList("0.98", tags.values),
        snapshot.get98thPercentile * factor
      ),
      sampleBuilder.createSample(
        name,
        "",
        quantile,
        combineValueAndList("0.99", tags.values),
        snapshot.get99thPercentile * factor
      ),
      sampleBuilder.createSample(
        name,
        "",
        quantile,
        combineValueAndList("0.999", tags.values),
        snapshot.get999thPercentile * factor
      ),
      sampleBuilder.createSample(
        name,
        "_count",
        new util.ArrayList[String](tags.keySet),
        new util.ArrayList[String](tags.values),
        count
      )
    )
    new MetricFamilySamples(samples.get(0).name, Type.SUMMARY, "", samples)
  }

  def fromHistogram(entry: util.Map.Entry[MetricId, Histogram]): MetricFamilySamples = fromSnapshotAndCount(
    entry.getKey.getKey, entry.getValue.getSnapshot, entry.getValue.getCount, 1.0, entry.getKey.getTags
  )

  def fromTimer(entry: util.Map.Entry[MetricId, Timer]): MetricFamilySamples = fromSnapshotAndCount(
    entry.getKey.getKey,
    entry.getValue.getSnapshot,
    entry.getValue.getCount,
    1.0D / TimeUnit.SECONDS.toNanos(1L),
    entry.getKey.getTags
  )

  def fromMeter(entry: util.Map.Entry[MetricId, Meter]): MetricFamilySamples = {
    val sample = getSample(entry.getKey, entry.getValue.getCount, "_count")
    new MetricFamilySamples(sample.name, Type.COUNTER, "", util.Arrays.asList(sample))
  }

  override def collect: util.List[MetricFamilySamples] = {
    val mfSamplesMap = new util.HashMap[String, MetricFamilySamples]

    registry.getGauges.entrySet.forEach(entry =>
      addToMap(mfSamplesMap, fromGauge(entry))
    )

    registry.getCounters.entrySet.forEach(entry =>
      addToMap(mfSamplesMap, fromCounter(entry))
    )

    registry.getHistograms.entrySet.forEach(entry =>
      addToMap(mfSamplesMap, fromHistogram(entry))
    )

    registry.getTimers.entrySet.forEach(entry =>
      addToMap(mfSamplesMap, fromTimer(entry))
    )

    registry.getMeters.entrySet.forEach(entry =>
      addToMap(mfSamplesMap, fromMeter(entry))
    )

    new util.ArrayList[MetricFamilySamples](mfSamplesMap.values)
  }

  def getKeyOrTags(metric: MetricId): String =
      if (metric.getTags.isEmpty) metric.getKey else metric.getTags.asScala.mkString(",")

  private def addToMap(mfSamplesMap: util.Map[String, MetricFamilySamples], newMfSamples: MetricFamilySamples): Unit = {
    if (newMfSamples != null) {
      val currentMfSamples = mfSamplesMap.get(newMfSamples.name)
      if (currentMfSamples == null) mfSamplesMap.put(newMfSamples.name, newMfSamples)
      else {
        val samples = new util.ArrayList[MetricFamilySamples.Sample](currentMfSamples.samples)
        samples.addAll(newMfSamples.samples)
        mfSamplesMap.put(newMfSamples.name, new MetricFamilySamples(newMfSamples.name, currentMfSamples.`type`, currentMfSamples.help, samples))
      }
    }
  }

  override def describe = new util.ArrayList[MetricFamilySamples]
}
