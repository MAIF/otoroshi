package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.arakelian.jq.{ImmutableJqLibrary, ImmutableJqRequest}
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.asScalaBufferConverter
import scala.util.{Failure, Success, Try}

case class JsonTransformConfig(filter: Option[String] = None) extends NgPluginConfig {
  def json: JsValue = JsonTransformConfig.format.writes(this)
}

object JsonTransformConfig {
  val library = ImmutableJqLibrary.of()
  val format  = new Format[JsonTransformConfig] {
    override def writes(o: JsonTransformConfig): JsValue             =
      Json.obj("filter" -> o.filter.map(JsString.apply).getOrElse(JsNull).asValue)
    override def reads(json: JsValue): JsResult[JsonTransformConfig] = JsSuccess(
      JsonTransformConfig(json.select("filter").asOpt[String])
    )
  }
}

trait JsonTransform {
  def transform(body: String, config: JsonTransformConfig): Either[String, String] = {
    config.filter match {
      case None         => body.right
      case Some(filter) => {
        val response = ImmutableJqRequest
          .builder()
          .lib(JsonTransformConfig.library)
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
}

class XmlToJsonRequest extends NgRequestTransformer with JsonTransform {

  private val configReads: Format[JsonTransformConfig] = JsonTransformConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "request body xml-to-json"
  override def description: Option[String]                 =
    "This plugin transform incoming request body from xml to json and may apply a jq transformation".some
  override def defaultConfigObject: Option[NgPluginConfig] = JsonTransformConfig().some

  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false
  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(JsonTransformConfig())
    if (ctx.request.hasBody && ctx.otoroshiRequest.contentType.exists(_.contains("text/xml"))) {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val xmlBody  = scala.xml.XML.loadString(bodyRaw.utf8String)
        val jsonBody = otoroshi.utils.xml.Xml.toJson(xmlBody).stringify
        transform(jsonBody, config) match {
          case Left(err)   => Results.InternalServerError(err).as("application/json").left
          case Right(body) => {
            ctx.otoroshiRequest
              .copy(
                body = Source(body.byteString.grouped(16 * 1024).toList),
                headers = ctx.otoroshiRequest.headers
                  .removeAndPutIgnoreCase("Content-Type" -> "application/json")
                  .removeAndPutIgnoreCase("Content-Length" -> jsonBody.size.toString)
              )
              .right
          }
        }
      }
    } else {
      ctx.otoroshiRequest.right.vfuture
    }
  }
}

class JsonToXmlRequest extends NgRequestTransformer with JsonTransform {

  private val configReads: Format[JsonTransformConfig] = JsonTransformConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "request body json-to-xml"
  override def description: Option[String]                 =
    "This plugin transform incoming request body from json to xml and may apply a jq transformation".some
  override def defaultConfigObject: Option[NgPluginConfig] = JsonTransformConfig().some

  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false
  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(JsonTransformConfig())
    if (ctx.request.hasBody && ctx.otoroshiRequest.contentType.exists(_.contains("application/json"))) {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val jsonBody = bodyRaw.utf8String
        transform(jsonBody, config) match {
          case Left(err)   => Results.InternalServerError(err).as("application/json").left
          case Right(body) => {
            val xmlBody = otoroshi.utils.xml.Xml.toXml(Json.parse(body)).toString().byteString
            ctx.otoroshiRequest
              .copy(
                body = Source(xmlBody.grouped(16 * 1024).toList),
                headers = ctx.otoroshiRequest.headers
                  .removeAndPutIgnoreCase("Content-Type" -> "text/xml")
                  .removeAndPutIgnoreCase("Content-Length" -> xmlBody.size.toString)
              )
              .right
          }
        }
      }
    } else {
      ctx.otoroshiRequest.right.vfuture
    }
  }
}

class XmlToJsonResponse extends NgRequestTransformer with JsonTransform {

  private val configReads: Format[JsonTransformConfig] = JsonTransformConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "response body xml-to-json"
  override def description: Option[String]                 =
    "This plugin transform response body from xml to json and may apply a jq transformation".some
  override def defaultConfigObject: Option[NgPluginConfig] = JsonTransformConfig().some

  override def transformsRequest: Boolean  = false
  override def transformsResponse: Boolean = true
  override def transformsError: Boolean    = false
  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(JsonTransformConfig())
    if (ctx.otoroshiResponse.contentType.exists(_.contains("text/xml"))) {
      ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val xmlBody  = scala.xml.XML.loadString(bodyRaw.utf8String)
        val jsonBody = otoroshi.utils.xml.Xml.toJson(xmlBody).stringify
        transform(jsonBody, config) match {
          case Left(err)   => Results.InternalServerError(err).as("application/json").left
          case Right(body) => {
            ctx.otoroshiResponse
              .copy(
                body = Source(body.byteString.grouped(16 * 1024).toList),
                headers = ctx.otoroshiResponse.headers
                  .removeAndPutIgnoreCase("Content-Type" -> "application/json")
                  .removeAndPutIgnoreCase("Content-Length" -> jsonBody.size.toString)
              )
              .right
          }
        }
      }
    } else {
      ctx.otoroshiResponse.right.vfuture
    }
  }
}

class JsonToXmlResponse extends NgRequestTransformer with JsonTransform {

  private val configReads: Format[JsonTransformConfig] = JsonTransformConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "response body json-to-xml"
  override def description: Option[String]                 =
    "This plugin transform response body from json to xml and may apply a jq transformation".some
  override def defaultConfigObject: Option[NgPluginConfig] = JsonTransformConfig().some

  override def transformsRequest: Boolean  = false
  override def transformsResponse: Boolean = true
  override def transformsError: Boolean    = false
  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(JsonTransformConfig())
    if (ctx.otoroshiResponse.contentType.exists(_.contains("application/json"))) {
      ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        transform(bodyRaw.utf8String, config) match {
          case Left(err)   => Results.InternalServerError(err).as("application/json").left
          case Right(body) => {
            val jsonBody = Json.parse(body)
            val xmlBody  = otoroshi.utils.xml.Xml.toXml(jsonBody).toString().byteString
            ctx.otoroshiResponse
              .copy(
                body = Source(xmlBody.grouped(16 * 1024).toList),
                headers = ctx.otoroshiResponse.headers
                  .removeAndPutIgnoreCase("Content-Type" -> "text/xml")
                  .removeAndPutIgnoreCase("Content-Length" -> xmlBody.size.toString)
              )
              .right
          }
        }
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
    jqRequestFilter: Option[String] = None,
    jqResponseFilter: Option[String] = None
) extends NgPluginConfig {
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
        jqRequestFilter = json.select("jq_request_filter").asOpt[String],
        jqResponseFilter = json.select("jq_response_filter").asOpt[String]
      )
    } match {
      case Success(value)     => JsSuccess(value)
      case Failure(exception) => JsError(exception.getMessage)
    }

    override def writes(o: SOAPActionConfig): JsValue = Json.obj(
      "url"                -> o.url.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "envelope"           -> o.envelope,
      "action"             -> o.action.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "preserve_query"     -> o.preserveQuery,
      "charset"            -> o.charset.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "jq_request_filter"  -> o.jqRequestFilter.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "jq_response_filter" -> o.jqResponseFilter.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
  }
}

class SOAPAction extends NgBackendCall {

  private val configReads: Reads[SOAPActionConfig] = SOAPActionConfig.format
  private val library                              = ImmutableJqLibrary.of()

  override def useDelegates: Boolean             = false
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def name: String                                = "SOAP action"
  override def description: Option[String]                 =
    "This plugin is able to call SOAP actions and expose it as a rest endpoint".some
  override def defaultConfigObject: Option[NgPluginConfig] = SOAPActionConfig(envelope = "<soap envelope />").some

  def el(envelope: String, body: Option[String], ctx: NgbBackendCallContext, env: Env): String = {
    val context = body match {
      case None    => ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty)
      case Some(b) => ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty) ++ Map("input_body" -> b)
    }
    GlobalExpressionLanguage.apply(
      value = envelope,
      req = ctx.rawRequest.some,
      service = ctx.route.serviceDescriptor.some,
      apiKey = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
      user = ctx.attrs.get(otoroshi.plugins.Keys.UserKey),
      context = context,
      attrs = ctx.attrs,
      env = env
    )
  }

  def transformResponseBody(body: String, config: SOAPActionConfig): Either[String, String] = {
    config.jqResponseFilter match {
      case None         => body.right
      case Some(filter) =>
        Try {
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
        } match {
          case Failure(e) => Left(Json.obj("error" -> e.getMessage).stringify)
          case Success(r) => r
        }
    }
  }

  def transformRequestBody(body: String, config: SOAPActionConfig): Either[String, String] = {
    config.jqRequestFilter match {
      case None         => body.right
      case Some(filter) =>
        Try {
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
        } match {
          case Failure(e) => Left(Json.obj("error" -> e.getMessage).stringify)
          case Success(r) => r
        }
    }
  }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config                                        = ctx.cachedConfig(internalName)(configReads).getOrElse(throw new RuntimeException("bad config"))
    val bodyF: Future[Either[String, Option[String]]] = if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
        val body = bodyRaw.utf8String
        if (config.convertRequestBodyToXml && ctx.request.contentType.exists(_.contains("application/json"))) {
          transformRequestBody(body, config) match {
            case Left(err)    => err.left
            case Right(tbody) => otoroshi.utils.xml.Xml.toXml(Json.parse(tbody)).toString().some.right
          }
        } else {
          body.some.right
        }
      }
    } else {
      None.right.vfuture
    }
    bodyF.flatMap {
      case Left(err)   =>
        bodyResponse(
          500,
          Map("Content-Type" -> "application/json"),
          Source.single(Json.parse(err).stringify.byteString)
        ).future
      case Right(body) => {
        val soapEnvelop: String = el(config.envelope, body, ctx, env)
        val operation           = config.action
        val url                 = config.url.getOrElse(s"${ctx.route.backend.targets.head.baseUrl}${ctx.route.backend.root}")
        env.Ws
          .url(url)
          .withHttpHeaders(
            "Content-Type" -> config.charset.getOrElse("text/xml; charset=utf-8")
          )
          .applyOnWithOpt(operation) { case (ws, op) =>
            ws.addHttpHeaders(
              "X-SOAP-RequestAction" -> op,
              "SOAPAction"           -> op
            )
          }
          .withMethod("POST")
          .withBody(soapEnvelop)
          .execute()
          .map { resp =>
            val headers = resp.headers
              .mapValues(_.last)
              .toSeq
              .filterNot(_._1 == "Content-Type")
              .filterNot(_._1 == "Content-Length")
              .filterNot(_._1 == "content-type")
              .filterNot(_._1 == "content-length")
            if (
              resp.contentType.contains("text/xml") || resp.contentType.contains("application/xml") || resp.contentType
                .contains("application/xml+soap")
            ) {
              val xmlBody  = scala.xml.XML.loadString(resp.body)
              val jsonBody = otoroshi.utils.xml.Xml.toJson(xmlBody).stringify
              val headerz  = headers :+ ("Content-Length" -> jsonBody.length.toString)
              val status   = if (resp.body.contains(":Fault>") && resp.body.contains(":Client")) {
                400
              } else if (resp.body.contains(":Fault>")) {
                500
              } else {
                200
              }
              transformResponseBody(jsonBody, config) match {
                case Left(error)     =>
                  bodyResponse(
                    500,
                    headerz.toMap ++ Map("Content-Type" -> "application/json"),
                    Source.single(error.byteString)
                  )
                case Right(response) =>
                  bodyResponse(
                    status,
                    headerz.toMap ++ Map("Content-Type" -> "application/json"),
                    Source.single(response.byteString)
                  )
              }
            } else {
              val headerz = headers :+ ("Content-Length" -> resp.body.length.toString)
              if (resp.body.contains(":Fault>") && resp.body.contains(":Client")) {
                bodyResponse(
                  400,
                  headerz.toMap ++ Map("Content-Type" -> "text/xml"),
                  Source.single(resp.body.byteString)
                )
              } else if (resp.body.contains(":Fault>")) {
                bodyResponse(
                  500,
                  headerz.toMap ++ Map("Content-Type" -> "text/xml"),
                  Source.single(resp.body.byteString)
                )
              } else {
                bodyResponse(
                  200,
                  headerz.toMap ++ Map("Content-Type" -> "text/xml"),
                  Source.single(resp.body.byteString)
                )
              }
            }
          }
      }
    }
  }
}
