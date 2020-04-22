package controllers.adminapi

import actions.ApiAction
import akka.util.ByteString
import env.Env
import otoroshi.script._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc._
import utils._

import scala.concurrent.{ExecutionContext, Future}

class ScriptApiController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) with BulkControllerHelper[Script, JsValue] with CrudControllerHelper[Script, JsValue] {

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

  def findAllScriptsList() = ApiAction.async { ctx =>
    OnlyIfScriptingEnabled {

      val transformersNames = env.scriptManager.transformersNames
      val validatorsNames   = env.scriptManager.validatorsNames
      val preRouteNames     = env.scriptManager.preRouteNames
      val reqSinkNames      = env.scriptManager.reqSinkNames
      val listenerNames     = env.scriptManager.listenerNames
      val jobNames          = env.scriptManager.jobNames

      val typ = ctx.request.getQueryString("type")
      val cpTransformers = typ match {
        case None                => transformersNames
        case Some("transformer") => transformersNames
        case Some("app")         => transformersNames
        case _                   => Seq.empty
      }
      val cpValidators = typ match {
        case None              => validatorsNames
        case Some("validator") => validatorsNames
        case _                 => Seq.empty
      }
      val cpPreRoutes = typ match {
        case None             => preRouteNames
        case Some("preroute") => preRouteNames
        case _                => Seq.empty
      }
      val cpRequestSinks = typ match {
        case None         => reqSinkNames
        case Some("sink") => reqSinkNames
        case _            => Seq.empty
      }
      val cpListenerNames = typ match {
        case None             => listenerNames
        case Some("listener") => listenerNames
        case _                => Seq.empty
      }
      val cpJobNames = typ match {
        case None        => jobNames
        case Some("job") => jobNames
        case _           => Seq.empty
      }
      def extractInfosFromJob(c: String): JsValue = {
        env.scriptManager.getAnyScript[Job](s"cp:$c") match {
          case Left(_)                                                          => extractInfos(c)
          case Right(instance) if instance.visibility == JobVisibility.UserLand => extractInfos(c)
          case Right(instance) if instance.visibility == JobVisibility.Internal => JsNull
        }
      }
      def extractInfos(c: String): JsValue = {
        env.scriptManager.getAnyScript[NamedPlugin](s"cp:$c") match {
          case Left(_) => Json.obj("id" -> s"cp:$c", "name" -> c, "description" -> JsNull)
          case Right(instance) =>
            Json.obj(
              "id"            -> s"cp:$c",
              "name"          -> instance.name,
              "description"   -> instance.description.map(JsString.apply).getOrElse(JsNull).as[JsValue],
              "defaultConfig" -> instance.defaultConfig.getOrElse(JsNull).as[JsValue],
              "configRoot"    -> instance.configRoot.map(JsString.apply).getOrElse(JsNull).as[JsValue],
              "configSchema"  -> instance.configSchema.getOrElse(JsNull).as[JsValue],
              "configFlow"    -> JsArray(instance.configFlow.map(JsString.apply))
            )
        }
      }
      env.datastores.scriptDataStore.findAll().map { all =>
        val allClasses = all
          .filter { script =>
            typ match {
              case None                                                      => true
              case Some("transformer") if script.`type` == TransformerType   => true
              case Some("transformer") if script.`type` == AppType           => true
              case Some("app") if script.`type` == AppType                   => true
              case Some("validator") if script.`type` == AccessValidatorType => true
              case Some("preroute") if script.`type` == PreRoutingType       => true
              case Some("sink") if script.`type` == RequestSinkType          => true
              case Some("listener") if script.`type` == EventListenerType    => true
              case Some("job") if script.`type` == JobType                   => true
              case _                                                         => false
            }
          }
          .map(c => (c, env.scriptManager.getAnyScript[NamedPlugin](c.id)))
          .map {
            case (c, Left(_)) => Json.obj("id" -> c.id, "name" -> c.name, "description" -> c.desc)
            case (c, Right(instance)) => Json.obj(
              "id"    -> c.id,
              "name"          -> JsString(Option(c.name).map(_.trim).filter(_.nonEmpty).getOrElse(instance.name)),
              "description"   -> Option(c.desc).map(_.trim).filter(_.nonEmpty).orElse(instance.description).map(JsString.apply).getOrElse(JsNull).as[JsValue],
              "defaultConfig" -> instance.defaultConfig.getOrElse(JsNull).as[JsValue],
              "configRoot"    -> instance.configRoot.map(JsString.apply).getOrElse(JsNull).as[JsValue],
              "configSchema"  -> instance.configSchema.getOrElse(JsNull).as[JsValue],
              "configFlow"    -> JsArray(instance.configFlow.map(JsString.apply))
            )
          } ++
          cpTransformers.map(extractInfos) ++
          cpValidators.map(extractInfos) ++
          cpPreRoutes.map(extractInfos) ++
          cpRequestSinks.map(extractInfos) ++
          cpListenerNames.map(extractInfos) ++
          cpJobNames.map(extractInfosFromJob).filter {
            case JsNull => false
            case _      => true
          }
        Ok(JsArray(allClasses))
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

  override def readEntity(json: JsValue): Either[String, Script] = Script._fmt.reads(json).asEither match {
    case Left(e) => Left(e.toString())
    case Right(r) => Right(r)
  }

  override def writeEntity(entity: Script): JsValue = Script._fmt.writes(entity)

  override def findByIdOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[Script]]] = {
    env.datastores.scriptDataStore.findById(id).map { opt =>
      Right(OptionalEntityAndContext(
        entity = opt,
        action = "ACCESS_SCRIPT",
        message = "User accessed a script",
        metadata = Json.obj("ScriptId" -> id),
        alert = "ScriptAccessed"
      ))
    }
  }

  override def findAllOps(req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[Script]]] = {
    env.datastores.scriptDataStore.findAll().map { seq =>
      Right(SeqEntityAndContext(
        entity = seq,
        action = "ACCESS_ALL_SCRIPTS",
        message = "User accessed all scripts",
        metadata = Json.obj(),
        alert = "ScriptsAccessed"
      ))
    }
  }

  override def createEntityOps(entity: Script)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Script]]] = {
    env.datastores.scriptDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "CREATE_SCRIPT",
          message = "User created a script",
          metadata = entity.toJson.as[JsObject],
          alert = "ScriptCreatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Script not stored ...")
        ))
      }
    }
  }

  override def updateEntityOps(entity: Script)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Script]]] = {
    env.datastores.scriptDataStore.set(entity).map {
      case true => {
        Right(EntityAndContext(
          entity = entity,
          action = "UPDATE_SCRIPT",
          message = "User updated a script",
          metadata = entity.toJson.as[JsObject],
          alert = "ScriptUpdatedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Script not stored ...")
        ))
      }
    }
  }

  override def deleteEntityOps(id: String)(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[Script]]] = {
    env.datastores.scriptDataStore.delete(id).map {
      case true => {
        Right(NoEntityAndContext(
          action = "DELETE_SCRIPT",
          message = "User deleted a Script",
          metadata = Json.obj("ScriptId" -> id),
          alert = "ScriptDeletedAlert"
        ))
      }
      case false => {
        Left(JsonApiError(
          500,
          Json.obj("error" -> "Script not deleted ...")
        ))
      }
    }
  }

  /*

  def findAllScripts() = ApiAction.async { ctx =>
    OnlyIfScriptingEnabled {
      env.datastores.scriptDataStore.findAll().map(all => Ok(JsArray(all.map(_.toJson))))
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



  def createScript() = ApiAction.async(parse.json) { ctx =>
    OnlyIfScriptingEnabled {
      val id = (ctx.request.body \ "id").asOpt[String]
      val body = ctx.request.body
        .as[JsObject] ++ id.map(v => Json.obj("id" -> id)).getOrElse(Json.obj("id" -> IdGenerator.token))
      Script.fromJsonSafe(body) match {
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
          val newScript   = utils.JsonPatchHelpers.patchJson(ctx.request.body, currentJson)
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
        Ok(Json.obj("deleted" -> true))
      }
    }
  }
  */
}