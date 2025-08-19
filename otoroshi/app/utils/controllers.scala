package otoroshi.utils.controllers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.actions.{ApiAction, ApiActionContext}
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.models.{BackOfficeUser, EntityLocationSupport}
import otoroshi.security.IdGenerator
import otoroshi.utils.JsonValidator
import otoroshi.utils.json.JsonOperationsHelper
import otoroshi.utils.json.JsonPatchHelpers.patchJson
import otoroshi.utils.syntax.implicits._
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc.Results.Ok
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

trait ApiError[Error] {
  def status: Int
  def body: JsValue
  def bodyAsJson: JsValue
}

case class JsonApiError(status: Int, body: JsValue) extends ApiError[JsValue] {
  def bodyAsJson: JsValue = body
}

case class EntityAndContext[Entity](entity: Entity, action: String, message: String, metadata: JsObject, alert: String)
case class NoEntityAndContext[Entity](action: String, message: String, metadata: JsObject, alert: String)
case class OptionalEntityAndContext[Entity](
    entity: Option[Entity],
    action: String,
    message: String,
    metadata: JsObject,
    alert: String
)
case class SeqEntityAndContext[Entity](
    entity: Seq[Entity],
    action: String,
    message: String,
    metadata: JsObject,
    alert: String
)

case class GenericAlert(
    `@id`: String,
    `@env`: String,
    user: JsValue,
    alertName: String,
    audit: AuditEvent,
    from: String,
    ua: String,
    `@timestamp`: DateTime = DateTime.now()
) extends AlertEvent {
  override def `@service`: String                  = "Otoroshi"
  override def `@serviceId`: String                = "--"
  override def fromOrigin: Option[String]          = Some(from)
  override def fromUserAgent: Option[String]       = Some(ua)
  override def toJson(implicit _env: Env): JsValue =
    Json.obj(
      "@id"           -> `@id`,
      "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"         -> `@type`,
      "@product"      -> _env.eventsName,
      "@serviceId"    -> `@serviceId`,
      "@service"      -> `@service`,
      "@env"          -> `@env`,
      "alert"         -> alertName,
      "adminApiAlert" -> true,
      "user"          -> user,
      "audit_payload" -> audit.toJson
    )
}

case class SendAuditAndAlert(
    action: String,
    message: String,
    alert: Option[String],
    meta: JsObject,
    ctx: ApiActionContext[_]
)

trait AdminApiHelper {
  def sendAudit(action: String, message: String, meta: JsObject, ctx: ApiActionContext[_])(implicit env: Env): Unit = {
    sendAuditAndAlert(SendAuditAndAlert(action, message, None, meta, ctx))(env)
  }
  def sendAuditAndAlert(action: String, message: String, alert: String, meta: JsObject, ctx: ApiActionContext[_])(
      implicit env: Env
  ): Unit = {
    sendAuditAndAlert(SendAuditAndAlert(action, message, alert.some, meta, ctx))(env)
  }
  def sendAuditAndAlert(options: SendAuditAndAlert)(implicit env: Env): Unit = {
    val SendAuditAndAlert(action, message, alertOpt, meta, ctx) = options
    val event: AdminApiEvent                                    = AdminApiEvent(
      env.snowflakeGenerator.nextIdStr(),
      env.env,
      Some(ctx.apiKey),
      ctx.user,
      action,
      message,
      ctx.from,
      ctx.ua,
      meta
    )
    Audit.send(event)
    alertOpt.foreach { alert =>
      Alerts.send(
        GenericAlert(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          ctx.user.getOrElse(ctx.apiKey.toJson),
          alert,
          event,
          ctx.from,
          ctx.ua
        )
      )
    }
  }
  def fetchWithPaginationAndFiltering[Entity, Error](
      ctx: ApiActionContext[_],
      filterPrefix: Option[String],
      writeEntity: Entity => JsValue,
      audit: SendAuditAndAlert
  )(
      findAllOps: => Future[Either[ApiError[Error], Seq[Entity]]]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[Error], Seq[Entity]]] = {

    val paginationPage: Int     = ctx.request.queryString
      .get("page")
      .flatMap(_.headOption)
      .map(_.toInt)
      .getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString
        .get("pageSize")
        .flatMap(_.headOption)
        .map(_.toInt)
        .getOrElse(Int.MaxValue)

    val paginationPosition = (paginationPage - 1) * paginationPageSize
    val prefix             = filterPrefix
    val filters            = ctx.request.queryString
      .mapValues(_.last)
      .collect {
        case v if prefix.isEmpty                                  => v
        case v if prefix.isDefined && v._1.startsWith(prefix.get) => (v._1.replace(prefix.get, ""), v._2)
      }
      .filterNot(a => a._1 == "page" || a._1 == "pageSize")
    val hasFilters         = filters.nonEmpty

    findAllOps.map {
      case Left(error)     => error.left
      case Right(entities) => {
        sendAuditAndAlert(audit)
        val jsonElements =
          entities.slice(paginationPosition, paginationPosition + paginationPageSize).map(e => (e, writeEntity(e)))
        if (hasFilters) {
          jsonElements
            .filter { case (_, elem) =>
              filters.forall { case (key, value) =>
                (elem \ key).as[JsValue] match {
                  case JsString(v)     => v == value
                  case JsBoolean(v)    => v == value.toBoolean
                  case JsNumber(v)     => v.toDouble == value.toDouble
                  case JsArray(values) => values.exists {
                    case JsString(v) => v.contains(value)
                    case JsBoolean(v) => v == value.toBoolean
                    case JsNumber(v) => v.toDouble == value.toDouble
                    case JsArray(values) => values.contains(JsString(value))
                    case _ => false
                  }
                  case _               => false
                }
              }
            }
            .map(_._1)
            .right
        } else {
          entities.right
        }
      }
    }
  }
  def fetchWithPaginationAndFilteringAsResult[Entity, Error](
      ctx: ApiActionContext[_],
      filterPrefix: Option[String],
      writeEntity: Entity => JsValue,
      audit: SendAuditAndAlert
  )(
      findAllOps: => Future[Either[ApiError[Error], Seq[Entity]]]
  )(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    fetchWithPaginationAndFiltering[Entity, Error](ctx, filterPrefix, writeEntity, audit)(findAllOps).map {
      case Left(error)       =>
        Results.Status(error.status)(Json.obj("error" -> "fetch_error", "error_description" -> error.bodyAsJson))
      case Right(finalItems) => {
        if (!ctx.request.accepts("application/json") && ctx.request.accepts("application/x-ndjson")) {
          Ok.sendEntity(
            HttpEntity.Streamed(
              data = Source(finalItems.map(writeEntity).map(e => e.stringify.byteString).toList),
              contentLength = None,
              contentType = "application/x-ndjson".some
            )
          )
        } else {
          Ok(JsArray(finalItems.map(writeEntity)))
        }
      }
    }
  }
}

trait EntityHelper[Entity <: EntityLocationSupport, Error] {
  def singularName: String
  def readId(json: JsValue): Either[String, String] = {
    (json \ "id").asOpt[String] match {
      case Some(id) => Right(id)
      case None     => Left("Id not found !")
    }
  }
  def extractId(entity: Entity): String
  def readEntity(json: JsValue): Either[JsValue, Entity]
  def readAndValidateEntity(json: JsValue, f: => Either[String, Option[BackOfficeUser]])(implicit
      env: Env
  ): Either[JsValue, Entity] = {
    f match {
      case Left(err)         => Left(err.json)
      case Right(None)       => readEntity(json)
      case Right(Some(user)) => {
        val envValidators: Seq[JsonValidator]  =
          env.adminEntityValidators.getOrElse("all", Seq.empty[JsonValidator]) ++ env.adminEntityValidators
            .getOrElse(singularName.toLowerCase, Seq.empty[JsonValidator])
        val userValidators: Seq[JsonValidator] =
          user.adminEntityValidators.getOrElse("all", Seq.empty[JsonValidator]) ++ user.adminEntityValidators
            .getOrElse(singularName.toLowerCase, Seq.empty[JsonValidator])
        val validators                         = envValidators ++ userValidators
        val failedValidators                   = validators.filterNot(_.validate(json))
        if (failedValidators.isEmpty) {
          readEntity(json)
        } else {
          val errors = failedValidators.flatMap(_.error).map(_.json)
          if (errors.isEmpty) {
            Left("entity validation failed".json)
          } else {
            Left(JsArray(errors))
          }
        }
      }
    }
  }
  def writeEntity(entity: Entity): JsValue
  def findByIdOps(
      id: String,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[Error], OptionalEntityAndContext[Entity]]]
  def findAllOps(
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[Error], SeqEntityAndContext[Entity]]]
  def createEntityOps(
      entity: Entity,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[Error], EntityAndContext[Entity]]]
  def updateEntityOps(
      entity: Entity,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[Error], EntityAndContext[Entity]]]
  def deleteEntityOps(
      id: String,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[Error], NoEntityAndContext[Entity]]]
  // def canRead[A](ctx: ApiActionContext[A])(entity: Entity)(implicit env: Env): Boolean = ctx.canUserRead(entity)
  // def canWrite[A](ctx: ApiActionContext[A])(entity: Entity)(implicit env: Env): Boolean = ctx.canUserWrite(entity)
  // def canReadWrite[A](ctx: ApiActionContext[A])(entity: Entity)(implicit env: Env): Boolean = ctx.canUserRead(entity) && ctx.canUserWrite(entity)
  def buildError(status: Int, message: String): ApiError[Error]

  def processId(rawId: String, ctx: ApiActionContext[_]): String = rawId
}

trait BulkHelper[Entity <: EntityLocationSupport, Error] extends EntityHelper[Entity, Error] {

  import Results._

  def env: Env

  def bulkCreate(ctx: ApiActionContext[Source[ByteString, _]]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc  = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") => {
        val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
        val src      = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
          .map(bs => Try(Json.parse(bs.utf8String)))
          .collect { case Success(e) => e }
          .map(e =>
            readAndValidateEntity(e, ctx.backOfficeUser) match {
              case Left(err)     => Left((err, e))
              case Right(entity) => Right(("--", entity))
            }
          )
          .filter {
            case Left(_)            => true
            case Right((_, entity)) => ctx.canUserWrite(entity)
          }
          .mapAsync(grouping) {
            case Left((error, json)) =>
              Json
                .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
                .stringify
                .byteString
                .future
            case Right((_, entity))  => {
              createEntityOps(entity, ctx.request).map {
                case Left(error)                                              =>
                  Json
                    .obj(
                      "status"            -> error.status,
                      "error"             -> "creation_error",
                      "error_description" -> error.bodyAsJson,
                      "id"                -> extractId(entity)
                    )
                    .stringify
                    .byteString
                case Right(EntityAndContext(_, action, message, meta, alert)) =>
                  val event: AdminApiEvent = AdminApiEvent(
                    env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    Some(ctx.apiKey),
                    ctx.user,
                    action,
                    message,
                    ctx.from,
                    ctx.ua,
                    meta
                  )
                  Audit.send(event)
                  Alerts.send(
                    GenericAlert(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      ctx.user.getOrElse(ctx.apiKey.toJson),
                      alert,
                      event,
                      ctx.from,
                      ctx.ua
                    )
                  )
                  Json.obj("status" -> 201, "created" -> true, "id" -> extractId(entity)).stringify.byteString
              }
            }
          }
        Ok.sendEntity(
          HttpEntity.Streamed.apply(
            data = src.filterNot(_.isEmpty).intersperse(ByteString.empty, ByteString("\n"), ByteString.empty),
            contentLength = None,
            contentType = Some("application/x-ndjson")
          )
        ).future
      }
      case _                            =>
        BadRequest(Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type")).future
    }
  }

  def bulkUpdate(ctx: ApiActionContext[Source[ByteString, _]]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc  = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") => {
        val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
        val src      = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
          .map(bs => Try(Json.parse(bs.utf8String)))
          .collect { case Success(e) => e }
          .map(e =>
            readAndValidateEntity(e, ctx.backOfficeUser) match {
              case Left(err)     => Left((err, e))
              case Right(entity) => Right(("--", entity))
            }
          )
          .filter {
            case Left(_)            => true
            case Right((_, entity)) => ctx.canUserWrite(entity)
          }
          .mapAsync(grouping) {
            case Left((error, json)) =>
              Json
                .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
                .stringify
                .byteString
                .future
            case Right((_, entity))  => {
              updateEntityOps(entity, ctx.request).map {
                case Left(error)                                              =>
                  Json
                    .obj(
                      "status"            -> error.status,
                      "error"             -> "update_error",
                      "error_description" -> error.bodyAsJson,
                      "id"                -> extractId(entity)
                    )
                    .stringify
                    .byteString
                case Right(EntityAndContext(_, action, message, meta, alert)) =>
                  val event: AdminApiEvent = AdminApiEvent(
                    env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    Some(ctx.apiKey),
                    ctx.user,
                    action,
                    message,
                    ctx.from,
                    ctx.ua,
                    meta
                  )
                  Audit.send(event)
                  Alerts.send(
                    GenericAlert(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      ctx.user.getOrElse(ctx.apiKey.toJson),
                      alert,
                      event,
                      ctx.from,
                      ctx.ua
                    )
                  )
                  Json.obj("status" -> 200, "updated" -> true, "id" -> extractId(entity)).stringify.byteString
              }
            }
          }
        Ok.sendEntity(
          HttpEntity.Streamed.apply(
            data = src.filterNot(_.isEmpty).intersperse(ByteString.empty, ByteString("\n"), ByteString.empty),
            contentLength = None,
            contentType = Some("application/x-ndjson")
          )
        ).future
      }
      case _                            =>
        BadRequest(Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type")).future
    }
  }

  def bulkPatch(ctx: ApiActionContext[Source[ByteString, _]]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc  = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") => {
        val grouping                   = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
        val src: Source[ByteString, _] = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
          .map(bs => Try(Json.parse(bs.utf8String)))
          .collect { case Success(e) => e }
          .map(e =>
            readId(e) match {
              case Left(err) => Left((err, e))
              case Right(id) => Right((id, (e \ "patch").as[JsValue]))
            }
          )
          .mapAsync(grouping) {
            case Left((error, json)) =>
              Json
                .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
                .stringify
                .byteString
                .future
            case Right((_id, patch)) => {
              val id = processId(_id, ctx)
              findByIdOps(id, ctx.request).flatMap {
                case Left(error)                                         =>
                  Json
                    .obj("status" -> 404, "error" -> "not_found", "error_description" -> "Entity not found", "id" -> id)
                    .stringify
                    .byteString
                    .future
                case Right(OptionalEntityAndContext(option, _, _, _, _)) =>
                  option match {
                    case None                                      =>
                      Json
                        .obj(
                          "status"            -> 404,
                          "error"             -> "not_found",
                          "error_description" -> "Entity not found",
                          "id"                -> id
                        )
                        .stringify
                        .byteString
                        .future
                    case Some(entity) if !ctx.canUserWrite(entity) => ByteString.empty.future
                    case Some(entity)                              => {
                      val currentJson = writeEntity(entity)
                      val newJson     = patchJson(patch, currentJson)
                      readAndValidateEntity(newJson, ctx.backOfficeUser) match {
                        case Left(e)          =>
                          Json
                            .obj(
                              "status"            -> 400,
                              "error"             -> "bad_entity",
                              "error_description" -> e,
                              "id"                -> extractId(entity)
                            )
                            .stringify
                            .byteString
                            .future
                        case Right(newEntity) => {
                          updateEntityOps(newEntity, ctx.request).map {
                            case Left(error)                                              =>
                              Json
                                .obj(
                                  "status"            -> error.status,
                                  "error"             -> "update_error",
                                  "error_description" -> error.bodyAsJson,
                                  "id"                -> extractId(entity)
                                )
                                .stringify
                                .byteString
                            case Right(EntityAndContext(_, action, message, meta, alert)) =>
                              val event: AdminApiEvent = AdminApiEvent(
                                env.snowflakeGenerator.nextIdStr(),
                                env.env,
                                Some(ctx.apiKey),
                                ctx.user,
                                action,
                                message,
                                ctx.from,
                                ctx.ua,
                                meta
                              )
                              Audit.send(event)
                              Alerts.send(
                                GenericAlert(
                                  env.snowflakeGenerator.nextIdStr(),
                                  env.env,
                                  ctx.user.getOrElse(ctx.apiKey.toJson),
                                  alert,
                                  event,
                                  ctx.from,
                                  ctx.ua
                                )
                              )
                              Json
                                .obj("status" -> 200, "updated" -> true, "id" -> extractId(entity))
                                .stringify
                                .byteString
                          }
                        }
                      }
                    }
                  }
              }
            }
          }
        Ok.sendEntity(
          HttpEntity.Streamed.apply(
            data = src.filterNot(_.isEmpty).intersperse(ByteString.empty, ByteString("\n"), ByteString.empty),
            contentLength = None,
            contentType = Some("application/x-ndjson")
          )
        ).future
      }
      case _                            =>
        BadRequest(Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type")).future
    }
  }

  def bulkDelete(ctx: ApiActionContext[Source[ByteString, _]]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc  = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    def actualDelete() = {
      val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
      val src      = ctx.request.body
        .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, true))
        .map(bs => Try(Json.parse(bs.utf8String)))
        .collect { case Success(e) => e }
        .map(e =>
          readId(e) match {
            case Left(err)     => Left((err, e))
            case Right(entity) => Right(("--", entity))
          }
        )
        .mapAsync(grouping) {
          case Left((error, json)) =>
            Json
              .obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json)
              .stringify
              .byteString
              .future
          case Right((_, _id))     => {
            val id = processId(_id, ctx)
            findByIdOps(id, ctx.request).flatMap {
              case Left(err)                                                                        =>
                Json
                  .obj("status" -> err.status, "error" -> "not_found", "error_description" -> err.bodyAsJson)
                  .stringify
                  .byteString
                  .future
              case Right(optent) if optent.entity.isEmpty                                           =>
                Json
                  .obj("status" -> 404, "error" -> "not_found", "error_description" -> "Entity does not exists")
                  .stringify
                  .byteString
                  .future
              case Right(optent) if optent.entity.isDefined && !ctx.canUserWrite(optent.entity.get) =>
                ByteString.empty.future
              case Right(optent) if optent.entity.isDefined && ctx.canUserWrite(optent.entity.get)  =>
                deleteEntityOps(id, ctx.request).map {
                  case Left(error)                                             =>
                    Json
                      .obj(
                        "status"            -> error.status,
                        "error"             -> "delete_error",
                        "error_description" -> error.bodyAsJson,
                        "id"                -> id
                      )
                      .stringify
                      .byteString
                  case Right(NoEntityAndContext(action, message, meta, alert)) =>
                    val event: AdminApiEvent = AdminApiEvent(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      Some(ctx.apiKey),
                      ctx.user,
                      action,
                      message,
                      ctx.from,
                      ctx.ua,
                      meta
                    )
                    Audit.send(event)
                    Alerts.send(
                      GenericAlert(
                        env.snowflakeGenerator.nextIdStr(),
                        env.env,
                        ctx.user.getOrElse(ctx.apiKey.toJson),
                        alert,
                        event,
                        ctx.from,
                        ctx.ua
                      )
                    )
                    Json.obj("status" -> 200, "deleted" -> true, "id" -> id).stringify.byteString
                }
            }
          }
        }
      Ok.sendEntity(
        HttpEntity.Streamed.apply(
          data = src.filterNot(_.isEmpty).intersperse(ByteString.empty, ByteString("\n"), ByteString.empty),
          contentLength = None,
          contentType = Some("application/x-ndjson")
        )
      ).future
    }

    (ctx.request.headers.get("Content-Type"), ctx.request.headers.get("X-Content-Type")) match {
      case (Some("application/x-ndjson"), _) => actualDelete()
      case (_, Some("application/x-ndjson")) => actualDelete()
      case _                                 =>
        BadRequest(Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type")).future
    }
  }
}

trait BulkControllerHelper[Entity <: EntityLocationSupport, Error] extends BulkHelper[Entity, Error] {

  private val sourceBodyParser = BodyParser("BulkController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)(env.otoroshiExecutionContext)
  }

  def ApiAction: ApiAction
  def bulkUpdateAction() = ApiAction.async(sourceBodyParser) { ctx => bulkUpdate(ctx) }
  def bulkCreateAction() = ApiAction.async(sourceBodyParser) { ctx => bulkCreate(ctx) }
  def bulkPatchAction()  = ApiAction.async(sourceBodyParser) { ctx => bulkPatch(ctx) }
  def bulkDeleteAction() = ApiAction.async(sourceBodyParser) { ctx => bulkDelete(ctx) }
}

trait CrudHelper[Entity <: EntityLocationSupport, Error] extends EntityHelper[Entity, Error] {

  import Results._

  def isApikey: Boolean = false

  def env: Env

  def create(ctx: ApiActionContext[JsValue]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc  = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    val rawBody        = ctx.request.body.asObject
    val dev            = if (env.isDev) "_dev" else ""
    val id             = rawBody
      .select("id")
      .asOpt[String]
      .orElse(rawBody.select("clientId").asOpt[String])
      .orElse(rawBody.select("client_id").asOpt[String])
      .getOrElse(s"${singularName}${dev}_${IdGenerator.uuid}")
    val body: JsObject = if (isApikey) {
      rawBody ++ Json.obj("client_id" -> id, "clientId" -> id)
    } else {
      rawBody ++ Json.obj("id" -> id)
    }
    readAndValidateEntity(body, ctx.backOfficeUser) match {
      case Left(e)                                    =>
        e.debugPrintln
        BadRequest(Json.obj("error" -> "bad_format", "error_description" -> "Bad entity format")).future
      case Right(entity) if !ctx.canUserWrite(entity) =>
        Forbidden(Json.obj("error" -> "forbidden", "error_description" -> "You're not allowed to do this !")).future
      case Right(newentity)                           => {
        findByIdOps(processId(id, ctx), ctx.request).flatMap {
          case Left(error)                                         =>
            Status(error.status)(Json.obj("error" -> "not_found", "error_description" -> error.bodyAsJson)).future
          case Right(OptionalEntityAndContext(option, _, _, _, _)) => {
            option match {
              case Some(_) =>
                BadRequest(Json.obj("error" -> "not_found", "error_description" -> "Entity already exists")).future
              case None    => {
                createEntityOps(newentity, ctx.request).map {
                  case Left(error)                                                   =>
                    Status(error.status)(Json.obj("error" -> "creation_error", "error_description" -> error.bodyAsJson))
                  case Right(EntityAndContext(entity, action, message, meta, alert)) =>
                    val event: AdminApiEvent = AdminApiEvent(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      Some(ctx.apiKey),
                      ctx.user,
                      action,
                      message,
                      ctx.from,
                      ctx.ua,
                      meta
                    )
                    Audit.send(event)
                    Alerts.send(
                      GenericAlert(
                        env.snowflakeGenerator.nextIdStr(),
                        env.env,
                        ctx.user.getOrElse(ctx.apiKey.toJson),
                        alert,
                        event,
                        ctx.from,
                        ctx.ua
                      )
                    )
                    Created(writeEntity(entity))
                }
              }
            }
          }
        }
      }
    }
  }

  def filterPrefix: Option[String] = "filter.".some

  def findAllEntities(ctx: ApiActionContext[AnyContent]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc  = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    val paginationPage: Int     = ctx.request.queryString
      .get("page")
      .flatMap(_.headOption)
      .map(_.toInt)
      .getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString
        .get("pageSize")
        .flatMap(_.headOption)
        .map(_.toInt)
        .getOrElse(Int.MaxValue)

    val paginationPosition = (paginationPage - 1) * paginationPageSize
    val prefix             = filterPrefix
    val filters            = ctx.request.queryString
      .mapValues(_.last)
      .collect {
        case v if prefix.isEmpty                                  => v
        case v if prefix.isDefined && v._1.startsWith(prefix.get) => (v._1.replace(prefix.get, ""), v._2)
      }
      .filterNot(a => a._1 == "page" || a._1 == "pageSize" || a._1 == "fields")
    val hasFilters         = filters.nonEmpty
    val fields             = ctx.request.getQueryString("fields").map(_.split(",").toSeq).getOrElse(Seq.empty[String])
    val hasFields          = fields.nonEmpty
    val filtered           = ctx.request
      .getQueryString("filtered")
      .map(
        _.split(",")
          .map(r => {
            val field = r.split(":")
            (field.head, field.last)
          })
          .toSeq
      )
      .getOrElse(Seq.empty[(String, String)])

    def sortFinalItems(values: Seq[JsValue]): Seq[JsValue] = {
      val sorted    = ctx.request
        .getQueryString("sorted")
        .map(
          _.split(",")
            .map(r => {
              val field = r.split(":")
              (field.head, field.last.toBoolean)
            })
            .toSeq
        )
        .getOrElse(Seq.empty[(String, Boolean)])
      val hasSorted = sorted.nonEmpty

      if (hasSorted) {
        sorted.foldLeft(values) { case (sortedArray, sort) =>
          val out = sortedArray
            .sortBy(r => {
              String.valueOf(JsonOperationsHelper.getValueAtPath(sort._1.toLowerCase(), r)._2)
            })(Ordering[String].reverse)

          // sort._2 = descending order
          if (sort._2) {
            out.reverse
          } else {
            out
          }

        }
      } else {
        values
      }
    }

    findAllOps(ctx.request).map {
      case Left(error)                                                        =>
        Status(error.status)(Json.obj("error" -> "fetch_error", "error_description" -> error.bodyAsJson))
      case Right(SeqEntityAndContext(entities, action, message, metadata, _)) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            action,
            message,
            ctx.from,
            ctx.ua,
            metadata
          )
        )
        val jsonElements: Seq[JsValue] =
          entities.filter(ctx.canUserRead).map(writeEntity)

        val reducedItems  = if (hasFilters) {
          val items: Seq[JsValue] = jsonElements.filter { elem =>
            filters.forall { case (key, value) =>
              (elem \ key).as[JsValue] match {
                case JsString(v)     => v == value
                case JsBoolean(v)    => v == value.toBoolean
                case JsNumber(v)     => v.toDouble == value.toDouble
                case JsArray(values) => values.exists {
                  case JsString(v) => v == value
                  case JsBoolean(v) => v == value.toBoolean
                  case JsNumber(v) => v.toDouble == value.toDouble
                  case JsArray(values) => values.contains(JsString(value))
                  case _ => false
                }
                case _               => false
              }
            }
          }
          items
        } else {
          jsonElements
        }
        val filteredItems = sortFinalItems(if (filtered.nonEmpty) {
          val items: Seq[JsValue] = reducedItems.filter { elem =>
            filtered.forall { case (key, value) =>
              JsonOperationsHelper.getValueAtPath(key.toLowerCase(), elem)._2.asOpt[JsValue] match {
                case Some(v) =>
                  v match {
                    case JsString(v)              => v.toLowerCase().indexOf(value) != -1
                    case JsBoolean(v)             => v == value.toBoolean
                    case JsNumber(v)              => v.toDouble == value.toDouble
                    case JsArray(values)          => values.exists {
                      case JsString(v) => v == value
                      case JsBoolean(v) => v == value.toBoolean
                      case JsNumber(v) => v.toDouble == value.toDouble
                      case JsArray(values) => values.contains(JsString(value))
                      case _ => false
                    }
                    case JsObject(v) if v.isEmpty =>
                      JsonOperationsHelper.getValueAtPath(key, elem)._2.asOpt[JsValue] match {
                        case Some(v) =>
                          v match {
                            case JsString(v)     => v.toLowerCase().indexOf(value) != -1
                            case JsBoolean(v)    => v == value.toBoolean
                            case JsNumber(v)     => v.toDouble == value.toDouble
                            case JsArray(values) => values.exists {
                              case JsString(v) => v == value
                              case JsBoolean(v) => v == value.toBoolean
                              case JsNumber(v) => v.toDouble == value.toDouble
                              case JsArray(values) => values.contains(JsString(value))
                              case _ => false
                            }
                            case _               => false
                          }
                        case _       => false
                      }
                    case _                        => false
                  }
                case _       =>
                  false
              }
            }
          }
          items
        } else {
          reducedItems
        })
        val finalItems    = if (hasFields) {
          filteredItems.map { item =>
            JsonOperationsHelper.filterJson(item.as[JsObject], fields)
          }
        } else {
          filteredItems
        }

        val sortedFinalItems = finalItems
          .slice(paginationPosition, paginationPosition + paginationPageSize)

        if (!ctx.request.accepts("application/json") && ctx.request.accepts("application/x-ndjson")) {
          Ok.sendEntity(
            HttpEntity.Streamed(
              data = Source(sortedFinalItems.toList.map(e => e.stringify.byteString)),
              contentLength = None,
              contentType = "application/x-ndjson".some
            )
          ).withHeaders(
            "X-Count"     -> entities.size.toString,
            "X-Offset"    -> paginationPosition.toString,
            "X-Page"      -> paginationPage.toString,
            "X-Page-Size" -> paginationPageSize.toString
          )
        } else {
          Ok(JsArray(sortedFinalItems)).withHeaders(
            "X-Count"     -> entities.size.toString,
            "X-Offset"    -> paginationPosition.toString,
            "X-Page"      -> paginationPage.toString,
            "X-Page-Size" -> paginationPageSize.toString
          )
        }
      }
    }
  }

  def findEntityById(id: String, ctx: ApiActionContext[AnyContent]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc  = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    findByIdOps(processId(id, ctx), ctx.request).map {
      case Left(error)                                                               =>
        Status(error.status)(Json.obj("error" -> "find_error", "error_description" -> error.bodyAsJson))
      case Right(OptionalEntityAndContext(entity, action, message, metadata, alert)) =>
        entity match {
          case None                           => NotFound(Json.obj("error" -> "not_found", "error_description" -> s"entity not found"))
          case Some(v) if !ctx.canUserRead(v) =>
            Forbidden(Json.obj("error" -> "forbidden", "error_description" -> "You're not allowed to do this !"))
          case Some(v)                        =>
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                action,
                message,
                ctx.from,
                ctx.ua,
                metadata
              )
            )
            val fields    = ctx.request.getQueryString("fields").map(_.split(",").toSeq).getOrElse(Seq.empty[String])
            val hasFields = fields.nonEmpty
            if (hasFields) {
              val out = writeEntity(v).as[JsObject]
              // TODO: support dotted notation ?
              Ok(JsObject(out.value.filterKeys(f => fields.contains(f))))
            } else {
              Ok(writeEntity(v))
            }
        }
    }
  }

  def updateEntity(id: String, ctx: ApiActionContext[JsValue]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc  = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    val body: JsObject = if (isApikey) {
      ctx.request.body.asObject ++ Json.obj("client_id" -> id, "clientId" -> id)
    } else {
      ctx.request.body.asObject ++ Json.obj("id" -> id)
    }
    findByIdOps(processId(id, ctx), ctx.request).flatMap {
      case Left(error)                                         =>
        Status(error.status)(
          Json.obj("error" -> "internal_server_error", "error_description" -> error.bodyAsJson)
        ).future
      case Right(OptionalEntityAndContext(option, _, _, _, _)) =>
        option match {
          case None                                            => NotFound(Json.obj("error" -> "not_found", "error_description" -> "Entity not found")).future
          case Some(oldEntity) if !ctx.canUserWrite(oldEntity) =>
            Forbidden(Json.obj("error" -> "forbidden", "error_description" -> "You're not allowed to do this !")).future
          case Some(oldEntity)                                 => {
            readAndValidateEntity(body, ctx.backOfficeUser) match {
              case Left(error)                                =>
                BadRequest(
                  Json.obj("error" -> "bad_entity", "error_description" -> error, "entity" -> ctx.request.body)
                ).future
              case Right(entity) if !ctx.canUserWrite(entity) =>
                Forbidden(
                  Json.obj("error" -> "forbidden", "error_description" -> "You're not allowed to do this !")
                ).future
              case Right(entity)                              => {
                updateEntityOps(entity, ctx.request).map {
                  case Left(error)                                              =>
                    Status(error.status)(Json.obj("error" -> "update_error", "error_description" -> error.bodyAsJson))
                  case Right(EntityAndContext(_, action, message, meta, alert)) =>
                    val event: AdminApiEvent = AdminApiEvent(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      Some(ctx.apiKey),
                      ctx.user,
                      action,
                      message,
                      ctx.from,
                      ctx.ua,
                      meta
                    )
                    Audit.send(event)
                    Alerts.send(
                      GenericAlert(
                        env.snowflakeGenerator.nextIdStr(),
                        env.env,
                        ctx.user.getOrElse(ctx.apiKey.toJson),
                        alert,
                        event,
                        ctx.from,
                        ctx.ua
                      )
                    )
                    Ok(writeEntity(entity))
                }
              }
            }
          }
        }
    }
  }

  def patchEntity(id: String, ctx: ApiActionContext[JsValue]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc  = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    findByIdOps(processId(id, ctx), ctx.request).flatMap {
      case Left(error)                                         =>
        Status(error.status)(Json.obj("error" -> "not_found", "error_description" -> error.bodyAsJson)).future
      case Right(OptionalEntityAndContext(option, _, _, _, _)) =>
        option match {
          case None                                            => NotFound(Json.obj("error" -> "not_found", "error_description" -> "Entity not found")).future
          case Some(oldEntity) if !ctx.canUserWrite(oldEntity) =>
            Forbidden(Json.obj("error" -> "forbidden", "error_description" -> "You're not allowed to do this !")).future
          case Some(oldEntity)                                 => {
            val currentJson = writeEntity(oldEntity)
            val newJson     = patchJson(ctx.request.body, currentJson).asObject ++ (if (isApikey) {
                                                                                  Json.obj(
                                                                                    "client_id" -> id,
                                                                                    "clientId"  -> id
                                                                                  )
                                                                                } else {
                                                                                  Json.obj(
                                                                                    "id" -> id
                                                                                  )
                                                                                })
            readAndValidateEntity(newJson, ctx.backOfficeUser) match {
              case Left(e)                                          => BadRequest(Json.obj("error" -> "bad_entity", "error_description" -> e)).future
              case Right(newEntity) if !ctx.canUserWrite(newEntity) =>
                Forbidden(
                  Json.obj("error" -> "forbidden", "error_description" -> "You're not allowed to do this !")
                ).future
              case Right(newEntity)                                 => {
                updateEntityOps(newEntity, ctx.request).map {
                  case Left(error)                                              =>
                    Status(error.status)(Json.obj("error" -> "update_error", "error_description" -> error.bodyAsJson))
                  case Right(EntityAndContext(_, action, message, meta, alert)) =>
                    val event: AdminApiEvent = AdminApiEvent(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      Some(ctx.apiKey),
                      ctx.user,
                      action,
                      message,
                      ctx.from,
                      ctx.ua,
                      meta
                    )
                    Audit.send(event)
                    Alerts.send(
                      GenericAlert(
                        env.snowflakeGenerator.nextIdStr(),
                        env.env,
                        ctx.user.getOrElse(ctx.apiKey.toJson),
                        alert,
                        event,
                        ctx.from,
                        ctx.ua
                      )
                    )
                    Ok(writeEntity(newEntity))
                }
              }
            }
          }
        }
    }
  }

  def deleteEntities(ids: Seq[String], ctx: ApiActionContext[_]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc  = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    Source(ids.toList)
      .mapAsync(1) { _id =>
        val id = processId(_id, ctx)
        findByIdOps(id, ctx.request).flatMap {
          case Left(err)                                                                        => (id, Some(err)).future
          case Right(optent) if optent.entity.isEmpty                                           => (id, buildError(404, "Entity not found").some).future
          case Right(optent) if optent.entity.isDefined && !ctx.canUserWrite(optent.entity.get) =>
            (id, buildError(401, "You're not allowed").some).future
          case Right(optent) if optent.entity.isDefined && ctx.canUserWrite(optent.entity.get)  =>
            deleteEntityOps(id, ctx.request).map {
              case Left(error)                                             =>
                (id, Some(error))
              case Right(NoEntityAndContext(action, message, meta, alert)) =>
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  action,
                  message,
                  ctx.from,
                  ctx.ua,
                  meta
                )
                Audit.send(event)
                Alerts.send(
                  GenericAlert(
                    env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    ctx.user.getOrElse(ctx.apiKey.toJson),
                    alert,
                    event,
                    ctx.from,
                    ctx.ua
                  )
                )
                (id, None)
            }
        }
      }
      .runFold(Seq.empty[(String, Option[ApiError[Error]])]) { case (seq, (id, done)) =>
        seq :+ (id, done)
      }
      .map { seq =>
        if (seq.size == 1) {
          val (id, done) = seq.head
          if (done.isEmpty) {
            Ok(Json.obj("deleted" -> true))
          } else {
            val error = done.get
            Status(error.status)(Json.obj("error" -> "delete_error", "error_description" -> error.bodyAsJson))
          }
        } else {
          Ok(JsArray(seq.map {
            case (id, Some(error)) =>
              Json.obj("id" -> id, "error" -> "delete_error", "error_description" -> error.bodyAsJson)
            case (id, _)           => Json.obj("id" -> id, "deleted" -> true)
          }))
        }
      }
  }
}

trait CrudControllerHelper[Entity <: EntityLocationSupport, Error] extends CrudHelper[Entity, Error] {

  private val sourceBodyParser = BodyParser("BulkController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)(env.otoroshiExecutionContext)
  }

  def cc: ControllerComponents
  def ApiAction: ApiAction

  def createAction() =
    ApiAction.async(cc.parsers.json) { ctx =>
      create(ctx)
    }

  def findAllEntitiesAction() =
    ApiAction.async { ctx =>
      findAllEntities(ctx)
    }

  def findEntityByIdAction(id: String) =
    ApiAction.async { ctx =>
      findEntityById(id, ctx)
    }

  def updateEntityAction(id: String) =
    ApiAction.async(cc.parsers.json) { ctx =>
      updateEntity(id, ctx)
    }

  def patchEntityAction(id: String) =
    ApiAction.async(cc.parsers.json) { ctx =>
      patchEntity(id, ctx)
    }

  def deleteEntityAction(id: String) =
    ApiAction.async { ctx =>
      deleteEntities(Seq(id), ctx)
    }

  def deleteEntitiesAction() =
    ApiAction.async(cc.parsers.json) { ctx =>
      val ids = (ctx.request.body \ "ids").as[JsArray].value.map(_.as[String])
      deleteEntities(ids, ctx)
    }
}
