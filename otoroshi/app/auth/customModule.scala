package otoroshi.auth
import otoroshi.env.Env
import otoroshi.models.{BackOfficeUser, EntityLocation, GlobalConfig, PrivateAppsUser, ServiceDescriptor}
import otoroshi.security.IdGenerator
import otoroshi.utils.JsonPathValidator
import play.api.libs.json.{Format, JsArray, JsError, JsResult, JsString, JsSuccess, JsValue, Json}
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class CustomModuleConfig(
                               id: String,
                               name: String,
                               desc: String,
                               clientSideSessionEnabled: Boolean,
                               sessionMaxAge: Int = 86400,
                               userValidators: Seq[JsonPathValidator] = Seq.empty,
                               tags: Seq[String],
                               metadata: Map[String, String],
                               sessionCookieValues: SessionCookieValues,
                               location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
                             ) extends AuthModuleConfig {
  def `type`: String = "custom"
  def humanName: String = "Custom Authentication"

  override def authModule(config: GlobalConfig): AuthModule = CustomAuthModule(this)
  override def withLocation(location: EntityLocation): AuthModuleConfig = copy(location = location)
  override def _fmt()(implicit env: Env): Format[AuthModuleConfig] = new Format[CustomModuleConfig] {
    override def writes(o: CustomModuleConfig): JsValue = o.asJson

    override def reads(json: JsValue): JsResult[CustomModuleConfig] = Try {
      CustomModuleConfig(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        desc = (json \ "desc").asOpt[String].getOrElse("--"),
        clientSideSessionEnabled = (json \ "clientSideSessionEnabled").asOpt[Boolean].getOrElse(true),
        sessionMaxAge = (json \ "sessionMaxAge").asOpt[Int].getOrElse(86400),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        sessionCookieValues =
          (json \ "sessionCookieValues").asOpt(SessionCookieValues.fmt).getOrElse(SessionCookieValues()),
        userValidators = (json \ "userValidators")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => JsonPathValidator.format.reads(v).asOpt))
          .getOrElse(Seq.empty)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }.asInstanceOf[Format[AuthModuleConfig]]

  override def asJson =
    location.jsonWithKey ++ Json.obj(
      "type" -> "custom",
      "id" -> this.id,
      "name" -> this.name,
      "desc" -> this.desc,
      "clientSideSessionEnabled" -> this.clientSideSessionEnabled,
      "sessionMaxAge" -> this.sessionMaxAge,
      "metadata" -> this.metadata,
      "tags" -> JsArray(tags.map(JsString.apply)),
      "sessionCookieValues" -> SessionCookieValues.fmt.writes(this.sessionCookieValues),
      "userValidators" -> JsArray(userValidators.map(_.json))
    )

  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = env.datastores.authConfigsDataStore.set(this)

  override def cookieSuffix(desc: ServiceDescriptor) = s"custom-auth-$id"

  def theDescription: String = desc
  def theMetadata: Map[String, String] = metadata
  def theName: String = name
  def theTags: Seq[String] = tags
}

object CustomAuthModule {
  def defaultConfig = CustomModuleConfig(
    id = IdGenerator.namedId("auth_mod", IdGenerator.uuid),
    name = "My custom auth. module",
    desc = "My custom auth. module",
    tags = Seq.empty,
    metadata = Map.empty,
    sessionCookieValues = SessionCookieValues(),
    clientSideSessionEnabled = true
  )
}

case class CustomAuthModule(authConfig: CustomModuleConfig) extends AuthModule {
  def this() = this(CustomAuthModule.defaultConfig)

  override def paLoginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result] = ???

  override def paLogout(request: RequestHeader, user: Option[PrivateAppsUser], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Option[String]]] = ???

  override def paCallback(request: Request[AnyContent], config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Either[String, PrivateAppsUser]] = ???

  override def boLoginPage(request: RequestHeader, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Result] = ???

  override def boLogout(request: RequestHeader, user: BackOfficeUser, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Option[String]]] = ???

  override def boCallback(request: Request[AnyContent], config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Either[String, BackOfficeUser]] = ???
}
