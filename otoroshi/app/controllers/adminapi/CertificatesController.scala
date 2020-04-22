package controllers.adminapi

import actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import env.Env
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}
import ssl.Cert
import utils._

import scala.concurrent.{ExecutionContext, Future}

class CertificatesController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[Cert, JsValue] with CrudControllerHelper[Cert, JsValue] {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-certificates-api")

  def renewCert(id: String) = ApiAction.async { ctx =>
    env.datastores.certificatesDataStore.findById(id).map(_.map(_.enrich())).flatMap {
      case None       => FastFuture.successful(NotFound(Json.obj("error" -> s"No Certificate found")))
      case Some(cert) => cert.renew().map(c => Ok(c.toJson))
    }
  }

  override def readEntity(json: JsValue): Either[String, Cert] = Cert._fmt.reads(json).asEither match {
    case Left(e) => Left(e.toString())
    case Right(r) => Right(r)
  }

  override def writeEntity(entity: Cert): JsValue = Cert._fmt.writes(entity)

  override def findByIdOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[Cert]]] = {
    env.datastores.certificatesDataStore.findById(id).map { opt =>
      Right(OptionalEntityAndContext(
        entity = opt,
        action = "ACCESS_CERTIFICATE",
        message = "User accessed a certificate",
        metadata = Json.obj("CertId" -> id),
        alert = "CertAccessed"
      ))
    }
  }

  override def findAllOps(req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[Cert]]] = {
    env.datastores.certificatesDataStore.findAll().map { seq =>
      Right(SeqEntityAndContext(
        entity = seq,
        action = "ACCESS_ALL_CERTIFICATES",
        message = "User accessed all certificates",
        metadata = Json.obj(),
        alert = "CertsAccessed"
      ))
    }
  }

  override def createEntityOps(entity: Cert)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Cert]]] = {
    env.datastores.certificatesDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "CREATE_CERTIFICATE",
          message = "User created a certificate",
          metadata = entity.toJson.as[JsObject],
          alert = "CertCreatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "certificate not stored ...")
        ))
      }
    }
  }

  override def updateEntityOps(entity: Cert)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Cert]]] = {
    env.datastores.certificatesDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "UPDATE_CERTIFICATE",
          message = "User updated a certificate",
          metadata = entity.toJson.as[JsObject],
          alert = "CertUpdatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "certificate not stored ...")
        ))
      }
    }
  }

  override def deleteEntityOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[Cert]]] = {
    env.datastores.certificatesDataStore.delete(id).map {
      case true => {
        Right(NoEntityAndContext(
          action = "DELETE_CERTIFICATE",
          message = "User deleted a certificate",
          metadata = Json.obj("CertId" -> id),
          alert = "CertDeletedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "certificate not deleted ...")
        ))
      }
    }
  }

  /*
  def createCert() = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "id").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("id" -> IdGenerator.token(64))
      case Some(b) => ctx.request.body.as[JsObject]
    }
    Cert.fromJsonSafe(body) match {
      case JsError(e) => BadRequest(Json.obj("error" -> "Bad Cert format")).asFuture
      case JsSuccess(group, _) =>
        group.enrich().save().map {
          case true => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "CREATE_CERTIFICATE",
              s"User created a certificate",
              ctx.from,
              ctx.ua,
              body
            )
            Audit.send(event)
            Alerts.send(
              CertCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(ctx.apiKey.toJson),
                event,
                ctx.from,
                ctx.ua)
            )
            Ok(group.toJson)
          }
          case false => InternalServerError(Json.obj("error" -> "Certificate not stored ..."))
        }
    }
  }

  def updateCert(CertId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.certificatesDataStore.findById(CertId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Certificate with clienId '$CertId' not found")).asFuture
      case Some(group) => {
        Cert.fromJsonSafe(ctx.request.body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad Certificate format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id != CertId =>
            BadRequest(Json.obj("error" -> "Bad Certificate format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id == CertId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_CERTIFICATE",
              s"User updated a certificate",
              ctx.from,
              ctx.ua,
              ctx.request.body
            )
            Audit.send(event)
            Alerts.send(
              CertUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(ctx.apiKey.toJson),
                event,
                ctx.from,
                ctx.ua)
            )
            newGroup.enrich().save().map(_ => Ok(newGroup.toJson))
          }
        }
      }
    }
  }

  def patchCert(CertId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.certificatesDataStore.findById(CertId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Certificate with clienId '$CertId' not found")).asFuture
      case Some(group) => {
        val currentGroupJson = group.toJson
        val newGroupJson     = patchJson(ctx.request.body, currentGroupJson)
        Cert.fromJsonSafe(newGroupJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad Certificate format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id != CertId =>
            BadRequest(Json.obj("error" -> "Bad Certificate format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id == CertId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_CERTIFICATE",
              s"User updated a certificate",
              ctx.from,
              ctx.ua,
              ctx.request.body
            )
            Audit.send(event)
            Alerts.send(
              CertUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                env.env,
                ctx.user.getOrElse(ctx.apiKey.toJson),
                event,
                ctx.from,
                ctx.ua)
            )
            newGroup.enrich().save().map(_ => Ok(newGroup.toJson))
          }
        }
      }
    }
  }

  def deleteCert(CertId: String) = ApiAction.async { ctx =>
    env.datastores.certificatesDataStore.findById(CertId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Certificate with id: '$CertId' not found")).asFuture
      case Some(cert) =>
        cert.delete().map { res =>
          val event: AdminApiEvent = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "DELETE_CERTIFICATE",
            s"User deleted a certificate",
            ctx.from,
            ctx.ua,
            Json.obj("CertId" -> CertId)
          )
          Audit.send(event)
          Alerts.send(
            CertDeleteAlert(env.snowflakeGenerator.nextIdStr(),
              env.env,
              ctx.user.getOrElse(ctx.apiKey.toJson),
              event,
              ctx.from,
              ctx.ua)
          )
          Ok(Json.obj("deleted" -> res))
        }
    }
  }

  def allCerts() = ApiAction.async { ctx =>
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
        ctx.ua,
        "ACCESS_ALL_CERTIFICATES",
        s"User accessed all certificates",
        ctx.from
      )
    )
    val id: Option[String]       = ctx.request.queryString.get("id").flatMap(_.headOption)
    val domain: Option[String]   = ctx.request.queryString.get("domain").flatMap(_.headOption)
    val client: Option[Boolean]  = ctx.request.queryString.get("client").flatMap(_.headOption).map(_.contains("true"))
    val ca: Option[Boolean]      = ctx.request.queryString.get("ca").flatMap(_.headOption).map(_.contains("true"))
    val keypair: Option[Boolean] = ctx.request.queryString.get("keypair").flatMap(_.headOption).map(_.contains("true"))
    val hasFilters               = id.orElse(domain).orElse(client).orElse(ca).orElse(keypair).isDefined
    env.datastores.certificatesDataStore.streamedFindAndMat(_ => true, 50, paginationPage, paginationPageSize).map {
      groups =>
        if (hasFilters) {
          Ok(
            JsArray(
              groups
                .filter {
                  case group if keypair.isDefined && keypair.get && group.keypair => true
                  case group if ca.isDefined && ca.get && group.ca                => true
                  case group if client.isDefined && client.get && group.client    => true
                  case group if id.isDefined && group.id == id.get                => true
                  case group if domain.isDefined && group.domain == domain.get    => true
                  case _                                                          => false
                }
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(groups.map(_.toJson)))
        }
    }
  }

  def oneCert(CertId: String) = ApiAction.async { ctx =>
    env.datastores.certificatesDataStore.findById(CertId).map {
      case None => NotFound(Json.obj("error" -> s"Certificate with id: '$CertId' not found"))
      case Some(group) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_CERTIFICATE",
            s"User accessed a certificate",
            ctx.from,
            ctx.ua,
            Json.obj("certId" -> CertId)
          )
        )
        Ok(group.toJson)
      }
    }
  }
  */
}