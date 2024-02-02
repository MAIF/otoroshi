package otoroshi.next.plugins

import akka.stream.Materializer
import com.networknt.schema.SpecVersion.VersionFlag
import com.networknt.schema.{InputFormat, JsonSchemaFactory, PathType, SchemaValidatorsConfig}
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.JsonPathValidator
import otoroshi.utils.syntax.implicits._
import play.api.http.websocket.CloseCodes
import play.api.libs.json._
import play.api.mvc.Results

import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

sealed trait RejectStrategy {
  def json: JsValue
}
object RejectStrategy       {
  case object Drop       extends RejectStrategy { def json: JsValue = JsString("drop")       }
  case object Close      extends RejectStrategy { def json: JsValue = JsString("close") }
  def parse(value: String): RejectStrategy = value.toLowerCase() match {
    case "drop"   => Drop
    case "close"  => Close
    case _        => Drop
  }

  def read(json: JsValue): RejectStrategy = json.select("reject_strategy")
      .asOpt[String]
      .map(RejectStrategy.parse)
      .getOrElse(RejectStrategy.Drop)
}

sealed trait FrameFormat {
  def json: JsValue
}
object FrameFormat       {
  case object All       extends FrameFormat { def json: JsValue = JsString("all")       }
  case object Binary    extends FrameFormat { def json: JsValue = JsString("binary") }
  case object Text      extends FrameFormat { def json: JsValue = JsString("text") }
  case object Json      extends FrameFormat { def json: JsValue = JsString("json") }
  def parse(value: String): FrameFormat = value.toLowerCase() match {
    case "all"    => All
    case "binary" => Binary
    case "text"   => Text
    case "json"   => Json
    case _        => All
  }
}

case class WebsocketTypeValidatorConfig(
                                         allowedFormat: FrameFormat = FrameFormat.All,
                                         rejectStrategy: RejectStrategy = RejectStrategy.Drop)
 extends NgPluginConfig {
  override def json: JsValue = WebsocketTypeValidatorConfig.format.writes(this)
}

object WebsocketTypeValidatorConfig {
  val default = WebsocketTypeValidatorConfig()
  val format  = new Format[WebsocketTypeValidatorConfig] {
    override def writes(o: WebsocketTypeValidatorConfig): JsValue = Json.obj(
      "allowed_format" -> o.allowedFormat.json,
      "reject_strategy" -> o.rejectStrategy.json
    )
    override def reads(json: JsValue): JsResult[WebsocketTypeValidatorConfig] = {
      Try {
        WebsocketTypeValidatorConfig(
          allowedFormat = json.select("allowed_format")
            .asOpt[String]
            .map(FrameFormat.parse)
            .getOrElse(FrameFormat.All),
          rejectStrategy = RejectStrategy.read(json)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(s) => JsSuccess(s)
      }
    }
  }
}

case class FrameFormatValidatorConfig(
    validator: Option[JsonPathValidator] = None,
    rejectStrategy: RejectStrategy = RejectStrategy.Drop
) extends NgPluginConfig {
  override def json: JsValue = FrameFormatValidatorConfig.format.writes(this)
}

object FrameFormatValidatorConfig {
  val default = FrameFormatValidatorConfig(validator = Some(JsonPathValidator("$.message", JsString("foo"), None)))
  val format  = new Format[FrameFormatValidatorConfig] {
    override def writes(o: FrameFormatValidatorConfig): JsValue = Json.obj(
      "validator" -> o.validator.map(_.json),
      "reject_strategy" -> o.rejectStrategy.json
    )
    override def reads(json: JsValue): JsResult[FrameFormatValidatorConfig] = {
      Try {
        FrameFormatValidatorConfig(
          validator = (json \ "validator").asOpt[JsValue]
            .flatMap(v => JsonPathValidator.format.reads(v).asOpt),
          rejectStrategy = RejectStrategy.read(json)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(s) => JsSuccess(s)
      }
    }
  }
}

class WebsocketContentValidatorIn extends NgWebsocketValidatorPlugin {

  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(FrameFormatValidatorConfig.default)
  override def core: Boolean                               = false
  override def name: String                                = "Websocket content validator in"
  override def description: Option[String]                 = "Validate the format of each frames".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Websocket)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)

  override def onResponseFlow: Boolean                     = false
  override def onRequestFlow: Boolean                      = true

  private def validate[A](ctx: NgWebsocketPluginContext, message: WebsocketMessage[A])
                         (implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    implicit val m: Materializer = env.otoroshiMaterializer
    val config = ctx.cachedConfig(internalName)(FrameFormatValidatorConfig.format).getOrElse(FrameFormatValidatorConfig())

    message.str
      .map(message => {
          val json = ctx.json.asObject ++ Json.obj(
            "route" -> ctx.route.json,
              "message" -> message
          )
          config.validator.forall(validator => validator.validate(json))
        })
  }

  override def access[A](ctx: NgWebsocketPluginContext, message: WebsocketMessage[A])
                            (implicit env: Env, ec: ExecutionContext): Future[NgWebsocketResponse] = {
    validate(ctx, message)
      .map {
        case true => NgWebsocketResponse()
        case false => NgWebsocketResponse.denied(Errors
        .craftResponseResultSync(
          "forbidden",
          Results.Forbidden,
          ctx.request,
          None,
          None,
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        ), CloseCodes.PolicyViolated, "failed to validate message")
    }
  }

  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = {
    val config = ctx.cachedConfig(internalName)(FrameFormatValidatorConfig.format).getOrElse(FrameFormatValidatorConfig())
    config.rejectStrategy
  }
}


class WebsocketTypeValidator extends NgWebsocketValidatorPlugin {

  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(WebsocketTypeValidatorConfig.default)
  override def core: Boolean                               = false
  override def name: String                                = "Websocket type validator"
  override def description: Option[String]                 = "Validate the type of each frame".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Websocket)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)

  override def onResponseFlow: Boolean                     = false
  override def onRequestFlow: Boolean                      = true

  override def access[A](ctx: NgWebsocketPluginContext, message: WebsocketMessage[A])(implicit env: Env, ec: ExecutionContext): Future[NgWebsocketResponse] = {
    implicit val m: Materializer = env.otoroshiMaterializer

    val config = ctx.cachedConfig(internalName)(WebsocketTypeValidatorConfig.format).getOrElse(WebsocketTypeValidatorConfig())

    (config.allowedFormat match {
      case FrameFormat.Binary if !message.isBinary => NgWebsocketResponse.fdenied(getResultError(ctx), CloseCodes.Unacceptable, "expected binary content")
      case FrameFormat.Text if !message.isText => NgWebsocketResponse.fdenied(getResultError(ctx), CloseCodes.Unacceptable, "expected text content")
      case FrameFormat.Text if message.isText => message.str()
          .map(str => {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(str)) {
              NgWebsocketResponse.denied(getResultError(ctx), CloseCodes.InconsistentData, "non-UTF-8 data within content")
            } else {
              NgWebsocketResponse()
            }
          })
      case FrameFormat.Json if message.isText => message.str()
          .map(bs => (Try(Json.parse(bs)), bs))
          .map(res => {
            res._1 match {
              case Success(_) if !StandardCharsets.UTF_8.newEncoder().canEncode(res._2) =>  NgWebsocketResponse.denied(getResultError(ctx), CloseCodes.InconsistentData, "non-UTF-8 data within content")
              case Failure(_) =>  NgWebsocketResponse.denied(getResultError(ctx), CloseCodes.Unacceptable, "expected json content")
              case _ => NgWebsocketResponse()
            }
          })
      case _ => NgWebsocketResponse.default
    })
  }

  private def getResultError(ctx: NgWebsocketPluginContext)(implicit env: Env, ec: ExecutionContext) = Errors
      .craftResponseResultSync(
        "forbidden",
        Results.Forbidden,
        ctx.request,
        None,
        None,
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )

  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = {
    val config = ctx.cachedConfig(internalName)(WebsocketTypeValidatorConfig.format).getOrElse(WebsocketTypeValidatorConfig())
    config.rejectStrategy
  }
}


case class WebsocketJsonFormatValidatorConfig(
                                               schema: Option[String] = None,
                                               specification: String = VersionFlag.V202012.getId,
                                               rejectStrategy: RejectStrategy = RejectStrategy.Drop
                                             )
 extends NgPluginConfig {
  override def json: JsValue = WebsocketJsonFormatValidatorConfig.format.writes(this)
}

object WebsocketJsonFormatValidatorConfig {
  val default = WebsocketJsonFormatValidatorConfig(schema = "{ \"type\": \"object\", \"required\": [\"name\"] }".some)
  val format  = new Format[WebsocketJsonFormatValidatorConfig] {
    override def writes(o: WebsocketJsonFormatValidatorConfig): JsValue = Json.obj(
      "schema" -> o.schema,
      "specification" -> o.specification,
      "reject_strategy" -> o.rejectStrategy.json
    )
    override def reads(json: JsValue): JsResult[WebsocketJsonFormatValidatorConfig] = {
      Try {
        WebsocketJsonFormatValidatorConfig(
          schema = json.select("schema").asOpt[String],
          specification = json.select("specification").asOpt[String].getOrElse(VersionFlag.V202012.getId),
          rejectStrategy = RejectStrategy.read(json)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(s) => JsSuccess(s)
      }
    }
  }
}

class WebsocketJsonFormatValidator extends NgWebsocketValidatorPlugin {

  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(WebsocketJsonFormatValidatorConfig.default)
  override def core: Boolean                               = false
  override def name: String                                = "Websocket json format validator"
  override def description: Option[String]                 = "Validate the json".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Websocket)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)

  override def onResponseFlow: Boolean                     = false
  override def onRequestFlow: Boolean                      = true

  override def access[A](ctx: NgWebsocketPluginContext, message: WebsocketMessage[A])(implicit env: Env, ec: ExecutionContext): Future[NgWebsocketResponse] = {
    implicit val m: Materializer = env.otoroshiMaterializer

    val config = ctx.cachedConfig(internalName)(WebsocketJsonFormatValidatorConfig.format).getOrElse(WebsocketJsonFormatValidatorConfig())

    message.str()
      .map(data => {
        val userSchema = config.schema.getOrElse("")

        val jsonSchemaFactory = JsonSchemaFactory.getInstance(VersionFlag.fromId(config.specification).get())

        val schemaConfig = new SchemaValidatorsConfig()
        schemaConfig.setPathType(PathType.JSON_POINTER)
        schemaConfig.setFormatAssertionsEnabled(true)

        val schema = jsonSchemaFactory.getSchema(userSchema, schemaConfig)
        schema.validate(data, InputFormat.JSON).isEmpty
      })
      .map {
        case true => NgWebsocketResponse()
        case false =>
          val result = Errors
            .craftResponseResultSync(
              "forbidden",
              Results.Forbidden,
              ctx.request,
              None,
              None,
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )

          NgWebsocketResponse.denied(result, CloseCodes.PolicyViolated, "failed to validate message")
      }
  }

  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = {
    val config = ctx.cachedConfig(internalName)(WebsocketJsonFormatValidatorConfig.format).getOrElse(WebsocketJsonFormatValidatorConfig())
    config.rejectStrategy
  }
}

case class WebsocketSizeValidatorConfig(clientMaxPayload: Int = 4096, rejectStrategy: RejectStrategy = RejectStrategy.Drop)
 extends NgPluginConfig {
  override def json: JsValue = WebsocketSizeValidatorConfig.format.writes(this)
}

object WebsocketSizeValidatorConfig {
  val default = WebsocketSizeValidatorConfig()
  val format  = new Format[WebsocketSizeValidatorConfig] {
    override def writes(o: WebsocketSizeValidatorConfig): JsValue = Json.obj(
      "client_max_payload" -> o.clientMaxPayload,
      "reject_strategy" -> o.rejectStrategy.json
    )
    override def reads(json: JsValue): JsResult[WebsocketSizeValidatorConfig] = {
      Try {
        WebsocketSizeValidatorConfig(
          clientMaxPayload = json.select("client_max_payload").asOpt[Int].getOrElse(4096),
          rejectStrategy = RejectStrategy.read(json)
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(s) => JsSuccess(s)
      }
    }
  }
}

class WebsocketSizeValidator extends NgWebsocketValidatorPlugin {

  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(WebsocketSizeValidatorConfig.default)
  override def core: Boolean                               = false
  override def name: String                                = "Websocket size validator"
  override def description: Option[String]                 = "Make sure the frame does not exceed the maximum size set.".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Websocket)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)

  override def onResponseFlow: Boolean                     = false
  override def onRequestFlow: Boolean                      = true

  override def access[A](ctx: NgWebsocketPluginContext, message: WebsocketMessage[A])(implicit env: Env, ec: ExecutionContext): Future[NgWebsocketResponse] = {
    implicit val m: Materializer = env.otoroshiMaterializer

    val config = ctx.cachedConfig(internalName)(WebsocketSizeValidatorConfig.format).getOrElse(WebsocketSizeValidatorConfig())

    message.size()
      .map(_ <= config.clientMaxPayload)
      .map {
        case true => NgWebsocketResponse()
        case false =>
          val result = Errors
            .craftResponseResultSync(
              "forbidden",
              Results.Forbidden,
              ctx.request,
              None,
              None,
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )

          NgWebsocketResponse.denied(result, CloseCodes.TooBig, "limit exceeded")
      }
  }

  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = {
    val config = ctx.cachedConfig(internalName)(WebsocketSizeValidatorConfig.format).getOrElse(WebsocketSizeValidatorConfig())
    config.rejectStrategy
  }
}
