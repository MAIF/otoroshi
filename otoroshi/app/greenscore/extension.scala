package otoroshi.greenscore

import akka.actor.{Actor, ActorRef, Props}
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.events.{GatewayEvent, OtoroshiEvent}
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions.{AdminExtension, AdminExtensionAdminApiRoute, AdminExtensionEntity, AdminExtensionId}
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.Future
import scala.util._

object OtoroshiEventListener {
  def props(ext: GreenScoreExtension, env: Env) = Props(new OtoroshiEventListener(ext, env))
}

class OtoroshiEventListener(ext: GreenScoreExtension, env: Env) extends Actor {
  override def receive: Receive = {
    case evt: GatewayEvent => {
      val routeId = evt.route.map(_.id).getOrElse(evt.`@serviceId`)
      ext.ecoMetrics.updateRoute(
        routeId = routeId,
        overhead = evt.overhead,
        overheadWoCb = evt.overheadWoCb,
        cbDuration = evt.cbDuration,
        duration = evt.duration,
        plugins = evt.route.map(_.plugins.slots.size).getOrElse(0),
        backendId = evt.target.scheme + evt.target.host + evt.target.uri,
        dataIn = evt.data.dataIn,
        dataOut = evt.data.dataOut,
        headers = evt.headers.foldLeft(0L) { case (acc, item) =>
          acc + item.key.byteString.size + item.value.byteString.size + 3 // 3 = ->
        } + evt.method.byteString.size + evt.url.byteString.size + evt.protocol.byteString.size + 2,
        headersOut = evt.headersOut.foldLeft(0L) { case (acc, item) =>
          acc + item.key.byteString.size + item.value.byteString.size + 3 // 3 = ->
        } + evt.protocol.byteString.size + 1 + 3 + Results
          .Status(evt.status)
          .header
          .reasonPhrase
          .map(_.byteString.size)
          .getOrElse(0)
      )
//      ext.logger.debug(s"global score for ${routeId}: ${ext.ecoMetrics.compute()}")
    }
    case _                 =>
  }
}

case class RouteScreenScore(routeId: String, rulesConfig: GreenScoreConfig)

case class GreenScoreEntity(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    routes: Seq[RouteScreenScore]
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = GreenScoreEntity.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object GreenScoreEntity {
  val format = new Format[GreenScoreEntity] {
    override def writes(o: GreenScoreEntity): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "routes"      -> JsArray(o.routes.map(route => {
        Json.obj(
          "routeId"     -> route.routeId,
          "rulesConfig" -> route.rulesConfig.json
        )
      }))
    )

    override def reads(json: JsValue): JsResult[GreenScoreEntity] = Try {
      GreenScoreEntity(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        routes = json
          .select("routes")
          .asOpt[JsArray]
          .map(routes => {
            routes.value.map(route => {
              route
                .asOpt[JsObject]
                .map(v => {
                  RouteScreenScore(
                    v.select("routeId").as[String],
                    v.select("rulesConfig").asOpt[JsObject].map(GreenScoreConfig.format.reads).get.get
                  )
                })
                .get
            })
          })
          .getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

trait GreenScoreDataStore extends BasicStore[GreenScoreEntity]

class KvGreenScoreDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends GreenScoreDataStore
    with RedisLikeStore[GreenScoreEntity] {
  override def fmt: Format[GreenScoreEntity]              = GreenScoreEntity.format
  override def redisLike(implicit env: Env): RedisLike    = redisCli
  override def key(id: String): String                    = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:greenscores:$id"
  override def extractId(value: GreenScoreEntity): String = value.id
}

class GreenScoreAdminExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val greenscoresDatastore: GreenScoreDataStore = new KvGreenScoreDataStore(extensionId, env.datastores.redis, env)
}

class GreenScoreAdminExtensionState(env: Env) {

  private val greenScores = new UnboundedTrieMap[String, GreenScoreEntity]()

  def greenScore(id: String): Option[GreenScoreEntity] = greenScores.get(id)
  def allGreenScores(): Seq[GreenScoreEntity]          = greenScores.values.toSeq

  private[greenscore] def updateGreenScores(values: Seq[GreenScoreEntity]): Unit = {
    greenScores.addAll(values.map(v => (v.id, v))).remAll(greenScores.keySet.toSeq.diff(values.map(_.id)))
  }
}

class GreenScoreExtension(val env: Env) extends AdminExtension {

  private[greenscore] val logger     = Logger("otoroshi-extension-green-score")
  private[greenscore] val ecoMetrics = new EcoMetrics(env)
  private val listener: ActorRef     = env.analyticsActorSystem.actorOf(OtoroshiEventListener.props(this, env))
  private lazy val datastores        = new GreenScoreAdminExtensionDatastores(env, id)
  private lazy val states            = new GreenScoreAdminExtensionState(env)

  override def id: AdminExtensionId = AdminExtensionId("otoroshi.extensions.GreenScore")

  override def enabled: Boolean = false //env.isDev || configuration.getOptional[Boolean]("enabled").getOrElse(false)

  override def name: String = "Green Score"

  override def description: Option[String] = None

  override def start(): Unit = {
    env.analyticsActorSystem.eventStream.subscribe(listener, classOf[OtoroshiEvent])
  }

  override def stop(): Unit = {
    env.analyticsActorSystem.eventStream.unsubscribe(listener)
  }

  override def syncStates(): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    for {
      scores <- datastores.greenscoresDatastore.findAll()
    } yield {
      states.updateGreenScores(scores)
      ()
    }
  }

  override def adminApiRoutes(): Seq[AdminExtensionAdminApiRoute] = Seq(
    AdminExtensionAdminApiRoute(
      "GET",
      "/api/extensions/green-score/eco",
      false,
      (ctx, request, apk, _) => {
        Results.Ok(Json.obj("score" -> 0)).vfuture
      }
    ),
    AdminExtensionAdminApiRoute(
      "GET",
      "/api/extensions/green-score/:greenscore",
      false,
      (ctx, request, apk, _) => {
        implicit val ec  = env.otoroshiExecutionContext
        implicit val ev  = env
        val greenScoreId = ctx.named("greenscore").map(JsString.apply).getOrElse(JsNull).asString
        for {
          scores <- datastores.greenscoresDatastore.findAll()
        } yield {
          val jsonScores = scores
            .find(_.id == greenScoreId)
            .map(group => group.routes.foldLeft(Json.arr())((acc, route) => acc :+ ecoMetrics.json(route.routeId)))
            .getOrElse(Json.arr())

          Results.Ok(
            Json.obj(
              "group"  -> greenScoreId,
              "scores" -> jsonScores
            )
          )
        }
      }
    ),
    AdminExtensionAdminApiRoute(
      "GET",
      "/api/extensions/green-score/template",
      false,
      (_, _, _, _) => {
        Results.Ok(GreenScoreConfig(sections = RulesManager.sections).json).vfuture
      }
    )
  )

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(
        Resource(
          "GreenScore",
          "green-scores",
          "green-score",
          "green-score.extensions.otoroshi.io",
          ResourceVersion("v1", true, false, true),
          GenericResourceAccessApiWithState[GreenScoreEntity](
            GreenScoreEntity.format,
            id => datastores.greenscoresDatastore.key(id),
            c => datastores.greenscoresDatastore.extractId(c),
            stateAll = () => states.allGreenScores(),
            stateOne = id => states.greenScore(id),
            stateUpdate = values => states.updateGreenScores(values)
          )
        )
      )
    )
  }
}
