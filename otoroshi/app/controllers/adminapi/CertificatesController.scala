package controllers.adminapi

import actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import env.Env
import otoroshi.utils.controllers.{ApiError, BulkControllerHelper, CrudControllerHelper, EntityAndContext, JsonApiError, NoEntityAndContext, OptionalEntityAndContext, SeqEntityAndContext}
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}
import ssl.Cert

import scala.concurrent.{ExecutionContext, Future}

class CertificatesController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[Cert, JsValue] with CrudControllerHelper[Cert, JsValue] {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-certificates-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] = JsonApiError(status, play.api.libs.json.JsString(message))

  def renewCert(id: String) = ApiAction.async { ctx =>
    env.datastores.certificatesDataStore.findById(id).map(_.map(_.enrich())).flatMap {
      case None       => FastFuture.successful(NotFound(Json.obj("error" -> s"No Certificate found")))
      case Some(cert) => cert.renew().map(c => Ok(c.toJson))
    }
  }

  override def extractId(entity: Cert): String = entity.id

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
}