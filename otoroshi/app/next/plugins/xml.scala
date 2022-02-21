package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.arakelian.jq.{ImmutableJqLibrary, ImmutableJqRequest}
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.asScalaBufferConverter
import scala.util.{Failure, Success, Try}

class XmlToJsonRequest extends NgRequestTransformer {
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean = false
  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    if (ctx.request.hasBody && ctx.otoroshiRequest.contentType.exists(_.contains("text/xml"))) {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val xmlBody = scala.xml.XML.loadString(bodyRaw.utf8String)
        val jsonBody = otoroshi.utils.xml.Xml.toJson(xmlBody).stringify.byteString
        ctx.otoroshiRequest.copy(
          body = Source(jsonBody.grouped(16 * 1024).toList),
          headers = ctx.otoroshiRequest.headers
            .-("content-type").-("Content-Type")
            .-("content-length").-("Content-Length")
            .+("Content-Type" -> "application/json")
            .+("Content-Length" -> jsonBody.size.toString)
        ).right
      }
    } else {
      ctx.otoroshiRequest.right.vfuture
    }
  }
}

class JsonToXmlRequest extends NgRequestTransformer {
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean = false
  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    if (ctx.request.hasBody && ctx.otoroshiRequest.contentType.exists(_.contains("application/json"))) {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val jsonBody = Json.parse(bodyRaw.utf8String)
        val xmlBody = otoroshi.utils.xml.Xml.toXml(jsonBody).toString().byteString
        ctx.otoroshiRequest.copy(
          body = Source(xmlBody.grouped(16 * 1024).toList),
          headers = ctx.otoroshiRequest.headers
            .-("content-type").-("Content-Type")
            .-("content-length").-("Content-Length")
            .+("Content-Type" -> "text/xml")
            .+("Content-Length" -> xmlBody.size.toString)
        ).right
      }
    } else {
      ctx.otoroshiRequest.right.vfuture
    }
  }
}

class XmlToJsonResponse extends NgRequestTransformer {
  override def transformsRequest: Boolean = false
  override def transformsResponse: Boolean = true
  override def transformsError: Boolean = false
  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    if (ctx.otoroshiResponse.contentType.exists(_.contains("text/xml"))) {
      ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val xmlBody = scala.xml.XML.loadString(bodyRaw.utf8String)
        val jsonBody = otoroshi.utils.xml.Xml.toJson(xmlBody).stringify.byteString
        ctx.otoroshiResponse.copy(
          body = Source(jsonBody.grouped(16 * 1024).toList),
          headers = ctx.otoroshiResponse.headers
            .-("content-type").-("Content-Type")
            .-("content-length").-("Content-Length")
            .+("Content-Type" -> "application/json")
            .+("Content-Length" -> jsonBody.size.toString)
        ).right
      }
    } else {
      ctx.otoroshiResponse.right.vfuture
    }
  }
}

class JsonToXmlResponse extends NgRequestTransformer {
  override def transformsRequest: Boolean = false
  override def transformsResponse: Boolean = true
  override def transformsError: Boolean = false
  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    if (ctx.otoroshiResponse.contentType.exists(_.contains("application/json"))) {
      ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val jsonBody = Json.parse(bodyRaw.utf8String)
        val xmlBody = otoroshi.utils.xml.Xml.toXml(jsonBody).toString().byteString
        ctx.otoroshiResponse.copy(
          body = Source(xmlBody.grouped(16 * 1024).toList),
          headers = ctx.otoroshiResponse.headers
            .-("content-type").-("Content-Type")
            .-("content-length").-("Content-Length")
            .+("Content-Type" -> "text/xml")
            .+("Content-Length" -> xmlBody.size.toString)
        ).right
      }
    } else {
      ctx.otoroshiResponse.right.vfuture
    }
  }
}

case class SOAPActionConfig(
  url: Option[String] = None,
  envelope: String,
  action: Option[String] = None,
  preserveQuery: Boolean = true,
  charset: Option[String] = None,
  convertRequestBodyToXml: Boolean = true,
  jqResponseFilter: Option[String] = None
) {
  def json: JsValue = SOAPActionConfig.format.writes(this)
}

object SOAPActionConfig {
  val format = new Format[SOAPActionConfig] {
    override def reads(json: JsValue): JsResult[SOAPActionConfig] = Try {
      SOAPActionConfig(
        url = json.select("url").asOpt[String],
        envelope = json.select("envelope").as[String],
        action = json.select("action").asOpt[String],
        preserveQuery = json.select("preserve_query").asOpt[Boolean].getOrElse(true),
        charset = json.select("charset").asOpt[String],
        jqResponseFilter = json.select("jq_response_filter").asOpt[String],
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(exception) => JsError(exception.getMessage)
    }

    override def writes(o: SOAPActionConfig): JsValue = Json.obj(
      "url" -> o.url.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "envelope" -> o.envelope,
      "action" -> o.action.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "preserve_query" -> o.preserveQuery,
      "charset" -> o.charset.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "jq_response_filter" -> o.jqResponseFilter.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    )
  }
}


class SOAPAction extends NgRequestTransformer {

  private val configReads: Reads[SOAPActionConfig] = SOAPActionConfig.format
  private val library = ImmutableJqLibrary.of()

  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean = false

  def el(envelope: String, body: Option[String], ctx: NgTransformerRequestContext, env: Env): String = {
    val context = body match {
      case None => ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty)
      case Some(b) => ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty) ++ Map("input_body" -> b)
    }
    GlobalExpressionLanguage.apply(
      value = envelope,
      req = ctx.request.some,
      service = ctx.route.serviceDescriptor.some,
      apiKey = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
      user = ctx.attrs.get(otoroshi.plugins.Keys.UserKey),
      context = context,
      attrs = ctx.attrs,
      env = env,
    )
  }

  def transformResponseBody(body: String, config: SOAPActionConfig): Either[String, String] = {
    config.jqResponseFilter match {
      case None => body.right
      case Some(filter) => {
        val response = ImmutableJqRequest
          .builder()
          .lib(library)
          .input(body)
          .filter(filter)
          .build()
          .execute()
        if (response.hasErrors) {
          JsArray(response.getErrors.asScala.map(err => JsString(err))).stringify.left
        } else {
          response.getOutput.right
        }
      }
    }
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(throw new RuntimeException("bad config"))
    val bodyF: Future[Option[String]] = if (ctx.otoroshiRequest.hasBody) {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val body = bodyRaw.utf8String
        if (config.convertRequestBodyToXml && ctx.otoroshiRequest.contentType.exists(_.contains("application/json"))) {
          otoroshi.utils.xml.Xml.toXml(Json.parse(body)).toString().some
        } else {
          body.some
        }
      }
    } else {
      None.vfuture
    }
    bodyF.flatMap { body =>
      val soapEnvelop: String = el(config.envelope, body, ctx, env)
      val operation = config.action
      val url = config.url.getOrElse(s"${ctx.route.backend.targets.head.baseUrl}${ctx.route.backend.root}")
      env.Ws.url(url)
        .withHttpHeaders(
          "Content-Type" -> config.charset.getOrElse("text/xml; charset=utf-8"),
        ).applyOnWithOpt(operation) {
          case (ws, op) => ws.addHttpHeaders(
            "X-SOAP-RequestAction" -> op,
            "SOAPAction" -> op,
          )
        }
        .withMethod("POST")
        .withBody(soapEnvelop)
        .execute()
        .map { resp =>
          val headers = resp.headers.mapValues(_.last).toSeq
            .filterNot(_._1 == "Content-Type")
            .filterNot(_._1 == "Content-Length")
            .filterNot(_._1 == "content-type")
            .filterNot(_._1 == "content-length")
          if (resp.contentType.contains("text/xml") || resp.contentType.contains("application/xml") || resp.contentType.contains("application/xml+soap")) {
            val xmlBody = scala.xml.XML.loadString(resp.body)
            val jsonBody = otoroshi.utils.xml.Xml.toJson(xmlBody).stringify
            val headerz = headers :+ ("Content-Length" -> jsonBody.length.toString)
            val status = if (resp.body.contains(":Fault>") && resp.body.contains(":Client")) {
              Results.BadRequest
            } else if (resp.body.contains(":Fault>")) {
              Results.InternalServerError
            } else {
              Results.Ok
            }
            transformResponseBody(jsonBody, config) match {
              case Left(error) => Results.InternalServerError(error)
                .as("application/json")
                .withHeaders(headerz: _*)
                .left
              case Right(response) => status(response)
                .as("application/json")
                .withHeaders(headerz: _*)
                .left
            }
          } else {
            val headerz = headers :+ ("Content-Length" -> resp.body.length.toString)
            if (resp.body.contains(":Fault>") && resp.body.contains(":Client")) {
              Results.BadRequest(resp.body)
                .as("text/xml")
                .withHeaders(headerz: _*)
                .left
            } else if (resp.body.contains(":Fault>")) {
              Results.InternalServerError(resp.body)
                .as("text/xml")
                .withHeaders(headerz: _*)
                .left
            } else {
              Results.Ok(resp.body)
                .as("text/xml")
                .withHeaders(headerz: _*)
                .left
            }
          }
        }
    }
  }
}

