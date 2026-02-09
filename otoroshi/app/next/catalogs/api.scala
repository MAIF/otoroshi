package otoroshi.next.catalogs

import org.joda.time.DateTime
import otoroshi.api.{Resource, WriteAction}
import otoroshi.env.Env
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.JsError.toJson
import play.api.libs.json._
import play.api.mvc.Results
import otoroshi.utils.yaml.Yaml

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class RemoteEntity(id: String, kind: String, source: String, syncAt: DateTime, content: JsObject)

object RemoteEntity {
  def fromJson(source: String, json: JsObject): Option[RemoteEntity] = {
    for {
      entityId <- json.select("id").asOpt[String]
        .orElse(json.select("clientId").asOpt[String])
        .orElse(json.select("serviceId").asOpt[String])
      kind <- json.select("kind").asOpt[String]
    } yield {
      RemoteEntity(
        id = entityId,
        kind = kind,
        source = source,
        syncAt = DateTime.now(),
        content = json
      )
    }
  }
}

trait CatalogSource {
  def sourceKind: String
  def supportsWebhook: Boolean
  def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteCatalog]]]
  def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, JsObject]]
  def fetch(catalog: RemoteCatalog, args: JsObject)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Either[JsValue, Seq[RemoteEntity]]]
}

object CatalogSources {
  private val possibleSources = new UnboundedTrieMap[String, CatalogSource]()

  def initDefaults(): Unit = {
    registerSource("file", new CatalogSourceFile())
    registerSource("http", new CatalogSourceHttp())
    registerSource("github", new CatalogSourceGithub())
    registerSource("gitlab", new CatalogSourceGitlab())
    registerSource("s3", new CatalogSourceS3())
    registerSource("consulkv", new CatalogSourceConsulKv())
    registerSource("bitbucket", new CatalogSourceBitbucket())
    registerSource("git", new CatalogSourceGit())
  }

  def registerSource(name: String, source: CatalogSource): Unit = {
    possibleSources.put(name, source)
  }

  def source(name: String): Option[CatalogSource] = possibleSources.get(name)
}

object RemoteContentParser {

  private val logger = Logger("otoroshi-remote-catalog-parser")

  def parse(content: JsValue, sourceName: String, allResources: Seq[Resource]): Seq[RemoteEntity] = {
    content match {
      case obj: JsObject => parseObject(obj, sourceName, allResources)
      case arr: JsArray  => parseArray(arr, sourceName)
      case _             =>
        logger.warn(s"Unsupported content format from source $sourceName")
        Seq.empty
    }
  }

  def parseRawContent(rawContent: String, sourceName: String, allResources: Seq[Resource]): Seq[RemoteEntity] = {
    Try(Json.parse(rawContent)).toOption match {
      case Some(json) => parse(json, sourceName, allResources)
      case None       =>
        splitContent(rawContent).filter(_.trim.nonEmpty).flatMap { doc =>
          Yaml.parse(doc) match {
            case Some(json) => parse(json, sourceName, allResources)
            case None       =>
              logger.warn(s"Cannot parse content from $sourceName as JSON or YAML")
              Seq.empty
          }
        }
    }
  }

  private def splitContent(content: String): Seq[String] = {
    var out     = Seq.empty[String]
    var current = Seq.empty[String]
    val lines   = content.split("\n")
    lines.foreach { line =>
      if (line.trim == "---") {
        out = out :+ current.mkString("\n")
        current = Seq.empty[String]
      } else {
        current = current :+ line
      }
    }
    if (current.nonEmpty)
      out = out :+ current.mkString("\n")
    out
  }

  private def parseObject(obj: JsObject, sourceName: String, allResources: Seq[Resource]): Seq[RemoteEntity] = {
    RemoteEntity.fromJson(sourceName, obj).toSeq
  }

  private def parseArray(arr: JsArray, sourceName: String): Seq[RemoteEntity] = {
    arr.value.flatMap {
      case obj: JsObject => RemoteEntity.fromJson(sourceName, obj)
      case _             => None
    }
  }
}

case class ReconcileResult(entityKind: String, created: Int, updated: Int, deleted: Int, errors: Seq[String]) {
  def json: JsValue = Json.obj(
    "entity_kind" -> entityKind,
    "created"     -> created,
    "updated"     -> updated,
    "deleted"     -> deleted,
    "errors"      -> JsArray(errors.map(JsString.apply))
  )
}

case class DeployReport(catalogId: String, results: Seq[ReconcileResult], timestamp: DateTime) {
  def json: JsValue = Json.obj(
    "catalog_id" -> catalogId,
    "results"    -> JsArray(results.map(_.json)),
    "timestamp"  -> timestamp.toString
  )
}

class RemoteCatalogEngine(env: Env) {

  private val logger            = Logger("otoroshi-remote-catalog-engine")
  private val deployingCatalogs = new UnboundedTrieMap[String, Boolean]()

  private def findAllResources(): Seq[Resource] = {
    env.allResources.resources ++ env.adminExtensions.resources()
  }

  private def findResource(kind: String): Option[Resource] = {
    val resources = findAllResources()
    if (kind.contains("/")) {
      val parts = kind.split("/")
      val group = parts(0)
      val kd = parts(1)
      resources.find(r => r.kind == kd && r.group == group)
    } else {
      resources.find(r => r.kind == kind)
    }
  }

  def deploy(catalog: RemoteCatalog, args: JsObject)(implicit
      ec: ExecutionContext,
      ev: Env
  ): Future[Either[JsValue, DeployReport]] = {
    if (deployingCatalogs.contains(catalog.id)) {
      Json.obj("error" -> s"Catalog ${catalog.id} is already being deployed").leftf
    } else {
      deployingCatalogs.put(catalog.id, true)
      logger.info(s"deploying catalog ${catalog.id} / ${catalog.sourceKind}")
      doFetchAndReconcile(catalog, args, dryRun = false).andThen { case _ =>
        deployingCatalogs.remove(catalog.id)
      }
    }
  }

  def dryRun(catalog: RemoteCatalog, args: JsObject)(implicit
      ec: ExecutionContext,
      ev: Env
  ): Future[Either[JsValue, DeployReport]] = {
    doFetchAndReconcile(catalog, args, dryRun = true)
  }

  private def doFetchAndReconcile(catalog: RemoteCatalog, args: JsObject, dryRun: Boolean)(implicit
      ec: ExecutionContext,
      ev: Env
  ): Future[Either[JsValue, DeployReport]] = {
    CatalogSources.source(catalog.sourceKind) match {
      case None         =>
        Json.obj("error" -> s"Unknown source kind: ${catalog.sourceKind}").leftf
      case Some(source) =>
        source.fetch(catalog, args).flatMap {
          case Left(err)       => err.leftf
          case Right(entities) => reconcile(catalog, entities, dryRun).map(_.right)
        }
    }
  }

  private def reconcile(catalog: RemoteCatalog, remoteEntities: Seq[RemoteEntity], dryRun: Boolean)(implicit
      ec: ExecutionContext,
      ev: Env
  ): Future[DeployReport] = {
    val grouped = env.allResources.resources
      .map(resource => (resource, remoteEntities.filter(re => re.kind == resource.groupKind || re.kind == resource.kind)))
      .filter(_._2.nonEmpty)
    grouped
      .mapAsync { case (resource, entities) =>
        reconcileResource(catalog, resource.groupKind, resource, entities, dryRun)
      }
      .map { results =>
        DeployReport(
          catalogId = catalog.id,
          results = results,
          timestamp = DateTime.now()
        )
      }
  }

  private def reconcileKind(
      catalog: RemoteCatalog,
      kind: String,
      entities: Seq[RemoteEntity],
      dryRun: Boolean
  )(implicit ec: ExecutionContext, ev: Env): Future[ReconcileResult] = {
    findResource(kind) match {
      case None           =>
        ReconcileResult(kind, 0, 0, 0, Seq(s"Unknown resource kind: $kind")).vfuture
      case Some(resource) =>
        reconcileResource(catalog, kind, resource, entities, dryRun)
    }
  }

  private def reconcileResource(
      catalog: RemoteCatalog,
      kind: String,
      resource: Resource,
      entities: Seq[RemoteEntity],
      dryRun: Boolean
  )(implicit ec: ExecutionContext, ev: Env): Future[ReconcileResult] = {
    val metadataKey = s"remote_catalog=${catalog.id}"
    var created     = 0
    var updated     = 0
    var errors      = Seq.empty[String]
    val remoteIds   = entities.map(_.id).toSet
    entities
      .mapAsync { entity =>
        val entityId     = entity.id
        val key          = resource.access.key(entityId)
        resource.access.validateToJson(entity.content, resource.singularName, None.right) match {
          case err@JsError(_) =>
            errors = errors :+ s"Error upserting entity $entityId of kind $kind: ${Json.stringify(toJson(err))}"
            ().vfuture
          case JsSuccess(body, _) => {
            val enrichedJson = enrichWithMetadata(body.asObject, metadataKey)
            (resource.access.oneJson(entityId) match {
              case None    =>
                created += 1
                if (!dryRun) {
                  resource.access.create(resource.version.name, resource.singularName, entityId.some, enrichedJson, WriteAction.Create, None).map(_ => ())
                } else {
                  ().vfuture
                }
              case Some(old) =>
                updated += 1
                if (!dryRun) {
                  resource.access.create(resource.version.name, resource.singularName, entityId.some, enrichedJson, WriteAction.Update, old.some).map(_ => ())
                } else {
                  ().vfuture
                }
            }).recover { case e: Throwable =>
              errors = errors :+ s"Error upserting entity $entityId of kind $kind: ${e.getMessage}"
            }
          }
        }
      }.flatMap { _ =>
        handleDeletions(catalog, kind, resource, remoteIds, metadataKey, dryRun).map { deletedCount =>
          ReconcileResult(kind, created, updated, deletedCount, errors)
        }
      }
  }

  private def handleDeletions(
      catalog: RemoteCatalog,
      kind: String,
      resource: Resource,
      remoteIds: Set[String],
      metadataKey: String,
      dryRun: Boolean
  )(implicit ec: ExecutionContext, ev: Env): Future[Int] = {
    Try {
      val allEntities = resource.access.allJson()
      val managedEntities = allEntities.filter { json =>
        json.select("metadata").asOpt[Map[String, String]]
          .exists(_.get("created_by").contains(metadataKey))
      }
      val toDelete = managedEntities.filter { json =>
        val entityId = json.select("id").asOpt[String]
          .orElse(json.select("clientId").asOpt[String])
          .orElse(json.select("serviceId").asOpt[String])
          .getOrElse("")
        !remoteIds.contains(entityId)
      }
      if (toDelete.isEmpty) {
        0.vfuture
      } else if (dryRun) {
        toDelete.size.vfuture
      } else {
        val idsToDelete = toDelete.map { json =>
          json.select("id").asOpt[String]
            .orElse(json.select("clientId").asOpt[String])
            .orElse(json.select("serviceId").asOpt[String])
            .getOrElse("")
        }
        resource.access.deleteMany(resource.version.name, idsToDelete).map(_ => toDelete.size)
      }
    }.getOrElse {
      logger.warn(s"Failed to handle deletions for kind $kind, catalog ${catalog.id}")
      0.vfuture
    }
  }

  private def enrichWithMetadata(json: JsObject, metadataKey: String): JsObject = {
    val currentMetadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty)
    val enrichedMetadata = currentMetadata + ("created_by" -> metadataKey)
    json ++ Json.obj("metadata" -> enrichedMetadata)
  }
}
