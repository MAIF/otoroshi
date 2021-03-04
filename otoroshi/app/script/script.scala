package otoroshi.script

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import otoroshi.actions.ApiAction
import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import com.google.common.hash.Hashing
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.events.{AnalyticEvent, OtoroshiEvent}

import javax.script._
import otoroshi.models._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.libs.ws.{DefaultWSCookie, WSCookie}
import play.api.mvc._
import redis.RedisClientMasterSlaves
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.{SchedulerHelper, TypedMap}
import otoroshi.utils.config.ConfigUtils
import otoroshi.utils.syntax.implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

sealed trait PluginType {
  def name: String
}

object AppType extends PluginType {
  def name: String = "app"
}

object TransformerType extends PluginType {
  def name: String = "transformer"
}

object AccessValidatorType extends PluginType {
  def name: String = "validator"
}

object PreRoutingType extends PluginType {
  def name: String = "preroute"
}

object RequestSinkType extends PluginType {
  def name: String = "sink"
}

object EventListenerType extends PluginType {
  def name: String = "listener"
}

object JobType extends PluginType {
  def name: String = "job"
}

object DataExporterType extends PluginType {
  def name: String = "exporter"
}

trait StartableAndStoppable {
  val funit: Future[Unit]                                         = FastFuture.successful(())
  def startWithPluginId(pluginId: String, env: Env): Future[Unit] = start(env)
  def start(env: Env): Future[Unit]                               = FastFuture.successful(())
  def stop(env: Env): Future[Unit]                                = FastFuture.successful(())
}

trait NamedPlugin { self =>
  def pluginType: PluginType
  def name: String                = self.getClass.getName
  def description: Option[String] = None

  def defaultConfig: Option[JsObject] = None
  def configRoot: Option[String]      =
    defaultConfig match {
      case None                                   => None
      case Some(config) if config.value.size > 1  => None
      case Some(config) if config.value.isEmpty   => None
      case Some(config) if config.value.size == 1 => config.value.headOption.map(_._1)
    }

  def configSchema: Option[JsObject] =
    defaultConfig.flatMap(c => configRoot.map(r => (c \ r).asOpt[JsObject].getOrElse(Json.obj()))) match {
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
  def configFlow: Seq[String]        =
    defaultConfig.flatMap(c => configRoot.map(r => (c \ r).asOpt[JsObject].getOrElse(Json.obj()))) match {
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

  def jsonDescription(): JsObject =
    Try {
      Json.obj(
        "name"          -> name,
        "description"   -> description.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "defaultConfig" -> defaultConfig.getOrElse(JsNull).as[JsValue],
        "configRoot"    -> configRoot.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "configSchema"  -> configSchema.getOrElse(JsNull).as[JsValue],
        "configFlow"    -> JsArray(configFlow.map(JsString.apply))
      )
    } match {
      case Failure(ex) => Json.obj()
      case Success(s)  => s
    }
}

case class HttpRequest(
    url: String,
    method: String,
    headers: Map[String, String],
    cookies: Seq[WSCookie] = Seq.empty[WSCookie],
    version: String,
    clientCertificateChain: Option[Seq[X509Certificate]],
    target: Option[Target],
    claims: OtoroshiClaim
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

case class HttpResponse(status: Int, headers: Map[String, String], cookies: Seq[WSCookie] = Seq.empty[WSCookie]) {
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

trait ContextWithConfig {
  def index: Int
  def config: JsValue
  def globalConfig: JsValue
  def configExists(name: String): Boolean         =
    (config \ name).asOpt[JsValue].orElse((globalConfig \ name).asOpt[JsValue]).isDefined
  def configFor(name: String): JsValue            =
    configForOpt(name).getOrElse(Json.obj())
  def configForOpt(name: String): Option[JsValue] =
    (config \ name).asOpt[JsValue].orElse((globalConfig \ name).asOpt[JsValue])
  private def conf[A](prefix: String = "config-"): Option[JsValue] = {
    config match {
      case json: JsArray  => Option(json.value(index)).orElse((config \ s"$prefix$index").asOpt[JsValue])
      case json: JsObject => (json \ s"$prefix$index").asOpt[JsValue]
      case _              => None
    }
  }
  private def confAt[A](key: String, prefix: String = "config-")(implicit fjs: Reads[A]): Option[A] = {
    val conf = config match {
      case json: JsArray  => Option(json.value(index)).getOrElse((config \ s"$prefix$index").as[JsValue])
      case json: JsObject => (json \ s"$prefix$index").as[JsValue]
      case _              => Json.obj()
    }
    (conf \ key).asOpt[A]
  }
}

sealed trait TransformerContext extends ContextWithConfig {
  def index: Int
  def snowflake: String
  def descriptor: ServiceDescriptor
  def apikey: Option[ApiKey]
  def user: Option[PrivateAppsUser]
  def request: RequestHeader
  def config: JsValue
  def attrs: TypedMap
  def globalConfig: JsValue
  private def conf[A](prefix: String = "config-"): Option[JsValue] = {
    config match {
      case json: JsArray  => Option(json.value(index)).orElse((config \ s"$prefix$index").asOpt[JsValue])
      case json: JsObject => (json \ s"$prefix$index").asOpt[JsValue]
      case _              => None
    }
  }
  private def confAt[A](key: String, prefix: String = "config-")(implicit fjs: Reads[A]): Option[A] = {
    val conf = config match {
      case json: JsArray  => Option(json.value(index)).getOrElse((config \ s"$prefix$index").as[JsValue])
      case json: JsObject => (json \ s"$prefix$index").as[JsValue]
      case _              => Json.obj()
    }
    (conf \ key).asOpt[A]
  }
}

case class BeforeRequestContext(
    index: Int,
    snowflake: String,
    descriptor: ServiceDescriptor,
    request: RequestHeader,
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue = Json.obj()
) extends ContextWithConfig {}

case class AfterRequestContext(
    index: Int,
    snowflake: String,
    descriptor: ServiceDescriptor,
    request: RequestHeader,
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue = Json.obj()
) extends ContextWithConfig {}

case class TransformerRequestContext(
    rawRequest: HttpRequest,
    otoroshiRequest: HttpRequest,
    index: Int,
    snowflake: String,
    descriptor: ServiceDescriptor,
    apikey: Option[ApiKey],
    user: Option[PrivateAppsUser],
    request: RequestHeader,
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue = Json.obj()
) extends TransformerContext {}

case class TransformerResponseContext(
    rawResponse: HttpResponse,
    otoroshiResponse: HttpResponse,
    index: Int,
    snowflake: String,
    descriptor: ServiceDescriptor,
    apikey: Option[ApiKey],
    user: Option[PrivateAppsUser],
    request: RequestHeader,
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue = Json.obj()
) extends TransformerContext {}

case class TransformerRequestBodyContext(
    rawRequest: HttpRequest,
    otoroshiRequest: HttpRequest,
    body: Source[ByteString, Any],
    index: Int,
    snowflake: String,
    descriptor: ServiceDescriptor,
    apikey: Option[ApiKey],
    user: Option[PrivateAppsUser],
    request: RequestHeader,
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue = Json.obj()
) extends TransformerContext {}

case class TransformerResponseBodyContext(
    rawResponse: HttpResponse,
    otoroshiResponse: HttpResponse,
    body: Source[ByteString, Any],
    index: Int,
    snowflake: String,
    descriptor: ServiceDescriptor,
    apikey: Option[ApiKey],
    user: Option[PrivateAppsUser],
    request: RequestHeader,
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue = Json.obj()
) extends TransformerContext {}

case class TransformerErrorContext(
    index: Int,
    snowflake: String,
    message: String,
    otoroshiResult: Result,
    otoroshiResponse: HttpResponse,
    request: RequestHeader,
    maybeCauseId: Option[String],
    callAttempts: Int,
    descriptor: ServiceDescriptor,
    apikey: Option[ApiKey],
    user: Option[PrivateAppsUser],
    config: JsValue,
    globalConfig: JsValue = Json.obj(),
    attrs: TypedMap
) extends TransformerContext {}

trait RequestTransformer extends StartableAndStoppable with NamedPlugin with InternalEventListener {

  def pluginType: PluginType = TransformerType

  def beforeRequest(
      context: BeforeRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    FastFuture.successful(())
  }

  def afterRequest(
      context: AfterRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    FastFuture.successful(())
  }

  def transformErrorWithCtx(
      context: TransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    FastFuture.successful(context.otoroshiResult)
  }

  def transformRequestWithCtx(
      context: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    transformRequest(
      context.snowflake,
      context.rawRequest,
      context.otoroshiRequest,
      context.descriptor,
      context.apikey,
      context.user
    )(env, ec, mat)
  }

  def transformResponseWithCtx(
      context: TransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    transformResponse(
      context.snowflake,
      context.rawResponse,
      context.otoroshiResponse,
      context.descriptor,
      context.apikey,
      context.user
    )(env, ec, mat)
  }

  def transformRequestBodyWithCtx(
      context: TransformerRequestBodyContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    transformRequestBody(
      context.snowflake,
      context.body,
      context.rawRequest,
      context.otoroshiRequest,
      context.descriptor,
      context.apikey,
      context.user
    )(env, ec, mat)
  }

  def transformResponseBodyWithCtx(
      context: TransformerResponseBodyContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    transformResponseBody(
      context.snowflake,
      context.body,
      context.rawResponse,
      context.otoroshiResponse,
      context.descriptor,
      context.apikey,
      context.user
    )(env, ec, mat)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def transformRequestSync(
      snowflake: String,
      rawRequest: HttpRequest,
      otoroshiRequest: HttpRequest,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, HttpRequest] = {
    Right(otoroshiRequest)
  }

  def transformRequest(
      snowflake: String,
      rawRequest: HttpRequest,
      otoroshiRequest: HttpRequest,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    FastFuture.successful(
      transformRequestSync(snowflake, rawRequest, otoroshiRequest, desc, apiKey, user)(env, ec, mat)
    )
  }

  def transformResponseSync(
      snowflake: String,
      rawResponse: HttpResponse,
      otoroshiResponse: HttpResponse,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, HttpResponse] = {
    Right(otoroshiResponse)
  }

  def transformResponse(
      snowflake: String,
      rawResponse: HttpResponse,
      otoroshiResponse: HttpResponse,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    FastFuture.successful(
      transformResponseSync(snowflake, rawResponse, otoroshiResponse, desc, apiKey, user)(env, ec, mat)
    )
  }

  def transformRequestBody(
      snowflake: String,
      body: Source[ByteString, _],
      rawRequest: HttpRequest,
      otoroshiRequest: HttpRequest,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    body
  }

  def transformResponseBody(
      snowflake: String,
      body: Source[ByteString, _],
      rawResponse: HttpResponse,
      otoroshiResponse: HttpResponse,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    body
  }
}

object DefaultRequestTransformer extends RequestTransformer

object CompilingRequestTransformer extends RequestTransformer {
  override def transformRequestSync(
      snowflake: String,
      rawRequest: HttpRequest,
      otoroshiRequest: HttpRequest,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, HttpRequest] = {
    val accept = rawRequest.headers.get("Accept").getOrElse("text/html").split(",").toSeq.map(_.trim)
    if (accept.contains("text/html")) { // in a browser
      Left(Results.ServiceUnavailable("<h3>not ready yet, plugin is loading ...</h3>").as("text/html"))
    } else {
      Left(Results.ServiceUnavailable(Json.obj("error" -> "not ready yet, plugin is loading ...")))
    }
  }
}

trait NanoApp extends RequestTransformer {

  override def pluginType: PluginType = AppType

  private val awaitingRequests = new TrieMap[String, Promise[Source[ByteString, _]]]()

  override def beforeRequest(
      ctx: BeforeRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.putIfAbsent(ctx.snowflake, Promise[Source[ByteString, _]])
    funit
  }

  override def afterRequest(
      ctx: AfterRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.remove(ctx.snowflake)
    funit
  }

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    awaitingRequests.get(ctx.snowflake).map { promise =>
      val consumed                          = new AtomicBoolean(false)
      val bodySource: Source[ByteString, _] = Source
        .future(promise.future)
        .flatMapConcat(s => s)
        .alsoTo(Sink.onComplete {
          case _ => consumed.set(true)
        })
      route(ctx.rawRequest, bodySource).map { r =>
        if (!consumed.get()) bodySource.runWith(Sink.ignore)
        Left(r)
      }
    } getOrElse {
      FastFuture.successful(
        Left(Results.InternalServerError(Json.obj("error" -> s"no body promise found for ${ctx.snowflake}")))
      )
    }
  }

  override def transformRequestBodyWithCtx(
      ctx: TransformerRequestBodyContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    awaitingRequests.get(ctx.snowflake).map(_.trySuccess(ctx.body))
    ctx.body
  }

  def route(
      request: HttpRequest,
      body: Source[ByteString, _]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    FastFuture.successful(routeSync(request, body))
  }

  def routeSync(
      request: HttpRequest,
      body: Source[ByteString, _]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Result = {
    Results.Ok(Json.obj("message" -> "Hello World!"))
  }
}

class ScriptCompiler(env: Env) {

  private val logger     = Logger("otoroshi-script-compiler")
  private val scriptExec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  def compile(script: String): Future[Either[JsValue, AnyRef]] = {
    val start = System.currentTimeMillis()
    Future
      .apply {
        try {
          val engineManager = new ScriptEngineManager(env.environment.classLoader)
          val scriptEngine  = engineManager.getEngineByName("scala")
          val engine        = scriptEngine.asInstanceOf[ScriptEngine with Invocable]
          if (scriptEngine == null) {
            // dev mode
            Left(
              Json.obj(
                "line"       -> 0,
                "column"     -> 0,
                "file"       -> "",
                "rawMessage" -> "",
                "message"    -> "You are in dev mode, Scala script engine does not work inside sbt :("
              )
            )
          } else {
            val ctx = new SimpleScriptContext
            val res = engine.eval(script, ctx) // .asInstanceOf[RequestTransformer]
            ctx.getErrorWriter.flush()
            ctx.getWriter.flush()
            Right(res)
          }
        } catch {
          case ex: ScriptException =>
            val message = ex.getMessage.replace("in " + ex.getFileName, "")
            Left(
              Json.obj(
                "line"       -> ex.getLineNumber,
                "column"     -> ex.getColumnNumber,
                "file"       -> ex.getFileName,
                "rawMessage" -> ex.getMessage,
                "message"    -> message
              )
            )
          case ex: Throwable       =>
            logger.error(s"Compilation error", ex)
            Left(
              Json.obj(
                "line"       -> 0,
                "column"     -> 0,
                "file"       -> "none",
                "rawMessage" -> ex.getMessage,
                "message"    -> ex.getMessage
              )
            )
        }
      }(scriptExec)
      .andThen {
        case _ => logger.debug(s"Compilation process took ${(System.currentTimeMillis() - start).millis}")
      }(scriptExec)
  }
}

case class ScriptsState(compiling: Boolean, initialized: Boolean) {
  def json: JsValue =
    Json.obj(
      "compiling"   -> compiling,
      "initialized" -> initialized
    )
}

class ScriptManager(env: Env) {

  private implicit val ec   = env.otoroshiExecutionContext
  private implicit val _env = env

  private val cpScriptExec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))
  private val logger       = Logger("otoroshi-script-manager")
  private val updateRef    = new AtomicReference[Cancellable]()
  private val firstScan    = new AtomicBoolean(false)
  private val compiling    = new TrieMap[String, Unit]()
  private val cache        = new TrieMap[String, (String, PluginType, Any)]()
  private val cpCache      = new TrieMap[String, (PluginType, Any)]()
  private val cpTryCache   = new TrieMap[String, Unit]()

  private val listeningCpScripts = new AtomicReference[Seq[InternalEventListener]](Seq.empty)

  private val _firstPluginsSearchDone = new AtomicBoolean(false)
  private val _firstCompilationDone   = new AtomicBoolean(false)

  def firstPluginsSearchDone(): Boolean = _firstPluginsSearchDone.get()
  def firstCompilationDone(): Boolean   = _firstCompilationDone.get()

  val starting = System.currentTimeMillis()

  lazy val (transformersNames, validatorsNames, preRouteNames, reqSinkNames, listenerNames, jobNames, exporterNames) =
    Try {
      import io.github.classgraph.{ClassGraph, ClassInfo, ScanResult}

      import collection.JavaConverters._
      val start                     = System.currentTimeMillis()
      val confPackages: Seq[String] =
        env.configuration.getOptionalWithFileSupport[Seq[String]]("otoroshi.plugins.packages").getOrElse(Seq.empty) ++
        env.configuration
          .getOptionalWithFileSupport[String]("otoroshi.plugins.packagesStr")
          .map(v => v.split(",").map(_.trim).toSeq)
          .getOrElse(Seq.empty)
      val allPackages               = Seq("otoroshi", "otoroshi_plugins") ++ confPackages
      val scanResult: ScanResult    = new ClassGraph()
        .addClassLoader(env.environment.classLoader)
        .enableClassInfo()
        .whitelistPackages(allPackages: _*)
        .scan

      // val scanResult: ScanResult = new ClassGraph().addClassLoader(env.environment.classLoader).enableAllInfo.blacklistPackages(
      //   "java.*",
      //   "javax.*",
      //   "aix.*",
      //   "akka.*",
      //   "cats.*",
      //   "ch.qos.logback.*",
      //   "com.auth0.*",
      //   "com.blueconic.*",
      //   "com.carrotsearch.*",
      //   "com.codahale.*",
      //   "com.cronutils.*",
      //   "com.datastax.*",
      //   "com.esri.*",
      //   "com.fasterxml.jackson.*",
      //   "com.github.benmanes.*",
      //   "com.github.blemale.*",
      //   "com.github.luben.*",
      //   "com.google.*",
      //   "com.jayway.*",
      //   "com.jcabi.*",
      //   "com.kenai.*",
      //   "com.maxmind.*",
      //   "com.nimbusds.*",
      //   "com.risksense.*",
      //   "com.scurrilous.*",
      //   "com.sksamuel.*",
      //   "com.squareup.*",
      //   "com.sun.*",
      //   "com.thoughtworks.*",
      //   "com.twitter.*",
      //   "com.typesafe.*",
      //   "com.upokecenter.*",
      //   "com.yubico.*",
      //   "common.message.*",
      //   "diffson.*",
      //   "edu.umd.*",
      //   "external.reactivemongo.*",
      //   "github.gphat.censorinus.*",
      //   "google.*",
      //   "groovy.*",
      //   "groovyjarjarantlr.*",
      //   "groovyjarjarasm.*",
      //   "groovyjarjarcommonscli/*",
      //   "groovyjarjarpicocli.*",
      //   "groverconfig8491016507689653801.*",
      //   "io.airlift.*",
      //   "io.estatico.*",
      //   "io.github.*",
      //   "io.gsonfire.*",
      //   "io.jsonwebtoken.*",
      //   "io.kubernetes.*",
      //   "io.lettuce.*",
      //   "io.netty.*",
      //   "io.prometheus.*",
      //   "io.sundr.*",
      //   "io.swagger.*",
      //   "javax.*",
      //   "jni.*",
      //   "jnr.*",
      //   "kafka.*",
      //   "kaleidoscope.*",
      //   "linux.*",
      //   "macrocompat.*",
      //   "mozilla.*",
      //   "net.i2p.*",
      //   "net.jcip.*",
      //   "net.jpountz.*",
      //   "net.minidev.*",
      //   "net.objecthunter.*",
      //   "nonapi.io.github.*",
      //   "okhttp3.*",
      //   "okio.*",
      //   "org.apache.*",
      //   "org.aspectj.*",
      //   "org.bouncycastle.*",
      //   "org.checkerframework.*",
      //   "org.codehaus.*",
      //   "org.eclipse.*",
      //   "org.iq80.*",
      //   "org.javatuples.*",
      //   "org.joda.*",
      //   "org.jose4j.*",
      //   "org.json.*",
      //   "org.mindrot.*",
      //   "org.mortbay.*",
      //   "org.objectweb.*",
      //   "org.reactivestreams.*",
      //   "org.shredzone.*",
      //   "org.slf4j.*",
      //   "org.xbill.*",
      //   "org.xerial.*",
      //   "org.yaml.*",
      //   "play.*",
      //   "public.*",
      //   "reactivemongo.*",
      //   "reactor.*",
      //   "redis.*",
      //   "scala.*",
      //   "templates.*",
      //   "win.*",
      // ).scan

      logger.debug(s"classpath scanning in ${System.currentTimeMillis() - start} ms.")
      try {

        def predicate(c: ClassInfo): Boolean =
          c.getName == "otoroshi.script.DefaultRequestTransformer$" ||
          c.getName == "otoroshi.script.CompilingRequestTransformer$" ||
          c.getName == "otoroshi.script.CompilingValidator$" ||
          c.getName == "otoroshi.script.CompilingPreRouting$" ||
          c.getName == "otoroshi.script.CompilingRequestSink$" ||
          c.getName == "otoroshi.script.CompilingOtoroshiEventListener$" ||
          c.getName == "otoroshi.script.DefaultValidator$" ||
          c.getName == "otoroshi.script.DefaultPreRouting$" ||
          c.getName == "otoroshi.script.DefaultRequestSink$" ||
          c.getName == "otoroshi.script.FailingPreRoute" ||
          c.getName == "otoroshi.script.FailingPreRoute$" ||
          c.getName == "otoroshi.script.DefaultOtoroshiEventListener$" ||
          c.getName == "otoroshi.script.DefaultJob$" ||
          c.getName == "otoroshi.script.CompilingJob$" ||
          c.getName == "otoroshi.script.NanoApp" ||
          c.getName == "otoroshi.script.NanoApp$"

        val requestTransformers: Seq[String] = (scanResult.getSubclasses(classOf[RequestTransformer].getName).asScala ++
        scanResult.getClassesImplementing(classOf[RequestTransformer].getName).asScala)
          .filterNot(predicate)
          .map(_.getName)

        val validators: Seq[String] = (scanResult.getSubclasses(classOf[AccessValidator].getName).asScala ++
        scanResult.getClassesImplementing(classOf[AccessValidator].getName).asScala).filterNot(predicate).map(_.getName)

        val preRoutes: Seq[String] = (scanResult.getSubclasses(classOf[PreRouting].getName).asScala ++
        scanResult.getClassesImplementing(classOf[PreRouting].getName).asScala).filterNot(predicate).map(_.getName)

        val reqSinks: Seq[String] = (scanResult.getSubclasses(classOf[RequestSink].getName).asScala ++
        scanResult.getClassesImplementing(classOf[RequestSink].getName).asScala).filterNot(predicate).map(_.getName)

        val listenerNames: Seq[String] = (scanResult.getSubclasses(classOf[OtoroshiEventListener].getName).asScala ++
        scanResult.getClassesImplementing(classOf[OtoroshiEventListener].getName).asScala)
          .filterNot(predicate)
          .map(_.getName)

        val jobNames: Seq[String] = (scanResult.getSubclasses(classOf[Job].getName).asScala ++
        scanResult.getClassesImplementing(classOf[Job].getName).asScala)
          .filterNot(predicate)
          .map(_.getName)

        val customExporters: Seq[String] = (scanResult.getSubclasses(classOf[CustomDataExporter].getName).asScala ++
        scanResult.getClassesImplementing(classOf[CustomDataExporter].getName).asScala)
          .filterNot(predicate)
          .map(_.getName)

        (requestTransformers, validators, preRoutes, reqSinks, listenerNames, jobNames, customExporters)
      } catch {
        case e: Throwable =>
          e.printStackTrace()
          (
            Seq.empty[String],
            Seq.empty[String],
            Seq.empty[String],
            Seq.empty[String],
            Seq.empty[String],
            Seq.empty[String],
            Seq.empty[String]
          )
      } finally if (scanResult != null) scanResult.close()
    } getOrElse (Seq.empty[String], Seq.empty[String], Seq.empty[String], Seq.empty[String], Seq.empty[String], Seq
      .empty[String], Seq.empty[String])

  private val allPlugins   = Seq(
    transformersNames,
    validatorsNames,
    preRouteNames,
    reqSinkNames,
    listenerNames,
    jobNames,
    exporterNames
  ).flatten.distinct.sortWith((s1, s2) => s1.compareTo(s2) < 0)
  private val printPlugins =
    env.configuration.getOptionalWithFileSupport[Boolean]("otoroshi.plugins.print").getOrElse(false)
  logger.info(s"Found ${allPlugins.size} plugins in classpath (${System.currentTimeMillis() - starting} ms)")
  if (printPlugins) logger.info("\n\n" + allPlugins.mkString("\n") + "\n")

  def start(): ScriptManager = {
    if (env.scriptingEnabled) {
      updateRef.set(
        env.otoroshiScheduler.scheduleAtFixedRate(1.second, 10.second)(
          SchedulerHelper.runnable(updateScriptCache(firstScan.compareAndSet(false, true)))
        )(
          env.otoroshiExecutionContext
        )
      )
    }
    env.otoroshiScheduler.scheduleOnce(1.second)(initClasspathModules())(env.otoroshiExecutionContext)
    this
  }

  def stop(): Unit = {
    cache.foreach(s =>
      Try {
        s._2._3.asInstanceOf[StartableAndStoppable].stop(env)
        s._2._3.asInstanceOf[InternalEventListener].stopEvent(env)
      }
    )
    cpCache.foreach(s =>
      Try {
        s._2._2.asInstanceOf[StartableAndStoppable].stop(env)
        s._2._2.asInstanceOf[InternalEventListener].stopEvent(env)
      }
    )
    Option(updateRef.get()).foreach(_.cancel())
  }

  def state(): Future[ScriptsState] = {
    env.datastores.scriptDataStore.findAll().map { scripts =>
      val allCompiled = !scripts.forall(s => cache.contains(s.id))
      val initial     = if (scripts.isEmpty) true else _firstCompilationDone.get()
      ScriptsState(compiling.nonEmpty, initial)
    }
  }

  private def initClasspathModules(): Future[Unit] = {
    env.metrics.withTimerAsync("otoroshi.core.plugins.classpath-scanning-starting") {
      Future {
        logger.info("Finding and starting plugins ...")
        val start   = System.currentTimeMillis()
        val plugins = (transformersNames ++ validatorsNames ++ preRouteNames)
          .map(c => env.scriptManager.getAnyScript[NamedPlugin](s"cp:$c"))
          .collect {
            case Right(plugin) => plugin
          }
        listeningCpScripts.set(plugins.collect {
          case listener: InternalEventListener if listener.listening => listener
        })
        _firstPluginsSearchDone.compareAndSet(false, true)
        logger.info(s"Finding and starting plugins done in ${System.currentTimeMillis() - start} ms.")
        ()
      }(cpScriptExec)
    }
  }

  private def compileAndUpdate(script: Script, oldScript: Option[InternalEventListener]): Future[Unit] = {
    compiling.putIfAbsent(script.id, ()) match {
      case Some(_) => FastFuture.successful(()) // do nothing as something is compiling
      case None    => {
        logger.debug(s"Updating script ${script.name}")
        env.scriptCompiler.compile(script.code).map {
          case Left(err)    =>
            logger.error(s"Script ${script.name} with id ${script.id} does not compile: ${err}")
            compiling.remove(script.id)
            ()
          case Right(trans) => {
            Try {
              oldScript.foreach { i =>
                i.asInstanceOf[StartableAndStoppable].stop(env)
                i.stopEvent(env)
              }
            }
            Try {
              trans.asInstanceOf[StartableAndStoppable].startWithPluginId(script.id, env)
              trans.asInstanceOf[InternalEventListener].startEvent(script.id, env)
            }
            cache.put(script.id, (script.hash, script.`type`, trans))
            compiling.remove(script.id)
            ()
          }
        }
      }
    }
  }

  private def compileAndUpdateIfNeeded(script: Script): Future[Unit] = {
    (cache.get(script.id), compiling.get(script.id)) match {
      case (None, None)                             => compileAndUpdate(script, None)
      case (None, Some(_))                          => FastFuture.successful(()) // do nothing as something is compiling
      case (Some(_), Some(_))                       => FastFuture.successful(()) // do nothing as something is compiling
      case (Some(cs), None) if cs._1 != script.hash =>
        compileAndUpdate(script, Some(cs._3.asInstanceOf[InternalEventListener]))
      case (Some(_), None)                          => FastFuture.successful(()) // do nothing as script has not changed from cache
    }
  }

  private def updateScriptCache(first: Boolean = false): Future[Unit] = {
    env.metrics.withTimerAsync("otoroshi.core.plugins.update-scripts") {
      logger.debug(s"updateScriptCache")
      if (first) logger.info("Compiling and starting scripts ...")
      val start = System.currentTimeMillis()
      env.datastores.scriptDataStore
        .findAll()
        .flatMap { scripts =>
          val all: Future[Seq[Unit]] = Future.sequence(scripts.map(compileAndUpdateIfNeeded))
          val ids                    = scripts.map(_.id)
          cache.keySet.filterNot(id => ids.contains(id)).foreach(id => cache.remove(id))
          all.map(_ => ())
        }
        .andThen {
          case _ if first =>
            _firstCompilationDone.compareAndSet(false, true)
            logger.info(s"Compiling and starting scripts done in ${System.currentTimeMillis() - start} ms.")
        }
    }
  }

  def getScript(ref: String)(implicit ec: ExecutionContext): RequestTransformer = {
    getAnyScript[RequestTransformer](ref) match {
      case Left("compiling") => CompilingRequestTransformer
      case Left(_)           => DefaultRequestTransformer
      case Right(any)        => any.asInstanceOf[RequestTransformer]
    }
  }

  def getAnyScript[A](ref: String)(implicit ec: ExecutionContext): Either[String, A] = {
    ref match {
      case r if r.startsWith("cp:") => {
        if (!cpTryCache.contains(ref)) {
          Try(env.environment.classLoader.loadClass(r.replace("cp:", ""))) // .asSubclass(classOf[A]))
            .map(clazz => clazz.newInstance()) match {
            case Success(tr) =>
              cpTryCache.put(ref, ())
              val typ = tr.asInstanceOf[NamedPlugin].pluginType
              cpCache.put(ref, (typ, tr))
              Try {
                tr.asInstanceOf[StartableAndStoppable].startWithPluginId(r, env)
                tr.asInstanceOf[InternalEventListener].startEvent(r, env)
              }
            case Failure(e)  =>
              e.printStackTrace()
              logger.error(s"Classpath script `$ref` does not exists ...")
          }
        }
        cpCache.get(ref).flatMap(a => Option(a._2)) match {
          case Some(script) => Right(script.asInstanceOf[A])
          case None         => Left("not-in-cache")
        }
      }
      case r                        => {
        env.datastores.scriptDataStore.findById(ref).map {
          case Some(script) => compileAndUpdateIfNeeded(script)
          case None         =>
            logger.error(s"Script with id `$ref` does not exists ...")
          // do nothing as the script does not exists
        }
        cache.get(ref).flatMap(a => Option(Right(a._3.asInstanceOf[A]))).getOrElse {
          if (compiling.contains(ref)) {
            Left("compiling")
          } else {
            Left("not-in-cache")
          }
        }
      }
    }
  }

  def preCompileScript(script: Script)(implicit ec: ExecutionContext): Unit = {
    compileAndUpdateIfNeeded(script)
  }

  def removeScript(id: String): Unit = {
    cache.remove(id)
    compiling.remove(id)
  }

  def dispatchEvent(evt: OtoroshiEvent)(implicit ec: ExecutionContext): Unit = {
    if (env.useEventStreamForScriptEvents) {
      env.metrics.withTimer("otoroshi.core.proxy.event-dispatch") {
        env.analyticsActorSystem.eventStream.publish(evt)
      }
    } else {
      Future {
        env.metrics.withTimer("otoroshi.core.proxy.event-dispatch") {
          val pluginListeners = listeningCpScripts.get()
          if (pluginListeners.nonEmpty) {
            pluginListeners.foreach(l => l.onEvent(evt)(env))
          }
          val scriptListeners = cache.values.map(_._3).collect {
            case listener: InternalEventListener if listener.listening => listener
          }
          if (scriptListeners.nonEmpty) {
            scriptListeners.foreach(l => l.onEvent(evt)(env))
          }
        }
        evt
      }(ec)
    }
  }
}

object Implicits {

  implicit class ServiceDescriptorWithTransformer(val desc: ServiceDescriptor) extends AnyVal {

    def beforeRequest(
        ctx: BeforeRequestContext
    )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Done] = {
      env.metrics.withTimerAsync("otoroshi.core.proxy.before-request") {
        env.scriptingEnabled match {
          case true =>
            val plugs    = desc.plugins.requestTransformers(ctx.request)
            val gScripts = env.datastores.globalConfigDataStore.latestSafe
              .filter(_.scripts.enabled)
              .map(_.scripts)
              .getOrElse(GlobalScripts(transformersConfig = Json.obj()))
            val refs     = (plugs ++ gScripts.transformersRefs ++ desc.transformerRefs).distinct
            if (refs.nonEmpty) {
              Source(refs.toList.zipWithIndex).runForeach {
                case (ref, index) =>
                  env.scriptManager
                    .getScript(ref)
                    .beforeRequest(
                      ctx.copy(
                        index = index,
                        globalConfig = ConfigUtils.mergeOpt(
                          gScripts.transformersConfig,
                          env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
                        ),
                        config = ConfigUtils.merge(ctx.config, desc.plugins.config)
                      )
                    )(env, ec, mat)
              }
            } else {
              FastFuture.successful(Done)
            }
          case _    => FastFuture.successful(Done)
        }
      }
    }

    def afterRequest(
        ctx: AfterRequestContext
    )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Done] = {
      env.metrics.withTimerAsync("otoroshi.core.proxy.after-request") {
        env.scriptingEnabled match {
          case true =>
            val plugs    = desc.plugins.requestTransformers(ctx.request)
            val gScripts = env.datastores.globalConfigDataStore.latestSafe
              .filter(_.scripts.enabled)
              .map(_.scripts)
              .getOrElse(GlobalScripts(transformersConfig = Json.obj()))
            val refs     = (plugs ++ gScripts.transformersRefs ++ desc.transformerRefs).distinct
            if (refs.nonEmpty) {
              Source(refs.toList.zipWithIndex).runForeach {
                case (ref, index) =>
                  env.scriptManager
                    .getScript(ref)
                    .afterRequest(
                      ctx.copy(
                        index = index,
                        globalConfig = ConfigUtils.mergeOpt(
                          gScripts.transformersConfig,
                          env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
                        ),
                        config = ConfigUtils.merge(ctx.config, desc.plugins.config)
                      )
                    )(env, ec, mat)
              }
            } else {
              FastFuture.successful(Done)
            }
          case _    => FastFuture.successful(Done)
        }
      }
    }

    def transformRequest(
        context: TransformerRequestContext
    )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] =
      env.metrics.withTimerAsync("otoroshi.core.proxy.transform-request") {
        env.scriptingEnabled match {
          case true =>
            val plugs    = desc.plugins.requestTransformers(context.request)
            val gScripts = env.datastores.globalConfigDataStore.latestSafe
              .filter(_.scripts.enabled)
              .map(_.scripts)
              .getOrElse(GlobalScripts(transformersConfig = Json.obj()))
            val refs     = (plugs ++ gScripts.transformersRefs ++ desc.transformerRefs).distinct
            if (refs.nonEmpty) {
              val either: Either[Result, HttpRequest] = Right(context.otoroshiRequest)
              Source(refs.toList.zipWithIndex).runFoldAsync(either) {
                case (Left(badResult), (_, _))              => FastFuture.successful(Left(badResult))
                case (Right(lastHttpRequest), (ref, index)) =>
                  env.scriptManager
                    .getScript(ref)
                    .transformRequestWithCtx(
                      context.copy(
                        otoroshiRequest = lastHttpRequest,
                        index = index,
                        globalConfig = ConfigUtils.mergeOpt(
                          gScripts.transformersConfig,
                          env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
                        ),
                        config = ConfigUtils.merge(context.config, desc.plugins.config)
                      )
                    )(env, ec, mat)
              }
            } else {
              FastFuture.successful(Right(context.otoroshiRequest))
            }
          case _    => FastFuture.successful(Right(context.otoroshiRequest))
        }
      }

    def transformResponse(
        context: TransformerResponseContext
    )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] =
      env.metrics.withTimerAsync("otoroshi.core.proxy.transform-response") {
        env.scriptingEnabled match {
          case true =>
            val plugs    = desc.plugins.requestTransformers(context.request)
            val gScripts = env.datastores.globalConfigDataStore.latestSafe
              .filter(_.scripts.enabled)
              .map(_.scripts)
              .getOrElse(GlobalScripts(transformersConfig = Json.obj()))
            val refs     = (plugs ++ gScripts.transformersRefs ++ desc.transformerRefs).distinct
            if (refs.nonEmpty) {
              val either: Either[Result, HttpResponse] = Right(context.otoroshiResponse)
              Source(refs.toList.zipWithIndex).runFoldAsync(either) {
                case (Left(badResult), _)                    => FastFuture.successful(Left(badResult))
                case (Right(lastHttpResponse), (ref, index)) =>
                  env.scriptManager
                    .getScript(ref)
                    .transformResponseWithCtx(
                      context.copy(
                        otoroshiResponse = lastHttpResponse,
                        index = index,
                        globalConfig = ConfigUtils.mergeOpt(
                          gScripts.transformersConfig,
                          env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
                        ),
                        config = ConfigUtils.merge(context.config, desc.plugins.config)
                      )
                    )(env, ec, mat)
              }
            } else {
              FastFuture.successful(Right(context.otoroshiResponse))
            }
          case _    => FastFuture.successful(Right(context.otoroshiResponse))
        }
      }

    def transformError(
        context: TransformerErrorContext
    )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] =
      env.metrics.withTimerAsync("otoroshi.core.proxy.transform-error") {
        env.scriptingEnabled match {
          case true =>
            val plugs    = desc.plugins.requestTransformers(context.request)
            val gScripts = env.datastores.globalConfigDataStore.latestSafe
              .filter(_.scripts.enabled)
              .map(_.scripts)
              .getOrElse(GlobalScripts(transformersConfig = Json.obj()))
            val refs     = (plugs ++ gScripts.transformersRefs ++ desc.transformerRefs).distinct
            if (refs.nonEmpty) {
              val result: Result = context.otoroshiResult
              Source(refs.toList.zipWithIndex).runFoldAsync(result) {
                case (lastResult, (ref, index)) =>
                  env.scriptManager
                    .getScript(ref)
                    .transformErrorWithCtx(
                      context.copy(
                        otoroshiResult = lastResult,
                        otoroshiResponse = HttpResponse(
                          lastResult.header.status,
                          lastResult.header.headers,
                          lastResult.newCookies.map(c =>
                            DefaultWSCookie(
                              name = c.name,
                              value = c.value,
                              domain = c.domain,
                              path = Option(c.path),
                              maxAge = c.maxAge.map(_.toLong),
                              secure = c.secure,
                              httpOnly = c.httpOnly
                            )
                          )
                        ),
                        index = index,
                        globalConfig = ConfigUtils.mergeOpt(
                          gScripts.transformersConfig,
                          env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
                        ),
                        config = ConfigUtils.merge(context.config, desc.plugins.config)
                      )
                    )(env, ec, mat)
              }
            } else {
              FastFuture.successful(context.otoroshiResult)
            }
          case _    => FastFuture.successful(context.otoroshiResult)
        }
      }

    def transformRequestBody(
        context: TransformerRequestBodyContext
    )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, Any] =
      env.metrics.withTimer("otoroshi.core.proxy.transform-request-body") {
        env.scriptingEnabled match {
          case true =>
            val plugs    = desc.plugins.requestTransformers(context.request)
            val gScripts = env.datastores.globalConfigDataStore.latestSafe
              .filter(_.scripts.enabled)
              .map(_.scripts)
              .getOrElse(GlobalScripts(transformersConfig = Json.obj()))
            val refs     = (plugs ++ gScripts.transformersRefs ++ desc.transformerRefs).distinct
            if (refs.nonEmpty) {
              Source.futureSource(Source(refs.toList.zipWithIndex).runFold(context.body) {
                case (body, (ref, index)) =>
                  env.scriptManager
                    .getScript(ref)
                    .transformRequestBodyWithCtx(
                      context.copy(
                        body = body,
                        index = index,
                        globalConfig = ConfigUtils.mergeOpt(
                          gScripts.transformersConfig,
                          env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
                        ),
                        config = ConfigUtils.merge(context.config, desc.plugins.config)
                      )
                    )(env, ec, mat)
              })
            } else {
              context.body
            }
          case _    => context.body
        }
      }

    def transformResponseBody(
        context: TransformerResponseBodyContext
    )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, Any] =
      env.metrics.withTimer("otoroshi.core.proxy.transform-response-body") {
        env.scriptingEnabled match {
          case true =>
            val plugs    = desc.plugins.requestTransformers(context.request)
            val gScripts = env.datastores.globalConfigDataStore.latestSafe
              .filter(_.scripts.enabled)
              .map(_.scripts)
              .getOrElse(GlobalScripts(transformersConfig = Json.obj()))
            val refs     = (plugs ++ gScripts.transformersRefs ++ desc.transformerRefs).distinct
            if (refs.nonEmpty) {
              Source.futureSource(Source(refs.toList.zipWithIndex).runFold(context.body) {
                case (body, (ref, index)) =>
                  env.scriptManager
                    .getScript(ref)
                    .transformResponseBodyWithCtx(
                      context.copy(
                        body = body,
                        index = index,
                        globalConfig = ConfigUtils.mergeOpt(
                          gScripts.transformersConfig,
                          env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
                        ),
                        config = ConfigUtils.merge(context.config, desc.plugins.config)
                      )
                    )(env, ec, mat)
              })
            } else {
              context.body
            }
          case _    => context.body
        }
      }
  }
}

case class Script(
    id: String,
    name: String,
    desc: String,
    code: String,
    `type`: PluginType,
    metadata: Map[String, String],
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends otoroshi.models.EntityLocationSupport {
  def save()(implicit ec: ExecutionContext, env: Env)   = env.datastores.scriptDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.scriptDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.scriptDataStore.exists(this)
  def toJson                                            = Script.toJson(this)
  def hash: String                                      = Hashing.sha256().hashString(code, StandardCharsets.UTF_8).toString
  def json: JsValue                                     = toJson
  def internalId: String                                = id
}

object Script {

  lazy val logger = Logger("otoroshi-script")

  val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

  val _fmt: Format[Script]                                                                  = new Format[Script] {
    override def writes(apk: Script): JsValue           =
      apk.location.jsonWithKey ++ Json.obj(
        "id"       -> apk.id,
        "name"     -> apk.name,
        "desc"     -> apk.desc,
        "code"     -> apk.code,
        "type"     -> apk.`type`.name,
        "metadata" -> apk.metadata
      )
    override def reads(json: JsValue): JsResult[Script] =
      Try {
        val scriptType = (json \ "type").asOpt[String].getOrElse("transformer") match {
          case "app"         => AppType
          case "transformer" => TransformerType
          case "validator"   => AccessValidatorType
          case "preroute"    => PreRoutingType
          case "sink"        => RequestSinkType
          case "job"         => JobType
          case "exporter"    => DataExporterType
          case _             => TransformerType
        }
        Script(
          location = otoroshi.models.EntityLocation.readFromKey(json),
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").as[String],
          code = (json \ "code").as[String],
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          `type` = scriptType
        )
      } map {
        case sd => JsSuccess(sd)
      } recover {
        case t =>
          logger.error("Error while reading Script", t)
          JsError(t.getMessage)
      } get
  }
  def toJson(value: Script): JsValue                                                        = _fmt.writes(value)
  def fromJsons(value: JsValue): Script                                                     =
    try {
      _fmt.reads(value).get
    } catch {
      case e: Throwable => {
        logger.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }
  def fromJsonSafe(value: JsValue): Either[Seq[(JsPath, Seq[JsonValidationError])], Script] = _fmt.reads(value).asEither
}

trait ScriptDataStore extends BasicStore[Script] {
  def template: Script =
    Script(
      id = IdGenerator.token,
      name = "New request transformer",
      desc = "New request transformer",
      code = """import akka.stream.Materializer
             |import otoroshi.env.Env
             |import otoroshi.models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
             |import otoroshi.script._
             |import play.api.Logger
             |import play.api.mvc.{Result, Results}
             |import scala.util._
             |import scala.concurrent.{ExecutionContext, Future}
             |import otoroshi.utils.syntax.implicits._
             |
             |/**
             | * Your own request transformer
             | */
             |class MyTransformer extends RequestTransformer {
             |
             |  val logger = Logger("my-transformer")
             |
             |  override def transformRequestWithCtx(
             |    ctx: TransformerRequestContext
             |  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
             |    logger.info(s"Request incoming with id: ${ctx.snowflake}")
             |    // Here add a new header to the request between otoroshi and the target
             |    Right(ctx.otoroshiRequest.copy(
             |      headers = ctx.otoroshiRequest.headers + ("Hello" -> "World")
             |    )).future
             |  }
             |}
             |
             |// don't forget to return an instance of the transformer to make it work
             |new MyTransformer()
           """.stripMargin,
      `type` = TransformerType,
      metadata = Map.empty
    )
}

class KvScriptDataStore(redisCli: RedisLike, _env: Env) extends ScriptDataStore with RedisLikeStore[Script] {
  override def fmt: Format[Script]                     = Script._fmt
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): Key                    = Key(s"${_env.storageRoot}:scripts:$id")
  override def extractId(value: Script): String        = value.id
}
