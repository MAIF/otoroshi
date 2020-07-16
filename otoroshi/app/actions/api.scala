package actions

import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

import akka.http.scaladsl.util.FastFuture
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import env.Env
import gateway.Errors
import models.{ApiKey, BackOfficeUser}
import otoroshi.models.RightsChecker.{SuperAdminOnly, TenantAdminOnly}
import otoroshi.models._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import security.{IdGenerator, OtoroshiClaim}
import utils.RequestImplicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ApiActionContext {
  val unauthorized = Results.Unauthorized(Json.obj("error" -> "You're not authorized here !"))
  val funauthorized = unauthorized.future
}

case class ApiActionContext[A](apiKey: ApiKey, request: Request[A]) {
  lazy val unauthorized = ApiActionContext.unauthorized
  lazy val funauthorized = ApiActionContext.funauthorized
  def user(implicit env: Env): Option[JsValue] =
    request.headers
      .get(env.Headers.OtoroshiAdminProfile)
      .flatMap(p => Try(Json.parse(new String(Base64.getDecoder.decode(p), Charsets.UTF_8))).toOption)
  def from(implicit env: Env): String = request.theIpAddress
  def ua: String                      = request.theUserAgent
  def currentTenant: TenantId = {
    Option(tenantRef.get()).getOrElse {
      val value = request.headers.get("Otoroshi-Tenant").getOrElse("default")
      val tenantId = TenantId(value)
      tenantRef.set(tenantId)
      tenantId
    }
  }

  private val tenantRef = new AtomicReference[TenantId]()
  private val bouRef = new AtomicReference[Either[String, Option[BackOfficeUser]]]()

  def backOfficeUser(implicit env: Env): Either[String, Option[BackOfficeUser]] = {
    Option(bouRef.get()).getOrElse {
      (
        if (env.bypassUserRightsCheck) {
          Right(None)
        } else {
          request.headers.get("Otoroshi-BackOffice-User") match {
            case None =>
              val tenantAccess = apiKey.metadata.get("otoroshi-tenant-access") // TODO: fix me !!! allow multiple tenants
              val teamAccess = apiKey.metadata.get("otoroshi-teams-access") // TODO: fix me !!!
              (tenantAccess, teamAccess) match {
                case (None, None) => Right(None)
                case (Some(tenants), Some(teams)) =>
                  val user = BackOfficeUser(
                    randomId = IdGenerator.token,
                    name = apiKey.clientName,
                    email = apiKey.clientId,
                    profile = Json.obj(),
                    authConfigId = "apikey",
                    simpleLogin = false,
                    metadata = Map.empty,
                    rights = UserRights(Seq(UserRight(TenantAccess(tenants), teams.split(",").map(_.trim).map(TeamAccess.apply))))
                  )
                  Right(user.some)
                case _ => Left("You're not authorized here (invalid setup) ! ")
              }
            case Some(userJwt) =>
              Try(JWT.require(Algorithm.HMAC512(apiKey.clientSecret)).build().verify(userJwt)) match {
                case Failure(e) =>
                  Left("You're not authorized here !")
                case Success(decoded) => {
                  Option(decoded.getClaim("user"))
                    .flatMap(c => Try(c.asString()).toOption)
                    .flatMap(u => Try(Json.parse(u)).toOption)
                    .flatMap(u => BackOfficeUser.fmt.reads(u).asOpt) match {
                    case None => Left("You're not authorized here !")
                    case Some(user) => Right(user.some)
                  }
                }
              }
          }
        }
        ).debug { either =>
        bouRef.set(either)
      }
    }
  }

  private def rootOrTenantAdmin(user: BackOfficeUser) (f: => Boolean)(implicit env: Env): Boolean = {
    if (env.bypassUserRightsCheck || SuperAdminOnly.canPerform(user, currentTenant) || TenantAdminOnly.canPerform(user, currentTenant)) {
      true
    } else {
      f
    }
  }

  def canUserRead[T <: EntityLocationSupport](item: T)(implicit env: Env): Boolean = {
    backOfficeUser match {
      case Left(_) => false
      case Right(None) => true
      case Right(Some(user)) => rootOrTenantAdmin(user) {
        currentTenant.value == item.location.tenant.value && user.rights.canReadTeams(currentTenant, item.location.teams)
      }
    }
  }
  def canUserWrite[T <: EntityLocationSupport](item: T)(implicit env: Env): Boolean = {
    backOfficeUser match {
      case Left(_) => false
      case Right(None) => true
      case Right(Some(user)) => rootOrTenantAdmin(user) {
        currentTenant.value == item.location.tenant.value && user.rights.canWriteTeams(currentTenant, item.location.teams)
      }
    }
  }

  def checkRights(rc: RightsChecker)(f: Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    if (env.bypassUserRightsCheck) {
      f
    } else {
      backOfficeUser match {
        case Left(error) => Results.Unauthorized(Json.obj("error" -> error)).future
        case Right(None) => f // standard api usage without limitations
        case Right(Some(user)) => if (rc.canPerform(user, currentTenant)) {
          f
        } else {
          Results.Unauthorized(Json.obj("error" -> "You're not authorized here !")).future
        }
      }
    }
  }
  /// utils methods
  def canReadService(id: String)(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    env.datastores.serviceDescriptorDataStore.findById(id).flatMap {
      case Some(service) if canUserRead(service) => f
      case _ => funauthorized
    }
  }
  def canWriteService(id: String)(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    env.datastores.serviceDescriptorDataStore.findById(id).flatMap {
      case Some(service) if canUserWrite(service) => f
      case _ => funauthorized
    }
  }
  def canReadApikey(id: String)(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    env.datastores.apiKeyDataStore.findById(id).flatMap {
      case Some(service) if canUserRead(service) => f
      case _ => funauthorized
    }
  }
  def canWriteApikey(id: String)(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    env.datastores.apiKeyDataStore.findById(id).flatMap {
      case Some(service) if canUserWrite(service) => f
      case _ => funauthorized
    }
  }
  def canReadGroup(id: String)(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    env.datastores.serviceGroupDataStore.findById(id).flatMap {
      case Some(service) if canUserRead(service) => f
      case _ => funauthorized
    }
  }
  def canWriteGroup(id: String)(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    env.datastores.serviceGroupDataStore.findById(id).flatMap {
      case Some(service) if canUserWrite(service) => f
      case _ => funauthorized
    }
  }
  def canWriteAuthModule(id: String)(f: => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    env.datastores.authConfigsDataStore.findById(id).flatMap {
      case Some(mod) if canUserWrite(mod) => f
      case _ => funauthorized
    }
  }
}

class ApiAction(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[ApiActionContext, AnyContent]
    with ActionFunction[Request, ApiActionContext] {

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-api-action")

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

  def error(message: String, ex: Option[Throwable] = None)(implicit request: Request[_]): Future[Result] = {
    ex match {
      case Some(e) => logger.error(s"error message: $message", e)
      case None    => logger.error(s"error message: $message")
    }
    FastFuture.successful(
      Results
        .Unauthorized(Json.obj("error" -> message))
        .withHeaders(
          env.Headers.OtoroshiStateResp -> request.headers.get(env.Headers.OtoroshiState).getOrElse("--")
        )
    )
  }

  override def invokeBlock[A](request: Request[A], block: ApiActionContext[A] => Future[Result]): Future[Result] = {

    implicit val req = request

    val host = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.adminApiHost => {
        request.headers.get(env.Headers.OtoroshiClaim).get.split("\\.").toSeq match {
          case Seq(head, body, signature) => {
            val claim = Json.parse(new String(OtoroshiClaim.decoder.decode(body), Charsets.UTF_8))
            (claim \ "sub").as[String].split(":").toSeq match {
              case Seq("apikey", clientId) => {
                env.datastores.globalConfigDataStore
                  .singleton()
                  .filter(c => request.method.toLowerCase() == "get" || !c.apiReadOnly)
                  .flatMap { _ =>
                    env.datastores.apiKeyDataStore.findById(clientId).flatMap {
                      case Some(apikey) if apikey.authorizedOnGroup(env.backOfficeGroup.id) || apikey.authorizedOnService(env.backOfficeDescriptor.id) => {
                        block(ApiActionContext(apikey, request)).foldM {
                          case Success(res) =>
                            res
                              .withHeaders(
                                env.Headers.OtoroshiStateResp -> request.headers
                                  .get(env.Headers.OtoroshiState)
                                  .getOrElse("--")
                              )
                              .asFuture
                          case Failure(err) => error(s"Server error : $err", Some(err))
                        }
                      }
                      case _ => error(s"You're not authorized - ${request.method} ${request.uri}")
                    }
                  } recoverWith {
                  case e =>
                    e.printStackTrace()
                    error(s"You're not authorized - ${request.method} ${request.uri}")
                }
              }
              case _ => error(s"You're not authorized - ${request.method} ${request.uri}")
            }
          }
          case _ => error(s"You're not authorized - ${request.method} ${request.uri}")
        }
      }
      case _ => error(s"Not found")
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

case class UnAuthApiActionContent[A](req: Request[A])

class UnAuthApiAction(val parser: BodyParser[AnyContent])(implicit env: Env)
    extends ActionBuilder[UnAuthApiActionContent, AnyContent]
    with ActionFunction[Request, UnAuthApiActionContent] {

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-api-action")

  def error(message: String, ex: Option[Throwable] = None)(implicit request: Request[_]): Future[Result] = {
    ex match {
      case Some(e) => logger.error(s"error message: $message", e)
      case None    => logger.error(s"error message: $message")
    }
    FastFuture.successful(
      Results
        .Unauthorized(Json.obj("error" -> message))
        .withHeaders(
          env.Headers.OtoroshiStateResp -> request.headers.get(env.Headers.OtoroshiState).getOrElse("--")
        )
    )
  }

  override def invokeBlock[A](request: Request[A],
                              block: UnAuthApiActionContent[A] => Future[Result]): Future[Result] = {

    implicit val req = request

    val host = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.adminApiHost => block(UnAuthApiActionContent(request))
      case _                => error(s"Not found")
    }
  }

  override protected def executionContext: ExecutionContext = ec
}
