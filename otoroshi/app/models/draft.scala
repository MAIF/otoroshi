package otoroshi.models

import next.models.Api
import otoroshi.actions.ApiAction
import otoroshi.api.{DeleteAction, WriteAction}
import otoroshi.env.Env
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class Draft(
    id: String,
    kind: String,
    content: JsValue,
    name: String,
    description: String,
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends otoroshi.models.EntityLocationSupport {
  def save()(implicit ec: ExecutionContext, env: Env) = env.datastores.draftsDataStore.set(this)
  override def internalId: String                     = id
  override def json: JsValue                          = Draft.format.writes(this)
  override def theName: String                        = name
  override def theDescription: String                 = description
  override def theTags: Seq[String]                   = tags
  override def theMetadata: Map[String, String]       = metadata
}

object Draft {
  def fromJsons(value: JsValue): Draft =
    try {
      format.reads(value).get
    } catch {
      case e: Throwable => throw e
    }
  val format                           = new Format[Draft] {
    override def writes(o: Draft): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "content"     -> o.content,
      "kind"        -> o.kind,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply))
    )
    override def reads(json: JsValue): JsResult[Draft] = Try {
      Draft(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        content = (json \ "content").asOpt[JsValue].getOrElse(Json.obj()),
        kind = (json \ "kind").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String])
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def writeValidator(
      newDraft: Draft,
      _body: JsValue,
      oldEntity: Option[(Draft, JsValue)],
      _singularName: String,
      _id: Option[String],
      action: WriteAction,
      env: Env
  ): Future[Either[JsValue, Draft]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    Api.format.reads(newDraft.content) match {
      case JsSuccess(api, _) =>
        Api
          .writeValidator(
            api,
            Json.obj(),
            oldEntity.map(oldDraft => (Api.format.reads(oldDraft._1.content).get, Json.obj())),
            _singularName,
            _id,
            action,
            env
          )
          .flatMap {
            case Left(value)   => value.leftf
            case Right(newApi) => newDraft.copy(content = newApi.json).rightf
          }

      case JsError(_) => newDraft.rightf
    }
  }

//  def deleteValidator(entity: Draft,
//                        body: JsValue,
//                        singularName: String,
//                        id: String,
//                        action: DeleteAction,
//                        env: Env):  Future[Either[JsValue, Unit]] = {
//    ???
//  }
}

trait DraftDataStore extends BasicStore[Draft] {
  def template(env: Env): Draft = {
    val defaultDraft = Draft(
      id = IdGenerator.namedId("draft", env),
      kind = "kind",
      name = "New draft",
      description = "New draft",
      tags = Seq.empty,
      metadata = Map.empty,
      content = Json.obj()
    )
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .draft
      .map { template =>
        Draft.format.reads(defaultDraft.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        defaultDraft
      }
  }
}

class KvDraftDataStore(redisCli: RedisLike, _env: Env) extends DraftDataStore with RedisLikeStore[Draft] {
  override def fmt: Format[Draft]                      = Draft.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:drafts:$id"
  override def extractId(value: Draft): String         = value.id
}
