package controllers
import actions.{ApiAction, UnAuthApiAction}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import env.Env
import events._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}

case class Part(fieldName: String, f: () => Future[Option[JsValue]]) {
  def call(req: RequestHeader)(implicit ec: ExecutionContext, mat: Materializer, env: Env): Future[JsObject] = {
    req.getQueryString("fields") match {
      case None => f().map {
        case Some(res) => Json.obj(fieldName -> res)
        case None => Json.obj()
      }
      case Some(fieldsStr) if fieldsStr.toLowerCase().split(",").toSeq.contains(fieldName.toLowerCase())=> {
        f().map {
          case Some(res) => Json.obj(fieldName -> res)
          case None => Json.obj()
        }
      }
      case _ => FastFuture.successful(Json.obj())
    }
  }
}

class AnalyticsController(ApiAction: ApiAction, UnAuthApiAction: UnAuthApiAction, cc: ControllerComponents)(
    implicit env: Env
) extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-analytics-api")

  def serviceStats(serviceId: String, from: Option[String], to: Option[String]) = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_SERVICE_STATS",
        s"User accessed a service descriptor stats",
        ctx.from,
        Json.obj("serviceId" -> serviceId)
      )
    )
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(9999)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
        case Some(desc) => {

          val analyticsService = new AnalyticsReadsServiceImpl(globalConfig, env)

          val fromDate = from.map(f => new DateTime(f.toLong))
          val toDate   = to.map(f => new DateTime(f.toLong))
          for {
            _ <- FastFuture.successful(())

            fhits        = analyticsService.fetchHits(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            fdatain      = analyticsService.fetchDataIn(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            fdataout     = analyticsService.fetchDataOut(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            favgduration = analyticsService.fetchAvgDuration(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            favgoverhead = analyticsService.fetchAvgOverhead(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            fstatusesPiechart = analyticsService
              .fetchStatusesPiechart(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            fstatusesHistogram = analyticsService
              .fetchStatusesHistogram(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            foverheadPercentiles = analyticsService
              .fetchOverheadPercentilesHistogram(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            foverheadStats = analyticsService
              .fetchOverheadStatsHistogram(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            fdurationPercentiles = analyticsService
              .fetchDurationPercentilesHistogram(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            fdurationStats = analyticsService
              .fetchDurationStatsHistogram(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            fdataInHistogram = analyticsService
              .fetchDataInStatsHistogram(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            fdataOutHistogram = analyticsService
              .fetchDataOutStatsHistogram(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            fApiKeyPiechart = analyticsService
              .fetchApiKeyPiechart(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)
            fUserPiechart = analyticsService
              .fetchUserPiechart(Some(ServiceDescriptorFilterable(desc)), fromDate, toDate)

            statusesPiechart    <- fstatusesPiechart
            statusesHistogram   <- fstatusesHistogram
            overheadPercentiles <- foverheadPercentiles
            overheadStats       <- foverheadStats
            durationPercentiles <- fdurationPercentiles
            durationStats       <- fdurationStats
            dataInStats         <- fdataInHistogram
            dataOutStats        <- fdataOutHistogram
            apiKeyPiechart      <- fApiKeyPiechart
            userPiechart        <- fUserPiechart

            hits        <- fhits
            datain      <- fdatain
            dataout     <- fdataout
            avgduration <- favgduration
            avgoverhead <- favgoverhead
          } yield {
            Ok(
              Json.obj(
                "statusesPiechart"    -> statusesPiechart,
                "statusesHistogram"   -> statusesHistogram,
                "overheadPercentiles" -> overheadPercentiles,
                "overheadStats"       -> overheadStats,
                "durationPercentiles" -> durationPercentiles,
                "durationStats"       -> durationStats,
                "dataInStats"         -> dataInStats,
                "dataOutStats"        -> dataOutStats,
                "apiKeyPiechart"      -> apiKeyPiechart,
                "userPiechart"        -> userPiechart,
                "hits"                -> hits,
                "dataIn"              -> datain,
                "dataOut"             -> dataout,
                "avgDuration"         -> avgduration,
                "avgOverhead"         -> avgoverhead
              )
            )
          }
        }
      }
    }
  }

  def globalStats(from: Option[String] = None, to: Option[String] = None) = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_GLOBAL_STATS",
        s"User accessed a global stats",
        ctx.from,
        Json.obj()
      )
    )
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(9999)
    val paginationPosition = (paginationPage - 1) * paginationPageSize

    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      env.datastores.serviceDescriptorDataStore.count().flatMap { nbrOfServices =>
        val analyticsService = new AnalyticsReadsServiceImpl(globalConfig, env)

        val fromDate = from.map(f => new DateTime(f.toLong))
        val toDate   = to.map(f => new DateTime(f.toLong))

        for {
          _ <- FastFuture.successful(())

          fhits                = analyticsService.fetchHits(None, fromDate, toDate)
          fdatain              = analyticsService.fetchDataIn(None, fromDate, toDate)
          fdataout             = analyticsService.fetchDataOut(None, fromDate, toDate)
          favgduration         = analyticsService.fetchAvgDuration(None, fromDate, toDate)
          favgoverhead         = analyticsService.fetchAvgOverhead(None, fromDate, toDate)
          fstatusesPiechart    = analyticsService.fetchStatusesPiechart(None, fromDate, toDate)
          fstatusesHistogram   = analyticsService.fetchStatusesHistogram(None, fromDate, toDate)
          foverheadPercentiles = analyticsService.fetchOverheadPercentilesHistogram(None, fromDate, toDate)
          foverheadStats       = analyticsService.fetchOverheadStatsHistogram(None, fromDate, toDate)
          fdurationPercentiles = analyticsService.fetchDurationPercentilesHistogram(None, fromDate, toDate)
          fdurationStats       = analyticsService.fetchDurationStatsHistogram(None, fromDate, toDate)
          fdataInHistogram     = analyticsService.fetchDataInStatsHistogram(None, fromDate, toDate)
          fdataOutHistogram    = analyticsService.fetchDataOutStatsHistogram(None, fromDate, toDate)
          fProductPiechart     = analyticsService.fetchProductPiechart(None, fromDate, toDate, (nbrOfServices * 4).toInt)
          fServicePiechart     = analyticsService.fetchServicePiechart(None, fromDate, toDate, (nbrOfServices * 4).toInt)
          fApiKeyPiechart      = analyticsService.fetchApiKeyPiechart(None, fromDate, toDate)
          fUserPiechart        = analyticsService.fetchUserPiechart(None, fromDate, toDate)

          statusesPiechart    <- fstatusesPiechart
          statusesHistogram   <- fstatusesHistogram
          overheadPercentiles <- foverheadPercentiles
          overheadStats       <- foverheadStats
          durationPercentiles <- fdurationPercentiles
          durationStats       <- fdurationStats
          dataInStats         <- fdataInHistogram
          dataOutStats        <- fdataOutHistogram
          productPiechart     <- fProductPiechart
          servicePiechart     <- fServicePiechart
          apiKeyPiechart      <- fApiKeyPiechart
          userPiechart        <- fUserPiechart

          hits        <- fhits
          datain      <- fdatain
          dataout     <- fdataout
          avgduration <- favgduration
          avgoverhead <- favgoverhead
        } yield
          Ok(
            Json.obj(
              "statusesPiechart"    -> statusesPiechart,
              "statusesHistogram"   -> statusesHistogram,
              "overheadPercentiles" -> overheadPercentiles,
              "overheadStats"       -> overheadStats,
              "durationPercentiles" -> durationPercentiles,
              "durationStats"       -> durationStats,
              "dataInStats"         -> dataInStats,
              "dataOutStats"        -> dataOutStats,
              "hits"                -> hits,
              "dataIn"              -> datain,
              "dataOut"             -> dataout,
              "avgDuration"         -> avgduration,
              "avgOverhead"         -> avgoverhead,
              "productPiechart"     -> productPiechart,
              "servicePiechart"     -> servicePiechart,
              "apiKeyPiechart"      -> apiKeyPiechart,
              "userPiechart"        -> userPiechart
            )
          )
      }
    }
  }

  def serviceEvents(serviceId: String, from: Option[String] = None, to: Option[String] = None) = ApiAction.async {
    ctx =>
      Audit.send(
        AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "ACCESS_SERVICE_EVENTS",
          s"User accessed a service descriptor events",
          ctx.from,
          Json.obj("serviceId" -> serviceId)
        )
      )
      val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
      val paginationPageSize: Int =
        ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(9999)
      val paginationPosition = (paginationPage - 1) * paginationPageSize
      val fromDate           = from.map(f => new DateTime(f.toLong))
      val toDate             = to.map(f => new DateTime(f.toLong))

      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
          case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
          case Some(desc) => {

            val analyticsService = new AnalyticsReadsServiceImpl(globalConfig, env)
            analyticsService
              .events("GatewayEvent",
                      Some(ServiceDescriptorFilterable(desc)),
                      fromDate,
                      toDate,
                      paginationPage,
                      paginationPageSize)
              .map(_.getOrElse(Json.obj()))
              .map { r =>
                logger.debug(s"$r")
                (r \ "events").as[JsValue]
              }
              .map(json => Ok(json))
          }
        }
      }
  }

  def filterableEvents(from: Option[String] = None, to: Option[String] = None) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(9999)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    val fromDate           = from.map(f => new DateTime(f.toLong))
    val toDate             = to.map(f => new DateTime(f.toLong))

    val apiKeyId  = ctx.request.getQueryString("apikey")
    val groupId   = ctx.request.getQueryString("group")
    val serviceId = ctx.request.getQueryString("service")
    serviceId.orElse(apiKeyId).orElse(groupId).map { entityId =>
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        val filterType = (serviceId, apiKeyId, groupId) match {
          case (Some(id), _, _) => "Service"
          case (_, Some(id), _) => "ApiKey"
          case (_, _, Some(id)) => "Group"
          case _                => "None"
        }
        val futureFilterable: Future[Option[Filterable]] = (serviceId, apiKeyId, groupId) match {
          case (Some(id), _, _) =>
            env.datastores.serviceDescriptorDataStore.findById(id).map(_.map(ServiceDescriptorFilterable.apply))
          case (_, Some(id), _) => env.datastores.apiKeyDataStore.findById(id).map(_.map(ApiKeyFilterable.apply))
          case (_, _, Some(id)) =>
            env.datastores.serviceGroupDataStore.findById(id).map(_.map(ServiceGroupFilterable.apply))
          case _ => FastFuture.successful(None)
        }
        futureFilterable.flatMap {
          case None => NotFound(Json.obj("error" -> s"Service with id: '$entityId' not found")).asFuture
          case Some(filterable) => {

            val analyticsService = new AnalyticsReadsServiceImpl(globalConfig, env)
            analyticsService
              .events("GatewayEvent", Some(filterable), fromDate, toDate, paginationPage, paginationPageSize)
              .map(_.getOrElse(Json.obj()))
              .map { r =>
                logger.debug(s"$r")
                (r \ "events").as[JsValue]
              }
              .map(json => Ok(Json.obj("type" -> filterType, "events" -> json)))
          }
        }
      }
    } getOrElse {
      NotFound(Json.obj("error" -> s"No entity found")).asFuture
    }
  }

  def filterableStats(from: Option[String], to: Option[String]) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(9999)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    val apiKeyId           = ctx.request.getQueryString("apikey")
    val groupId            = ctx.request.getQueryString("group")
    val serviceId          = ctx.request.getQueryString("service")
    serviceId.orElse(apiKeyId).orElse(groupId).map { entityId =>
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        val filterType = (serviceId, apiKeyId, groupId) match {
          case (Some(id), _, _) => "Service"
          case (_, Some(id), _) => "ApiKey"
          case (_, _, Some(id)) => "Group"
          case _                => "None"
        }
        val futureFilterable: Future[Option[Filterable]] = (serviceId, apiKeyId, groupId) match {
          case (Some(id), _, _) =>
            env.datastores.serviceDescriptorDataStore.findById(id).map(_.map(ServiceDescriptorFilterable.apply))
          case (_, Some(id), _) => env.datastores.apiKeyDataStore.findById(id).map(_.map(ApiKeyFilterable.apply))
          case (_, _, Some(id)) =>
            env.datastores.serviceGroupDataStore.findById(id).map(_.map(ServiceGroupFilterable.apply))
          case _ => FastFuture.successful(None)
        }
        futureFilterable.flatMap {
          case None => NotFound(Json.obj("error" -> s"Entity: '$entityId' not found")).asFuture
          case Some(filterable) => {

            val analyticsService = new AnalyticsReadsServiceImpl(globalConfig, env)

            val fromDate = from.map(f => new DateTime(f.toLong))
            val toDate   = to.map(f => new DateTime(f.toLong))

            val parts = Seq(
              Part("statusesPiechart", () => analyticsService.fetchStatusesPiechart(Some(filterable), fromDate, toDate)),
              Part("statusesHistogram", () => analyticsService.fetchStatusesHistogram(Some(filterable), fromDate, toDate)),
              Part("overheadPercentiles", () => analyticsService.fetchOverheadPercentilesHistogram(Some(filterable), fromDate, toDate)),
              Part("overheadStats", () => analyticsService.fetchOverheadStatsHistogram(Some(filterable), fromDate, toDate)),
              Part("durationPercentiles", () => analyticsService.fetchDurationPercentilesHistogram(Some(filterable), fromDate, toDate)),
              Part("durationStats", () => analyticsService.fetchDurationStatsHistogram(Some(filterable), fromDate, toDate)),
              Part("dataInStats", () => analyticsService.fetchDataInStatsHistogram(Some(filterable), fromDate, toDate)),
              Part("dataOutStats", () => analyticsService.fetchDataOutStatsHistogram(Some(filterable), fromDate, toDate)),
              Part("hits", () => analyticsService.fetchHits(Some(filterable), fromDate, toDate)),
              Part("dataIn", () => analyticsService.fetchDataIn(Some(filterable), fromDate, toDate)),
              Part("dataOut", () => analyticsService.fetchDataOut(Some(filterable), fromDate, toDate)),
              Part("avgDuration", () => analyticsService.fetchAvgDuration(Some(filterable), fromDate, toDate)),
              Part("avgOverhead", () => analyticsService.fetchAvgOverhead(Some(filterable), fromDate, toDate)),
              Part("apiKeyPiechart", () => (serviceId, apiKeyId, groupId) match {
                case (Some(id), _, _) => analyticsService.fetchApiKeyPiechart(Some(filterable), fromDate, toDate)
                case (_, _, Some(id)) => analyticsService.fetchApiKeyPiechart(Some(filterable), fromDate, toDate)
                case _ => FastFuture.successful(None)
              }),
              Part("servicePiechart", () => (serviceId, apiKeyId, groupId) match {
                case (_, Some(id), _) => analyticsService.fetchServicePiechart(Some(filterable), fromDate, toDate, 0)
                case (_, _, Some(id)) => analyticsService.fetchServicePiechart(Some(filterable), fromDate, toDate, 0)
                case _ => FastFuture.successful(None)
              }),
              Part("userPiechart", () => analyticsService.fetchUserPiechart(Some(filterable), fromDate, toDate))
            )
            FastFuture.sequence(parts.map(_.call(ctx.request))).map { pts =>
              Ok(pts.foldLeft(Json.obj("type" -> filterType))(_ ++ _))
            }
          }
        }
      }
    } getOrElse {
      NotFound(Json.obj("error" -> s"No entity found")).asFuture
    }
  }
}
