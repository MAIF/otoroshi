package utils

import actions.{ApiAction, ApiActionContext}
import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import env.Env
import events._
import org.joda.time.DateTime
import otoroshi.utils.syntax.implicits._
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc._
import security.IdGenerator
import utils.JsonPatchHelpers.patchJson

import scala.concurrent.{ExecutionContext, Future}

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
case class OptionalEntityAndContext[Entity](entity: Option[Entity], action: String, message: String, metadata: JsObject, alert: String)
case class SeqEntityAndContext[Entity](entity: Seq[Entity], action: String, message: String, metadata: JsObject, alert: String)

case class GenericAlert(`@id`: String,
                              `@env`: String,
                              user: JsValue,
                              alertName: String,
                              audit: AuditEvent,
                              from: String,
                              ua: String,
                              `@timestamp`: DateTime = DateTime.now())
  extends AlertEvent {
  override def `@service`: String            = "Otoroshi"
  override def `@serviceId`: String          = "--"
  override def fromOrigin: Option[String]    = Some(from)
  override def fromUserAgent: Option[String] = Some(ua)
  override def toJson(implicit _env: Env): JsValue = Json.obj(
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
    "audit"         -> audit.toJson
  )
}

trait EntityHelper[Entity, Error] {
  def readId(json: JsValue): Either[String, String] = {
    (json \ "id").asOpt[String] match {
      case Some(id) => Right(id)
      case None => Left("Id not found !")
    }
  }
  def readEntity(json: JsValue): Either[String, Entity]
  def writeEntity(entity: Entity): JsValue
  def findByIdOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[Error], OptionalEntityAndContext[Entity]]]
  def findAllOps(req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[Error], SeqEntityAndContext[Entity]]]
  def createEntityOps(entity: Entity)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[Error], EntityAndContext[Entity]]]
  def updateEntityOps(entity: Entity)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[Error], EntityAndContext[Entity]]]
  def deleteEntityOps(id: String)(implicit env: Env, ec: ExecutionContext):     Future[Either[ApiError[Error], NoEntityAndContext[Entity]]]
}

trait BulkHelper[Entity, Error] extends EntityHelper[Entity, Error] {

  import Results._

  def env: Env

  def bulkCreate(ctx: ApiActionContext[Source[ByteString, _]]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") => {
        val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
        val src = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
          .map(bs => Json.parse(bs.utf8String))
          .map(e => readEntity(e) match {
            case Left(err) => Left((err, e))
            case Right(entity) => Right(("--", entity))
          })
          .mapAsync(grouping) {
            case Left((error, json)) =>
              Json.obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json).stringify.byteString.future
            case Right((_, entity)) => {
              createEntityOps(entity).map {
                case Left(error) =>
                  Json.obj("status" -> error.status, "error" -> "creation_error", "error_description" -> error.bodyAsJson).stringify.byteString
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
                    GenericAlert(env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      ctx.user.getOrElse(ctx.apiKey.toJson),
                      alert,
                      event,
                      ctx.from,
                      ctx.ua)
                  )
                  Json.obj("status" -> 201, "created" -> true).stringify.byteString
              }
            }
          }
        Ok.sendEntity(HttpEntity.Streamed.apply(
          data = src,
          contentLength = None,
          contentType = Some("application/x-ndjson")
        )).future
      }
      case _ => BadRequest(Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type")).future
    }
  }

  def bulkUpdate(ctx: ApiActionContext[Source[ByteString, _]]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") => {
        val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
        val src = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
          .map(bs => Json.parse(bs.utf8String))
          .map(e => readEntity(e) match {
            case Left(err) => Left((err, e))
            case Right(entity) => Right(("--", entity))
          })
          .mapAsync(grouping) {
            case Left((error, json)) =>
              Json.obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json).stringify.byteString.future
            case Right((_, entity)) => {
              updateEntityOps(entity).map {
                case Left(error) =>
                  Json.obj("status" -> error.status, "error" -> "update_error", "error_description" -> error.bodyAsJson).stringify.byteString
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
                    GenericAlert(env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      ctx.user.getOrElse(ctx.apiKey.toJson),
                      alert,
                      event,
                      ctx.from,
                      ctx.ua)
                  )
                  Json.obj("status" -> 200, "updated" -> true).stringify.byteString
              }
            }
          }
        Ok.sendEntity(HttpEntity.Streamed.apply(
          data = src,
          contentLength = None,
          contentType = Some("application/x-ndjson")
        )).future
      }
      case _ => BadRequest(Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type")).future
    }
  }

  def bulkPatch(ctx: ApiActionContext[Source[ByteString, _]]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") => {
        val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
        val src: Source[ByteString, _] = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
          .map(bs => Json.parse(bs.utf8String))
          .map(e => readId(e) match {
            case Left(err) => Left((err, e))
            case Right(id) => Right((id, (e \ "patch").as[JsValue]))
          })
          .mapAsync(grouping) {
            case Left((error, json)) =>
              Json.obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json).stringify.byteString.future
            case Right((id, patch)) => {
              findByIdOps(id).flatMap {
                case Left(error) => Json.obj("status" -> 404, "error" -> "not_found", "error_description" -> "Entity not found").stringify.byteString.future
                case Right(OptionalEntityAndContext(option, _, _, _, _)) => option match {
                  case None => Json.obj("status" -> 404, "error" -> "not_found", "error_description" -> "Entity not found").stringify.byteString.future
                  case Some(entity) => {
                    val currentJson = writeEntity(entity)
                    val newJson     = patchJson(patch, currentJson)
                    readEntity(newJson) match {
                      case Left(e) => Json.obj("status" -> 400, "error" -> "bad_entity", "error_description" -> e).stringify.byteString.future
                      case Right(newEntity) => {
                        updateEntityOps(newEntity).map {
                          case Left(error) =>
                            Json.obj("status" -> error.status, "error" -> "update_error", "error_description" -> error.bodyAsJson).stringify.byteString
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
                              GenericAlert(env.snowflakeGenerator.nextIdStr(),
                                env.env,
                                ctx.user.getOrElse(ctx.apiKey.toJson),
                                alert,
                                event,
                                ctx.from,
                                ctx.ua)
                            )
                            Json.obj("status" -> 200, "updated" -> true).stringify.byteString
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        Ok.sendEntity(HttpEntity.Streamed.apply(
          data = src.intersperse(ByteString.empty, ByteString("\n"), ByteString.empty),
          contentLength = None,
          contentType = Some("application/x-ndjson")
        )).future
      }
      case _ => BadRequest(Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type")).future
    }
  }

  def bulkDelete(ctx: ApiActionContext[Source[ByteString, _]]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    ctx.request.headers.get("Content-Type") match {
      case Some("application/x-ndjson") => {
        val grouping = ctx.request.getQueryString("_group").map(_.toInt).filter(_ < 10).getOrElse(1)
        val src = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
          .map(bs => Json.parse(bs.utf8String))
          .map(e => readId(e) match {
            case Left(err) => Left((err, e))
            case Right(entity) => Right(("--", entity))
          })
          .mapAsync(grouping) {
            case Left((error, json)) =>
              Json.obj("status" -> 400, "error" -> "bad_entity", "error_description" -> error, "entity" -> json).stringify.byteString.future
            case Right((_, id)) => {
              deleteEntityOps(id).map {
                case Left(error) =>
                  Json.obj("status" -> error.status, "error" -> "delete_error", "error_description" -> error.bodyAsJson).stringify.byteString
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
                    GenericAlert(env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      ctx.user.getOrElse(ctx.apiKey.toJson),
                      alert,
                      event,
                      ctx.from,
                      ctx.ua)
                  )
                  Json.obj("status" -> 200, "deleted" -> true).stringify.byteString
              }
            }
          }
        Ok.sendEntity(HttpEntity.Streamed.apply(
          data = src,
          contentLength = None,
          contentType = Some("application/x-ndjson")
        )).future
      }
      case _ => BadRequest(Json.obj("error" -> "bad_content_type", "error_description" -> "Unsupported content type")).future
    }
  }
}

trait BulkControllerHelper[Entity, Error] extends BulkHelper[Entity, Error] {

  private val sourceBodyParser = BodyParser("BulkController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)(env.otoroshiExecutionContext)
  }

  def ApiAction: ApiAction
  def bulkUpdateAction() = ApiAction.async(sourceBodyParser) { ctx => bulkUpdate(ctx) }
  def bulkCreateAction() = ApiAction.async(sourceBodyParser) { ctx => bulkCreate(ctx) }
  def bulkPatchAction() = ApiAction.async(sourceBodyParser) { ctx => bulkPatch(ctx) }
  def bulkDeleteAction() = ApiAction.async(sourceBodyParser) { ctx => bulkDelete(ctx) }
}

trait CrudHelper[Entity, Error] extends EntityHelper[Entity, Error] {

  import Results._

  def env: Env

  def create(ctx: ApiActionContext[JsValue]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    val body: JsObject = (ctx.request.body \ "id").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("id" -> IdGenerator.token(64))
      case Some(b) => ctx.request.body.as[JsObject]
    }
    readEntity(body) match {
      case Left(e) => BadRequest(Json.obj("error" -> "bad_format", "error_description" -> "Bad entity format")).future
      case Right(entity) =>
        createEntityOps(entity).map {
          case Left(error) => Status(error.status)(Json.obj("error" -> "creation_error", "error_description" -> error.bodyAsJson))
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
              GenericAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(ctx.apiKey.toJson),
                alert,
                event,
                ctx.from,
                ctx.ua)
            )
            Created(writeEntity(entity))
        }
    }
  }

  def findAllEntities(ctx: ApiActionContext[AnyContent]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    val paginationPage: Int = ctx.request.queryString
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

    val filters = ctx.request.queryString.mapValues(_.last).filterNot(a => a._1 == "page" || a._1 == "pageSize")
    val hasFilters = filters.nonEmpty

    findAllOps(ctx.request).map {
      case Left(error) => Status(error.status)(Json.obj("error" -> "find_error", "error_description" -> error.bodyAsJson))
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
        val jsonElements = entities.drop(paginationPosition).take(paginationPageSize).map(writeEntity)
        if (hasFilters) {
          Ok(JsArray(jsonElements.filter { elem =>
            filters.forall {
              case (key, value) => (elem \ key).as[JsValue] match {
                case JsString(v) => v == value
                case JsBoolean(v) => v == value.toBoolean
                case JsNumber(v) => v.toDouble == value.toDouble
                case _ => false
              }
            }
          }))
        } else {
          Ok(JsArray(jsonElements))
        }
      }
    }
  }

  def findEntityById(id: String, ctx: ApiActionContext[AnyContent]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    findByIdOps(id).map {
      case Left(error) => Status(error.status)(Json.obj("error" -> "find_error", "error_description" -> error.bodyAsJson))
      case Right(OptionalEntityAndContext(entity, action, message, metadata, alert)) => entity match {
        case None => NotFound(Json.obj("error" -> "not_found", "error_description" -> s"entity not found"))
        case Some(v) =>
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
          Ok(writeEntity(v))
      }
    }
  }

  def updateEntity(id: String, ctx: ApiActionContext[JsValue]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    readEntity(ctx.request.body) match {
      case Left(error) =>
        BadRequest(Json.obj("error" -> "bad_entity", "error_description" -> error, "entity" -> ctx.request.body)).future
      case Right(entity) => {
        updateEntityOps(entity).map {
          case Left(error) =>
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
              GenericAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(ctx.apiKey.toJson),
                alert,
                event,
                ctx.from,
                ctx.ua)
            )
            Ok(writeEntity(entity))
        }
      }
    }
  }

  def patchEntity(id: String, ctx: ApiActionContext[JsValue]): Future[Result] = {

    implicit val implEnv = env
    implicit val implEc = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    findByIdOps(id).flatMap {
      case Left(error) => NotFound(Json.obj("error" -> "not_found", "error_description" -> "Entity not found")).future
      case Right(OptionalEntityAndContext(option, _, _, _, _)) => option match {
        case None => NotFound(Json.obj("error" -> "not_found", "error_description" -> "Entity not found")).future
        case Some(entity) => {
          val currentJson = writeEntity(entity)
          val newJson     = patchJson(ctx.request.body, currentJson)
          readEntity(newJson) match {
            case Left(e) => BadRequest(Json.obj("error" -> "bad_entity", "error_description" -> e)).future
            case Right(newEntity) => {
              updateEntityOps(newEntity).map {
                case Left(error) =>
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
                    GenericAlert(env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      ctx.user.getOrElse(ctx.apiKey.toJson),
                      alert,
                      event,
                      ctx.from,
                      ctx.ua)
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
    implicit val implEc = env.otoroshiExecutionContext
    implicit val implMat = env.otoroshiMaterializer

    Source(ids.toList).mapAsync(1) { id =>
      deleteEntityOps(id).map {
        case Left(error) =>
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
            GenericAlert(env.snowflakeGenerator.nextIdStr(),
              env.env,
              ctx.user.getOrElse(ctx.apiKey.toJson),
              alert,
              event,
              ctx.from,
              ctx.ua)
          )
          (id, None)
      }
    }.runFold(Seq.empty[(String, Option[ApiError[Error]])]) {
      case (seq, (id, done)) => seq :+ (id, done)
    }.map { seq =>
      if (seq.size == 1) {
        val (id, done) = seq.head
        if (done.isEmpty) {
          Ok(Json.obj("deleted" -> true))
        } else {
          val error = done.get
          Status(error.status)(Json.obj( "error" -> "delete_error", "error_description" -> error.bodyAsJson))
        }
      } else {
        Ok(JsArray(seq.map {
          case (id, Some(error)) => Json.obj("id" -> id, "error" -> "delete_error", "error_description" -> error.bodyAsJson)
          case (id, _) => Json.obj("id" -> id, "deleted" -> true)
        }))
      }
    }
  }
}

trait CrudControllerHelper[Entity, Error] extends CrudHelper[Entity, Error] {

  private val sourceBodyParser = BodyParser("BulkController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)(env.otoroshiExecutionContext)
  }

  def cc: ControllerComponents
  def ApiAction: ApiAction

  def createAction() = ApiAction.async(cc.parsers.json) { ctx =>
    create(ctx)
  }

  def findAllEntitiesAction() = ApiAction.async { ctx =>
    findAllEntities(ctx)
  }

  def findEntityByIdAction(id: String) = ApiAction.async { ctx =>
    findEntityById(id, ctx)
  }

  def updateEntityAction(id: String) = ApiAction.async(cc.parsers.json) { ctx =>
    updateEntity(id, ctx)
  }

  def patchEntityAction(id: String) = ApiAction.async(cc.parsers.json) { ctx =>
    patchEntity(id, ctx)
  }

  def deleteEntityAction(id: String) = ApiAction.async { ctx =>
    deleteEntities(Seq(id), ctx)
  }

  def deleteEntitiesAction() = ApiAction.async(cc.parsers.json) { ctx =>
    val ids = (ctx.request.body \ "ids").as[JsArray].value.map(_.as[String])
    deleteEntities(ids, ctx)
  }
}

