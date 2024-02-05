package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.arakelian.jq.{ImmutableJqLibrary, ImmutableJqRequest}
import com.networknt.schema.SpecVersion.VersionFlag
import com.networknt.schema.{InputFormat, JsonSchemaFactory, PathType, SchemaValidatorsConfig}
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.JsonPathValidator
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.http.websocket.CloseCodes
import play.api.libs.json._
import play.api.mvc.Results

import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.asScalaBufferConverter
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
  override def description: Option[String]                 = "Validate the content of each frame".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Websocket)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)

  override def onResponseFlow: Boolean                     = false
  override def onRequestFlow: Boolean                      = true

  private def validate(ctx: NgWebsocketPluginContext, message: WebsocketMessage)
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

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    validate(ctx, message)
      .flatMap {
        case true => Right(message).vfuture
        case false => Left(NgWebsocketError(CloseCodes.PolicyViolated, "failed to validate message")).vfuture
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

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    implicit val m: Materializer = env.otoroshiMaterializer

    val config = ctx.cachedConfig(internalName)(WebsocketTypeValidatorConfig.format).getOrElse(WebsocketTypeValidatorConfig())

    (config.allowedFormat match {
      case FrameFormat.Binary if !message.isBinary =>  Left(NgWebsocketError(CloseCodes.Unacceptable, "expected binary content")).vfuture
      case FrameFormat.Text if !message.isText => Left(NgWebsocketError(CloseCodes.Unacceptable, "expected text content")).vfuture
      case FrameFormat.Text if message.isText => message.str()
          .flatMap(str => {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(str)) {
              Left(NgWebsocketError(CloseCodes.InconsistentData, "non-UTF-8 data within content")).vfuture
            } else {
              Right(message).vfuture
            }
          })
      case FrameFormat.Json if message.isText => message.str()
          .map(bs => (Try(Json.parse(bs)), bs))
          .flatMap(res => {
            res._1 match {
              case Success(_) if !StandardCharsets.UTF_8.newEncoder().canEncode(res._2) => Left(NgWebsocketError(CloseCodes.InconsistentData, "non-UTF-8 data within content")).vfuture
              case Failure(_) => Left(NgWebsocketError(CloseCodes.Unacceptable, "expected json content")).vfuture
              case _ => Right(message).vfuture
            }
          })
      case _ => Right(message).vfuture
    })
  }

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

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
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
      .flatMap {
        case true => Right(message).vfuture
        case false => Left(NgWebsocketError(CloseCodes.PolicyViolated, "failed to validate message")).vfuture
      }
  }

  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = {
    val config = ctx.cachedConfig(internalName)(WebsocketJsonFormatValidatorConfig.format).getOrElse(WebsocketJsonFormatValidatorConfig())
    config.rejectStrategy
  }
}

case class WebsocketSizeValidatorConfig(
                                         clientMaxPayload: Int = 4096,
                                         upstreamMaxPayload: Int = 4096,
                                         rejectStrategy: RejectStrategy = RejectStrategy.Drop)
 extends NgPluginConfig {
  override def json: JsValue = WebsocketSizeValidatorConfig.format.writes(this)
}

object WebsocketSizeValidatorConfig {
  val default = WebsocketSizeValidatorConfig()
  val format  = new Format[WebsocketSizeValidatorConfig] {
    override def writes(o: WebsocketSizeValidatorConfig): JsValue = Json.obj(
      "client_max_payload" -> o.clientMaxPayload,
      "upstream_max_payload" -> o.upstreamMaxPayload,
      "reject_strategy" -> o.rejectStrategy.json
    )
    override def reads(json: JsValue): JsResult[WebsocketSizeValidatorConfig] = {
      Try {
        WebsocketSizeValidatorConfig(
          clientMaxPayload = json.select("client_max_payload").asOpt[Int].getOrElse(4096),
          upstreamMaxPayload = json.select("upstream_max_payload").asOpt[Int].getOrElse(4096),
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
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess, NgStep.TransformResponse)

  override def onResponseFlow: Boolean                     = true
  override def onRequestFlow: Boolean                      = true

  private def internalCanAccess(ctx: NgWebsocketPluginContext, message: WebsocketMessage, maxSize: Int, reason: String)
                                  (implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    implicit val m: Materializer = env.otoroshiMaterializer

    message.size()
      .map(_ <= maxSize)
      .flatMap {
        case true => Right(message).vfuture
        case false => Left(NgWebsocketError(CloseCodes.TooBig, reason)).vfuture
      }
  }

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    val config = ctx.cachedConfig(internalName)(WebsocketSizeValidatorConfig.format).getOrElse(WebsocketSizeValidatorConfig())

    internalCanAccess(ctx, message, config.clientMaxPayload, "limit exceeded")
  }

  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = {
    val config = ctx.cachedConfig(internalName)(WebsocketSizeValidatorConfig.format).getOrElse(WebsocketSizeValidatorConfig())
    config.rejectStrategy
  }

  override def onResponseMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    val config = ctx.cachedConfig(internalName)(WebsocketSizeValidatorConfig.format).getOrElse(WebsocketSizeValidatorConfig())

    internalCanAccess(ctx, message, config.upstreamMaxPayload, reason = "upstream payload limit exceeded")
  }
}

case class JqWebsocketMessageTransformerConfig(requestFilter: String = ".", responseFilter: String = ".") extends NgPluginConfig {
  override def json: JsValue = JqWebsocketMessageTransformerConfig.format.writes(this)
}
object JqWebsocketMessageTransformerConfig {
  val format = new Format[JqWebsocketMessageTransformerConfig] {
    override def reads(json: JsValue): JsResult[JqWebsocketMessageTransformerConfig] = Try {
      JqWebsocketMessageTransformerConfig(
        requestFilter = json.select("request_filter").asOpt[String].getOrElse("."),
        responseFilter = json.select("response_filter").asOpt[String].getOrElse("."),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
    override def writes(o: JqWebsocketMessageTransformerConfig): JsValue = Json.obj(
      "request_filter" -> o.requestFilter,
      "response_filter" -> o.responseFilter,
    )
  }
}

class JqWebsocketMessageTransformer extends NgWebsocketPlugin {

  private val library = ImmutableJqLibrary.of()
  private val logger  = Logger("otoroshi-plugins-jq-websocket")

  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(JqWebsocketMessageTransformerConfig())
  override def core: Boolean                               = false
  override def name: String                                = "Websocket JQ transformer"
  override def description: Option[String]                 = "Transform messages JSON content using JQ filters".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Websocket)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformResponse)

  override def onRequestFlow: Boolean = true
  override def onResponseFlow: Boolean = true

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    val config = ctx.cachedConfig(internalName)(JqWebsocketMessageTransformerConfig.format).getOrElse(JqWebsocketMessageTransformerConfig())
    onMessage(ctx, message, config.requestFilter)
  }

  override def onResponseMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    val config = ctx.cachedConfig(internalName)(JqWebsocketMessageTransformerConfig.format).getOrElse(JqWebsocketMessageTransformerConfig())
    onMessage(ctx, message, config.responseFilter)
  }

  def onMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage, filter: String)(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    implicit val mat = env.otoroshiMaterializer
    if (message.isText) {
      message.str().flatMap { bodyStr =>
        Try(Json.parse(bodyStr)) match {
          case Failure(e) => Right(message).vfuture
          case Success(_) => {
            val request  = ImmutableJqRequest
              .builder()
              .lib(library)
              .input(bodyStr)
              .putArgJson("context", ctx.json.stringify)
              .filter(filter)
              .build()
            val response = request.execute()
            if (response.hasErrors) {
              logger.error(
                s"error while transforming response body:\n${response.getErrors.asScala.mkString("\n")}"
              )
              val errors = JsArray(response.getErrors.asScala.map(err => JsString(err)))
              Right(WebsocketMessage.PlayMessage(play.api.http.websocket.TextMessage(errors.stringify))).vfuture
            } else {
              val rawBody = response.getOutput
              Right(WebsocketMessage.PlayMessage(play.api.http.websocket.TextMessage(rawBody))).vfuture
            }
          }
        }
      }
    } else {
      Right(message).vfuture
    }
  }
}
