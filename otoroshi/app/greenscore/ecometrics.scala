package otoroshi.greenscore

import com.codahale.metrics.UniformReservoir
import otoroshi.cluster.ClusterLeaderUpdateMessage.RouteCallIncr
import otoroshi.greenscore.EcoMetrics.{MAX_GREEN_SCORE_NOTE, colorFromScore, letterFromScore}
import otoroshi.greenscore.Score.SectionScore
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.BetterSyntax
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

case class DynamicTripleBounds(
                                overhead: TripleBounds = TripleBounds(),
                                duration: TripleBounds = TripleBounds(),
                                backendDuration: TripleBounds = TripleBounds(),
                                calls: TripleBounds = TripleBounds(),
                                dataIn: TripleBounds = TripleBounds(),
                                dataOut: TripleBounds = TripleBounds(),
                                headersOut: TripleBounds = TripleBounds(),
                                headersIn: TripleBounds = TripleBounds()
                              ) {

  def json() = Json.obj(
    "overhead" -> overhead.json(),
    "duration" -> duration.json(),
    "backendDuration" -> backendDuration.json(),
    "calls" -> calls.json(),
    "dataIn" -> dataIn.json(),
    "dataOut" -> dataOut.json(),
    "headersOut" -> headersOut.json(),
    "headersIn" -> headersIn.json(),
  )

  def +(other: DynamicTripleBounds) = copy(
    overhead = overhead + other.overhead,
    duration = duration + other.duration,
    backendDuration = backendDuration + other.backendDuration,
    calls = calls + other.calls,
    dataIn = dataIn + other.dataIn,
    headersOut = headersOut + other.headersOut,
    headersIn = headersIn + other.headersIn,
    dataOut = dataOut + other.dataOut,
  )

  def from(reservoirs: RouteReservoirs, thresholds: Thresholds) = {
    DynamicTripleBounds(
      overhead.incr(reservoirs.overhead.getSnapshot.getMean.toInt, thresholds.overhead),
      duration.incr(reservoirs.duration.getSnapshot.getMean.toInt, thresholds.duration),
      backendDuration.incr( reservoirs.backendDuration.getSnapshot.getMean.toInt, thresholds.backendDuration),
      calls.incr(reservoirs.calls.getSnapshot.getMean.toInt, thresholds.calls),
      dataIn.incr(reservoirs.dataIn.getSnapshot.getMean.toInt, thresholds.dataIn),
      dataOut.incr(reservoirs.dataOut.getSnapshot.getMean.toInt, thresholds.dataOut),
      headersOut.incr(reservoirs.headersOut.getSnapshot.getMean.toInt, thresholds.headersOut),
      headersIn.incr(reservoirs.headersIn.getSnapshot.getMean.toInt, thresholds.headersIn)
    )
  }
}

case class ScalingRouteReservoirs(overhead: Float = 0,
                                  duration: Float = 0,
                                  backendDuration: Float = 0,
                                  calls: Float = 0,
                                  dataIn: Float = 0,
                                  dataOut: Float = 0,
                                  headersOut: Float = 0,
                                  headersIn: Float = 0) {
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
  private def scalingReservoir(value: Double, limit: Int): Float = {
    if (value > limit)
      1
    else
      (value / limit).toFloat
  }

  def from(reservoirs: RouteReservoirs) = {
    ScalingRouteReservoirs(
      reservoirs.overhead.getSnapshot.getMean.toLong,
      reservoirs.duration.getSnapshot.getMean.toLong,
      reservoirs.backendDuration.getSnapshot.getMean.toLong,
      reservoirs.calls.getSnapshot.getMean.toLong,
      reservoirs.dataIn.getSnapshot.getMean.toLong,
      reservoirs.dataOut.getSnapshot.getMean.toLong,
      reservoirs.headersOut.getSnapshot.getMean.toLong,
      reservoirs.headersIn.getSnapshot.getMean.toLong,
    )
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

  def avgDouble(value: Double): Double = {
    (if (value == Double.NaN || value == Double.NegativeInfinity || value == Double.PositiveInfinity) {
      0.0
    } else {
      value
    }).applyOn {
      case Double.NaN => 0.0
      case Double.NegativeInfinity => 0.0
      case Double.PositiveInfinity => 0.0
      case v if v.toString == "NaN" => 0.0
      case v if v.toString == "Infinity" => 0.0
      case v => v
    }
  }

  case class SectionScore(id: String = "",
                          date: Long = 0L,
                          score: Double = 0.0,
                          scalingScore: Double = 0.0) {
    def json() = {
      Json.obj(
        "id" -> id,
        "date" -> date,
        "score" -> score,
        "scaling_score" -> avgDouble(scalingScore)
      )
    }

    def merge(other: SectionScore): SectionScore = SectionScore(
      id = other.id,
      score = score + other.score,
      scalingScore = scalingScore + other.scalingScore,
      date = other.date
    )

    def merge(other: RouteScoreByDateAndSection): SectionScore = this.merge(other.score)
  }
}

case class RouteScore(
                       sectionsScoreByDate: Seq[RouteScoreByDateAndSection],
                       dynamicValues: Dynamicvalues
                     ) {
  def json(): JsObject = Json.obj(
    "sections_score_by_date" -> sectionsScoreByDate.map(_.json()),
    "dynamic_values" -> dynamicValues.json()
  )
}

case class Dynamicvalues(scaling: ScalingRouteReservoirs = ScalingRouteReservoirs(),
                         raw: ScalingRouteReservoirs = ScalingRouteReservoirs(),
                         counters: DynamicTripleBounds = DynamicTripleBounds()) {
  def json() = Json.obj(
    "scaling" -> scaling.json(),
    "raw" -> raw.json(),
    "counters" -> counters.json()
  )

  def merge(other: Dynamicvalues) = copy(
    scaling = scaling.merge(other.scaling),
    raw = raw.merge(other.raw),
    counters = counters + other.counters
  )

  def mean(length: Int) = copy(
    scaling = scaling.mean(length),
    raw = raw.mean(length)
  )
}

case class GroupScore(
                       informations: GreenScoreEntity,
                       sectionsScoreByDate: Seq[RouteScoreByDateAndSection],
                       dynamicValues: Dynamicvalues
                     ) {
  def json() = Json.obj(
    "informations" -> informations.json,
    "sections_score_by_date" -> sectionsScoreByDate.map(_.json()),
    "dynamic_values" -> dynamicValues.json()
  )
}

case class GlobalScore(
                        dynamicValues: Dynamicvalues,
                        sectionsScoreByDate: Seq[RouteScoreByDateAndSection]
                      ) {
  def json() = Json.obj(
    "sections_score_by_date" -> sectionsScoreByDate.map(_.json()),
    "dynamic_values" -> dynamicValues.json()
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
                                      length: Int = 1,
                                      section: String = "",
                                      sectionWeight: Double = 0.0,
                                      score: SectionScore = SectionScore(),
                                      letter: String = "",
                                      color: String = "") {
  def json() = Json.obj(
    "date" -> date,
    "length" -> length,
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
        scalingScore = if(score.score == 0)  0 else score.score / ((sectionWeight / 100) * MAX_GREEN_SCORE_NOTE)
      ))
  }

  def processGroup(length: Int): RouteScoreByDateAndSection = {
    val groupScore = score.score / length
    copy(
      letter = letterFromScore(groupScore),
      color = colorFromScore(groupScore),
      score = score.copy(
        score = score.score / length,
        scalingScore = score.scalingScore / length
      ))
  }
}

class EcoMetrics {

  private val registry = new ThresholdsRegistry()

  private def calculateRulesByDate(rules: RulesRouteConfiguration): Seq[RouteScoreByDateAndSection] = {

    val enrichedRules = rules.states
      .sortBy(_.date)(Ordering.Long)
      .zipWithIndex
      .foldLeft(Seq.empty[RuleStateRecord]) {
        case(acc, (item, i)) =>
          acc :+ item.copy(
          states = rules.states.slice(0, i+1).flatMap(_.states)
            .foldLeft(RulesManager.rules.map(r => RuleState(r.id, enabled = false))) {
            case (acc, i) => acc.map(p => if(p.id == i.id) i else p)
          })
      }

    enrichedRules
      .foldLeft(Seq.empty[RouteScoreByDateAndSection]) { case (acc, state) =>
        acc ++ state.states
          .filter(_.enabled)
          .foldLeft(Seq.empty[RouteScoreByDateAndSection]) {
            case (acc, rule) =>
              val ruleWeight = RulesManager.rules.find(r => r.id == rule.id).get
              val value = MAX_GREEN_SCORE_NOTE * (ruleWeight.sectionWeight / 100) * (ruleWeight.weight / 100)

              acc.find(score => score.section == ruleWeight.section) match {
                case None => acc :+ RouteScoreByDateAndSection(
                  date = state.date,
                  section = ruleWeight.section,
                  sectionWeight = ruleWeight.sectionWeight,
                  score = SectionScore(
                    id = ruleWeight.section,
                    date = state.date,
                    score = value,
                  ))
                case Some(item) => acc.filter(score => !(score.section == ruleWeight.section)) :+ RouteScoreByDateAndSection(
                  date = state.date,
                  section = ruleWeight.section,
                  sectionWeight = ruleWeight.sectionWeight,
                  score = SectionScore(
                    id = ruleWeight.section,
                    date = state.date,
                    score = item.score.score + value
                  )
                )
              }
          }
      }
      .map(_.processRoute())
  }

  private def mergeRoutesScoreByDateAndSection(routes: Seq[RouteScoreByDateAndSection]) = {
    routes.foldLeft(Seq.empty[RouteScoreByDateAndSection]) { case (acc, state) =>
      acc.find(score => score.date == state.date && score.section == state.section)
        .map(item => {
          acc.filter(score => !(score.date == state.date && score.section == state.section)) :+ RouteScoreByDateAndSection(
            date = state.date,
            section = state.section,
            score = item.score.merge(state.score),
            sectionWeight = item.sectionWeight,
            length = item.length + 1
          )
        })
        .getOrElse(acc :+ state)
    }
  }

  private def mergeRouteDynamicValues(dynamicScore: Seq[Dynamicvalues]): Dynamicvalues = {
    if (dynamicScore.isEmpty) {
      Dynamicvalues()
    } else {
      val result = dynamicScore
        .foldLeft(Dynamicvalues()) { case (acc, item) => acc.merge(item) }

      result.mean(dynamicScore.length)
    }
  }

  def calculateGlobalScore(groups: Seq[GroupScore]): GlobalScore = {
    GlobalScore(
      dynamicValues = mergeRouteDynamicValues(groups.map(_.dynamicValues)),
      sectionsScoreByDate = mergeRoutesScoreByDateAndSection(groups.flatMap(_.sectionsScoreByDate))
    )
  }

  def calculateGroupScore(group: GreenScoreEntity): GroupScore = {
    val scoreByRoute = group.routes.map(route => calculateRouteScore(route, group.thresholds))

    val groupScore: Seq[RouteScoreByDateAndSection] = mergeRoutesScoreByDateAndSection(scoreByRoute.flatMap(_.sectionsScoreByDate))

    GroupScore(
      informations = group,
      sectionsScoreByDate = groupScore.map(_.processGroup(group.routes.length)),
      dynamicValues = mergeRouteDynamicValues(scoreByRoute.map(_.dynamicValues))
    )
  }

  def calculateRouteScore(route: RouteRules, thresholds: Thresholds): RouteScore = {
    val sectionsScoreByDate = calculateRulesByDate(route.rulesConfig)

    val routeScore = registry.route(route.routeId).getOrElse(RouteReservoirs())

    RouteScore(
      sectionsScoreByDate = sectionsScoreByDate,
      dynamicValues = Dynamicvalues(
        scaling = ScalingRouteReservoirs.from(routeScore, thresholds),
        raw = ScalingRouteReservoirs.from(routeScore),
        counters = DynamicTripleBounds().from(routeScore, thresholds)
      )
    )
  }

  def json(routeId: String): JsValue = registry.json(routeId)

  def updateRoute(routeCallIncr: RouteCallIncr): Unit = {
    registry.updateRoute(routeCallIncr)
  }
}
