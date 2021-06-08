package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.models.RightsChecker.Anyone
import otoroshi.script._
import otoroshi.utils.controllers.{
  ApiError,
  BulkControllerHelper,
  CrudControllerHelper,
  EntityAndContext,
  JsonApiError,
  NoEntityAndContext,
  OptionalEntityAndContext,
  SeqEntityAndContext
}
import play.api.Logger
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ScriptApiController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[Script, JsValue]
    with CrudControllerHelper[Script, JsValue] {

  import otoroshi.utils.future.Implicits._

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val logger = Logger("otoroshi-scripts-api")

  val sourceBodyParser = BodyParser("scripts-parsers") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  def OnlyIfScriptingEnabled(f: => Future[Result]): Future[Result] = {
    env.scriptingEnabled match {
      case true  => f
      case false => InternalServerError(Json.obj("error" -> "Scripting not enabled !")).asFuture
    }
  }

  def findAllScriptsList() =
    ApiAction.async { ctx =>
      val transformersNames = env.scriptManager.transformersNames
      val validatorsNames   = env.scriptManager.validatorsNames
      val preRouteNames     = env.scriptManager.preRouteNames
      val reqSinkNames      = env.scriptManager.reqSinkNames
      val listenerNames     = env.scriptManager.listenerNames
      val jobNames          = env.scriptManager.jobNames
      val exporterNames     = env.scriptManager.exporterNames

      val typ             = ctx.request.getQueryString("type")
      val cpTransformers  = typ match {
        case None                => transformersNames
        case Some("transformer") => transformersNames
        case Some("app")         => transformersNames
        case _                   => Seq.empty
      }
      val cpValidators    = typ match {
        case None              => validatorsNames
        case Some("validator") => validatorsNames
        case _                 => Seq.empty
      }
      val cpPreRoutes     = typ match {
        case None             => preRouteNames
        case Some("preroute") => preRouteNames
        case _                => Seq.empty
      }
      val cpRequestSinks  = typ match {
        case None         => reqSinkNames
        case Some("sink") => reqSinkNames
        case _            => Seq.empty
      }
      val cpListenerNames = typ match {
        case None             => listenerNames
        case Some("listener") => listenerNames
        case _                => Seq.empty
      }
      val cpJobNames      = typ match {
        case None        => jobNames
        case Some("job") => jobNames
        case _           => Seq.empty
      }
      val cpExporterNames = typ match {
        case None             => exporterNames
        case Some("exporter") => exporterNames
        case _                => Seq.empty
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
          case Left(_)         => Json.obj("id" -> s"cp:$c", "name" -> c, "description" -> JsNull, "pluginType" -> PluginType.CompositeType.name)
          case Right(instance) => instance.jsonDescription ++ Json.obj("id" -> s"cp:$c", "name" -> instance.name, "pluginType" -> instance.pluginType.name)
        }
      }
      env.datastores.scriptDataStore.findAll().map { all =>
        val allClasses = all
          .filter(ctx.canUserRead)
          .filter { script =>
            typ match {
              case None                                                                 => true
              case Some("transformer") if script.`type` == PluginType.TransformerType   => true
              case Some("transformer") if script.`type` == PluginType.AppType           => true
              case Some("app") if script.`type` == PluginType.AppType                   => true
              case Some("validator") if script.`type` == PluginType.AccessValidatorType => true
              case Some("preroute") if script.`type` == PluginType.PreRoutingType       => true
              case Some("sink") if script.`type` == PluginType.RequestSinkType          => true
              case Some("listener") if script.`type` == PluginType.EventListenerType    => true
              case Some("job") if script.`type` == PluginType.JobType                   => true
              case Some("exporter") if script.`type` == PluginType.DataExporterType     => true
              case Some("composite") if script.`type` == PluginType.CompositeType       => true
              case Some("*")                                                            => true
              case _                                                                    => false
            }
          }
          .map(c => (c, env.scriptManager.getAnyScript[NamedPlugin](c.id)))
          .map {
            case (c, Left(_))         => Json.obj("id" -> c.id, "name" -> c.name, "description" -> c.desc, "pluginType" -> PluginType.CompositeType.name)
            case (c, Right(instance)) =>
              Json.obj(
                "id"            -> c.id,
                "name"          -> JsString(Option(c.name).map(_.trim).filter(_.nonEmpty).getOrElse(instance.name)),
                "description"   -> Option(c.desc)
                  .map(_.trim)
                  .filter(_.nonEmpty)
                  .orElse(instance.description)
                  .map(JsString.apply)
                  .getOrElse(JsNull)
                  .as[JsValue],
                "pluginType"    -> instance.pluginType.name,
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
          cpExporterNames.map(extractInfos) ++
          cpJobNames.map(extractInfosFromJob).filter {
            case JsNull => false
            case _      => true
          }
        Ok(JsArray(allClasses))
      }
    }

  def compileScript() =
    ApiAction.async(sourceBodyParser) { ctx =>
      ctx.checkRights(Anyone) {
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
    }

  override def extractId(entity: Script): String = entity.id

  override def readEntity(json: JsValue): Either[String, Script] =
    Script._fmt.reads(json).asEither match {
      case Left(e)  => Left(e.toString())
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: Script): JsValue = Script._fmt.writes(entity)

  override def findByIdOps(
      id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[Script]]] = {
    env.datastores.scriptDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_SCRIPT",
          message = "User accessed a script",
          metadata = Json.obj("ScriptId" -> id),
          alert = "ScriptAccessed"
        )
      )
    }
  }

  override def findAllOps(
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[Script]]] = {
    env.datastores.scriptDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_SCRIPTS",
          message = "User accessed all scripts",
          metadata = Json.obj(),
          alert = "ScriptsAccessed"
        )
      )
    }
  }

  override def createEntityOps(
      entity: Script
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Script]]] = {
    env.datastores.scriptDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_SCRIPT",
            message = "User created a script",
            metadata = entity.toJson.as[JsObject],
            alert = "ScriptCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "Script not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
      entity: Script
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Script]]] = {
    env.datastores.scriptDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_SCRIPT",
            message = "User updated a script",
            metadata = entity.toJson.as[JsObject],
            alert = "ScriptUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "Script not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
      id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[Script]]] = {
    env.datastores.scriptDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_SCRIPT",
            message = "User deleted a Script",
            metadata = Json.obj("ScriptId" -> id),
            alert = "ScriptDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "Script not deleted ...")
          )
        )
      }
    }
  }
}
