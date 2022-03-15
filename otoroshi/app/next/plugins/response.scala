package otoroshi.next.plugins

import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

case class StaticResponseConfig(status: Int = 200, headers: Map[String, String] = Map.empty, body: String = "") extends NgPluginConfig {
  def json: JsValue = StaticResponseConfig.format.writes(this)
}

object StaticResponseConfig {
  val format = new Format[StaticResponseConfig] {
    override def writes(o: StaticResponseConfig): JsValue = Json.obj(
      "status" -> o.status,
      "headers" -> o.headers,
      "body" -> o.body
    )
    override def reads(json: JsValue): JsResult[StaticResponseConfig] = Try {
      StaticResponseConfig(
        status = json.select("status").asOpt[Int].getOrElse(200),
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        body = json.select("body").asOpt[String].getOrElse(""),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }
  }
}

class StaticResponse extends NgRequestTransformer {

  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String                = "Static Response"
  override def description: Option[String] = "This plugin returns static responses".some
  override def defaultConfigObject: Option[NgPluginConfig] = StaticResponseConfig().some

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl)
  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)

  override def transformsResponse: Boolean = false
  override def transformsError: Boolean = false

  override def isTransformRequestAsync: Boolean = false

  override def transformRequestSync(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config = ctx.cachedConfig(internalName)(StaticResponseConfig.format).getOrElse(StaticResponseConfig())
    val contentType      = config.headers.get("Content-Type").orElse(config.headers.get("content-type")).getOrElse("application/json")
    val headers          = config.headers.filterNot(_._1.toLowerCase() == "content-type")
    val body: ByteString = config.body match {
      case str if str.startsWith("Base64(") => str.substring(7).init.byteString.decodeBase64
      case str => str.byteString
    }
    Left(Results.Status(config.status)(body).withHeaders(headers.toSeq: _*).as(contentType))
  }
}

case class MockResponse(path: String = "/", method: String = "GET", status: Int = 200, headers: Map[String, String] = Map.empty, body: String = "") extends NgPluginConfig {
  def json: JsValue = MockResponse.format.writes(this)
}

object MockResponse {
  val format = new Format[MockResponse] {
    override def writes(o: MockResponse): JsValue = Json.obj(
      "path" -> o.path,
      "method" -> o.method,
      "status" -> o.status,
      "headers" -> o.headers,
      "body" -> o.body
    )
    override def reads(json: JsValue): JsResult[MockResponse] = Try {
      MockResponse(
        path = json.select("path").asOpt[String].getOrElse("/"),
        method = json.select("method").asOpt[String].getOrElse("GET"),
        status = json.select("status").asOpt[Int].getOrElse(200),
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        body = json.select("body").asOpt[String].getOrElse(""),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }
  }
}

case class MockResponsesConfig(responses: Seq[MockResponse] = Seq.empty, passThrough: Boolean = true) extends NgPluginConfig {
  def json: JsValue = MockResponsesConfig.format.writes(this)
}

object MockResponsesConfig {
  val format = new Format[MockResponsesConfig] {
    override def writes(o: MockResponsesConfig): JsValue = Json.obj(
      "responses" -> JsArray(o.responses.map(_.json)),
      "pass_through" -> o.passThrough
    )
    override def reads(json: JsValue): JsResult[MockResponsesConfig] = Try {
      MockResponsesConfig(
        responses = json.select("responses").asOpt[Seq[JsValue]].map(arr => arr.flatMap(v => MockResponse.format.reads(v).asOpt)).getOrElse(Seq.empty),
        passThrough = json.select("pass_through").asOpt[Boolean].getOrElse(true)
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }
  }
}

class MockResponses extends NgRequestTransformer {

  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String                = "Mock Responses"
  override def description: Option[String] = "This plugin returns mock responses".some
  override def defaultConfigObject: Option[NgPluginConfig] = MockResponsesConfig().some

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl)
  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)

  override def transformsResponse: Boolean = false
  override def transformsError: Boolean = false

  override def isTransformRequestAsync: Boolean = false

  override def configSchema: Option[JsObject] = Json.obj(
    "responses" -> Json.obj(
      "label" -> "Responses",
      "array" -> true,
      "createOption" -> true,
      "format" -> "form",
      "type" -> "object",
      "schema" -> Json.obj(
        "path" -> Json.obj("type" -> "string"),
        "method" -> Json.obj("type" -> "string"),
        "status" -> Json.obj("type" -> "number"),
        "headers" -> Json.obj("type" -> "object"),
        "body" -> Json.obj("type" -> "string", "format" -> "code")
      )
    ),
    "pass_through" -> Json.obj(
      "type" -> "bool",
      "label" -> "Pass through"
    )
  ).some

  override def transformRequestSync(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config = ctx.cachedConfig(internalName)(MockResponsesConfig.format).getOrElse(MockResponsesConfig())
    config.responses.filter(_.method.toLowerCase == ctx.otoroshiRequest.method.toLowerCase).find { resp =>
      resp.path.wildcard.matches(ctx.otoroshiRequest.path)
    } match {
      case None if !config.passThrough => Left(Results.NotFound(Json.obj("error" -> "resource not found !")))
      case None if config.passThrough => Left(Results.NotFound(Json.obj("error" -> "resource not found !")))
      case Some(response) => {
        val contentType      = response.headers.get("Content-Type").orElse(response.headers.get("content-type")).getOrElse("application/json")
        val headers          = response.headers.filterNot(_._1.toLowerCase() == "content-type")
        val body: ByteString = response.body match {
          case str if str.startsWith("Base64(") => str.substring(7).init.byteString.decodeBase64
          case str => str.byteString
        }
        Left(Results.Status(response.status)(body).withHeaders(headers.toSeq: _*).as(contentType))
      }
    }
  }
}
