package controllers.adminapi

import actions.ApiAction
import akka.http.scaladsl.util.FastFuture
import env.Env
import events._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents}
import security.IdGenerator
import ssl.Cert
import utils.JsonPatchHelpers.patchJson

import otoroshi.utils.syntax.implicits._

class CertificatesController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-certificates-api")

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

  def renewCert(id: String) = ApiAction.async { ctx =>
    env.datastores.certificatesDataStore.findById(id).map(_.map(_.enrich())).flatMap {
      case None       => FastFuture.successful(NotFound(Json.obj("error" -> s"No Certificate found")))
      case Some(cert) => cert.renew().map(c => Ok(c.toJson))
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
}