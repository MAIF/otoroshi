package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent._
import scala.concurrent.duration._
import scala.util._

case class OpenFGAValidatorConfig(
  url: String = "http://localhost:8088",
  token: Option[String] = None,
  tlsConfig: NgTlsConfig = NgTlsConfig(),
  timeout: FiniteDuration = 10.seconds,
  storeId: String = "--",
  modelId: String = "--",
  tupleKey: JsObject = Json.obj(),
  contextualTuples: JsArray = Json.arr()
) extends NgPluginConfig {
  def json: JsValue = OpenFGAValidatorConfig.format.writes(this)
}

object OpenFGAValidatorConfig {

  def configFlow: Seq[String] = Seq(
    "url",
    "timeout",
    "token",
    "tls_config",
    "store_id",
    "model_id",
    "tuple_key",
    "contextual_tuples"
  )

  def configSchema: JsObject = Json.obj(
    "url" -> Json.obj("type" -> "string", "label" -> "OpenFGA URL", "default" -> "http://localhost:8088"),
    "token" -> Json.obj("type" -> "string", "label" -> "OpenFGA token", "default" -> ""),
    "tls_config" -> Json.obj("type" -> "json", "label" -> "TLS configuration", "default" -> "{}"),
    "timeout" -> Json.obj("type" -> "number", "label" -> "Timeout", "default" -> 10),
    "store_id" -> Json.obj("type" -> "string", "label" -> "Store ID", "default" -> "--"),
    "model_id" -> Json.obj("type" -> "string", "label" -> "Model ID", "default" -> "--"),
    "tuple_key" -> Json.obj("type" -> "json", "label" -> "Authorization tuple", "default" -> "{}"),
    "contextual_tuples" -> Json.obj("type" -> "json", "label" -> "Contextual tuples", "default" -> "[]"),
  )

  def default: OpenFGAValidatorConfig = OpenFGAValidatorConfig()

  val format = new Format[OpenFGAValidatorConfig] {

    override def reads(json: JsValue): JsResult[OpenFGAValidatorConfig] = Try {
      OpenFGAValidatorConfig(
        url = json.select("url").asString,
        token = json.select("token").asOptString,
        tlsConfig = json.select("tls_config").asOpt[JsObject].flatMap(o => NgTlsConfig.format.reads(o).asOpt).getOrElse(NgTlsConfig()),
        timeout = json.select("timeout").asOptLong.map(_.millis).getOrElse(10.seconds),
        storeId = json.select("store_id").asOptString.getOrElse("--"),
        modelId = json.select("model_id").asOptString.getOrElse("--"),
        tupleKey = json.select("tuple_key").asOpt[JsObject].getOrElse(Json.obj()),
        contextualTuples = json.select("contextual_tuples").asOpt[JsArray].getOrElse(Json.arr())
      )
    } match {
      case Success(config)    => JsSuccess(config)
      case Failure(exception) => JsError(exception.getMessage)
    }

    override def writes(o: OpenFGAValidatorConfig): JsValue = Json.obj(
      "url" -> o.url,
      "token" -> o.token.map(_.json).getOrElse(JsNull).asValue,
      "tls_config" -> o.tlsConfig.json,
      "timeout" -> o.timeout.toMillis,
      "store_id" -> o.storeId,
      "model_id" -> o.modelId,
      "tuple_key" -> o.tupleKey,
      "contextual_tuples" -> o.contextualTuples,
    )
  }
}

class OpenFGAValidator extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Security)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true

  override def noJsForm: Boolean                           = true
  override def defaultConfigObject: Option[NgPluginConfig] = OpenFGAValidatorConfig.default.some
  override def configFlow: Seq[String]                     = OpenFGAValidatorConfig.configFlow
  override def configSchema: Option[JsObject]              = OpenFGAValidatorConfig.configSchema.some

  override def name: String                = "OpenFGA validator"
  override def description: Option[String] =
    Some(
      "Enforces fine-grained authorizations using OpenFGA"
    )

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val conf = ctx
      .cachedConfig(internalName)(OpenFGAValidatorConfig.format)
      .getOrElse(OpenFGAValidatorConfig.default)
      .applyOn { config =>
        val json = config.json.stringify.evaluateEl(ctx.attrs).parseJson
        OpenFGAValidatorConfig.format.reads(json).get
      }
    env.MtlsWs
      .url(s"${conf.url}/stores/${conf.storeId}/check", conf.tlsConfig.legacy)
      .withRequestTimeout(conf.timeout)
      .withHttpHeaders(
        "content-type" -> "application/json",
        "accept" -> "application/json",
      )
      .applyOnWithOpt(conf.token) {
        case (builder, token) => builder.addHttpHeaders("Authorization" -> s"Bearer ${token}")
      }
      .post(Json.obj(
        "authorization_model_id" -> conf.modelId,
        "tuple_key" -> conf.tupleKey,
      ).applyOnIf(conf.contextualTuples.value.nonEmpty)(_ ++
        Json.obj("contextual_tuples" -> Json.obj("tuple_keys" -> conf.contextualTuples)))
      )
      .map { resp =>
        if (resp.status == 200) {
          resp.json.select("allowed").asOptBoolean.getOrElse(false) match {
            case true => NgAccess.NgAllowed
            case false => NgAccess.NgDenied(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
          }
        } else {
          NgAccess.NgDenied(Results.Unauthorized(Json.obj("error" -> "unauthorized")))
        }
      }
  }
}
