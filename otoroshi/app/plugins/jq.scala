package otoroshi.plugins.jq

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.arakelian.jq.{ImmutableJqLibrary, ImmutableJqRequest}
import otoroshi.env.Env
import otoroshi.script._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, JsString, Json}
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{Request, RequestHeader, Result, Results}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._

class JqBodyTransformer extends RequestTransformer {

  private val logger = Logger("otoroshi-plugins-jq")

  private val requestKey  = TypedKey[Future[Source[ByteString, _]]]("otoroshi.plugins.jq.RequestBody")
  private val responseKey = TypedKey[Source[ByteString, _]]("otoroshi.plugins.jq.ResponseBody")

  private val library = ImmutableJqLibrary.of()

  override def name: String = "JQ bodies transformer"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "JqBodyTransformer" -> Json.obj(
          "request"  -> Json.obj("filter" -> ".", "included" -> Json.arr(), "excluded" -> Json.arr()),
          "response" -> Json.obj("filter" -> ".", "included" -> Json.arr(), "excluded" -> Json.arr())
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

  def shouldApply(included: Seq[String], excluded: Seq[String], uri: String): Boolean = {
    val isExcluded =
      if (excluded.isEmpty) false else excluded.exists(p => otoroshi.utils.RegexPool.regex(p).matches(uri))
    val isIncluded =
      if (included.isEmpty) true else included.exists(p => otoroshi.utils.RegexPool.regex(p).matches(uri))
    !isExcluded && isIncluded
  }

  private def hasBody(request: RequestHeader): Boolean =
    (request.method, request.headers.get("Content-Length")) match {
      case ("GET", Some(_))    => true
      case ("GET", None)       => false
      case ("HEAD", Some(_))   => true
      case ("HEAD", None)      => false
      case ("PATCH", _)        => true
      case ("POST", _)         => true
      case ("PUT", _)          => true
      case ("DELETE", Some(_)) => true
      case ("DELETE", None)    => false
      case _                   => true
    }

  override def transformResponseWithCtx(
      ctx: TransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    val config   = ctx.configFor("JqBodyTransformer").select("response")
    val filter   = config.select("filter").asOpt[String].getOrElse(".")
    val included = config.select("included").asOpt[Seq[String]].getOrElse(Seq.empty)
    val excluded = config.select("excluded").asOpt[Seq[String]].getOrElse(Seq.empty)
    if (shouldApply(included, excluded, ctx.request.thePath)) {
      val newHeaders =
        ctx.otoroshiResponse.headers.-("Content-Length").-("content-length").+("Transfer-Encoding" -> "chunked")
      ctx.rawResponse.body().runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val bodyStr  = bodyRaw.utf8String
        val request  = ImmutableJqRequest
          .builder()
          .lib(library)
          .input(bodyStr)
          .applyOnWithOpt(ctx.attrs.get(otoroshi.plugins.Keys.OtoTokenKey)) { case (builder, token) =>
            builder.putArgJson("otoToken", token.stringify)
          }
          .applyOnWithOpt(ctx.attrs.get(otoroshi.plugins.Keys.MatchedInputTokenKey)) { case (builder, token) =>
            builder.putArgJson("inToken", token.stringify)
          }
          .applyOnWithOpt(ctx.attrs.get(otoroshi.plugins.Keys.MatchedOutputTokenKey)) { case (builder, token) =>
            builder.putArgJson("token", token.stringify)
          }
          .applyOnWithOpt(ctx.user) { case (builder, user) =>
            builder.putArgJson("user", user.asJsonCleaned.stringify)
          }
          .applyOnWithOpt(ctx.apikey) { case (builder, user) =>
            builder.putArgJson("apikey", user.lightJson.stringify)
          }
          .putArgJson("queryParams", JsObject(ctx.request.theUri.query().toMap.mapValues(JsString.apply)).stringify)
          .putArgJson("headers", JsObject(ctx.request.headers.toSimpleMap.mapValues(JsString.apply)).stringify)
          .filter(filter)
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
          val source = Source(response.getOutput.byteString.grouped(32 * 1024).toList)
          ctx.attrs.put(responseKey -> source)
          ctx.otoroshiResponse.copy(headers = newHeaders).right
        }
      }
    } else {
      ctx.otoroshiResponse.rightf
    }
  }

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val promise = Promise[Source[ByteString, _]]()
    ctx.attrs.put(requestKey -> promise.future)
    val config   = ctx.configFor("JqBodyTransformer").select("request")
    val filter   = config.select("filter").asOpt[String].getOrElse(".")
    val included = config.select("included").asOpt[Seq[String]].getOrElse(Seq.empty)
    val excluded = config.select("excluded").asOpt[Seq[String]].getOrElse(Seq.empty)
    if (hasBody(ctx.request) && shouldApply(included, excluded, ctx.request.thePath)) {
      ctx.rawRequest.body().runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val bodyStr  = bodyRaw.utf8String
        val request  = ImmutableJqRequest
          .builder()
          .lib(library)
          .input(bodyStr)
          .applyOnWithOpt(ctx.attrs.get(otoroshi.plugins.Keys.OtoTokenKey)) { case (builder, token) =>
            builder.putArgJson("otoToken", token.stringify)
          }
          .applyOnWithOpt(ctx.attrs.get(otoroshi.plugins.Keys.MatchedInputTokenKey)) { case (builder, token) =>
            builder.putArgJson("inToken", token.stringify)
          }
          .applyOnWithOpt(ctx.attrs.get(otoroshi.plugins.Keys.MatchedOutputTokenKey)) { case (builder, token) =>
            builder.putArgJson("token", token.stringify)
          }
          .applyOnWithOpt(ctx.user) { case (builder, user) =>
            builder.putArgJson("user", user.asJsonCleaned.stringify)
          }
          .applyOnWithOpt(ctx.apikey) { case (builder, user) =>
            builder.putArgJson("apikey", user.lightJson.stringify)
          }
          .putArgJson("queryParams", JsObject(ctx.request.theUri.query().toMap.mapValues(JsString.apply)).stringify)
          .putArgJson("headers", JsObject(ctx.request.headers.toSimpleMap.mapValues(JsString.apply)).stringify)
          .filter(filter)
          .build()
        val response = request.execute()
        if (response.hasErrors) {
          val errors = JsArray(response.getErrors.asScala.map(err => JsString(err)))
          logger.error(
            s"error while transforming request body, sending the original payload instead:\n${response.getErrors.asScala
              .mkString("\n")}"
          )
          Results
            .InternalServerError(Json.obj("error" -> "error while transforming request body", "details" -> errors))
            .left
        } else {
          val rawResponseBody       = response.getOutput.byteString
          val rawResponseBodyLength = rawResponseBody.size
          val newHeaders            = ctx.otoroshiRequest.headers
            .-("Content-Length")
            .-("content-length")
            .+("Content-Length" -> rawResponseBodyLength.toString)
          val source                = Source(rawResponseBody.grouped(32 * 1024).toList)
          promise.trySuccess(source)
          ctx.otoroshiRequest.copy(headers = newHeaders).right
        }
      }
    } else {
      ctx.otoroshiRequest.rightf
    }
  }

  override def transformResponseBodyWithCtx(
      ctx: TransformerResponseBodyContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    ctx.attrs.get(responseKey) match {
      case None       => Source.empty
      case Some(body) => body
    }
  }

  override def transformRequestBodyWithCtx(
      ctx: TransformerRequestBodyContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    ctx.attrs.get(requestKey) match {
      case None       => Source.empty
      case Some(body) => Source.future(body).flatMapConcat(b => b)
    }
  }
}
