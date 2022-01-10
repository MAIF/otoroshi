package otoroshi.next.plugins.api

import akka.Done
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import otoroshi.next.models.{PluginInstance, Route}
import otoroshi.script.{Access, AccessContext, Allowed, ContextWithConfig, Denied, HttpRequest, HttpResponse, InternalEventListener, NamedPlugin, PluginType, StartableAndStoppable, TransformerContext}
import otoroshi.utils.TypedMap
import play.api.libs.json._
import play.api.libs.ws.WSCookie
import play.api.mvc.{RequestHeader, Result, Results}

import java.security.cert.X509Certificate
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

case class PluginHttpRequest(
  url: String,
  method: String,
  headers: Map[String, String],
  cookies: Seq[WSCookie] = Seq.empty[WSCookie],
  version: String,
  clientCertificateChain: Option[Seq[X509Certificate]],
  body: Source[ByteString, _]
) {
  lazy val contentType: Option[String] = headers.get("Content-Type").orElse(headers.get("content-type"))
  lazy val host: String                = headers.get("Host").orElse(headers.get("host")).getOrElse("")
  lazy val uri: Uri                    = Uri(url)
  lazy val scheme: String              = uri.scheme
  lazy val authority: Uri.Authority    = uri.authority
  lazy val fragment: Option[String]    = uri.fragment
  lazy val path: String                = uri.path.toString()
  lazy val queryString: Option[String] = uri.rawQueryString
  lazy val relativeUri: String         = uri.toRelative.toString()
  def json: JsValue                    =
    Json.obj(
      "url"     -> url,
      "method"  -> method,
      "headers" -> headers,
      "version" -> version,
      "cookies" -> JsArray(
        cookies.map(c =>
          Json.obj(
            "name"     -> c.name,
            "value"    -> c.value,
            "domain"   -> c.domain.map(JsString.apply).getOrElse(JsNull).as[JsValue],
            "path"     -> c.path.map(JsString.apply).getOrElse(JsNull).as[JsValue],
            "maxAge"   -> c.maxAge.map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).as[JsValue],
            "secure"   -> c.secure,
            "httpOnly" -> c.httpOnly
          )
        )
      )
    )
}

case class PluginHttpResponse(
  status: Int,
  headers: Map[String, String],
  cookies: Seq[WSCookie] = Seq.empty[WSCookie],
  body: Source[ByteString, _]
) {
  def json: JsValue =
    Json.obj(
      "status"  -> status,
      "headers" -> headers,
      "cookies" -> JsArray(
        cookies.map(c =>
          Json.obj(
            "name"     -> c.name,
            "value"    -> c.value,
            "domain"   -> c.domain.map(JsString.apply).getOrElse(JsNull).as[JsValue],
            "path"     -> c.path.map(JsString.apply).getOrElse(JsNull).as[JsValue],
            "maxAge"   -> c.maxAge.map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).as[JsValue],
            "secure"   -> c.secure,
            "httpOnly" -> c.httpOnly
          )
        )
      )
    )
}

trait NgNamedPlugin extends NamedPlugin { self =>
  override def pluginType: PluginType = PluginType.CompositeType
  override def configRoot: Option[String] = None
  override def configSchema: Option[JsObject] =
    defaultConfig match {
      case None         => None
      case Some(config) => {
        def genSchema(jsobj: JsObject, prefix: String): JsObject = {
          jsobj.value.toSeq
            .map {
              case (key, JsString(_))              =>
                Json.obj(prefix + key -> Json.obj("type" -> "string", "props" -> Json.obj("label" -> (prefix + key))))
              case (key, JsNumber(_))              =>
                Json.obj(prefix + key -> Json.obj("type" -> "number", "props" -> Json.obj("label" -> (prefix + key))))
              case (key, JsBoolean(_))             =>
                Json.obj(prefix + key -> Json.obj("type" -> "bool", "props" -> Json.obj("label" -> (prefix + key))))
              case (key, JsArray(values))          => {
                if (values.isEmpty) {
                  Json.obj(prefix + key -> Json.obj("type" -> "array", "props" -> Json.obj("label" -> (prefix + key))))
                } else {
                  values.head match {
                    case JsNumber(_) =>
                      Json.obj(
                        prefix + key -> Json.obj(
                          "type"  -> "array",
                          "props" -> Json.obj("label" -> (prefix + key), "inputType" -> "number")
                        )
                      )
                    case _           =>
                      Json.obj(
                        prefix + key -> Json.obj("type" -> "array", "props" -> Json.obj("label" -> (prefix + key)))
                      )
                  }
                }
              }
              case ("mtlsConfig", a @ JsObject(_)) => genSchema(a, prefix + "mtlsConfig.")
              case ("mtls", a @ JsObject(_))       => genSchema(a, prefix + "mtls.")
              case ("filter", a @ JsObject(_))     => genSchema(a, prefix + "filter.")
              case ("not", a @ JsObject(_))        => genSchema(a, prefix + "not.")
              case (key, JsObject(_))              =>
                Json.obj(prefix + key -> Json.obj("type" -> "object", "props" -> Json.obj("label" -> (prefix + key))))
              case (key, JsNull)                   => Json.obj()
            }
            .foldLeft(Json.obj())(_ ++ _)
        }
        Some(genSchema(config, ""))
      }
    }
  override def configFlow: Seq[String]        =
    defaultConfig match {
      case None         => Seq.empty
      case Some(config) => {
        def genFlow(jsobj: JsObject, prefix: String): Seq[String] = {
          jsobj.value.toSeq.flatMap {
            case ("mtlsConfig", a @ JsObject(_)) => genFlow(a, prefix + "mtlsConfig.")
            case ("mtls", a @ JsObject(_))       => genFlow(a, prefix + "mtls.")
            case ("filter", a @ JsObject(_))     => genFlow(a, prefix + "filter.")
            case ("not", a @ JsObject(_))        => genFlow(a, prefix + "not.")
            case (key, value)                    => Seq(prefix + key)
          }
        }
        genFlow(config, "")
      }
    }
  override def jsonDescription(): JsObject =
    Try {
      Json.obj(
        "name"          -> name,
        "description"   -> description.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "defaultConfig" -> defaultConfig.getOrElse(JsNull).as[JsValue],
        "configSchema"  -> configSchema.getOrElse(JsNull).as[JsValue],
        "configFlow"    -> JsArray(configFlow.map(JsString.apply))
      )
    } match {
      case Failure(ex) => Json.obj()
      case Success(s)  => s
    }
}

trait NgPlugin extends StartableAndStoppable with NgNamedPlugin with InternalEventListener

case class NgPreRoutingContext(
  snowflake: String,
  request: RequestHeader,
  route: Route,
  config: JsValue,
  globalConfig: JsValue,
  attrs: TypedMap,
) {
  def json: JsValue = Json.obj(

  )
}

case class PluginWrapper[A <: NgNamedPlugin](instance: PluginInstance, plugin: A)

trait NgPreRoutingError {
  def result: Result
}
case class NgPreRoutingErrorRaw(
  body: ByteString,
  code: Int = 500,
  contentType: String,
  headers: Map[String, String] = Map.empty
) extends NgPreRoutingError {
  def result: Result = {
    Results.Status(code).apply(body).as(contentType).withHeaders(headers.toSeq: _*)
  }
}
case class NgPreRoutingErrorWithResult(result: Result) extends NgPreRoutingError

object NgPreRouting {
  val futureDone: Future[Either[NgPreRoutingError, Done]] = FastFuture.successful(Right(Done))
}

trait NgPreRouting extends NgPlugin {
  def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = NgPreRouting.futureDone
}

case class NgBeforeRequestContext(
  snowflake: String,
  route: Route,
  request: RequestHeader,
  config: JsValue,
  attrs: TypedMap,
  globalConfig: JsValue = Json.obj()
)

case class NgAfterRequestContext(
  snowflake: String,
  route: Route,
  request: RequestHeader,
  config: JsValue,
  attrs: TypedMap,
  globalConfig: JsValue = Json.obj()
)

case class NgTransformerRequestContext(
  rawRequest: PluginHttpRequest,
  otoroshiRequest: PluginHttpRequest,
  snowflake: String,
  route: Route,
  apikey: Option[ApiKey],
  user: Option[PrivateAppsUser],
  request: RequestHeader,
  config: JsValue,
  attrs: TypedMap,
  globalConfig: JsValue = Json.obj()
)

case class NgTransformerResponseContext(
  rawResponse: PluginHttpResponse,
  otoroshiResponse: PluginHttpResponse,
  snowflake: String,
  route: Route,
  apikey: Option[ApiKey],
  user: Option[PrivateAppsUser],
  request: RequestHeader,
  config: JsValue,
  attrs: TypedMap,
  globalConfig: JsValue = Json.obj()
)

case class NgTransformerErrorContext(
  snowflake: String,
  message: String,
  otoroshiResult: Result,
  otoroshiResponse: PluginHttpResponse,
  request: RequestHeader,
  maybeCauseId: Option[String],
  callAttempts: Int,
  route: Route,
  apikey: Option[ApiKey],
  user: Option[PrivateAppsUser],
  config: JsValue,
  globalConfig: JsValue = Json.obj(),
  attrs: TypedMap
)

trait NgRequestTransformer extends NgPlugin {

  def beforeRequest(ctx: NgBeforeRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    FastFuture.successful(())
  }

  def afterRequest(ctx: NgAfterRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    FastFuture.successful(())
  }

  def transformError(ctx: NgTransformerErrorContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    FastFuture.successful(ctx.otoroshiResult)
  }

  def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpRequest]] = {
    FastFuture.successful(Right(ctx.otoroshiRequest))
  }

  def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, PluginHttpResponse]] = {
    FastFuture.successful(Right(ctx.otoroshiResponse))
  }
}

case class NgAccessContext(
  snowflake: String,
  request: RequestHeader,
  route: Route,
  user: Option[PrivateAppsUser],
  apikey: Option[ApiKey],
  config: JsValue,
  attrs: TypedMap,
  globalConfig: JsValue
)

sealed trait NgAccess
object NgAccess {
  case object NgAllowed extends NgAccess
  case class NgDenied(result: Result) extends NgAccess
}

trait NgAccessValidator extends NgNamedPlugin {
  def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess]
}
