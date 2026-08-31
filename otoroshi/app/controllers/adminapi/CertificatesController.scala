package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import org.apache.pekko.http.scaladsl.util.FastFuture
import otoroshi.env.Env
import otoroshi.utils.controllers.{
  ApiError,
  BulkControllerHelper,
  CrudControllerHelper,
  EntityAndContext,
  JsonApiError,
  NoEntityAndContext,
  OptionalEntityAndContext,
  SeqEntityAndContext
}
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}
import otoroshi.models.RightsChecker
import otoroshi.ssl.Cert
import org.joda.time.DateTime

import scala.concurrent.{ExecutionContext, Future}

class CertificatesController(val ApiAction: ApiAction, val cc: ControllerComponents)(using val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[Cert, JsValue]
    with CrudControllerHelper[Cert, JsValue] {

  implicit lazy val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: org.apache.pekko.stream.Materializer = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-certificates-api")

  override def singularName: String = "certificate"

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  def renewCert(id: String) =
    ApiAction.async { ctx =>
      env.datastores.certificatesDataStore.findById(id).map(_.map(_.enrich())).flatMap {
        case None       => FastFuture.successful(NotFound(Json.obj("error" -> s"No Certificate found")))
        case Some(cert) =>
          cert.renew().map {
            case Left(err) => InternalServerError(Json.obj("error" -> err))
            case Right(c)  => Ok(c.toJson)
          }
      }
    }

  /**
   * One-shot maintenance endpoint for instances that ran the duplicating renewal path: every ACME renewal
   * used to leave an extra active entity behind, so the population of renewable let's encrypt certificates
   * doubled at each cycle and every lineage ordered its own certificate for the same domain, until let's
   * encrypt started refusing them.
   *
   * Collapses each group of let's encrypt certificates holding the exact same chain and key down to a
   * single entity and, when asked for, drops the expired let's encrypt leftovers of the domains that still
   * have a valid certificate. Dry run unless `dryRun=false` is passed: deleting certificates on the user's
   * behalf is not a decision otoroshi takes silently.
   */
  def dedupLetsEncryptCerts() =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        val dryRun        = !ctx.request.getQueryString("dryRun").contains("false")
        val deleteExpired = ctx.request.getQueryString("deleteExpired").contains("true")
        env.datastores.certificatesDataStore.findAll().flatMap { certificates =>
          val letsEncryptCerts = certificates.filter(_.letsEncrypt).map(_.enrich())

          // among the entities holding the exact same chain, the one to keep is the plainly named one - an
          // "[UNTIL EXPIRATION]" or "[EXPIRED]" copy is never a better candidate than the real entity -
          // then the smallest id, so the outcome does not depend on iteration order.
          def score(c: Cert): (Int, String) =
            (if (c.name.startsWith("[UNTIL EXPIRATION] ") || c.name.startsWith("[EXPIRED] ")) 1 else 0, c.id)

          val duplicates = letsEncryptCerts
            .groupBy(_.contentHash)
            .values
            .filter(_.size > 1)
            .flatMap(group => group.sortBy(score).tail)
            .map(_.id)
            .toSeq
            .distinct

          val survivingDomains = letsEncryptCerts
            .filterNot(c => duplicates.contains(c.id))
            .filter(c => c.to.isAfter(DateTime.now()))
            .flatMap(_.allDomains)
            .map(_.toLowerCase)
            .toSet

          // an expired certificate is only dropped when the domains it covers are still covered by a
          // certificate that survives the dedup, so this can never leave a domain without a certificate.
          val expired =
            if (!deleteExpired) Seq.empty[String]
            else
              letsEncryptCerts
                .filterNot(c => duplicates.contains(c.id))
                .filter(c => c.to.isBefore(DateTime.now()))
                .filter(c => c.allDomains.nonEmpty && c.allDomains.forall(d => survivingDomains.contains(d.toLowerCase)))
                .map(_.id)

          val toDelete = (duplicates ++ expired).distinct
          val payload  = Json.obj(
            "dryRun"       -> dryRun,
            "certificates" -> letsEncryptCerts.size,
            "duplicates"   -> JsArray(duplicates.map(JsString.apply)),
            "expired"      -> JsArray(expired.map(JsString.apply)),
            "deleted"      -> (if (dryRun) 0 else toDelete.size)
          )
          if (dryRun || toDelete.isEmpty) {
            FastFuture.successful(Ok(payload))
          } else {
            logger.info(s"deleting ${toDelete.size} duplicated/expired let's encrypt certificates")
            env.datastores.certificatesDataStore.deleteByIds(toDelete).map(_ => Ok(payload))
          }
        }
      }
    }

  override def extractId(entity: Cert): String = entity.id

  override def readEntity(json: JsValue): Either[JsValue, Cert] =
    Cert._fmt.reads(json).asEither match {
      case Left(e)  => Left(JsError.toJson(e))
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: Cert): JsValue = Cert._fmt.writes(entity)

  override def findByIdOps(
      id: String,
      req: RequestHeader
  )(using env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[Cert]]] = {
    env.datastores.certificatesDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_CERTIFICATE",
          message = "User accessed a certificate",
          metadata = Json.obj("CertId" -> id),
          alert = "CertAccessed"
        )
      )
    }
  }

  override def findAllOps(
      req: RequestHeader
  )(using env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[Cert]]] = {
    val keypair = req.queryString.get("keypair").map(_.last).getOrElse("false").toBoolean
    env.datastores.certificatesDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = if (keypair) seq.filter(_.keypair) else seq,
          action = "ACCESS_ALL_CERTIFICATES",
          message = "User accessed all certificates",
          metadata = Json.obj(),
          alert = "CertsAccessed"
        )
      )
    }
  }

  override def createEntityOps(
      entity: Cert,
      req: RequestHeader
  )(using env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Cert]]] = {
    val noEnrich = req.getQueryString("enrich").contains("false")
    val enriched = if (noEnrich) entity else entity.enrich()
    env.datastores.certificatesDataStore.set(enriched).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_CERTIFICATE",
            message = "User created a certificate",
            metadata = entity.toJson.as[JsObject],
            alert = "CertCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "certificate not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
      entity: Cert,
      req: RequestHeader
  )(using env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Cert]]] = {
    val noEnrich = req.getQueryString("enrich").contains("false")
    val enriched = if (noEnrich) entity else entity.enrich()
    env.datastores.certificatesDataStore.set(enriched).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_CERTIFICATE",
            message = "User updated a certificate",
            metadata = entity.toJson.as[JsObject],
            alert = "CertUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "certificate not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
      id: String,
      req: RequestHeader
  )(using env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[Cert]]] = {
    env.datastores.certificatesDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_CERTIFICATE",
            message = "User deleted a certificate",
            metadata = Json.obj("CertId" -> id),
            alert = "CertDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "certificate not deleted ...")
          )
        )
      }
    }
  }
}
