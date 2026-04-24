package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.networknt.schema.{InputFormat, JsonSchemaFactory, PathType, SchemaValidatorsConfig}
import com.networknt.schema.SpecVersion.VersionFlag
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

case class JsonSchemaValidatorConfig(
    schema: Option[String] = None,
    specification: String = VersionFlag.V202012.getId,
    failOnValidationError: Boolean = true
) extends NgPluginConfig {
  override def json: JsValue = JsonSchemaValidatorConfig.format.writes(this)
}

object JsonSchemaValidatorConfig {
  val default = JsonSchemaValidatorConfig(
    schema = "{ \"type\": \"object\", \"required\": [\"name\"] }".some
  )
  val format  = new Format[JsonSchemaValidatorConfig] {
    override def writes(o: JsonSchemaValidatorConfig): JsValue             = Json.obj(
      "schema"                   -> o.schema,
      "specification"            -> o.specification,
      "fail_on_validation_error" -> o.failOnValidationError
    )
    override def reads(json: JsValue): JsResult[JsonSchemaValidatorConfig] = Try {
      JsonSchemaValidatorConfig(
        schema = json.select("schema").asOpt[String],
        specification = json.select("specification").asOpt[String].getOrElse(VersionFlag.V202012.getId),
        failOnValidationError = json.select("fail_on_validation_error").asOpt[Boolean].getOrElse(true)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
  }
}

object JsonSchemaValidator {

  val logger = Logger("otoroshi-plugins-ng-jsonschema-validator")

  def isJsonContentType(ct: Option[String]): Boolean =
    ct.exists(c => c.toLowerCase.contains("json"))

  def validate(bodyStr: String, config: JsonSchemaValidatorConfig): Either[Seq[String], Unit] = {
    val userSchema = config.schema.getOrElse("")
    if (userSchema.trim.isEmpty) {
      Right(())
    } else {
      Try {
        val versionFlag  = Option(VersionFlag.fromId(config.specification)).flatMap(o => Option(o.orElse(null)))
          .getOrElse(VersionFlag.V202012)
        val factory      = JsonSchemaFactory.getInstance(versionFlag)
        val schemaConfig = new SchemaValidatorsConfig()
        schemaConfig.setPathType(PathType.JSON_POINTER)
        schemaConfig.setFormatAssertionsEnabled(true)
        val schema       = factory.getSchema(userSchema, schemaConfig)
        val results      = schema.validate(bodyStr, InputFormat.JSON)
        if (results.isEmpty) Right(())
        else Left(results.asScala.toSeq.map(_.getMessage))
      } match {
        case Success(v) => v
        case Failure(t) => Left(Seq(s"validation error: ${t.getMessage}"))
      }
    }
  }
}

class JsonSchemaRequestValidator extends NgRequestTransformer {

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "Json schema request validator"
  override def description: Option[String]                 =
    "Validates the body of incoming HTTP requests against a JSON schema".some
  override def defaultConfigObject: Option[NgPluginConfig] = JsonSchemaValidatorConfig.default.some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Security)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)

  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx
      .cachedConfig(internalName)(JsonSchemaValidatorConfig.format)
      .getOrElse(JsonSchemaValidatorConfig())
    if (!ctx.otoroshiRequest.hasBody || !JsonSchemaValidator.isJsonContentType(ctx.otoroshiRequest.contentType)) {
      ctx.otoroshiRequest.right.vfuture
    } else {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { rawBody =>
        val bodyStr = rawBody.utf8String
        JsonSchemaValidator.validate(bodyStr, config) match {
          case Right(_)     =>
            ctx.otoroshiRequest.copy(body = Source.single(rawBody)).right
          case Left(errors) =>
            JsonSchemaValidator.logger.warn(
              s"request body schema validation failed on route '${ctx.route.id}': ${errors.mkString(", ")}"
            )
            if (config.failOnValidationError) {
              Results
                .UnprocessableEntity(
                  Json.obj(
                    "error"             -> "request body does not match the json schema",
                    "validation_errors" -> JsArray(errors.map(JsString.apply))
                  )
                )
                .left
            } else {
              ctx.otoroshiRequest.copy(body = Source.single(rawBody)).right
            }
        }
      }
    }
  }
}

class JsonSchemaResponseValidator extends NgRequestTransformer {

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "Json schema response validator"
  override def description: Option[String]                 =
    "Validates the body of outgoing HTTP responses against a JSON schema".some
  override def defaultConfigObject: Option[NgPluginConfig] = JsonSchemaValidatorConfig.default.some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Security)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)

  override def transformsRequest: Boolean = false
  override def transformsError: Boolean   = false

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(JsonSchemaValidatorConfig.format)
      .getOrElse(JsonSchemaValidatorConfig())
    val ct     = ctx.otoroshiResponse.headers
      .get("Content-Type")
      .orElse(ctx.otoroshiResponse.headers.get("content-type"))
    if (!JsonSchemaValidator.isJsonContentType(ct)) {
      ctx.otoroshiResponse.right.vfuture
    } else {
      ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { rawBody =>
        val bodyStr = rawBody.utf8String
        JsonSchemaValidator.validate(bodyStr, config) match {
          case Right(_)     =>
            ctx.otoroshiResponse.copy(body = Source.single(rawBody)).right
          case Left(errors) =>
            JsonSchemaValidator.logger.warn(
              s"response body schema validation failed on route '${ctx.route.id}': ${errors.mkString(", ")}"
            )
            if (config.failOnValidationError) {
              Results
                .BadGateway(
                  Json.obj(
                    "error"             -> "response body does not match the json schema",
                    "validation_errors" -> JsArray(errors.map(JsString.apply))
                  )
                )
                .left
            } else {
              ctx.otoroshiResponse.copy(body = Source.single(rawBody)).right
            }
        }
      }
    }
  }
}
