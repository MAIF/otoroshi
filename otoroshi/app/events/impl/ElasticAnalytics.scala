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
}
