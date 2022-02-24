package otoroshi.utils.prometheus

import com.codahale.metrics._
import com.spotify.metrics.core.{MetricId, SemanticMetricRegistry}
import io.prometheus.client.Collector.{MetricFamilySamples, Type}
import io.prometheus.client.dropwizard.samplebuilder.{DefaultSampleBuilder, SampleBuilder}

import java.util
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.mapAsScalaMapConverter

class CustomCollector(registry: SemanticMetricRegistry, jmxRegistry: MetricRegistry)
    extends io.prometheus.client.Collector
    with io.prometheus.client.Collector.Describable {

  private val sampleBuilder: SampleBuilder = new DefaultSampleBuilder

  def fromCounter(key: MetricId, entryValue: Counter): MetricFamilySamples = {
    val sample = getSample(key, entryValue.getCount.doubleValue)
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

  def fromGauge(key: MetricId, entryValue: Gauge[_]): MetricFamilySamples = {
    val obj   = entryValue.getValue
    var value = .0

    obj match {
      case number: Number => value = number.doubleValue
      case bool: Boolean  => value = if (bool) 1 else 0
      case _              => return null
    }

    val sample = getSample(key, value)
    new MetricFamilySamples(sample.name, Type.GAUGE, "", util.Arrays.asList(sample))
  }

  private def combineValueAndList(value: String, l: util.Collection[String]) = {
    val half = new util.ArrayList[String]() { { value } }
    half.addAll(new util.ArrayList[String](l))
    half
  }

  def fromSnapshotAndCount(
      name: String,
      snapshot: Snapshot,
      count: Long,
      factor: Double,
      tags: util.Map[String, String]
  ): MetricFamilySamples = {
    val quantile = new util.ArrayList[String]() { { "quantile" } }
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

  def fromHistogram(key: MetricId, entryValue: Histogram): MetricFamilySamples =
    fromSnapshotAndCount(
      key.getKey,
      entryValue.getSnapshot,
      entryValue.getCount,
      1.0,
      key.getTags
    )

  def fromTimer(key: MetricId, entryValue: Timer): MetricFamilySamples =
    fromSnapshotAndCount(
      key.getKey,
      entryValue.getSnapshot,
      entryValue.getCount,
      1.0d / TimeUnit.SECONDS.toNanos(1L),
      key.getTags
    )

  def fromMeter(key: MetricId, entryValue: Meter): MetricFamilySamples = {
    val sample = getSample(key, entryValue.getCount, "_count")
    new MetricFamilySamples(sample.name, Type.COUNTER, "", util.Arrays.asList(sample))
  }

  override def collect: util.List[MetricFamilySamples] = {
    val mfSamplesMap = new util.HashMap[String, MetricFamilySamples]

    registry.getGauges.entrySet.forEach(entry => addToMap(mfSamplesMap, fromGauge(entry.getKey, entry.getValue)))
    registry.getCounters.entrySet.forEach(entry => addToMap(mfSamplesMap, fromCounter(entry.getKey, entry.getValue)))
    registry.getHistograms.entrySet.forEach(entry =>
      addToMap(mfSamplesMap, fromHistogram(entry.getKey, entry.getValue))
    )
    registry.getTimers.entrySet.forEach(entry => addToMap(mfSamplesMap, fromTimer(entry.getKey, entry.getValue)))
    registry.getMeters.entrySet.forEach(entry => addToMap(mfSamplesMap, fromMeter(entry.getKey, entry.getValue)))

    jmxRegistry.getGauges.entrySet.forEach(entry =>
      addToMap(mfSamplesMap, fromGauge(MetricId.build(entry.getKey), entry.getValue))
    )
    jmxRegistry.getCounters.entrySet.forEach(entry =>
      addToMap(mfSamplesMap, fromCounter(MetricId.build(entry.getKey), entry.getValue))
    )
    jmxRegistry.getHistograms.entrySet.forEach(entry =>
      addToMap(mfSamplesMap, fromHistogram(MetricId.build(entry.getKey), entry.getValue))
    )
    jmxRegistry.getTimers.entrySet.forEach(entry =>
      addToMap(mfSamplesMap, fromTimer(MetricId.build(entry.getKey), entry.getValue))
    )
    jmxRegistry.getMeters.entrySet.forEach(entry =>
      addToMap(mfSamplesMap, fromMeter(MetricId.build(entry.getKey), entry.getValue))
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
        mfSamplesMap.put(
          newMfSamples.name,
          new MetricFamilySamples(newMfSamples.name, currentMfSamples.`type`, currentMfSamples.help, samples)
        )
      }
    }
  }

  override def describe = new util.ArrayList[MetricFamilySamples]
}
