package otoroshi.next.extensions

import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.plugins.api.{
  NgAccess,
  NgAccessContext,
  NgAccessValidator,
  NgPluginCategory,
  NgPluginConfig,
  NgPluginVisibility,
  NgStep
}
import otoroshi.storage._
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class Foo(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String]
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = Foo.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object Foo {
  val format = new Format[Foo] {
    override def writes(o: Foo): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply))
    )
    override def reads(json: JsValue): JsResult[Foo] = Try {
      Foo(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String])
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

trait FooDataStore extends BasicStore[Foo]

class KvFooDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends FooDataStore
    with RedisLikeStore[Foo] {
  override def fmt: Format[Foo]                        = Foo.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:foos:$id"
  override def extractId(value: Foo): String           = value.id
}

class FooAdminExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val fooDatastore: FooDataStore = new KvFooDataStore(extensionId, env.datastores.redis, env)
}

class FooAdminExtensionState(env: Env) {

  private val foos = new UnboundedTrieMap[String, Foo]()

  def foo(id: String): Option[Foo] = foos.get(id)
  def allFoos(): Seq[Foo]          = foos.values.toSeq

  private[extensions] def updateFoos(values: Seq[Foo]): Unit = {
    foos.addAll(values.map(v => (v.id, v))).remAll(foos.keySet.toSeq.diff(values.map(_.id)))
  }
}

class FooAdminExtension(val env: Env) extends AdminExtension {

  private lazy val datastores = new FooAdminExtensionDatastores(env, id)
  private lazy val states     = new FooAdminExtensionState(env)

  override def id: AdminExtensionId = AdminExtensionId("otoroshi.extensions.Foo")

  override def name: String = "Foo"

  override def description: Option[String] = "Foo".some

  override def enabled: Boolean = env.isDev || configuration.getOptional[Boolean]("enabled").getOrElse(false)

  override def start(): Unit = {
    "start example extension".debugPrintln
  }

  override def stop(): Unit = {
    "stop example extension".debugPrintln
  }

  override def syncStates(): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    for {
      foos <- datastores.fooDatastore.findAll()
    } yield {
      states.updateFoos(foos)
      ()
    }
  }

  override def frontendExtensions(): Seq[AdminExtensionFrontendExtension] = {
    Seq(
      AdminExtensionFrontendExtension("/__otoroshi_assets/javascripts/extensions/foos.js")
    )
  }

  override def wellKnownRoutes(): Seq[AdminExtensionWellKnownRoute] = Seq(
    AdminExtensionWellKnownRoute(
      "GET",
      "/.well-known/otoroshi/extensions/foo/bars/:id",
      false,
      (ctx, request, body) => {
        Results.Ok(Json.obj("id" -> ctx.named("id").map(JsString.apply).getOrElse(JsNull).asValue)).vfuture
      }
    ),
    AdminExtensionWellKnownRoute(
      "GET",
      "/.well-known/otoroshi/extensions/foo/check",
      false,
      (ctx, request, body) => {
        Results.Ok(Json.obj("check" -> true)).vfuture
      }
    )
  )

  override def adminApiRoutes(): Seq[AdminExtensionAdminApiRoute] = Seq(
    AdminExtensionAdminApiRoute(
      "GET",
      "/api/extensions/foo/foos/:id",
      false,
      (ctx, request, apk, _) => {
        Results.Ok(Json.obj("foo_id" -> ctx.named("id").map(JsString.apply).getOrElse(JsNull).asValue)).vfuture
      }
    )
  )

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(
        Resource(
          "Foo",
          "foos",
          "foo",
          "foo.extensions.otoroshi.io",
          ResourceVersion("v1", true, false, true),
          GenericResourceAccessApiWithState[Foo](
            Foo.format,
            id => datastores.fooDatastore.key(id),
            c => datastores.fooDatastore.extractId(c),
            stateAll = () => states.allFoos(),
            stateOne = id => states.foo(id),
            stateUpdate = values => states.updateFoos(values)
          )
        )
      )
    )
  }
}

/*

case class FooPluginConfig(filter: String) extends NgPluginConfig {
  def json: JsValue = Json.obj("filter" -> filter)
}

class FooPlugin extends NgAccessValidator {

  override def core: Boolean = false
  override def name: String = "Foo"
  override def description: Option[String] = "foo foo".some
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def isAccessAsync: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(FooPluginConfig("--"))
  override def multiInstance: Boolean = true

  override def accessSync(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): NgAccess = NgAccess.NgAllowed
}

 */
