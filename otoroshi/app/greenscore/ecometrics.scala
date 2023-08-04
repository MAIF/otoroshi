package otoroshi.greenscore

import com.codahale.metrics.UniformReservoir
import otoroshi.env.Env
import otoroshi.utils.cache.types.UnboundedTrieMap
import play.api.Logger
import play.api.libs.json.JsValue

import java.util.{Timer => _}
import scala.collection.concurrent.TrieMap

class GlobalScore {
  private val backendsScore: UnboundedTrieMap[String, BackendScore] = TrieMap.empty
  private val routesScore: UnboundedTrieMap[String, RouteScore] = TrieMap.empty

  def updateBackend(backendId: String, dataIn: Long,
                    dataOut: Long,
                    headers: Long,
                    headersOut: Long) = {
    backendsScore.getOrElseUpdate(backendId, new BackendScore()).update(dataIn, dataOut, headers, headersOut)
  }

  def updateRoute(routeId: String, overhead: Long,
                  overheadWithoutCircuitBreaker: Long,
                  circuitBreakerDuration: Long,
                  duration: Long,
                  plugins: Int) = {
    routesScore.getOrElseUpdate(routeId, new RouteScore())
      .update(overhead, overheadWithoutCircuitBreaker, circuitBreakerDuration, duration, plugins)
  }

  def compute(): Double = {
    backendsScore.values.foldLeft(0.0) { case (acc, item) => acc + item.compute() } +
      routesScore.values.foldLeft(0.0) { case (acc, item) => acc + item.compute() }
  }
}

class RouteScore {
  private val overheadReservoir: UniformReservoir = new UniformReservoir()
  private val overheadWithoutCircuitBreakerReservoir: UniformReservoir = new UniformReservoir()
  private val circuitBreakerDurationReservoir: UniformReservoir = new UniformReservoir()
  private val durationReservoir: UniformReservoir = new UniformReservoir()
  private val pluginsReservoir: UniformReservoir = new UniformReservoir()

  def update(overhead: Long,
             overheadWithoutCircuitBreaker: Long,
             circuitBreakerDuration: Long,
             duration: Long,
             plugins: Int) = {
    overheadReservoir.update(overhead)
    overheadWithoutCircuitBreakerReservoir.update(overheadWithoutCircuitBreaker)
    circuitBreakerDurationReservoir.update(circuitBreakerDuration)
    durationReservoir.update(duration)
    pluginsReservoir.update(plugins)
  }

  def compute(): Double = {
    overheadReservoir.getSnapshot.getMean +
      overheadWithoutCircuitBreakerReservoir.getSnapshot.getMean +
      circuitBreakerDurationReservoir.getSnapshot.getMean +
      durationReservoir.getSnapshot.getMean +
      pluginsReservoir.getSnapshot.getMean
  }
}

class BackendScore {
  private val dataInReservoir: UniformReservoir = new UniformReservoir()
  private val headersOutReservoir: UniformReservoir = new UniformReservoir()
  private val dataOutReservoir: UniformReservoir = new UniformReservoir()
  private val headersReservoir: UniformReservoir = new UniformReservoir()

  def update(dataIn: Long,
             dataOut: Long,
             headers: Long,
             headersOut: Long) = {
    dataInReservoir.update(dataIn)
    dataOutReservoir.update(dataOut)
    headersReservoir.update(headers)
    headersOutReservoir.update(headersOut)
  }

  def compute(): Double = {
    dataInReservoir.getSnapshot.getMean +
      dataOutReservoir.getSnapshot.getMean +
      headersReservoir.getSnapshot.getMean +
      headersOutReservoir.getSnapshot.getMean
  }
}

class EcoMetrics(env: Env)  {

  private implicit val ev = env
  private implicit val ec = env.otoroshiExecutionContext

  private val logger = Logger("otoroshi-eco-metrics")

  private val registry = new GlobalScore()

  def compute() = registry.compute()

  def updateBackend(backendId: String,
                    dataIn: Long,
                    dataOut: Long,
                    headers: Long,
                    headersOut: Long) = {
    registry.updateBackend(backendId, dataIn, dataOut, headers, headersOut)
  }

  def updateRoute(routeId: String,
                  overhead: Long,
                  overheadWoCb: Long,
                  cbDuration: Long,
                  duration: Long,
                  plugins: Int) = {
    registry.updateRoute(routeId, overhead, overheadWoCb, cbDuration, duration, plugins)
  }
}