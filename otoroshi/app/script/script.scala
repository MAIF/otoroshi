package otoroshi.script

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import actions.ApiAction
import akka.actor.Cancellable
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.common.hash.Hashing
import env.Env
import javax.script._
import models._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc.{AbstractController, BodyParser, ControllerComponents, Result}
import redis.RedisClientMasterSlaves
import storage.redis.RedisStore
import storage.{BasicStore, RedisLike, RedisLikeStore}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class HttpRequest(url: String, method: String, headers: Map[String, String], query: Map[String, String]) {
  lazy val host: String = headers.getOrElse("Host", "")
}
case class HttpResponse(status: Int, headers: Map[String, String])

trait RequestTransformer {

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
    FastFuture.successful(transformRequestSync(snowflake, rawRequest, otoroshiRequest, desc, apiKey, user)(env, ec, mat))
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
    FastFuture.successful(transformResponseSync(snowflake, rawResponse, otoroshiResponse, desc, apiKey, user)(env, ec, mat))
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

class ScriptCompiler(env: Env) {

  private val logger = Logger("otoroshi-script-compiler")
  private val scriptExec    = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  def compile(script: String): Future[Either[JsValue, RequestTransformer]] = {
    val start = System.currentTimeMillis()
    Future.apply {
      try {
        val engineManager = new ScriptEngineManager(env.environment.classLoader)
        val scriptEngine  = engineManager.getEngineByName("scala")
        val engine        = scriptEngine.asInstanceOf[ScriptEngine with Invocable]
        if (scriptEngine == null) {
          // dev mode
          Left(Json.obj(
            "line" -> 0,
            "column" -> 0,
            "file" -> "",
            "rawMessage" -> "",
            "message" -> "You are in dev mode, Scala script engine does not work inside sbt :("
          ))
        } else {
          val ctx = new SimpleScriptContext
          val res = engine.eval(script, ctx).asInstanceOf[RequestTransformer]
          ctx.getErrorWriter.flush()
          ctx.getWriter.flush()
          Right(res)
        }
      } catch {
        case ex: ScriptException =>
          val message = ex.getMessage.replace("in " + ex.getFileName, "")
          Left(Json.obj(
            "line" -> ex.getLineNumber,
            "column" -> ex.getColumnNumber,
            "file" -> ex.getFileName,
            "rawMessage" -> ex.getMessage,
            "message" -> message
          ))
        case ex: Throwable =>
          logger.error(s"Compilation error", ex)
          Left(Json.obj(
            "line" -> 0,
            "column" -> 0,
            "file" -> "none",
            "rawMessage" -> ex.getMessage,
            "message" -> ex.getMessage
          ))
      }
    }(scriptExec).andThen{
      case _ => logger.debug(s"Compilation process took ${(System.currentTimeMillis() - start).millis}")
    }(scriptExec)
  }
}

class ScriptManager(env: Env) {

  private implicit val ec = env.otoroshiExecutionContext
  private implicit val _env = env

  private val logger = Logger("otoroshi-script-manager")
  private val updateRef = new AtomicReference[Cancellable]()
  private val compiling = new TrieMap[String, Unit]()
  private val cache = new TrieMap[String, (String, RequestTransformer)]()

  def start(): ScriptManager = {
    if (env.scriptingEnabled) {
      updateRef.set(env.otoroshiScheduler.schedule(1.second, 10.second)(updateScriptCache())(env.otoroshiExecutionContext))
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
            if (env.isDev) logger.error(s"Script ${script.name} with id ${script.id} does not compile: ${err}")
            compiling.remove(script.id)
          case Right(trans) => {
            cache.put(script.id, (script.hash, trans))
            compiling.remove(script.id)
          }
        }
      }
    }
  }

  private def compileAndUpdateIfNeeded(script: Script): Unit = {
    (cache.get(script.id), compiling.get(script.id)) match {
      case (None, None)        => compileAndUpdate(script)
      case (None, Some(_))     => // do nothing as something is compiling
      case (Some(_), Some(_)) => // do nothing as something is compiling
      case (Some(cs), None) if cs._1 != script.hash => compileAndUpdate(script)
      case (Some(_), None)    => // do nothing as script has not changed from cache
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
    env.datastores.scriptDataStore.findById(ref).map {
      case Some(script) => compileAndUpdateIfNeeded(script)
      case None =>
        logger.error(s"Script with id `$ref` does not exists ...")
        // do nothing as the script does not exists
    }
    cache.get(ref).flatMap(a => Option(a._2)).getOrElse(DefaultRequestTransformer)
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

    def transformRequest(
      snowflake: String,
      rawRequest: HttpRequest,
      otoroshiRequest: HttpRequest,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
    )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
      env.scriptingEnabled match {
        case true => desc.transformerRef match {
          case Some(ref) =>
            val script = env.scriptManager.getScript(ref)
            script.transformRequest(snowflake, rawRequest, otoroshiRequest, desc, apiKey, user)(env, ec, mat)
          case None => FastFuture.successful(Right(otoroshiRequest))
        }
        case false => FastFuture.successful(Right(otoroshiRequest))
      }
    }

    def transformResponse(
      snowflake: String,
      rawResponse: HttpResponse,
      otoroshiResponse: HttpResponse,
      desc: ServiceDescriptor,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
    )(implicit env: Env, ec: ExecutionContext, mat: Materializer):Future[Either[Result, HttpResponse]] = {
      env.scriptingEnabled match {
        case true => desc.transformerRef match {
          case Some(ref) => env.scriptManager.getScript(ref).transformResponse(snowflake, rawResponse, otoroshiResponse, desc, apiKey, user)(env, ec, mat)
          case None => FastFuture.successful(Right(otoroshiResponse))
        }
        case false => FastFuture.successful(Right(otoroshiResponse))
      }
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
      env.scriptingEnabled match {
        case true => desc.transformerRef match {
          case Some(ref) => env.scriptManager.getScript(ref).transformRequestBody(snowflake, body, rawRequest, otoroshiRequest, desc, apiKey, user)(env, ec, mat)
          case None => body
        }
        case false => body
      }
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
      env.scriptingEnabled match {
        case true => desc.transformerRef match {
          case Some(ref) => env.scriptManager.getScript(ref).transformResponseBody(snowflake, body, rawResponse, otoroshiResponse, desc, apiKey, user)(env, ec, mat)
          case None => body
        }
        case false => body
      }
    }
  }
}

case class Script(id: String, name: String, desc: String, code: String) {
  def save()(implicit ec: ExecutionContext, env: Env)   = env.datastores.scriptDataStore.set(this)
  def delete()(implicit ec: ExecutionContext, env: Env) = env.datastores.scriptDataStore.delete(this)
  def exists()(implicit ec: ExecutionContext, env: Env) = env.datastores.scriptDataStore.exists(this)
  def toJson                                    = Script.toJson(this)
  def hash: String = Hashing.sha256().hashString(code, StandardCharsets.UTF_8).toString
}

object Script {

  lazy val logger = Logger("otoroshi-script")

  val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

  val _fmt: Format[Script] = new Format[Script] {
    override def writes(apk: Script): JsValue = Json.obj(
      "id" -> apk.id,
      "name" -> apk.name,
      "desc" -> apk.desc,
      "code" -> apk.code
    )
    override def reads(json: JsValue): JsResult[Script] =
      Try {
        Script(
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").as[String],
          code = (json \ "code").as[String]
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
  override def fmt: Format[Script] = Script._fmt
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): Key = Key(s"${_env.storageRoot}:scripts:$id")
  override def extractId(value: Script): String = value.id
}

class RedisScriptDataStore(redisCli: RedisClientMasterSlaves, _env: Env) extends ScriptDataStore with RedisStore[Script] {
  override def fmt: Format[Script] = Script._fmt
  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def key(id: String): Key = Key(s"${_env.storageRoot}:scripts:$id")
  override def extractId(value: Script): String = value.id
}

class ScriptApiController(ApiAction: ApiAction, cc: ControllerComponents)(
  implicit env: Env
) extends AbstractController(cc) {
  
  import gnieh.diffson.playJson._
  import utils.future.Implicits._

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val logger = Logger("otoroshi-scripts-api")

  val sourceBodyParser = BodyParser("scripts-parsers") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def OnlyIfScriptingEnabled(f: => Future[Result]): Future[Result] = {
    env.scriptingEnabled match {
      case true => f
      case false => InternalServerError(Json.obj("error" -> "Scripting not enabled !")).asFuture
    }
  }

  def findAllScripts() = ApiAction.async { ctx =>
    OnlyIfScriptingEnabled {
      env.datastores.scriptDataStore.findAll().map(all => Ok(JsArray(all.map(_.toJson))))
    }
  }

  def findAllScriptsList() = ApiAction.async { ctx =>
    OnlyIfScriptingEnabled {
      env.datastores.scriptDataStore.findAll().map { all =>
        Ok(JsArray(all.map { script =>
          Json.obj("id" -> script.id, "name" -> script.name)
        }))
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
          case Right(_) => Ok(Json.obj("done" -> true))
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
          val patch = JsonPatch(ctx.request.body)
          val newScript = patch(currentJson)
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


