package controllers.adminapi

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

import otoroshi.actions.ApiAction
import akka.NotUsed
import akka.stream.scaladsl.Source
import otoroshi.cluster.StatsView
import env.Env
import otoroshi.events.{AdminApiEvent, Audit}
import javax.management.{Attribute, ObjectName}
import otoroshi.models.RightsChecker.Anyone
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents}
import otoroshi.utils.syntax.implicits._

import scala.concurrent.duration.FiniteDuration

class StatsController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-stats-api")

  private def avgDouble(value: Double, extractor: StatsView => Double, stats: Seq[StatsView]): Double = {
    (if (value == Double.NaN || value == Double.NegativeInfinity || value == Double.PositiveInfinity) {
      0.0
    } else {
      stats.map(extractor).:+(value).fold(0.0)(_ + _) / (stats.size + 1)
    }).applyOn {
      case Double.NaN => 0.0
      case Double.NegativeInfinity => 0.0
      case Double.PositiveInfinity => 0.0
      case v if v.toString == "NaN" => 0.0
      case v if v.toString == "Infinity" => 0.0
      case v => v
    }
  }

  private def sumDouble(value: Double, extractor: StatsView => Double, stats: Seq[StatsView]): Double = {
    if (value == Double.NaN || value == Double.NegativeInfinity || value == Double.PositiveInfinity) {
      0.0
    } else {
      stats.map(extractor).:+(value).fold(0.0)(_ + _)
    }.applyOn {
      case Double.NaN => 0.0
      case Double.NegativeInfinity => 0.0
      case Double.PositiveInfinity => 0.0
      case v if v.toString == "NaN" => 0.0
      case v if v.toString == "Infinity" => 0.0
      case v => v
    }
  }

  def globalLiveStats() = ApiAction.async { ctx =>
    ctx.checkRights(Anyone) {
      Audit.send(
        AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "ACCESS_GLOBAL_LIVESTATS",
          "User accessed global livestats",
          ctx.from,
          ctx.ua
        )
      )
      for {
        calls <- env.datastores.serviceDescriptorDataStore.globalCalls()
        dataIn <- env.datastores.serviceDescriptorDataStore.globalDataIn()
        dataOut <- env.datastores.serviceDescriptorDataStore.globalDataOut()
        rate <- env.datastores.serviceDescriptorDataStore.globalCallsPerSec()
        duration <- env.datastores.serviceDescriptorDataStore.globalCallsDuration()
        overhead <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
        dataInRate <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor("global")
        dataOutRate <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor("global")
        concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
        membersStats <- env.datastores.clusterStateDataStore.getMembers().map(_.map(_.statsView))
      } yield
        Ok(
          Json.obj(
            "calls" -> calls,
            "dataIn" -> dataIn,
            "dataOut" -> dataOut,
            "rate" -> sumDouble(rate, _.rate, membersStats),
            "duration" -> avgDouble(duration, _.duration, membersStats),
            "overhead" -> avgDouble(overhead, _.overhead, membersStats),
            "dataInRate" -> sumDouble(dataInRate, _.dataInRate, membersStats),
            "dataOutRate" -> sumDouble(dataOutRate, _.dataOutRate, membersStats),
            "concurrentHandledRequests" -> sumDouble(concurrentHandledRequests.toDouble,
              _.concurrentHandledRequests.toDouble,
              membersStats).toLong
          )
        )
    }
  }

  def hostMetrics() = ApiAction.async { ctx =>
    ctx.checkRights(Anyone) {
      Audit.send(
        AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "ACCESS_HOST_METRICS",
          "User accessed global livestats",
          ctx.from,
          ctx.ua
        )
      )
      val appEnv = Option(System.getenv("APP_ENV")).getOrElse("--")
      val commitId = Option(System.getenv("COMMIT_ID")).getOrElse("--")
      val instanceNumber = Option(System.getenv("INSTANCE_NUMBER")).getOrElse("--")
      val appId = Option(System.getenv("APP_ID")).getOrElse("--")
      val instanceId = Option(System.getenv("INSTANCE_ID")).getOrElse("--")

      val mbs = ManagementFactory.getPlatformMBeanServer
      val rt = Runtime.getRuntime

      def getProcessCpuLoad(): Double = {
        val name = ObjectName.getInstance("java.lang:type=OperatingSystem")
        val list = mbs.getAttributes(name, Array("ProcessCpuLoad"))
        if (list.isEmpty) return 0.0
        val att = list.get(0).asInstanceOf[Attribute]
        val value = att.getValue.asInstanceOf[Double]
        if (value == -1.0) return 0.0
        (value * 1000) / 10.0
        // ManagementFactory.getOperatingSystemMXBean.getSystemLoadAverage
      }

      val source = Source
        .tick(FiniteDuration(0, TimeUnit.MILLISECONDS), FiniteDuration(2000, TimeUnit.MILLISECONDS), NotUsed)
        .map(
          _ =>
            Json.obj(
              "cpu_usage" -> getProcessCpuLoad(),
              "heap_used" -> (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024,
              "heap_size" -> rt.totalMemory() / 1024 / 1024,
              "live_threads" -> ManagementFactory.getThreadMXBean.getThreadCount,
              "live_peak_threads" -> ManagementFactory.getThreadMXBean.getPeakThreadCount,
              "daemon_threads" -> ManagementFactory.getThreadMXBean.getDaemonThreadCount,
              "env" -> appEnv,
              "commit_id" -> commitId,
              "instance_number" -> instanceNumber,
              "app_id" -> appId,
              "instance_id" -> instanceId
            )
        )
        .map(Json.stringify)
        .map(slug => s"data: $slug\n\n")
      Ok.chunked(source).as("text/event-stream").future
    }
  }

  def serviceLiveStats(id: String, every: Option[Int]) = ApiAction.async { ctx =>
    ctx.canReadService(id) {
      Audit.send(
        AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "ACCESS_SERVICE_LIVESTATS",
          "User accessed service livestats",
          ctx.from,
          ctx.ua,
          Json.obj("serviceId" -> id)
        )
      )

      def fetch() = id match {
        case "global" =>
          for {
            calls <- env.datastores.serviceDescriptorDataStore.globalCalls()
            dataIn <- env.datastores.serviceDescriptorDataStore.globalDataIn()
            dataOut <- env.datastores.serviceDescriptorDataStore.globalDataOut()
            rate <- env.datastores.serviceDescriptorDataStore.globalCallsPerSec()
            duration <- env.datastores.serviceDescriptorDataStore.globalCallsDuration()
            overhead <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
            dataInRate <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor("global")
            dataOutRate <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor("global")
            concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
            membersStats <- env.datastores.clusterStateDataStore.getMembers().map(_.map(_.statsView))
          } yield {
            Json.obj(
              "calls" -> calls,
              "dataIn" -> dataIn,
              "dataOut" -> dataOut,
              "rate" -> sumDouble(rate, _.rate, membersStats),
              "duration" -> avgDouble(duration, _.duration, membersStats),
              "overhead" -> avgDouble(overhead, _.overhead, membersStats),
              "dataInRate" -> sumDouble(dataInRate, _.dataInRate, membersStats),
              "dataOutRate" -> sumDouble(dataOutRate, _.dataOutRate, membersStats),
              "concurrentHandledRequests" -> sumDouble(concurrentHandledRequests.toDouble,
                _.concurrentHandledRequests.toDouble,
                membersStats).toLong
            )
          }
        case serviceId =>
          for {
            calls <- env.datastores.serviceDescriptorDataStore.calls(serviceId)
            dataIn <- env.datastores.serviceDescriptorDataStore.dataInFor(serviceId)
            dataOut <- env.datastores.serviceDescriptorDataStore.dataOutFor(serviceId)
            rate <- env.datastores.serviceDescriptorDataStore.callsPerSec(serviceId)
            duration <- env.datastores.serviceDescriptorDataStore.callsDuration(serviceId)
            overhead <- env.datastores.serviceDescriptorDataStore.callsOverhead(serviceId)
            dataInRate <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor(serviceId)
            dataOutRate <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor(serviceId)
            concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
            membersStats <- env.datastores.clusterStateDataStore.getMembers().map(_.map(_.statsView))
          } yield
            Json.obj(
              "calls" -> calls,
              "dataIn" -> dataIn,
              "dataOut" -> dataOut,
              "rate" -> sumDouble(rate, _.rate, membersStats),
              "duration" -> avgDouble(duration, _.duration, membersStats),
              "overhead" -> avgDouble(overhead, _.overhead, membersStats),
              "dataInRate" -> sumDouble(dataInRate, _.dataInRate, membersStats),
              "dataOutRate" -> sumDouble(dataOutRate, _.dataOutRate, membersStats),
              "concurrentHandledRequests" -> sumDouble(concurrentHandledRequests.toDouble,
                _.concurrentHandledRequests.toDouble,
                membersStats).toLong
            )
      }

      every match {
        case Some(millis) =>
          Ok.chunked(
            Source
              .tick(FiniteDuration(0, TimeUnit.MILLISECONDS), FiniteDuration(millis, TimeUnit.MILLISECONDS), NotUsed)
              .flatMapConcat(_ => Source.future(fetch()))
              .map(json => s"data: ${Json.stringify(json)}\n\n")
          )
            .as("text/event-stream").future
        case None => Ok.chunked(Source.single(1).flatMapConcat(_ => Source.future(fetch()))).as("application/json").future
      }
    }
  }

}
