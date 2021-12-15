package otoroshi.plugins.jq

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.arakelian.jq.{ImmutableJqLibrary, ImmutableJqRequest}
import otoroshi.env.Env
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class JqBodyTransformer extends RequestTransformer {

  private val logger = Logger("otoroshi-plugins-jq")

  private val library = ImmutableJqLibrary.of()

  override def name: String = "Transform input/output request bodies using JQ filters"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "JqBodyTransformer" -> Json.obj(
          "request" -> Json.obj("filter" -> "."),
          "response" -> Json.obj("filter" -> "."),
        )
      )
    )

  override def description: Option[String] =
    Some(
      s"""This plugin let you transform JSON bodies (in requests and responses) using [JQ filters](https://stedolan.github.io/jq/manual/#Basicfilters).
        |
        |This plugin can accept the following configuration
        |
        |```json
        |${defaultConfig.get.prettify}
        |```
    """.stripMargin
    )

  override def transformResponseWithCtx(
    ctx: TransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    val newHeaders = ctx.otoroshiResponse.headers.-("Content-Length").-("content-length").+("Transfer-Encoding" -> "chunked")
    ctx.otoroshiResponse.copy(headers = newHeaders).right.future
  }

  override def transformResponseBodyWithCtx(ctx: TransformerResponseBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    val filter = ctx.configFor("JqBodyTransformer").select("response").select("filter").asOpt[String].getOrElse(".")
    val future = ctx.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
      val bodyStr = bodyRaw.utf8String
      val request = ImmutableJqRequest
        .builder()
        .lib(library)
        .input(bodyStr)
        .filter(filter)
        .build()
      val response = request.execute()
      if (response.hasErrors) {
        // Source(JsArray(response.getErrors.asScala.map(err => JsString(err))).stringify.byteString.grouped(32 * 1024).toList)
        logger.error(s"error while transforming response body, sending the original payload instead:\n${response.getErrors.asScala.mkString("\n")}")
        Source(bodyRaw.grouped(32 * 1024).toList)
      } else {
        Source(response.getOutput.byteString.grouped(32 * 1024).toList)
      }
    }
    Source.future(future).flatMapConcat(identity)
  }

  override def transformRequestBodyWithCtx(ctx: TransformerRequestBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    val filter = ctx.configFor("JqBodyTransformer").select("request").select("filter").asOpt[String].getOrElse(".")
    val future = ctx.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
      val bodyStr = bodyRaw.utf8String
      val request = ImmutableJqRequest
        .builder()
        .lib(library)
        .input(bodyStr)
        .filter(filter)
        .build()
      val response = request.execute()
      if (response.hasErrors) {
        // Source(JsArray(response.getErrors.asScala.map(err => JsString(err))).stringify.byteString.grouped(32 * 1024).toList)
        logger.error(s"error while transforming request body, sending the original payload instead:\n${response.getErrors.asScala.mkString("\n")}")
        Source(bodyRaw.grouped(32 * 1024).toList)
      } else {
        Source(response.getOutput.byteString.grouped(32 * 1024).toList)
      }
    }
    Source.future(future).flatMapConcat(identity)
  }
}
