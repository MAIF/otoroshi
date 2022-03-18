package otoroshi.script

import akka.http.scaladsl.util.FastFuture
import otoroshi.env.Env
import otoroshi.gateway.{Errors, GwError}
import otoroshi.models._
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.utils.TypedMap
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class AccessValidatorRef(
    enabled: Boolean = false,
    excludedPatterns: Seq[String] = Seq.empty[String],
    refs: Seq[String] = Seq.empty,
    config: JsValue = Json.obj()
) {
  def json: JsValue = AccessValidatorRef.format.writes(this)
}

object AccessValidatorRef {
  val format = new Format[AccessValidatorRef] {
    override def writes(o: AccessValidatorRef): JsValue             =
      Json.obj(
        "enabled"          -> o.enabled,
        "refs"             -> JsArray(o.refs.map(JsString.apply)),
        "config"           -> o.config,
        "excludedPatterns" -> JsArray(o.excludedPatterns.map(JsString.apply))
      )
    override def reads(json: JsValue): JsResult[AccessValidatorRef] =
      Try {
        JsSuccess(
          AccessValidatorRef(
            refs = (json \ "refs")
              .asOpt[Seq[String]]
              .orElse((json \ "ref").asOpt[String].map(r => Seq(r)))
              .getOrElse(Seq.empty),
            enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
            config = (json \ "config").asOpt[JsValue].getOrElse(Json.obj()),
            excludedPatterns = (json \ "excludedPatterns").asOpt[Seq[String]].getOrElse(Seq.empty[String])
          )
        )
      } recover { case e =>
        JsError(e.getMessage)
      } get
  }
}

sealed trait Access
case object Allowed               extends Access
case class Denied(result: Result) extends Access

trait AccessValidator extends StartableAndStoppable with NamedPlugin with InternalEventListener {
  def pluginType: PluginType                                                                      = PluginType.AccessValidatorType
  def access(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Access] = {
    canAccess(context)(env, ec).flatMap {
      case true  => FastFuture.successful(Allowed)
      case false =>
        Errors
          .craftResponseResult(
            "bad request",
            Results.BadRequest,
            context.request,
            None,
            None,
            attrs = context.attrs
          )
          .map(Denied.apply)
    }
  }
  def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] =
    FastFuture.successful(true)
}

case class AccessContext(
    snowflake: String,
    index: Int,
    request: RequestHeader,
    descriptor: ServiceDescriptor,
    user: Option[PrivateAppsUser],
    apikey: Option[ApiKey],
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue
) extends ContextWithConfig

object DefaultValidator extends AccessValidator {

  def visibility: NgPluginVisibility = NgPluginVisibility.NgInternal
  def categories: Seq[NgPluginCategory] = Seq.empty
  def steps: Seq[NgStep] = Seq.empty

  override def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    FastFuture.successful(true)
  }
}

object CompilingValidator extends AccessValidator {

  def visibility: NgPluginVisibility = NgPluginVisibility.NgInternal
  def categories: Seq[NgPluginCategory] = Seq.empty
  def steps: Seq[NgStep] = Seq.empty

  override def canAccess(context: AccessContext)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    context.attrs.put(otoroshi.plugins.Keys.GwErrorKey -> GwError("not ready yet, plugin is loading ..."))
    FastFuture.successful(false)
  }
}
