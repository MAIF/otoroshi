package otoroshi.greenscore

import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

case class RuleId(value: String)
case class SectionId(value: String)

case class RulesSection(id: SectionId, rules: Seq[Rule]) {
  def json(): JsValue = Json.obj(
    "id"    -> id.value,
    "rules" -> rules.map(_.json())
  )
}

case class TripleBounds(excellent: Int = 0, sufficient: Int = 0, poor: Int = 0)

object TripleBounds {
  def json(obj: TripleBounds) = Json.obj(
    "excellent"  -> obj.excellent,
    "sufficient" -> obj.sufficient,
    "poor"       -> obj.poor
  )

  def reads(item: JsValue): JsResult[TripleBounds] = {
    Try {
      JsSuccess(
        TripleBounds(
          excellent = item.select("excellent").as[Int],
          sufficient = item.select("sufficient").as[Int],
          poor = item.select("poor").as[Int]
        )
      )
    } recover { case e =>
      JsError(e.getMessage)
    } get
  }
}

case class Thresholds(
    plugins: TripleBounds = TripleBounds(excellent = 5, sufficient = 10, poor = 15),
    dataOut: TripleBounds = TripleBounds(excellent = 100, sufficient = 500, poor = 1000),
    headersOut: TripleBounds = TripleBounds(excellent = 10, sufficient = 30, poor = 50)
)

object Thresholds {
  def json(obj: Thresholds) = Json.obj(
    "plugins"    -> TripleBounds.json(obj.plugins),
    "dataOut"    -> TripleBounds.json(obj.dataOut),
    "headersOut" -> TripleBounds.json(obj.headersOut)
  )

  def reads(item: JsValue): JsResult[Thresholds] = {
    Try {
      JsSuccess(
        Thresholds(
          plugins = item.select("plugins").as[TripleBounds](TripleBounds.reads),
          dataOut = item.select("dataOut").as[TripleBounds](TripleBounds.reads),
          headersOut = item.select("headersOut").as[TripleBounds](TripleBounds.reads)
        )
      )
    } recover { case e =>
      JsError(e.getMessage)
    } get
  }
}

object RulesSection {
  def reads(json: JsValue): JsResult[Seq[RulesSection]] = {
    Try {
      JsSuccess(
        json
          .as[JsArray]
          .value
          .map(item =>
            RulesSection(
              id = SectionId(item.select("id").as[String]),
              rules = item.select("rules").as[Seq[Rule]](Rule.reads)
            )
          )
      )
    } recover { case e =>
      JsError(e.getMessage)
    } get
  }
}

case class Rule(
    id: RuleId,
    description: Option[String] = None,
    advice: Option[String] = None,
    weight: Double,
    sectionWeight: Double,
    enabled: Boolean = true
) {
  def json(): JsValue = Json.obj(
    "id"             -> id.value,
    "description"    -> description,
    "advice"         -> advice,
    "weight"         -> weight,
    "section_weight" -> sectionWeight,
    "enabled"        -> enabled
  )
}

object Rule {
  def reads(json: JsValue): JsResult[Seq[Rule]] = {
    Try {
      JsSuccess(
        json
          .as[JsArray]
          .value
          .map(item =>
            Rule(
              id = RuleId(item.select("id").as[String]),
              description = item.select("description").asOpt[String],
              advice = item.select("advice").asOpt[String],
              weight = item.select("weight").as[Double],
              sectionWeight = item.select("section_weight").as[Double],
              enabled = item.select("enabled").as[Boolean]
            )
          )
      )
    } recover { case e =>
      JsError(e.getMessage)
    } get
  }
}

object RulesManager {
  val sections = Seq(
    RulesSection(
      SectionId("architecture"),
      Seq(
        Rule(
          RuleId("AR01"),
          weight = 25,
          sectionWeight = 25,
          description =
            "Use Event Driven Architecture to avoid polling madness and inform subscribers of an update.".some,
          advice = "Use Event Driven Architecture to avoid polling madness.".some
        ),
        Rule(
          RuleId("AR02"),
          weight = 25,
          sectionWeight = 25,
          description = "API runtime close to the Consumer.".some,
          advice = "Deploy the API near the consumer".some
        ),
        Rule(
          RuleId("AR03"),
          weight = 25,
          sectionWeight = 25,
          description = "Ensure the same API does not exist *.".some,
          advice = "Ensure only one API fit the same need".some
        ),
        Rule(
          RuleId("AR04"),
          weight = 25,
          sectionWeight = 25,
          description = "Use scalable infrastructure to avoid over-provisioning.".some,
          advice = "Use scalable infrastructure to avoid over-provisioning".some
        )
      )
    ),
    RulesSection(
      SectionId("design"),
      Seq(
        Rule(
          RuleId("DE01"),
          weight = 25,
          sectionWeight = 40,
          description = "Choose an exchange format with the smallest size (JSON is smallest than XML).".some,
          advice = "Prefer an exchange format with the smallest size (JSON is smaller than XML).".some
        ),
        Rule(
          RuleId("DE02"),
          weight = 15,
          sectionWeight = 40,
          description = "new API --> cache usage.".some,
          advice = "Use cache to avoid useless requests and preserve compute resources.".some
        ),
        Rule(
          RuleId("DE03"),
          weight = 20,
          sectionWeight = 40,
          description = "Existing API --> cache usage efficiency.".some,
          advice = "Use the cache efficiently to avoid useless resources consumtion.".some
        ),
        Rule(
          RuleId("DE04"),
          weight = 2,
          sectionWeight = 40,
          description = "Opaque token usage.".some,
          advice = "Prefer opaque token usage prior to JWT".some
        ),
        Rule(
          RuleId("DE05"),
          weight = 4,
          sectionWeight = 40,
          description = "Align the cache refresh with the datasource **.".some,
          advice = "Align cache refresh strategy with the data source ".some
        ),
        Rule(
          RuleId("DE06"),
          weight = 4,
          sectionWeight = 40,
          description = "Allow part refresh of cache.".some,
          advice = "Allow a part cache refresh".some
        ),
        Rule(
          RuleId("DE07"),
          weight = 10,
          sectionWeight = 40,
          description = "Is System,  Business or cx API ?.".some,
          advice = "Use Business & Cx APIs closer to the business need".some
        ),
        Rule(
          RuleId("DE08"),
          weight = 2.5,
          sectionWeight = 40,
          description = "Possibility to filter results.".some,
          advice = "Implement filtering mechanism to limit the payload size".some
        ),
        Rule(
          RuleId("DE09"),
          weight = 10,
          sectionWeight = 40,
          description = "Leverage OData or GraphQL for your databases APIs.".some,
          advice = "Leverage OData or GraphQL when relevant".some
        ),
        Rule(
          RuleId("DE10"),
          weight = 5,
          sectionWeight = 40,
          description = "Redundant data information in the same API.".some,
          advice = "Avoid redundant data information in the same API".some
        ),
        Rule(
          RuleId("DE11"),
          weight = 2.5,
          sectionWeight = 40,
          description = "Possibility to fitler pagination results.".some,
          advice = "Implement pagination mechanism to limit the payload size".some
        )
      )
    ),
    RulesSection(
      SectionId("usage"),
      Seq(
        Rule(
          RuleId("US01"),
          weight = 5,
          sectionWeight = 25,
          description = "Use query parameters for GET Methods.".some,
          advice =
            "Implement filters to limit which data are returned by the API (send just the data the consumer need).".some
        ),
        Rule(
          RuleId("US02"),
          weight = 10,
          sectionWeight = 25,
          description = "Decomission end of life or not used APIs.".some,
          advice = "Decomission end of life or not used APIs".some
        ),
        Rule(
          RuleId("US03"),
          weight = 10,
          sectionWeight = 25,
          description = "Number of API version <=2 .".some,
          advice = "Compute resources saved & Network impact reduced".some
        ),
        Rule(
          RuleId("US04"),
          weight = 10,
          sectionWeight = 25,
          description = "Usage of Pagination of results available.".some,
          advice = "Optimize queries to limit the information returned to what is strictly necessary.".some
        ),
        Rule(
          RuleId("US05"),
          weight = 20,
          sectionWeight = 25,
          description =
            "Choosing relevant data representation (user don’t need to do multiple calls) is Cx API ?.".some,
          advice =
            "Choose the correct API based on use case to avoid requests on multiple systems or large number of requests. Refer to the data catalog to validate the data source.".some
        ),
        Rule(
          RuleId("US06"),
          weight = 25,
          sectionWeight = 25,
          description = "Number of Consumers.".some,
          advice =
            "Deploy an API well designed and documented to increase the reuse rate. Rate based on number of different consumers".some
        ),
        Rule(
          RuleId("US07"),
          weight = 20,
          sectionWeight = 25,
          description = "Error rate.".some,
          advice = "Monitor and decrease the error rate to avoid over processing".some
        )
      )
    ),
    RulesSection(
      SectionId("log"),
      Seq(
        Rule(
          RuleId("LO01"),
          weight = 100,
          sectionWeight = 10,
          description = "Logs retention.".some,
          advice = "Align log retention period to the business need (ops and Legal)".some
        )
      )
    )
  )
}

case class GreenScoreConfig(sections: Seq[RulesSection], thresholds: Thresholds = Thresholds()) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "sections"   -> sections.map(section => {
      Json.obj(
        "id"    -> section.id.value,
        "rules" -> section.rules.map(_.json())
      )
    }),
    "thresholds" -> Thresholds.json(thresholds)
  )
}

object GreenScoreConfig {
  def readFrom(lookup: JsLookupResult): GreenScoreConfig = {
    lookup match {
      case JsDefined(value) => format.reads(value).getOrElse(GreenScoreConfig(sections = RulesManager.sections))
      case _: JsUndefined   => GreenScoreConfig(sections = RulesManager.sections)
    }
  }

  val format = new Format[GreenScoreConfig] {
    override def reads(json: JsValue): JsResult[GreenScoreConfig] = Try {
      GreenScoreConfig(
        sections = json.select("sections").as[Seq[RulesSection]](RulesSection.reads),
        thresholds = json.select("thresholds").as[Thresholds](Thresholds.reads)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: GreenScoreConfig): JsValue             = o.json
  }
}
