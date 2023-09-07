package otoroshi.greenscore

import com.codahale.metrics.UniformReservoir
import otoroshi.env.Env
import otoroshi.greenscore.EcoMetrics.{MAX_GREEN_SCORE_NOTE, colorFromScore, letterFromScore, scoreToColor}
import otoroshi.greenscore.Score.{RouteScore, SectionScore}
import otoroshi.utils.cache.types.UnboundedTrieMap
import play.api.libs.json._

import java.util.{Timer => _}
import scala.collection.concurrent.TrieMap
import scala.util.Try

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

  def route(routeId: String): Option[RouteScore] = routesScore.get(routeId)

  def json(routeId: String) = routesScore.get(routeId).map(_.json()).getOrElse(new RouteScore().json())
}

class RouteScore {
  private val overheadReservoir: UniformReservoir                      = new UniformReservoir()
  private val overheadWithoutCircuitBreakerReservoir: UniformReservoir = new UniformReservoir()
  private val circuitBreakerDurationReservoir: UniformReservoir        = new UniformReservoir()
  private val durationReservoir: UniformReservoir                      = new UniformReservoir()
  var pluginsReservoir: Int                                    = 0

  private val dataInReservoir: UniformReservoir     = new UniformReservoir()
  val headersOutReservoir: UniformReservoir = new UniformReservoir()
  val dataOutReservoir: UniformReservoir    = new UniformReservoir()
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
    pluginsReservoir = plugins

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
    "pluginsReservoir"                       -> pluginsReservoir,
    "backendId"                              -> backendId,
    "dataInReservoir"                        -> dataInReservoir.getSnapshot.getMean,
    "dataOutReservoir"                       -> dataOutReservoir.getSnapshot.getMean,
    "headersReservoir"                       -> headersReservoir.getSnapshot.getMean,
    "headersOutReservoir"                    -> headersOutReservoir.getSnapshot.getMean
  )
}

object SectionScoreHelper {
  val format = new Format[SectionScore] {
    override def writes(o: SectionScore): JsValue = ???

    override def reads(value: JsValue): JsResult[SectionScore] = Try {
      JsSuccess(
        SectionScore(
          id = (value \ "id").as[String],
          score = (value \ "score").as[Double],
          normalizedScore = (value \ "normalized_score").as[Double],
          letter = (value \ "letter").as[String],
          color = (value \ "color").as[String],
        )
      )
    } recover { case e =>
      JsError(e.getMessage)
    } get
  }


  def mean(values: Seq[SectionScore]) = SectionScore(
    id = values.head.id,
    score = values.foldLeft(0.0)(_ + _.score) / values.length,
    normalizedScore = values.foldLeft(0.0)(_ + _.normalizedScore) / values.length,
    letter = letterFromScore(values.foldLeft(0.0)(_ + _.score)),
    color = colorFromScore(values.foldLeft(0.0)(_ + _.score))
  )
}

sealed trait Score {
  def color: String
  def letter: String
}
object Score {
  case class Excellent(color: String = "#2ecc71", letter: String = "A")       extends Score
  case class Acceptable(color: String = "#27ae60", letter: String = "B")      extends Score
  case class Sufficient(color: String = "#f1c40f", letter: String = "C")      extends Score
  case class Poor(color: String = "#d35400", letter: String = "D")            extends Score
  case class ExtremelyPoor(color: String = "#c0392b", letter: String = "E")   extends Score

  case class SectionScore(id: String = "",
                          score: Double = 0.0,
                          normalizedScore: Double = 0.0,
                          letter: String = "",
                          color: String = "") {
    def json = Json.obj(
      "id" -> id,
      "score" -> score,
      "normalized_score" -> normalizedScore,
      "letter" -> letter,
      "color" -> color
    )

    def merge(other: SectionScore): SectionScore = SectionScore(
      id = other.id,
      score = score + other.score,
      normalizedScore = normalizedScore + other.normalizedScore,
      letter = other.letter,
      color = other.color
    )
  }
  case class RouteScore(
                         route: RouteGreenScore,
                         informations: SectionScore,
                         sectionsScore: Seq[SectionScore],
                         pluginsInstance: Double,
                         producedData: Double,
                         producedHeaders: Double,
                         architecture: SectionScore,
                         design: SectionScore,
                         log: SectionScore,
                         usage: SectionScore
                       ) {
    def json(): JsObject = Json.obj(
      "route" -> Json.obj(
        "routeId" -> route.routeId,
        "rules_config" -> GreenScoreConfig.format.writes(route.rulesConfig)
      ),
      "informations" -> informations.json,
      "sections" -> sectionsScore.map(_.json),
      "plugins_instance" -> pluginsInstance,
      "produced_data" -> producedData,
      "produced_headers" -> producedHeaders,
      "architecture" -> architecture.json,
      "design" -> design.json,
      "log" -> log.json,
      "usage" -> usage.json
    )
  }
}

object EcoMetrics {
  private val MAX_GREEN_SCORE_NOTE = 6000

  private def scoreToColor(rank: Double): Score = {
    if (rank >= MAX_GREEN_SCORE_NOTE) {
      Score.Excellent()
    } else if (rank >= 3000) {
      Score.Acceptable()
    } else if (rank >= 2000) {
      Score.Sufficient()
    } else if (rank >= 1000) {
      Score.Poor()
    } else // rank < 1000
      Score.ExtremelyPoor()
  }

  def letterFromScore(rank: Double): String = {
    scoreToColor(rank).letter
  }

  def colorFromScore(rank: Double): String = {
    scoreToColor(rank).color
  }
}

class EcoMetrics(env: Env) {

  private val registry = new GlobalScore()

  private def calculateRules(rules: GreenScoreConfig) = {
    rules.sections
      .foldLeft(Seq.empty[SectionScore]) {
        case (scores, section) =>
          val sectionScore = section.rules.foldLeft(0.0) { case (acc, rule) =>
            if (rule.enabled) {
              acc + MAX_GREEN_SCORE_NOTE * (rule.sectionWeight / 100) * (rule.weight / 100)
            } else {
              acc
            }
          }
          scores :+ SectionScore(
            section.id.value,
            sectionScore,
            sectionScore / (section.rules.head.sectionWeight / 100 * MAX_GREEN_SCORE_NOTE),
            letterFromScore(sectionScore),
            colorFromScore(sectionScore)
          )
      }
  }

  def normalizeReservoir(value: Double, limit: Int) = {
    if (value > limit)
      1
    else
      value / limit
  }

  def calculateScore(route: RouteGreenScore) = {
    val sectionsScore: Seq[SectionScore] = calculateRules(route.rulesConfig)

    val routeScore = registry.route(route.routeId)
      .getOrElse(new RouteScore())

    val plugins = 1 - this.normalizeReservoir(routeScore.pluginsReservoir, route.rulesConfig.thresholds.plugins.poor)
    val producedData = 1 - this.normalizeReservoir(routeScore.dataOutReservoir.getSnapshot.getMean, route.rulesConfig.thresholds.dataOut.poor)
    val producedHeaders = 1 - this.normalizeReservoir(routeScore.headersOutReservoir.getSnapshot.getMean, route.rulesConfig.thresholds.headersOut.poor)

    RouteScore(
      route = route,
      sectionsScore = sectionsScore,
      pluginsInstance = plugins,
      producedData = producedData,
      producedHeaders = producedHeaders,
      architecture = sectionsScore.find(section => section.id == "architecture").get,
      design = sectionsScore.find(section => section.id == "design").get,
      usage = sectionsScore.find(section => section.id == "usage").get,
      log = sectionsScore.find(section => section.id == "log").get,
      informations = SectionScore(
        id = route.routeId,
        score = sectionsScore.foldLeft(0.0)( _ + _.score),
        normalizedScore =  sectionsScore.foldLeft(0.0)( _ + _.normalizedScore),
        letter = letterFromScore(sectionsScore.foldLeft(0.0)( _ + _.score)),
        color = colorFromScore(sectionsScore.foldLeft(0.0)( _ + _.score)),
      )
    )
  }

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
