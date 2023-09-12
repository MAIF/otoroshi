package otoroshi.greenscore

import com.codahale.metrics.UniformReservoir
import otoroshi.env.Env
import otoroshi.greenscore.EcoMetrics.{MAX_GREEN_SCORE_NOTE, colorFromScore, letterFromScore, scoreToColor}
import otoroshi.greenscore.Score.{DynamicScore, RouteScore, SectionScore}
import otoroshi.utils.cache.types.UnboundedTrieMap
import play.api.libs.json._

import java.util.{Timer => _}
import scala.collection.concurrent.TrieMap

class ThresholdsRegistry {
  private val routesScore: UnboundedTrieMap[String, RouteReservoirs] = TrieMap.empty

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
      .getOrElseUpdate(routeId, new RouteReservoirs())
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

  def route(routeId: String): Option[RouteReservoirs] = routesScore.get(routeId)

  def json(routeId: String) = routesScore.get(routeId).map(_.json()).getOrElse(new RouteReservoirs().json())
}

class RouteReservoirs {
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
                          date: Long = 0L,
                          score: Double = 0.0,
                          normalizedScore: Double = 0.0,
                          letter: String = "",
                          color: String = "") {
    def json() = Json.obj(
      "id" -> id,
      "date" -> date,
      "score" -> score,
      "normalized_score" -> normalizedScore,
      "letter" -> letter,
      "color" -> color
    )

    def merge(other: SectionScore): SectionScore = SectionScore(
      id = other.id,
      score = score + other.score,
      normalizedScore = normalizedScore + other.normalizedScore,
      date = other.date,
      letter = other.letter,
      color = other.color
    )

    def merge(other: RouteScoreByDateAndSection): SectionScore = this.merge(other.score)
  }

  case class DynamicScore(plugins: Double = 0.0, producedData: Double = 0.0, producedHeaders: Double = 0.0) {
    def json() = Json.obj(
      "plugins_instance" -> plugins,
      "produced_data" -> producedData,
      "produced_headers" -> producedHeaders
    )

    def merge(other: DynamicScore) = DynamicScore(
      plugins = plugins + other.plugins,
      producedData = producedData + other.producedData,
      producedHeaders = producedHeaders + other.producedHeaders
    )
  }

  case class RouteScore(
                         sectionsScoreByDate: Seq[RouteScoreByDateAndSection],
                         dynamicScores: DynamicScore
                       ) {
    def json(): JsObject = Json.obj(
      "sections_score_by_date" -> sectionsScoreByDate.map(_.json()),
      "dynamic_score" -> dynamicScores.json()
    )
  }
}

case class GroupScore(
                       informations: GreenScoreEntity,
                       scoreByRoute: Seq[RouteScore],
                       sectionsScoreByDate: Seq[RouteScoreByDateAndSection],
                       dynamicScores: DynamicScore
                     ) {
  def json() = Json.obj(
    "informations" -> informations.json,
    "score_by_route" -> scoreByRoute.map(_.json()),
    "sections_score_by_date" -> sectionsScoreByDate.map(_.json()),
    "dynamic_score" -> dynamicScores.json()
  )
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

case class RouteScoreByDateAndSection(date: Long, section: String, score: SectionScore) {
  def json() = Json.obj(
    "date" -> date,
    "section" -> section,
    "score" -> score.json,
//    "rules" -> rules.map(_.json())
  )
}

class EcoMetrics {

  private val registry = new ThresholdsRegistry()

  private def calculateRulesByDate(rules: RulesRouteConfiguration): Seq[RouteScoreByDateAndSection] = {
    rules.states.flatMap(state => RulesManager.rules
        .zipWithIndex
        .foldLeft(Seq.empty[RouteScoreByDateAndSection]) {
          case (acc, (rule, ruleIndex)) =>
            val value = if (state.states.lift(ruleIndex).exists(_.enabled)) {
              MAX_GREEN_SCORE_NOTE * (rule.sectionWeight / 100) * (rule.weight / 100)
            } else {
              0
            }
            acc.find(score => score.date == state.date && score.section == rule.section)
              .map(item => {
                acc.filter(score => score.date != state.date && score.section != rule.section) :+ RouteScoreByDateAndSection(
                  date = state.date,
                  section = rule.section,
//                  rules = item.rules :+ rule,
                  score = SectionScore(
                    id = rule.section,
                    date = state.date,
                    score = item.score.score + value,
                    normalizedScore = item.score.score + value / (rule.sectionWeight / 100 * MAX_GREEN_SCORE_NOTE),
                    letter = letterFromScore(item.score.score + value),
                    color = colorFromScore(item.score.score + value)
                  )
                )
              })
              .getOrElse(acc :+ RouteScoreByDateAndSection(
                date = state.date,
                section = rule.section,
                score = SectionScore(
                  id = rule.section,
                  date = state.date,
                  score = value,
                  normalizedScore = value / (rule.sectionWeight / 100 * MAX_GREEN_SCORE_NOTE),
                  letter = letterFromScore(value),
                  color = colorFromScore(value)
                )
              ))
        })
  }

  private def normalizeReservoir(value: Double, limit: Int) = {
    if (value > limit)
      1
    else
      value / limit
  }

  private def mergeRoutesScoreByDateAndSection(routes: Seq[RouteScoreByDateAndSection]) = {
    routes.foldLeft(Seq.empty[RouteScoreByDateAndSection]) { case (acc, state) =>
      acc.find(score => score.date == state.date && score.section == state.section)
        .map(item => {
          acc.filter(score => score.date != state.date && score.section != state.section) :+ RouteScoreByDateAndSection(
            date = state.date,
            section = state.section,
//            rules = item.rules ++ state.rules,
            score = item.score.merge(state.score)
          )
        })
        .getOrElse(acc :+ state)
    }
  }

  private def mergeRouteDynamicScores(dynamicScore: Seq[DynamicScore]): DynamicScore = {
    val result = dynamicScore
      .foldLeft(DynamicScore()) { case (acc, item) => acc.merge(item) }

    DynamicScore(
      plugins = result.plugins / dynamicScore.length,
      producedData = result.producedData / dynamicScore.length,
      producedHeaders = result.producedHeaders / dynamicScore.length,
    )
  }

  def calculateGroupScore(group: GreenScoreEntity): GroupScore = {
    val scoreByRoute = group.routes.map(route => calculateRouteScore(route))

    val groupScore = mergeRoutesScoreByDateAndSection(scoreByRoute.flatMap(_.sectionsScoreByDate))

    GroupScore(
      informations = group,
      scoreByRoute = scoreByRoute,
      sectionsScoreByDate = groupScore,
      dynamicScores = mergeRouteDynamicScores(scoreByRoute.map(_.dynamicScores))
    )
  }

  def calculateRouteScore(route: RouteRules): RouteScore = {
    val sectionsScoreByDate = calculateRulesByDate(route.rulesConfig)

    val routeScore = registry.route(route.routeId).getOrElse(new RouteReservoirs())

    val plugins = 1 - this.normalizeReservoir(routeScore.pluginsReservoir, route.rulesConfig.thresholds.plugins.poor)
    val producedData = 1 - this.normalizeReservoir(routeScore.dataOutReservoir.getSnapshot.getMean, route.rulesConfig.thresholds.dataOut.poor)
    val producedHeaders = 1 - this.normalizeReservoir(routeScore.headersOutReservoir.getSnapshot.getMean, route.rulesConfig.thresholds.headersOut.poor)

    RouteScore(
      sectionsScoreByDate = sectionsScoreByDate,
      dynamicScores = DynamicScore(
        plugins = plugins,
        producedData = producedData,
        producedHeaders = producedHeaders,
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
