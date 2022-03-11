package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.arakelian.jq._
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

case class JQConfig(request: String = ".", response: String = "") {
  def json: JsValue = JQConfig.format.writes(this)
}

object JQConfig {
  val format = new Format[JQConfig] {
    override def writes(o: JQConfig): JsValue = Json.obj(
      "request" -> o.request,
      "response" -> o.response,
    )
    override def reads(json: JsValue): JsResult[JQConfig] = Try {
      JQConfig(
        request = json.select("request").asOpt[String].getOrElse("."),
        response = json.select("response").asOpt[String].getOrElse("."),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }
  }
}

case class JQRequestConfig(filter: String = ".") {
  def json: JsValue = JQRequestConfig.format.writes(this)
}

object JQRequestConfig {
  val format = new Format[JQRequestConfig] {
    override def writes(o: JQRequestConfig): JsValue = Json.obj(
      "filter" -> o.filter,
    )
    override def reads(json: JsValue): JsResult[JQRequestConfig] = Try {
      JQRequestConfig(
        filter = json.select("filter").asOpt[String].getOrElse("."),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }
  }
}

case class JQResponseConfig(filter: String = ".") {
  def json: JsValue = JQResponseConfig.format.writes(this)
}

object JQResponseConfig {
  val format = new Format[JQResponseConfig] {
    override def writes(o: JQResponseConfig): JsValue = Json.obj(
      "filter" -> o.filter,
    )
    override def reads(json: JsValue): JsResult[JQResponseConfig] = Try {
      JQResponseConfig(
        filter = json.select("filter").asOpt[String].getOrElse("."),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }
  }
}

class JQ extends NgRequestTransformer {

  private val library = ImmutableJqLibrary.of()
  private val logger = Logger("otoroshi-plugins-ng-jq")

  override def multiInstance: Boolean = true
  override def name: String                = "JQ"
  override def description: Option[String] = s"""This plugin let you transform JSON bodies (in requests and responses) using [JQ filters](https://stedolan.github.io/jq/manual/#Basicfilters).""".some
  override def defaultConfig: Option[JsObject] = JQConfig().json.asObject.some

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest, NgStep.TransformResponse)

  override def transformsError: Boolean = false

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(JQConfig.format).getOrElse(JQConfig())
    if (ctx.otoroshiRequest.hasBody) {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val bodyStr = bodyRaw.utf8String
        val request = ImmutableJqRequest
          .builder()
          .lib(library)
          .input(bodyStr)
          .putArgJson("context", ctx.json.stringify)
          .filter(config.request)
          .build()
        val response = request.execute()
        if (response.hasErrors) {
          logger.error(
            s"error while transforming response body:\n${
              response.getErrors.asScala
                .mkString("\n")
            }"
          )
          val errors = JsArray(response.getErrors.asScala.map(err => JsString(err)))
          Results
            .InternalServerError(Json.obj("error" -> "error while transforming response body", "details" -> errors))
            .left
        } else {
          val rawBody = response.getOutput.byteString
          val source = Source(rawBody.grouped(16 * 1024).toList)
          ctx.otoroshiRequest.copy(
            body = source,
            headers = ctx.otoroshiRequest.headers.removeAndPutIgnoreCase("Content-Length" -> rawBody.size.toString)
          ).right
        }
      }
    } else {
      ctx.otoroshiRequest.right.vfuture
    }
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(JQConfig.format).getOrElse(JQConfig())
    ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
      val bodyStr  = bodyRaw.utf8String
      val request  = ImmutableJqRequest
        .builder()
        .lib(library)
        .input(bodyStr)
        .putArgJson("context", ctx.json.stringify)
        .filter(config.response)
        .build()
      val response = request.execute()
      if (response.hasErrors) {
        logger.error(
          s"error while transforming response body, sending the original payload instead:\n${response.getErrors.asScala
            .mkString("\n")}"
        )
        val errors = JsArray(response.getErrors.asScala.map(err => JsString(err)))
        Results
          .InternalServerError(Json.obj("error" -> "error while transforming response body", "details" -> errors))
          .left
      } else {
        val rawBody = response.getOutput.byteString
        val source = Source(rawBody.grouped(16 * 1024).toList)
        ctx.otoroshiResponse.copy(
          body = source,
          headers = ctx.otoroshiResponse.headers.removeAndPutIgnoreCase("Content-Length" -> rawBody.size.toString)
        ).right
      }
    }
  }
}

class JQRequest extends NgRequestTransformer {

  private val library = ImmutableJqLibrary.of()
  private val logger = Logger("otoroshi-plugins-ng-jq-request")

  override def multiInstance: Boolean = true
  override def name: String                = "JQ transform request"
  override def description: Option[String] = s"""This plugin let you transform request JSON body using [JQ filters](https://stedolan.github.io/jq/manual/#Basicfilters).""".some
  override def defaultConfig: Option[JsObject] = JQRequestConfig().json.asObject.some

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)

  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean = false

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(JQRequestConfig.format).getOrElse(JQRequestConfig())
    if (ctx.otoroshiRequest.hasBody) {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val bodyStr = bodyRaw.utf8String
        val request = ImmutableJqRequest
          .builder()
          .lib(library)
          .input(bodyStr)
          .putArgJson("context", ctx.json.stringify)
          .filter(config.filter)
          .build()
        val response = request.execute()
        if (response.hasErrors) {
          logger.error(
            s"error while transforming response body:\n${
              response.getErrors.asScala
                .mkString("\n")
            }"
          )
          val errors = JsArray(response.getErrors.asScala.map(err => JsString(err)))
          Results
            .InternalServerError(Json.obj("error" -> "error while transforming response body", "details" -> errors))
            .left
        } else {
          val rawBody = response.getOutput.byteString
          val source = Source(rawBody.grouped(16 * 1024).toList)
          ctx.otoroshiRequest.copy(
            body = source,
            headers = ctx.otoroshiRequest.headers.removeAndPutIgnoreCase("Content-Length" -> rawBody.size.toString)
          ).right
        }
      }
    } else {
      ctx.otoroshiRequest.right.vfuture
    }
  }
}

class JQResponse extends NgRequestTransformer {

  private val library = ImmutableJqLibrary.of()
  private val logger = Logger("otoroshi-plugins-ng-jq-response")

  override def multiInstance: Boolean = true
  override def name: String                = "JQ transform response"
  override def description: Option[String] = s"""This plugin let you transform JSON response using [JQ filters](https://stedolan.github.io/jq/manual/#Basicfilters).""".some
  override def defaultConfig: Option[JsObject] = JQResponseConfig().json.asObject.some

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def steps: Seq[NgStep] = Seq(NgStep.TransformResponse)

  override def transformsError: Boolean = false
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = false

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(JQResponseConfig.format).getOrElse(JQResponseConfig())
    ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
      val bodyStr  = bodyRaw.utf8String
      val request  = ImmutableJqRequest
        .builder()
        .lib(library)
        .input(bodyStr)
        .putArgJson("context", ctx.json.stringify)
        .filter(config.filter)
        .build()
      val response = request.execute()
      if (response.hasErrors) {
        logger.error(
          s"error while transforming response body, sending the original payload instead:\n${response.getErrors.asScala
            .mkString("\n")}"
        )
        val errors = JsArray(response.getErrors.asScala.map(err => JsString(err)))
        Results
          .InternalServerError(Json.obj("error" -> "error while transforming response body", "details" -> errors))
          .left
      } else {
        val rawBody = response.getOutput.byteString
        val source = Source(rawBody.grouped(16 * 1024).toList)
        ctx.otoroshiResponse.copy(
          body = source,
          headers = ctx.otoroshiResponse.headers.removeAndPutIgnoreCase("Content-Length" -> rawBody.size.toString)
        ).right
      }
    }
  }
}
