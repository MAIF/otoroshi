package controllers
import actions.{ApiAction, UnAuthApiAction}
import akka.http.scaladsl.util.FastFuture
import env.Env
import events.{AdminApiEvent, AnalyticsReadsServiceImpl, Audit}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents}
import utils.future.Implicits._

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

            fhits                = analyticsService.fetchHits(Some(desc), fromDate, toDate)
            fdatain              = analyticsService.fetchDataIn(Some(desc), fromDate, toDate)
            fdataout             = analyticsService.fetchDataOut(Some(desc), fromDate, toDate)
            favgduration         = analyticsService.fetchAvgDuration(Some(desc), fromDate, toDate)
            favgoverhead         = analyticsService.fetchAvgOverhead(Some(desc), fromDate, toDate)
            fstatusesPiechart    = analyticsService.fetchStatusesPiechart(Some(desc), fromDate, toDate)
            fstatusesHistogram   = analyticsService.fetchStatusesHistogram(Some(desc), fromDate, toDate)
            foverheadPercentiles = analyticsService.fetchOverheadPercentilesHistogram(Some(desc), fromDate, toDate)
            foverheadStats       = analyticsService.fetchOverheadStatsHistogram(Some(desc), fromDate, toDate)
            fdurationPercentiles = analyticsService.fetchDurationPercentilesHistogram(Some(desc), fromDate, toDate)
            fdurationStats       = analyticsService.fetchDurationStatsHistogram(Some(desc), fromDate, toDate)
            fdataInHistogram     = analyticsService.fetchDataInStatsHistogram(Some(desc), fromDate, toDate)
            fdataOutHistogram    = analyticsService.fetchDataOutStatsHistogram(Some(desc), fromDate, toDate)
            fApiKeyPiechart      = analyticsService.fetchApiKeyPiechart(Some(desc), fromDate, toDate)
            fUserPiechart        = analyticsService.fetchUserPiechart(Some(desc), fromDate, toDate)


            statusesPiechart    <- fstatusesPiechart
            statusesHistogram   <- fstatusesHistogram
            overheadPercentiles <- foverheadPercentiles
            overheadStats       <- foverheadStats
            durationPercentiles <- fdurationPercentiles
            durationStats       <- fdurationStats
            dataInStats         <- fdataInHistogram
            dataOutStats        <- fdataOutHistogram
            apiKeyPiechart      <- fApiKeyPiechart
            userPiechart      <- fUserPiechart

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
              .events("GatewayEvent", Some(desc), fromDate, toDate, paginationPage, paginationPageSize)
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

}
