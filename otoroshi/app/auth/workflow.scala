package otoroshi.auth

import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.BodyHelper
import otoroshi.next.utils.JsonHelpers
import otoroshi.next.workflow.{Node, WorkflowAdminExtension}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{JsonPathValidator, TypedMap}
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object WorkflowAuthModuleConfig {
  val defaultConfig = WorkflowAuthModuleConfig(
    location = EntityLocation.default,
    id = IdGenerator.namedId("auth_mod", IdGenerator.uuid),
    name = "New workflow auth. module",
    description = "New workflow auth. module",
    tags = Seq.empty,
    metadata = Map.empty,
    sessionMaxAge = 86400,
    clientSideSessionEnabled = false,
    sessionCookieValues = SessionCookieValues(true, true),
    userValidators = Seq.empty,
    workflowRef = None
  )
  val format        = new Format[WorkflowAuthModuleConfig] {
    override def writes(o: WorkflowAuthModuleConfig): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "type"                     -> "workflow",
      "id"                       -> o.id,
      "name"                     -> o.name,
      "desc"                     -> o.description,
      "tags"                     -> o.tags,
      "metadata"                 -> o.metadata,
      "sessionMaxAge"            -> o.sessionMaxAge,
      "clientSideSessionEnabled" -> o.clientSideSessionEnabled,
      "sessionCookieValues"      -> SessionCookieValues.fmt.writes(o.sessionCookieValues),
      "userValidators"           -> JsArray(o.userValidators.map(_.json)),
      "remoteValidators"         -> JsArray(o.remoteValidators.map(_.json)),
      "allowedUsers"             -> o.allowedUsers,
      "deniedUsers"              -> o.deniedUsers,
      "workflowRef"              -> o.workflowRef.map(JsString.apply).getOrElse(JsNull).asValue
    )
    override def reads(json: JsValue): JsResult[WorkflowAuthModuleConfig] = Try {
      WorkflowAuthModuleConfig(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].orElse((json \ "desc").asOpt[String]).getOrElse("--"),
        clientSideSessionEnabled = (json \ "clientSideSessionEnabled").asOpt[Boolean].getOrElse(true),
        sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        allowedUsers = json.select("allowedUsers").asOpt[Seq[String]].getOrElse(Seq.empty),
        deniedUsers = json.select("deniedUsers").asOpt[Seq[String]].getOrElse(Seq.empty),
        sessionCookieValues =
          (json \ "sessionCookieValues").asOpt(SessionCookieValues.fmt).getOrElse(SessionCookieValues()),
        userValidators = (json \ "userValidators")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => JsonPathValidator.format.reads(v).asOpt))
          .getOrElse(Seq.empty),
        remoteValidators = (json \ "remoteValidators")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => RemoteUserValidatorSettings.format.reads(v).asOpt))
          .getOrElse(Seq.empty),
        workflowRef = json.select("workflowRef").asOpt[String].filter(_.trim.nonEmpty)
      )
    } match {
      case Failure(e)   => JsError(e.getMessage)
      case Success(mod) => JsSuccess(mod)
    }
  }
}

case class WorkflowAuthModuleConfig(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    sessionMaxAge: Int,
    clientSideSessionEnabled: Boolean,
    sessionCookieValues: SessionCookieValues,
    userValidators: Seq[JsonPathValidator] = Seq.empty,
    remoteValidators: Seq[RemoteUserValidatorSettings] = Seq.empty,
    workflowRef: Option[String],
    allowedUsers: Seq[String] = Seq.empty,
    deniedUsers: Seq[String] = Seq.empty
) extends AuthModuleConfig {

  override def authModule(config: GlobalConfig): AuthModule = new WorkflowAuthModule(this)

  override def form: Option[Form]                                               = None
  override def cookieSuffix(desc: ServiceDescriptor): String                    = s"workflow-auth-$id"
  override def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.authConfigsDataStore.set(this)
  override def withLocation(location: EntityLocation): AuthModuleConfig         = copy(location = location)
  override def asJson: JsValue                                                  = WorkflowAuthModuleConfig.format.writes(this)
  override def _fmt()(implicit env: Env): Format[AuthModuleConfig]              = AuthModuleConfig._fmt(env)
  override def `type`: String                                                   = "workflow"
  override def humanName: String                                                = "Workflow auth. module provider"
  override def desc: String                                                     = description
  override def theName: String                                                  = name
  override def theDescription: String                                           = description
  override def theTags: Seq[String]                                             = tags
  override def theMetadata: Map[String, String]                                 = metadata
}

object WorkflowAuthModule {
  val logger = Logger("otoroshi-workflow-auth-module")
}

class WorkflowAuthModule(val authConfig: WorkflowAuthModuleConfig) extends AuthModule {

  def this() = this(WorkflowAuthModuleConfig.defaultConfig)

  private def runWorkflowPhase(phase: String, input: JsObject)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[String, JsValue]] = {
    authConfig.workflowRef match {
      case None      => Future.successful(Left("workflow ref not configured"))
      case Some(ref) =>
        env.adminExtensions.extension[WorkflowAdminExtension] match {
          case None            => Future.successful(Left("workflow extension not found"))
          case Some(extension) =>
            extension.workflow(ref) match {
              case None           => Future.successful(Left(s"workflow '${ref}' not found"))
              case Some(workflow) =>
                extension.engine
                  .run(
                    ref,
                    Node.from(workflow.config),
                    Json.obj("phase" -> phase, "input" -> input),
                    TypedMap.empty,
                    workflow.functions
                  )
                  .map { result =>
                    if (result.hasError) {
                      Left(result.error.get.json.stringify)
                    } else {
                      Right(result.returned.getOrElse(Json.obj()))
                    }
                  }
            }
        }
    }
  }

  private def resultFromResponse(response: JsValue): Result = {
    val body        = BodyHelper.extractBodyFrom(response)
    val headers     = response
      .select("headers")
      .asOpt[Map[String, String]]
      .getOrElse(Map("Content-Type" -> "text/html"))
    val contentType = headers.getIgnoreCase("Content-Type").getOrElse("text/html")
    Results
      .Status(response.select("status").asOpt[Int].getOrElse(200))
      .apply(body)
      .withHeaders(headers.toSeq: _*)
      .as(contentType)
  }

  override def paLoginPage(
      request: RequestHeader,
      config: GlobalConfig,
      descriptor: ServiceDescriptor,
      isRoute: Boolean
  )(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    val route = NgRoute.fromServiceDescriptor(descriptor, false)
    val input = Json.obj(
      "request"       -> JsonHelpers.requestToJson(request, TypedMap.empty),
      "global_config" -> config.json,
      "service"       -> descriptor.json,
      "route"         -> route.json,
      "is_route"      -> isRoute
    )
    runWorkflowPhase("pa_login_page", input).map {
      case Left(err)       => Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> err))
      case Right(response) => resultFromResponse(response)
    }
  }

  override def paLogout(
      request: RequestHeader,
      user: Option[PrivateAppsUser],
      config: GlobalConfig,
      descriptor: ServiceDescriptor
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Option[String]]] = {
    val route = NgRoute.fromServiceDescriptor(descriptor, false)
    val input = Json.obj(
      "request"       -> JsonHelpers.requestToJson(request, TypedMap.empty),
      "global_config" -> config.json,
      "service"       -> descriptor.json,
      "route"         -> route.json,
      "user"          -> user.map(_.json).getOrElse(JsNull).asValue
    )
    runWorkflowPhase("pa_logout", input).map {
      case Left(err)       =>
        Results
          .InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> err))
          .left
      case Right(response) =>
        val logoutUrl = response.select("logout_url").asOpt[String]
        logoutUrl.right
    }
  }

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[ErrorReason, PrivateAppsUser]] = {
    val route = NgRoute.fromServiceDescriptor(descriptor, false)
    val input = Json.obj(
      "request"       -> JsonHelpers.requestToJson(request, TypedMap.empty),
      "global_config" -> config.json,
      "service"       -> descriptor.json,
      "route"         -> route.json
    )
    runWorkflowPhase("pa_callback", input).flatMap {
      case Left(err)       => ErrorReason(err).left.vfuture
      case Right(response) =>
        PrivateAppsUser.fmt.reads(response) match {
          case JsError(errors)    => ErrorReason(errors.toString()).left.vfuture
          case JsSuccess(user, _) =>
            user.validate(
              descriptor,
              isRoute = true,
              authConfig
            )
        }
    }
  }

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Result] = {
    val input = Json.obj(
      "request"       -> JsonHelpers.requestToJson(request, TypedMap.empty),
      "global_config" -> config.json
    )
    runWorkflowPhase("bo_login_page", input).map {
      case Left(err)       => Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> err))
      case Right(response) => resultFromResponse(response)
    }
  }

  override def boLogout(request: RequestHeader, user: BackOfficeUser, config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[Result, Option[String]]] = {
    val input = Json.obj(
      "request"       -> JsonHelpers.requestToJson(request, TypedMap.empty),
      "global_config" -> config.json,
      "user"          -> user.json
    )
    runWorkflowPhase("bo_logout", input).map {
      case Left(err)       =>
        Results
          .InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> err))
          .left
      case Right(response) =>
        val logoutUrl = response.select("logout_url").asOpt[String]
        logoutUrl.right
    }
  }

  override def boCallback(request: Request[AnyContent], config: GlobalConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[ErrorReason, BackOfficeUser]] = {
    val input = Json.obj(
      "request"       -> JsonHelpers.requestToJson(request, TypedMap.empty),
      "global_config" -> config.json
    )
    runWorkflowPhase("bo_callback", input).flatMap {
      case Left(err)       => ErrorReason(err).left.vfuture
      case Right(response) =>
        BackOfficeUser.fmt.reads(response) match {
          case JsError(errors)    => ErrorReason(errors.toString()).left.vfuture
          case JsSuccess(user, _) =>
            user.validate(
              env.backOfficeServiceDescriptor,
              isRoute = false,
              authConfig
            )
        }
    }
  }
}
