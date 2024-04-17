package otoroshi.greenscore

import akka.actor.{Actor, ActorRef, Props}
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.cluster.ClusterLeaderUpdateMessage.RouteCallIncr
import otoroshi.env.Env
import otoroshi.events.{AnalyticsReadsServiceImpl, GatewayEvent, OtoroshiEvent}
import otoroshi.models.{EntityLocation, EntityLocationSupport, ServiceDescriptor}
import otoroshi.next.extensions.{AdminExtension, AdminExtensionAdminApiRoute, AdminExtensionEntity, AdminExtensionId}
import otoroshi.next.utils.JsonHelpers.requestBody
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.SourceBody
import play.api.mvc.Results
import play.api.mvc.Results.NotFound

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Future
import scala.util._

object OtoroshiEventListener {
  def props(ext: GreenScoreExtension, env: Env) = Props(new OtoroshiEventListener(ext, env))
}

class OtoroshiEventListener(ext: GreenScoreExtension, env: Env) extends Actor {
  override def receive: Receive = {
    case evt: GatewayEvent =>
      val routeId = evt.route.map(_.id).getOrElse(evt.`@serviceId`)
      ext.ecoMetrics.updateRoute(
        RouteCallIncr(
          routeId = routeId,
          calls = new AtomicLong(1),
          overhead = new AtomicLong(evt.overhead),
          duration = new AtomicLong(evt.duration),
          backendDuration = new AtomicLong(evt.backendDuration),
          dataIn = new AtomicLong(evt.data.dataIn),
          dataOut = new AtomicLong(evt.data.dataOut),
          headersIn = new AtomicLong(evt.headers.foldLeft(0L) { case (acc, item) =>
            acc + item.key.byteString.size + item.value.byteString.size + 3 // 3 = ->
          } + evt.method.byteString.size + evt.url.byteString.size + evt.protocol.byteString.size + 2),
          headersOut = new AtomicLong(
            evt.headersOut.foldLeft(0L) { case (acc, item) =>
              acc + item.key.byteString.size + item.value.byteString.size + 3 // 3 = ->
            } + evt.protocol.byteString.size + 1 + 3 + Results
              .Status(evt.status)
              .header
              .reasonPhrase
              .map(_.byteString.size)
              .getOrElse(0)
          )
        )
      )
    case _                 =>
  }
}

case class RouteRules(routeId: String, rulesConfig: RulesRouteConfiguration)

case class GreenScoreEntity(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    routes: Seq[RouteRules],
    thresholds: Thresholds = Thresholds()
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
      })),
      "thresholds"  -> o.thresholds.json()
    )

    override def reads(json: JsValue): JsResult[GreenScoreEntity] = Try {
      GreenScoreEntity(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        thresholds = json.select("thresholds").as[Thresholds](Thresholds.reads),
        routes = json
          .select("routes")
          .asOpt[JsArray]
          .map(routes => {
            routes.value.map(route => {
              route
                .asOpt[JsObject]
                .map(v => {
                  RouteRules(
                    v.select("routeId").as[String],
                    v.select("rulesConfig").asOpt[JsObject].map(RulesRouteConfiguration.format.reads).get.get
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

  private[greenscore] val logger          = Logger("otoroshi-extension-green-score")
  private[greenscore] val ecoMetrics      = new EcoMetrics()
  private val listener: ActorRef          = env.analyticsActorSystem.actorOf(OtoroshiEventListener.props(this, env))
  private[greenscore] lazy val datastores = new GreenScoreAdminExtensionDatastores(env, id)
  private lazy val states                 = new GreenScoreAdminExtensionState(env)

  override def id: AdminExtensionId = AdminExtensionId("otoroshi.extensions.GreenScore")

  override def enabled: Boolean = env.isDev || configuration.getOptional[Boolean]("enabled").getOrElse(false)

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
      "/api/extensions/green-score",
      false,
      (ctx, request, apk, _) => {
        implicit val ec = env.otoroshiExecutionContext
        implicit val ev = env

        for {
          groups <- datastores.greenscoresDatastore.findAll()
        } yield {
          val globalScore = ecoMetrics.calculateGlobalScore(groups)

          Results.Ok(
            Json.obj(
              "groups" -> groups.map(_.json),
              "scores" -> globalScore.json()
            )
          )
        }
      }
    ),
    AdminExtensionAdminApiRoute(
      "POST",
      "/api/extensions/green-score",
      wantsBody = true,
      (ctx, request, apk, body) => {
        implicit val ec = env.otoroshiExecutionContext
        implicit val ev = env

        body
          .map(
            _.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer)
              .map(r => Json.parse(r.utf8String))
          )
          .getOrElse(Json.arr().vfuture)
          .flatMap(ids => {
            val identifiers = ids.asOpt[JsArray].getOrElse(Json.arr())
            for {
              groups <- if (identifiers.value.isEmpty) datastores.greenscoresDatastore.findAll()
                        else
                          datastores.greenscoresDatastore
                            .findAllById(ids.asOpt[JsArray].getOrElse(Json.arr()).as[Seq[String]])
            } yield {
              val globalScore = ecoMetrics.calculateGlobalScore(groups)

              Results.Ok(
                Json.obj(
                  "groups" -> groups.map(_.json),
                  "scores" -> globalScore.json()
                )
              )
            }
          })
      }
    ),
    AdminExtensionAdminApiRoute(
      "GET",
      "/api/extensions/green-score/template",
      false,
      (_, _, _, _) => {
        Results.Ok(JsArray(RulesManager.rules.map(_.json()))).vfuture
      }
    ),
    AdminExtensionAdminApiRoute(
      "GET",
      "/api/extensions/green-score/efficience/:route",
      false,
      (routerCtx, request, _, _) => {
        //        val random = new Random()
        //        Results.Ok(
        //          Json.arr(
        //            Json.obj("key" -> 1649810400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649814000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649817600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649821200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649824800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649828400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649832000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649835600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649839200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649842800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649846400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649850000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649853600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649857200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649860800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649864400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649868000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649871600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649875200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649878800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649882400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649886000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649889600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649893200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649896800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649900400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649904000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649907600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649911200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649914800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649918400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649922000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649925600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649929200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649932800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649936400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649940000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649943600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649947200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649950800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649954400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649958000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649961600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649965200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649968800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649972400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649976000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649979600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649983200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649986800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649990400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649994000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1649997600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650001200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650004800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650008400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650012000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650015600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650019200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650022800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650026400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650030000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650033600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650037200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650040800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650044400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650048000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650051600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650055200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650058800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650062400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650066000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650069600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650073200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650076800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650080400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650084000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650087600000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650091200000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650094800000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650098400000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650102000000L, "doc_count" -> random.nextInt(1001)),
        //            Json.obj("key" -> 1650105600000L, "doc_count" -> random.nextInt(1001))
        //          )
        //        ).future
        implicit val e = env;
        implicit val ctx = env.analyticsExecutionContext;


        env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
          val analyticsService = new AnalyticsReadsServiceImpl(globalConfig, env)

          //todo: getDate from request ???
          val from = DateTime.now().minusDays(6).withTimeAtStartOfDay().some
          val to = DateTime.now().some

//          val fromDate =
//            from.map(f => new DateTime(f.toLong)).orElse(DateTime.now().minusDays(90).withTimeAtStartOfDay().some)
//          val toDate   = to.map(f => new DateTime(f.toLong))

          //todo: use ctx.canUserRead ????
          routerCtx.named("route") match {
            case Some(routeId) => env.datastores.routeDataStore.findById(routeId)
              .flatMap {
                case Some(route) => analyticsService.fetchRouteEfficience(route, from, to)
                  .map {
                  case Some(value) => Results.Ok(value)
                  case None        => NotFound(Json.obj("error" -> "No entity found (1)"))
                }
                case None => NotFound(Json.obj("error" -> "No entity found (2)")).future
              }
            case None => NotFound(Json.obj("error" -> "No entity found (3)")).future
          }
        }
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
            classOf[GreenScoreEntity],
            id => datastores.greenscoresDatastore.key(id),
            c => datastores.greenscoresDatastore.extractId(c),
            json => json.select("id").asString,
            () => "id",
            stateAll = () => states.allGreenScores(),
            stateOne = id => states.greenScore(id),
            stateUpdate = values => states.updateGreenScores(values),
            tmpl = (v, p) =>
              GreenScoreEntity(
                id = IdGenerator.namedId("green-score", env),
                name = "green score group",
                description = "screen score for the routes of this group",
                metadata = Map.empty,
                tags = Seq.empty,
                routes = Seq.empty,
                location = EntityLocation.default
              ).json
          )
        )
      )
    )
  }

  def updateFromQuotas(routeCallIncr: RouteCallIncr) = {
    ecoMetrics.updateRoute(routeCallIncr)
  }
}
