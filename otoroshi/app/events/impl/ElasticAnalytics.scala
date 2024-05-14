package otoroshi.events.impl

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Sink, Source}
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.models.{ApiKey, ElasticAnalyticsConfig, IndexSettingsInterval, ServiceDescriptor, ServiceGroup}
import org.joda.time.format.{DateTimeFormatterBuilder, ISODateTimeFormat}
import org.joda.time.{DateTime, Interval}
import otoroshi.jobs.updates.Version
import otoroshi.next.models.NgRoute
import otoroshi.utils.cache.types.UnboundedTrieMap
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSRequest}
import play.api.{Environment, Logger}
import otoroshi.utils.syntax.implicits._

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object QueryResponse {
  val empty: QueryResponse = QueryResponse(Json.obj())
}

case class QueryResponse(resp: JsValue) {
  lazy val countOpt: Option[Int]   = (resp \ "count").asOpt[Int].orElse((resp \ "count" \ "value").asOpt[Int])
  lazy val count: Int              = countOpt.getOrElse(0)
  lazy val hitSizeOpt: Option[Int] =
    (resp \ "hits" \ "total").asOpt[Int].orElse((resp \ "hits" \ "total" \ "value").asOpt[Int])
  lazy val hitSize: Int            = hitSizeOpt.getOrElse(0)
  lazy val isEmpty: Boolean        = hitSize <= 0
  lazy val nonEmpty: Boolean       = !isEmpty
  lazy val hits: Seq[JsValue]      = (resp \ "hits" \ "hits").as[Seq[JsValue]]
}
// TODO: handle issue: "mapper [route.plugins.config.version] cannot be changed from type [text] to [long]
object ElasticTemplates                 {
  val indexTemplate_v6 =
    """{
      |  "template": "$$$INDEX$$$-*",
      |  "settings": {
      |    "number_of_shards": $$$SHARDS$$$,
      |    "number_of_replicas": $$$REPLICAS$$$,
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
      |    "number_of_shards": $$$SHARDS$$$,
      |    "number_of_replicas": $$$REPLICAS$$$,
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
      |      },
      |      "identity": {
      |        "properties": {
      |          "label": {
      |            "type": "keyword"
      |          },
      |          "identityType": {
      |            "type": "keyword"
      |          },
      |          "identity": {
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
      |    "number_of_shards": $$$SHARDS$$$,
      |    "number_of_replicas": $$$REPLICAS$$$,
      |    "index.mapping.ignore_malformed": true,
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
      |      },
      |      {
      |        "version_text_template": {
      |          "path_match": "route.plugins.config.version",
      |          "mapping": {
      |            "type": "text"
      |          },
      |          "match_mapping_type": "string"
      |        }
      |      },
      |      {
      |        "version_float_template": {
      |          "path_match": "route.plugins.config.version",
      |          "mapping": {
      |            "type": "float"
      |          },
      |          "match_mapping_type": "string"
      |        }
      |      },
      |      {
      |        "version_long_template": {
      |          "path_match": "route.plugins.config.version",
      |          "mapping": {
      |            "type": "long"
      |          },
      |          "match_mapping_type": "string"
      |        }
      |      },
      |      {
      |        "version_integer_template": {
      |          "path_match": "route.plugins.config.version",
      |          "mapping": {
      |            "type": "integer"
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
      |      },
      |      "otoroshiHeadersIn": {
      |        "properties": {
      |          "key": {
      |            "type": "keyword"
      |          },
      |          "value": {
      |            "type": "keyword"
      |          }
      |        }
      |      },
      |      "otoroshiHeadersOut": {
      |        "properties": {
      |          "key": {
      |            "type": "keyword"
      |          },
      |          "value": {
      |            "type": "keyword"
      |          }
      |        }
      |      },
      |      "identity": {
      |        "properties": {
      |          "label": {
      |            "type": "keyword"
      |          },
      |          "identityType": {
      |            "type": "keyword"
      |          },
      |          "identity": {
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

  val clusterInitializedCache = new UnboundedTrieMap[String, (Boolean, ElasticVersion)]()

  def toKey(config: ElasticAnalyticsConfig): String = {
    val index: String  = config.index.getOrElse("otoroshi-events")
    val `type`: String = config.`type`.getOrElse("event")
    s"${config.uris.mkString("-")}/$index/${`type`}"
  }

  def initialized(config: ElasticAnalyticsConfig, version: ElasticVersion): Unit = {
    clusterInitializedCache.putIfAbsent(toKey(config), (true, version))
  }

  def isInitialized(config: ElasticAnalyticsConfig): (Boolean, ElasticVersion) = {
    clusterInitializedCache.getOrElse(
      toKey(config), {
        config.version match {
          case Some(rawVersion) => {
            val version = Version(rawVersion) match {
              case v if v.isAfterEq(Version("8.0.0")) => ElasticVersion.AboveEight(rawVersion)
              case v if v.isBefore(Version("7.0.0"))  => ElasticVersion.UnderSeven(rawVersion)
              case v if v.isAfterEq(Version("7.8.0")) => ElasticVersion.AboveSevenEight(rawVersion)
              case v if v.isAfterEq(Version("7.0.0")) => ElasticVersion.AboveSeven(rawVersion)
              case _                                  => ElasticVersion.AboveSeven(rawVersion)
            }
            (false, version)
          }
          case None             => (false, ElasticVersion.default)
        }
      }
    )
  }
}

sealed trait ElasticVersion {
  def underSeven: Boolean
  def underEight: Boolean
  def aboveOrEqualsEight: Boolean
}
object ElasticVersion       {

  case class UnderSeven(raw: String)      extends ElasticVersion {
    def underSeven: Boolean         = true
    def underEight: Boolean         = true
    def aboveOrEqualsEight: Boolean = false
  }
  case class AboveSeven(raw: String)      extends ElasticVersion {
    def underSeven: Boolean         = false
    def underEight: Boolean         = true
    def aboveOrEqualsEight: Boolean = false
  }
  case class AboveSevenEight(raw: String) extends ElasticVersion {
    def underSeven: Boolean         = false
    def underEight: Boolean         = true
    def aboveOrEqualsEight: Boolean = false
  }
  case class AboveEight(raw: String)      extends ElasticVersion {
    def underSeven: Boolean         = false
    def underEight: Boolean         = false
    def aboveOrEqualsEight: Boolean = true
  }

  val defaultStr: String      = "6.0.0"
  val default: ElasticVersion = UnderSeven(defaultStr)
}

object ElasticUtils {

  import otoroshi.utils.http.Implicits._

  // private def indexUri: String = {
  //   val df = ISODateTimeFormat.date().print(DateTime.now())
  //   urlFromPath(s"/$index-$df/${`type`}")
  // }

  private val counter = new AtomicLong(1L)

  def urlFromPath(path: String, config: ElasticAnalyticsConfig): String = {
    if (config.uris.size == 1) {
      s"${config.uris.head}$path"
    } else {
      val index = counter.incrementAndGet() % (if (config.uris.nonEmpty) config.uris.size else 1)
      val uri   = config.uris.apply(index.toInt)
      s"${uri}$path"
    }
  }

  def authHeader(config: ElasticAnalyticsConfig): Option[String] = {
    for {
      user     <- config.user
      password <- config.password
    } yield s"Basic ${Base64.getEncoder.encodeToString(s"$user:$password".getBytes())}"
  }

  def url(url: String, config: ElasticAnalyticsConfig, env: Env)(implicit ec: ExecutionContext): WSRequest = {
    val builder =
      env.MtlsWs
        .url(url, config.mtlsConfig)
        .withMaybeProxyServer(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.elastic))
    authHeader(config)
      .fold(builder) { h =>
        builder.withHttpHeaders("Authorization" -> h)
      }
      .addHttpHeaders(config.headers.toSeq: _*)
  }

  def getElasticVersion(config: ElasticAnalyticsConfig, logger: Logger, env: Env)(implicit
      ec: ExecutionContext
  ): Future[ElasticVersion] = {

    import otoroshi.jobs.updates.Version

    config.version match {
      case Some(version) => {
        (Version(version) match {
          case v if v.isAfterEq(Version("8.0.0")) => ElasticVersion.AboveEight(version)
          case v if v.isBefore(Version("7.0.0"))  => ElasticVersion.UnderSeven(version)
          case v if v.isAfterEq(Version("7.8.0")) => ElasticVersion.AboveSevenEight(version)
          case v if v.isAfterEq(Version("7.0.0")) => ElasticVersion.AboveSeven(version)
          case _                                  => ElasticVersion.AboveSeven(version)
        }).future
      }
      case None          => {
        ElasticUtils
          .checkVersion(config, logger, env)
          .map {
            case Left(err) => ElasticVersion.default
            case Right(_v) => {
              Version(_v) match {
                case v if v.isAfterEq(Version("8.0.0")) => ElasticVersion.AboveEight(_v)
                case v if v.isBefore(Version("7.0.0"))  => ElasticVersion.UnderSeven(_v)
                case v if v.isAfterEq(Version("7.8.0")) => ElasticVersion.AboveSevenEight(_v)
                case v if v.isAfterEq(Version("7.0.0")) => ElasticVersion.AboveSeven(_v)
                case _                                  => ElasticVersion.AboveSeven(_v)
              }
            }
          }

        //url(urlFromPath("", config), config, env)
        //  .get()
        //  .map(_.json)
        //  .map(json => (json \ "version" \ "number").asOpt[String].orElse(config.version).getOrElse(ElasticVersion.defaultStr))
        //  // .map(v => v.split("\\.").headOption.map(_.toInt).getOrElse(6))
        //  .map { _v =>
        //    Version(_v) match {
        //      case v if v.isBefore(Version("7.0.0"))  => ElasticVersion.UnderSeven(_v)
        //      case v if v.isAfterEq(Version("7.8.0")) => ElasticVersion.AboveSevenEight(_v)
        //      case v if v.isAfterEq(Version("7.0.0")) => ElasticVersion.AboveSeven(_v)
        //      case _                                  => ElasticVersion.AboveSeven(_v)
        //    }
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
      //}
    }
  }

  def applyTemplate(config: ElasticAnalyticsConfig, logger: Logger, env: Env)(implicit
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Unit] = {
    val index: String            = config.index.getOrElse("otoroshi-events")
    val numberOfShards: String   = config.indexSettings.numberOfShards.toString
    val numberOfReplicas: String = config.indexSettings.numberOfReplicas.toString
    getElasticVersion(config, logger, env).flatMap { version =>
      // from elastic 7.8, we should use /_index_template/otoroshi-tpl and wrap almost everything expect index_patterns in a "template" object
      val (strTpl, indexTemplatePath) = version match {
        case ElasticVersion.UnderSeven(_)      => (ElasticTemplates.indexTemplate_v6, "/_template/otoroshi-tpl")
        case ElasticVersion.AboveSeven(_)      => (ElasticTemplates.indexTemplate_v7, "/_template/otoroshi-tpl")
        case ElasticVersion.AboveSevenEight(_) =>
          (ElasticTemplates.indexTemplate_v7_8, "/_index_template/otoroshi-tpl")
        case ElasticVersion.AboveEight(_)      =>
          (ElasticTemplates.indexTemplate_v7_8, "/_index_template/otoroshi-tpl")
      }
      if (logger.isDebugEnabled) logger.debug(s"$version, $indexTemplatePath")
      val tpl: JsValue                = if (config.indexSettings.clientSide) {
        Json.parse(
          strTpl
            .replace("$$$INDEX$$$", index)
            .replace("$$$SHARDS$$$", numberOfShards)
            .replace("$$$REPLICAS$$$", numberOfReplicas)
        )
      } else {
        Json.parse(
          strTpl
            .replace("$$$INDEX$$$-*", index)
            .replace("$$$SHARDS$$$", numberOfShards)
            .replace("$$$REPLICAS$$$", numberOfReplicas)
        )
      }
      if (logger.isDebugEnabled) logger.debug(s"Creating otoroshi template with \n${Json.prettyPrint(tpl)}")
      url(urlFromPath(indexTemplatePath, config), config, env)
        .get()
        .flatMap { resp =>
          resp.status match {
            case 200 =>
              // TODO: check if same ???
              resp.ignore()
              val tplCreated = url(urlFromPath(indexTemplatePath, config), config, env).put(tpl)
              tplCreated.onComplete {
                case Success(r) if r.status >= 400 =>
                  logger.error(s"Error creating template ${r.status}: ${r.body}")
                case Failure(e)                    =>
                  logger.error("Error creating template", e)
                case Success(r)                    =>
                  r.ignore()
                  if (logger.isDebugEnabled) logger.debug("Otoroshi template updated")
                  ElasticWritesAnalytics.initialized(config, version)
                case _                             =>
                  if (logger.isDebugEnabled) logger.debug("Otoroshi template updated")
                  ElasticWritesAnalytics.initialized(config, version)
              }
              tplCreated.map(_ => ())
            case 404 =>
              resp.ignore()
              val tplCreated = url(urlFromPath(indexTemplatePath, config), config, env).post(tpl)
              tplCreated.onComplete {
                case Success(r) if r.status >= 400 =>
                  logger.error(s"Error creating template ${r.status}: ${r.body}")
                case Failure(e)                    =>
                  logger.error("Error creating template", e)
                case Success(r)                    =>
                  r.ignore()
                  if (logger.isDebugEnabled) logger.debug("Otoroshi template created")
                  ElasticWritesAnalytics.initialized(config, version)
                case _                             =>
                  if (logger.isDebugEnabled) logger.debug("Otoroshi template created")
                  ElasticWritesAnalytics.initialized(config, version)
              }
              tplCreated.map(_ => ())
            case _   =>
              logger.error(s"Error creating template ${resp.status}: ${resp.body}")
              FastFuture.successful(())
          }
        }
    }
  }

  def checkAvailability(config: ElasticAnalyticsConfig, env: Env)(implicit
      ec: ExecutionContext
  ): Future[Either[JsValue, JsValue]] = {
    url(urlFromPath("/_cluster/health", config), config, env)
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right(resp.json)
        } else {
          Left(resp.json)
        }
      }
  }

  def checkVersion(config: ElasticAnalyticsConfig, logger: Logger, env: Env)(implicit
      ec: ExecutionContext
  ): Future[Either[JsValue, String]] = {
    url(urlFromPath("", config), config, env)
      .get()
      .map { resp =>
        if (resp.status == 200) {
          val version =
            (resp.json \ "version" \ "number").asOpt[String].orElse(config.version).getOrElse(ElasticVersion.defaultStr)
          if (logger.isDebugEnabled) logger.debug(s"elastic version from server is: ${version}")
          Right(version)
        } else {
          logger.error(s"error while fetching elastic version: ${resp.status} - ${resp.json}")
          Left(resp.json)
        }
      }
  }

  def checkSearch(config: ElasticAnalyticsConfig, env: Env)(implicit
      ec: ExecutionContext
  ): Future[Either[JsValue, Long]] = {
    val countUrl = config.index match {
      case None                                           => urlFromPath(s"/_count", config)
      case Some(index) if config.indexSettings.clientSide => urlFromPath(s"/${index}*/_count", config)
      case Some(index)                                    => urlFromPath(s"/${index}/_count", config)
    }
    url(countUrl, config, env)
      .get()
      .map { resp =>
        if (resp.status == 200) {
          Right((resp.json \ "count").asOpt[Long].getOrElse(0L))
        } else {
          Left(resp.json)
        }
      }
  }
}

class ElasticWritesAnalytics(config: ElasticAnalyticsConfig, env: Env) extends AnalyticsWritesService {

  import otoroshi.utils.http.Implicits._

  lazy val logger = Logger("otoroshi-analytics-writes-elastic")

  private val environment: Environment           = env.environment
  private val executionContext: ExecutionContext = env.analyticsExecutionContext
  private val system: ActorSystem                = env.analyticsActorSystem

  private def urlFromPath(path: String): String = ElasticUtils.urlFromPath(path, config)
  private val index: String                     = config.index.getOrElse("otoroshi-events")
  private val `type`: String                    = config.`type`.getOrElse("event")
  private implicit val mat                      = Materializer(system)

  if (config.applyTemplate) {
    init()
  } else {
    initializeVersionWithoutTemplate()
  }

  def initializeVersionWithoutTemplate(): Unit = {
    if (ElasticWritesAnalytics.isInitialized(config)._1) {
      ()
    } else {
      implicit val ec = env.otoroshiExecutionContext
      config.version match {
        case Some(versionRaw) => {
          val version = Version(versionRaw) match {
            case v if v.isAfterEq(Version("8.0.0")) => ElasticVersion.AboveEight(versionRaw)
            case v if v.isBefore(Version("7.0.0"))  => ElasticVersion.UnderSeven(versionRaw)
            case v if v.isAfterEq(Version("7.8.0")) => ElasticVersion.AboveSevenEight(versionRaw)
            case v if v.isAfterEq(Version("7.0.0")) => ElasticVersion.AboveSeven(versionRaw)
            case _                                  => ElasticVersion.AboveSeven(versionRaw)
          }
          ElasticWritesAnalytics.initialized(config, version)
        }
        case None             => {
          ElasticUtils.checkVersion(config, logger, env).map {
            case Left(err)         => ElasticWritesAnalytics.initialized(config, ElasticVersion.default)
            case Right(versionRaw) => {
              val version = Version(versionRaw) match {
                case v if v.isAfterEq(Version("8.0.0")) => ElasticVersion.AboveEight(versionRaw)
                case v if v.isBefore(Version("7.0.0"))  => ElasticVersion.UnderSeven(versionRaw)
                case v if v.isAfterEq(Version("7.8.0")) => ElasticVersion.AboveSevenEight(versionRaw)
                case v if v.isAfterEq(Version("7.0.0")) => ElasticVersion.AboveSeven(versionRaw)
                case _                                  => ElasticVersion.AboveSeven(versionRaw)
              }
              ElasticWritesAnalytics.initialized(config, version)
            }
          }
        }
      }
    }
  }

  override def init(): Unit = {
    if (ElasticWritesAnalytics.isInitialized(config)._1) {
      ()
    } else {
      implicit val ec: ExecutionContext = executionContext
      logger.info(
        s"Creating Otoroshi template for $index on es cluster at ${config.uris.map(uri => s"$uri/$index/${`type`}").mkString(", ")}"
      )
      // AWAIT: valid
      Await.result(
        ElasticUtils.applyTemplate(config, logger, env).recover { case t: Throwable =>
          logger.error("error during elasticsearch initialization", t)
        },
        5.second
      )
    }
  }

  private def bulkRequest(source: JsValue): String = {
    val version: ElasticVersion = ElasticWritesAnalytics.isInitialized(config)._2
    val df                      = if (config.indexSettings.clientSide) {
      config.indexSettings.interval match {
        case IndexSettingsInterval.Day   => ISODateTimeFormat.date().print(DateTime.now())
        case IndexSettingsInterval.Week  => ISODateTimeFormat.weekyearWeek().print(DateTime.now())
        case IndexSettingsInterval.Month => ISODateTimeFormat.yearMonth().print(DateTime.now())
        case IndexSettingsInterval.Year  => ISODateTimeFormat.year().print(DateTime.now())
        case _                           => ISODateTimeFormat.date().print(DateTime.now())
      }
    } else {
      ""
    }
    val indexWithDate           = if (config.indexSettings.clientSide) s"$index-$df" else index
    val indexClause             = Json.stringify(
      Json.obj(
        "index" -> Json
          .obj("_index" -> indexWithDate)
          .applyOnIf(version.underSeven)(_ ++ Json.obj("_type" -> `type`))
      )
    )
    val sourceClause            = Json.stringify(source)
    s"$indexClause\n$sourceClause"
  }

  override def publish(event: Seq[JsValue])(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val builder = env.MtlsWs
      .url(urlFromPath("/_bulk"), config.mtlsConfig)
      .withMaybeProxyServer(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.elastic))

    val clientInstance = ElasticUtils
      .authHeader(config)
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
      .grouped(config.maxBulkSize)
      .map(_.map(bulkRequest))
      .mapAsync(config.sendWorkers) { bulk =>
        val body = bulk.mkString("", "\n", "\n\n").byteString
        if (logger.isDebugEnabled) logger.debug(s"preparing bulk of ${bulk.size} items of size ${body.size} bytes")
        val req  = clientInstance.withMethod("POST").withBody(body)
        val post = req.execute()
        post.onComplete {
          case Success(resp) =>
            if (resp.status >= 400) {
              logger.error(s"Error publishing event to elastic: ${resp.status}, ${resp.body}") // --- event: $event")
            } else {
              val esResponse = Json.parse(resp.body)
              val esError    = (esResponse \ "errors").asOpt[Boolean].getOrElse(false)
              if (esError) {
                logger.error(s"An error occured in ES bulk: ${resp.status}, ${Json.prettyPrint(esResponse)}")
              }
              resp.ignore()
            }
          case Failure(e)    =>
            logger.error(s"Error publishing event to elastic", e)
        }
        post
      }
      .runWith(Sink.ignore)
      .map(_ => ())
  }
}

object ElasticReadsAnalytics {
  private val cache = new UnboundedTrieMap[String, ElasticVersion]()
  def getElasticVersion(config: ElasticAnalyticsConfig, logger: Logger)(implicit env: Env): ElasticVersion = {
    val key: String = config.uris.mkString(",")
    cache.get(key) match {
      case Some(version) =>
        version
      case None          => {
        val version =
          Await.result(ElasticUtils.getElasticVersion(config, logger, env)(env.otoroshiExecutionContext), 30.seconds)
        cache.putIfAbsent(key, version)
        version
      }
    }
  }
}

class ElasticReadsAnalytics(config: ElasticAnalyticsConfig, env: Env) extends AnalyticsReadsService {

  private val executionContext: ExecutionContext = env.analyticsExecutionContext
  private val system: ActorSystem                = env.analyticsActorSystem

  private def urlFromPath(path: String): String = ElasticUtils.urlFromPath(path, config)
  private val `type`: String                    = config.`type`.getOrElse("type")
  private val index: String                     = config.index.getOrElse("otoroshi-events")
  private val searchUri                         =
    if (config.indexSettings.clientSide) urlFromPath(s"/$index*/_search") else urlFromPath(s"/$index/_search")
  private val countUri                          =
    if (config.indexSettings.clientSide) urlFromPath(s"/$index*/_count") else urlFromPath(s"/$index/_count")
  private implicit val mat                      = Materializer(system)

  lazy val logger = Logger("otoroshi-analytics-reads-elastic")

  private lazy val version = ElasticReadsAnalytics.getElasticVersion(config, logger)(env)

  private def authHeader(): Option[String] = ElasticUtils.authHeader(config)

  private def url(url: String): WSRequest = ElasticUtils.url(url, config, env)(env.analyticsExecutionContext)

  def checkAvailability()(implicit ec: ExecutionContext): Future[Either[JsValue, JsValue]] =
    ElasticUtils.checkAvailability(config, env)

  def checkSearch()(implicit ec: ExecutionContext): Future[Either[JsValue, Long]] =
    ElasticUtils.checkSearch(config, env)

  def checkVersion()(implicit ec: ExecutionContext): Future[Either[JsValue, String]] =
    ElasticUtils.checkVersion(config, logger, env)

  def getElasticVersion()(implicit ec: ExecutionContext): Future[ElasticVersion] =
    ElasticUtils.getElasticVersion(config, logger, env)

  private def query(query: JsObject, debug: Boolean = false)(implicit ec: ExecutionContext): Future[QueryResponse] = {
    val builder = env.MtlsWs.url(searchUri, config.mtlsConfig)
    if (logger.isDebugEnabled) logger.debug(s"Query to Elasticsearch: $searchUri")
    if (logger.isDebugEnabled) logger.debug(s"Query to Elasticsearch: ${Json.prettyPrint(query)}")

    authHeader()
      .fold(builder) { h =>
        builder.withHttpHeaders("Authorization" -> h)
      }
      .addHttpHeaders(config.headers.toSeq: _*)
      .post(query)
      .flatMap { resp =>
        resp.status match {
          case 200 => FastFuture.successful(QueryResponse(resp.json))
          case _   =>
            FastFuture.failed(
              new RuntimeException(s"Error during es request: \n * ${resp.body}, \nquery was \n * $query")
            )
        }
      }
  }

  private def count(query: JsObject, debug: Boolean = false)(implicit ec: ExecutionContext): Future[QueryResponse] = {
    val builder = env.MtlsWs.url(countUri, config.mtlsConfig)
    if (logger.isDebugEnabled) logger.debug(s"Query to Elasticsearch: $countUri")
    if (logger.isDebugEnabled) logger.debug(s"Query to Elasticsearch: ${Json.prettyPrint(query)}")

    authHeader()
      .fold(builder) { h =>
        builder.withHttpHeaders("Authorization" -> h)
      }
      .addHttpHeaders(config.headers.toSeq: _*)
      .post(query)
      .flatMap { resp =>
        resp.status match {
          case 200 => FastFuture.successful(QueryResponse(resp.json))
          case _   =>
            FastFuture.failed(
              new RuntimeException(s"Error during es request: \n * ${resp.body}, \nquery was \n * $query")
            )
        }
      }
  }

  override def fetchHits(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] = {
    for {
      res <- count(
               Json.obj(
                 "query" -> Json.obj(
                   "bool" -> filters(filterable, from, to, raw = false)
                 )
               )
             )
    } yield {
      Json
        .obj(
          "count" -> res.count
        )
        .some
    }
  }

  override def events(
      eventType: String,
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime],
      page: Int,
      size: Int,
      order: String = "desc"
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] = {
    val pageFrom = (page - 1) * size
    for {
      noraw <- query(
                 Json.obj(
                   "size"  -> size,
                   "from"  -> pageFrom,
                   "query" -> Json.obj(
                     "bool" -> filters(filterable, from, to, raw = false)
                   ),
                   "sort"  -> Json.obj(
                     "@timestamp" -> Json.obj(
                       "order" -> order
                     )
                   )
                 )
               )
      raw   <- if (noraw.isEmpty)
                 query(
                   Json.obj(
                     "size"  -> size,
                     "from"  -> pageFrom,
                     "query" -> Json.obj(
                       "bool" -> filters(filterable, from, to, raw = true)
                     ),
                     "sort"  -> Json.obj(
                       "@timestamp" -> Json.obj(
                         "order" -> order
                       )
                     )
                   )
                 )
               else QueryResponse.empty.vfuture
    } yield {
      val hits                 = if (noraw.isEmpty) raw.hits else noraw.hits
      val events: Seq[JsValue] = hits.map { j =>
        (j \ "_source").as[JsValue]
      }
      Json
        .obj(
          "events" -> events
        )
        .some
    }
  }

  override def fetchDataIn(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    sum("data.dataIn", filterable, from, to).map(Some.apply)

  override def fetchDataOut(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    sum("data.dataOut", filterable, from, to).map(Some.apply)

  override def fetchAvgDuration(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    avg("duration", filterable, from, to).map(Some.apply)

  override def fetchAvgOverhead(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    avg("overhead", filterable, from, to).map(Some.apply)

  override def fetchStatusesPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    piechart("status", filterable, from, to).map(Some.apply)

  override def fetchStatusesHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] = {
    for {
      noraw <- query(
                 Json.obj(
                   "size"  -> 0,
                   "query" -> Json.obj {
                     "bool" -> filters(filterable, from, to, raw = false)
                   },
                   "aggs"  -> Json.obj(
                     "codes" -> Json.obj(
                       "aggs"  -> Json.obj(
                         "codesOverTime" -> Json.obj(
                           "date_histogram" -> Json
                             .obj(
                               "field" -> "@timestamp"
                             )
                             .applyOnIf(version.underEight) { obj => obj ++ Json.obj("interval" -> "hour") }
                             .applyOnIf(version.aboveOrEqualsEight) { obj =>
                               obj ++ Json.obj("calendar_interval" -> "hour")
                             }
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
                         "field"  -> "status",
                         "keyed"  -> true
                       )
                     )
                   )
                 )
               )
      raw   <- if (noraw.isEmpty)
                 query(
                   Json.obj(
                     "size"  -> 0,
                     "query" -> Json.obj {
                       "bool" -> filters(filterable, from, to, raw = true)
                     },
                     "aggs"  -> Json.obj(
                       "codes" -> Json.obj(
                         "aggs"  -> Json.obj(
                           "codesOverTime" -> Json.obj(
                             "date_histogram" -> Json
                               .obj(
                                 "field" -> "@timestamp"
                               )
                               .applyOnIf(version.underEight) { obj => obj ++ Json.obj("interval" -> "hour") }
                               .applyOnIf(version.aboveOrEqualsEight) { obj =>
                                 obj ++ Json.obj("calendar_interval" -> "hour")
                               }
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
                           "field"  -> "status",
                           "keyed"  -> true
                         )
                       )
                     )
                   )
                 )
               else QueryResponse.empty.vfuture
    } yield {
      val res               = if (noraw.isEmpty) raw.resp else noraw.resp
      val buckets: JsObject = (res \ "aggregations" \ "codes" \ "buckets").asOpt[JsObject].getOrElse(Json.obj())
      val series            = buckets.value
        .map { case (k, v) =>
          Json.obj(
            "name"  -> k,
            "count" -> (v \ "doc_count").asOpt[Int],
            "data"  -> (v \ "codesOverTime" \ "buckets")
              .asOpt[Seq[JsValue]]
              .map(_.flatMap { j =>
                for {
                  k <- (j \ "key").asOpt[JsValue]
                  v <- (j \ "doc_count").asOpt[Int]
                } yield Json.arr(k, v)
              })
          )
        }
      Json
        .obj(
          "chart"  -> Json.obj("type" -> "areaspline"),
          "series" -> series
        )
        .some
    }
  }

  override def fetchDataInStatsHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    statsHistogram("data.dataIn", filterable, from, to).map(Some.apply)

  override def fetchDataOutStatsHistogram(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    statsHistogram("data.dataOut", filterable, from, to).map(Some.apply)

  override def fetchDurationStatsHistogram(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    statsHistogram("duration", filterable, from, to).map(Some.apply)

  override def fetchDurationPercentilesHistogram(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    percentilesHistogram("duration", filterable, from, to).map(Some.apply)

  override def fetchOverheadPercentilesHistogram(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    percentilesHistogram("overhead", filterable, from, to).map(Some.apply)

  override def fetchProductPiechart(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime],
      size: Int
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    piechart("@product", filterable, from, to).map(Some.apply)

  override def fetchApiKeyPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(
      implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    piechart(
      "identity.label.raw",
      filterable,
      from,
      to,
      additionalFilters = Seq(
        Json.obj(
          "term" -> Json.obj(
            "identity.identityType.raw" -> "APIKEY"
          )
        ),
        Json.obj(
          "term" -> Json.obj(
            "identity.identityType" -> "APIKEY"
          )
        )
      )
    ).map(Some.apply)

  override def fetchUserPiechart(filterable: Option[Filterable], from: Option[DateTime], to: Option[DateTime])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    piechart(
      "identity.label.raw",
      filterable,
      from,
      to,
      additionalFilters = Seq(
        Json.obj(
          "term" -> Json.obj(
            "identity.identityType.raw" -> "PRIVATEAPP"
          )
        )
      )
    ).map(Some.apply)

  override def fetchServicePiechart(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime],
      size: Int
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    piechart("@service", filterable, from, to).map(Some.apply)

  override def fetchOverheadStatsHistogram(
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] =
    statsHistogram("overhead", filterable, from, to).map(Some.apply)

  private def statsHistogram(
      field: String,
      filterable: Option[Filterable],
      mayBeFrom: Option[DateTime],
      mayBeTo: Option[DateTime]
  )(implicit ec: ExecutionContext): Future[JsObject] = {
    for {
      noraw <- query(
                 Json.obj(
                   "size"  -> 0,
                   "query" -> Json.obj(
                     "bool" -> filters(filterable, mayBeFrom, mayBeTo, raw = false)
                   ),
                   "aggs"  -> Json.obj(
                     "stats" -> Json.obj(
                       "date_histogram" -> Json
                         .obj(
                           "field" -> "@timestamp"
                         )
                         .applyOnIf(version.underEight) { obj =>
                           obj ++ Json.obj("interval" -> calcInterval(mayBeFrom, mayBeTo))
                         }
                         .applyOnIf(version.aboveOrEqualsEight) { obj =>
                           obj ++ Json.obj("calendar_interval" -> calcInterval(mayBeFrom, mayBeTo))
                         },
                       "aggs"           -> Json.obj(
                         "stats" -> Json.obj(
                           "extended_stats" -> Json.obj(
                             "field" -> field
                           )
                         )
                       )
                     )
                   )
                 )
               )
      raw   <- if (noraw.isEmpty)
                 query(
                   Json.obj(
                     "size"  -> 0,
                     "query" -> Json.obj(
                       "bool" -> filters(filterable, mayBeFrom, mayBeTo, raw = true)
                     ),
                     "aggs"  -> Json.obj(
                       "stats" -> Json.obj(
                         "date_histogram" -> Json
                           .obj(
                             "field" -> "@timestamp"
                           )
                           .applyOnIf(version.underEight) { obj =>
                             obj ++ Json.obj("interval" -> calcInterval(mayBeFrom, mayBeTo))
                           }
                           .applyOnIf(version.aboveOrEqualsEight) { obj =>
                             obj ++ Json.obj("calendar_interval" -> calcInterval(mayBeFrom, mayBeTo))
                           },
                         "aggs"           -> Json.obj(
                           "stats" -> Json.obj(
                             "extended_stats" -> Json.obj(
                               "field" -> field
                             )
                           )
                         )
                       )
                     )
                   )
                 )
               else QueryResponse.empty.vfuture
    } yield {
      val res    = if (noraw.isEmpty) raw.resp else noraw.resp
      val bucket = (res \ "aggregations" \ "stats" \ "buckets").asOpt[JsValue].getOrElse(JsNull)
      Json.obj(
        "chart"  -> Json.obj("type" -> "chart"),
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

  private def percentilesHistogram(
      field: String,
      filterable: Option[Filterable],
      mayBeFrom: Option[DateTime],
      mayBeTo: Option[DateTime]
  )(implicit ec: ExecutionContext): Future[JsObject] = {
    for {
      noraw <- query(
                 Json.obj(
                   "size"  -> 0,
                   "query" -> Json.obj(
                     "bool" -> filters(filterable, mayBeFrom, mayBeTo, raw = false)
                   ),
                   "aggs"  -> Json.obj(
                     "stats" -> Json.obj(
                       "date_histogram" -> Json
                         .obj(
                           "field" -> "@timestamp"
                         )
                         .applyOnIf(version.underEight) { obj =>
                           obj ++ Json.obj("interval" -> calcInterval(mayBeFrom, mayBeTo))
                         }
                         .applyOnIf(version.aboveOrEqualsEight) { obj =>
                           obj ++ Json.obj("calendar_interval" -> calcInterval(mayBeFrom, mayBeTo))
                         },
                       "aggs"           -> Json.obj(
                         "stats" -> Json.obj(
                           "percentiles" -> Json.obj(
                             "field" -> field
                           )
                         )
                       )
                     )
                   )
                 )
               )
      raw   <- if (noraw.isEmpty)
                 query(
                   Json.obj(
                     "size"  -> 0,
                     "query" -> Json.obj(
                       "bool" -> filters(filterable, mayBeFrom, mayBeTo, raw = true)
                     ),
                     "aggs"  -> Json.obj(
                       "stats" -> Json.obj(
                         "date_histogram" -> Json
                           .obj(
                             "field" -> "@timestamp"
                           )
                           .applyOnIf(version.underEight) { obj =>
                             obj ++ Json.obj("interval" -> calcInterval(mayBeFrom, mayBeTo))
                           }
                           .applyOnIf(version.aboveOrEqualsEight) { obj =>
                             obj ++ Json.obj("calendar_interval" -> calcInterval(mayBeFrom, mayBeTo))
                           },
                         "aggs"           -> Json.obj(
                           "stats" -> Json.obj(
                             "percentiles" -> Json.obj(
                               "field" -> field
                             )
                           )
                         )
                       )
                     )
                   )
                 )
               else QueryResponse.empty.vfuture
    } yield {
      val res    = if (noraw.isEmpty) raw.resp else noraw.resp
      val bucket = (res \ "aggregations" \ "stats" \ "buckets").asOpt[JsValue].getOrElse(JsNull)
      Json.obj(
        "chart"  -> Json.obj("type" -> "areaspline"),
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

  private def extractSerie(
      bucket: JsValue,
      name: String,
      extract: JsValue => JsValueWrapper,
      extra: JsObject = Json.obj()
  ): JsValue = {
    bucket match {
      case JsArray(array) =>
        Json.obj(
          "name" -> name,
          "data" -> array.map { j =>
            Json.arr(
              (j \ "key").as[JsValue],
              extract(j)
            )
          }
        ) ++ extra
      case _              => JsNull
    }
  }

  private def sum(
      field: String,
      filterable: Option[Filterable],
      mayBeFrom: Option[DateTime],
      mayBeTo: Option[DateTime]
  )(implicit
      ec: ExecutionContext
  ): Future[JsObject] = {
    aggregation("sum", field, filterable, mayBeFrom, mayBeTo)
  }
  private def avg(
      field: String,
      filterable: Option[Filterable],
      mayBeFrom: Option[DateTime],
      mayBeTo: Option[DateTime]
  )(implicit
      ec: ExecutionContext
  ): Future[JsObject] = {
    aggregation("avg", field, filterable, mayBeFrom, mayBeTo)
  }

  private def aggregation(
      operation: String,
      field: String,
      filterable: Option[Filterable],
      mayBeFrom: Option[DateTime],
      mayBeTo: Option[DateTime]
  )(implicit ec: ExecutionContext): Future[JsObject] = {
    for {
      noraw <- query(
                 Json.obj(
                   "size"  -> 0,
                   "query" -> Json.obj(
                     "bool" -> filters(filterable, mayBeFrom, mayBeTo, raw = false)
                   ),
                   "aggs"  -> Json.obj {
                     operation -> Json.obj(
                       operation -> Json.obj(
                         "field" -> field
                       )
                     )
                   }
                 )
               )
      raw   <- if (noraw.isEmpty)
                 query(
                   Json.obj(
                     "size"  -> 0,
                     "query" -> Json.obj(
                       "bool" -> filters(filterable, mayBeFrom, mayBeTo, raw = true)
                     ),
                     "aggs"  -> Json.obj {
                       operation -> Json.obj(
                         operation -> Json.obj(
                           "field" -> field
                         )
                       )
                     }
                   )
                 )
               else QueryResponse.empty.vfuture
    } yield {
      val res = if (noraw.isEmpty) raw.resp else noraw.resp
      Json.obj(
        field -> (res \ "aggregations" \ operation \ "value").asOpt[JsValue]
      )
    }
  }

  def piechart(
      field: String,
      filterable: Option[Filterable],
      from: Option[DateTime],
      to: Option[DateTime],
      size: Int = 200,
      additionalFilters: Seq[JsObject] = Seq.empty
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[JsValue] = {
    for {
      noraw <- query(
                 Json.obj(
                   "size"  -> 0,
                   "query" -> Json.obj(
                     "bool" -> filters(filterable, from, to, additionalMust = additionalFilters, raw = false)
                   ),
                   "aggs"  -> Json.obj(
                     "codes" -> Json.obj(
                       "terms" -> Json.obj(
                         "field" -> field,
                         "order" -> Json.obj(
                           "_key" -> "asc"
                         ),
                         "size"  -> size
                       )
                     )
                   )
                 )
               )
      raw   <- if (noraw.isEmpty)
                 query(
                   Json.obj(
                     "size"  -> 0,
                     "query" -> Json.obj(
                       "bool" -> filters(filterable, from, to, additionalMust = additionalFilters, raw = true)
                     ),
                     "aggs"  -> Json.obj(
                       "codes" -> Json.obj(
                         "terms" -> Json.obj(
                           "field" -> field,
                           "order" -> Json.obj(
                             "_key" -> "asc"
                           ),
                           "size"  -> size
                         )
                       )
                     )
                   )
                 )
               else QueryResponse.empty.vfuture
    } yield {
      val json = if (noraw.isEmpty) raw.resp else noraw.resp
      val pie  = (json \ "aggregations" \ "codes" \ "buckets")
        .asOpt[Seq[JsObject]]
        .getOrElse(Seq.empty)
        .map { o =>
          Json.obj(
            "name" -> s"${(o \ "key").as[JsValue]}",
            "y"    -> (o \ "doc_count").asOpt[JsValue]
          )
        }
      Json.obj(
        "name" -> "Pie Chart",
        "colorPoint" -> true,
        "series"     -> Json.arr(
          Json.obj(
            "data" -> JsArray(pie)
          )
        )
      )
    }
  }

  private def dateFilters(mayBeFrom: Option[DateTime], mayBeTo: Option[DateTime]): Seq[JsObject] = {
    val to            = mayBeTo.getOrElse(DateTime.now())
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

  private def gatewayEventFilters     = Seq(Json.obj("term" -> Json.obj("@type" -> "GatewayEvent")))
  private def healthCheckEventFilters = Seq(Json.obj("term" -> Json.obj("@type" -> "HealthCheckEvent")))

  private def filters(
      filterable: Option[Filterable],
      mayBeFrom: Option[DateTime],
      mayBeTo: Option[DateTime],
      additionalMust: Seq[JsObject] = Seq.empty,
      // additionalShould: Seq[JsObject] = Seq.empty,
      eventFilter: Seq[JsObject] = gatewayEventFilters,
      raw: Boolean
  ): JsObject = {

    val additionalShould: Seq[JsObject] = Seq.empty

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

    val eventFilterArr    =
      if (eventFilter.isEmpty) Json.arr()
      else
        JsArray(
          eventFilter.map(e => Json.obj("bool" -> Json.obj("should" -> Json.arr(e), "minimum_should_match" -> 1)))
        )
    val additionalMustArr =
      if (additionalMust.isEmpty) Json.arr()
      else JsArray(additionalMust.map(e => Json.obj("bool" -> Json.obj("must" -> Json.arr(e)))))
    val gatewayEvent      = Json.arr(
      Json.obj(
        "bool" -> Json.obj(
          "should"               -> Json.arr(
            Json.obj(
              "match_phrase" -> Json.obj(
                "@type" -> "GatewayEvent"
              )
            )
          ),
          "minimum_should_match" -> 1
        )
      )
    )
    val basicFilters      = eventFilterArr ++ additionalMustArr /* ++ gatewayEvent */

    def basicQuery(filters: JsArray): JsObject = {
      Json.obj(
        "must"     -> Json.arr(),
        "must_not" -> Json.arr(),
        "should"   -> Json.arr(),
        "filter"   -> Json.arr(
          Json.obj(
            "bool"  -> Json.obj(
              "filter" -> filters
            )
          ),
          Json.obj(
            "range" -> Json.obj(
              "@timestamp" -> Json.obj(
                "gte"    -> mayBeFrom.getOrElse(DateTime.now()).toString(),
                "lte"    -> mayBeTo.getOrElse(DateTime.now()).toString(),
                "format" -> "strict_date_optional_time"
              )
            )
          )
        )
      )
    }

    filterable match {
      case None                                => {
        basicQuery(basicFilters)
        // Json.obj(
        //   "must" -> (
        //     dateFilters(mayBeFrom, mayBeTo) ++
        //     eventFilter ++
        //     additionalMust
        //   )
        // )
      }
      case Some(ServiceGroupFilterable(group)) => {

        val filters = Json.arr(
          Json.obj(
            "bool" -> Json.obj(
              "should"               -> Json.arr(
                Json.obj(
                  "match_phrase" -> Json.obj(
                    "@type" -> "GatewayEvent"
                  )
                )
              ),
              "minimum_should_match" -> 1
            )
          )
        ) ++ Json.arr(
          Json.obj(
            "bool" -> Json.obj(
              "should"               -> Json.arr(
                Json.obj(
                  "match_phrase" -> Json.obj(
                    "descriptor.groups" -> group.id
                  )
                ),
                Json.obj(
                  "match_phrase" -> Json.obj(
                    "descriptor.groups.raw" -> group.id
                  )
                )
              ),
              "minimum_should_match" -> 1
            )
          )
        )

        basicQuery(filters)

        // Json.obj(
        //   "should"               -> (
        //     Seq(
        //       if (!raw) Json.obj("term" -> Json.obj("descriptor.groups" -> group.id)) else Json.obj("term" -> Json.obj("descriptor.groups.raw" -> group.id))
        //     ) ++
        //     additionalShould
        //   ),
        //   "must"                 -> (
        //     dateFilters(mayBeFrom, mayBeTo) ++
        //     eventFilter ++
        //     additionalMust
        //   )
        // ).applyOnIf(additionalShould.nonEmpty) { obj =>
        //   obj ++ Json.obj("minimum_should_match" -> 1)
        // }
      }
      case Some(ApiKeyFilterable(apiKey))             => {
        val filters = Json.arr(
          Json.obj(
            "bool" -> Json.obj(
              "should"               -> Json.arr(
                Json.obj(
                  "match_phrase" -> Json.obj(
                    "@type" -> "GatewayEvent"
                  )
                )
              ),
              "minimum_should_match" -> 1
            )
          )
        ) ++ Json.arr(
          Json.obj(
            "bool" -> Json.obj(
              "should"               -> Json.arr(
                Json.obj(
                  "match_phrase" -> Json.obj(
                    "identity.identity" -> apiKey.clientId
                  )
                ),
                Json.obj(
                  "match_phrase" -> Json.obj(
                    "identity.identity.raw" -> apiKey.clientId
                  )
                )
              ),
              "minimum_should_match" -> 1
            )
          )
        )

        basicQuery(filters)

        // Json.obj(
        //   "should"               -> (
        //     Seq(
        //       // Json.obj("term" -> Json.obj("identity.identityType.raw"     -> "APIKEY")),
        //       if (!raw) Json.obj("term" -> Json.obj("identity.identity" -> apiKey.clientId)) else Json.obj("term" -> Json.obj("identity.identity.raw" -> apiKey.clientId))
        //       // if (raw) Json.obj("term" -> Json.obj("identity.identity.raw" -> apiKey.clientId)) else Json.obj("term" -> Json.obj("identity.identity" -> apiKey.clientId))
        //     ) ++
        //     additionalShould
        //   ),
        //   "must"                 -> (
        //     dateFilters(mayBeFrom, mayBeTo) ++
        //     eventFilter ++
        //     additionalMust
        //   )
        // ).applyOnIf(additionalShould.nonEmpty) { obj =>
        //   obj ++ Json.obj("minimum_should_match" -> 1)
        // }
      }
      case Some(ServiceDescriptorFilterable(service)) => {

        val filters = Json.arr(
          Json.obj(
            "bool" -> Json.obj(
              "should"               -> Json.arr(
                Json.obj(
                  "match_phrase" -> Json.obj(
                    "@type" -> "GatewayEvent"
                  )
                )
              ),
              "minimum_should_match" -> 1
            )
          )
        ) ++ Json.arr(
          Json.obj(
            "bool" -> Json.obj(
              "should"               -> Json.arr(
                Json.obj(
                  "match_phrase" -> Json.obj(
                    "@serviceId" -> service.id
                  )
                ),
                Json.obj(
                  "match_phrase" -> Json.obj(
                    "@serviceId.raw" -> service.id
                  )
                )
              ),
              "minimum_should_match" -> 1
            )
          )
        )

        basicQuery(filters)

        // Json.obj(
        //   "should"               -> (
        //     Seq(
        //       if (!raw) Json.obj("term" -> Json.obj("@serviceId" -> service.id)) else Json.obj("term" -> Json.obj("@serviceId.raw" -> service.id))
        //     ) ++
        //     additionalShould
        //   ),
        //   "must"                 -> (
        //     dateFilters(mayBeFrom, mayBeTo) ++
        //     eventFilter ++
        //     additionalMust
        //   )
        // ).applyOnIf(additionalShould.nonEmpty) { obj =>
        //   obj ++ Json.obj("minimum_should_match" -> 1)
        // }
      }
    }
  }

  override def fetchServicesStatus(
      servicesDescriptors: Seq[ServiceDescriptor],
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] = {
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
    for {
      noraw <- query(
                 Json.obj(
                   "query" -> Json.obj(
                     "bool" -> filters(
                       None,
                       from,
                       to,
                       raw = false,
                       eventFilter = healthCheckEventFilters,
                       additionalMust = Seq(
                         Json.obj(
                           "terms" -> Json.obj("@serviceId" -> JsArray(servicesDescriptors.map(d => JsString(d.id))))
                         )
                       )
                     )
                   ),
                   "aggs"  -> Json.obj(
                     "services" -> Json.obj(
                       "terms" -> Json.obj(
                         "field" -> "@serviceId"
                       ),
                       "aggs"  -> Json.obj(
                         "date" -> Json.obj(
                           "date_histogram" -> Json
                             .obj(
                               "field"           -> "@timestamp",
                               "format"          -> "yyyy-MM-dd",
                               "min_doc_count"   -> 0,
                               "extended_bounds" -> extendedBounds
                             )
                             .applyOnIf(version.underEight) { obj => obj ++ Json.obj("interval" -> "day") }
                             .applyOnIf(version.aboveOrEqualsEight) { obj =>
                               obj ++ Json.obj("calendar_interval" -> "day")
                             }
                             .debugPrintln,
                           "aggs"           -> Json.obj(
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
                 )
               )
      raw   <- if (noraw.isEmpty)
                 query(
                   Json.obj(
                     "query" -> Json.obj(
                       "bool" -> filters(
                         None,
                         from,
                         to,
                         raw = true,
                         eventFilter = healthCheckEventFilters,
                         additionalMust = Seq(
                           Json.obj(
                             "terms" -> Json.obj("@serviceId" -> JsArray(servicesDescriptors.map(d => JsString(d.id))))
                           )
                         )
                       )
                     ),
                     "aggs"  -> Json.obj(
                       "services" -> Json.obj(
                         "terms" -> Json.obj(
                           "field" -> "@serviceId"
                         ),
                         "aggs"  -> Json.obj(
                           "date" -> Json.obj(
                             "date_histogram" -> Json
                               .obj(
                                 "field"           -> "@timestamp",
                                 "format"          -> "yyyy-MM-dd",
                                 "min_doc_count"   -> 0,
                                 "extended_bounds" -> extendedBounds
                               )
                               .applyOnIf(version.underEight) { obj => obj ++ Json.obj("interval" -> "day") }
                               .applyOnIf(version.aboveOrEqualsEight) { obj =>
                                 obj ++ Json.obj("calendar_interval" -> "day")
                               },
                             "aggs"           -> Json.obj(
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
                   )
                 )
               else QueryResponse.empty.vfuture
    } yield {
      val json = if (noraw.isEmpty) raw.resp else noraw.resp
      (json \ "aggregations" \ "services" \ "buckets")
        .asOpt[JsArray]
        .map { services =>
          JsArray(services.value.map(service => {
            val id           = (service \ "key").as[String]
            val total_period = (service \ "doc_count").as[Float]
            val dates        = (service \ "date" \ "buckets")
              .as[JsArray]
              .value
              .map(date => {
                val timestamp = (date \ "key").as[Float]
                val hrDate    = (date \ "key_as_string").as[String]
                val total_day = (date \ "doc_count").as[Float]
                val status    = (date \ "status" \ "buckets")
                  .as[JsArray]
                  .value
                  .map(h => {
                    val value      = (h \ "key").as[String]
                    val count      = (h \ "doc_count").as[Float]
                    val percentage = (count / total_day) * 100
                    Json.obj("health" -> value, "total" -> count, "percentage" -> percentage)
                  })

                Json.obj(
                  "date"         -> timestamp,
                  "dateAsString" -> hrDate,
                  "total"        -> total_day,
                  "status"       -> JsArray(status)
                )
              })

            val name         = servicesDescriptors.find(_.id == id).map(_.name).getOrElse("UNKNOWN")
            val env          = servicesDescriptors.find(_.id == id).map(_.env).getOrElse("prod")
            val value        = servicesDescriptors.find(_.id == id)
            val kind: String = value
              .map(_.metadata.getOrElse("kind", "service"))
              .getOrElse("service")

            Json.obj(
              "descriptor" -> id,
              "kind"       -> kind,
              "service"    -> name,
              "line"       -> env,
              "total"      -> total_period,
              "dates"      -> JsArray(dates)
            )
          }))
        }
    }
  }

  override def fetchServiceResponseTime(
      servicesDescriptor: ServiceDescriptor,
      from: Option[DateTime],
      to: Option[DateTime]
  )(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] = {
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
    for {
      noraw <- query(
                 Json.obj(
                   "query" -> Json.obj(
                     "bool" -> filters(
                       None,
                       from,
                       to,
                       raw = false,
                       eventFilter = healthCheckEventFilters,
                       additionalMust =
                         Seq(Json.obj("term" -> Json.obj("@serviceId" -> Json.obj("value" -> servicesDescriptor.id))))
                     )
                   ),
                   "aggs"  -> Json.obj(
                     "dates" -> Json.obj(
                       "date_histogram" -> Json
                         .obj(
                           "field"           -> "@timestamp",
                           "format"          -> "yyyy-MM-dd",
                           "min_doc_count"   -> 0,
                           "extended_bounds" -> extendedBounds
                         )
                         .applyOnIf(version.underEight) { obj => obj ++ Json.obj("interval" -> "hour") }
                         .applyOnIf(version.aboveOrEqualsEight) { obj =>
                           obj ++ Json.obj("calendar_interval" -> "hour")
                         },
                       "aggs"           -> Json.obj(
                         "duration" -> Json.obj(
                           "avg" -> Json.obj(
                             "field" -> "duration"
                           )
                         )
                       )
                     )
                   )
                 )
               )
      raw   <- if (noraw.isEmpty)
                 query(
                   Json.obj(
                     "query" -> Json.obj(
                       "bool" -> filters(
                         None,
                         from,
                         to,
                         raw = true,
                         eventFilter = healthCheckEventFilters,
                         additionalMust =
                           Seq(Json.obj("term" -> Json.obj("@serviceId" -> Json.obj("value" -> servicesDescriptor.id))))
                       )
                     ),
                     "aggs"  -> Json.obj(
                       "dates" -> Json.obj(
                         "date_histogram" -> Json
                           .obj(
                             "field"           -> "@timestamp",
                             "format"          -> "yyyy-MM-dd",
                             "min_doc_count"   -> 0,
                             "extended_bounds" -> extendedBounds
                           )
                           .applyOnIf(version.underEight) { obj => obj ++ Json.obj("interval" -> "hour") }
                           .applyOnIf(version.aboveOrEqualsEight) { obj =>
                             obj ++ Json.obj("calendar_interval" -> "hour")
                           },
                         "aggs"           -> Json.obj(
                           "duration" -> Json.obj(
                             "avg" -> Json.obj(
                               "field" -> "duration"
                             )
                           )
                         )
                       )
                     )
                   )
                 )
               else QueryResponse.empty.vfuture
    } yield {
      val json = if (noraw.isEmpty) raw.resp else noraw.resp
      (json \ "aggregations" \ "dates" \ "buckets")
        .asOpt[JsArray]
        .map(_.value)
        .map(dates =>
          JsArray(dates.map(date => {
            val timestamp = (date \ "key").as[Float]
            val duration  = (date \ "duration" \ "value").asOpt[Float]
            Json.obj("timestamp" -> timestamp, "duration" -> duration)
          }))
        )
    }
  }

  override def fetchRouteEfficiency(route: NgRoute, from: Option[DateTime], to: Option[DateTime], excludedPaths: Seq[String] = Seq.empty)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] = {
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

    val rangeCriteria = from
      .map { f =>
        Json.obj(
          "lte" -> to.getOrElse(DateTime.now()).getMillis,
          "gte" -> f.getMillis
        )
      }
      .getOrElse {
        Json.obj(
          "lte" -> to.getOrElse(DateTime.now()).getMillis
        )
      }

    val excludedPathsCriteria = if (excludedPaths.isEmpty) Json.obj() else Json.obj(
      "filter" -> Json.arr(
        Json.obj("bool" -> Json.obj(
          "must_not" -> JsArray(excludedPaths.map(path => Json.obj("match" -> Json.obj(
            "target.uri" -> path
          ))))
        ))
      )
    )

    val queryJson = Json.obj(
      "size" -> 0,
      "query" -> Json.obj(
        "bool" -> Json.obj(
          "must" -> Json.arr(
            Json.obj("match" -> Json.obj("@type" -> "GatewayEvent")),
            Json.obj("match" -> Json.obj("@serviceId" -> route.id)),
            Json.obj("range" -> Json.obj(
              "@timestamp" -> rangeCriteria
            ))
          )
        ).++(excludedPathsCriteria)
      ),
      "aggs" -> Json.obj(
        "dates" -> Json.obj(
          "date_histogram" -> Json
            .obj(
              "field" -> "@callAt",
              "fixed_interval" -> "1h",
              "extended_bounds" -> extendedBounds
            ),
          "aggs" -> Json.obj(
            "avgDuration" -> Json.obj(
              "avg" -> Json.obj(
                "field" -> "backendDuration"
              )
            )
          )
        )
      )
    )

    println(Json.prettyPrint(queryJson))

//    val data = Json.parse("{\n  \"took\" : 2168,\n  \"timed_out\" : false,\n  \"_shards\" : {\n    \"total\" : 1379,\n    \"successful\" : 1379,\n    \"skipped\" : 1334,\n    \"failed\" : 0\n  },\n  \"hits\" : {\n    \"total\" : {\n      \"value\" : 10000,\n      \"relation\" : \"gte\"\n    },\n    \"max_score\" : null,\n    \"hits\" : [ ]\n  },\n  \"aggregations\" : {\n    \"dates\" : {\n      \"buckets\" : [\n        {\n          \"key\" : 1712736000000,\n          \"doc_count\" : 17744,\n          \"avgDuration\" : {\n            \"value\" : 7.980500450856628\n          }\n        },\n        {\n          \"key\" : 1712739600000,\n          \"doc_count\" : 18994,\n          \"avgDuration\" : {\n            \"value\" : 8.00500157944614\n          }\n        },\n        {\n          \"key\" : 1712743200000,\n          \"doc_count\" : 12721,\n          \"avgDuration\" : {\n            \"value\" : 7.938919896234573\n          }\n        },\n        {\n          \"key\" : 1712746800000,\n          \"doc_count\" : 10113,\n          \"avgDuration\" : {\n            \"value\" : 8.06694353802037\n          }\n        },\n        {\n          \"key\" : 1712750400000,\n          \"doc_count\" : 19368,\n          \"avgDuration\" : {\n            \"value\" : 7.901022304832714\n          }\n        },\n        {\n          \"key\" : 1712754000000,\n          \"doc_count\" : 19155,\n          \"avgDuration\" : {\n            \"value\" : 8.108640041764552\n          }\n        },\n        {\n          \"key\" : 1712757600000,\n          \"doc_count\" : 17106,\n          \"avgDuration\" : {\n            \"value\" : 8.180287618379516\n          }\n        },\n        {\n          \"key\" : 1712761200000,\n          \"doc_count\" : 11557,\n          \"avgDuration\" : {\n            \"value\" : 8.764385221078134\n          }\n        },\n        {\n          \"key\" : 1712764800000,\n          \"doc_count\" : 4078,\n          \"avgDuration\" : {\n            \"value\" : 9.729279058361943\n          }\n        },\n        {\n          \"key\" : 1712768400000,\n          \"doc_count\" : 1472,\n          \"avgDuration\" : {\n            \"value\" : 11.372282608695652\n          }\n        },\n        {\n          \"key\" : 1712772000000,\n          \"doc_count\" : 1150,\n          \"avgDuration\" : {\n            \"value\" : 11.741739130434782\n          }\n        },\n        {\n          \"key\" : 1712775600000,\n          \"doc_count\" : 1212,\n          \"avgDuration\" : {\n            \"value\" : 11.576732673267326\n          }\n        },\n        {\n          \"key\" : 1712779200000,\n          \"doc_count\" : 886,\n          \"avgDuration\" : {\n            \"value\" : 12.869074492099323\n          }\n        },\n        {\n          \"key\" : 1712782800000,\n          \"doc_count\" : 102,\n          \"avgDuration\" : {\n            \"value\" : 16.666666666666668\n          }\n        },\n        {\n          \"key\" : 1712786400000,\n          \"doc_count\" : 228,\n          \"avgDuration\" : {\n            \"value\" : 10.25438596491228\n          }\n        },\n        {\n          \"key\" : 1712790000000,\n          \"doc_count\" : 96,\n          \"avgDuration\" : {\n            \"value\" : 15.364583333333334\n          }\n        },\n        {\n          \"key\" : 1712793600000,\n          \"doc_count\" : 212,\n          \"avgDuration\" : {\n            \"value\" : 13.787735849056604\n          }\n        },\n        {\n          \"key\" : 1712797200000,\n          \"doc_count\" : 42,\n          \"avgDuration\" : {\n            \"value\" : 17.452380952380953\n          }\n        },\n        {\n          \"key\" : 1712800800000,\n          \"doc_count\" : 82,\n          \"avgDuration\" : {\n            \"value\" : 15.853658536585366\n          }\n        },\n        {\n          \"key\" : 1712804400000,\n          \"doc_count\" : 127,\n          \"avgDuration\" : {\n            \"value\" : 17.677165354330707\n          }\n        },\n        {\n          \"key\" : 1712808000000,\n          \"doc_count\" : 404,\n          \"avgDuration\" : {\n            \"value\" : 14.064356435643564\n          }\n        },\n        {\n          \"key\" : 1712811600000,\n          \"doc_count\" : 826,\n          \"avgDuration\" : {\n            \"value\" : 12.283292978208232\n          }\n        },\n        {\n          \"key\" : 1712815200000,\n          \"doc_count\" : 5887,\n          \"avgDuration\" : {\n            \"value\" : 8.695430609818244\n          }\n        },\n        {\n          \"key\" : 1712818800000,\n          \"doc_count\" : 14969,\n          \"avgDuration\" : {\n            \"value\" : 8.28652548600441\n          }\n        },\n        {\n          \"key\" : 1712822400000,\n          \"doc_count\" : 18175,\n          \"avgDuration\" : {\n            \"value\" : 8.186354883081155\n          }\n        },\n        {\n          \"key\" : 1712826000000,\n          \"doc_count\" : 19402,\n          \"avgDuration\" : {\n            \"value\" : 7.990001030821565\n          }\n        },\n        {\n          \"key\" : 1712829600000,\n          \"doc_count\" : 12483,\n          \"avgDuration\" : {\n            \"value\" : 8.46815669310262\n          }\n        },\n        {\n          \"key\" : 1712833200000,\n          \"doc_count\" : 10468,\n          \"avgDuration\" : {\n            \"value\" : 8.32718761941154\n          }\n        },\n        {\n          \"key\" : 1712836800000,\n          \"doc_count\" : 16935,\n          \"avgDuration\" : {\n            \"value\" : 8.217360496014171\n          }\n        },\n        {\n          \"key\" : 1712840400000,\n          \"doc_count\" : 17473,\n          \"avgDuration\" : {\n            \"value\" : 8.19429977679849\n          }\n        },\n        {\n          \"key\" : 1712844000000,\n          \"doc_count\" : 16428,\n          \"avgDuration\" : {\n            \"value\" : 8.257243730216704\n          }\n        },\n        {\n          \"key\" : 1712847600000,\n          \"doc_count\" : 9962,\n          \"avgDuration\" : {\n            \"value\" : 8.731981529813291\n          }\n        },\n        {\n          \"key\" : 1712851200000,\n          \"doc_count\" : 3166,\n          \"avgDuration\" : {\n            \"value\" : 9.301958307012002\n          }\n        },\n        {\n          \"key\" : 1712854800000,\n          \"doc_count\" : 1386,\n          \"avgDuration\" : {\n            \"value\" : 11.20923520923521\n          }\n        },\n        {\n          \"key\" : 1712858400000,\n          \"doc_count\" : 1110,\n          \"avgDuration\" : {\n            \"value\" : 12.04054054054054\n          }\n        },\n        {\n          \"key\" : 1712862000000,\n          \"doc_count\" : 1071,\n          \"avgDuration\" : {\n            \"value\" : 11.902894491129786\n          }\n        },\n        {\n          \"key\" : 1712865600000,\n          \"doc_count\" : 752,\n          \"avgDuration\" : {\n            \"value\" : 12.86968085106383\n          }\n        },\n        {\n          \"key\" : 1712869200000,\n          \"doc_count\" : 659,\n          \"avgDuration\" : {\n            \"value\" : 12.737481031866464\n          }\n        },\n        {\n          \"key\" : 1712872800000,\n          \"doc_count\" : 226,\n          \"avgDuration\" : {\n            \"value\" : 15.008849557522124\n          }\n        },\n        {\n          \"key\" : 1712876400000,\n          \"doc_count\" : 126,\n          \"avgDuration\" : {\n            \"value\" : 16.69047619047619\n          }\n        },\n        {\n          \"key\" : 1712880000000,\n          \"doc_count\" : 63,\n          \"avgDuration\" : {\n            \"value\" : 18.317460317460316\n          }\n        },\n        {\n          \"key\" : 1712883600000,\n          \"doc_count\" : 39,\n          \"avgDuration\" : {\n            \"value\" : 17.102564102564102\n          }\n        },\n        {\n          \"key\" : 1712887200000,\n          \"doc_count\" : 54,\n          \"avgDuration\" : {\n            \"value\" : 16.75925925925926\n          }\n        },\n        {\n          \"key\" : 1712890800000,\n          \"doc_count\" : 80,\n          \"avgDuration\" : {\n            \"value\" : 17.975\n          }\n        },\n        {\n          \"key\" : 1712894400000,\n          \"doc_count\" : 336,\n          \"avgDuration\" : {\n            \"value\" : 14.994047619047619\n          }\n        },\n        {\n          \"key\" : 1712898000000,\n          \"doc_count\" : 772,\n          \"avgDuration\" : {\n            \"value\" : 12.295336787564766\n          }\n        },\n        {\n          \"key\" : 1712901600000,\n          \"doc_count\" : 6314,\n          \"avgDuration\" : {\n            \"value\" : 8.737250554323724\n          }\n        },\n        {\n          \"key\" : 1712905200000,\n          \"doc_count\" : 16273,\n          \"avgDuration\" : {\n            \"value\" : 8.422847661771032\n          }\n        },\n        {\n          \"key\" : 1712908800000,\n          \"doc_count\" : 18957,\n          \"avgDuration\" : {\n            \"value\" : 8.42986759508361\n          }\n        },\n        {\n          \"key\" : 1712912400000,\n          \"doc_count\" : 18713,\n          \"avgDuration\" : {\n            \"value\" : 8.431197563191365\n          }\n        },\n        {\n          \"key\" : 1712916000000,\n          \"doc_count\" : 11646,\n          \"avgDuration\" : {\n            \"value\" : 8.563455263609823\n          }\n        },\n        {\n          \"key\" : 1712919600000,\n          \"doc_count\" : 9162,\n          \"avgDuration\" : {\n            \"value\" : 8.764571054354944\n          }\n        },\n        {\n          \"key\" : 1712923200000,\n          \"doc_count\" : 17294,\n          \"avgDuration\" : {\n            \"value\" : 8.604660575922285\n          }\n        },\n        {\n          \"key\" : 1712926800000,\n          \"doc_count\" : 17101,\n          \"avgDuration\" : {\n            \"value\" : 8.801590550260219\n          }\n        },\n        {\n          \"key\" : 1712930400000,\n          \"doc_count\" : 14489,\n          \"avgDuration\" : {\n            \"value\" : 8.515356477327629\n          }\n        },\n        {\n          \"key\" : 1712934000000,\n          \"doc_count\" : 8446,\n          \"avgDuration\" : {\n            \"value\" : 9.320980345725788\n          }\n        },\n        {\n          \"key\" : 1712937600000,\n          \"doc_count\" : 2727,\n          \"avgDuration\" : {\n            \"value\" : 9.774844151081775\n          }\n        },\n        {\n          \"key\" : 1712941200000,\n          \"doc_count\" : 1242,\n          \"avgDuration\" : {\n            \"value\" : 11.473429951690822\n          }\n        },\n        {\n          \"key\" : 1712944800000,\n          \"doc_count\" : 866,\n          \"avgDuration\" : {\n            \"value\" : 12.289838337182449\n          }\n        },\n        {\n          \"key\" : 1712948400000,\n          \"doc_count\" : 755,\n          \"avgDuration\" : {\n            \"value\" : 12.397350993377483\n          }\n        },\n        {\n          \"key\" : 1712952000000,\n          \"doc_count\" : 633,\n          \"avgDuration\" : {\n            \"value\" : 13.415481832543444\n          }\n        },\n        {\n          \"key\" : 1712955600000,\n          \"doc_count\" : 605,\n          \"avgDuration\" : {\n            \"value\" : 12.185123966942148\n          }\n        },\n        {\n          \"key\" : 1712959200000,\n          \"doc_count\" : 248,\n          \"avgDuration\" : {\n            \"value\" : 14.108870967741936\n          }\n        },\n        {\n          \"key\" : 1712962800000,\n          \"doc_count\" : 134,\n          \"avgDuration\" : {\n            \"value\" : 15.850746268656716\n          }\n        },\n        {\n          \"key\" : 1712966400000,\n          \"doc_count\" : 131,\n          \"avgDuration\" : {\n            \"value\" : 15.992366412213741\n          }\n        },\n        {\n          \"key\" : 1712970000000,\n          \"doc_count\" : 108,\n          \"avgDuration\" : {\n            \"value\" : 16.046296296296298\n          }\n        },\n        {\n          \"key\" : 1712973600000,\n          \"doc_count\" : 72,\n          \"avgDuration\" : {\n            \"value\" : 19.40277777777778\n          }\n        },\n        {\n          \"key\" : 1712977200000,\n          \"doc_count\" : 77,\n          \"avgDuration\" : {\n            \"value\" : 16.68831168831169\n          }\n        },\n        {\n          \"key\" : 1712980800000,\n          \"doc_count\" : 84,\n          \"avgDuration\" : {\n            \"value\" : 17.523809523809526\n          }\n        },\n        {\n          \"key\" : 1712984400000,\n          \"doc_count\" : 351,\n          \"avgDuration\" : {\n            \"value\" : 14.72934472934473\n          }\n        },\n        {\n          \"key\" : 1712988000000,\n          \"doc_count\" : 2176,\n          \"avgDuration\" : {\n            \"value\" : 9.836856617647058\n          }\n        },\n        {\n          \"key\" : 1712991600000,\n          \"doc_count\" : 3861,\n          \"avgDuration\" : {\n            \"value\" : 8.93939393939394\n          }\n        },\n        {\n          \"key\" : 1712995200000,\n          \"doc_count\" : 4277,\n          \"avgDuration\" : {\n            \"value\" : 8.856441430909516\n          }\n        },\n        {\n          \"key\" : 1712998800000,\n          \"doc_count\" : 4235,\n          \"avgDuration\" : {\n            \"value\" : 8.791027154663519\n          }\n        },\n        {\n          \"key\" : 1713002400000,\n          \"doc_count\" : 2787,\n          \"avgDuration\" : {\n            \"value\" : 9.218873340509509\n          }\n        },\n        {\n          \"key\" : 1713006000000,\n          \"doc_count\" : 1915,\n          \"avgDuration\" : {\n            \"value\" : 10.022976501305482\n          }\n        },\n        {\n          \"key\" : 1713009600000,\n          \"doc_count\" : 2692,\n          \"avgDuration\" : {\n            \"value\" : 9.316121842496285\n          }\n        },\n        {\n          \"key\" : 1713013200000,\n          \"doc_count\" : 2553,\n          \"avgDuration\" : {\n            \"value\" : 9.401488444966706\n          }\n        },\n        {\n          \"key\" : 1713016800000,\n          \"doc_count\" : 2332,\n          \"avgDuration\" : {\n            \"value\" : 9.515866209262436\n          }\n        },\n        {\n          \"key\" : 1713020400000,\n          \"doc_count\" : 1115,\n          \"avgDuration\" : {\n            \"value\" : 11.46726457399103\n          }\n        },\n        {\n          \"key\" : 1713024000000,\n          \"doc_count\" : 651,\n          \"avgDuration\" : {\n            \"value\" : 12.499231950844854\n          }\n        },\n        {\n          \"key\" : 1713027600000,\n          \"doc_count\" : 506,\n          \"avgDuration\" : {\n            \"value\" : 13.65612648221344\n          }\n        },\n        {\n          \"key\" : 1713031200000,\n          \"doc_count\" : 441,\n          \"avgDuration\" : {\n            \"value\" : 13.82312925170068\n          }\n        },\n        {\n          \"key\" : 1713034800000,\n          \"doc_count\" : 463,\n          \"avgDuration\" : {\n            \"value\" : 13.207343412526997\n          }\n        },\n        {\n          \"key\" : 1713038400000,\n          \"doc_count\" : 414,\n          \"avgDuration\" : {\n            \"value\" : 13.847826086956522\n          }\n        },\n        {\n          \"key\" : 1713042000000,\n          \"doc_count\" : 291,\n          \"avgDuration\" : {\n            \"value\" : 14.43298969072165\n          }\n        },\n        {\n          \"key\" : 1713045600000,\n          \"doc_count\" : 217,\n          \"avgDuration\" : {\n            \"value\" : 14.709677419354838\n          }\n        },\n        {\n          \"key\" : 1713049200000,\n          \"doc_count\" : 97,\n          \"avgDuration\" : {\n            \"value\" : 16.123711340206185\n          }\n        },\n        {\n          \"key\" : 1713052800000,\n          \"doc_count\" : 78,\n          \"avgDuration\" : {\n            \"value\" : 16.73076923076923\n          }\n        },\n        {\n          \"key\" : 1713056400000,\n          \"doc_count\" : 59,\n          \"avgDuration\" : {\n            \"value\" : 17.25423728813559\n          }\n        },\n        {\n          \"key\" : 1713060000000,\n          \"doc_count\" : 32,\n          \"avgDuration\" : {\n            \"value\" : 19.25\n          }\n        },\n        {\n          \"key\" : 1713063600000,\n          \"doc_count\" : 29,\n          \"avgDuration\" : {\n            \"value\" : 18.310344827586206\n          }\n        },\n        {\n          \"key\" : 1713067200000,\n          \"doc_count\" : 64,\n          \"avgDuration\" : {\n            \"value\" : 15.578125\n          }\n        },\n        {\n          \"key\" : 1713070800000,\n          \"doc_count\" : 163,\n          \"avgDuration\" : {\n            \"value\" : 15.809815950920246\n          }\n        },\n        {\n          \"key\" : 1713074400000,\n          \"doc_count\" : 311,\n          \"avgDuration\" : {\n            \"value\" : 16.083601286173632\n          }\n        },\n        {\n          \"key\" : 1713078000000,\n          \"doc_count\" : 513,\n          \"avgDuration\" : {\n            \"value\" : 13.087719298245615\n          }\n        },\n        {\n          \"key\" : 1713081600000,\n          \"doc_count\" : 654,\n          \"avgDuration\" : {\n            \"value\" : 12.299694189602446\n          }\n        },\n        {\n          \"key\" : 1713085200000,\n          \"doc_count\" : 740,\n          \"avgDuration\" : {\n            \"value\" : 12.508108108108107\n          }\n        },\n        {\n          \"key\" : 1713088800000,\n          \"doc_count\" : 680,\n          \"avgDuration\" : {\n            \"value\" : 12.379411764705882\n          }\n        },\n        {\n          \"key\" : 1713092400000,\n          \"doc_count\" : 645,\n          \"avgDuration\" : {\n            \"value\" : 12.34108527131783\n          }\n        },\n        {\n          \"key\" : 1713096000000,\n          \"doc_count\" : 586,\n          \"avgDuration\" : {\n            \"value\" : 12.361774744027304\n          }\n        },\n        {\n          \"key\" : 1713099600000,\n          \"doc_count\" : 741,\n          \"avgDuration\" : {\n            \"value\" : 12.134952766531715\n          }\n        },\n        {\n          \"key\" : 1713103200000,\n          \"doc_count\" : 687,\n          \"avgDuration\" : {\n            \"value\" : 11.799126637554584\n          }\n        },\n        {\n          \"key\" : 1713106800000,\n          \"doc_count\" : 770,\n          \"avgDuration\" : {\n            \"value\" : 11.97922077922078\n          }\n        },\n        {\n          \"key\" : 1713110400000,\n          \"doc_count\" : 731,\n          \"avgDuration\" : {\n            \"value\" : 12.616963064295486\n          }\n        },\n        {\n          \"key\" : 1713114000000,\n          \"doc_count\" : 789,\n          \"avgDuration\" : {\n            \"value\" : 12.228136882129277\n          }\n        },\n        {\n          \"key\" : 1713117600000,\n          \"doc_count\" : 664,\n          \"avgDuration\" : {\n            \"value\" : 12.957831325301205\n          }\n        },\n        {\n          \"key\" : 1713121200000,\n          \"doc_count\" : 707,\n          \"avgDuration\" : {\n            \"value\" : 13.11032531824611\n          }\n        },\n        {\n          \"key\" : 1713124800000,\n          \"doc_count\" : 561,\n          \"avgDuration\" : {\n            \"value\" : 13.650623885918003\n          }\n        },\n        {\n          \"key\" : 1713128400000,\n          \"doc_count\" : 417,\n          \"avgDuration\" : {\n            \"value\" : 13.73621103117506\n          }\n        },\n        {\n          \"key\" : 1713132000000,\n          \"doc_count\" : 196,\n          \"avgDuration\" : {\n            \"value\" : 14.535714285714286\n          }\n        },\n        {\n          \"key\" : 1713135600000,\n          \"doc_count\" : 114,\n          \"avgDuration\" : {\n            \"value\" : 15.728070175438596\n          }\n        },\n        {\n          \"key\" : 1713139200000,\n          \"doc_count\" : 59,\n          \"avgDuration\" : {\n            \"value\" : 18.016949152542374\n          }\n        },\n        {\n          \"key\" : 1713142800000,\n          \"doc_count\" : 66,\n          \"avgDuration\" : {\n            \"value\" : 17.318181818181817\n          }\n        },\n        {\n          \"key\" : 1713146400000,\n          \"doc_count\" : 72,\n          \"avgDuration\" : {\n            \"value\" : 16.680555555555557\n          }\n        },\n        {\n          \"key\" : 1713150000000,\n          \"doc_count\" : 138,\n          \"avgDuration\" : {\n            \"value\" : 17.065217391304348\n          }\n        },\n        {\n          \"key\" : 1713153600000,\n          \"doc_count\" : 334,\n          \"avgDuration\" : {\n            \"value\" : 14.197604790419161\n          }\n        },\n        {\n          \"key\" : 1713157200000,\n          \"doc_count\" : 724,\n          \"avgDuration\" : {\n            \"value\" : 12.4060773480663\n          }\n        },\n        {\n          \"key\" : 1713160800000,\n          \"doc_count\" : 6121,\n          \"avgDuration\" : {\n            \"value\" : 8.423460218918477\n          }\n        },\n        {\n          \"key\" : 1713164400000,\n          \"doc_count\" : 17834,\n          \"avgDuration\" : {\n            \"value\" : 7.830548390714366\n          }\n        },\n        {\n          \"key\" : 1713168000000,\n          \"doc_count\" : 21206,\n          \"avgDuration\" : {\n            \"value\" : 7.826841459964161\n          }\n        },\n        {\n          \"key\" : 1713171600000,\n          \"doc_count\" : 21025,\n          \"avgDuration\" : {\n            \"value\" : 7.864256837098692\n          }\n        },\n        {\n          \"key\" : 1713175200000,\n          \"doc_count\" : 13348,\n          \"avgDuration\" : {\n            \"value\" : 8.003970632304465\n          }\n        },\n        {\n          \"key\" : 1713178800000,\n          \"doc_count\" : 10836,\n          \"avgDuration\" : {\n            \"value\" : 8.367478774455519\n          }\n        },\n        {\n          \"key\" : 1713182400000,\n          \"doc_count\" : 20269,\n          \"avgDuration\" : {\n            \"value\" : 8.085894716068873\n          }\n        },\n        {\n          \"key\" : 1713186000000,\n          \"doc_count\" : 19818,\n          \"avgDuration\" : {\n            \"value\" : 7.998889898072459\n          }\n        },\n        {\n          \"key\" : 1713189600000,\n          \"doc_count\" : 17213,\n          \"avgDuration\" : {\n            \"value\" : 8.194039388834021\n          }\n        },\n        {\n          \"key\" : 1713193200000,\n          \"doc_count\" : 11295,\n          \"avgDuration\" : {\n            \"value\" : 8.776272687029659\n          }\n        },\n        {\n          \"key\" : 1713196800000,\n          \"doc_count\" : 3999,\n          \"avgDuration\" : {\n            \"value\" : 9.169042260565142\n          }\n        },\n        {\n          \"key\" : 1713200400000,\n          \"doc_count\" : 1659,\n          \"avgDuration\" : {\n            \"value\" : 10.981916817359854\n          }\n        },\n        {\n          \"key\" : 1713204000000,\n          \"doc_count\" : 1386,\n          \"avgDuration\" : {\n            \"value\" : 10.667388167388168\n          }\n        },\n        {\n          \"key\" : 1713207600000,\n          \"doc_count\" : 1294,\n          \"avgDuration\" : {\n            \"value\" : 10.741112828438949\n          }\n        },\n        {\n          \"key\" : 1713211200000,\n          \"doc_count\" : 977,\n          \"avgDuration\" : {\n            \"value\" : 11.953940634595702\n          }\n        },\n        {\n          \"key\" : 1713214800000,\n          \"doc_count\" : 557,\n          \"avgDuration\" : {\n            \"value\" : 12.935368043087971\n          }\n        },\n        {\n          \"key\" : 1713218400000,\n          \"doc_count\" : 205,\n          \"avgDuration\" : {\n            \"value\" : 14.86829268292683\n          }\n        },\n        {\n          \"key\" : 1713222000000,\n          \"doc_count\" : 125,\n          \"avgDuration\" : {\n            \"value\" : 15.592\n          }\n        },\n        {\n          \"key\" : 1713225600000,\n          \"doc_count\" : 77,\n          \"avgDuration\" : {\n            \"value\" : 17.233766233766232\n          }\n        },\n        {\n          \"key\" : 1713229200000,\n          \"doc_count\" : 58,\n          \"avgDuration\" : {\n            \"value\" : 17.051724137931036\n          }\n        },\n        {\n          \"key\" : 1713232800000,\n          \"doc_count\" : 40,\n          \"avgDuration\" : {\n            \"value\" : 19.05\n          }\n        },\n        {\n          \"key\" : 1713236400000,\n          \"doc_count\" : 98,\n          \"avgDuration\" : {\n            \"value\" : 16.275510204081634\n          }\n        },\n        {\n          \"key\" : 1713240000000,\n          \"doc_count\" : 453,\n          \"avgDuration\" : {\n            \"value\" : 13.571743929359823\n          }\n        },\n        {\n          \"key\" : 1713243600000,\n          \"doc_count\" : 765,\n          \"avgDuration\" : {\n            \"value\" : 12.533333333333333\n          }\n        },\n        {\n          \"key\" : 1713247200000,\n          \"doc_count\" : 5710,\n          \"avgDuration\" : {\n            \"value\" : 8.452714535901926\n          }\n        },\n        {\n          \"key\" : 1713250800000,\n          \"doc_count\" : 16248,\n          \"avgDuration\" : {\n            \"value\" : 7.943993106843919\n          }\n        },\n        {\n          \"key\" : 1713254400000,\n          \"doc_count\" : 19994,\n          \"avgDuration\" : {\n            \"value\" : 8.008952685805742\n          }\n        },\n        {\n          \"key\" : 1713258000000,\n          \"doc_count\" : 19798,\n          \"avgDuration\" : {\n            \"value\" : 7.827709869683806\n          }\n        },\n        {\n          \"key\" : 1713261600000,\n          \"doc_count\" : 12501,\n          \"avgDuration\" : {\n            \"value\" : 8.003919686425085\n          }\n        },\n        {\n          \"key\" : 1713265200000,\n          \"doc_count\" : 9849,\n          \"avgDuration\" : {\n            \"value\" : 7.960503604426846\n          }\n        },\n        {\n          \"key\" : 1713268800000,\n          \"doc_count\" : 18248,\n          \"avgDuration\" : {\n            \"value\" : 7.884699693117054\n          }\n        },\n        {\n          \"key\" : 1713272400000,\n          \"doc_count\" : 17836,\n          \"avgDuration\" : {\n            \"value\" : 8.04384391119085\n          }\n        },\n        {\n          \"key\" : 1713276000000,\n          \"doc_count\" : 16876,\n          \"avgDuration\" : {\n            \"value\" : 7.859623133443944\n          }\n        },\n        {\n          \"key\" : 1713279600000,\n          \"doc_count\" : 10943,\n          \"avgDuration\" : {\n            \"value\" : 7.9732248926254226\n          }\n        },\n        {\n          \"key\" : 1713283200000,\n          \"doc_count\" : 3546,\n          \"avgDuration\" : {\n            \"value\" : 8.896503102086859\n          }\n        },\n        {\n          \"key\" : 1713286800000,\n          \"doc_count\" : 1511,\n          \"avgDuration\" : {\n            \"value\" : 11.164791528788882\n          }\n        },\n        {\n          \"key\" : 1713290400000,\n          \"doc_count\" : 1196,\n          \"avgDuration\" : {\n            \"value\" : 11.538461538461538\n          }\n        },\n        {\n          \"key\" : 1713294000000,\n          \"doc_count\" : 1204,\n          \"avgDuration\" : {\n            \"value\" : 12.210963455149502\n          }\n        },\n        {\n          \"key\" : 1713297600000,\n          \"doc_count\" : 976,\n          \"avgDuration\" : {\n            \"value\" : 12.714139344262295\n          }\n        },\n        {\n          \"key\" : 1713301200000,\n          \"doc_count\" : 564,\n          \"avgDuration\" : {\n            \"value\" : 12.97695035460993\n          }\n        },\n        {\n          \"key\" : 1713304800000,\n          \"doc_count\" : 206,\n          \"avgDuration\" : {\n            \"value\" : 14.21359223300971\n          }\n        },\n        {\n          \"key\" : 1713308400000,\n          \"doc_count\" : 347,\n          \"avgDuration\" : {\n            \"value\" : 11.236311239193084\n          }\n        },\n        {\n          \"key\" : 1713312000000,\n          \"doc_count\" : 204,\n          \"avgDuration\" : {\n            \"value\" : 13.377450980392156\n          }\n        },\n        {\n          \"key\" : 1713315600000,\n          \"doc_count\" : 30,\n          \"avgDuration\" : {\n            \"value\" : 16.966666666666665\n          }\n        },\n        {\n          \"key\" : 1713319200000,\n          \"doc_count\" : 68,\n          \"avgDuration\" : {\n            \"value\" : 17.25\n          }\n        },\n        {\n          \"key\" : 1713322800000,\n          \"doc_count\" : 91,\n          \"avgDuration\" : {\n            \"value\" : 18.13186813186813\n          }\n        },\n        {\n          \"key\" : 1713326400000,\n          \"doc_count\" : 406,\n          \"avgDuration\" : {\n            \"value\" : 13.041871921182265\n          }\n        },\n        {\n          \"key\" : 1713330000000,\n          \"doc_count\" : 678,\n          \"avgDuration\" : {\n            \"value\" : 11.523598820058996\n          }\n        },\n        {\n          \"key\" : 1713333600000,\n          \"doc_count\" : 5850,\n          \"avgDuration\" : {\n            \"value\" : 8.25931623931624\n          }\n        },\n        {\n          \"key\" : 1713337200000,\n          \"doc_count\" : 17283,\n          \"avgDuration\" : {\n            \"value\" : 7.749175490366256\n          }\n        },\n        {\n          \"key\" : 1713340800000,\n          \"doc_count\" : 1370,\n          \"avgDuration\" : {\n            \"value\" : 8.11021897810219\n          }\n        }\n      ]\n    }\n  }\n}")

    for {
      resp <- query(queryJson)
//      resp <- data.future
    } yield {

      (resp.resp \ "aggregations" \ "dates" \ "buckets")
//      (resp \ "aggregations" \ "dates" \ "buckets")
        .asOpt[JsArray]
        .map { date =>
          JsArray(date.value.map(d => {
            val timestamp = (d \ "key").as[Long]
            val hits = (d \ "doc_count").as[Long]
            val avgDuration = (d \ "avgDuration" \ "value").asOpt[Float]

            val fl = avgDuration.getOrElse(0F)
            Json.obj(
              "date" -> timestamp,
              "hits" -> hits,
              "avgDuration" -> fl
            )
          }))
        }
    }
  }
}
