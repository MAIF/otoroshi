package otoroshi.script

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import actions.ApiAction
import akka.actor.Cancellable
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import com.google.common.hash.Hashing
import env.Env
import javax.script._
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.libs.ws.WSCookie
import play.api.mvc._
import redis.RedisClientMasterSlaves
import storage.redis.RedisStore
import storage.{BasicStore, RedisLike, RedisLikeStore}
import utils.TypedMap

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

case class HttpRequest(url: String,
                       method: String,
                       headers: Map[String, String],
                       cookies: Seq[WSCookie] = Seq.empty[WSCookie],
                       version: String,
                       clientCertificateChain: Option[Seq[X509Certificate]]) {
  lazy val contentType: Option[String] = headers.get("Content-Type")
  lazy val host: String                = headers.getOrElse("Host", "")
  lazy val uri: Uri                    = Uri(url)
  lazy val scheme: String              = uri.scheme
  lazy val authority: Uri.Authority    = uri.authority
  lazy val fragment: Option[String]    = uri.fragment
  lazy val path: String                = uri.path.toString()
  lazy val queryString: Option[String] = uri.rawQueryString
  lazy val relativeUri: String         = uri.toRelative.toString()
}

case class HttpResponse(status: Int, headers: Map[String, String], cookies: Seq[WSCookie] = Seq.empty[WSCookie])

sealed trait TransformerContext {
  def index: Int
  def snowflake: String
  def descriptor: ServiceDescriptor
  def apikey: Option[ApiKey]
  def user: Option[PrivateAppsUser]
  def request: RequestHeader
  def config: JsValue
  def attrs: TypedMap
  def globalConfig: JsValue
  // TODO: add user-agent infos
  // TODO: add client geoloc infos
  def conf[A](prefix: String = "config-"): Option[JsValue] = {
    config match {
      case json: JsArray => Option(json.value(index)).orElse((config \ s"$prefix$index").asOpt[JsValue])
      case json: JsObject => (json \ s"$prefix$index").asOpt[JsValue]
      case _ => None
    }
  }
  def confAt[A](key: String, prefix: String = "config-")(implicit fjs: Reads[A]): Option[A] = {
    val conf = config match {
      case json: JsArray => Option(json.value(index)).getOrElse((config \ s"$prefix$index").as[JsValue])
      case json: JsObject => (json \ s"$prefix$index").as[JsValue]
      case _ => Json.obj()
    }
    (conf \ key).asOpt[A]
  }
}

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

trait RequestTransformer {

  def transformRequestWithCtx(context: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    transformRequest(context.snowflake, context.rawRequest, context.otoroshiRequest, context.descriptor, context.apikey, context.user)(env, ec, mat)
  }

  def transformResponseWithCtx(context: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    transformResponse(context.snowflake, context.rawResponse, context.otoroshiResponse, context.descriptor, context.apikey, context.user)(env, ec, mat)
  }

  def transformRequestBodyWithCtx(context: TransformerRequestBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    transformRequestBody(context.snowflake, context.body, context.rawRequest, context.otoroshiRequest, context.descriptor, context.apikey, context.user)(env, ec, mat)
  }

  def transformResponseBodyWithCtx(context: TransformerResponseBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    transformResponseBody(context.snowflake, context.body, context.rawResponse, context.otoroshiResponse, context.descriptor, context.apikey, context.user)(env, ec, mat)
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
      Left(Results.InternalServerError("<h3>not ready yet ...</h3>"))
    } else {
      Left(Results.InternalServerError(Json.obj("error" -> "not ready yet ...")))
    }
  }
}

trait NanoApp extends RequestTransformer {

  private val awaitingRequests = new TrieMap[String, Promise[Source[ByteString, _]]]()

  override def transformRequest(
      snowflake: String,
      rawRequest: HttpRequest,
      otoroshiRequest: HttpRequest,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val promise = Promise[Source[ByteString, _]]
    awaitingRequests.put(snowflake, promise)
    val bodySource: Source[ByteString, _] = Source.fromFuture(promise.future).flatMapConcat(s => s)
    route(rawRequest, bodySource).map(r => Left(r))
  }

  override def transformRequestBody(
      snowflake: String,
      body: Source[ByteString, _],
      rawRequest: HttpRequest,
      otoroshiRequest: HttpRequest,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    awaitingRequests.get(snowflake).map(_.trySuccess(body))
    awaitingRequests.remove(snowflake)
    body
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
            val res = engine.eval(script, ctx)// .asInstanceOf[RequestTransformer]
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
          case ex: Throwable =>
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

class ScriptManager(env: Env) {

  private implicit val ec   = env.otoroshiExecutionContext
  private implicit val _env = env

  private val logger     = Logger("otoroshi-script-manager")
  private val updateRef  = new AtomicReference[Cancellable]()
  private val compiling  = new TrieMap[String, Unit]()
  private val cache      = new TrieMap[String, (String, ScriptType, Any)]()
  private val cpCache    = new TrieMap[String, (ScriptType, Any)]()
  private val cpTryCache = new TrieMap[String, Unit]()

  def start(): ScriptManager = {
    if (env.scriptingEnabled) {
      updateRef.set(
        env.otoroshiScheduler.schedule(1.second, 10.second)(updateScriptCache())(env.otoroshiExecutionContext)
      )
    }
    this
  }

  def stop(): Unit = {
    Option(updateRef.get()).foreach(_.cancel())
  }

  private def compileAndUpdate(script: Script): Unit = {
    compiling.putIfAbsent(script.id, ()) match {
      case Some(_) => // do nothing as something is compiling
      case None => {
        logger.debug(s"Updating script ${script.name}")
        env.scriptCompiler.compile(script.code).map {
          case Left(err) =>
            logger.error(s"Script ${script.name} with id ${script.id} does not compile: ${err}")
            compiling.remove(script.id)
          case Right(trans) => {
            cache.put(script.id, (script.hash, script.`type`, trans))
            compiling.remove(script.id)
          }
        }
      }
    }
  }

  private def compileAndUpdateIfNeeded(script: Script): Unit = {
    (cache.get(script.id), compiling.get(script.id)) match {
      case (None, None)                             => compileAndUpdate(script)
      case (None, Some(_))                          => // do nothing as something is compiling
      case (Some(_), Some(_))                       => // do nothing as something is compiling
      case (Some(cs), None) if cs._1 != script.hash => compileAndUpdate(script)
      case (Some(_), None)                          => // do nothing as script has not changed from cache
    }
  }

  private def updateScriptCache(): Unit = {
    logger.debug(s"updateScriptCache")
    env.datastores.scriptDataStore.findAll().map { scripts =>
      scripts.foreach(compileAndUpdateIfNeeded)
      val ids = scripts.map(_.id)
      cache.keySet.filterNot(id => ids.contains(id)).foreach(id => cache.remove(id))
    }
  }

  def getScript(ref: String)(implicit ec: ExecutionContext): RequestTransformer = {
    getAnyScript[RequestTransformer](ref) match {
      case Left("compiling")    => CompilingRequestTransformer
      case Left(_)              => DefaultRequestTransformer
      case Right(any)           => any.asInstanceOf[RequestTransformer]
    }
    // ref match {
    //   case r if r.startsWith("cp:") => {
    //     if (!cpTryCache.contains(ref)) {
    //       Try(env.environment.classLoader.loadClass(r.replace("cp:", "")).asSubclass(classOf[RequestTransformer]))
    //         .map(clazz => clazz.newInstance()) match {
    //         case Success(tr) =>
    //           cpTryCache.put(ref, ())
    //           cpCache.put(ref, (TransformerType, tr))
    //         case Failure(e) => logger.error(s"Classpath transformer `$ref` does not exists ...")
    //       }
    //     }
    //     cpCache.get(ref).flatMap(a => Option(a)).getOrElse(DefaultRequestTransformer)
    //   }
    //   case r => {
    //     env.datastores.scriptDataStore.findById(ref).map {
    //       case Some(script) => compileAndUpdateIfNeeded(script)
    //       case None =>
    //         logger.error(s"Script with id `$ref` does not exists ...")
    //       // do nothing as the script does not exists
    //     }
    //     cache.get(ref).flatMap(a => Option(a._2)).getOrElse {
    //       if (compiling.contains(ref)) {
    //         CompilingRequestTransformer
    //       } else {
    //         DefaultRequestTransformer
    //       }
    //     }
    //   }
    // }
  }

  def getAnyScript[A](ref: String)(implicit ec: ExecutionContext): Either[String, A] = {
    ref match {
      case r if r.startsWith("cp:") => {
        if (!cpTryCache.contains(ref)) {
          Try(env.environment.classLoader.loadClass(r.replace("cp:", "")))// .asSubclass(classOf[A]))
            .map(clazz => clazz.newInstance()) match {
            case Success(tr) =>
              cpTryCache.put(ref, ())
              val typ: ScriptType = tr match {
                case _: NanoApp => AppType
                case _: RequestTransformer => TransformerType
                case _: AccessValidator => AccessValidatorType
                case _ => TransformerType
              }
              cpCache.put(ref, (typ, tr))
            case Failure(e) =>
              logger.error(s"Classpath script `$ref` does not exists ...")
          }
        }
        cpCache.get(ref).flatMap(a => Option(a._2)) match {
          case Some(script) => Right(script.asInstanceOf[A])
          case None => Left("not-in-cache")
        }
      }
      case r => {
        env.datastores.scriptDataStore.findById(ref).map {
          case Some(script) => compileAndUpdateIfNeeded(script)
          case None =>
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
}

object Implicits {

  implicit class ServiceDescriptorWithTransformer(val desc: ServiceDescriptor) extends AnyVal {

    //def transformRequest(
    //    snowflake: String,
    //    rawRequest: HttpRequest,
    //    otoroshiRequest: HttpRequest,
    //    desc: ServiceDescriptor,
    //    apiKey: Option[ApiKey] = None,
    //    user: Option[PrivateAppsUser] = None,
    //)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    def transformRequest(context: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
      env.scriptingEnabled match {
        case true =>
          if (desc.transformerRefs.nonEmpty) {
            val gScripts = env.datastores.globalConfigDataStore.latestSafe.filter(_.scripts.enabled).map(_.scripts).getOrElse(GlobalScripts(transformersConfig = Json.obj()))
            val refs = gScripts.transformersRefs ++ desc.transformerRefs
            val either: Either[Result, HttpRequest] = Right(context.otoroshiRequest)
            Source(refs.toList.zipWithIndex).runFoldAsync(either) {
              case (Left(badResult), (_, _)) => FastFuture.successful(Left(badResult))
              case (Right(lastHttpRequest), (ref, index)) =>
                env.scriptManager
                  .getScript(ref)
                  .transformRequestWithCtx(context.copy(otoroshiRequest = lastHttpRequest, index = index, config = context.config, globalConfig = gScripts.transformersConfig))(env, ec, mat)
            }
            // desc.transformerRef match {
            //   case Some(ref) =>
            //     env.scriptManager
            //       .getScript(ref)
            //       .transformRequest(snowflake, rawRequest, otoroshiRequest, desc, apiKey, user)(env, ec, mat)
            //   case None => FastFuture.successful(Right(otoroshiRequest))
            // }
          } else {
            FastFuture.successful(Right(context.otoroshiRequest))
          }
        case _ => FastFuture.successful(Right(context.otoroshiRequest))
      }
    }

    // def transformResponse(
    //     snowflake: String,
    //     rawResponse: HttpResponse,
    //     otoroshiResponse: HttpResponse,
    //     desc: ServiceDescriptor,
    //     apiKey: Option[ApiKey] = None,
    //     user: Option[PrivateAppsUser] = None
    // )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    def transformResponse(context: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
      env.scriptingEnabled match {
        case true =>
          if (desc.transformerRefs.nonEmpty) {
            val gScripts = env.datastores.globalConfigDataStore.latestSafe.filter(_.scripts.enabled).map(_.scripts).getOrElse(GlobalScripts(transformersConfig = Json.obj()))
            val refs = gScripts.transformersRefs ++ desc.transformerRefs
            val either: Either[Result, HttpResponse] = Right(context.otoroshiResponse)
            Source(refs.toList.zipWithIndex).runFoldAsync(either) {
              case (Left(badResult), _) => FastFuture.successful(Left(badResult))
              case (Right(lastHttpResponse), (ref, index)) =>
                env.scriptManager
                  .getScript(ref)
                  .transformResponseWithCtx(context.copy(otoroshiResponse = lastHttpResponse, index = index, config = context.config, globalConfig = gScripts.transformersConfig))(env, ec, mat)
            }
            // desc.transformerRef match {
            //   case Some(ref) =>
            //     env.scriptManager
            //       .getScript(ref)
            //       .transformResponse(snowflake, rawResponse, otoroshiResponse, desc, apiKey, user)(env, ec, mat)
            //   case None => FastFuture.successful(Right(otoroshiResponse))
            // }
          } else {
            FastFuture.successful(Right(context.otoroshiResponse))
          }
        case _ => FastFuture.successful(Right(context.otoroshiResponse))
      }
    }

    //def transformRequestBody(
    //    snowflake: String,
    //    body: Source[ByteString, Any],
    //    rawRequest: HttpRequest,
    //    otoroshiRequest: HttpRequest,
    //    desc: ServiceDescriptor,
    //    apiKey: Option[ApiKey] = None,
    //    user: Option[PrivateAppsUser] = None
    //)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, Any] = {
    def transformRequestBody(context: TransformerRequestBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, Any] = {
      env.scriptingEnabled match {
        case true =>
          if (desc.transformerRefs.nonEmpty) {
            val gScripts = env.datastores.globalConfigDataStore.latestSafe.filter(_.scripts.enabled).map(_.scripts).getOrElse(GlobalScripts(transformersConfig = Json.obj()))
            val refs = gScripts.transformersRefs ++ desc.transformerRefs
            Source.fromFutureSource(Source(refs.toList.zipWithIndex).runFold(context.body) {
              case (body, (ref, index)) =>
                env.scriptManager
                  .getScript(ref)
                  .transformRequestBodyWithCtx(context.copy(body = body, index = index, config = context.config, globalConfig = gScripts.transformersConfig))(env, ec, mat)
            })
            // desc.transformerRef match {
            //   case Some(ref) =>
            //     env.scriptManager
            //       .getScript(ref)
            //       .transformRequestBody(snowflake, body, rawRequest, otoroshiRequest, desc, apiKey, user)(env, ec, mat)
            //   case None => body
            // }
          } else {
            context.body
          }
        case _ => context.body
      }
    }

    //def transformResponseBody(
    //    snowflake: String,
    //    body: Source[ByteString, Any],
    //    rawResponse: HttpResponse,
    //    otoroshiResponse: HttpResponse,
    //    desc: ServiceDescriptor,
    //    apiKey: Option[ApiKey] = None,
    //    user: Option[PrivateAppsUser] = None
    //)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, Any] = {
    def transformResponseBody(context: TransformerResponseBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, Any] = {
      env.scriptingEnabled match {
        case true =>
          if (desc.transformerRefs.nonEmpty) {
            val gScripts = env.datastores.globalConfigDataStore.latestSafe.filter(_.scripts.enabled).map(_.scripts).getOrElse(GlobalScripts(transformersConfig = Json.obj()))
            val refs = gScripts.transformersRefs ++ desc.transformerRefs
            Source.fromFutureSource(Source(refs.toList.zipWithIndex).runFold(context.body) {
              case (body, (ref, index)) =>
                env.scriptManager
                  .getScript(ref)
                  .transformResponseBodyWithCtx(context.copy(body = body, index = index, config = context.config, globalConfig = gScripts.transformersConfig))(env, ec, mat)
            })
            // desc.transformerRef match {
            //   case Some(ref) =>
            //     env.scriptManager
            //       .getScript(ref)
            //       .transformResponseBody(snowflake, body, rawResponse, otoroshiResponse, desc, apiKey, user)(env, ec, mat)
            //   case None => body
            // }
          } else {
            context.body
          }
        case _ => context.body
      }
    }
  }
}

sealed trait ScriptType {
  def name: String
}

object AppType extends ScriptType {
  def name: String = "app"
}


object TransformerType extends ScriptType {
  def name: String = "transformer"
}

object AccessValidatorType extends ScriptType {
  def name: String = "validator"
}

case class Script(id: String, name: String, desc: String, code: String, `type`: ScriptType) {
  def save()(implicit ec: ExecutionContext, env: Env)   = env.datastores.scriptDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.scriptDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.scriptDataStore.exists(this)
  def toJson                                            = Script.toJson(this)
  def hash: String                                      = Hashing.sha256().hashString(code, StandardCharsets.UTF_8).toString
}

object Script {

  lazy val logger = Logger("otoroshi-script")

  val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

  val _fmt: Format[Script] = new Format[Script] {
    override def writes(apk: Script): JsValue = Json.obj(
      "id"   -> apk.id,
      "name" -> apk.name,
      "desc" -> apk.desc,
      "code" -> apk.code,
      "type" -> apk.`type`.name
    )
    override def reads(json: JsValue): JsResult[Script] =
      Try {
        val scriptType = (json \ "type").asOpt[String].getOrElse("transformer") match {
          case "app" =>         AppType
          case "transformer" => TransformerType
          case "validator" =>   AccessValidatorType
          case _ =>             TransformerType
        }
        Script(
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").as[String],
          code = (json \ "code").as[String],
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
  def toJson(value: Script): JsValue = _fmt.writes(value)
  def fromJsons(value: JsValue): Script =
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

trait ScriptDataStore extends BasicStore[Script]

class InMemoryScriptDataStore(redisCli: RedisLike, _env: Env) extends ScriptDataStore with RedisLikeStore[Script] {
  override def fmt: Format[Script]                     = Script._fmt
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): Key                    = Key(s"${_env.storageRoot}:scripts:$id")
  override def extractId(value: Script): String        = value.id
}

class RedisScriptDataStore(redisCli: RedisClientMasterSlaves, _env: Env)
    extends ScriptDataStore
    with RedisStore[Script] {
  override def fmt: Format[Script]                                = Script._fmt
  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def key(id: String): Key                               = Key(s"${_env.storageRoot}:scripts:$id")
  override def extractId(value: Script): String                   = value.id
}

class ScriptApiController(ApiAction: ApiAction, cc: ControllerComponents)(
    implicit env: Env
) extends AbstractController(cc) {

  import gnieh.diffson.playJson._
  import utils.future.Implicits._

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val logger = Logger("otoroshi-scripts-api")

  val sourceBodyParser = BodyParser("scripts-parsers") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def OnlyIfScriptingEnabled(f: => Future[Result]): Future[Result] = {
    env.scriptingEnabled match {
      case true  => f
      case false => InternalServerError(Json.obj("error" -> "Scripting not enabled !")).asFuture
    }
  }

  def findAllScripts() = ApiAction.async { ctx =>
    OnlyIfScriptingEnabled {
      env.datastores.scriptDataStore.findAll().map(all => Ok(JsArray(all.map(_.toJson))))
    }
  }

  private lazy val transformersNames: Seq[String] = Try {
    import io.github.classgraph.{ClassGraph, ClassInfoList, ScanResult}

    import collection.JavaConverters._
    val scanResult: ScanResult = new ClassGraph()
      .addClassLoader(env.environment.classLoader)
      .enableAllInfo
      .blacklistPackages("java.*", "javax.*")
      .scan
    try {
      val controlClasses1: ClassInfoList = scanResult.getSubclasses(classOf[RequestTransformer].getName)
      val controlClasses2: ClassInfoList = scanResult.getClassesImplementing(classOf[RequestTransformer].getName)

      val classes                        = controlClasses1.asScala ++ controlClasses2.asScala
      classes
        .filterNot(
          c => c.getName == "otoroshi.script.DefaultRequestTransformer$" ||
            c.getName == "otoroshi.script.CompilingRequestTransformer$" ||
            c.getName == "otoroshi.script.CompilingValidator$" ||
            c.getName == "otoroshi.script.DefaultValidator$" ||
            c.getName == "otoroshi.script.NanoApp" ||
            c.getName == "otoroshi.script.NanoApp$"

        )
        .map(c => c.getName)
    } catch {
      case e: Throwable =>
        e.printStackTrace()
        Seq.empty[String]
    } finally if (scanResult != null) scanResult.close()
  } getOrElse Seq.empty[String]

  private lazy val validatorsNames: Seq[String] = Try {
    import io.github.classgraph.{ClassGraph, ClassInfoList, ScanResult}

    import collection.JavaConverters._
    val scanResult: ScanResult = new ClassGraph()
      .addClassLoader(env.environment.classLoader)
      .enableAllInfo
      .blacklistPackages("java.*", "javax.*")
      .scan
    try {

      val controlClasses3: ClassInfoList = scanResult.getSubclasses(classOf[AccessValidator].getName)
      val controlClasses4: ClassInfoList = scanResult.getClassesImplementing(classOf[AccessValidator].getName)

      val classes                        = controlClasses3.asScala ++ controlClasses4.asScala
      classes
        .filterNot(
          c => c.getName == "otoroshi.script.DefaultRequestTransformer$" ||
            c.getName == "otoroshi.script.CompilingRequestTransformer$" ||
            c.getName == "otoroshi.script.CompilingValidator$" ||
            c.getName == "otoroshi.script.DefaultValidator$" ||
            c.getName == "otoroshi.script.NanoApp" ||
            c.getName == "otoroshi.script.NanoApp$"

        )
        .map(c => c.getName)
    } catch {
      case e: Throwable =>
        e.printStackTrace()
        Seq.empty[String]
    } finally if (scanResult != null) scanResult.close()
  } getOrElse Seq.empty[String]

  def findAllScriptsList() = ApiAction.async { ctx =>
    OnlyIfScriptingEnabled {
      val typ = ctx.request.getQueryString("type")
      val cpTransformers = typ match {
        case None => transformersNames
        case Some("transformer") => transformersNames
        case Some("app") => transformersNames
        case _ => Seq.empty
      }
      val cpValidators = typ match {
        case None => validatorsNames
        case Some("validator") => validatorsNames
        case _ => Seq.empty
      }
      env.datastores.scriptDataStore.findAll().map { all =>
        val allClasses = all.filter { script =>
          typ match {
            case None => true
            case Some("transformer") if script.`type` == TransformerType => true
            case Some("transformer") if script.`type` == AppType => true
            case Some("app") if script.`type` == AppType => true
            case Some("validator") if script.`type` == AccessValidatorType => true
            case _ => false
          }
        }.map(c => Json.obj("id" -> c.id, "name" -> c.name)) ++
          cpTransformers.map(c => Json.obj("id" -> s"cp:$c", "name" -> c)) ++
          cpValidators.map(c => Json.obj("id" -> s"cp:$c", "name" -> c))
        Ok(JsArray(allClasses))
      }
    }
  }

  def findScriptById(id: String) = ApiAction.async { ctx =>
    OnlyIfScriptingEnabled {
      env.datastores.scriptDataStore.findById(id).map {
        case Some(script) => Ok(script.toJson)
        case None =>
          NotFound(
            Json.obj("error" -> s"Script with id $id not found")
          )
      }
    }
  }

  def compileScript() = ApiAction.async(sourceBodyParser) { ctx =>
    OnlyIfScriptingEnabled {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
        val code = Json.parse(body.utf8String).\("code").as[String]
        env.scriptCompiler.compile(code).map {
          case Left(err) => Ok(Json.obj("done" -> true, "error" -> err))
          case Right(_)  => Ok(Json.obj("done" -> true))
        }
      }
    }
  }

  def createScript() = ApiAction.async(parse.json) { ctx =>
    OnlyIfScriptingEnabled {
      Script.fromJsonSafe(ctx.request.body) match {
        case Left(_) => BadRequest(Json.obj("error" -> "Bad Script format")).asFuture
        case Right(script) =>
          env.datastores.scriptDataStore.set(script).map { _ =>
            env.scriptManager.preCompileScript(script)
            Ok(script.toJson)
          }
      }
    }
  }

  def updateScript(id: String) = ApiAction.async(parse.json) { ctx =>
    OnlyIfScriptingEnabled {
      env.datastores.scriptDataStore.findById(id).flatMap {
        case None =>
          NotFound(
            Json.obj("error" -> s"Script with id $id not found")
          ).asFuture
        case Some(initialScript) => {
          Script.fromJsonSafe(ctx.request.body) match {
            case Left(_) => BadRequest(Json.obj("error" -> "Bad Script format")).asFuture
            case Right(script) => {
              env.datastores.scriptDataStore.set(script).map { _ =>
                env.scriptManager.preCompileScript(script)
                Ok(script.toJson)
              }
            }
          }
        }
      }
    }
  }

  def patchScript(id: String) = ApiAction.async(parse.json) { ctx =>
    OnlyIfScriptingEnabled {
      env.datastores.scriptDataStore.findById(id).flatMap {
        case None =>
          NotFound(
            Json.obj("error" -> s"Script with id $id not found")
          ).asFuture
        case Some(initialScript) => {
          val currentJson = initialScript.toJson
          val patch       = JsonPatch(ctx.request.body)
          val newScript   = patch(currentJson)
          Script.fromJsonSafe(newScript) match {
            case Left(_) => BadRequest(Json.obj("error" -> "Bad Script format")).asFuture
            case Right(newScript) => {
              env.datastores.scriptDataStore.set(newScript).map { _ =>
                env.scriptManager.preCompileScript(newScript)
                Ok(newScript.toJson)
              }
            }
          }
        }
      }
    }
  }

  def deleteScript(id: String) = ApiAction.async { ctx =>
    OnlyIfScriptingEnabled {
      env.datastores.scriptDataStore.delete(id).map { _ =>
        env.scriptManager.removeScript(id)
        Ok(Json.obj("done" -> true))
      }
    }
  }
}
