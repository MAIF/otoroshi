package otoroshi.next.plugins

import otoroshi.next.models._
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

case class MandatoryConsumerPresetConfig(ref: Option[String] = None, tags: Seq[String] = Seq.empty)
    extends NgPluginConfig {
  def json: JsValue = MandatoryConsumerPresetConfig.format.writes(this)
}

object MandatoryConsumerPresetConfig {
  val default                        = MandatoryConsumerPresetConfig()
  val configFlow                     = Seq(
    "ref",
    "tags"
  )
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "tags" -> Json.obj(
        "label"  -> "Mandatory tags for apikeys",
        "type"   -> "array",
        "array"  -> true,
        "format" -> JsNull
      ),
      "ref"  -> Json.obj(
        "type"  -> "select",
        "label" -> s"Auth. module",
        "props" -> Json.obj(
          "optionsFrom"        -> "/bo/api/proxy/apis/security.otoroshi.io/v1/auth-modules",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      )
    )
  )
  val format                         = new Format[MandatoryConsumerPresetConfig] {
    override def reads(json: JsValue): JsResult[MandatoryConsumerPresetConfig] = Try {
      MandatoryConsumerPresetConfig(
        ref = json.select("ref").asOpt[String],
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: MandatoryConsumerPresetConfig): JsValue             = Json.obj(
      "ref"  -> o.ref.map(_.json).getOrElse(JsNull).asValue,
      "tags" -> JsArray(o.tags.map(_.json))
    )
  }
}

class MandatoryConsumerPreset extends NgPresetPlugin {

  override def name: String                                = "Mandatory Consumer Preset"
  override def description: Option[String]                 =
    "This plugin tries to authenticate a consumer using apikey, local oauth2 and remote oauth2 (using Authorization header)".some
  override def core: Boolean                               = false
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Custom("Presets"))
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(MandatoryConsumerPresetConfig.default)
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = MandatoryConsumerPresetConfig.configFlow
  override def configSchema: Option[JsObject]              = MandatoryConsumerPresetConfig.configSchema

  override def expand(ctx: NgPresetPluginContext): Seq[NgPluginInstance] = {
    val config = MandatoryConsumerPresetConfig.format.reads(ctx.config).getOrElse(MandatoryConsumerPresetConfig.default)
    Seq(
      NgPluginInstance(
        plugin = "cp:otoroshi.next.plugins.ApikeyCalls",
        config = NgPluginInstanceConfig(
          NgApikeyCallsConfig(
            mandatory = false
          ).json.asObject
        ),
        pluginIndex = Some(
          PluginIndex(
            validateAccess = Some(0.01)
          )
        )
      ),
      NgPluginInstance(
        plugin = "cp:otoroshi.next.plugins.NgApikeyMandatoryTags",
        config = NgPluginInstanceConfig(
          NgApikeyMandatoryTagsConfig(
            tags = config.tags
          ).json.asObject
        ),
        pluginIndex = Some(
          PluginIndex(
            validateAccess = Some(0.02)
          )
        )
      ),
      NgPluginInstance(
        plugin = "cp:otoroshi.next.plugins.OIDCJwtVerifier",
        config = NgPluginInstanceConfig(
          OIDCJwtVerifierConfig(
            mandatory = false,
            ref = config.ref,
            user = true,
            customResponse = true,
            customResponseStatus = 401,
            customResponseHeaders = Map("Content-Type" -> "application/json"),
            customResponseBody = Json.obj("error" -> "unauthorized").stringify
          ).json.asObject
        ),
        pluginIndex = Some(
          PluginIndex(
            validateAccess = Some(0.03)
          )
        )
      ),
      NgPluginInstance(
        plugin = "cp:otoroshi.next.plugins.NgExpectedConsumer",
        pluginIndex = Some(
          PluginIndex(
            validateAccess = Some(999.0)
          )
        )
      )
    )
  }
}
