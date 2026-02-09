package otoroshi.next.catalogs

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models.{BackOfficeUser, EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions._
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class RemoteCatalogScheduling(
    enabled: Boolean = false,
    kind: JobKind = JobKind.ScheduledEvery,
    instantiation: JobInstantiation = JobInstantiation.OneInstancePerOtoroshiInstance,
    initialDelay: Option[FiniteDuration] = None,
    interval: Option[FiniteDuration] = None,
    cronExpression: Option[String] = None,
    deployArgs: JsObject = Json.obj()
) {
  def json: JsValue = Json.obj(
    "enabled"         -> enabled,
    "kind"            -> kind.name,
    "instantiation"   -> instantiation.name,
    "initial_delay"   -> initialDelay.map(_.toMillis).map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).asValue,
    "interval"        -> interval.map(_.toMillis).map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).asValue,
    "cron_expression" -> cronExpression.map(JsString.apply).getOrElse(JsNull).asValue,
    "deploy_args"     -> deployArgs
  )
}

object RemoteCatalogScheduling {
  val default = RemoteCatalogScheduling()
  val format  = new Format[RemoteCatalogScheduling] {
    override def reads(json: JsValue): JsResult[RemoteCatalogScheduling] = Try {
      RemoteCatalogScheduling(
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
        kind = json.select("kind").asOpt[String].map(JobKind.apply).getOrElse(JobKind.ScheduledEvery),
        instantiation = json
          .select("instantiation")
          .asOpt[String]
          .map(JobInstantiation.apply)
          .getOrElse(JobInstantiation.OneInstancePerOtoroshiInstance),
        initialDelay = json.select("initial_delay").asOpt[Long].map(_.millis),
        interval = json.select("interval").asOpt[Long].map(_.millis),
        cronExpression = json.select("cron_expression").asOpt[String],
        deployArgs = json.select("deploy_args").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(e)   => JsError(e.getMessage)
      case Success(cfg) => JsSuccess(cfg)
    }

    override def writes(o: RemoteCatalogScheduling): JsValue = o.json
  }
}

case class RemoteCatalog(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    enabled: Boolean,
    sourceKind: String,
    sourceConfig: JsObject,
    scheduling: RemoteCatalogScheduling,
    testDeployArgs: JsObject
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = RemoteCatalog.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object RemoteCatalog {
  def template(): RemoteCatalog = RemoteCatalog(
    location = EntityLocation.default,
    id = s"remote-catalog_${IdGenerator.uuid}",
    name = "New Remote Catalog",
    description = "New Remote Catalog",
    metadata = Map.empty,
    tags = Seq.empty,
    enabled = true,
    sourceKind = "http",
    sourceConfig = Json.obj(),
    scheduling = RemoteCatalogScheduling.default,
    testDeployArgs = Json.obj()
  )
  val format = new Format[RemoteCatalog] {
    override def writes(o: RemoteCatalog): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"               -> o.id,
      "name"             -> o.name,
      "description"      -> o.description,
      "metadata"         -> o.metadata,
      "tags"             -> JsArray(o.tags.map(JsString.apply)),
      "enabled"          -> o.enabled,
      "source_kind"      -> o.sourceKind,
      "source_config"    -> o.sourceConfig,
      "scheduling"       -> o.scheduling.json,
      "test_deploy_args" -> o.testDeployArgs
    )
    override def reads(json: JsValue): JsResult[RemoteCatalog] = Try {
      RemoteCatalog(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse("--"),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        sourceKind = (json \ "source_kind").asOpt[String].getOrElse("http"),
        sourceConfig = (json \ "source_config").asOpt[JsObject].getOrElse(Json.obj()),
        scheduling = (json \ "scheduling")
          .asOpt[JsObject]
          .flatMap(o => RemoteCatalogScheduling.format.reads(o).asOpt)
          .getOrElse(RemoteCatalogScheduling.default),
        testDeployArgs = (json \ "test_deploy_args").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

trait RemoteCatalogDataStore extends BasicStore[RemoteCatalog]

class KvRemoteCatalogDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends RemoteCatalogDataStore
    with RedisLikeStore[RemoteCatalog] {
  override def fmt: Format[RemoteCatalog]                   = RemoteCatalog.format
  override def redisLike(implicit env: Env): RedisLike      = redisCli
  override def key(id: String): String                      =
    s"${_env.storageRoot}:extensions:${extensionId.cleanup}:remote-catalogs:$id"
  override def extractId(value: RemoteCatalog): String      = value.id
}

class RemoteCatalogAdminExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val remoteCatalogsDatastore: RemoteCatalogDataStore =
    new KvRemoteCatalogDataStore(extensionId, env.datastores.redis, env)
}

class RemoteCatalogAdminExtensionState(env: Env) {

  private val catalogs = new UnboundedTrieMap[String, RemoteCatalog]()

  def catalog(id: String): Option[RemoteCatalog] = catalogs.get(id)
  def allCatalogs(): Seq[RemoteCatalog]          = catalogs.values.toSeq

  private[catalogs] def updateCatalogs(values: Seq[RemoteCatalog]): Unit = {
    catalogs.addAll(values.map(v => (v.id, v))).remAll(catalogs.keySet.toSeq.diff(values.map(_.id)))
  }
}

class RemoteCatalogAdminExtension(val env: Env) extends AdminExtension {

  private lazy val logger = Logger("otoroshi-remote-catalogs-extension")

  private[catalogs] lazy val datastores = new RemoteCatalogAdminExtensionDatastores(env, id)
  private[catalogs] lazy val states     = new RemoteCatalogAdminExtensionState(env)
  private[catalogs] val handledJobs     = new UnboundedTrieMap[String, Job]()

  val engine = new RemoteCatalogEngine(env)

  override def id: AdminExtensionId = AdminExtensionId("otoroshi.extensions.RemoteCatalogs")

  override def name: String = "Otoroshi Remote Catalogs extension"

  override def description: Option[String] = "Otoroshi Remote Catalogs extension".some

  override def enabled: Boolean = true

  override def start(): Unit = {
    CatalogSources.initDefaults()
  }

  override def stop(): Unit = ()

  override def syncStates(): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    for {
      configs <- datastores.remoteCatalogsDatastore.findAllAndFillSecrets()
    } yield {
      states.updateCatalogs(configs)
      startJobsIfNeeded(configs)
      ()
    }
  }

  override def frontendExtensions(): Seq[AdminExtensionFrontendExtension] = Seq(
    AdminExtensionFrontendExtension("/__otoroshi_assets/javascripts/extensions/catalogs.js")
  )

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(
        Resource(
          "RemoteCatalog",
          "remote-catalogs",
          "remote-catalog",
          "catalogs.otoroshi.io",
          ResourceVersion("v1", true, false, true),
          GenericResourceAccessApiWithState[RemoteCatalog](
            RemoteCatalog.format,
            classOf[RemoteCatalog],
            id => datastores.remoteCatalogsDatastore.key(id),
            c => datastores.remoteCatalogsDatastore.extractId(c),
            json => json.select("id").asString,
            () => "id",
            tmpl = (v, p, _ctx) => RemoteCatalog.template().json,
            stateAll = () => states.allCatalogs(),
            stateOne = id => states.catalog(id),
            stateUpdate = values => states.updateCatalogs(values)
          )
        )
      )
    )
  }

  override def adminApiRoutes(): Seq[AdminExtensionAdminApiRoute] = {
    Seq(
      AdminExtensionAdminApiRoute(
        method = "POST",
        path = "/api/extensions/remote-catalogs/_deploy",
        wantsBody = true,
        handle = (ctx, req, apikey, optBody) => handleDeploy(optBody)
      )
    )
  }

  override def backofficeAuthRoutes(): Seq[AdminExtensionBackofficeAuthRoute] = Seq(
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = "/extensions/remote-catalogs/_deploy",
      wantsBody = true,
      handle = (ctx, req, user, body) => handleDeploy(body)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = "/extensions/remote-catalogs/_test",
      wantsBody = true,
      handle = (ctx, req, user, body) => handleTest(body)
    )
  )

  private def handleDeploy(optBody: Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec  = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev  = env
    optBody match {
      case None       => Results.BadRequest(Json.obj("error" -> "no body")).vfuture
      case Some(body) =>
        body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
          val payload  = bodyRaw.utf8String.parseJson
          val catalogs = payload.asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          catalogs
            .mapAsync { item =>
              val catalogId = item.select("id").asString
              val args      = item.select("args").asOpt[JsObject].getOrElse(Json.obj())
              states.catalog(catalogId) match {
                case None          =>
                  Json.obj("catalog_id" -> catalogId, "error" -> "catalog not found").vfuture
                case Some(catalog) =>
                  engine.deploy(catalog, args).map {
                    case Left(err)     => Json.obj("catalog_id" -> catalogId, "error" -> err)
                    case Right(report) => report.json
                  }
              }
            }
            .map(results => Results.Ok(JsArray(results)))
        }
    }
  }

  private def handleTest(optBody: Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec  = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev  = env
    optBody match {
      case None       => Results.BadRequest(Json.obj("error" -> "no body")).vfuture
      case Some(body) =>
        body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
          val payload   = bodyRaw.utf8String.parseJson
          val catalogId = payload.select("id").asString
          val args      = payload.select("args").asOpt[JsObject].getOrElse(Json.obj())
          states.catalog(catalogId) match {
            case None          =>
              Results.NotFound(Json.obj("error" -> "catalog not found")).vfuture
            case Some(catalog) =>
              engine.dryRun(catalog, args).map {
                case Left(err)     => Results.InternalServerError(Json.obj("error" -> err))
                case Right(report) => Results.Ok(report.json)
              }
          }
        }
    }
  }

  def startJobsIfNeeded(catalogs: Seq[RemoteCatalog]): Unit = {
    val currentIds: Seq[String] = catalogs.filter(c => c.enabled && c.scheduling.enabled).map { catalog =>
      val actualJob        = new RemoteCatalogJob(catalog.id, catalog.scheduling)
      val uniqueId: String = actualJob.uniqueId.id
      if (!handledJobs.contains(uniqueId)) {
        handledJobs.put(uniqueId, actualJob)
        env.jobManager.registerJob(actualJob)
      }
      uniqueId
    }
    handledJobs.values.toSeq.foreach { job =>
      val jobId: String = job.uniqueId.id
      if (!currentIds.contains(jobId)) {
        handledJobs.remove(jobId)
        env.jobManager.unregisterJob(job)
      }
    }
  }
}

class RemoteCatalogJob(ref: String, config: RemoteCatalogScheduling) extends Job {

  private val logger = Logger("otoroshi-remote-catalog-job")

  override def core: Boolean                     = true
  override def name: String                      = "Remote Catalog Job"
  override def description: Option[String]       = "This job deploys entities from a remote catalog".some
  override def defaultConfig: Option[JsObject]   = None
  override def visibility: otoroshi.next.plugins.api.NgPluginVisibility =
    otoroshi.next.plugins.api.NgPluginVisibility.NgUserLand
  override def categories: Seq[otoroshi.next.plugins.api.NgPluginCategory] =
    Seq(otoroshi.next.plugins.api.NgPluginCategory.Custom("Remote Catalogs"))
  override def steps: Seq[otoroshi.next.plugins.api.NgStep] = Seq(otoroshi.next.plugins.api.NgStep.Job)
  override def jobVisibility: JobVisibility      = JobVisibility.UserLand
  override def starting: JobStarting             = JobStarting.Automatically

  override def uniqueId: JobId                                                 = JobId(s"io.otoroshi.next.catalogs.RemoteCatalogJob#${ref}")
  override def kind: JobKind                                                   = config.kind
  override def instantiation(ctx: JobContext, env: Env): JobInstantiation      = config.instantiation
  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = config.initialDelay
  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration]     = config.interval
  override def cronExpression(ctx: JobContext, env: Env): Option[String]       = config.cronExpression
  override def predicate(ctx: JobContext, env: Env): Option[Boolean]           = None

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Try {
    env.adminExtensions
      .extension[RemoteCatalogAdminExtension]
      .map { ext =>
        ext.states.catalog(ref) match {
          case None          =>
            logger.error(s"No remote catalog found for ${ref}")
            Future.successful(())
          case Some(catalog) =>
            ext.engine.deploy(catalog, config.deployArgs).map {
              case Left(err)     => logger.error(s"Error deploying remote catalog ${ref}: ${err.stringify}")
              case Right(report) =>
                if (report.results.exists(_.errors.nonEmpty)) {
                  logger.warn(s"Remote catalog ${ref} deployed with some errors: ${report.json.stringify}")
                } else {
                  logger.info(s"Remote catalog ${ref} deployed successfully")
                }
            }
        }
      }
      .getOrElse(Future.successful(()))
  } match {
    case Failure(e) =>
      logger.error("error during remote catalog job run", e)
      FastFuture.successful(())
    case Success(s) => s
  }
}
