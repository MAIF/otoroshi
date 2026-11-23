package otoroshi.actions

import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import org.apache.pekko.http.scaladsl.util.FastFuture
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.nio.charset.StandardCharsets
import otoroshi.next.models.Api
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{ApiKey, BackOfficeUser, EntityLocationSupport, *}
import otoroshi.models.RightsChecker.{SuperAdminOnly, TenantAdminOnly}
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.mvc.*
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.{JsonPathValidator, JsonValidator}
import otoroshi.utils.http.RequestImplicits.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

object ApiActionContext {

  // an apikey carries the admin api rights of its holder in its own metadata, so writing one is also
  // granting rights: refuse to write an apikey that would grant more than the writer already has. an apikey
  // authorized on the admin api without rights metadata has no restriction at all: only super admins write those.
  // An alias key (ApiRef.credential) gives the access of the credential it shares: only the users who can write that
  // credential write it. A key under the client id of a credential that is gone while aliases still name it would call
  // through them with a secret of its own: only a super admin writes it (an import, a restoration, whatever the order
  // of the keys)
  def canUserGrantApikeyRights(apikey: ApiKey, user: BackOfficeUser)(using env: Env): Boolean =
    canUserGrantOwnRights(apikey, user) && !takesOverCredential(apikey, user) && !mintsCredential(apikey, user) &&
    (apikey.apiRef.flatMap(_.credential) match {
      case None             => true
      case Some(credential) => canUserWriteCredential(credential, user)
    })

  // read from the proxy state, like the aliases themselves: the client id has aliases, and no key
  private def takesOverCredential(apikey: ApiKey, user: BackOfficeUser)(using env: Env): Boolean =
    if (env.bypassUserRightsCheck || user.rights.superAdmin) false
    else env.proxyState.hasApikeyAliases(apikey.clientId) && env.proxyState.apikey(apikey.clientId).isEmpty

  // a minted credential (SharedCredential.minted) is the one the subscriptions of its owner share without naming it:
  // written by hand, it would be shared by the subscriptions of a consumer that never asked for it. Only a super admin
  // writes one the proxy state does not have as such yet (an import, a restoration)
  private def mintsCredential(apikey: ApiKey, user: BackOfficeUser)(using env: Env): Boolean =
    if (env.bypassUserRightsCheck || user.rights.superAdmin || !apikey.sharedCredential.exists(_.minted)) false
    else !env.proxyState.apikey(apikey.clientId).exists(_.sharedCredential == apikey.sharedCredential)

  // the rights an apikey grants by itself
  private def canUserGrantOwnRights(apikey: ApiKey, user: BackOfficeUser)(using env: Env): Boolean = {
    // its rights are the ones of its owner, whatever the apikey says
    if (PersonalAdminApikey.isPersonal(apikey)) PersonalAdminApikey.canWrite(user)
    else canUserGrantRightsMetadata(apikey, user)
  }

  // whether a user can write the apikey of a client id, read from the proxy state: what sharing it with an alias key
  // requires. The credential is checked by itself, without the credential it would share: a credential is never an
  // alias, and two forged keys naming each other do not loop. An apikey the proxy state does not have yet is only
  // written by a super admin
  def canUserWriteCredential(clientId: String, user: BackOfficeUser)(using env: Env): Boolean =
    env.bypassUserRightsCheck || user.rights.superAdmin || env.proxyState
      .apikey(clientId)
      .exists(credential => canUserWriteCredential(credential, user))

  // the same, of a credential that was read already (from the datastore)
  def canUserWriteCredential(credential: ApiKey, user: BackOfficeUser)(using env: Env): Boolean =
    env.bypassUserRightsCheck || user.rights.superAdmin ||
    (canUserWriteApikeyItself(credential, user) && canUserGrantOwnRights(credential, user))

  // what canUserWrite checks of the location of an apikey, without a request: its own tenant, its teams and its kind
  private def canUserWriteApikeyItself(apikey: ApiKey, user: BackOfficeUser)(using env: Env): Boolean = {
    val tenant = apikey.location.tenant
    val teams  = apikey.location.teams
    env.bypassUserRightsCheck || user.rights.superAdmin || (
      user.rights.canWriteTenant(tenant) &&
      user.rights.canWriteTeams(tenant, teams) &&
      user.rights.canWriteKinds(tenant, teams, Seq(apikeysKind))
    )
  }

  private def canUserGrantRightsMetadata(apikey: ApiKey, user: BackOfficeUser)(using env: Env): Boolean = {
    apikey.metadata.get(ApiKey.accessRightsMetadataKey) match {
      case None      =>
        user.rights.superAdmin || !(apikey.authorizedOnGroup(env.backOfficeGroup.id) || apikey
          .authorizedOnService(env.backOfficeDescriptor.id))
      case Some(raw) =>
        Try(Json.parse(raw)).toOption.flatMap(_.asOpt[JsArray]).map(UserRights.readFromArray) match {
          // unreadable rights are read as "no restriction at all" when the apikey is used, which is
          // the widest grant there is
          case None         => false
          case Some(rights) => user.rights.covers(rights)
        }
    }
  }

  // the secret of an apikey is a credential: a caller sees it only if it could have granted that apikey. An apikey
  // that gives access to the admin api is seen by the callers whose rights cover the ones it gives (one without rights
  // metadata by the super admins only), a personal admin apikey by the ones who can read it, any other apikey (a
  // consumer key) by whoever can read it
  def canUserReadApikeySecret(apikey: ApiKey, user: BackOfficeUser, caller: => Option[PersonalAdminApikeyOwner])(using
      env: Env
  ): Boolean = {
    if (env.bypassUserRightsCheck || user.rights.superAdmin) true
    else if (PersonalAdminApikey.isPersonal(apikey)) PersonalAdminApikey.canRead(apikey.metadata, caller, user)
    else if (!(apikey.authorizedOnGroup(env.backOfficeGroup.id) || apikey.authorizedOnService(env.backOfficeDescriptor.id)))
      true
    else canUserGrantRightsMetadata(apikey, user)
  }

  val forbidden  = Results.Forbidden(Json.obj("error" -> "You're not authorized here !"))
  val fforbidden = forbidden.future

  val apikeysKind = EntityKind("apim.otoroshi.io", "apikeys")

  // the backoffice user token is minted for one admin api call and consumed right away, so it has no
  // reason to outlive it. the leeway covers clock skew between cluster nodes
  val backOfficeUserTokenTtlSeconds    = 30L
  val backOfficeUserTokenLeewaySeconds = 10L
}

trait ApiActionContextCapable {

  def apiKey: ApiKey
  def request: RequestHeader

  lazy val forbidden  = ApiActionContext.forbidden
  lazy val fforbidden = ApiActionContext.fforbidden

  def user(using env: Env): Option[JsValue] =
    request.headers
      .get(env.Headers.OtoroshiAdminProfile)
      .flatMap(p => Try(Json.parse(new String(Base64.getDecoder.decode(p), StandardCharsets.UTF_8))).toOption)

  def from(using env: Env): String = request.theIpAddress

  def ua: String = request.theUserAgent

  lazy val currentTenant: TenantId = {
    TenantId(request.headers.get("Otoroshi-Tenant").getOrElse("default"))
  }

  private val bouRef = new AtomicReference[Either[String, Option[BackOfficeUser]]]()

  // a call made with a personal admin apikey (and not on behalf of a backoffice user)
  def isPersonalAdminApikeyCall: Boolean =
    PersonalAdminApikey.isPersonal(apiKey) && request.headers.get("Otoroshi-BackOffice-User").isEmpty

  // the identity of a call made with a personal admin apikey is read from the datastore, asynchronously, before the call
  // is handled (see ApiAction.runBlock)
  private[actions] def resolvedPersonalAdminApikeyUser(user: Either[String, BackOfficeUser]): Unit =
    bouRef.set(user.map(_.some))

  // the owner of a personal admin apikey the caller would be
  private def callerOwner(user: BackOfficeUser)(using env: Env): Option[PersonalAdminApikeyOwner] =
    if (isPersonalAdminApikeyCall) PersonalAdminApikey.ownerOf(apiKey.metadata) else PersonalAdminApikey.ownerFor(user)

  def userIsSuperAdmin(using env: Env): Boolean = {
    backOfficeUser match {
      case Left(_)           => false
      case Right(None)       => {
        val tenantAccess = apiKey.metadata
          .get(ApiKey.accessRightsMetadataKey)
          .map(Json.parse)
          .flatMap(_.asOpt[JsArray])
          .map(UserRights.readFromArray)
        tenantAccess match {
          case None             => true
          case Some(userRights) => userRights.superAdmin
        }
      }
      case Right(Some(user)) => user.rights.superAdmin
    }
  }

  def oneAuthorizedTenant(using env: Env): TenantId =
    backOfficeUser.toOption.flatten.map(_.rights.oneAuthorizedTenant).getOrElse(TenantId.default)

  def oneAuthorizedTeam(using env: Env): TeamId =
    backOfficeUser.toOption.flatten.map(_.rights.oneAuthorizedTeam).getOrElse(TeamId.default)

  def backOfficeUser(using env: Env): Either[String, Option[BackOfficeUser]] = {
    Option(bouRef.get()).getOrElse {
      (
        if (env.bypassUserRightsCheck) {
          Right(None)
        } else {
          request.headers.get("Otoroshi-BackOffice-User") match {
            // resolved from the datastore before the call by ApiAction.runBlock. a context built another way (the admin
            // routes of the extensions) reads them from the proxy state
            case None if PersonalAdminApikey.isPersonal(apiKey) =>
              PersonalAdminApikey.user(apiKey, currentTenant).map(_.some)
            case None          =>
              val tenantAccess = apiKey.metadata
                .get(ApiKey.accessRightsMetadataKey)
                .map(Json.parse)
                .flatMap(_.asOpt[JsArray])
                .map(UserRights.readFromArray)
              tenantAccess match {
                case None             => Right(None)
                case Some(userRights) =>
                  val user = BackOfficeUser(
                    randomId = IdGenerator.token,
                    name = apiKey.clientName,
                    email = apiKey.clientId,
                    profile = Json.obj(),
                    authConfigId = "apikey",
                    simpleLogin = false,
                    tags = Seq.empty,
                    metadata = Map.empty,
                    rights = userRights,
                    location = EntityLocation(currentTenant, teams = Seq(TeamId.all)),
                    adminEntityValidators = Map.empty
                  )
                  Right(user.some)
              }
            case Some(userJwt) =>
              Try(
                JWT
                  .require(Algorithm.HMAC512(env.otoroshiSecret))
                  .acceptLeeway(ApiActionContext.backOfficeUserTokenLeewaySeconds)
                  .build()
                  .verify(userJwt)
              ) match {
                case Failure(e)                                       =>
                  Left("You're not authorized here !")
                // an expiry is required, not just honoured when present: a token minted before it was
                // added would otherwise stay valid forever
                case Success(decoded) if decoded.getExpiresAt == null =>
                  Left("You're not authorized here !")
                case Success(decoded)                                 => {
                  Option(decoded.getClaim("user"))
                    .flatMap(c => Try(c.asString()).toOption)
                    .flatMap(u => Try(Json.parse(u)).toOption)
                    .flatMap(u => BackOfficeUser.fmt.reads(u).asOpt) match {
                    case None       => Left("You're not authorized here !")
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

  private def rootOrTenantAdmin(user: BackOfficeUser)(f: => Boolean)(using env: Env): Boolean = {
    if (env.bypassUserRightsCheck || SuperAdminOnly.canPerform(user, currentTenant)) { // || TenantAdminOnly.canPerform(user, currentTenant)) {
      true
    } else {
      f
    }
  }

  // the kinds are the ones of the entity the json describes: without them, only a right giving access to every kind
  // reaches it
  def canUserReadJson(item: JsValue, kinds: Seq[EntityKind] = Seq.empty)(using env: Env): Boolean = {
    canUserRead(FakeEntityLocationSupport(EntityLocation.readFromKey(item), kinds)) &&
    (!kinds.contains(ApiActionContext.apikeysKind) || canUserAccessPersonalApikey(
      PersonalAdminApikey.metadataOfJson(item),
      write = false
    ))
  }

  // a personal admin apikey is read by its owner and the super admins, written by the super admins only
  private def canUserAccessPersonalApikey(metadata: Map[String, String], write: Boolean)(using env: Env): Boolean = {
    if (!PersonalAdminApikey.isPersonal(metadata)) {
      true
    } else {
      backOfficeUser match {
        case Left(_)           => false
        case Right(None)       => true
        case Right(Some(user)) =>
          if (write) PersonalAdminApikey.canWrite(user)
          else PersonalAdminApikey.canRead(metadata, callerOwner(user), user)
      }
    }
  }

  // whether the caller sees the credentials of an apikey it can read, see ApiActionContext.canUserReadApikeySecret
  def canUserReadApikeySecret(apikey: ApiKey)(using env: Env): Boolean = {
    backOfficeUser match {
      case Left(_)           => false
      case Right(None)       => true
      case Right(Some(user)) => ApiActionContext.canUserReadApikeySecret(apikey, user, callerOwner(user))
    }
  }

  // the json of an apikey in a response of the admin api: without its credentials when the caller cannot see them
  def apikeyJson(apikey: ApiKey)(using env: Env): JsValue =
    if (canUserReadApikeySecret(apikey)) apikey.toJson else ApiKey.withoutSecrets(apikey.toJson)

  def canUserRead[T <: EntityLocationSupport](item: T)(using env: Env): Boolean = {
    backOfficeUser match {
      case Left(_)           => false
      case Right(None)       => true
      case Right(Some(user)) =>
        rootOrTenantAdmin(user) {
          (item match {
            case _: Tenant =>
              user.rights.canReadTenant(item.location.tenant)
            case _         =>
              (currentTenant.value == item.location.tenant.value || item.location.tenant == TenantId.all) && user.rights
                .canReadTenant(item.location.tenant) && user.rights.canReadTeams(currentTenant, item.location.teams)
          }) && user.rights.canReadKinds(item.location.tenant, item.location.teams, EntityKind.of(item)) &&
          (item match {
            case apikey: ApiKey =>
              !PersonalAdminApikey.isPersonal(apikey) ||
              PersonalAdminApikey.canRead(apikey.metadata, callerOwner(user), user)
            case _              => true
          })
        }
    }
  }

  def canUserWriteJson(item: JsValue, kinds: Seq[EntityKind] = Seq.empty)(using env: Env): Boolean = {
    canUserWrite(FakeEntityLocationSupport(EntityLocation.readFromKey(item), kinds)) &&
    (!kinds.contains(ApiActionContext.apikeysKind) || canUserAccessPersonalApikey(
      PersonalAdminApikey.metadataOfJson(item),
      write = true
    ))
  }

  private def canUserGrantApikeyRights(apikey: ApiKey, user: BackOfficeUser)(using env: Env): Boolean =
    ApiActionContext.canUserGrantApikeyRights(apikey, user)

  def canUserWrite[T <: EntityLocationSupport](item: T)(using env: Env): Boolean = {
    backOfficeUser match {
      case Left(_)           => false
      case Right(None)       => true
      case Right(Some(user)) =>
        rootOrTenantAdmin(user) {
          val locationIsMine = item match {
            case _: Tenant =>
              (currentTenant.value == item.location.tenant.value || item.location.tenant == TenantId.all) && user.rights
                .canWriteTenant(item.location.tenant)
            case _         =>
              (currentTenant.value == item.location.tenant.value || item.location.tenant == TenantId.all) && user.rights
                .canWriteTenant(item.location.tenant) && user.rights.canWriteTeams(currentTenant, item.location.teams)
          }
          // apikeys and admins carry the rights of their holder: writing or deleting one with more rights than the
          // caller would let it take over, or lock out, a stronger account located where it can write
          locationIsMine && user.rights.canWriteKinds(item.location.tenant, item.location.teams, EntityKind.of(item)) &&
          (item match {
            case apikey: ApiKey       => canUserGrantApikeyRights(apikey, user)
            case admin: OtoroshiAdmin => user.rights.covers(admin.rights)
            case _                    => true
          })
        }
    }
  }

  // by name, like every other guard here: a by value body would run the whole guarded block, side
  // effects included, before the rights are even checked, and only the result would be discarded
  def checkRights(rc: RightsChecker)(f: => Future[Result])(using ec: ExecutionContext, env: Env): Future[Result] = {
    if (env.bypassUserRightsCheck) {
      f
    } else {
      backOfficeUser match {
        case Left(error)       => Results.Forbidden(Json.obj("error" -> error)).future
        case Right(None)       => f // standard api usage without limitations
        case Right(Some(user)) =>
          if (rc.canPerform(user, currentTenant)) {
            f
          } else {
            Results.Forbidden(Json.obj("error" -> "You're not authorized here !")).future
          }
      }
    }
  }

  private def findServiceById(
      serviceId: String
  )(using ec: ExecutionContext, env: Env): Future[Option[ServiceDescriptor]] = {
    def getRouteCompositions = {
      env.datastores.routeCompositionDataStore.findById(serviceId) flatMap {
        case Some(service) => service.toRoutes.head.legacy.some.vfuture
        case None          => getDraftRoutes
      }
    }

    def getDraftRoutes: Future[Option[ServiceDescriptor]] = {
      env.datastores.draftsDataStore.findById(serviceId) flatMap {
        case Some(service) =>
          val api = Api.format.reads(service.content).get
          if (api.routes.isEmpty) {
            None.vfuture
          } else {
            api.routeToNgRoute(api.routes.head).map(_.get.legacy.some)
          }
        case None          =>
          None.vfuture
      }
    }

    env.datastores.serviceDescriptorDataStore.findById(serviceId) flatMap {
      case Some(service) => service.some.vfuture
      case None          =>
        env.datastores.routeDataStore.findById(serviceId) flatMap {
          case Some(service) => service.legacy.some.vfuture
          case None          =>
            env.datastores.apiDataStore.findById(serviceId) flatMap {
              case Some(api) => api.legacy.some.vfuture
              case None      =>
                env.datastores.apiDataStore.findAll() flatMap { apis =>
                  apis.find(api => api.routes.exists(_.id == serviceId)) match {
                    case Some(api) =>
                      api.apiRouteToNgRoute(serviceId).flatMap {
                        case Some(route) => route.legacy.some.future
                        case _           => getRouteCompositions
                      }
                    case None      => getRouteCompositions
                  }
                }
            }
        }
    }
  }

  /// utils methods
  def canReadService(id: String)(f: => Future[Result])(using ec: ExecutionContext, env: Env): Future[Result] = {
    if (id == "global") {
      f
    } else {
      findServiceById(id).flatMap {
        case None                                  => serviceNotFound(id)
        case Some(service) if canUserRead(service) => f
        case _                                     => fforbidden
      }
    }
  }

  def canWriteService(id: String)(f: => Future[Result])(using ec: ExecutionContext, env: Env): Future[Result] = {
    findServiceById(id).flatMap {
      case None                                   => serviceNotFound(id)
      case Some(service) if canUserWrite(service) => f
      case _                                      => fforbidden
    }
  }

  // an entity that does not exist is not found, one the admin cannot access is forbidden
  private def serviceNotFound(id: String): Future[Result] =
    FastFuture.successful(Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> s"entity '$id' not found")))

  def canReadApikey(id: String)(f: => Future[Result])(using ec: ExecutionContext, env: Env): Future[Result] = {
    env.datastores.apiKeyDataStore.findById(id).flatMap {
      case Some(service) if canUserRead(service) => f
      case _                                     => fforbidden
    }
  }

  def canWriteApikey(id: String)(f: => Future[Result])(using ec: ExecutionContext, env: Env): Future[Result] = {
    env.datastores.apiKeyDataStore.findById(id).flatMap {
      case Some(service) if canUserWrite(service) => f
      case _                                      => fforbidden
    }
  }

  def canReadGroup(id: String)(f: => Future[Result])(using ec: ExecutionContext, env: Env): Future[Result] = {
    env.datastores.serviceGroupDataStore.findById(id).flatMap {
      case Some(service) if canUserRead(service) => f
      case _                                     => fforbidden
    }
  }

  def canWriteGroup(id: String)(f: => Future[Result])(using ec: ExecutionContext, env: Env): Future[Result] = {
    env.datastores.serviceGroupDataStore.findById(id).flatMap {
      case Some(service) if canUserWrite(service) => f
      case _                                      => fforbidden
    }
  }

  def canWriteAuthModule(id: String)(f: => Future[Result])(using ec: ExecutionContext, env: Env): Future[Result] = {
    // env.datastores.authConfigsDataStore.findById(id).flatMap {
    env.proxyState.authModuleAsync(id).flatMap {
      case Some(mod) if canUserWrite(mod) => f
      case _                              => fforbidden
    }
  }

  def validateEntity(json: JsValue, singularName: String)(using env: Env): Either[JsValue, JsValue] = {
    backOfficeUser match {
      case Left(err)         => Left(err.json)
      case Right(None)       => Right(json)
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
          Right(json)
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
}

case class ApiActionContext[A](apiKey: ApiKey, request: Request[A]) extends ApiActionContextCapable {}

class ApiAction(val parser: BodyParser[AnyContent])(using env: Env)
    extends ActionBuilder[ApiActionContext, AnyContent]
    with ActionFunction[Request, ApiActionContext] {

  implicit lazy val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-api-action")
  lazy val jwtVerifier = JWT.require(Algorithm.HMAC512(env.sharedKey)).build()

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), StandardCharsets.UTF_8)

  def error(message: String, ex: Option[Throwable] = None)(using request: Request[?]): Future[Result] = {
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

  // the caller is authenticated at this point: a failure of the controller is a server error, not an
  // authentication one
  def serverError(err: Throwable)(using request: Request[?]): Future[Result] = {
    logger.error(s"error message: Server error : $err", err)
    FastFuture.successful(
      Results
        .InternalServerError(Json.obj("error" -> s"Server error : $err"))
        .withHeaders(
          env.Headers.OtoroshiStateResp -> request.headers.get(env.Headers.OtoroshiState).getOrElse("--")
        )
    )
  }

  // a controller throwing before it returns its future would otherwise end up in the recoverWith of
  // the authentication, and be answered as an authentication failure
  private def runBlock[A](ctx: ApiActionContext[A], block: ApiActionContext[A] => Future[Result]): Future[Result] =
    try {
      // the rights of a personal admin apikey are the ones of its owner at the time of the call, read from the datastore.
      // an apikey whose rights cannot be resolved (its owner has no rights policy giving it rights anymore, the local
      // admin owning it was deleted) is refused right away, rather than answered with empty lists
      if (ctx.isPersonalAdminApikeyCall && !env.bypassUserRightsCheck) {
        PersonalAdminApikey.userF(ctx.apiKey, ctx.currentTenant).flatMap {
          case Left(err)  => Results.Unauthorized(Json.obj("error" -> err)).future
          case Right(usr) =>
            ctx.resolvedPersonalAdminApikeyUser(Right(usr))
            block(ctx)
        }
      } else {
        block(ctx)
      }
    } catch { case NonFatal(e) => Future.failed(e) }

  override def invokeBlock[A](request: Request[A], block: ApiActionContext[A] => Future[Result]): Future[Result] = {

    implicit val req = request

    val host = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
    def perform(): Future[Result] = {
      request.headers.get(env.Headers.OtoroshiClaim).map(_.split("\\.").toSeq) match {
        case Some(Seq(head, body, signature)) => Try(jwtVerifier.verify(s"$head.$body.$signature")) match {
          case Failure(e) => error(s"You're not authorized - ${request.method} ${request.uri}")
          case Success(_) =>
            val claim          = Json.parse(new String(OtoroshiClaim.decoder.decode(body), StandardCharsets.UTF_8))
            val lastestApikey  = (claim \ "access_type").asOpt[String].exists(v => v == "apikey" || v == "both")
            val latestClientId = (claim \ "apikey" \ "clientId").asOpt[String]
            (claim \ "sub").as[String].split(":").toSeq match {
              case Seq("apikey", clientId)                        => {
                env.datastores.globalConfigDataStore
                  .singleton()
                  .filter(c => request.method.toLowerCase() == "get" || !c.apiReadOnly)
                  .flatMap { _ =>
                    env.datastores.apiKeyDataStore.findById(clientId).flatMap {
                      case Some(apikey)
                          if apikey.authorizedOnGroup(env.backOfficeGroup.id) || apikey
                            .authorizedOnService(env.backOfficeDescriptor.id) => {
                        runBlock(ApiActionContext(apikey, request), block).foldM {
                          case Success(res) =>
                            res
                              .withHeaders(
                                env.Headers.OtoroshiStateResp -> request.headers
                                  .get(env.Headers.OtoroshiState)
                                  .getOrElse("--")
                              )
                              .asFuture
                          case Failure(err) => serverError(err)
                        }
                      }
                      case _ => error(s"You're not authorized - ${request.method} ${request.uri}")
                    }
                  } recoverWith { case e =>
                  e.printStackTrace()
                  error(s"You're not authorized - ${request.method} ${request.uri}")
                }
              }
              case _ if lastestApikey && latestClientId.isDefined => {
                env.datastores.globalConfigDataStore
                  .singleton()
                  .filter(c => request.method.toLowerCase() == "get" || !c.apiReadOnly)
                  .flatMap { _ =>
                    env.datastores.apiKeyDataStore.findById(latestClientId.get).flatMap {
                      case Some(apikey)
                          if apikey.authorizedOnGroup(env.backOfficeGroup.id) || apikey
                            .authorizedOnService(env.backOfficeDescriptor.id) => {
                        runBlock(ApiActionContext(apikey, request), block).foldM {
                          case Success(res) =>
                            res
                              .withHeaders(
                                env.Headers.OtoroshiStateResp -> request.headers
                                  .get(env.Headers.OtoroshiState)
                                  .getOrElse("--")
                              )
                              .asFuture
                          case Failure(err) => serverError(err)
                        }
                      }
                      case _ => error(s"You're not authorized - ${request.method} ${request.uri}")
                    }
                  } recoverWith { case e =>
                  e.printStackTrace()
                  error(s"You're not authorized - ${request.method} ${request.uri}")
                }
              }
              case _                                              => error(s"You're not authorized - ${request.method} ${request.uri}")
            }
          }
          case _                          => error(s"You're not authorized - ${request.method} ${request.uri}")
        }
    }
    host match {
      case env.adminApiHost                     => perform()
      case h if env.adminApiDomains.contains(h) => perform()
      case _                                    => error(s"Not found")
    }
  }

  override protected def executionContext: ExecutionContext = ec
}

case class UnAuthApiActionContent[A](req: Request[A])

class UnAuthApiAction(val parser: BodyParser[AnyContent])(using env: Env)
    extends ActionBuilder[UnAuthApiActionContent, AnyContent]
    with ActionFunction[Request, UnAuthApiActionContent] {

  implicit lazy val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-api-action")

  def error(message: String, ex: Option[Throwable] = None)(using request: Request[?]): Future[Result] = {
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

  override def invokeBlock[A](
      request: Request[A],
      block: UnAuthApiActionContent[A] => Future[Result]
  ): Future[Result] = {

    implicit val req = request

    val host = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
    host match {
      case env.adminApiHost                     => block(UnAuthApiActionContent(request))
      case h if env.adminApiDomains.contains(h) => block(UnAuthApiActionContent(request))
      case _                                    => error(s"Not found")
    }
  }

  override protected def executionContext: ExecutionContext = ec
}
