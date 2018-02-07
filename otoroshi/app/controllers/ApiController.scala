package controllers

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import javax.management.{Attribute, ObjectName}

import gnieh.diffson.playJson._
import actions.ApiAction
import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import env.Env
import events._
import models._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsSuccess, Json, _}
import play.api.mvc._
import security.IdGenerator
import utils.future.Implicits._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

class ApiController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env) extends AbstractController(cc) {

  implicit lazy val ec  = env.apiExecutionContext
  implicit lazy val mat = env.materializer

  lazy val logger = Logger("otoroshi-admin-api")

  def globalLiveStats() = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_GLOBAL_LIVESTATS",
        "User accessed global livestats",
        ctx.from
      )
    )
    for {
      calls                     <- env.datastores.serviceDescriptorDataStore.globalCalls()
      dataIn                    <- env.datastores.serviceDescriptorDataStore.globalDataIn()
      dataOut                   <- env.datastores.serviceDescriptorDataStore.globalDataOut()
      rate                      <- env.datastores.serviceDescriptorDataStore.globalCallsPerSec()
      duration                  <- env.datastores.serviceDescriptorDataStore.globalCallsDuration()
      overhead                  <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
      dataInRate                <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor("global")
      dataOutRate               <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor("global")
      concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
      //concurrentProcessedRequests <- env.datastores.requestsDataStore.asyncGetProcessedRequests()
    } yield
      Ok(
        Json.obj(
          "calls"                     -> calls,
          "dataIn"                    -> dataIn,
          "dataOut"                   -> dataOut,
          "rate"                      -> rate,
          "duration"                  -> duration,
          "overhead"                  -> overhead,
          "dataInRate"                -> dataInRate,
          "dataOutRate"               -> dataOutRate,
          "concurrentHandledRequests" -> concurrentHandledRequests
          //"concurrentProcessedRequests" -> concurrentProcessedRequests
        )
      )
  }

  def hostMetrics() = ApiAction { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_HOST_METRICS",
        "User accessed global livestats",
        ctx.from
      )
    )
    val appEnv         = Option(System.getenv("APP_ENV")).getOrElse("--")
    val commitId       = Option(System.getenv("COMMIT_ID")).getOrElse("--")
    val instanceNumber = Option(System.getenv("INSTANCE_NUMBER")).getOrElse("--")
    val appId          = Option(System.getenv("APP_ID")).getOrElse("--")
    val instanceId     = Option(System.getenv("INSTANCE_ID")).getOrElse("--")

    val mbs = ManagementFactory.getPlatformMBeanServer
    val rt  = Runtime.getRuntime

    def getProcessCpuLoad(): Double = {
      val name = ObjectName.getInstance("java.lang:type=OperatingSystem")
      val list = mbs.getAttributes(name, Array("ProcessCpuLoad"))
      if (list.isEmpty) return 0.0
      val att   = list.get(0).asInstanceOf[Attribute]
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
            "cpu_usage"         -> getProcessCpuLoad(),
            "heap_used"         -> (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024,
            "heap_size"         -> rt.totalMemory() / 1024 / 1024,
            "live_threads"      -> ManagementFactory.getThreadMXBean.getThreadCount,
            "live_peak_threads" -> ManagementFactory.getThreadMXBean.getPeakThreadCount,
            "daemon_threads"    -> ManagementFactory.getThreadMXBean.getDaemonThreadCount,
            "env"               -> appEnv,
            "commit_id"         -> commitId,
            "instance_number"   -> instanceNumber,
            "app_id"            -> appId,
            "instance_id"       -> instanceId
        )
      )
      .map(Json.stringify)
      .map(slug => s"data: $slug\n\n")
    Ok.chunked(source).as("text/event-stream")
  }

  def serviceLiveStats(id: String, every: Option[Int]) = ApiAction { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_SERVICE_LIVESTATS",
        "User accessed service livestats",
        ctx.from,
        Json.obj("serviceId" -> id)
      )
    )
    def fetch() = id match {
      case "global" =>
        for {
          calls                     <- env.datastores.serviceDescriptorDataStore.globalCalls()
          dataIn                    <- env.datastores.serviceDescriptorDataStore.globalDataIn()
          dataOut                   <- env.datastores.serviceDescriptorDataStore.globalDataOut()
          rate                      <- env.datastores.serviceDescriptorDataStore.globalCallsPerSec()
          duration                  <- env.datastores.serviceDescriptorDataStore.globalCallsDuration()
          overhead                  <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
          dataInRate                <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor("global")
          dataOutRate               <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor("global")
          concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
          //concurrentProcessedRequests <- env.datastores.requestsDataStore.asyncGetProcessedRequests()
        } yield
          Json.obj(
            "calls"                     -> calls,
            "dataIn"                    -> dataIn,
            "dataOut"                   -> dataOut,
            "rate"                      -> rate,
            "duration"                  -> duration,
            "overhead"                  -> overhead,
            "dataInRate"                -> dataInRate,
            "dataOutRate"               -> dataOutRate,
            "concurrentHandledRequests" -> concurrentHandledRequests
            //"concurrentProcessedRequests" -> concurrentProcessedRequests
          )
      case serviceId =>
        for {
          calls                     <- env.datastores.serviceDescriptorDataStore.calls(serviceId)
          dataIn                    <- env.datastores.serviceDescriptorDataStore.dataInFor(serviceId)
          dataOut                   <- env.datastores.serviceDescriptorDataStore.dataOutFor(serviceId)
          rate                      <- env.datastores.serviceDescriptorDataStore.callsPerSec(serviceId)
          duration                  <- env.datastores.serviceDescriptorDataStore.callsDuration(serviceId)
          overhead                  <- env.datastores.serviceDescriptorDataStore.callsOverhead(serviceId)
          dataInRate                <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor(serviceId)
          dataOutRate               <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor(serviceId)
          concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
          //concurrentProcessedRequests <- env.datastores.requestsDataStore.asyncGetProcessedRequests()
        } yield
          Json.obj(
            "calls"                     -> calls,
            "dataIn"                    -> dataIn,
            "dataOut"                   -> dataOut,
            "rate"                      -> rate,
            "duration"                  -> duration,
            "overhead"                  -> overhead,
            "dataInRate"                -> dataInRate,
            "dataOutRate"               -> dataOutRate,
            "concurrentHandledRequests" -> concurrentHandledRequests
            //"concurrentProcessedRequests" -> concurrentProcessedRequests
          )
    }
    every match {
      case Some(millis) =>
        Ok.chunked(
            Source
              .tick(FiniteDuration(0, TimeUnit.MILLISECONDS), FiniteDuration(millis, TimeUnit.MILLISECONDS), NotUsed)
              .flatMapConcat(_ => Source.fromFuture(fetch()))
              .map(json => s"data: ${Json.stringify(json)}\n\n")
          )
          .as("text/event-stream")
      case None => Ok.chunked(Source.single(1).flatMapConcat(_ => Source.fromFuture(fetch()))).as("application/json")
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def allLines() = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_ALL_LINES",
        "User accessed all lines",
        ctx.from
      )
    )
    env.datastores.globalConfigDataStore.allEnv().map {
      case lines => Ok(JsArray(lines.toSeq.map(l => JsString(l))))
    }
  }

  def servicesForALine(line: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_SERVICES_FOR_LINES",
        s"User accessed service list for line $line",
        ctx.from,
        Json.obj("line" -> line)
      )
    )
    env.datastores.serviceDescriptorDataStore.findByEnv(line).map {
      case descriptors => Ok(JsArray(descriptors.drop(paginationPosition).take(paginationPageSize).map(_.toJson)))
    }
  }

  def globalConfig() = ApiAction.async { ctx =>
    env.datastores.globalConfigDataStore.findById("global").map {
      case None => NotFound(Json.obj("error" -> "GlobalConfig not found"))
      case Some(ak) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_GLOBAL_CONFIG",
            s"User accessed global Otoroshi config",
            ctx.from
          )
        )
        Ok(ak.toJson)
      }
    }
  }

  def updateGlobalConfig() = ApiAction.async(parse.json) { ctx =>
    val user = ctx.user.getOrElse(ctx.apiKey.toJson)
    GlobalConfig.fromJsonSafe(ctx.request.body) match {
      case JsError(e) => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad GlobalConfig format")))
      case JsSuccess(ak, _) => {
        env.datastores.globalConfigDataStore.singleton().flatMap { conf =>
          val admEvt = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "UPDATE_GLOBAL_CONFIG",
            s"User updated global Otoroshi config",
            ctx.from,
            ctx.request.body
          )
          Audit.send(admEvt)
          Alerts.send(
            GlobalConfigModification(env.snowflakeGenerator.nextIdStr(), env.env, user, conf.toJson, ak.toJson, admEvt)
          )
          ak.save().map(_ => Ok(Json.obj("done" -> true))) // TODO : rework
        }
      }
    }
  }

  def patchGlobalConfig() = ApiAction.async(parse.json) { ctx =>
    val user = ctx.user.getOrElse(ctx.apiKey.toJson)
    env.datastores.globalConfigDataStore.singleton().flatMap { conf =>
      val currentConfigJson = conf.toJson
      val patch             = JsonPatch(ctx.request.body)
      val newConfigJson     = patch(currentConfigJson)
      GlobalConfig.fromJsonSafe(newConfigJson) match {
        case JsError(e) => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad GlobalConfig format")))
        case JsSuccess(ak, _) => {
          val admEvt = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "UPDATE_GLOBAL_CONFIG",
            s"User updated global Otoroshi config",
            ctx.from,
            ctx.request.body
          )
          Audit.send(admEvt)
          Alerts.send(
            GlobalConfigModification(env.snowflakeGenerator.nextIdStr(), env.env, user, conf.toJson, ak.toJson, admEvt)
          )
          ak.save().map(_ => Ok(Json.obj("done" -> true))) // TODO : rework
        }
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def createGroup() = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "id").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("id" -> IdGenerator.token(64))
      case Some(b) => ctx.request.body.as[JsObject]
    }
    ServiceGroup.fromJsonSafe(body) match {
      case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
      case JsSuccess(group, _) =>
        group.save().map {
          case true => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "CREATE_SERVICE_GROUP",
              s"User created a service group",
              ctx.from,
              body
            )
            Audit.send(event)
            Alerts.send(
              ServiceGroupCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                                       env.env,
                                       ctx.user.getOrElse(ctx.apiKey.toJson),
                                       event)
            )
            Ok(group.toJson)
          }
          case false => InternalServerError(Json.obj("error" -> "Developer not stored ..."))
        }
    }
  }

  def updateGroup(serviceGroupId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with clienId '$serviceGroupId' not found")).asFuture
      case Some(group) => {
        ServiceGroup.fromJsonSafe(ctx.request.body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id != serviceGroupId =>
            BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id == serviceGroupId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE_GROUP",
              s"User updated a service group",
              ctx.from,
              ctx.request.body
            )
            Audit.send(event)
            Alerts.send(
              ServiceGroupUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                       env.env,
                                       ctx.user.getOrElse(ctx.apiKey.toJson),
                                       event)
            )
            newGroup.save().map(_ => Ok(newGroup.toJson))
          }
        }
      }
    }
  }

  def patchGroup(serviceGroupId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with clienId '$serviceGroupId' not found")).asFuture
      case Some(group) => {
        val currentGroupJson = group.toJson
        val patch            = JsonPatch(ctx.request.body)
        val newGroupJson     = patch(currentGroupJson)
        ServiceGroup.fromJsonSafe(newGroupJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id != serviceGroupId =>
            BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id == serviceGroupId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE_GROUP",
              s"User updated a service group",
              ctx.from,
              ctx.request.body
            )
            Audit.send(event)
            Alerts.send(
              ServiceGroupUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                       env.env,
                                       ctx.user.getOrElse(ctx.apiKey.toJson),
                                       event)
            )
            newGroup.save().map(_ => Ok(newGroup.toJson))
          }
        }
      }
    }
  }

  def deleteGroup(serviceGroupId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with id: '$serviceGroupId' not found")).asFuture
      case Some(dev) =>
        dev.delete().map { res =>
          val event: AdminApiEvent = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "DELETE_SERVICE_GROUP",
            s"User deleted a service group",
            ctx.from,
            Json.obj("serviceGroupId" -> serviceGroupId)
          )
          Audit.send(event)
          Alerts.send(
            ServiceGroupDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event)
          )
          Ok(Json.obj("deleted" -> res))
        }
    }
  }

  // TODO
  def addServiceToGroup(serviceGroupId: String) = ApiAction.async(parse.json) { ctx =>
    ???
  }

  // TODO
  def addExistingServiceToGroup(serviceGroupId: String, serviceId: String) = ApiAction.async(parse.json) { ctx =>
    ???
  }

  // TODO
  def removeServiceFromGroup(serviceGroupId: String, serviceId: String) = ApiAction.async { ctx =>
    ???
  }

  def allServiceGroups() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_ALL_SERVICES_GROUPS",
        s"User accessed all services groups",
        ctx.from
      )
    )
    val id: Option[String]   = ctx.request.queryString.get("id").flatMap(_.headOption)
    val name: Option[String] = ctx.request.queryString.get("name").flatMap(_.headOption)
    val hasFilters           = id.orElse(name).orElse(name).isDefined
    env.datastores.serviceGroupDataStore.streamedFindAndMat(_ => true, 50, paginationPage, paginationPageSize).map {
      groups =>
        if (hasFilters) {
          Ok(
            JsArray(
              groups
                .filter {
                  case group if id.isDefined && group.id == id.get       => true
                  case group if name.isDefined && group.name == name.get => true
                  case _                                                 => false
                }
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(groups.map(_.toJson)))
        }
    }
  }

  def serviceGroup(serviceGroupId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).map {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with id: '$serviceGroupId' not found"))
      case Some(group) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICES_GROUP",
            s"User accessed a service group",
            ctx.from,
            Json.obj("serviceGroupId" -> serviceGroupId)
          )
        )
        Ok(group.toJson)
      }
    }
  }

  def serviceGroupServices(serviceGroupId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with id: '$serviceGroupId' not found")).asFuture
      case Some(group) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICES_FROM_SERVICES_GROUP",
            s"User accessed all services from a services group",
            ctx.from,
            Json.obj("serviceGroupId" -> serviceGroupId)
          )
        )
        group.services
          .map(services => Ok(JsArray(services.drop(paginationPosition).take(paginationPageSize).map(_.toJson))))
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def initiateApiKey(groupId: Option[String]) = ApiAction.async { ctx =>
    groupId match {
      case Some(gid) => {
        env.datastores.serviceGroupDataStore.findById(gid).map {
          case Some(group) => {
            val apiKey = env.datastores.apiKeyDataStore.initiateNewApiKey(gid)
            Ok(apiKey.toJson)
          }
          case None => NotFound(Json.obj("error" -> s"Group with id `$gid` does not exist"))
        }
      }
      case None => {
        val apiKey = env.datastores.apiKeyDataStore.initiateNewApiKey("default")
        FastFuture.successful(Ok(apiKey.toJson))
      }
    }
  }

  def initiateServiceGroup() = ApiAction { ctx =>
    val group = env.datastores.serviceGroupDataStore.initiateNewGroup()
    Ok(group.toJson)
  }

  def initiateService() = ApiAction { ctx =>
    val desc = env.datastores.serviceDescriptorDataStore.initiateNewDescriptor()
    Ok(desc.toJson)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def createService() = ApiAction.async(parse.json) { ctx =>
    val rawBody = ctx.request.body.as[JsObject]
    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      val body: JsObject = ((rawBody \ "id").asOpt[String] match {
        case None    => rawBody ++ Json.obj("id" -> IdGenerator.token(64))
        case Some(b) => rawBody
      }) ++ ((rawBody \ "groupId").asOpt[String] match {
        case None if globalConfig.autoLinkToDefaultGroup => rawBody ++ Json.obj("groupId" -> "default")
        case Some(b)                                     => rawBody
      })
      ServiceDescriptor.fromJsonSafe(body) match {
        case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
        case JsSuccess(desc, _) =>
          desc.save().map {
            case false => InternalServerError(Json.obj("error" -> "ServiceDescriptor not stored ..."))
            case true => {
              val event: AdminApiEvent = AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "CREATE_SERVICE",
                s"User created a service",
                ctx.from,
                desc.toJson
              )
              Audit.send(event)
              Alerts.send(
                ServiceCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                                    env.env,
                                    ctx.user.getOrElse(ctx.apiKey.toJson),
                                    event)
              )
              ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).addServices(Seq(desc))
              Ok(desc.toJson)
            }
          }
      }
    }
  }

  def updateService(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceDescriptor with id '$serviceId' not found")).asFuture
      case Some(desc) => {
        ServiceDescriptor.fromJsonSafe(ctx.request.body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id != serviceId =>
            BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id == serviceId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE",
              s"User updated a service",
              ctx.from,
              desc.toJson
            )
            Audit.send(event)
            Alerts.send(
              ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                  env.env,
                                  ctx.user.getOrElse(ctx.apiKey.toJson),
                                  event)
            )
            if (desc.canary.enabled && !newDesc.canary.enabled) {
              env.datastores.canaryDataStore.destroyCanarySession(newDesc.id)
            }
            if (desc.clientConfig != newDesc.clientConfig) {
              env.circuitBeakersHolder.resetCircuitBreakersFor(serviceId) // pretty much useless as its mono instance
            }
            ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
            newDesc.save().map { _ =>
              ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
                .addServices(Seq(newDesc))
              Ok(newDesc.toJson)
            }
          }
        }
      }
    }
  }

  def patchService(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceDescriptor with id '$serviceId' not found")).asFuture
      case Some(desc) => {
        val currentDescJson = desc.toJson
        val patch           = JsonPatch(body)
        val newDescJson     = patch(currentDescJson)
        ServiceDescriptor.fromJsonSafe(newDescJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id != serviceId =>
            BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id == serviceId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE",
              s"User updated a service",
              ctx.from,
              desc.toJson
            )
            Audit.send(event)
            Alerts.send(
              ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                  env.env,
                                  ctx.user.getOrElse(ctx.apiKey.toJson),
                                  event)
            )
            if (desc.canary.enabled && !newDesc.canary.enabled) {
              env.datastores.canaryDataStore.destroyCanarySession(newDesc.id)
            }
            if (desc.clientConfig != newDesc.clientConfig) {
              env.circuitBeakersHolder.resetCircuitBreakersFor(serviceId) // pretty much useless as its mono instance
            }
            ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
            newDesc.save().map { _ =>
              ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
                .addServices(Seq(newDesc))
              Ok(newDesc.toJson)
            }
          }
        }
      }
    }
  }

  def deleteService(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceDescriptor with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        desc.delete().map { res =>
          val admEvt = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "DELETE_SERVICE",
            s"User deleted a service",
            ctx.from,
            desc.toJson
          )
          Audit.send(admEvt)
          Alerts.send(
            ServiceDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                                env.env,
                                ctx.user.getOrElse(ctx.apiKey.toJson),
                                admEvt)
          )
          env.datastores.canaryDataStore.destroyCanarySession(desc.id)
          ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
          Ok(Json.obj("deleted" -> res))
        }
    }
  }

  def allServices() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition            = (paginationPage - 1) * paginationPageSize
    val _env: Option[String]          = ctx.request.queryString.get("env").flatMap(_.headOption)
    val group: Option[String]         = ctx.request.queryString.get("group").flatMap(_.headOption)
    val id: Option[String]            = ctx.request.queryString.get("id").flatMap(_.headOption)
    val name: Option[String]          = ctx.request.queryString.get("name").flatMap(_.headOption)
    val target: Option[String]        = ctx.request.queryString.get("target").flatMap(_.headOption)
    val exposedDomain: Option[String] = ctx.request.queryString.get("exposedDomain").flatMap(_.headOption)
    val domain: Option[String]        = ctx.request.queryString.get("domain").flatMap(_.headOption)
    val subdomain: Option[String]     = ctx.request.queryString.get("subdomain").flatMap(_.headOption)
    val hasFilters = _env
      .orElse(group)
      .orElse(id)
      .orElse(name)
      .orElse(target)
      .orElse(exposedDomain)
      .orElse(domain)
      .orElse(subdomain)
      .isDefined
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_ALL_SERVICES",
        s"User accessed all service descriptors",
        ctx.from,
        Json.obj(
          "env"   -> JsString(_env.getOrElse("--")),
          "group" -> JsString(_env.getOrElse("--"))
        )
      )
    )
    env.datastores.serviceDescriptorDataStore
      .streamedFindAndMat(_ => true, 50, paginationPage, paginationPageSize)
      .map { descs =>
        if (hasFilters) {
          Ok(
            JsArray(
              descs
                .filter {
                  case desc if _env.isDefined && desc.env == _env.get                                 => true
                  case desc if group.isDefined && desc.groupId == group.get                           => true
                  case desc if id.isDefined && desc.id == id.get                                      => true
                  case desc if name.isDefined && desc.name == name.get                                => true
                  case desc if target.isDefined && desc.targets.find(_.asUrl == target.get).isDefined => true
                  case desc if exposedDomain.isDefined && desc.exposedDomain == exposedDomain.get     => true
                  case desc if domain.isDefined && desc.domain == domain.get                          => true
                  case desc if subdomain.isDefined && desc.subdomain == subdomain.get                 => true
                  case _                                                                              => false
                }
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(descs.map(_.toJson)))
        }
      }
  }

  def service(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found"))
      case Some(desc) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE",
            s"User accessed a service descriptor",
            ctx.from,
            Json.obj("serviceId" -> serviceId)
          )
        )
        Ok(desc.toJson)
      }
    }
  }

  def serviceTargets(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found"))
      case Some(desc) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_TARGETS",
            s"User accessed a service targets",
            ctx.from,
            Json.obj("serviceId" -> serviceId)
          )
        )
        Ok(JsArray(desc.targets.map(t => JsString(s"${t.scheme}://${t.host}"))))
      }
    }
  }

  def updateServiceTargets(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        val event = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "UPDATE_SERVICE_TARGETS",
          s"User updated a service targets",
          ctx.from,
          Json.obj("serviceId" -> serviceId, "patch" -> body)
        )
        val actualTargets = JsArray(desc.targets.map(t => JsString(s"${t.scheme}://${t.host}")))
        val patch         = JsonPatch(body)
        val newTargets = patch(actualTargets)
          .as[JsArray]
          .value
          .map(_.as[String])
          .map(s => s.split("://"))
          .map(arr => Target(scheme = arr(0), host = arr(1)))
        val newDesc = desc.copy(targets = newTargets)
        Audit.send(event)
        Alerts.send(
          ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user.getOrElse(ctx.apiKey.toJson), event)
        )
        ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
        newDesc.save().map { _ =>
          ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
            .addServices(Seq(newDesc))
          Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
        }
      }
    }
  }

  def serviceAddTarget(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        val event = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "UPDATE_SERVICE_TARGETS",
          s"User updated a service targets",
          ctx.from,
          Json.obj("serviceId" -> serviceId, "patch" -> body)
        )
        val newTargets = (body \ "target").asOpt[String] match {
          case Some(target) =>
            val parts = target.split("://")
            val tgt   = Target(scheme = parts(0), host = parts(1))
            if (desc.targets.contains(tgt))
              desc.targets
            else
              desc.targets :+ tgt
          case None => desc.targets
        }
        val newDesc = desc.copy(targets = newTargets)
        Audit.send(event)
        Alerts.send(
          ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user.getOrElse(ctx.apiKey.toJson), event)
        )
        ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
        newDesc.save().map { _ =>
          ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
            .addServices(Seq(newDesc))
          Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
        }
      }
    }
  }

  def serviceDeleteTarget(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        val event = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "DELETE_SERVICE_TARGET",
          s"User deleted a service target",
          ctx.from,
          Json.obj("serviceId" -> serviceId, "patch" -> body)
        )
        val newTargets = (body \ "target").asOpt[String] match {
          case Some(target) =>
            val parts = target.split("://")
            val tgt   = Target(scheme = parts(0), host = parts(1))
            if (desc.targets.contains(tgt))
              desc.targets.filterNot(_ == tgt)
            else
              desc.targets
          case None => desc.targets
        }
        val newDesc = desc.copy(targets = newTargets)
        Audit.send(event)
        Alerts.send(
          ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user.getOrElse(ctx.apiKey.toJson), event)
        )
        ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
        newDesc.save().map { _ =>
          ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
            .addServices(Seq(newDesc))
          Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
        }
      }
    }
  }

  def serviceLiveStats(serviceId: String) = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_SERVICE_LIVESTATS",
        s"User accessed a service descriptor livestats",
        ctx.from,
        Json.obj("serviceId" -> serviceId)
      )
    )
    for {
      calls       <- env.datastores.serviceDescriptorDataStore.calls(serviceId)
      dataIn      <- env.datastores.serviceDescriptorDataStore.dataInFor(serviceId)
      dataOut     <- env.datastores.serviceDescriptorDataStore.dataOutFor(serviceId)
      rate        <- env.datastores.serviceDescriptorDataStore.callsPerSec(serviceId)
      duration    <- env.datastores.serviceDescriptorDataStore.callsDuration(serviceId)
      overhead    <- env.datastores.serviceDescriptorDataStore.callsOverhead(serviceId)
      dataInRate  <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor(serviceId)
      dataOutRate <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor(serviceId)
    } yield
      Ok(
        Json.obj(
          "calls"       -> calls,
          "dataIn"      -> dataIn,
          "dataOut"     -> dataOut,
          "rate"        -> rate,
          "duration"    -> duration,
          "overhead"    -> overhead,
          "dataInRate"  -> dataInRate,
          "dataOutRate" -> dataOutRate
        )
      )
  }

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
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        env.datastores.globalConfigDataStore.singleton().flatMap { config =>
          config.analyticsEventsUrl match {
            case None => Ok(Json.obj("stats" -> Json.arr(), "agg" -> Json.arr())).asFuture
            case Some(source) => {

              def fetchHits() =
                env.Ws
                  .url(source.url + "/GatewayEvent/_count")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchDataIn() =
                env.Ws
                  .url(source.url + "/GatewayEvent/data.dataIn/_sum")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchDataOut() =
                env.Ws
                  .url(source.url + "/GatewayEvent/data.dataOut/_sum")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchAvgDuration() =
                env.Ws
                  .url(source.url + "/GatewayEvent/duration/_avg")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchAvgOverhead() =
                env.Ws
                  .url(source.url + "/GatewayEvent/overhead/_avg")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchStatusesPiechart() =
                env.Ws
                  .url(source.url + "/GatewayEvent/status/_piechart")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchStatusesHistogram() =
                env.Ws
                  .url(source.url + "/httpStatus/_histogram")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchDataInStatsHistogram() =
                env.Ws
                  .url(source.url + "/GatewayEvent/data.dataIn/_histogram/stats")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchDataOutStatsHistogram() =
                env.Ws
                  .url(source.url + "/GatewayEvent/data.dataOut/_histogram/stats")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchDurationStatsHistogram() =
                env.Ws
                  .url(source.url + "/GatewayEvent/duration/_histogram/stats")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchDurationPercentilesHistogram() =
                env.Ws
                  .url(source.url + "/GatewayEvent/duration/_histogram/percentiles")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchOverheadPercentilesHistogram() =
                env.Ws
                  .url(source.url + "/GatewayEvent/overhead/_histogram/percentiles")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              def fetchOverheadStatsHistogram() =
                env.Ws
                  .url(source.url + "/GatewayEvent/overhead/_histogram/stats")
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "services" -> desc.name,
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                    "to" -> to
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now())
                      .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                  )
                  .get()
                  .map(_.json)

              for {
                _ <- FastFuture.successful(())

                fhits                = fetchHits()
                fdatain              = fetchDataIn()
                fdataout             = fetchDataOut()
                favgduration         = fetchAvgDuration()
                favgoverhead         = fetchAvgOverhead()
                fstatusesPiechart    = fetchStatusesPiechart()
                fstatusesHistogram   = fetchStatusesHistogram()
                foverheadPercentiles = fetchOverheadPercentilesHistogram()
                foverheadStats       = fetchOverheadStatsHistogram()
                fdurationPercentiles = fetchDurationPercentilesHistogram()
                fdurationStats       = fetchDurationStatsHistogram()
                fdataInHistogram     = fetchDataInStatsHistogram()
                fdataOutHistogram    = fetchDataOutStatsHistogram()

                statusesPiechart    <- fstatusesPiechart
                statusesHistogram   <- fstatusesHistogram
                overheadPercentiles <- foverheadPercentiles
                overheadStats       <- foverheadStats
                durationPercentiles <- fdurationPercentiles
                durationStats       <- fdurationStats
                dataInStats         <- fdataInHistogram
                dataOutStats        <- fdataOutHistogram

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
                    "avgOverhead"         -> avgoverhead
                  )
                )
            }
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

    env.datastores.globalConfigDataStore.singleton().flatMap { config =>
      env.datastores.serviceDescriptorDataStore.count().flatMap { nbrOfServices =>
        config.analyticsEventsUrl match {
          case None => Ok(Json.obj("stats" -> Json.arr(), "agg" -> Json.arr())).asFuture
          case Some(source) => {

            def fetchHits() =
              env.Ws
                .url(source.url + "/GatewayEvent/_count")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchDataIn() =
              env.Ws
                .url(source.url + "/GatewayEvent/data.dataIn/_sum")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchDataOut() =
              env.Ws
                .url(source.url + "/GatewayEvent/data.dataOut/_sum")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchAvgDuration() =
              env.Ws
                .url(source.url + "/GatewayEvent/duration/_avg")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchAvgOverhead() =
              env.Ws
                .url(source.url + "/GatewayEvent/overhead/_avg")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchStatusesPiechart() =
              env.Ws
                .url(source.url + "/GatewayEvent/status/_piechart")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchStatusesHistogram() =
              env.Ws
                .url(source.url + "/httpStatus/_histogram")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchDataInStatsHistogram() =
              env.Ws
                .url(source.url + "/GatewayEvent/data.dataIn/_histogram/stats")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchDataOutStatsHistogram() =
              env.Ws
                .url(source.url + "/GatewayEvent/data.dataOut/_histogram/stats")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchDurationStatsHistogram() =
              env.Ws
                .url(source.url + "/GatewayEvent/duration/_histogram/stats")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchDurationPercentilesHistogram() =
              env.Ws
                .url(source.url + "/GatewayEvent/duration/_histogram/percentiles")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchOverheadPercentilesHistogram() =
              env.Ws
                .url(source.url + "/GatewayEvent/overhead/_histogram/percentiles")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchOverheadStatsHistogram() =
              env.Ws
                .url(source.url + "/GatewayEvent/overhead/_histogram/stats")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchProductPiechart() =
              env.Ws
                .url(source.url + "/GatewayEvent/@product/_piechart")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "size" -> (nbrOfServices * 4).toString, // hell yeah
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            def fetchServicePiechart() =
              env.Ws
                .url(source.url + "/GatewayEvent/@service/_piechart")
                .withHttpHeaders(source.headers.toSeq: _*)
                .withQueryStringParameters(
                  "size" -> (nbrOfServices * 4).toString, // hell yeah
                  "from" -> from
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now().minusHours(1))
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                  "to" -> to
                    .map(f => new DateTime(f.toLong))
                    .getOrElse(DateTime.now())
                    .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                )
                .get()
                .map(_.json)

            for {
              _ <- FastFuture.successful(())

              fhits                = fetchHits()
              fdatain              = fetchDataIn()
              fdataout             = fetchDataOut()
              favgduration         = fetchAvgDuration()
              favgoverhead         = fetchAvgOverhead()
              fstatusesPiechart    = fetchStatusesPiechart()
              fstatusesHistogram   = fetchStatusesHistogram()
              foverheadPercentiles = fetchOverheadPercentilesHistogram()
              foverheadStats       = fetchOverheadStatsHistogram()
              fdurationPercentiles = fetchDurationPercentilesHistogram()
              fdurationStats       = fetchDurationStatsHistogram()
              fdataInHistogram     = fetchDataInStatsHistogram()
              fdataOutHistogram    = fetchDataOutStatsHistogram()
              fProductPiechart     = fetchProductPiechart()
              fServicePiechart     = fetchServicePiechart()

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
                  "servicePiechart"     -> servicePiechart
                )
              )
          }
        }
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
      env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
        case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
        case Some(desc) => {
          env.datastores.globalConfigDataStore.singleton().flatMap { config =>
            config.analyticsEventsUrl match {
              case None => Ok(Json.arr()).asFuture
              case Some(source) => {
                env.Ws
                  .url(source.url)
                  .withHttpHeaders(source.headers.toSeq: _*)
                  .withQueryStringParameters(
                    "@type" -> "GatewayEvent",
                    "from" -> from
                      .map(f => new DateTime(f.toLong))
                      .getOrElse(DateTime.now().minusHours(1))
                      .getMillis
                      .toString,
                    "to"         -> to.map(f => new DateTime(f.toLong)).getOrElse(DateTime.now()).getMillis.toString,
                    "@serviceId" -> desc.id,
                    "pageSize"   -> paginationPageSize.toString,
                    "pageNum"    -> paginationPage.toString
                  )
                  .get()
                  .map { r =>
                    logger.debug(r.body)
                    (r.json.as[JsObject] \ "events").as[JsValue]
                  }
                  .map(json => Ok(json))
              }
            }
          }
        }
      }
  }

  def serviceHealth(serviceId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_HEALTH",
            s"User accessed a service descriptor helth",
            ctx.from,
            Json.obj("serviceId" -> serviceId)
          )
        )
        env.datastores.healthCheckDataStore
          .findAll(desc)
          .map(evts => Ok(JsArray(evts.drop(paginationPosition).take(paginationPageSize).map(_.toJson))))
      }
    }
  }

  def serviceTemplate(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        env.datastores.errorTemplateDataStore.findById(desc.id).map {
          case Some(template) => Ok(template.toJson)
          case None           => NotFound(Json.obj("error" -> "template not found"))
        }
      }
    }
  }

  def updateServiceTemplate(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "serviceId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("serviceId" -> serviceId)
      case Some(_) => ctx.request.body.as[JsObject]
    }
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(_) => {
        ErrorTemplate.fromJsonSafe(body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ErrorTemplate format")).asFuture
          case JsSuccess(errorTemplate, _) =>
            env.datastores.errorTemplateDataStore.set(errorTemplate).map {
              case false => InternalServerError(Json.obj("error" -> "ErrorTemplate not stored ..."))
              case true => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_ERROR_TEMPLATE",
                  s"User updated an error template",
                  ctx.from,
                  errorTemplate.toJson
                )
                Audit.send(event)
                Ok(errorTemplate.toJson)
              }
            }
        }
      }
    }
  }

  def createServiceTemplate(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "serviceId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("serviceId" -> serviceId)
      case Some(_) => ctx.request.body.as[JsObject]
    }
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(_) => {
        ErrorTemplate.fromJsonSafe(body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> s"Bad ErrorTemplate format $e")).asFuture
          case JsSuccess(errorTemplate, _) =>
            env.datastores.errorTemplateDataStore.set(errorTemplate).map {
              case false => InternalServerError(Json.obj("error" -> "ErrorTemplate not stored ..."))
              case true => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "CREATE_ERROR_TEMPLATE",
                  s"User created an error template",
                  ctx.from,
                  errorTemplate.toJson
                )
                Audit.send(event)
                Ok(errorTemplate.toJson)
              }
            }
        }
      }
    }
  }

  def deleteServiceTemplate(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        env.datastores.errorTemplateDataStore.findById(desc.id).flatMap {
          case None => NotFound(Json.obj("error" -> "template not found")).asFuture
          case Some(errorTemplate) =>
            env.datastores.errorTemplateDataStore.delete(desc.id).map { _ =>
              val event: AdminApiEvent = AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "DELETE_ERROR_TEMPLATE",
                s"User deleted an error template",
                ctx.from,
                errorTemplate.toJson
              )
              Audit.send(event)
              Ok(Json.obj("done" -> true))
            }
        }
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def createApiKey(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "clientId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("clientId" -> IdGenerator.token(16))
      case Some(b) => ctx.request.body.as[JsObject]
    }
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id $serviceId not found")).asFuture
      case Some(desc) =>
        desc.group.flatMap {
          case None => NotFound(Json.obj("error" -> s"Service group not found")).asFuture
          case Some(group) => {
            val apiKeyJson = (body \ "authorizedGroup").asOpt[String] match {
              case None                                 => body ++ Json.obj("authorizedGroup" -> group.id)
              case Some(groupId) if groupId != group.id => body ++ Json.obj("authorizedGroup" -> group.id)
              case Some(groupId) if groupId == group.id => body
            }
            ApiKey.fromJsonSafe(apiKeyJson) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(apiKey, _) =>
                apiKey.save().map {
                  case false => InternalServerError(Json.obj("error" -> "ApiKey not stored ..."))
                  case true => {
                    val event: AdminApiEvent = AdminApiEvent(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      Some(ctx.apiKey),
                      ctx.user,
                      "CREATE_APIKEY",
                      s"User created an ApiKey",
                      ctx.from,
                      desc.toJson
                    )
                    Audit.send(event)
                    Alerts.send(
                      ApiKeyCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                                         env.env,
                                         ctx.user.getOrElse(ctx.apiKey.toJson),
                                         event)
                    )
                    env.datastores.apiKeyDataStore.addFastLookupByService(serviceId, apiKey).map { _ =>
                      env.datastores.apiKeyDataStore.findAll()
                    }
                    Ok(apiKey.toJson)
                  }
                }
            }
          }
        }
    }
  }

  def createApiKeyFromGroup(groupId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "clientId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("clientId" -> IdGenerator.token(16))
      case Some(b) => ctx.request.body.as[JsObject]
    }
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service group not found")).asFuture
      case Some(group) => {
        val apiKeyJson = (body \ "authorizedGroup").asOpt[String] match {
          case None                         => body ++ Json.obj("authorizedGroup" -> group.id)
          case Some(gid) if gid != group.id => body ++ Json.obj("authorizedGroup" -> group.id)
          case Some(gid) if gid == group.id => body
        }
        ApiKey.fromJsonSafe(apiKeyJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
          case JsSuccess(apiKey, _) =>
            apiKey.save().map {
              case false => InternalServerError(Json.obj("error" -> "ApiKey not stored ..."))
              case true => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "CREATE_APIKEY",
                  s"User created an ApiKey",
                  ctx.from,
                  group.toJson
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event)
                )
                env.datastores.apiKeyDataStore.addFastLookupByGroup(groupId, apiKey).map { _ =>
                  env.datastores.apiKeyDataStore.findAll()
                }
                Ok(apiKey.toJson)
              }
            }
        }
      }
    }
  }

  def updateApiKey(serviceId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            ApiKey.fromJsonSafe(ctx.request.body) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  desc.toJson
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def patchApiKey(serviceId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            val currentApiKeyJson = apiKey.toJson
            val patch             = JsonPatch(ctx.request.body)
            val newApiKeyJson     = patch(currentApiKeyJson)
            ApiKey.fromJsonSafe(newApiKeyJson) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  desc.toJson
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def updateApiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            ApiKey.fromJsonSafe(ctx.request.body) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  group.toJson
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def patchApiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            val currentApiKeyJson = apiKey.toJson
            val patch             = JsonPatch(ctx.request.body)
            val newApiKeyJson     = patch(currentApiKeyJson)
            ApiKey.fromJsonSafe(newApiKeyJson) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  group.toJson
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def deleteApiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "DELETE_APIKEY",
              s"User deleted an ApiKey",
              ctx.from,
              group.toJson
            )
            Audit.send(event)
            Alerts.send(
              ApiKeyDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                                 env.env,
                                 ctx.user.getOrElse(ctx.apiKey.toJson),
                                 event)
            )
            env.datastores.apiKeyDataStore.deleteFastLookupByGroup(groupId, apiKey)
            apiKey.delete().map(res => Ok(Json.obj("deleted" -> true)))
          }
        }
    }
  }

  def deleteApiKey(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "DELETE_APIKEY",
              s"User deleted an ApiKey",
              ctx.from,
              desc.toJson
            )
            Audit.send(event)
            Alerts.send(
              ApiKeyDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                                 env.env,
                                 ctx.user.getOrElse(ctx.apiKey.toJson),
                                 event)
            )
            env.datastores.apiKeyDataStore.deleteFastLookupByService(serviceId, apiKey)
            apiKey.delete().map(res => Ok(Json.obj("deleted" -> true)))
          }
        }
    }
  }

  def apiKeys(serviceId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition       = (paginationPage - 1) * paginationPageSize
    val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
    val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
    val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
    val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
    val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
    env.datastores.apiKeyDataStore.findByService(serviceId).fold {
      case Failure(_) => NotFound(Json.obj("error" -> s"ApiKeys for service with id: '$serviceId' does not exist"))
      case Success(apiKeys) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_APIKEYS",
            s"User accessed apikeys from a service descriptor",
            ctx.from,
            Json.obj("serviceId" -> serviceId)
          )
        )
        if (hasFilters) {
          Ok(
            JsArray(
              apiKeys
                .filter {
                  case keys if group.isDefined && keys.authorizedGroup == group.get       => true
                  case keys if clientId.isDefined && keys.clientId == clientId.get        => true
                  case keys if name.isDefined && keys.clientName == name.get              => true
                  case keys if enabled.isDefined && keys.enabled == enabled.get.toBoolean => true
                  case _                                                                  => false
                }
                .drop(paginationPosition)
                .take(paginationPageSize)
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(apiKeys.map(_.toJson)))
        }
      }
    }
  }

  def apiKeysFromGroup(groupId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition       = (paginationPage - 1) * paginationPageSize
    val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
    val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
    val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
    val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
    val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
    env.datastores.apiKeyDataStore.findByGroup(groupId).fold {
      case Failure(_) => NotFound(Json.obj("error" -> s"ApiKeys for group with id: '$groupId' does not exist"))
      case Success(apiKeys) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_APIKEYS",
            s"User accessed apikeys from a group",
            ctx.from,
            Json.obj("groupId" -> groupId)
          )
        )
        if (hasFilters) {
          Ok(
            JsArray(
              apiKeys
                .filter {
                  case keys if group.isDefined && keys.authorizedGroup == group.get       => true
                  case keys if clientId.isDefined && keys.clientId == clientId.get        => true
                  case keys if name.isDefined && keys.clientName == name.get              => true
                  case keys if enabled.isDefined && keys.enabled == enabled.get.toBoolean => true
                  case _                                                                  => false
                }
                .drop(paginationPosition)
                .take(paginationPageSize)
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(apiKeys.map(_.toJson)))
        }
      }
    }
  }

  def allApiKeys() = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_ALL_APIKEYS",
        s"User accessed all apikeys",
        ctx.from
      )
    )
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition       = (paginationPage - 1) * paginationPageSize
    val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
    val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
    val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
    val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
    val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
    env.datastores.apiKeyDataStore.streamedFindAndMat(_ => true, 50, paginationPage, paginationPageSize).map { keys =>
      if (hasFilters) {
        Ok(
          JsArray(
            keys
              .filter {
                case keys if group.isDefined && keys.authorizedGroup == group.get       => true
                case keys if clientId.isDefined && keys.clientId == clientId.get        => true
                case keys if name.isDefined && keys.clientName == name.get              => true
                case keys if enabled.isDefined && keys.enabled == enabled.get.toBoolean => true
                case _                                                                  => false
              }
              .map(_.toJson)
          )
        )
      } else {
        Ok(JsArray(keys.map(_.toJson)))
      }
    }
  }

  def apiKey(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).map {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found"))
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            )
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY",
                s"User accessed an apikey from a service descriptor",
                ctx.from,
                Json.obj("serviceId" -> serviceId, "clientId" -> clientId)
              )
            )
            Ok(apiKey.toJson)
          }
        }
    }
  }

  def apiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).map {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found"))
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'"))
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY",
                s"User accessed an apikey from a service descriptor",
                ctx.from,
                Json.obj("groupId" -> groupId, "clientId" -> clientId)
              )
            )
            Ok(apiKey.toJson)
          }
        }
    }
  }

  def apiKeyGroup(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId =>
            apiKey.group.map {
              case None => NotFound(Json.obj("error" -> s"ServiceGroup for ApiKey '$clientId' not found"))
              case Some(group) => {
                Audit.send(
                  AdminApiEvent(
                    env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    Some(ctx.apiKey),
                    ctx.user,
                    "ACCESS_SERVICE_APIKEY_GROUP",
                    s"User accessed an apikey servicegroup from a service descriptor",
                    ctx.from,
                    Json.obj("serviceId" -> serviceId, "clientId" -> clientId)
                  )
                )
                Ok(group.toJson)
              }
            }
        }
    }
  }

  // fixme : use a body to update
  def updateApiKeyGroup(serviceId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId =>
            apiKey.group.flatMap {
              case None => NotFound(Json.obj("error" -> s"ServiceGroup for ApiKey '$clientId' not found")).asFuture
              case Some(group) => {
                val newApiKey = apiKey.copy(authorizedGroup = group.id)
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  desc.toJson
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
        }
    }
  }

  def apiKeyQuotas(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                ctx.from,
                Json.obj("serviceId" -> serviceId, "clientId" -> clientId)
              )
            )
            apiKey.remainingQuotas().map(rq => Ok(rq.toJson))
          }
        }
    }
  }

  def apiKeyFromGroupQuotas(groupId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                ctx.from,
                Json.obj("groupId" -> groupId, "clientId" -> clientId)
              )
            )
            apiKey.remainingQuotas().map(rq => Ok(rq.toJson))
          }
        }
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def serviceCanaryMembers(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.canaryDataStore.canaryCampaign(serviceId).map { campaign =>
      Ok(
        Json.obj(
          "canaryUsers"   -> campaign.canaryUsers,
          "standardUsers" -> campaign.standardUsers
        )
      )
    }
  }

  def resetServiceCanaryMembers(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.canaryDataStore.destroyCanarySession(serviceId).map { done =>
      Ok(Json.obj("done" -> done))
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def fullExport() = ApiAction.async { ctx =>
    env.datastores.globalConfigDataStore.fullExport().map { e =>
      val event = AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "FULL_OTOROSHI_EXPORT",
        s"Admin exported Otoroshi",
        ctx.from,
        e
      )
      Audit.send(event)
      Alerts.send(
        OtoroshiExportAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user.getOrElse(Json.obj()), event, e)
      )
      Ok(Json.prettyPrint(e)).as("application/json")
    }
  }

  def fullImportFromFile() = ApiAction.async(parse.temporaryFile) { ctx =>
    val source = scala.io.Source.fromFile(ctx.request.body.path.toFile, "utf-8").getLines().mkString("\n")
    val json   = Json.parse(source).as[JsObject]
    env.datastores.globalConfigDataStore
      .fullImport(json)
      .map(_ => Ok(Json.obj("done" -> true)))
      .recover {
        case e => InternalServerError(Json.obj("error" -> e.getMessage))
      }
  }

  def fullImport() = ApiAction.async(parse.json) { ctx =>
    val json = ctx.request.body.as[JsObject]
    env.datastores.globalConfigDataStore
      .fullImport(json)
      .map(_ => Ok(Json.obj("done" -> true)))
      .recover {
        case e => InternalServerError(Json.obj("error" -> e.getMessage))
      }
  }
}
