package otoroshi.greenscore

import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import scala.util.{Failure, Success, Try}

case class RuleId(value: String)

case class TripleBounds(excellent: Int = 0, sufficient: Int = 0, poor: Int = 0) {
  def +(b: TripleBounds) = copy(
    excellent = excellent + b.excellent, sufficient = sufficient + b.sufficient, poor = poor + b.poor
  )

  def incr(value: Int, thresholds: TripleBounds) = {
    if(value <= thresholds.excellent)
      copy(excellent = excellent + 1)
    else if(value <= thresholds.sufficient)
      copy(sufficient = sufficient + 1)
    else
      copy(poor = poor + 1)
  }

  def json() = Json.obj(
    "excellent" -> excellent,
    "sufficient" -> sufficient,
    "poor" -> poor
  )
}

object TripleBounds {
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
                       overhead: TripleBounds = TripleBounds(excellent = 5, sufficient = 10, poor = 15),
                       duration: TripleBounds = TripleBounds(excellent = 5, sufficient = 10, poor = 15),
                       backendDuration: TripleBounds = TripleBounds(excellent = 5, sufficient = 10, poor = 15),
                       calls: TripleBounds = TripleBounds(excellent = 5, sufficient = 10, poor = 15),
                       dataIn: TripleBounds = TripleBounds(excellent = 100, sufficient = 500, poor = 1000),
                       dataOut: TripleBounds = TripleBounds(excellent = 100, sufficient = 500, poor = 1000),
                       headersOut: TripleBounds = TripleBounds(excellent = 10, sufficient = 30, poor = 50),
                       headersIn: TripleBounds = TripleBounds(excellent = 10, sufficient = 30, poor = 50)
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
}

object Thresholds {
  def reads(item: JsValue): JsResult[Thresholds] = {
    Try {
      JsSuccess(
        Thresholds(
          calls = item.select("calls").as[TripleBounds](TripleBounds.reads),
          dataIn = item.select("dataIn").as[TripleBounds](TripleBounds.reads),
          dataOut = item.select("dataOut").as[TripleBounds](TripleBounds.reads),
          overhead = item.select("overhead").as[TripleBounds](TripleBounds.reads),
          duration = item.select("duration").as[TripleBounds](TripleBounds.reads),
          backendDuration = item.select("backendDuration").as[TripleBounds](TripleBounds.reads),
          headersIn = item.select("headersIn").as[TripleBounds](TripleBounds.reads),
          headersOut = item.select("headersOut").as[TripleBounds](TripleBounds.reads)
        )
      )
    } recover { case e =>
      JsError(e.getMessage)
    } get
  }
}

case class Rule(
    id: RuleId,
    section: String,
    description: Option[String] = None,
    advice: Option[String] = None,
    weight: Double,
    sectionWeight: Double
) {
  def json(): JsValue = Json.obj(
    "id"             -> id.value,
    "section"        -> section,
    "description"    -> description,
    "advice"         -> advice,
    "weight"         -> weight,
    "section_weight" -> sectionWeight
  )
}

case class RuleState(
                      id: RuleId,
                      enabled: Boolean = true
                    ) {
  def json(): JsValue = Json.obj(
    "id"      -> id.value,
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
              section = item.select("section").as[String],
              description = item.select("description").asOpt[String],
              advice = item.select("advice").asOpt[String],
              weight = item.select("weight").as[Double],
              sectionWeight = item.select("section_weight").as[Double]
            )
          )
      )
    } recover { case e =>
      JsError(e.getMessage)
    } get
  }
}

case class RuleStateRecord(date: Long, states: Seq[RuleState])

object RuleStateRecord {
  val format = new Format[RuleStateRecord] {
    override def reads(json: JsValue): JsResult[RuleStateRecord] = Try {
      RuleStateRecord(
        date = (json \ "date").as[Long],
        states = json.select("states").asOpt[Seq[RuleState]](RuleState.reads).getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }

    override def writes(o: RuleStateRecord): JsValue = Json.obj(
      "date" -> o.date,
      "states" -> JsArray(o.states.map(_.json()))
    )
  }
}

object RuleState {
  def reads(json: JsValue): JsResult[Seq[RuleState]] = {
    Try {
      JsSuccess(
        json
          .as[JsArray]
          .value
          .map(item =>
            RuleState(
              id = RuleId(item.select("id").as[String]),
              enabled = item.select("enabled").asOpt[Boolean].getOrElse(true)
            )
          )
      )
    } recover { case e =>
      JsError(e.getMessage)
    } get
  }
}

object RulesManager {
  val rules = Seq(
      Rule(
        RuleId("AR01"),
        section = "architecture",
        weight = 25,
        sectionWeight = 25,
        description =
          "Use Event Driven Architecture to avoid polling madness and inform subscribers of an update.".some,
        advice = "Use Event Driven Architecture to avoid polling madness.".some
      ),
      Rule(
        RuleId("AR02"),
        section = "architecture",
        weight = 25,
        sectionWeight = 25,
        description = "API runtime close to the Consumer.".some,
        advice = "Deploy the API near the consumer".some
      ),
      Rule(
        RuleId("AR03"),
        section = "architecture",
        weight = 25,
        sectionWeight = 25,
        description = "Ensure the same API does not exist *.".some,
        advice = "Ensure only one API fit the same need".some
      ),
      Rule(
        RuleId("AR04"),
        section = "architecture",
        weight = 25,
        sectionWeight = 25,
        description = "Use scalable infrastructure to avoid over-provisioning.".some,
        advice = "Use scalable infrastructure to avoid over-provisioning".some
      ),
      Rule(
        RuleId("DE01"),
        section = "design",
        weight = 25,
        sectionWeight = 40,
        description = "Choose an exchange format with the smallest size (JSON is smallest than XML).".some,
        advice = "Prefer an exchange format with the smallest size (JSON is smaller than XML).".some
      ),
      Rule(
        RuleId("DE02"),
        section = "design",
        weight = 15,
        sectionWeight = 40,
        description = "new API --> cache usage.".some,
        advice = "Use cache to avoid useless requests and preserve compute resources.".some
      ),
      Rule(
        RuleId("DE03"),
        section = "design",
        weight = 20,
        sectionWeight = 40,
        description = "Existing API --> cache usage efficiency.".some,
        advice = "Use the cache efficiently to avoid useless resources consumtion.".some
      ),
      Rule(
        RuleId("DE04"),
        section = "design",
        weight = 2,
        sectionWeight = 40,
        description = "Opaque token usage.".some,
        advice = "Prefer opaque token usage prior to JWT".some
      ),
      Rule(
        RuleId("DE05"),
        section = "design",
        weight = 4,
        sectionWeight = 40,
        description = "Align the cache refresh with the datasource **.".some,
        advice = "Align cache refresh strategy with the data source ".some
      ),
      Rule(
        RuleId("DE06"),
        section = "design",
        weight = 4,
        sectionWeight = 40,
        description = "Allow part refresh of cache.".some,
        advice = "Allow a part cache refresh".some
      ),
      Rule(
        RuleId("DE07"),
        section = "design",
        weight = 10,
        sectionWeight = 40,
        description = "Is System,  Business or cx API ?.".some,
        advice = "Use Business & Cx APIs closer to the business need".some
      ),
      Rule(
        RuleId("DE08"),
        section = "design",
        weight = 2.5,
        sectionWeight = 40,
        description = "Possibility to filter results.".some,
        advice = "Implement filtering mechanism to limit the payload size".some
      ),
      Rule(
        RuleId("DE09"),
        section = "design",
        weight = 10,
        sectionWeight = 40,
        description = "Leverage OData or GraphQL for your databases APIs.".some,
        advice = "Leverage OData or GraphQL when relevant".some
      ),
      Rule(
        RuleId("DE10"),
        section = "design",
        weight = 5,
        sectionWeight = 40,
        description = "Redundant data information in the same API.".some,
        advice = "Avoid redundant data information in the same API".some
      ),
      Rule(
        RuleId("DE11"),
        section = "design",
        weight = 2.5,
        sectionWeight = 40,
        description = "Possibility to fitler pagination results.".some,
        advice = "Implement pagination mechanism to limit the payload size".some
      ),
      Rule(
        RuleId("US01"),
        section = "usage",
        weight = 5,
        sectionWeight = 25,
        description = "Use query parameters for GET Methods.".some,
        advice =
          "Implement filters to limit which data are returned by the API (send just the data the consumer need).".some
      ),
      Rule(
        RuleId("US02"),
        section = "usage",
        weight = 10,
        sectionWeight = 25,
        description = "Decomission end of life or not used APIs.".some,
        advice = "Decomission end of life or not used APIs".some
      ),
      Rule(
        RuleId("US03"),
        section = "usage",
        weight = 10,
        sectionWeight = 25,
        description = "Number of API version <=2 .".some,
        advice = "Compute resources saved & Network impact reduced".some
      ),
      Rule(
        RuleId("US04"),
        section = "usage",
        weight = 10,
        sectionWeight = 25,
        description = "Usage of Pagination of results available.".some,
        advice = "Optimize queries to limit the information returned to what is strictly necessary.".some
      ),
      Rule(
        RuleId("US05"),
        section = "usage",
        weight = 20,
        sectionWeight = 25,
        description =
          "Choosing relevant data representation (user don’t need to do multiple calls) is Cx API ?.".some,
        advice =
          "Choose the correct API based on use case to avoid requests on multiple systems or large number of requests. Refer to the data catalog to validate the data source.".some
      ),
      Rule(
        RuleId("US06"),
        section = "usage",
        weight = 25,
        sectionWeight = 25,
        description = "Number of Consumers.".some,
        advice =
          "Deploy an API well designed and documented to increase the reuse rate. Rate based on number of different consumers".some
      ),
      Rule(
        RuleId("US07"),
        section = "usage",
        weight = 20,
        sectionWeight = 25,
        description = "Error rate.".some,
        advice = "Monitor and decrease the error rate to avoid over processing".some
      ),
      Rule(
        RuleId("LO01"),
        section = "log",
        weight = 100,
        sectionWeight = 10,
        description = "Logs retention.".some,
        advice = "Align log retention period to the business need (ops and Legal)".some
      )
  )
}

case class RulesRouteConfiguration(states: Seq[RuleStateRecord] = Seq.empty) extends NgPluginConfig {
  def json: JsValue = {
    Json.obj(
      "states" -> JsArray(states.map(RuleStateRecord.format.writes))
    )
  }
}

object RulesRouteConfiguration {
  def readFrom(lookup: JsLookupResult): RulesRouteConfiguration = {
    lookup match {
      case JsDefined(value) => format.reads(value)
        .getOrElse(RulesRouteConfiguration())
      case _: JsUndefined   => RulesRouteConfiguration()
    }
  }

  val format = new Format[RulesRouteConfiguration] {
    override def reads(json: JsValue): JsResult[RulesRouteConfiguration] = Try {
      RulesRouteConfiguration(
        states = (json \ "states")
          .asOpt[Seq[RuleStateRecord]](Reads.seq(RuleStateRecord.format.reads))
          .getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: RulesRouteConfiguration): JsValue             = o.json
  }
}