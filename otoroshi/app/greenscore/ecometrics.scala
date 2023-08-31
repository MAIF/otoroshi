package otoroshi.greenscore

import com.codahale.metrics.UniformReservoir
import otoroshi.env.Env
import otoroshi.utils.cache.types.UnboundedTrieMap
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}

import java.util.{Timer => _}
import scala.collection.concurrent.TrieMap

class GlobalScore {
  private val routesScore: UnboundedTrieMap[String, RouteScore] = TrieMap.empty

  def updateRoute(
      routeId: String,
      overhead: Long,
      overheadWithoutCircuitBreaker: Long,
      circuitBreakerDuration: Long,
      duration: Long,
      plugins: Int,
      backendId: String,
      dataIn: Long,
      dataOut: Long,
      headers: Long,
      headersOut: Long
  ) = {
    routesScore
      .getOrElseUpdate(routeId, new RouteScore())
      .update(
        overhead,
        overheadWithoutCircuitBreaker,
        circuitBreakerDuration,
        duration,
        plugins,
        backendId,
        dataIn,
        dataOut,
        headers,
        headersOut
      )
  }

  def json(routeId: String) = routesScore.get(routeId).map(_.json()).getOrElse(Json.obj())
}

class RouteScore {
  private val overheadReservoir: UniformReservoir                      = new UniformReservoir()
  private val overheadWithoutCircuitBreakerReservoir: UniformReservoir = new UniformReservoir()
  private val circuitBreakerDurationReservoir: UniformReservoir        = new UniformReservoir()
  private val durationReservoir: UniformReservoir                      = new UniformReservoir()
  private val pluginsReservoir: UniformReservoir                       = new UniformReservoir()

  private val dataInReservoir: UniformReservoir     = new UniformReservoir()
  private val headersOutReservoir: UniformReservoir = new UniformReservoir()
  private val dataOutReservoir: UniformReservoir    = new UniformReservoir()
  private val headersReservoir: UniformReservoir    = new UniformReservoir()

  private var backendId: String = ""

  def update(
      overhead: Long,
      overheadWithoutCircuitBreaker: Long,
      circuitBreakerDuration: Long,
      duration: Long,
      plugins: Int,
      backendId: String,
      dataIn: Long,
      dataOut: Long,
      headers: Long,
      headersOut: Long
  ) = {
    overheadReservoir.update(overhead)
    overheadWithoutCircuitBreakerReservoir.update(overheadWithoutCircuitBreaker)
    circuitBreakerDurationReservoir.update(circuitBreakerDuration)
    durationReservoir.update(duration)
    pluginsReservoir.update(plugins)

    dataInReservoir.update(dataIn)
    dataOutReservoir.update(dataOut)
    headersReservoir.update(headers)
    headersOutReservoir.update(headersOut)

    this.backendId = backendId
  }

  def json(): JsValue = Json.obj(
    "overheadReservoir"                      -> overheadReservoir.getSnapshot.getMean,
    "overheadWithoutCircuitBreakerReservoir" -> overheadWithoutCircuitBreakerReservoir.getSnapshot.getMean,
    "circuitBreakerDurationReservoir"        -> circuitBreakerDurationReservoir.getSnapshot.getMean,
    "durationReservoir"                      -> durationReservoir.getSnapshot.getMean,
    "pluginsReservoir"                       -> pluginsReservoir.getSnapshot.getMean,
    "backendId"                              -> backendId,
    "dataInReservoir"                        -> dataInReservoir.getSnapshot.getMean,
    "dataOutReservoir"                       -> dataOutReservoir.getSnapshot.getMean,
    "headersReservoir"                       -> headersReservoir.getSnapshot.getMean,
    "headersOutReservoir"                    -> headersOutReservoir.getSnapshot.getMean
  )

//  def compute(): Double = dataOutReservoir.getSnapshot.getMean + headersOutReservoir.getSnapshot.getMean
}

class EcoMetrics(env: Env) {

  private val registry = new GlobalScore()

  def json(routeId: String): JsValue = registry.json(routeId)

  def updateRoute(
      routeId: String,
      overhead: Long,
      overheadWoCb: Long,
      cbDuration: Long,
      duration: Long,
      plugins: Int,
      backendId: String,
      dataIn: Long,
      dataOut: Long,
      headers: Long,
      headersOut: Long
  ) = {
    registry.updateRoute(
      routeId,
      overhead,
      overheadWoCb,
      cbDuration,
      duration,
      plugins,
      backendId,
      dataIn,
      dataOut,
      headers,
      headersOut
    )
  }
}
