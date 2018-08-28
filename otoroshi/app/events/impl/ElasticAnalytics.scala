package events.impl
import java.util.Base64

import akka.http.scaladsl.util.FastFuture
import env.Env
import events.AnalyticsService
import models.ElasticAnalyticsConfig
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.Logger
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}

class ElasticAnalytics(config: ElasticAnalyticsConfig, client: WSClient) extends AnalyticsService {

  private val index: String = config.index.getOrElse("otoroshi-events")
  private val searchUri   = s"${config.clusterUri}/$index*/_search"

  lazy val logger = Logger("otoroshi-analytics-api")

  override def fetchHits(service: Option[String],
                         from: Option[DateTime],
                         to: Option[DateTime])(
                          implicit env: Env,
                          ec: ExecutionContext
                        ): Future[Option[JsValue]] =
    query(Json.obj(
      "size" -> 0,
      "query" -> Json.obj(
        "bool" -> Json.obj(
          "must" -> filters(service, from, to)
        )
      )
    ))
      .map { resp =>
        Json.obj(
          "count" -> (resp \ "hits" \ "total").asOpt[Int]
        )
      }
      .map(Some.apply)



  override def events(eventType: String,
                      service: Option[String],
                      from: Option[DateTime],
                      to: Option[DateTime],
                      page: Int,
                      size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] = ???


  override def fetchDataIn(service: Option[String],
                           from: Option[DateTime],
                           to: Option[DateTime])(
                            implicit env: Env,
                            ec: ExecutionContext
                          ): Future[Option[JsValue]] =
    sum("data.dataIn", service, from, to).map(Some.apply)

  override def fetchDataOut(service: Option[String],
                            from: Option[DateTime],
                            to: Option[DateTime])(
                             implicit env: Env,
                             ec: ExecutionContext
                           ): Future[Option[JsValue]] =
    sum("data.dataOut", service, from, to).map(Some.apply)

  override def fetchAvgDuration(service: Option[String],
                                from: Option[DateTime],
                                to: Option[DateTime])(
                                 implicit env: Env,
                                 ec: ExecutionContext
                               ): Future[Option[JsValue]] =
    avg("duration", service, from, to).map(Some.apply)

  override def fetchAvgOverhead(service: Option[String],
                                from: Option[DateTime],
                                to: Option[DateTime])(
                                 implicit env: Env,
                                 ec: ExecutionContext
                               ): Future[Option[JsValue]] =
    avg("overhead", service, from, to).map(Some.apply)

  override def fetchStatusesPiechart(service: Option[String],
                                     from: Option[DateTime],
                                     to: Option[DateTime])(
                                      implicit env: Env,
                                      ec: ExecutionContext
                                    ): Future[Option[JsValue]] =
    piechart("status", service, from, to).map(Some.apply)

  override def fetchStatusesHistogram(service: Option[String],
                                      from: Option[DateTime],
                                      to: Option[DateTime])(
                                       implicit env: Env,
                                       ec: ExecutionContext
                                     ): Future[Option[JsValue]] =
    query(Json.obj(
      "size" -> 0,
      "query" -> Json.obj {
        "bool" -> Json.obj {
          "must" -> filters(service, from, to)
        }
      },
      "aggs" -> Json.obj (
        "codes" -> Json.obj (
          "aggs" -> Json.obj (
            "codesOverTime" -> Json.obj (
              "date_histogram" -> Json.obj (
                "interval" -> "hour",
                "field" -> "@timestamp"
              )
            )
          ),
          "range" -> Json.obj (
            "ranges" -> Json.arr (
              Json.obj("from" -> 100, "to" -> 199, "key" -> "1**"),
              Json.obj("from" -> 200, "to" -> 299, "key" -> "2**"),
              Json.obj("from" -> 300, "to" -> 399, "key" -> "3**"),
              Json.obj("from" -> 400, "to" -> 499, "key" -> "4**"),
              Json.obj("from" -> 500, "to" -> 599, "key" -> "5**")
            ),
            "field" -> "status",
            "keyed" -> true
          )
        )
      )
    )).map { res =>
      val buckets: JsObject = (res \ "aggregations" \ "codes" \ "buckets").asOpt[JsObject].getOrElse(Json.obj())

      val series = buckets.value.filter {
        case (_, v) => (v \ "codesOverTime" \ "buckets").asOpt[JsArray].exists(_.value.nonEmpty)
      }.map {
        case (k, v) =>
          Json.obj(
            "name"  -> k,
            "count" -> (v \ "doc_count").asOpt[Int],
            "data" -> (v \ "codesOverTime" \ "buckets")
              .asOpt[JsArray]
              .map(_.value.flatMap { j =>
                for {
                  k <- (j \ "key").asOpt[String]
                  v <- (j \ "doc_count").asOpt[Int]
                } yield Json.arr(k, v)
              })
          )
      }
      Json.obj(
        "chart" -> Json.obj("type" -> "areaspline"),
        "series" -> series
      )
    }
    .map(Some.apply)

  override def fetchDataInStatsHistogram(service: Option[String],
                                         from: Option[DateTime],
                                         to: Option[DateTime])(
                                          implicit env: Env,
                                          ec: ExecutionContext
                                        ): Future[Option[JsValue]] =
    statsHistogram("data.dataIn", service, from, to).map(Some.apply)

  override def fetchDataOutStatsHistogram(service: Option[String],
                                          from: Option[DateTime],
                                          to: Option[DateTime])(
                                           implicit env: Env,
                                           ec: ExecutionContext
                                         ): Future[Option[JsValue]] =
    statsHistogram("data.dataOut", service, from, to).map(Some.apply)

  override def fetchDurationStatsHistogram(service: Option[String],
                                           from: Option[DateTime],
                                           to: Option[DateTime])(
                                            implicit env: Env,
                                            ec: ExecutionContext
                                          ): Future[Option[JsValue]] =
    statsHistogram("duration", service, from, to).map(Some.apply)

  override def fetchDurationPercentilesHistogram(service: Option[String],
                                                 from: Option[DateTime],
                                                 to: Option[DateTime])(
                                                  implicit env: Env,
                                                  ec: ExecutionContext
                                                ): Future[Option[JsValue]] =
    percentilesHistogram("duration", service, from, to).map(Some.apply)

  override def fetchOverheadPercentilesHistogram(service: Option[String],
                                                 from: Option[DateTime],
                                                 to: Option[DateTime])(
                                                  implicit env: Env,
                                                  ec: ExecutionContext
                                                ): Future[Option[JsValue]] =
    percentilesHistogram("overhead", service, from, to).map(Some.apply)

  override def fetchProductPiechart(service: Option[String],
                                    from: Option[DateTime],
                                    to: Option[DateTime],
                                    size: Int)(
                                     implicit env: Env,
                                     ec: ExecutionContext
                                   ): Future[Option[JsValue]] =
    piechart("@product", service, from, to).map(Some.apply)

  override def fetchServicePiechart(service: Option[String],
                                    from: Option[DateTime],
                                    to: Option[DateTime],
                                    size: Int)(
                                     implicit env: Env,
                                     ec: ExecutionContext
                                   ): Future[Option[JsValue]] =
    piechart("@service", service, from, to).map(Some.apply)

  override def fetchOverheadStatsHistogram(service: Option[String],
                                           from: Option[DateTime],
                                           to: Option[DateTime])(
                                            implicit env: Env,
                                            ec: ExecutionContext): Future[Option[JsValue]] =
    statsHistogram("overhead", service, from, to).map(Some.apply)


  private def query(query: JsObject)(implicit ec: ExecutionContext): Future[JsValue] = {
    val basicHeader: Option[String] = for {
      user <- config.user
      password <- config.password
    } yield s"Basic ${Base64.getEncoder.encodeToString(s"$user:$password".getBytes())}"

    val builder = client.url(searchUri)

    logger.debug(s"Query to Elasticsearch: $query")

    basicHeader
      .fold(builder) { h =>
        builder.withHttpHeaders("Authorization" -> h)
      }
      .post(query)
      .flatMap { resp =>
        resp.status match {
          case 200 => FastFuture.successful(resp.json)
          case  _ => FastFuture.failed(new RuntimeException(s"Error during es request: ${resp.body}"))
        }
      }
  }

  private def statsHistogram(field: String, service: Option[String], mayBeFrom: Option[DateTime], mayBeTo: Option[DateTime])(implicit ec: ExecutionContext): Future[JsObject] = {
    query(Json.obj(
      "size" -> 0,
      "query" -> Json.obj(
        "bool" -> Json.obj(
          "must" -> filters(service, mayBeFrom, mayBeTo)
        )
      ),
      "aggs" -> Json.obj (
        "stats" -> Json.obj(
          "date_histogram" -> Json.obj(
            "field" -> "@timestamp",
            "interval" -> calcInterval(mayBeFrom, mayBeTo)
          ),
          "aggs" -> Json.obj(
            "stats" -> Json.obj(
              "extended_stats" -> Json.obj(
                "field" -> field
              )
            )
          )
        )
      )
    )).map { res =>
      val bucket = (res \ "aggregations" \ "stats" \ "buckets").as[JsValue]
      Json.obj(
        "chart" -> Json.obj("type" -> "chart"),
        "series" -> Json.arr(
          extractSerie(bucket, "count", json => (json \ "stats" \ "count").asOpt[Int]),
          extractSerie(bucket, "min", json => (json \ "stats" \ "min").asOpt[Int]),
          extractSerie(bucket, "max", json => (json \ "stats" \ "max").asOpt[Int]),
          extractSerie(bucket, "avg", json => (json \ "stats" \ "avg").asOpt[Int]),
          extractSerie(bucket, "std deviation", json => (json \ "stats" \ "std_deviation").asOpt[Int])
        )
      )
    }
  }


  private def percentilesHistogram(field: String, service: Option[String], mayBeFrom: Option[DateTime], mayBeTo: Option[DateTime])(implicit ec: ExecutionContext): Future[JsObject] = {
    query(Json.obj(
      "size" -> 0,
      "query" -> Json.obj(
        "bool" -> Json.obj(
          "must" -> filters(service, mayBeFrom, mayBeTo)
        )
      ),
      "aggs" -> Json.obj(
        "stats" -> Json.obj(
          "date_histogram" -> Json.obj(
            "field" -> "@timestamp",
            "interval" -> calcInterval(mayBeFrom, mayBeTo)
          ),
          "aggs" -> Json.obj(
            "stats" -> Json.obj(
              "percentiles" -> Json.obj(
                "field" -> field
              )
            )
          )
        )
      )
    )).map { res =>
      val bucket = (res \ "aggregations" \ "stats" \ "buckets").as[JsValue]
      Json.obj(
        "chart" -> Json.obj("type" -> "chart"),
        "series" -> Json.arr(
          extractSerie(bucket, "1.0",   json => (json \ "stats" \ "values" \ "1.0").asOpt[Int]),
          extractSerie(bucket, "5.0",   json => (json \ "stats" \ "values" \ "5.0").asOpt[Int]),
          extractSerie(bucket, "25.0",  json => (json \ "stats" \ "values" \ "25.0").asOpt[Int]),
          extractSerie(bucket, "50.0",  json => (json \ "stats" \ "values" \ "50.0").asOpt[Int]),
          extractSerie(bucket, "75.0",  json => (json \ "stats" \ "values" \ "75.0").asOpt[Int]),
          extractSerie(bucket, "95.0",  json => (json \ "stats" \ "values" \ "95.0").asOpt[Int]),
          extractSerie(bucket, "99.0",  json => (json \ "stats" \ "values" \ "99.0").asOpt[Int])
        )
      )
    }
  }

  private def calcInterval(from: Option[DateTime], to: Option[DateTime]) = "month"

  private def extractSerie(bucket: JsValue, name: String, extract: JsValue => JsValueWrapper, extra: JsObject = Json.obj()): JsValue = {
    bucket match {
      case JsArray(array) =>
        Json.obj(
          "name" -> name,
          "data" -> Json.arr(array.map { extract }:_*),
        ) ++ extra
      case _ => JsNull
    }
  }

  private def sum(field: String, service: Option[String], mayBeFrom: Option[DateTime], mayBeTo: Option[DateTime])(implicit ec: ExecutionContext): Future[JsObject] = {
    aggregation("sum", field, service, mayBeFrom, mayBeTo)
  }
  private def avg(field: String, service: Option[String], mayBeFrom: Option[DateTime], mayBeTo: Option[DateTime])(implicit ec: ExecutionContext): Future[JsObject] = {
    aggregation("avg", field, service, mayBeFrom, mayBeTo)
  }

  private def aggregation(operation: String, field: String, service: Option[String], mayBeFrom: Option[DateTime], mayBeTo: Option[DateTime])(implicit ec: ExecutionContext): Future[JsObject] = {
    query(Json.obj(
      "size" -> 0,
      "query" -> Json.obj(
        "bool" -> Json.obj(
          "must" -> filters(service, mayBeFrom, mayBeTo)
        )
      ),
      "aggs" -> Json.obj {
        operation -> Json.obj(
          operation -> Json.obj(
            "field" -> field
          )
        )
      }
    )).map { res =>
      Json.obj(
        field -> (res \ "aggregations" \ operation \ "value").as[JsValue]
      )
    }
  }

  def piechart(field: String,
              service: Option[String],
              from: Option[DateTime],
              to: Option[DateTime],
              size: Int = 200)(implicit env: Env, ec: ExecutionContext): Future[JsValue] =
    query(Json.obj(
      "size" -> 0,
      "query" -> Json.obj(
        "bool" -> Json.obj {
          "must" -> filters(service, from, to)
        }
      ),
      "aggs" -> Json.obj(
        "codes" -> Json.obj(
          "terms" -> Json.obj(
            "field" -> field,
                    "order" -> Json.obj(
                      "_term" -> "asc"
                    ),
                    "size" -> size
          )
        )
      )
    ))
    .map { json =>
      val pie = (json \ "aggregations" \ "codes" \ "buckets")
        .asOpt[Seq[JsObject]]
        .getOrElse(Seq.empty)
        .map { o =>
          Json.obj(
            "name" -> s"${(o \ "key").asOpt[String].getOrElse("")}",
            "y" -> (o \ "doc_count").asOpt[Int]
          )
        }
      Json.obj(
        "name" -> "Pie Chart",
        "colorPoint" -> true,
        "data" -> JsArray(pie)
      )
    }

  private def filters(service: Option[String],
                      mayBeFrom: Option[DateTime],
                      mayBeTo: Option[DateTime]): Seq[JsObject] = {
    val to = mayBeTo.getOrElse(DateTime.now())
    val rangeCriteria = mayBeFrom.map { from =>
      Json.obj(
        "format" -> "date_optional_time",
        "lte" -> ISODateTimeFormat.dateTime().print(to),
        "gte" -> ISODateTimeFormat.dateTime().print(from)
      )
    }.getOrElse {
      Json.obj(
        "format" -> "date_optional_time",
        "lte" -> ISODateTimeFormat.dateTime().print(to)
      )
    }

    val rangeQuery = Json.obj(
      "range" -> Json.obj(
        "@timestamp" -> rangeCriteria
      )
    )

    val serviceQuery = service.map { s =>
      Json.obj(
        "bool" -> Json.obj(
          "minimum_should_match"  -> 1,
          "should" -> Json.arr(
            Json.obj(
              "bool" -> Json.obj(
                "must_not" -> Json.obj(
                  "exists" -> Json.obj(
                    "field" -> "@product"
                  )
                )
              )
            ),
            Json.obj(
              "terms" -> Json.obj(
                "@product" -> Json.arr(s)
              )
            )
          )
        )
      )
    }

    Seq(
      Some(Json.obj(
        "terms" -> Json.obj(
          "@type" -> "GatewayEvent"
        )
      )),
      Some(rangeQuery),
      serviceQuery
    ).flatten
  }



}
