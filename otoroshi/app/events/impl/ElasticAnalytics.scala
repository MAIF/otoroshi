package otoroshi.events.impl

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Sink, Source}
import otoroshi.env.Env
import otoroshi.events._
import models.{ApiKey, ElasticAnalyticsConfig, ServiceDescriptor, ServiceGroup}
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, Interval}
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSRequest}
import play.api.{Environment, Logger}
import otoroshi.utils.syntax.implicits._

import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object ElasticTemplates {
  val indexTemplate_v6 =
    """{
      |  "template": "$$$INDEX$$$-*",
      |  "settings": {
      |    "number_of_shards": 1,
      |    "index": {
      |    }
      |  },
      |  "mappings": {
      |    "_default_": {
      |      "date_detection": false,
      |      "dynamic_templates": [
      |        {
      |          "string_template": {
      |            "match": "*",
      |            "mapping": {
      |              "type": "text",
      |              "fielddata": true
      |            },
      |            "match_mapping_type": "string"
      |          }
      |        }
      |      ],
      |      "properties": {
      |        "@id": {
      |          "type": "keyword"
      |        },
      |        "@timestamp": {
      |          "type": "date"
      |        },
      |        "@created": {
      |          "type": "date"
      |        },
      |        "@product": {
      |          "type": "keyword"
      |        },
      |        "@type": {
      |          "type": "keyword"
      |        },
      |        "@service": {
      |          "type": "keyword"
      |        },
      |        "@serviceId": {
      |          "type": "keyword"
      |        },
      |        "@env": {
      |          "type": "keyword"
      |        },
      |        "health": {
      |          "type": "keyword"
      |        },
      |        "headers": {
      |          "properties": {
      |            "key": {
      |              "type": "keyword"
      |            },
      |            "value": {
      |              "type": "keyword"
      |            }
      |          }
      |        },
      |        "headersOut": {
      |          "properties": {
      |            "key": {
      |              "type": "keyword"
      |            },
      |            "value": {
      |              "type": "keyword"
      |            }
      |          }
      |        }
      |      }
      |    }
      |  }
      |}
    """.stripMargin

  val indexTemplate_v7 =
    """{
      |  "index_patterns" : ["$$$INDEX$$$-*"],
      |  "settings": {
      |    "number_of_shards": 1,
      |    "index": {}
      |  },
      |  "mappings": {
      |    "date_detection": false,
      |    "dynamic_templates": [
      |      {
      |        "string_template": {
      |          "match": "*",
      |          "mapping": {
      |            "type": "text",
      |            "fielddata": true
      |          },
      |          "match_mapping_type": "string"
      |        }
      |      }
      |    ],
      |    "properties": {
      |      "@id": {
      |        "type": "keyword"
      |      },
      |      "@timestamp": {
      |        "type": "date"
      |      },
      |      "@created": {
      |        "type": "date"
      |      },
      |      "@product": {
      |        "type": "keyword"
      |      },
      |      "@type": {
      |        "type": "keyword"
      |      },
      |      "@service": {
      |        "type": "keyword"
      |      },
      |      "@serviceId": {
      |        "type": "keyword"
      |      },
      |      "@env": {
      |        "type": "keyword"
      |      },
      |      "health": {
      |        "type": "keyword"
      |      },
      |      "headers": {
      |        "properties": {
      |          "key": {
      |            "type": "keyword"
      |          },
      |          "value": {
      |            "type": "keyword"
      |          }
      |        }
      |      },
      |      "headersOut": {
      |        "properties": {
      |          "key": {
      |            "type": "keyword"
      |          },
      |          "value": {
      |            "type": "keyword"
      |          }
      |        }
      |      }
      |    }
      |  }
      |}
    """.stripMargin

    val indexTemplate_v7_8 =
    """{
      |  "index_patterns" : ["$$$INDEX$$$-*"],
      |  "template": {
      |  "settings": {
      |    "number_of_shards": 1,
      |    "index": {}
      |  },
      |  "mappings": {
      |    "date_detection": false,
      |    "dynamic_templates": [
      |      {
      |        "string_template": {
      |          "match": "*",
      |          "mapping": {
      |            "type": "text",
      |            "fielddata": true
      |          },
      |          "match_mapping_type": "string"
      |        }
      |      }
      |    ],
      |    "properties": {
      |      "@id": {
      |        "type": "keyword"
      |      },
      |      "@timestamp": {
      |        "type": "date"
      |      },
      |      "@created": {
      |        "type": "date"
      |      },
      |      "@product": {
      |        "type": "keyword"
      |      },
      |      "@type": {
      |        "type": "keyword"
      |      },
      |      "@service": {
      |        "type": "keyword"
      |      },
      |      "@serviceId": {
      |        "type": "keyword"
      |      },
      |      "@env": {
      |        "type": "keyword"
      |      },
      |      "health": {
      |        "type": "keyword"
      |      },
      |      "headers": {
      |        "properties": {
      |          "key": {
      |            "type": "keyword"
      |          },
      |          "value": {
      |            "type": "keyword"
      |          }
      |        }
      |      },
      |      "headersOut": {
      |        "properties": {
      |          "key": {
      |            "type": "keyword"
      |          },
      |          "value": {
      |            "type": "keyword"
      |          }
      |        }
      |      }
      |    }
      |  }
      |  }
      |}
    """.stripMargin
}

object ElasticWritesAnalytics {

  import collection.JavaConverters._

  val clusterInitializedCache = new ConcurrentHashMap[String, (Boolean, ElasticVersion)]()

  def toKey(config: ElasticAnalyticsConfig): String = {
    val index: String = config.index.getOrElse("otoroshi-events")
    val `type`: String = config.`type`.getOrElse("event")
    s"${config.clusterUri}/$index/${`type`}"
  }

  def initialized(config: ElasticAnalyticsConfig, version: ElasticVersion): Unit = {
    clusterInitializedCache.putIfAbsent(toKey(config), (true, version))
  }

  def isInitialized(config: ElasticAnalyticsConfig): (Boolean, ElasticVersion) = {
    clusterInitializedCache.asScala.getOrElse(toKey(config), (false, ElasticVersion.UnderSeven))
  }
}

sealed trait ElasticVersion
object ElasticVersion {
  case object UnderSeven extends ElasticVersion
  case object AboveSeven extends ElasticVersion
  case object AboveSevenEight extends ElasticVersion
}

class ElasticWritesAnalytics(config: ElasticAnalyticsConfig, env: Env) extends AnalyticsWritesService {

  import otoroshi.utils.http.Implicits._

  lazy val logger = Logger("otoroshi-analytics-writes-elastic")

  private val environment: Environment           = env.environment
  private val executionContext: ExecutionContext = env.analyticsExecutionContext
  private val system: ActorSystem                = env.analyticsActorSystem

  private def urlFromPath(path: String): String = s"${config.clusterUri}$path"
  private val index: String                     = config.index.getOrElse("otoroshi-events")
  private val `type`: String                    = config.`type`.getOrElse("event")
  private implicit val mat                      = Materializer(system)

  private def url(url: String): WSRequest = {
    val builder =
      env.MtlsWs
        .url(url, config.mtlsConfig)
        .withMaybeProxyServer(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.elastic))
    authHeader()
      .fold(builder) { h =>
        builder.withHttpHeaders("Authorization" -> h)
      }
      .addHttpHeaders(config.headers.toSeq: _*)
  }

  private def getElasticVersion()(implicit ec: ExecutionContext): Future[ElasticVersion] = {

    import otoroshi.jobs.updates.Version

    url(urlFromPath(""))
      .get()
      .map(_.json)
      .map(json => (json \ "version" \ "number").asOpt[String].getOrElse("6.0.0"))
      // .map(v => v.split("\\.").headOption.map(_.toInt).getOrElse(6))
      .map { _v =>
        Version(_v) match {
          case v if v.isBefore(Version("7.0.0")) =>  ElasticVersion.UnderSeven
          case v if v.isAfterEq(Version("7.8.0")) => ElasticVersion.AboveSevenEight
          case v if v.isAfterEq(Version("7.0.0")) => ElasticVersion.AboveSeven
          case _ => ElasticVersion.AboveSeven
        }
        // _v.split("\\.").headOption.map(_.toInt).getOrElse(6) match {
        //   case v if v <= 6 => ElasticVersion.UnderSeven
        //   case v if v > 6 => {
        //     _v.split("\\.").drop(1).headOption.map(_.toInt).getOrElse(1) match {
        //       case v if v >= 8 => ElasticVersion.AboveSevenEight
        //       case v if v < 8 => ElasticVersion.AboveSeven
        //     }
        //   }
        // } 
      }
  }

  override def init(): Unit = {
    if (ElasticWritesAnalytics.isInitialized(config)._1) {
      ()
    } else {
      implicit val ec: ExecutionContext = executionContext
      logger.info(
        s"Creating Otoroshi template for $index on es cluster at ${config.clusterUri}/$index/${`type`}"
      )
      Await.result(
        getElasticVersion().flatMap { version =>
          // from elastic 7.8, we should use /_index_template/otoroshi-tpl and wrap almost everything expect index_patterns in a "template" object
          val (strTpl, indexTemplatePath) = version match {
            case ElasticVersion.UnderSeven      => (ElasticTemplates.indexTemplate_v6, "/_template/otoroshi-tpl")
            case ElasticVersion.AboveSeven      => (ElasticTemplates.indexTemplate_v7, "/_template/otoroshi-tpl")
            case ElasticVersion.AboveSevenEight => (ElasticTemplates.indexTemplate_v7_8, "/_index_template/otoroshi-tpl")
          }
          val tpl: JsValue = Json.parse(strTpl.replace("$$$INDEX$$$", index))
          logger.debug(s"Creating otoroshi template with \n${Json.prettyPrint(tpl)}")
          url(urlFromPath(indexTemplatePath))
            .get()
            .flatMap { resp =>
              resp.status match {
                case 200 =>
                // TODO: check if same ???
                  resp.ignore()
                  val tplCreated = url(urlFromPath(indexTemplatePath)).put(tpl)
                  tplCreated.onComplete {
                    case Success(r) if r.status >= 400 =>
                      logger.error(s"Error creating template ${r.status}: ${r.body}")
                    case Failure(e) =>
                      logger.error("Error creating template", e)
                    case Success(r) =>
                      r.ignore()
                      logger.debug("Otoroshi template updated")
                      ElasticWritesAnalytics.initialized(config, version)
                    case _ =>
                      logger.debug("Otoroshi template updated")
                      ElasticWritesAnalytics.initialized(config, version)
                  }
                  tplCreated.map(_ => ())
                case 404 =>
                  resp.ignore()
                  val tplCreated = url(urlFromPath(indexTemplatePath)).post(tpl)
                  tplCreated.onComplete {
                    case Success(r) if r.status >= 400 =>
                      logger.error(s"Error creating template ${r.status}: ${r.body}")
                    case Failure(e) =>
                      logger.error("Error creating template", e)
                    case Success(r) =>
                      r.ignore()
                      logger.debug("Otoroshi template created")
                      ElasticWritesAnalytics.initialized(config, version)
                    case _ =>
                      logger.debug("Otoroshi template created")
                      ElasticWritesAnalytics.initialized(config, version)
                  }
                  tplCreated.map(_ => ())
                case _ =>
                  logger.error(s"Error creating template ${resp.status}: ${resp.body}")
                  FastFuture.successful(())
              }
            }
        },
        5.second
      )
    }
  }

  init()

  private def bulkRequest(source: JsValue): String = {
    val version: ElasticVersion = ElasticWritesAnalytics.isInitialized(config)._2
    val df            = ISODateTimeFormat.date().print(DateTime.now())
    val indexWithDate = s"$index-$df"
    val indexClause   = Json.stringify(Json.obj("index" -> Json.obj("_index" -> indexWithDate).applyOnIf(version == ElasticVersion.UnderSeven)(_ ++ Json.obj("_type" -> `type`))))
    val sourceClause  = Json.stringify(source)
    s"$indexClause\n$sourceClause"
  }

  private def authHeader(): Option[String] = {
    for {
      user     <- config.user
      password <- config.password
    } yield s"Basic ${Base64.getEncoder.encodeToString(s"$user:$password".getBytes())}"
  }

  override def publish(event: Seq[JsValue])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val builder = env.MtlsWs
      .url(urlFromPath("/_bulk"), config.mtlsConfig)
      .withMaybeProxyServer(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.elastic))

    val clientInstance = authHeader()
      .fold {
        builder.withHttpHeaders(
          "Content-Type" -> "application/x-ndjson"
        )
      } { h =>
        builder.withHttpHeaders(
          "Authorization" -> h,
          "Content-Type"  -> "application/x-ndjson"
        )
      }
      .addHttpHeaders(config.headers.toSeq: _*)
    Source(event.toList)
      .grouped(500)
      .map(_.map(bulkRequest))
      .mapAsync(10) { bulk =>
        val req  = bulk.mkString("", "\n", "\n\n")
        val post = clientInstance.post(req)
        post.onComplete {
          case Success(resp) =>
            if (resp.status >= 400) {
              logger.error(s"Error publishing event to elastic: ${resp.status}, ${resp.body} --- event: $event")
            } else {
              val esResponse = Json.parse(resp.body)
              val esError = (esResponse \ "errors").asOpt[Boolean].getOrElse(false)
              if (esError) {
                logger.error(s"An error occured in ES bulk: ${resp.status}, ${Json.prettyPrint(esResponse)}")
              }
              resp.ignore()
            }
          case Failure(e) =>
            logger.error(s"Error publishing event to elastic", e)
        }
        post
      }
      .runWith(Sink.ignore)
      .map(_ => ())
  }
}

class ElasticReadsAnalytics(config: ElasticAnalyticsConfig, env: Env) extends AnalyticsReadsService {

  private val executionContext: ExecutionContext = env.analyticsExecutionContext
  private val system: ActorSystem                = env.analyticsActorSystem

  private def urlFromPath(path: String): String = s"${config.clusterUri}$path"
  private val `type`: String                    = config.`type`.getOrElse("type")
  private val index: String                     = config.index.getOrElse("otoroshi-events")
  private val searchUri                         = urlFromPath(s"/$index*/_search")
  private implicit val mat                      = Materializer(system)

  private def indexUri: String = {
    val df = ISODateTimeFormat.date().print(DateTime.now())
    urlFromPath(s"/$index-$df/${`type`}")
  }

  lazy val logger = Logger("otoroshi-analytics-reads-elastic")

  private def url(url: String): WSRequest = {
    val builder = env.MtlsWs.url(url, config.mtlsConfig)
    authHeader()
      .fold(builder) { h =>
        builder.withHttpHeaders("Authorization" -> h)
      }
      .addHttpHeaders(config.headers.toSeq: _*)
  }

  override def fetchHits(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]] = {
    query(
      Json.obj(
        "size" -> 0,
        "query" -> Json.obj(
          "bool" -> filters(filterable, from, to)
        )
      )
    ).map { resp =>
      Json.obj(
        "count" -> (resp \ "hits" \ "total").asOpt[Int]
      )
    }
      .map(Some.apply)
  }

  override def events(eventType: String,
                      filterable: Option[Filterable],
                      from: Option[DateTime],
                      to: Option[DateTime],
                      page: Int,
                      size: Int,
                      order: String = "desc")(
                       implicit env: Env,
                       ec: ExecutionContext
                     ): Future[Option[JsValue]] = {
    val pageFrom = (page - 1) * size
    query(
      Json.obj(
        "size" -> size,
        "from" -> pageFrom,
        "query" -> Json.obj(
          "bool" -> filters(filterable, from, to) /*Json.obj(
            "minimum_should_match" -> 1,
            "should" -> (
              service.map(s => Json.obj("term" -> Json.obj("@serviceId"     -> s.id))).toSeq ++
              service.map(s => Json.obj("term" -> Json.obj("@serviceId.raw" -> s.id))).toSeq
            ),
            "must" -> (
              dateFilters(from, to) ++
              gatewayEventFilters
            )
          )*/
        ),
        "sort" -> Json.obj(
          "@timestamp" -> Json.obj(
            "order" -> order
          )
        )
      )
    ).map { res =>
      val events: Seq[JsValue] = (res \ "hits" \ "hits").as[Seq[JsValue]].map { j =>
        (j \ "_source").as[JsValue]
      }
      Json.obj(
        "events" -> events
      )
    }
      .map(Some.apply)
  }

  override def fetchDataIn(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]] =
    sum("data.dataIn", filterable, from, to).map(Some.apply)

  override def fetchDataOut(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]] =
    sum("data.dataOut", filterable, from, to).map(Some.apply)

  override def fetchAvgDuration(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]] =
    avg("duration", filterable, from, to).map(Some.apply)

  override def fetchAvgOverhead(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]] =
    avg("overhead", filterable, from, to).map(Some.apply)

  override def fetchStatusesPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]] =
    piechart("status", filterable, from, to).map(Some.apply)

  override def fetchStatusesHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]] =
    query(
      Json.obj(
        "size" -> 0,
        "query" -> Json.obj {
          "bool" -> filters(filterable, from, to)
        },
        "aggs" -> Json.obj(
          "codes" -> Json.obj(
            "aggs" -> Json.obj(
              "codesOverTime" -> Json.obj(
                "date_histogram" -> Json.obj(
                  "interval" -> "hour",
                  "field"    -> "@timestamp"
                )
              )
            ),
            "range" -> Json.obj(
              "ranges" -> Json.arr(
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
      )
    ).map { res =>
      val buckets: JsObject = (res \ "aggregations" \ "codes" \ "buckets").asOpt[JsObject].getOrElse(Json.obj())
      val series = buckets.value
        .map {
          case (k, v) =>
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
        "chart"  -> Json.obj("type" -> "areaspline"),
        "series" -> series
      )
    }
      .map(Some.apply)

  override def fetchDataInStatsHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]] =
    statsHistogram("data.dataIn", filterable, from, to).map(Some.apply)

  override def fetchDataOutStatsHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]] =
    statsHistogram("data.dataOut", filterable, from, to).map(Some.apply)

  override def fetchDurationStatsHistogram(filterable: Option[Filterable],
                                           from: Option[DateTime],
                                           to: Option[DateTime])(
                                            implicit env: Env,
                                            ec: ExecutionContext
                                          ): Future[Option[JsValue]] =
    statsHistogram("duration", filterable, from, to).map(Some.apply)

  override def fetchDurationPercentilesHistogram(filterable: Option[Filterable],
                                                 from: Option[DateTime],
                                                 to: Option[DateTime])(
                                                  implicit env: Env,
                                                  ec: ExecutionContext
                                                ): Future[Option[JsValue]] =
    percentilesHistogram("duration", filterable, from, to).map(Some.apply)

  override def fetchOverheadPercentilesHistogram(filterable: Option[Filterable],
                                                 from: Option[DateTime],
                                                 to: Option[DateTime])(
                                                  implicit env: Env,
                                                  ec: ExecutionContext
                                                ): Future[Option[JsValue]] =
    percentilesHistogram("overhead", filterable, from, to).map(Some.apply)

  override def fetchProductPiechart(filterable: Option[Filterable],
                                    from: Option[DateTime],
                                    to: Option[DateTime],
                                    size: Int)(
                                     implicit env: Env,
                                     ec: ExecutionContext
                                   ): Future[Option[JsValue]] =
    piechart("@product", filterable, from, to).map(Some.apply)

  override def fetchApiKeyPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]] =
    piechart("identity.label.raw",
      filterable,
      from,
      to,
      additionalFilters = Seq(
        Json.obj(
          "term" -> Json.obj(
            "identity.identityType.raw" -> "APIKEY"
          )
        )
      )).map(Some.apply)

  override def fetchUserPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
    implicit env: Env,
    ec: ExecutionContext
  ): Future[Option[JsValue]] =
    piechart("identity.label.raw",
      filterable,
      from,
      to,
      additionalFilters = Seq(
        Json.obj(
          "term" -> Json.obj(
            "identity.identityType.raw" -> "PRIVATEAPP"
          )
        )
      )).map(Some.apply)

  override def fetchServicePiechart(filterable: Option[Filterable],
                                    from: Option[DateTime],
                                    to: Option[DateTime],
                                    size: Int)(
                                     implicit env: Env,
                                     ec: ExecutionContext
                                   ): Future[Option[JsValue]] =
    piechart("@service", filterable, from, to).map(Some.apply)

  override def fetchOverheadStatsHistogram(filterable: Option[Filterable],
                                           from: Option[DateTime],
                                           to: Option[DateTime])(
                                            implicit env: Env,
                                            ec: ExecutionContext
                                          ): Future[Option[JsValue]] =
    statsHistogram("overhead", filterable, from, to).map(Some.apply)

  private def query(query: JsObject)(implicit ec: ExecutionContext): Future[JsValue] = {
    val builder = env.MtlsWs.url(searchUri, config.mtlsConfig)

    logger.debug(s"Query to Elasticsearch: ${Json.prettyPrint(query)}")

    authHeader()
      .fold(builder) { h =>
        builder.withHttpHeaders("Authorization" -> h)
      }
      .addHttpHeaders(config.headers.toSeq: _*)
      .post(query)
      .flatMap { resp =>
        resp.status match {
          case 200 => FastFuture.successful(resp.json)
          case _ =>
            FastFuture.failed(
              new RuntimeException(s"Error during es request: \n * ${resp.body}, \nquery was \n * $query")
            )
        }
      }
  }

  private def authHeader(): Option[String] = {
    for {
      user     <- config.user
      password <- config.password
    } yield s"Basic ${Base64.getEncoder.encodeToString(s"$user:$password".getBytes())}"
  }

  private def statsHistogram(field: String,
                             filterable: Option[Filterable],
                             mayBeFrom: Option[DateTime],
                             mayBeTo: Option[DateTime])(implicit ec: ExecutionContext): Future[JsObject] = {
    query(
      Json.obj(
        "size" -> 0,
        "query" -> Json.obj(
          "bool" -> filters(filterable, mayBeFrom, mayBeTo)
        ),
        "aggs" -> Json.obj(
          "stats" -> Json.obj(
            "date_histogram" -> Json.obj(
              "field"    -> "@timestamp",
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
      )
    ).map { res =>
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

  private def percentilesHistogram(field: String,
                                   filterable: Option[Filterable],
                                   mayBeFrom: Option[DateTime],
                                   mayBeTo: Option[DateTime])(implicit ec: ExecutionContext): Future[JsObject] = {
    query(
      Json.obj(
        "size" -> 0,
        "query" -> Json.obj(
          "bool" -> filters(filterable, mayBeFrom, mayBeTo)
        ),
        "aggs" -> Json.obj(
          "stats" -> Json.obj(
            "date_histogram" -> Json.obj(
              "field"    -> "@timestamp",
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
      )
    ).map { res =>
      val bucket = (res \ "aggregations" \ "stats" \ "buckets").asOpt[JsValue].getOrElse(JsNull)
      Json.obj(
        "chart" -> Json.obj("type" -> "areaspline"),
        "series" -> Json.arr(
          extractSerie(bucket, "1.0", json => (json \ "stats" \ "values" \ "1.0").asOpt[JsValue]),
          extractSerie(bucket, "5.0", json => (json \ "stats" \ "values" \ "5.0").asOpt[JsValue]),
          extractSerie(bucket, "25.0", json => (json \ "stats" \ "values" \ "25.0").asOpt[JsValue]),
          extractSerie(bucket, "50.0", json => (json \ "stats" \ "values" \ "50.0").asOpt[JsValue]),
          extractSerie(bucket, "75.0", json => (json \ "stats" \ "values" \ "75.0").asOpt[JsValue]),
          extractSerie(bucket, "95.0", json => (json \ "stats" \ "values" \ "95.0").asOpt[JsValue]),
          extractSerie(bucket, "99.0", json => (json \ "stats" \ "values" \ "99.0").asOpt[JsValue])
        )
      )
    }
  }

  private def calcInterval(mayBeFrom: Option[DateTime], mayBeTo: Option[DateTime]) = {
    val from = mayBeFrom.getOrElse(DateTime.now())
    val to   = mayBeTo.getOrElse(DateTime.now())

    val interval = new Interval(from, to).toDuration
    val days     = interval.toStandardDays.getDays
    val hours    = interval.toStandardHours.getHours

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

  private def extractSerie(bucket: JsValue,
                           name: String,
                           extract: JsValue => JsValueWrapper,
                           extra: JsObject = Json.obj()): JsValue = {
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

  private def sum(field: String,
                  filterable: Option[Filterable],
                  mayBeFrom: Option[DateTime],
                  mayBeTo: Option[DateTime])(
                   implicit ec: ExecutionContext
                 ): Future[JsObject] = {
    aggregation("sum", field, filterable, mayBeFrom, mayBeTo)
  }
  private def avg(field: String,
                  filterable: Option[Filterable],
                  mayBeFrom: Option[DateTime],
                  mayBeTo: Option[DateTime])(
                   implicit ec: ExecutionContext
                 ): Future[JsObject] = {
    aggregation("avg", field, filterable, mayBeFrom, mayBeTo)
  }

  private def aggregation(operation: String,
                          field: String,
                          filterable: Option[Filterable],
                          mayBeFrom: Option[DateTime],
                          mayBeTo: Option[DateTime])(implicit ec: ExecutionContext): Future[JsObject] = {
    query(
      Json.obj(
        "size" -> 0,
        "query" -> Json.obj(
          "bool" -> filters(filterable, mayBeFrom, mayBeTo)
        ),
        "aggs" -> Json.obj {
          operation -> Json.obj(
            operation -> Json.obj(
              "field" -> field
            )
          )
        }
      )
    ).map { res =>
      Json.obj(
        field -> (res \ "aggregations" \ operation \ "value").asOpt[JsValue]
      )
    }
  }

  def piechart(field: String,
               filterable: Option[Filterable],
               from: Option[DateTime],
               to: Option[DateTime],
               size: Int = 200,
               additionalFilters: Seq[JsObject] = Seq.empty)(
                implicit env: Env,
                ec: ExecutionContext
              ): Future[JsValue] = {
    query(
      Json.obj(
        "size" -> 0,
        "query" -> Json.obj(
          "bool" -> filters(filterable, from, to, additionalMust = additionalFilters)
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
      )
    ).map { json =>
      val pie = (json \ "aggregations" \ "codes" \ "buckets")
        .asOpt[Seq[JsObject]]
        .getOrElse(Seq.empty)
        .map { o =>
          Json.obj(
            "name" -> s"${(o \ "key").as[JsValue]}",
            "y"    -> (o \ "doc_count").asOpt[JsValue]
          )
        }
      Json.obj(
        "name"       -> "Pie Chart",
        "colorPoint" -> true,
        "series" -> Json.arr(
          Json.obj(
            "data" -> JsArray(pie)
          )
        )
      )
    }
  }

  private def dateFilters(mayBeFrom: Option[DateTime], mayBeTo: Option[DateTime]): Seq[JsObject] = {
    val to = mayBeTo.getOrElse(DateTime.now())
    //val formatter = ISODateTimeFormat.dateHourMinuteSecondMillis()
    val rangeCriteria = mayBeFrom
      .map { from =>
        Json.obj(
          "lte" -> to.getMillis,
          "gte" -> from.getMillis
        )
      }
      .getOrElse {
        Json.obj(
          "lte" -> to.getMillis
        )
      }

    val rangeQuery = Json.obj(
      "range" -> Json.obj(
        "@timestamp" -> rangeCriteria
      )
    )

    Seq(
      Some(rangeQuery)
    ).flatten
  }

  private def gatewayEventFilters = Seq(Json.obj("term" -> Json.obj("@type" -> "GatewayEvent")))
  private def healthCheckEventFilters = Seq(Json.obj("term" -> Json.obj("@type" -> "HealthCheckEvent")))

  private def filters(filterable: Option[Filterable],
                      mayBeFrom: Option[DateTime],
                      mayBeTo: Option[DateTime],
                      additionalMust: Seq[JsObject] = Seq.empty,
                      additionalShould: Seq[JsObject] = Seq.empty,
                      eventFilter: Seq[JsObject] = gatewayEventFilters): JsObject = {

    // val serviceQuery = service.map { s =>
    //   Json.obj(
    //     "term" -> Json.obj(
    //       "@serviceId" -> s.id
    //     )
    //   )
    // }
    // dateFilters(mayBeFrom, mayBeTo) ++
    // gatewayEventFilters ++
    // serviceQuery.toSeq

    filterable match {
      case None => {
        Json.obj(
          "must" -> (
            dateFilters(mayBeFrom, mayBeTo) ++
              eventFilter ++
              additionalMust
            )
        )
      }
      case Some(ServiceGroupFilterable(group)) => {
        Json.obj(
          "minimum_should_match" -> 1,
          "should" -> (
            Seq(
              Json.obj("term" -> Json.obj("descriptor.groups"     -> group.id)),
              Json.obj("term" -> Json.obj("descriptor.groups.raw" -> group.id))
            ) ++
              additionalShould
            ),
          "must" -> (
            dateFilters(mayBeFrom, mayBeTo) ++
              eventFilter ++
              additionalMust
            )
        )
      }
      case Some(ApiKeyFilterable(apiKey)) => {
        Json.obj(
          "minimum_should_match" -> 1,
          "should" -> (
            Seq(
              // Json.obj("term" -> Json.obj("identity.identityType.raw"     -> "APIKEY")),
              Json.obj("term" -> Json.obj("identity.identity"     -> apiKey.clientId)),
              Json.obj("term" -> Json.obj("identity.identity.raw" -> apiKey.clientId))
            ) ++
              additionalShould
            ),
          "must" -> (
            dateFilters(mayBeFrom, mayBeTo) ++
              eventFilter ++
              additionalMust
            )
        )
      }
      case Some(ServiceDescriptorFilterable(service)) => {
        Json.obj(
          "minimum_should_match" -> 1,
          "should" -> (
            Seq(
              Json.obj("term" -> Json.obj("@serviceId"     -> service.id)),
              Json.obj("term" -> Json.obj("@serviceId.raw" -> service.id))
            ) ++
              additionalShould
            ),
          "must" -> (
            dateFilters(mayBeFrom, mayBeTo) ++
              eventFilter ++
              additionalMust
            )
        )
      }
    }
  }

  override def fetchServicesStatus(servicesDescriptors: Seq[ServiceDescriptor],
                                  from: Option[DateTime],
                                  to: Option[DateTime])(
                                   implicit env: Env,
                                   ec: ExecutionContext): Future[Option[JsValue]] = {
    val extendedBounds = from
      .map { from =>
        Json.obj(
          "max" -> to.getOrElse(DateTime.now).getMillis,
          "min" -> from.getMillis
        )
      }
      .getOrElse {
        Json.obj(
          "max" -> to.getOrElse(DateTime.now).getMillis
        )
      }
    query(Json.obj(
      "query" -> Json.obj(
        "bool" -> filters(None, from, to, eventFilter = healthCheckEventFilters,
          additionalMust = Seq(Json.obj("terms" -> Json.obj("@serviceId" -> JsArray(servicesDescriptors.map(d => JsString(d.id))))))
        )),
      "aggs" -> Json.obj(
        "services" -> Json.obj(
          "terms" -> Json.obj(
            "field" -> "@serviceId"
          ),
          "aggs" -> Json.obj(
            "date" -> Json.obj(
              "date_histogram" -> Json.obj(
                "field" -> "@timestamp",
                "interval" -> "day",
                "format" -> "yyyy-MM-dd",
                "min_doc_count" -> 0,
                "extended_bounds" -> extendedBounds
              ),
              "aggs" -> Json.obj(
                "status" -> Json.obj(
                  "terms" -> Json.obj(
                    "field" -> "health"
                  )
                )
              )
            )
          )
        )
      )
    ))
      .map { json =>
        (json \ "aggregations" \ "services" \ "buckets")
          .asOpt[JsArray]
          .map { services =>
            JsArray(services.value.map(service => {
              val id = (service \ "key").as[String]
              val total_period = (service \ "doc_count").as[Float]
              val dates = (service \ "date" \ "buckets")
                .as[JsArray]
                .value
                .map(date => {
                  val timestamp = (date \ "key").as[Float]
                  val hrDate = (date \ "key_as_string").as[String]
                  val total_day = (date \ "doc_count").as[Float]
                  val status = (date \ "status" \ "buckets")
                    .as[JsArray]
                    .value
                    .map(h => {
                      val value = (h \ "key").as[String]
                      val count = (h \ "doc_count").as[Float]
                      val percentage = (count / total_day) * 100
                      Json.obj("health" -> value, "total" -> count, "percentage" -> percentage)
                    })

                  Json.obj("date" -> timestamp, "dateAsString" -> hrDate, "total" -> total_day, "status" -> JsArray(status))
                })

              val name = servicesDescriptors.find(_.id == id).map(_.name).getOrElse("UNKNOWN")
              val env = servicesDescriptors.find(_.id == id).map(_.env).getOrElse("prod")
              Json.obj("descriptor" -> id, "service" -> name, "line" -> env,  "total" -> total_period, "dates" -> JsArray(dates))
            }))
          }
      }
  }

  override def fetchServiceResponseTime(servicesDescriptor: ServiceDescriptor,
                                   from: Option[DateTime],
                                   to: Option[DateTime])(
                                    implicit env: Env,
                                    ec: ExecutionContext): Future[Option[JsValue]] = {
    val extendedBounds = from
      .map { from =>
        Json.obj(
          "max" -> to.getOrElse(DateTime.now).getMillis,
          "min" -> from.getMillis
        )
      }
      .getOrElse {
        Json.obj(
          "max" -> to.getOrElse(DateTime.now).getMillis
        )
      }

    query(Json.obj(
      "query" -> Json.obj(
        "bool" -> filters(None, from, to, eventFilter = healthCheckEventFilters,
          additionalMust = Seq(Json.obj("term" -> Json.obj("@serviceId" -> Json.obj("value" -> servicesDescriptor.id))))
        )),
      "aggs" -> Json.obj(
        "dates" -> Json.obj(
          "date_histogram" -> Json.obj(
            "field" -> "@timestamp",
            "interval" -> "hour",
            "format" -> "yyyy-MM-dd",
            "min_doc_count" -> 0,
            "extended_bounds" -> extendedBounds
          ),
          "aggs" -> Json.obj(
            "duration" -> Json.obj(
              "avg" -> Json.obj(
                "field" -> "duration"
              )
            )
          )
        )
      )
    ))
      .map { json =>
        (json \ "aggregations" \ "dates" \ "buckets")
          .asOpt[JsArray]
          .map(_.value)
          .map(dates => JsArray(dates.map(date => {
            val timestamp = (date \ "key").as[Float]
            val duration = (date \ "duration" \ "value").asOpt[Float]

            Json.obj("timestamp" -> timestamp, "duration" -> duration)
          })))
      }
  }
}
