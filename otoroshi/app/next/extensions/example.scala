package otoroshi.next.extensions

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.api._
import otoroshi.cluster.ClusterMode
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.storage._
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json._
import play.api.mvc.Results
import play.api.{Configuration, Environment, Logger}
import storage.drivers.generic.{GenericDataStores, GenericRedisLike, GenericRedisLikeBuilder}

import scala.collection.concurrent.TrieMap
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

class FooRedisLike(env: Env, actorSystem: ActorSystem) extends GenericRedisLike {

  val redis       = new otoroshi.storage.drivers.inmemory.SwappableInMemoryRedis(false, env, actorSystem)
  implicit val ec = actorSystem.dispatcher

  override def setCounter(key: String, value: Long): Future[Unit]               = redis.set(key, value.toString).map(_ => ())
  override def rawGet(key: String): Future[Option[Any]]                         = redis.rawGet(key)
  override def health()(implicit ec: ExecutionContext): Future[DataStoreHealth] = redis.health()
  override def stop(): Unit                                                     = redis.stop()
  override def flushall(): Future[Boolean]                                      = redis.flushall()

  override def get(key: String): Future[Option[ByteString]]                                                            = redis.get(key)
  override def mget(keys: String*): Future[Seq[Option[ByteString]]]                                                    = redis.mget(keys: _*)
  override def set(key: String, value: String, exSeconds: Option[Long], pxMilliseconds: Option[Long]): Future[Boolean] =
    redis.set(key, value, exSeconds, pxMilliseconds)
  override def setBS(
      key: String,
      value: ByteString,
      exSeconds: Option[Long],
      pxMilliseconds: Option[Long]
  ): Future[Boolean]                                                                                                   = redis.setBS(key, value, exSeconds, pxMilliseconds)
  override def del(keys: String*): Future[Long]                                                                        = redis.del(keys: _*)
  override def incr(key: String): Future[Long]                                                                         = redis.incr(key)
  override def incrby(key: String, increment: Long): Future[Long]                                                      = redis.incrby(key, increment)
  override def exists(key: String): Future[Boolean]                                                                    = redis.exists(key)
  override def keys(pattern: String): Future[Seq[String]]                                                              = redis.keys(pattern)

  override def hdel(key: String, fields: String*): Future[Long]                       = redis.hdel(key, fields: _*)
  override def hgetall(key: String): Future[Map[String, ByteString]]                  = redis.hgetall(key)
  override def hset(key: String, field: String, value: String): Future[Boolean]       = redis.hset(key, field, value)
  override def hsetBS(key: String, field: String, value: ByteString): Future[Boolean] = redis.hsetBS(key, field, value)

  override def llen(key: String): Future[Long]                                       = redis.llen(key)
  override def lpush(key: String, values: String*): Future[Long]                     = redis.lpush(key, values: _*)
  override def lpushLong(key: String, values: Long*): Future[Long]                   = redis.lpushLong(key, values: _*)
  override def lpushBS(key: String, values: ByteString*): Future[Long]               = redis.lpushBS(key, values: _*)
  override def lrange(key: String, start: Long, stop: Long): Future[Seq[ByteString]] = redis.lrange(key, start, stop)
  override def ltrim(key: String, start: Long, stop: Long): Future[Boolean]          = redis.ltrim(key, start, stop)

  override def pttl(key: String): Future[Long]                           = redis.pttl(key)
  override def ttl(key: String): Future[Long]                            = redis.ttl(key)
  override def expire(key: String, seconds: Int): Future[Boolean]        = redis.expire(key, seconds)
  override def pexpire(key: String, milliseconds: Long): Future[Boolean] = redis.pexpire(key, milliseconds)

  override def sadd(key: String, members: String*): Future[Long]             = redis.sadd(key, members: _*)
  override def saddBS(key: String, members: ByteString*): Future[Long]       = redis.saddBS(key, members: _*)
  override def sismember(key: String, member: String): Future[Boolean]       = redis.sismember(key, member)
  override def sismemberBS(key: String, member: ByteString): Future[Boolean] = redis.sismemberBS(key, member)
  override def smembers(key: String): Future[Seq[ByteString]]                = redis.smembers(key)
  override def srem(key: String, members: String*): Future[Long]             = redis.srem(key, members: _*)
  override def sremBS(key: String, members: ByteString*): Future[Long]       = redis.sremBS(key, members: _*)
  override def scard(key: String): Future[Long]                              = redis.scard(key)

  override def typ(key: String): Future[String] = {
    rawGet(key) map {
      case Some(_: String)                                                     => "string"
      case Some(_: ByteString)                                                 => "string"
      case Some(_: Long)                                                       => "string"
      case Some(_: java.util.concurrent.ConcurrentHashMap[String, ByteString]) => "hash"
      case Some(_: TrieMap[String, ByteString])                                => "hash"
      case Some(_: java.util.concurrent.CopyOnWriteArrayList[ByteString])      => "list"
      case Some(_: scala.collection.mutable.MutableList[ByteString])           => "list"
      case Some(_: java.util.concurrent.CopyOnWriteArraySet[ByteString])       => "set"
      case Some(_: scala.collection.mutable.HashSet[ByteString])               => "set"
      case _                                                                   => "none"
    }
  }
}

class FooRedisLikeBuilder  extends GenericRedisLikeBuilder {
  override def build(
      configuration: Configuration,
      environment: Environment,
      lifecycle: ApplicationLifecycle,
      clusterMode: ClusterMode,
      redisStatsItems: Int,
      actorSystem: ActorSystem,
      mat: Materializer,
      logger: Logger,
      env: Env
  ): GenericRedisLike = {
    new FooRedisLike(env, actorSystem)
  }
}
class FooDataStoresBuilder extends DataStoresBuilder       {
  override def build(
      configuration: Configuration,
      environment: Environment,
      lifecycle: ApplicationLifecycle,
      clusterMode: ClusterMode,
      env: Env
  ): DataStores = {
    new GenericDataStores(
      configuration,
      environment,
      lifecycle,
      clusterMode,
      redisStatsItems = 100,
      builder = new FooRedisLikeBuilder(),
      env
    )
  }
}

object FooDataStoresBuilder {
  def apply(): DataStoresBuilder = new FooDataStoresBuilder()
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

  override def datastoreBuilders(): Map[String, DataStoresBuilder] = Map(
    "foo" -> FooDataStoresBuilder()
  )

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
            classOf[Foo],
            id => datastores.fooDatastore.key(id),
            c => datastores.fooDatastore.extractId(c),
            (json) => json.select("id").asString,
            () => "id",
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
