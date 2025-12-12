package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import com.arakelian.jq.{ImmutableJqLibrary, ImmutableJqRequest}
import com.networknt.schema.SpecVersion.VersionFlag
import com.networknt.schema.{InputFormat, JsonSchemaFactory, PathType, SchemaValidatorsConfig}
import io.otoroshi.wasm4s.scaladsl.WasmFunctionParameters
import otoroshi.env.Env
import otoroshi.gateway.WebSocketProxyActor
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.next.workflow.{Node, WorkflowAdminExtension}
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{JsonPathValidator, UrlSanitizer}
import otoroshi.wasm.WasmConfig
import play.api.Logger
import play.api.http.websocket.{CloseCodes, Message}
import play.api.libs.json._
import play.api.libs.streams.ActorFlow
import reactor.core.publisher.Sinks

import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.asScalaBufferConverter
import scala.util._

sealed trait RejectStrategy {
  def json: JsValue
}
object RejectStrategy       {
  case object Drop  extends RejectStrategy { def json: JsValue = JsString("drop")  }
  case object Close extends RejectStrategy { def json: JsValue = JsString("close") }
  def parse(value: String): RejectStrategy = value.toLowerCase() match {
    case "drop"  => Drop
    case "close" => Close
    case _       => Drop
  }

  def read(json: JsValue): RejectStrategy = json
    .select("reject_strategy")
    .asOpt[String]
    .map(RejectStrategy.parse)
    .getOrElse(RejectStrategy.Drop)
}

sealed trait FrameFormat {
  def json: JsValue
}
object FrameFormat       {
  case object All    extends FrameFormat { def json: JsValue = JsString("all")    }
  case object Binary extends FrameFormat { def json: JsValue = JsString("binary") }
  case object Text   extends FrameFormat { def json: JsValue = JsString("text")   }
  case object Json   extends FrameFormat { def json: JsValue = JsString("json")   }
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
    rejectStrategy: RejectStrategy = RejectStrategy.Drop
) extends NgPluginConfig {
  override def json: JsValue = WebsocketTypeValidatorConfig.format.writes(this)
}

object WebsocketTypeValidatorConfig {
  val default = WebsocketTypeValidatorConfig()
  val format  = new Format[WebsocketTypeValidatorConfig] {
    override def writes(o: WebsocketTypeValidatorConfig): JsValue = Json.obj(
      "allowed_format"  -> o.allowedFormat.json,
      "reject_strategy" -> o.rejectStrategy.json
    )
    override def reads(json: JsValue): JsResult[WebsocketTypeValidatorConfig] = {
      Try {
        WebsocketTypeValidatorConfig(
          allowedFormat = json
            .select("allowed_format")
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
      "validator"       -> o.validator.map(_.json),
      "reject_strategy" -> o.rejectStrategy.json
    )
    override def reads(json: JsValue): JsResult[FrameFormatValidatorConfig] = {
      Try {
        FrameFormatValidatorConfig(
          validator = (json \ "validator")
            .asOpt[JsValue]
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

  override def onResponseFlow: Boolean = false
  override def onRequestFlow: Boolean  = true

  private def validate(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Boolean] = {
    implicit val m: Materializer = env.otoroshiMaterializer
    val config                   =
      ctx.cachedConfig(internalName)(FrameFormatValidatorConfig.format).getOrElse(FrameFormatValidatorConfig())

    message.str
      .map(message => {
        val json = ctx.json.asObject ++ Json.obj(
          "route"   -> ctx.route.json,
          "message" -> message
        )
        config.validator.forall(validator => validator.validate(json))
      })
  }

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    validate(ctx, message)
      .flatMap {
        case true  => Right(message).vfuture
        case false => Left(NgWebsocketError(CloseCodes.PolicyViolated, "failed to validate message")).vfuture
      }
  }

  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = {
    val config =
      ctx.cachedConfig(internalName)(FrameFormatValidatorConfig.format).getOrElse(FrameFormatValidatorConfig())
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

  override def onResponseFlow: Boolean = false
  override def onRequestFlow: Boolean  = true

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    implicit val m: Materializer = env.otoroshiMaterializer

    val config =
      ctx.cachedConfig(internalName)(WebsocketTypeValidatorConfig.format).getOrElse(WebsocketTypeValidatorConfig())

    (config.allowedFormat match {
      case FrameFormat.Binary if !message.isBinary =>
        Left(NgWebsocketError(CloseCodes.Unacceptable, "expected binary content")).vfuture
      case FrameFormat.Text if !message.isText     =>
        Left(NgWebsocketError(CloseCodes.Unacceptable, "expected text content")).vfuture
      case FrameFormat.Text if message.isText      =>
        message
          .str()
          .flatMap(str => {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(str)) {
              Left(NgWebsocketError(CloseCodes.InconsistentData, "non-UTF-8 data within content")).vfuture
            } else {
              Right(message).vfuture
            }
          })
      case FrameFormat.Json if message.isText      =>
        message
          .str()
          .map(bs => (Try(Json.parse(bs)), bs))
          .flatMap(res => {
            res._1 match {
              case Success(_) if !StandardCharsets.UTF_8.newEncoder().canEncode(res._2) =>
                Left(NgWebsocketError(CloseCodes.InconsistentData, "non-UTF-8 data within content")).vfuture
              case Failure(_)                                                           => Left(NgWebsocketError(CloseCodes.Unacceptable, "expected json content")).vfuture
              case _                                                                    => Right(message).vfuture
            }
          })
      case _                                       => Right(message).vfuture
    })
  }

  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = {
    val config =
      ctx.cachedConfig(internalName)(WebsocketTypeValidatorConfig.format).getOrElse(WebsocketTypeValidatorConfig())
    config.rejectStrategy
  }
}

case class WebsocketJsonFormatValidatorConfig(
    schema: Option[String] = None,
    specification: String = VersionFlag.V202012.getId,
    rejectStrategy: RejectStrategy = RejectStrategy.Drop
) extends NgPluginConfig {
  override def json: JsValue = WebsocketJsonFormatValidatorConfig.format.writes(this)
}

object WebsocketJsonFormatValidatorConfig {
  val default = WebsocketJsonFormatValidatorConfig(schema = "{ \"type\": \"object\", \"required\": [\"name\"] }".some)
  val format  = new Format[WebsocketJsonFormatValidatorConfig] {
    override def writes(o: WebsocketJsonFormatValidatorConfig): JsValue = Json.obj(
      "schema"          -> o.schema,
      "specification"   -> o.specification,
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

  override def onResponseFlow: Boolean = false
  override def onRequestFlow: Boolean  = true

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    implicit val m: Materializer = env.otoroshiMaterializer

    val config = ctx
      .cachedConfig(internalName)(WebsocketJsonFormatValidatorConfig.format)
      .getOrElse(WebsocketJsonFormatValidatorConfig())

    message
      .str()
      .map(data => {
        val userSchema = config.schema.getOrElse("")

        val jsonSchemaFactory = JsonSchemaFactory.getInstance(VersionFlag.fromId(config.specification).get())

        val schemaConfig = new SchemaValidatorsConfig()
        schemaConfig.setPathType(PathType.JSON_POINTER)
        schemaConfig.setFormatAssertionsEnabled(true)

        val schema = jsonSchemaFactory.getSchema(userSchema, schemaConfig)

        Try {
          schema.validate(data, InputFormat.JSON).isEmpty
        } recover { case _ =>
          false
        } get
      })
      .flatMap {
        case true  => Right(message).vfuture
        case false => Left(NgWebsocketError(CloseCodes.PolicyViolated, "failed to validate message")).vfuture
      }
  }

  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = {
    val config = ctx
      .cachedConfig(internalName)(WebsocketJsonFormatValidatorConfig.format)
      .getOrElse(WebsocketJsonFormatValidatorConfig())
    config.rejectStrategy
  }
}

case class WebsocketSizeValidatorConfig(
    clientMaxPayload: Int = 4096,
    upstreamMaxPayload: Int = 4096,
    rejectStrategy: RejectStrategy = RejectStrategy.Drop
) extends NgPluginConfig {
  override def json: JsValue = WebsocketSizeValidatorConfig.format.writes(this)
}

object WebsocketSizeValidatorConfig {
  val default = WebsocketSizeValidatorConfig()
  val format  = new Format[WebsocketSizeValidatorConfig] {
    override def writes(o: WebsocketSizeValidatorConfig): JsValue = Json.obj(
      "client_max_payload"   -> o.clientMaxPayload,
      "upstream_max_payload" -> o.upstreamMaxPayload,
      "reject_strategy"      -> o.rejectStrategy.json
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

  override def onResponseFlow: Boolean = true
  override def onRequestFlow: Boolean  = true

  private def internalCanAccess(ctx: NgWebsocketPluginContext, message: WebsocketMessage, maxSize: Int, reason: String)(
      implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    implicit val m: Materializer = env.otoroshiMaterializer

    message
      .size()
      .map(_ <= maxSize)
      .flatMap {
        case true  => Right(message).vfuture
        case false => Left(NgWebsocketError(CloseCodes.TooBig, reason)).vfuture
      }
  }

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    val config =
      ctx.cachedConfig(internalName)(WebsocketSizeValidatorConfig.format).getOrElse(WebsocketSizeValidatorConfig())

    internalCanAccess(ctx, message, config.clientMaxPayload, "limit exceeded")
  }

  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = {
    val config =
      ctx.cachedConfig(internalName)(WebsocketSizeValidatorConfig.format).getOrElse(WebsocketSizeValidatorConfig())
    config.rejectStrategy
  }

  override def onResponseMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    val config =
      ctx.cachedConfig(internalName)(WebsocketSizeValidatorConfig.format).getOrElse(WebsocketSizeValidatorConfig())

    internalCanAccess(ctx, message, config.upstreamMaxPayload, reason = "upstream payload limit exceeded")
  }
}

case class JqWebsocketMessageTransformerConfig(requestFilter: String = ".", responseFilter: String = ".")
    extends NgPluginConfig                 {
  override def json: JsValue = JqWebsocketMessageTransformerConfig.format.writes(this)
}
object JqWebsocketMessageTransformerConfig {
  val format = new Format[JqWebsocketMessageTransformerConfig] {
    override def reads(json: JsValue): JsResult[JqWebsocketMessageTransformerConfig] = Try {
      JqWebsocketMessageTransformerConfig(
        requestFilter = json.select("request_filter").asOpt[String].getOrElse("."),
        responseFilter = json.select("response_filter").asOpt[String].getOrElse(".")
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
    override def writes(o: JqWebsocketMessageTransformerConfig): JsValue             = Json.obj(
      "request_filter"  -> o.requestFilter,
      "response_filter" -> o.responseFilter
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

  override def onRequestFlow: Boolean                                        = true
  override def onResponseFlow: Boolean                                       = true
  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = RejectStrategy.Drop

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    val config = ctx
      .cachedConfig(internalName)(JqWebsocketMessageTransformerConfig.format)
      .getOrElse(JqWebsocketMessageTransformerConfig())
    onMessage(ctx, message, config.requestFilter)
  }

  override def onResponseMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    val config = ctx
      .cachedConfig(internalName)(JqWebsocketMessageTransformerConfig.format)
      .getOrElse(JqWebsocketMessageTransformerConfig())
    onMessage(ctx, message, config.responseFilter)
  }

  def onMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage, filter: String)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    implicit val mat = env.otoroshiMaterializer
    if (message.isText) {
      message.str().flatMap { bodyStr =>
        Try(Json.parse(bodyStr)) match {
          case Failure(e) => Left(NgWebsocketError(CloseCodes.PolicyViolated, "message payload is not json")).vfuture
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
      Left(NgWebsocketError(CloseCodes.PolicyViolated, "message payload is not text")).vfuture
    }
  }
}

class WasmWebsocketTransformer extends NgWebsocketPlugin {

  override def multiInstance: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(WasmConfig())
  override def core: Boolean                               = false
  override def name: String                                = "Websocket Wasm transformer"
  override def description: Option[String]                 = "Transform messages and filter websocket messages".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Websocket, NgPluginCategory.Wasm)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformResponse)

  override def onRequestFlow: Boolean                                        = true
  override def onResponseFlow: Boolean                                       = true
  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = RejectStrategy.Drop

  private val logger = Logger("otoroshi-plugins-wasm-websocket-transformer")

  def onMessage(
      ctx: NgWebsocketPluginContext,
      message: WebsocketMessage,
      functionName: Option[String]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    implicit val mat = env.otoroshiMaterializer
    val config       = ctx
      .cachedConfig(internalName)(WasmConfig.format)
      .getOrElse(WasmConfig())
    (if (message.isText) {
       message.str().map { str =>
         ctx.wasmJson.as[JsObject] ++ Json.obj(
           "message" -> Json.obj(
             "kind"    -> "text",
             "payload" -> str
           )
         )
       }
     } else {
       message.bytes().map { bytes =>
         ctx.wasmJson.as[JsObject] ++ Json.obj(
           "message" -> Json.obj(
             "kind"    -> "binary",
             "payload" -> bytes
           )
         )
       }
     }).flatMap { input =>
      env.wasmIntegration.wasmVmFor(config).flatMap {
        case None                    => Left(NgWebsocketError(500, "plugin not found !")).vfuture
        case Some((vm, localConfig)) =>
          vm.call(
            WasmFunctionParameters.ExtismFuntionCall(
              config.functionName.filter(_.nonEmpty).orElse(functionName).getOrElse("on_message"),
              input.stringify
            ),
            None
          ).flatMap {
            case Left(err)     =>
              Left(NgWebsocketError(500, err.stringify)).vfuture
            case Right(resStr) => {
              Try(Json.parse(resStr._1)) match {
                case Failure(e)        =>
                  Left(NgWebsocketError(500, Json.obj("error" -> e.getMessage).stringify)).vfuture
                case Success(response) => {
                  AttrsHelper.updateAttrs(ctx.attrs, response)
                  val error = response.select("error").asOpt[Boolean].getOrElse(false)
                  if (error) {
                    val reason: String  = response.select("reason").asOpt[String].getOrElse("error")
                    val statusCode: Int = response.select("statusCode").asOpt[Int].getOrElse(500)
                    Left(NgWebsocketError(statusCode, reason)).vfuture
                  } else {
                    val msg                       = response.select("message").asOpt[JsObject].getOrElse(Json.obj())
                    val kind                      = msg.select("kind").asOpt[String].getOrElse("text")
                    val message: WebsocketMessage = if (kind == "text") {
                      val payload = msg.select("payload").asOpt[String].getOrElse("")
                      WebsocketMessage.PlayMessage(play.api.http.websocket.TextMessage(payload))
                    } else {
                      val payload = msg
                        .select("payload")
                        .asOpt[Array[Byte]]
                        .map(bytes => ByteString(bytes))
                        .getOrElse(ByteString.empty)
                      WebsocketMessage.PlayMessage(play.api.http.websocket.BinaryMessage(payload))
                    }
                    Right(message).vfuture
                  }
                }
              }
            }
          }.andThen { case e =>
            vm.release()

            e match {
              case Failure(exception) => logger.error(exception.getMessage)
              case Success(_)         =>
            }
          }.recover { case e: Throwable =>
            Left(NgWebsocketError(500, Json.obj("error" -> e.getMessage).stringify))
          }
      }
    }
  }

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    onMessage(ctx, message, "on_request_message".some)
  }

  override def onResponseMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    onMessage(ctx, message, "on_response_message".some)
  }
}

case class WorkflowWebsocketConfig(incomingWorkflow: Option[String] = None, outgoingWorkflow: Option[String] = None) extends NgPluginConfig {
  def json: JsValue = WorkflowWebsocketConfig.format.writes(this)
}

object WorkflowWebsocketConfig {
  val format = new Format[WorkflowWebsocketConfig] {
    override def reads(json: JsValue): JsResult[WorkflowWebsocketConfig] = Try {
      WorkflowWebsocketConfig(
        incomingWorkflow = json.select("incoming_workflow").asOptString,
        outgoingWorkflow = json.select("outgoing_workflow").asOptString,
      )
    } match {
      case Failure(e: Throwable) => JsError(e.getMessage)
      case Success(e)          => JsSuccess(e)
    }
    override def writes(o: WorkflowWebsocketConfig): JsValue = Json.obj(
      "incoming_workflow" -> o.incomingWorkflow,
      "outgoing_workflow" -> o.outgoingWorkflow,
    )
  }
  val configFlowNoAsync: Seq[String] = Seq("incoming_workflow", "outgoing_workflow")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "incoming_workflow"   -> Json.obj(
        "type"  -> "select",
        "label" -> s"Incoming message workflow",
        "props" -> Json.obj(
          "optionsFrom"        -> s"/bo/api/proxy/apis/plugins.otoroshi.io/v1/workflows",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      ),
      "outgoing_workflow"   -> Json.obj(
        "type"  -> "select",
        "label" -> s"Outgoing message workflow",
        "props" -> Json.obj(
          "optionsFrom"        -> s"/bo/api/proxy/apis/plugins.otoroshi.io/v1/workflows",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      )
    )
  )
}

class WorkflowWebsocketTransformer extends NgWebsocketPlugin {

  override def multiInstance: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(WorkflowWebsocketConfig())
  override def core: Boolean = true
  override def name: String = "Websocket Workflow transformer"
  override def description: Option[String] = "Transform messages and filter websocket messages".some
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Websocket, NgPluginCategory.Custom("Workflow"))
  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def onRequestFlow: Boolean = true
  override def onResponseFlow: Boolean = true
  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = RejectStrategy.Drop
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String]        = WorkflowWebsocketConfig.configFlowNoAsync
  override def configSchema: Option[JsObject] = WorkflowWebsocketConfig.configSchema

  def onMessage(
                 ctx: NgWebsocketPluginContext,
                 message: WebsocketMessage,
                 workflowId: String,
                 action: String,
               )(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    implicit val mat = env.otoroshiMaterializer
    (if (message.isText) {
      message.str().map { str =>
        ctx.wasmJson.as[JsObject] ++ Json.obj(
          "action" -> action,
          "message" -> Json.obj(
            "kind"    -> "text",
            "payload" -> str
          )
        )
      }
    } else {
      message.bytes().map { bytes =>
        ctx.wasmJson.as[JsObject] ++ Json.obj(
          "action" -> action,
          "message" -> Json.obj(
            "kind"    -> "binary",
            "payload" -> bytes
          )
        )
      }
    }).flatMap { input =>
      val ext = env.adminExtensions.extension[WorkflowAdminExtension].get
      ext.workflow(workflowId) match {
        case None => message.rightf
        case Some(workflow) => {
          ext.engine
            .run(workflowId, Node.from(workflow.config), input.asObject, ctx.attrs, workflow.functions)
            .map { res =>
              if (res.hasError) {
                Left(NgWebsocketError(500, res.error.get.json.stringify))
              } else {
                res.returned match {
                  case None => message.right
                  case Some(JsNull) => message.right
                  case Some(response) => {
                    AttrsHelper.updateAttrs(ctx.attrs, response)
                    val error = response.select("error").asOpt[Boolean].getOrElse(false)
                    if (error) {
                      val reason: String = response.select("reason").asOpt[String].getOrElse("error")
                      val statusCode: Int = response.select("statusCode").asOpt[Int].getOrElse(500)
                      Left(NgWebsocketError(statusCode, reason))
                    } else {
                      val msg = response.select("message").asOpt[JsObject].getOrElse(Json.obj())
                      val kind = msg.select("kind").asOpt[String].getOrElse("text")
                      val message: WebsocketMessage = if (kind == "text") {
                        val payload = msg.select("payload").asOpt[String].getOrElse("")
                        WebsocketMessage.PlayMessage(play.api.http.websocket.TextMessage(payload))
                      } else {
                        val payload = msg
                          .select("payload")
                          .asOpt[Array[Byte]]
                          .map(bytes => ByteString(bytes))
                          .getOrElse(ByteString.empty)
                        WebsocketMessage.PlayMessage(play.api.http.websocket.BinaryMessage(payload))
                      }
                      Right(message)
                    }
                  }
                }
              }
            }
        }
      }
    }
  }

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
                                                                                          env: Env,
                                                                                          ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    val config = ctx
      .cachedConfig(internalName)(WorkflowWebsocketConfig.format)
      .getOrElse(WorkflowWebsocketConfig())
    config.incomingWorkflow match {
      case None => message.rightf
      case Some(workflowId) => onMessage(ctx, message, workflowId, "incoming_message")
    }
  }

  override def onResponseMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit
                                                                                           env: Env,
                                                                                           ec: ExecutionContext
  ): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    val config = ctx
      .cachedConfig(internalName)(WorkflowWebsocketConfig.format)
      .getOrElse(WorkflowWebsocketConfig())
    config.incomingWorkflow match {
      case None => message.rightf
      case Some(workflowId) => onMessage(ctx, message, workflowId, "outgoing_message")
    }
  }
}


case class WebsocketMirrorBackendConfig(url: Option[String] = None) extends NgPluginConfig {
  def json: JsValue = WebsocketMirrorBackendConfig.format.writes(this)
}

object WebsocketMirrorBackendConfig {
  val format = new Format[WebsocketMirrorBackendConfig] {

    override def reads(json: JsValue): JsResult[WebsocketMirrorBackendConfig] = Try {
      WebsocketMirrorBackendConfig(
        url = json.select("url").asOpt[String].filter(_.trim.nonEmpty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }

    override def writes(o: WebsocketMirrorBackendConfig): JsValue = Json.obj(
      "url" -> o.url.map(_.json).getOrElse(JsNull).asValue
    )
  }
  def configFlow: Seq[String] = Seq(
    "url"
  )
  def configSchema: JsObject  = Json.obj(
    "url" -> Json.obj("type" -> "string", "label" -> "Target URL")
  )
}

class WebsocketMirrorBackend extends NgWebsocketBackendPlugin {

  override def name: String                                = "Websocket mirror backend"
  override def description: Option[String]                 = "Mirror incoming websocket messages to another target".some
  override def core: Boolean                               = false
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Websocket)
  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(WebsocketMirrorBackendConfig())
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = WebsocketMirrorBackendConfig.configFlow
  override def configSchema: Option[JsObject]              = WebsocketMirrorBackendConfig.configSchema.some

  override def callBackendOrError(
    ctx: NgWebsocketPluginContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, Flow[Message, Message, _]]] = {
    val config = ctx
      .cachedConfig(internalName)(WebsocketMirrorBackendConfig.format)
      .getOrElse(WebsocketMirrorBackendConfig())
    val request = ctx.otoroshiRequest
    // val ctxPlugins = ctx.attrs.get(Keys.ContextualPluginsKey).get
    config.url match {
      case None => {
        ActorFlow
          .actorRef(out =>
            WebSocketProxyActor.props(
              UrlSanitizer.sanitize(request.url),
              out,
              request.headers.toSeq,
              ctx.request,
              request,
              ctx.route.serviceDescriptor,
              ctx.route.some,
              None, //ctxPlugins.some,
              ctx.target,
              ctx.attrs,
              env,
            )
          )(env.otoroshiActorSystem, env.otoroshiMaterializer).rightf
      }
      case Some(url) => {
        val hotSource = Sinks.many().unicast().onBackpressureBuffer[play.api.http.websocket.Message]()
        val hotFlux   = hotSource.asFlux()
        val cb = (m: play.api.http.websocket.Message) => {
          hotSource.tryEmitNext(m)
          ()
        }
        val mirrorFlow = ActorFlow
          .actorRef(out =>
            WebSocketProxyActor.props(
              UrlSanitizer.sanitize(url).evaluateEl(ctx.attrs),
              out,
              request.headers.toSeq,
              ctx.request,
              request,
              ctx.route.serviceDescriptor,
              ctx.route.some,
              None,//ctxPlugins.some,
              ctx.target,
              ctx.attrs,
              env
            )
          )(env.otoroshiActorSystem, env.otoroshiMaterializer)
        val response = ActorFlow
          .actorRef(out =>
            WebSocketProxyActor.props(
              UrlSanitizer.sanitize(request.url),
              out,
              request.headers.toSeq,
              ctx.request,
              request,
              ctx.route.serviceDescriptor,
              ctx.route.some,
              None,//ctxPlugins.some,
              ctx.target,
              ctx.attrs,
              env,
              Some(cb)
            )
          )(env.otoroshiActorSystem, env.otoroshiMaterializer)
          .alsoTo(Sink.onComplete {
            case _ => hotSource.tryEmitComplete()
          }).rightf
        Source.fromPublisher(hotFlux).via(mirrorFlow).runWith(Sink.foreach { m: play.api.http.websocket.Message =>
          //println("Got sink message: " + m)
        })(env.otoroshiMaterializer)
        response
      }
    }
  }
}
