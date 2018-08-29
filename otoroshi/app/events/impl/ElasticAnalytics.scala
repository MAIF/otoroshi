package events.impl
import java.nio.file.{Files, Paths}
import java.util.Base64

import akka.http.scaladsl.util.FastFuture
import env.Env
import events.{AnalyticEvent, AnalyticsService}
import models.ElasticAnalyticsConfig
import org.joda.time.{DateTime, Interval}
import org.joda.time.format.ISODateTimeFormat
import play.api.{Environment, Logger}
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSRequest}

import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

class ElasticAnalytics(config: ElasticAnalyticsConfig, environment: Environment, client: WSClient, executionContext: ExecutionContext) extends AnalyticsService {

  private def urlFromPath(path: String): String = s"${config.clusterUri}$path"
  private val `type`: String = config.`type`.getOrElse("type")
  private val index: String = config.index.getOrElse("otoroshi-events")
  private val searchUri   = urlFromPath(s"/$index*/_search")

  private def indexUri: String = {
    val df = ISODateTimeFormat.date().print(DateTime.now())
    urlFromPath(s"/$index-$df/${`type`}")
  }

  lazy val logger = Logger("otoroshi-analytics-elastic")

  init()

  private def init(): Unit = {
    implicit val ec: ExecutionContext = executionContext
    val strTpl = new String(Files.readAllBytes(Paths.get(environment.getFile("conf/elasticsearch/template.json").toURI)))
    val tpl: JsValue = Json.parse(strTpl.replace("$$$INDEX$$$", index))
    logger.debug(s"Creating otoroshi template with \n${Json.prettyPrint(tpl)}")
    Await.result(
      url(urlFromPath("/_template/otoroshi-tpl"))
        .get()
        .flatMap { resp =>
            resp.status match {
              case 200  =>
                val tplCreated = url(urlFromPath("/_template/otoroshi-tpl")).put(tpl)
                tplCreated.onComplete {
                  case Success(r) if r.status >= 400 =>
                    logger.error(s"Error creating template ${r.status}: ${r.body}")
                  case Failure(e) =>
                    logger.error("Error creating template", e)
                  case _ =>
                    logger.debug("Otoroshi template created")
                }
                tplCreated.map(_ => ())
              case 404 =>
                val tplCreated = url(urlFromPath("/_template/otoroshi-tpl")).post(tpl)
                tplCreated.onComplete {
                  case Success(r) if r.status >= 400 =>
                    logger.error(s"Error creating template ${r.status}: ${r.body}")
                  case Failure(e) =>
                    logger.error("Error creating template", e)
                  case _ =>
                    logger.debug("Otoroshi template created")
                }
                tplCreated.map(_ => ())
              case _ =>
                logger.error(s"Error creating template ${resp.status}: ${resp.body}")
                FastFuture.successful(())
            }
          },
      1.second
    )
  }

  private def url(url: String): WSRequest = {
    val builder = client.url(url)
    authHeader()
      .fold(builder) { h =>
        builder.withHttpHeaders("Authorization" -> h)
      }
  }

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




  override def publish(event: Seq[AnalyticEvent])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val builder = client.url(indexUri)
    val clientInstance = authHeader()
      .fold(builder) { h =>
        builder.withHttpHeaders("Authorization" -> h)
      }
    Future.traverse(event) { event =>
      val post = clientInstance.post(event.toJson)
        post.onComplete {
          case Success(resp) =>
            if (resp.status >= 400) {
              logger.error(s"Error publishing event to elastic: ${resp.status}, ${resp.body} --- event: $event")
            }
          case Failure(e) =>
            logger.error(s"Error publishing event to elastic", e)
        }
      post
    }
    .map(_ => ())
  }


  override def events(eventType: String,
                      service: Option[String],
                      from: Option[DateTime],
                      to: Option[DateTime],
                      page: Int,
                      size: Int)(
      implicit env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] = {
    val pageFrom = (page - 1) * size

    val queryFilters: Seq[JsObject] = filters(None, from, to) ++ Seq(
      service.map(s => Json.obj("term" -> Json.obj("@service" -> s)))
    ).flatten


    query(
      Json.obj(
        "size" -> size,
        "from" -> pageFrom,
        "query" -> Json.obj(
          "bool" -> Json.obj(
            "filters" -> queryFilters
          )
        ),
        "sort" -> Json.obj(
          "@timestamp" -> Json.obj(
            "order" -> "desc"
          )
        )
      )
    )
    .map { res =>
      Json.obj(
        "events" -> (res \ "response" \ "hits" \ "hits" \ "_source").asOpt[Seq[JsObject]]
      )
    }
    .map(Some.apply)
  }


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
      val series = buckets.value
        .map { case (k, v) =>
            Json.obj(
              "name"  -> k,
              "count" -> (v \ "doc_count").asOpt[Int],
              "data" -> (v \ "codesOverTime" \ "buckets")
                .asOpt[Seq[JsValue]]
                .map(_.flatMap { j =>
                  for {
                    k <- (j \ "key").asOpt[JsValue]
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
    val builder = client.url(searchUri)

    logger.debug(s"Query to Elasticsearch: $query")

    authHeader()
      .fold(builder) { h =>
        builder.withHttpHeaders("Authorization" -> h)
      }
      .post(query)
      .flatMap { resp =>
        resp.status match {
          case 200 => FastFuture.successful(resp.json)
          case  _ => FastFuture.failed(new RuntimeException(s"Error during es request: \n * ${resp.body}, \nquery was \n * $query"))
        }
      }
  }

  private def authHeader(): Option[String] = {
    for {
      user <- config.user
      password <- config.password
    } yield s"Basic ${Base64.getEncoder.encodeToString(s"$user:$password".getBytes())}"
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
      val bucket = (res \ "aggregations" \ "stats" \ "buckets").asOpt[JsValue].getOrElse(JsNull)
      Json.obj(
        "chart" -> Json.obj("type" -> "chart"),
        "series" -> Json.arr(
          extractSerie(bucket, "count", json => (json \ "stats" \ "count").asOpt[JsValue]),
          extractSerie(bucket, "min", json => (json \ "stats" \ "min").asOpt[JsValue]),
          extractSerie(bucket, "max", json => (json \ "stats" \ "max").asOpt[JsValue]),
          extractSerie(bucket, "avg", json => (json \ "stats" \ "avg").asOpt[JsValue]),
          extractSerie(bucket, "std deviation", json => (json \ "stats" \ "std_deviation").asOpt[JsValue])
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
      val bucket = (res \ "aggregations" \ "stats" \ "buckets").asOpt[JsValue].getOrElse(JsNull)
      Json.obj(
        "chart" -> Json.obj("type" -> "areaspline"),
        "series" -> Json.arr(
          extractSerie(bucket, "1.0",   json => (json \ "stats" \ "values" \ "1.0").asOpt[JsValue]),
          extractSerie(bucket, "5.0",   json => (json \ "stats" \ "values" \ "5.0").asOpt[JsValue]),
          extractSerie(bucket, "25.0",  json => (json \ "stats" \ "values" \ "25.0").asOpt[JsValue]),
          extractSerie(bucket, "50.0",  json => (json \ "stats" \ "values" \ "50.0").asOpt[JsValue]),
          extractSerie(bucket, "75.0",  json => (json \ "stats" \ "values" \ "75.0").asOpt[JsValue]),
          extractSerie(bucket, "95.0",  json => (json \ "stats" \ "values" \ "95.0").asOpt[JsValue]),
          extractSerie(bucket, "99.0",  json => (json \ "stats" \ "values" \ "99.0").asOpt[JsValue])
        )
      )
    }
  }

  private def calcInterval(mayBeFrom: Option[DateTime], mayBeTo: Option[DateTime]) = {
    val from = mayBeFrom.getOrElse(DateTime.now())
    val to = mayBeTo.getOrElse(DateTime.now())

    val interval = new Interval(from, to).toDuration
    val days = interval.toStandardDays.getDays
    val hours = interval.toStandardHours.getHours

    if (days > 24) {
      "month"
    } else if (days > 60) {
      "week"
    } else if (days > 30) {
      "day"
    } else if (hours > 12) {
      "hour"
    } else {
      "minute"
    }

  }

  private def extractSerie(bucket: JsValue, name: String, extract: JsValue => JsValueWrapper, extra: JsObject = Json.obj()): JsValue = {
    bucket match {
      case JsArray(array) =>
        Json.obj(
          "name" -> name,
          "data" -> array.map { j =>
            Json.arr(
              (j \ "key").as[JsValue],
              extract(j)
            )
          },
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
        field -> (res \ "aggregations" \ operation \ "value").asOpt[JsValue]
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
            "name" -> s"${(o \ "key").as[JsValue]}",
            "y" -> (o \ "doc_count").asOpt[JsValue]
          )
        }
      Json.obj(
        "name" -> "Pie Chart",
        "colorPoint" -> true,
        "series" -> Json.arr(
          Json.obj(
            "data" -> JsArray(pie)
          )
        )
      )
    }

  private def filters(service: Option[String],
                      mayBeFrom: Option[DateTime],
                      mayBeTo: Option[DateTime]): Seq[JsObject] = {
    val to = mayBeTo.getOrElse(DateTime.now())
    //val formatter = ISODateTimeFormat.dateHourMinuteSecondMillis()
    val rangeCriteria = mayBeFrom.map { from =>
      Json.obj(
        "lte" -> to.getMillis,
        "gte" -> from.getMillis
      )
    }.getOrElse {
      Json.obj(
        "lte" -> to.getMillis
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
              "term" -> Json.obj(
                "@product" -> s
              )
            )
          )
        )
      )
    }

    Seq(
      Some(Json.obj("term" -> Json.obj("@type" -> "GatewayEvent"))),
      Some(rangeQuery),
      serviceQuery
    ).flatten
  }



}
