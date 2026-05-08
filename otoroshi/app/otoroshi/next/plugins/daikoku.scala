package otoroshi.next.plugins

import otoroshi.models.{AlgoSettings, HSAlgoSettings, SecComInfoTokenVersion}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, PluginIndex}
import otoroshi.next.plugins.api._
import otoroshi.utils.infotoken.AddFieldsSettings
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue, BetterJsValueReader, BetterSyntax}
import play.api.libs.json._

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

case class ExposeDaikokuPresetConfig(
    authenticationRef: Option[String] = None,
    exclude: Seq[String] = Seq(
      "/admin-api/.*",
      "/cms-api/.*",
      "/health",
      "/integration-api/.*",
      "/robots.txt"
    ),
    headerName: Option[String] = Some("Otoroshi-claim"),
    addFields: Option[AddFieldsSettings] = None,
    algo: AlgoSettings = HSAlgoSettings(512, "secret")
) extends NgPluginConfig {
  def json: JsValue = ExposeDaikokuPresetConfig.format.writes(this)
}

object ExposeDaikokuPresetConfig {
  val default                        = ExposeDaikokuPresetConfig()
  val configFlow                     = Seq.empty
  val configSchema: Option[JsObject] = None
  val format                         = new Format[ExposeDaikokuPresetConfig] {
    override def reads(json: JsValue): JsResult[ExposeDaikokuPresetConfig] = Try {
      ExposeDaikokuPresetConfig(
        authenticationRef = json.selectAsOptString("authentication_ref"),
        exclude = (json \ "exclude").asOpt[Seq[String]].getOrElse(Seq.empty),
        headerName = json.selectAsOptString("header_name").orElse("Otoroshi-claim".some),
        addFields = json.select("add_fields").asOpt[Map[String, String]].map(m => AddFieldsSettings(m)),
        algo = AlgoSettings
          .fromJson(json.select("algo").asOpt[JsObject].getOrElse(Json.obj()))
          .getOrElse(HSAlgoSettings(512, "secret"))
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: ExposeDaikokuPresetConfig): JsValue             = Json.obj(
      "authentication_ref" -> o.authenticationRef,
      "exclude"            -> o.exclude,
      "header_name"        -> o.headerName,
      "add_fields"         -> o.addFields.map(v => JsObject(v.fields.mapValues(JsString.apply))),
      "algo"               -> o.algo.asJson
    )
  }
}

class DaikokuProxyPlugin extends NgPresetPlugin {

  override def name: String                                = "Expose Daikoku through Otoroshi"
  override def description: Option[String]                 = "This plugin exposes Daikoku behind Otoroshi via configured plugins.".some
  override def core: Boolean                               = false
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Custom("Presets"))
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(ExposeDaikokuPresetConfig.default)
  override def noJsForm: Boolean                           = false

  override def expand(ctx: NgPresetPluginContext): Seq[NgPluginInstance] = {
    val config = ExposeDaikokuPresetConfig.format.reads(ctx.config).getOrElse(ExposeDaikokuPresetConfig.default)
    Seq(
      NgPluginInstance(
        plugin = "cp:otoroshi.next.plugins.AuthModule",
        exclude = config.exclude,
        config = NgPluginInstanceConfig(
          NgAuthModuleConfig(config.authenticationRef).json.asObject
        ),
        pluginIndex = Some(
          PluginIndex(
            validateAccess = Some(0.02)
          )
        )
      ),
      NgPluginInstance(
        plugin = "cp:otoroshi.next.plugins.OtoroshiInfos",
        config = NgPluginInstanceConfig(
          NgOtoroshiInfoConfig(
            secComVersion = SecComInfoTokenVersion.Latest,
            secComTtl = 30.seconds,
            headerName = Some("Authorization"),
            addFields = None,
            projection = Json.obj(),
            algo = HSAlgoSettings(512, "secret")
          ).json.asObject
        ),
        pluginIndex = Some(
          PluginIndex(
            validateAccess = Some(0.03)
          )
        )
      ),
      NgPluginInstance(
        plugin = "cp:otoroshi.next.plugins.XForwardedHeaders",
        pluginIndex = Some(
          PluginIndex(
            validateAccess = Some(999.0)
          )
        )
      )
    )
  }
}
