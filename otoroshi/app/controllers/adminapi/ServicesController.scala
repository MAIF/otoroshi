//[REMOVE SERVICEDESC] package otoroshi.controllers.adminapi
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC] import otoroshi.actions.{ApiAction, ApiActionContext}
//[REMOVE SERVICEDESC] import org.apache.pekko.util.ByteString
//[REMOVE SERVICEDESC] import otoroshi.env.Env
//[REMOVE SERVICEDESC] import otoroshi.events.*
//[REMOVE SERVICEDESC] import otoroshi.models.{ErrorTemplate, ServiceDescriptor, ServiceDescriptorQuery, Target}
//[REMOVE SERVICEDESC] import otoroshi.next.models.NgRoute
//[REMOVE SERVICEDESC] import otoroshi.utils.controllers.{
//[REMOVE SERVICEDESC]   AdminApiHelper,
//[REMOVE SERVICEDESC]   ApiError,
//[REMOVE SERVICEDESC]   BulkControllerHelper,
//[REMOVE SERVICEDESC]   CrudControllerHelper,
//[REMOVE SERVICEDESC]   EntityAndContext,
//[REMOVE SERVICEDESC]   JsonApiError,
//[REMOVE SERVICEDESC]   NoEntityAndContext,
//[REMOVE SERVICEDESC]   OptionalEntityAndContext,
//[REMOVE SERVICEDESC]   SendAuditAndAlert,
//[REMOVE SERVICEDESC]   SeqEntityAndContext
//[REMOVE SERVICEDESC] }
//[REMOVE SERVICEDESC] import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
//[REMOVE SERVICEDESC] import otoroshi.utils.syntax.implicits.*
//[REMOVE SERVICEDESC] import play.api.Logger
//[REMOVE SERVICEDESC] import play.api.libs.json.*
//[REMOVE SERVICEDESC] import play.api.mvc.{AbstractController, BodyParser, ControllerComponents, RequestHeader}
//[REMOVE SERVICEDESC] import otoroshi.utils.json.JsonPatchHelpers.patchJson
//[REMOVE SERVICEDESC] import otoroshi.utils.syntax.implicits.*
//[REMOVE SERVICEDESC] import play.api.libs.streams.Accumulator
//[REMOVE SERVICEDESC] import play.api.mvc.Results.Status
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC] import scala.concurrent.{ExecutionContext, Future}
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC] class ServicesController(val ApiAction: ApiAction, val cc: ControllerComponents)(using val env: Env)
//[REMOVE SERVICEDESC]     extends AbstractController(cc)
//[REMOVE SERVICEDESC]     with BulkControllerHelper[ServiceDescriptor, JsValue]
//[REMOVE SERVICEDESC]     with CrudControllerHelper[ServiceDescriptor, JsValue]
//[REMOVE SERVICEDESC]     with AdminApiHelper {
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   implicit lazy val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext
//[REMOVE SERVICEDESC]   implicit lazy val mat: org.apache.pekko.stream.Materializer = env.otoroshiMaterializer
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   lazy val sourceBodyParser = BodyParser("ServicesController BodyParser") { _ =>
//[REMOVE SERVICEDESC]     Accumulator.source[ByteString].map(Right.apply)
//[REMOVE SERVICEDESC]   }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   lazy val logger = Logger("otoroshi-services-api")
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   override def singularName: String = "service-descriptor"
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   override def buildError(status: Int, message: String): ApiError[JsValue] =
//[REMOVE SERVICEDESC]     JsonApiError(status, play.api.libs.json.JsString(message))
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   override def extractId(entity: ServiceDescriptor): String = entity.id
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   override def readEntity(json: JsValue): Either[JsValue, ServiceDescriptor] =
//[REMOVE SERVICEDESC]     ServiceDescriptor._fmt.reads(json).asEither match {
//[REMOVE SERVICEDESC]       case Left(e)  => Left(JsError.toJson(e))
//[REMOVE SERVICEDESC]       case Right(r) => Right(r)
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   override def writeEntity(entity: ServiceDescriptor): JsValue = ServiceDescriptor._fmt.writes(entity)
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   override def findByIdOps(id: String, req: RequestHeader)(using
//[REMOVE SERVICEDESC]       env: Env,
//[REMOVE SERVICEDESC]       ec: ExecutionContext
//[REMOVE SERVICEDESC]   ): Future[Either[ApiError[JsValue], OptionalEntityAndContext[ServiceDescriptor]]] = {
//[REMOVE SERVICEDESC]     env.datastores.serviceDescriptorDataStore.findById(id).map { opt =>
//[REMOVE SERVICEDESC]       Right(
//[REMOVE SERVICEDESC]         OptionalEntityAndContext(
//[REMOVE SERVICEDESC]           entity = opt,
//[REMOVE SERVICEDESC]           action = "ACCESS_SERVICE_DESCRIPTOR",
//[REMOVE SERVICEDESC]           message = "User accessed a service descriptor",
//[REMOVE SERVICEDESC]           metadata = Json.obj("ServiceDescriptorId" -> id),
//[REMOVE SERVICEDESC]           alert = "ServiceDescriptorAccessed"
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]       )
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]   }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   override def findAllOps(req: RequestHeader)(using
//[REMOVE SERVICEDESC]       env: Env,
//[REMOVE SERVICEDESC]       ec: ExecutionContext
//[REMOVE SERVICEDESC]   ): Future[Either[ApiError[JsValue], SeqEntityAndContext[ServiceDescriptor]]] = {
//[REMOVE SERVICEDESC]     env.datastores.serviceDescriptorDataStore.findAll().map { seq =>
//[REMOVE SERVICEDESC]       Right(
//[REMOVE SERVICEDESC]         SeqEntityAndContext(
//[REMOVE SERVICEDESC]           entity = seq,
//[REMOVE SERVICEDESC]           action = "ACCESS_ALL_SERVICE_DESCRIPTORS",
//[REMOVE SERVICEDESC]           message = "User accessed all service descriptors",
//[REMOVE SERVICEDESC]           metadata = Json.obj(),
//[REMOVE SERVICEDESC]           alert = "ServiceDescriptorsAccessed"
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]       )
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]   }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   override def createEntityOps(
//[REMOVE SERVICEDESC]       entity: ServiceDescriptor,
//[REMOVE SERVICEDESC]       req: RequestHeader
//[REMOVE SERVICEDESC]   )(using env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ServiceDescriptor]]] = {
//[REMOVE SERVICEDESC]     env.datastores.serviceDescriptorDataStore.set(entity).map {
//[REMOVE SERVICEDESC]       case true  => {
//[REMOVE SERVICEDESC]         Right(
//[REMOVE SERVICEDESC]           EntityAndContext(
//[REMOVE SERVICEDESC]             entity = entity,
//[REMOVE SERVICEDESC]             action = "CREATE_SERVICE_DESCRIPTOR",
//[REMOVE SERVICEDESC]             message = "User created a service descriptor",
//[REMOVE SERVICEDESC]             metadata = entity.toJson.as[JsObject],
//[REMOVE SERVICEDESC]             alert = "ServiceDescriptorCreatedAlert"
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]       case false => {
//[REMOVE SERVICEDESC]         Left(
//[REMOVE SERVICEDESC]           JsonApiError(
//[REMOVE SERVICEDESC]             500,
//[REMOVE SERVICEDESC]             Json.obj("error" -> "service descriptor not stored ...")
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]   }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   override def updateEntityOps(
//[REMOVE SERVICEDESC]       entity: ServiceDescriptor,
//[REMOVE SERVICEDESC]       req: RequestHeader
//[REMOVE SERVICEDESC]   )(using env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[ServiceDescriptor]]] = {
//[REMOVE SERVICEDESC]     env.datastores.serviceDescriptorDataStore.set(entity).map {
//[REMOVE SERVICEDESC]       case true  => {
//[REMOVE SERVICEDESC]         Right(
//[REMOVE SERVICEDESC]           EntityAndContext(
//[REMOVE SERVICEDESC]             entity = entity,
//[REMOVE SERVICEDESC]             action = "UPDATE_SERVICE_DESCRIPTOR",
//[REMOVE SERVICEDESC]             message = "User updated a service descriptor",
//[REMOVE SERVICEDESC]             metadata = entity.toJson.as[JsObject],
//[REMOVE SERVICEDESC]             alert = "ServiceDescriptorUpdatedAlert"
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]       case false => {
//[REMOVE SERVICEDESC]         Left(
//[REMOVE SERVICEDESC]           JsonApiError(
//[REMOVE SERVICEDESC]             500,
//[REMOVE SERVICEDESC]             Json.obj("error" -> "service descriptor not stored ...")
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]   }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   override def deleteEntityOps(id: String, req: RequestHeader)(using
//[REMOVE SERVICEDESC]       env: Env,
//[REMOVE SERVICEDESC]       ec: ExecutionContext
//[REMOVE SERVICEDESC]   ): Future[Either[ApiError[JsValue], NoEntityAndContext[ServiceDescriptor]]] = {
//[REMOVE SERVICEDESC]     env.datastores.serviceDescriptorDataStore.delete(id).map {
//[REMOVE SERVICEDESC]       case true  => {
//[REMOVE SERVICEDESC]         Right(
//[REMOVE SERVICEDESC]           NoEntityAndContext(
//[REMOVE SERVICEDESC]             action = "DELETE_SERVICE_DESCRIPTOR",
//[REMOVE SERVICEDESC]             message = "User deleted a service descriptor",
//[REMOVE SERVICEDESC]             metadata = Json.obj("ServiceDescriptorId" -> id),
//[REMOVE SERVICEDESC]             alert = "ServiceDescriptorDeletedAlert"
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]       case false => {
//[REMOVE SERVICEDESC]         Left(
//[REMOVE SERVICEDESC]           JsonApiError(
//[REMOVE SERVICEDESC]             500,
//[REMOVE SERVICEDESC]             Json.obj("error" -> "service descriptor not deleted ...")
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]   }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def allLines() =
//[REMOVE SERVICEDESC]     ApiAction.async { ctx =>
//[REMOVE SERVICEDESC]       val options = SendAuditAndAlert("ACCESS_ALL_LINES", s"User accessed all lines", None, Json.obj(), ctx)
//[REMOVE SERVICEDESC]       fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: String) => JsString(e), options) {
//[REMOVE SERVICEDESC]         env.datastores.globalConfigDataStore.allEnv().map(_.toSeq).fright[JsonApiError]
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def servicesForALine(line: String) =
//[REMOVE SERVICEDESC]     ApiAction.async { ctx =>
//[REMOVE SERVICEDESC]       val options = SendAuditAndAlert(
//[REMOVE SERVICEDESC]         "ACCESS_SERVICES_FOR_LINES",
//[REMOVE SERVICEDESC]         s"User accessed service list for line $line",
//[REMOVE SERVICEDESC]         None,
//[REMOVE SERVICEDESC]         Json.obj("line" -> line),
//[REMOVE SERVICEDESC]         ctx
//[REMOVE SERVICEDESC]       )
//[REMOVE SERVICEDESC]       fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: ServiceDescriptor) => e.toJson, options) {
//[REMOVE SERVICEDESC]         env.datastores.serviceDescriptorDataStore.findByEnv(line).map(_.filter(ctx.canUserRead)).fright[JsonApiError]
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def serviceTargets(serviceId: String) =
//[REMOVE SERVICEDESC]     ApiAction.async { ctx =>
//[REMOVE SERVICEDESC]       ctx.canReadService(serviceId) {
//[REMOVE SERVICEDESC]         val options = SendAuditAndAlert(
//[REMOVE SERVICEDESC]           "ACCESS_SERVICE_TARGETS",
//[REMOVE SERVICEDESC]           "User accessed a service targets",
//[REMOVE SERVICEDESC]           None,
//[REMOVE SERVICEDESC]           Json.obj("serviceId" -> serviceId),
//[REMOVE SERVICEDESC]           ctx
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]         fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: String) => JsString(e), options) {
//[REMOVE SERVICEDESC]           env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
//[REMOVE SERVICEDESC]             case None       => JsonApiError(404, JsString(s"Service with id: '$serviceId' not found")).left[Seq[String]]
//[REMOVE SERVICEDESC]             case Some(desc) => desc.targets.map(t => s"${t.scheme}://${t.host}").right[JsonApiError]
//[REMOVE SERVICEDESC]           }
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def updateServiceTargets(serviceId: String) =
//[REMOVE SERVICEDESC]     ApiAction.async(parse.json) { ctx =>
//[REMOVE SERVICEDESC]       val body = ctx.request.body
//[REMOVE SERVICEDESC]       env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
//[REMOVE SERVICEDESC]         case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
//[REMOVE SERVICEDESC]         case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
//[REMOVE SERVICEDESC]         case Some(desc)                            => {
//[REMOVE SERVICEDESC]           val event         = AdminApiEvent(
//[REMOVE SERVICEDESC]             env.snowflakeGenerator.nextIdStr(),
//[REMOVE SERVICEDESC]             env.env,
//[REMOVE SERVICEDESC]             Some(ctx.apiKey),
//[REMOVE SERVICEDESC]             ctx.user,
//[REMOVE SERVICEDESC]             "UPDATE_SERVICE_TARGETS",
//[REMOVE SERVICEDESC]             s"User updated a service targets",
//[REMOVE SERVICEDESC]             ctx.from,
//[REMOVE SERVICEDESC]             ctx.ua,
//[REMOVE SERVICEDESC]             Json.obj("serviceId" -> serviceId, "patch" -> body)
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]           val actualTargets = JsArray(desc.targets.map(t => JsString(s"${t.scheme}://${t.host}")))
//[REMOVE SERVICEDESC]           val newTargets    = patchJson(body, actualTargets)
//[REMOVE SERVICEDESC]             .as[JsArray]
//[REMOVE SERVICEDESC]             .value.toSeq
//[REMOVE SERVICEDESC]             .map(_.as[String])
//[REMOVE SERVICEDESC]             .map(s => s.split("://"))
//[REMOVE SERVICEDESC]             .map(arr => Target(scheme = arr(0), host = arr(1)))
//[REMOVE SERVICEDESC]           val newDesc       = desc.copy(targets = newTargets.toSeq)
//[REMOVE SERVICEDESC]           Audit.send(event)
//[REMOVE SERVICEDESC]           Alerts.send(
//[REMOVE SERVICEDESC]             ServiceUpdatedAlert(
//[REMOVE SERVICEDESC]               env.snowflakeGenerator.nextIdStr(),
//[REMOVE SERVICEDESC]               env.env,
//[REMOVE SERVICEDESC]               ctx.user.getOrElse(ctx.apiKey.toJson),
//[REMOVE SERVICEDESC]               event,
//[REMOVE SERVICEDESC]               ctx.from,
//[REMOVE SERVICEDESC]               ctx.ua
//[REMOVE SERVICEDESC]             )
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]           ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
//[REMOVE SERVICEDESC]           newDesc.save().map { _ =>
//[REMOVE SERVICEDESC]             ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
//[REMOVE SERVICEDESC]               .addServices(Seq(newDesc))
//[REMOVE SERVICEDESC]             Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
//[REMOVE SERVICEDESC]           }
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def serviceAddTarget(serviceId: String) =
//[REMOVE SERVICEDESC]     ApiAction.async(parse.json) { ctx =>
//[REMOVE SERVICEDESC]       val body = ctx.request.body
//[REMOVE SERVICEDESC]       env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
//[REMOVE SERVICEDESC]         case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
//[REMOVE SERVICEDESC]         case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
//[REMOVE SERVICEDESC]         case Some(desc)                            => {
//[REMOVE SERVICEDESC]           val event      = AdminApiEvent(
//[REMOVE SERVICEDESC]             env.snowflakeGenerator.nextIdStr(),
//[REMOVE SERVICEDESC]             env.env,
//[REMOVE SERVICEDESC]             Some(ctx.apiKey),
//[REMOVE SERVICEDESC]             ctx.user,
//[REMOVE SERVICEDESC]             "UPDATE_SERVICE_TARGETS",
//[REMOVE SERVICEDESC]             s"User updated a service targets",
//[REMOVE SERVICEDESC]             ctx.from,
//[REMOVE SERVICEDESC]             ctx.ua,
//[REMOVE SERVICEDESC]             Json.obj("serviceId" -> serviceId, "patch" -> body)
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]           val newTargets = (body \ "target").asOpt[String] match {
//[REMOVE SERVICEDESC]             case Some(target) =>
//[REMOVE SERVICEDESC]               val parts = target.split("://")
//[REMOVE SERVICEDESC]               val tgt   = Target(scheme = parts(0), host = parts(1))
//[REMOVE SERVICEDESC]               if (desc.targets.contains(tgt))
//[REMOVE SERVICEDESC]                 desc.targets
//[REMOVE SERVICEDESC]               else
//[REMOVE SERVICEDESC]                 desc.targets :+ tgt
//[REMOVE SERVICEDESC]             case None         => desc.targets
//[REMOVE SERVICEDESC]           }
//[REMOVE SERVICEDESC]           val newDesc    = desc.copy(targets = newTargets.toSeq)
//[REMOVE SERVICEDESC]           Audit.send(event)
//[REMOVE SERVICEDESC]           Alerts.send(
//[REMOVE SERVICEDESC]             ServiceUpdatedAlert(
//[REMOVE SERVICEDESC]               env.snowflakeGenerator.nextIdStr(),
//[REMOVE SERVICEDESC]               env.env,
//[REMOVE SERVICEDESC]               ctx.user.getOrElse(ctx.apiKey.toJson),
//[REMOVE SERVICEDESC]               event,
//[REMOVE SERVICEDESC]               ctx.from,
//[REMOVE SERVICEDESC]               ctx.ua
//[REMOVE SERVICEDESC]             )
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]           ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
//[REMOVE SERVICEDESC]           newDesc.save().map { _ =>
//[REMOVE SERVICEDESC]             ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
//[REMOVE SERVICEDESC]               .addServices(Seq(newDesc))
//[REMOVE SERVICEDESC]             Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
//[REMOVE SERVICEDESC]           }
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def serviceDeleteTarget(serviceId: String) =
//[REMOVE SERVICEDESC]     ApiAction.async(parse.json) { ctx =>
//[REMOVE SERVICEDESC]       val body = ctx.request.body
//[REMOVE SERVICEDESC]       env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
//[REMOVE SERVICEDESC]         case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
//[REMOVE SERVICEDESC]         case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
//[REMOVE SERVICEDESC]         case Some(desc)                            => {
//[REMOVE SERVICEDESC]           val event      = AdminApiEvent(
//[REMOVE SERVICEDESC]             env.snowflakeGenerator.nextIdStr(),
//[REMOVE SERVICEDESC]             env.env,
//[REMOVE SERVICEDESC]             Some(ctx.apiKey),
//[REMOVE SERVICEDESC]             ctx.user,
//[REMOVE SERVICEDESC]             "DELETE_SERVICE_TARGET",
//[REMOVE SERVICEDESC]             s"User deleted a service target",
//[REMOVE SERVICEDESC]             ctx.from,
//[REMOVE SERVICEDESC]             ctx.ua,
//[REMOVE SERVICEDESC]             Json.obj("serviceId" -> serviceId, "patch" -> body)
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]           val newTargets = (body \ "target").asOpt[String] match {
//[REMOVE SERVICEDESC]             case Some(target) =>
//[REMOVE SERVICEDESC]               val parts = target.split("://")
//[REMOVE SERVICEDESC]               val tgt   = Target(scheme = parts(0), host = parts(1))
//[REMOVE SERVICEDESC]               if (desc.targets.contains(tgt))
//[REMOVE SERVICEDESC]                 desc.targets.filterNot(_ == tgt)
//[REMOVE SERVICEDESC]               else
//[REMOVE SERVICEDESC]                 desc.targets
//[REMOVE SERVICEDESC]             case None         => desc.targets
//[REMOVE SERVICEDESC]           }
//[REMOVE SERVICEDESC]           val newDesc    = desc.copy(targets = newTargets.toSeq)
//[REMOVE SERVICEDESC]           Audit.send(event)
//[REMOVE SERVICEDESC]           Alerts.send(
//[REMOVE SERVICEDESC]             ServiceUpdatedAlert(
//[REMOVE SERVICEDESC]               env.snowflakeGenerator.nextIdStr(),
//[REMOVE SERVICEDESC]               env.env,
//[REMOVE SERVICEDESC]               ctx.user.getOrElse(ctx.apiKey.toJson),
//[REMOVE SERVICEDESC]               event,
//[REMOVE SERVICEDESC]               ctx.from,
//[REMOVE SERVICEDESC]               ctx.ua
//[REMOVE SERVICEDESC]             )
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]           ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
//[REMOVE SERVICEDESC]           newDesc.save().map { _ =>
//[REMOVE SERVICEDESC]             ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
//[REMOVE SERVICEDESC]               .addServices(Seq(newDesc))
//[REMOVE SERVICEDESC]             Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
//[REMOVE SERVICEDESC]           }
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def serviceLiveStats(serviceId: String) =
//[REMOVE SERVICEDESC]     ApiAction.async { ctx =>
//[REMOVE SERVICEDESC]       ctx.canReadService(serviceId) {
//[REMOVE SERVICEDESC]         Audit.send(
//[REMOVE SERVICEDESC]           AdminApiEvent(
//[REMOVE SERVICEDESC]             env.snowflakeGenerator.nextIdStr(),
//[REMOVE SERVICEDESC]             env.env,
//[REMOVE SERVICEDESC]             Some(ctx.apiKey),
//[REMOVE SERVICEDESC]             ctx.user,
//[REMOVE SERVICEDESC]             "ACCESS_SERVICE_LIVESTATS",
//[REMOVE SERVICEDESC]             s"User accessed a service descriptor livestats",
//[REMOVE SERVICEDESC]             ctx.from,
//[REMOVE SERVICEDESC]             ctx.ua,
//[REMOVE SERVICEDESC]             Json.obj("serviceId" -> serviceId)
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]         for {
//[REMOVE SERVICEDESC]           calls       <- env.datastores.serviceDescriptorDataStore.calls(serviceId)
//[REMOVE SERVICEDESC]           dataIn      <- env.datastores.serviceDescriptorDataStore.dataInFor(serviceId)
//[REMOVE SERVICEDESC]           dataOut     <- env.datastores.serviceDescriptorDataStore.dataOutFor(serviceId)
//[REMOVE SERVICEDESC]           rate        <- env.datastores.serviceDescriptorDataStore.callsPerSec(serviceId)
//[REMOVE SERVICEDESC]           duration    <- env.datastores.serviceDescriptorDataStore.callsDuration(serviceId)
//[REMOVE SERVICEDESC]           overhead    <- env.datastores.serviceDescriptorDataStore.callsOverhead(serviceId)
//[REMOVE SERVICEDESC]           dataInRate  <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor(serviceId)
//[REMOVE SERVICEDESC]           dataOutRate <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor(serviceId)
//[REMOVE SERVICEDESC]         } yield Ok(
//[REMOVE SERVICEDESC]           Json.obj(
//[REMOVE SERVICEDESC]             "calls"       -> calls,
//[REMOVE SERVICEDESC]             "dataIn"      -> dataIn,
//[REMOVE SERVICEDESC]             "dataOut"     -> dataOut,
//[REMOVE SERVICEDESC]             "rate"        -> rate,
//[REMOVE SERVICEDESC]             "duration"    -> duration,
//[REMOVE SERVICEDESC]             "overhead"    -> overhead,
//[REMOVE SERVICEDESC]             "dataInRate"  -> dataInRate,
//[REMOVE SERVICEDESC]             "dataOutRate" -> dataOutRate
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def serviceHealth(serviceId: String) =
//[REMOVE SERVICEDESC]     ApiAction.async { ctx =>
//[REMOVE SERVICEDESC]       ctx.canReadService(serviceId) {
//[REMOVE SERVICEDESC]         val options = SendAuditAndAlert(
//[REMOVE SERVICEDESC]           "ACCESS_SERVICE_HEALTH",
//[REMOVE SERVICEDESC]           "User accessed a service descriptor health",
//[REMOVE SERVICEDESC]           None,
//[REMOVE SERVICEDESC]           Json.obj("serviceId" -> serviceId),
//[REMOVE SERVICEDESC]           ctx
//[REMOVE SERVICEDESC]         )
//[REMOVE SERVICEDESC]         fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: HealthCheckEvent) => e.toJson, options) {
//[REMOVE SERVICEDESC]           env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
//[REMOVE SERVICEDESC]             case None       =>
//[REMOVE SERVICEDESC]               env.datastores.routeDataStore.findById(serviceId).flatMap {
//[REMOVE SERVICEDESC]                 case None        =>
//[REMOVE SERVICEDESC]                   JsonApiError(404, JsString(s"Service with id: '$serviceId' not found")).leftf[Seq[HealthCheckEvent]]
//[REMOVE SERVICEDESC]                 case Some(route) =>
//[REMOVE SERVICEDESC]                   env.datastores.healthCheckDataStore.findAll(route.legacy).fright[JsonApiError]
//[REMOVE SERVICEDESC]               }
//[REMOVE SERVICEDESC]             case Some(desc) => env.datastores.healthCheckDataStore.findAll(desc).fright[JsonApiError]
//[REMOVE SERVICEDESC]           }
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def serviceTemplate(serviceId: String) =
//[REMOVE SERVICEDESC]     ApiAction.async { ctx =>
//[REMOVE SERVICEDESC]       env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
//[REMOVE SERVICEDESC]         case None                                 => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
//[REMOVE SERVICEDESC]         case Some(desc) if !ctx.canUserRead(desc) => ctx.fforbidden
//[REMOVE SERVICEDESC]         case Some(desc)                           => {
//[REMOVE SERVICEDESC]           env.datastores.errorTemplateDataStore.findById(desc.id).map {
//[REMOVE SERVICEDESC]             case Some(template) => Ok(template.toJson)
//[REMOVE SERVICEDESC]             case None           => NotFound(Json.obj("error" -> "template not found"))
//[REMOVE SERVICEDESC]           }
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def updateServiceTemplate(serviceId: String) =
//[REMOVE SERVICEDESC]     ApiAction.async(sourceBodyParser) { ctx =>
//[REMOVE SERVICEDESC]       ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
//[REMOVE SERVICEDESC]         val requestBody    = Json.parse(bodyRaw.utf8String)
//[REMOVE SERVICEDESC]         val body: JsObject = (requestBody \ "serviceId").asOpt[String] match {
//[REMOVE SERVICEDESC]           case None    => requestBody.as[JsObject] ++ Json.obj("serviceId" -> serviceId)
//[REMOVE SERVICEDESC]           case Some(_) => requestBody.as[JsObject]
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]         env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
//[REMOVE SERVICEDESC]           case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
//[REMOVE SERVICEDESC]           case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
//[REMOVE SERVICEDESC]           case Some(_)                               => {
//[REMOVE SERVICEDESC]             ErrorTemplate.fromJsonSafe(body) match {
//[REMOVE SERVICEDESC]               case JsError(e)                  => BadRequest(Json.obj("error" -> "Bad ErrorTemplate format")).asFuture
//[REMOVE SERVICEDESC]               case JsSuccess(errorTemplate, _) =>
//[REMOVE SERVICEDESC]                 env.datastores.errorTemplateDataStore.findById(errorTemplate.serviceId).flatMap {
//[REMOVE SERVICEDESC]                   case None                                            => NotFound(Json.obj("error" -> "ErrorTemplate does not exists")).asFuture
//[REMOVE SERVICEDESC]                   case Some(oldEntity) if !ctx.canUserWrite(oldEntity) =>
//[REMOVE SERVICEDESC]                     BadRequest(Json.obj("error" -> "You cant access this ErrorTemplate")).asFuture
//[REMOVE SERVICEDESC]                   case Some(_)                                         => {
//[REMOVE SERVICEDESC]                     env.datastores.errorTemplateDataStore.set(errorTemplate.copy(serviceId = serviceId)).map {
//[REMOVE SERVICEDESC]                       case false => InternalServerError(Json.obj("error" -> "ErrorTemplate not stored ..."))
//[REMOVE SERVICEDESC]                       case true  => {
//[REMOVE SERVICEDESC]                         val event: AdminApiEvent = AdminApiEvent(
//[REMOVE SERVICEDESC]                           env.snowflakeGenerator.nextIdStr(),
//[REMOVE SERVICEDESC]                           env.env,
//[REMOVE SERVICEDESC]                           Some(ctx.apiKey),
//[REMOVE SERVICEDESC]                           ctx.user,
//[REMOVE SERVICEDESC]                           "UPDATE_ERROR_TEMPLATE",
//[REMOVE SERVICEDESC]                           s"User updated an error template",
//[REMOVE SERVICEDESC]                           ctx.from,
//[REMOVE SERVICEDESC]                           ctx.ua,
//[REMOVE SERVICEDESC]                           errorTemplate.toJson
//[REMOVE SERVICEDESC]                         )
//[REMOVE SERVICEDESC]                         Audit.send(event)
//[REMOVE SERVICEDESC]                         Ok(errorTemplate.toJson)
//[REMOVE SERVICEDESC]                       }
//[REMOVE SERVICEDESC]                     }
//[REMOVE SERVICEDESC]                   }
//[REMOVE SERVICEDESC]                 }
//[REMOVE SERVICEDESC]             }
//[REMOVE SERVICEDESC]           }
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def createServiceTemplate(serviceId: String) =
//[REMOVE SERVICEDESC]     ApiAction.async(sourceBodyParser) { ctx =>
//[REMOVE SERVICEDESC]       ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
//[REMOVE SERVICEDESC]         val requestBody    = Json.parse(bodyRaw.utf8String)
//[REMOVE SERVICEDESC]         val body: JsObject = (requestBody \ "serviceId").asOpt[String] match {
//[REMOVE SERVICEDESC]           case None    => requestBody.as[JsObject] ++ Json.obj("serviceId" -> serviceId)
//[REMOVE SERVICEDESC]           case Some(_) => requestBody.as[JsObject]
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]         env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
//[REMOVE SERVICEDESC]           case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
//[REMOVE SERVICEDESC]           case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
//[REMOVE SERVICEDESC]           case Some(_)                               => {
//[REMOVE SERVICEDESC]             ErrorTemplate.fromJsonSafe(body) match {
//[REMOVE SERVICEDESC]               case JsError(e)                  => BadRequest(Json.obj("error" -> s"Bad ErrorTemplate format $e")).asFuture
//[REMOVE SERVICEDESC]               case JsSuccess(errorTemplate, _) =>
//[REMOVE SERVICEDESC]                 env.datastores.errorTemplateDataStore.findById(errorTemplate.serviceId).flatMap {
//[REMOVE SERVICEDESC]                   case Some(_) => BadRequest(Json.obj("error" -> "ErrorTemplate already exists")).asFuture
//[REMOVE SERVICEDESC]                   case None    => {
//[REMOVE SERVICEDESC]                     env.datastores.errorTemplateDataStore.set(errorTemplate).map {
//[REMOVE SERVICEDESC]                       case false => InternalServerError(Json.obj("error" -> "ErrorTemplate not stored ..."))
//[REMOVE SERVICEDESC]                       case true  => {
//[REMOVE SERVICEDESC]                         val event: AdminApiEvent = AdminApiEvent(
//[REMOVE SERVICEDESC]                           env.snowflakeGenerator.nextIdStr(),
//[REMOVE SERVICEDESC]                           env.env,
//[REMOVE SERVICEDESC]                           Some(ctx.apiKey),
//[REMOVE SERVICEDESC]                           ctx.user,
//[REMOVE SERVICEDESC]                           "CREATE_ERROR_TEMPLATE",
//[REMOVE SERVICEDESC]                           s"User created an error template",
//[REMOVE SERVICEDESC]                           ctx.from,
//[REMOVE SERVICEDESC]                           ctx.ua,
//[REMOVE SERVICEDESC]                           errorTemplate.toJson
//[REMOVE SERVICEDESC]                         )
//[REMOVE SERVICEDESC]                         Audit.send(event)
//[REMOVE SERVICEDESC]                         Ok(errorTemplate.toJson)
//[REMOVE SERVICEDESC]                       }
//[REMOVE SERVICEDESC]                     }
//[REMOVE SERVICEDESC]                   }
//[REMOVE SERVICEDESC]                 }
//[REMOVE SERVICEDESC]             }
//[REMOVE SERVICEDESC]           }
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def deleteServiceTemplate(serviceId: String) =
//[REMOVE SERVICEDESC]     ApiAction.async { ctx =>
//[REMOVE SERVICEDESC]       env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
//[REMOVE SERVICEDESC]         case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
//[REMOVE SERVICEDESC]         case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
//[REMOVE SERVICEDESC]         case Some(desc)                            => {
//[REMOVE SERVICEDESC]           env.datastores.errorTemplateDataStore.findById(desc.id).flatMap {
//[REMOVE SERVICEDESC]             case None                => NotFound(Json.obj("error" -> "template not found")).asFuture
//[REMOVE SERVICEDESC]             case Some(errorTemplate) =>
//[REMOVE SERVICEDESC]               env.datastores.errorTemplateDataStore.delete(desc.id).map { _ =>
//[REMOVE SERVICEDESC]                 val event: AdminApiEvent = AdminApiEvent(
//[REMOVE SERVICEDESC]                   env.snowflakeGenerator.nextIdStr(),
//[REMOVE SERVICEDESC]                   env.env,
//[REMOVE SERVICEDESC]                   Some(ctx.apiKey),
//[REMOVE SERVICEDESC]                   ctx.user,
//[REMOVE SERVICEDESC]                   "DELETE_ERROR_TEMPLATE",
//[REMOVE SERVICEDESC]                   s"User deleted an error template",
//[REMOVE SERVICEDESC]                   ctx.from,
//[REMOVE SERVICEDESC]                   ctx.ua,
//[REMOVE SERVICEDESC]                   errorTemplate.toJson
//[REMOVE SERVICEDESC]                 )
//[REMOVE SERVICEDESC]                 Audit.send(event)
//[REMOVE SERVICEDESC]                 Ok(Json.obj("done" -> true))
//[REMOVE SERVICEDESC]               }
//[REMOVE SERVICEDESC]           }
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def convertAsRoute(serviceId: String) = ApiAction.async { ctx =>
//[REMOVE SERVICEDESC]     env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
//[REMOVE SERVICEDESC]       case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).vfuture
//[REMOVE SERVICEDESC]       case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
//[REMOVE SERVICEDESC]       case Some(desc)                            => {
//[REMOVE SERVICEDESC]         Ok(NgRoute.fromServiceDescriptor(desc, debug = false).json).vfuture
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]   }
//[REMOVE SERVICEDESC]
//[REMOVE SERVICEDESC]   def importAsRoute(serviceId: String) = ApiAction.async { ctx =>
//[REMOVE SERVICEDESC]     env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
//[REMOVE SERVICEDESC]       case None                                  => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).vfuture
//[REMOVE SERVICEDESC]       case Some(desc) if !ctx.canUserWrite(desc) => ctx.fforbidden
//[REMOVE SERVICEDESC]       case Some(desc)                            => {
//[REMOVE SERVICEDESC]         val route = NgRoute.fromServiceDescriptor(desc, debug = false)
//[REMOVE SERVICEDESC]         route.save().map { _ =>
//[REMOVE SERVICEDESC]           val port = if (ctx.request.theSecured) env.exposedHttpsPortInt else env.exposedHttpPortInt
//[REMOVE SERVICEDESC]           desc.copy(enabled = false).save()
//[REMOVE SERVICEDESC]           Ok(
//[REMOVE SERVICEDESC]             route.json.asObject ++ Json.obj(
//[REMOVE SERVICEDESC]               "resource_url"    -> s"${ctx.request.theProtocol}://${env.adminApiExposedHost}:${port}/api/routes/${route.id}",
//[REMOVE SERVICEDESC]               "resource_ui_url" -> s"${ctx.request.theProtocol}://${env.backOfficeHost}:${port}${env.backOfficePath}/routes/${route.id}"
//[REMOVE SERVICEDESC]             )
//[REMOVE SERVICEDESC]           )
//[REMOVE SERVICEDESC]         }
//[REMOVE SERVICEDESC]       }
//[REMOVE SERVICEDESC]     }
//[REMOVE SERVICEDESC]   }
//[REMOVE SERVICEDESC] }
