package otoroshi.plugins.static

import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.script.{HttpRequest, RequestTransformer, TransformerRequestContext}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsNull, JsObject, Json}
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class StaticResponse extends RequestTransformer {

  override def name: String = "Static Response"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "StaticResponse" -> Json.obj(
          "status"             -> 200,
          "headers"             -> Json.obj(
            "Content-Type" -> "application/json"
          ),
          "body" -> """{"message":"hello world!"}""",
          "bodyBase64" -> JsNull
        )
      )
    )

  override def description: Option[String] =
    Some(
      s"""This plugin returns a static response for any request
        |
        |This plugin can accept the following configuration
        |
        |```json
        |${defaultConfig.get.prettify}
        |```
    """.stripMargin
    )

  override def transformRequestWithCtx(
    ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = ctx.configFor("StaticResponse")
    val status = config.select("status").asOpt[Int].getOrElse(200)
    val _headers = config.select("headers").asOpt[Map[String, String]].getOrElse(Map("Content-Type" -> "application/json"))
    val contentType = _headers.get("Content-Type").orElse(_headers.get("content-type")).getOrElse("application/json")
    val headers = _headers.filterNot(_._1.toLowerCase() == "content-type")
    val bodytext = config.select("body").asOpt[String].map(ByteString.apply)
    val bodyBase64 = config.select("bodyBase64").asOpt[String].map(ByteString.apply).map(_.decodeBase64)
    val body: ByteString = bodytext.orElse(bodyBase64).getOrElse("""{"message":"hello world!"}""".byteString)
    Left(Results.Status(status)(body).withHeaders(headers.toSeq: _*).as(contentType)).future
  }
}