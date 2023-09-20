package otoroshi.greenscore

import com.codahale.metrics.UniformReservoir
import otoroshi.cluster.ClusterQuotaIncr.RouteCallIncr
import otoroshi.greenscore.EcoMetrics.{MAX_GREEN_SCORE_NOTE, colorFromScore, letterFromScore}
import otoroshi.greenscore.Score.{RouteScore, SectionScore}
import otoroshi.utils.cache.types.UnboundedTrieMap
import play.api.libs.json._

import java.util.{Timer => _}
import scala.collection.concurrent.TrieMap

class ThresholdsRegistry {
  private val routesScore: UnboundedTrieMap[String, RouteReservoirs] = TrieMap.empty

  def updateRoute(routeCallIncr: RouteCallIncr) = {
    routesScore
      .getOrElseUpdate(routeCallIncr.routeId, RouteReservoirs())
      .update(routeCallIncr)
  }

  def route(routeId: String): Option[RouteReservoirs] = routesScore.get(routeId)

  def json(routeId: String) = routesScore.get(routeId).map(_.json()).getOrElse(RouteReservoirs().json())
}

case class ScalingRouteReservoirs(overhead: Long = 0,
                                  duration: Long = 0,
                                  backendDuration: Long = 0,
                                  calls: Long = 0,
                                  dataIn: Long = 0,
                                  dataOut: Long = 0,
                                  headersOut: Long = 0,
                                  headersIn: Long = 0) {
  def json() = Json.obj(
    "overhead" -> overhead,
    "duration" -> duration,
    "backendDuration" -> backendDuration,
    "calls" -> calls,
    "dataIn" -> dataIn,
    "dataOut" -> dataOut,
    "headersOut" -> headersOut,
    "headersIn" -> headersIn,
  )

  def merge(other: ScalingRouteReservoirs) = copy(
    overhead = overhead + other.overhead,
    duration = duration + other.duration,
    backendDuration = backendDuration + other.backendDuration,
    calls = calls + other.calls,
    dataIn = dataIn + other.dataIn,
    headersOut = headersOut + other.headersOut,
    headersIn = headersIn + other.headersIn,
    dataOut = dataOut + other.dataOut,
  )

  def mean(length: Int) = copy(
    overhead = overhead / length,
    duration = duration / length,
    backendDuration = backendDuration / length,
    calls = calls / length,
    dataIn = dataIn / length,
    headersOut = headersOut / length,
    headersIn = headersIn / length,
    dataOut = dataOut / length,
  )
}

object ScalingRouteReservoirs {
  private def scalingReservoir(value: Double, limit: Int): Long = {
    if (value > limit)
      1
    else
      (value / limit).toLong
  }

  def from(reservoirs: RouteReservoirs, thresholds: Thresholds) = {
    ScalingRouteReservoirs(
      overhead = 1 - this.scalingReservoir(reservoirs.overhead.getSnapshot.getMean, thresholds.overhead.poor),
      duration = 1 - this.scalingReservoir(reservoirs.duration.getSnapshot.getMean, thresholds.duration.poor),
      backendDuration = 1 - this.scalingReservoir(reservoirs.backendDuration.getSnapshot.getMean, thresholds.backendDuration.poor),
      calls = 1 - this.scalingReservoir(reservoirs.calls.getSnapshot.getMean, thresholds.calls.poor),
      dataIn = 1 - this.scalingReservoir(reservoirs.dataIn.getSnapshot.getMean, thresholds.dataIn.poor),
      dataOut = 1 - this.scalingReservoir(reservoirs.dataOut.getSnapshot.getMean, thresholds.dataOut.poor),
      headersOut = 1 - this.scalingReservoir(reservoirs.headersOut.getSnapshot.getMean, thresholds.headersOut.poor),
      headersIn = 1 - this.scalingReservoir(reservoirs.headersIn.getSnapshot.getMean, thresholds.headersIn.poor)
    )
  }
}

case class RouteReservoirs(
  overhead: UniformReservoir         = new UniformReservoir(),
  duration: UniformReservoir         = new UniformReservoir(),
  backendDuration: UniformReservoir  = new UniformReservoir(),
  calls: UniformReservoir            = new UniformReservoir(),
  dataIn: UniformReservoir           = new UniformReservoir(),
  headersOut: UniformReservoir       = new UniformReservoir(),
  dataOut: UniformReservoir          = new UniformReservoir(),
  headersIn: UniformReservoir        = new UniformReservoir()

) {
  def update(routeCallIncr: RouteCallIncr) = {
    overhead.update(routeCallIncr.overhead.get())
    duration.update(routeCallIncr.duration.get())
    backendDuration.update(routeCallIncr.backendDuration.get())

    dataIn.update(routeCallIncr.dataIn.get())
    dataOut.update(routeCallIncr.dataOut.get())
    headersIn.update(routeCallIncr.headersIn.get())
    headersOut.update(routeCallIncr.headersOut.get())
    calls.update(routeCallIncr.calls.get())
  }

  def json(): JsValue = Json.obj(
    "overhead"               -> overhead.getSnapshot.getMean,
    "duration"                      -> duration.getSnapshot.getMean,
    "backendDuration"               -> backendDuration.getSnapshot.getMean,
    "dataIn"                        -> dataIn.getSnapshot.getMean,
    "dataOut"                       -> dataOut.getSnapshot.getMean,
    "headersIn"                     -> headersIn.getSnapshot.getMean,
    "headersOut"                    -> headersOut.getSnapshot.getMean,
    "calls"                         -> calls.getSnapshot.getMean
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
                          normalizedScore: Double = 0.0) {
    def json() = Json.obj(
      "id" -> id,
      "date" -> date,
      "score" -> score,
      "normalized_score" -> normalizedScore
    )

    def merge(other: SectionScore): SectionScore = SectionScore(
      id = other.id,
      score = score + other.score,
      normalizedScore = normalizedScore + other.normalizedScore,
      date = other.date
    )

    def merge(other: RouteScoreByDateAndSection): SectionScore = this.merge(other.score)
  }

  case class RouteScore(
                         sectionsScoreByDate: Seq[RouteScoreByDateAndSection],
                         dynamicScores: ScalingRouteReservoirs
                       ) {
    def json(): JsObject = Json.obj(
      "sections_score_by_date" -> sectionsScoreByDate.map(_.json()),
      "dynamic_score" -> dynamicScores.json()
    )
  }
}

case class GroupScore(
                       informations: GreenScoreEntity,
                       sectionsScoreByDate: Seq[RouteScoreByDateAndSection],
                       dynamicScores: ScalingRouteReservoirs
                     ) {
  def json() = Json.obj(
    "informations" -> informations.json,
    "sections_score_by_date" -> sectionsScoreByDate.map(_.json()),
    "dynamic_score" -> dynamicScores.json()
  )
}

case class GlobalScore(
                        dynamicScores: ScalingRouteReservoirs,
                        sectionsScoreByDate: Seq[RouteScoreByDateAndSection]
                      ) {
  def json() = Json.obj(
    "sections_score_by_date" -> sectionsScoreByDate.map(_.json()),
    "dynamic_score" -> dynamicScores.json()
  )
}


object EcoMetrics {
  val MAX_GREEN_SCORE_NOTE = 6000

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

case class RouteScoreByDateAndSection(date: Long,
                                      section: String,
                                      sectionWeight: Double,
                                      score: SectionScore,
                                      letter: String = "",
                                      color: String = "") {
  def json() = Json.obj(
    "date" -> date,
    "section" -> section,
    "section_weight" -> sectionWeight,
    "score" -> score.json,
    "letter" -> letter,
    "color" -> color
  )

  def processRoute(): RouteScoreByDateAndSection = {
    copy(
      letter = letterFromScore(score.score),
      color = colorFromScore(score.score),
      score = score.copy(
        normalizedScore = score.score / (sectionWeight / 100 * MAX_GREEN_SCORE_NOTE)
      ))
  }

  def processGroup(length: Int): RouteScoreByDateAndSection = {
    val groupScore = score.score / length
    copy(
      letter = letterFromScore(groupScore),
      color = colorFromScore(groupScore),
      score = score.copy(
        normalizedScore = score.normalizedScore / length
      ))
  }
}

class EcoMetrics {

  private val registry = new ThresholdsRegistry()

  private def calculateRulesByDate(rules: RulesRouteConfiguration): Seq[RouteScoreByDateAndSection] = {
    rules.states.flatMap(state => RulesManager.rules
        .foldLeft(Seq.empty[RouteScoreByDateAndSection]) {
          case (acc, rule) =>
            val value = if (state.states.exists(s => s.id == rule.id && s.enabled)) {
              MAX_GREEN_SCORE_NOTE * (rule.sectionWeight / 100) * (rule.weight / 100)
            } else {
              0
            }
            acc.find(score => score.date == state.date && score.section == rule.section)
              .map(item => {
                acc.filter(score => !(score.date == state.date && score.section == rule.section)) :+ RouteScoreByDateAndSection(
                  date = state.date,
                  section = rule.section,
                  sectionWeight = rule.sectionWeight,
                  score = SectionScore(
                    id = rule.section,
                    date = state.date,
                    score = item.score.score + value,
                  )
                )
              })
              .getOrElse(acc :+ RouteScoreByDateAndSection(
                date = state.date,
                section = rule.section,
                sectionWeight = rule.sectionWeight,
                score = SectionScore(
                  id = rule.section,
                  date = state.date,
                  score = value
                )
              ))
        })
  }

  private def mergeRoutesScoreByDateAndSection(routes: Seq[RouteScoreByDateAndSection]) = {
    routes.foldLeft(Seq.empty[RouteScoreByDateAndSection]) { case (acc, state) =>
      acc.find(score => score.date == state.date && score.section == state.section)
        .map(item => {
          acc.filter(score => !(score.date == state.date && score.section == state.section)) :+ RouteScoreByDateAndSection(
            date = state.date,
            section = state.section,
            score = item.score.merge(state.score),
            sectionWeight = item.sectionWeight
          )
        })
        .getOrElse(acc :+ state)
    }
  }

  private def mergeRouteDynamicScores(dynamicScore: Seq[ScalingRouteReservoirs]): ScalingRouteReservoirs = {
    if (dynamicScore.isEmpty) {
      ScalingRouteReservoirs()
    } else {
      val result = dynamicScore
        .foldLeft(ScalingRouteReservoirs()) { case (acc, item) => acc.merge(item) }

      result.mean(dynamicScore.length)
    }
  }

  def calculateGlobalScore(groups: Seq[GroupScore]): GlobalScore = {
    GlobalScore(
      dynamicScores = mergeRouteDynamicScores(groups.map(_.dynamicScores)),
      sectionsScoreByDate = mergeRoutesScoreByDateAndSection(groups.flatMap(_.sectionsScoreByDate))
    )
  }

  def calculateGroupScore(group: GreenScoreEntity): GroupScore = {
    val scoreByRoute = group.routes.map(route => calculateRouteScore(route))

    val groupScore: Seq[RouteScoreByDateAndSection] = mergeRoutesScoreByDateAndSection(scoreByRoute.flatMap(_.sectionsScoreByDate))

    GroupScore(
      informations = group,
      sectionsScoreByDate = groupScore.map(_.processGroup(group.routes.length)),
      dynamicScores = mergeRouteDynamicScores(scoreByRoute.map(_.dynamicScores))
    )
  }

  def calculateRouteScore(route: RouteRules): RouteScore = {
    val sectionsScoreByDate = calculateRulesByDate(route.rulesConfig)

    val routeScore = registry.route(route.routeId).getOrElse(RouteReservoirs())

    RouteScore(
      sectionsScoreByDate = sectionsScoreByDate.map(section => section.processRoute()),
      dynamicScores = ScalingRouteReservoirs.from(routeScore, route.rulesConfig.thresholds)
    )
  }

  def json(routeId: String): JsValue = registry.json(routeId)

  def updateRoute(routeCallIncr: RouteCallIncr): Unit = {
    registry.updateRoute(routeCallIncr)
  }
}
