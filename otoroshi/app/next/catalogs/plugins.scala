package otoroshi.next.catalogs

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

case class RemoteCatalogDeploySingleConfig(json: JsValue = Json.obj()) extends NgPluginConfig {
  lazy val catalogRef: String = json.select("catalog_ref").asOpt[String].getOrElse("")
}

object RemoteCatalogDeploySingleConfig {
  val configFlow: Seq[String]        = Seq("catalog_ref")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "catalog_ref" -> Json.obj(
        "type"  -> "select",
        "label" -> s"Remote Catalog",
        "props" -> Json.obj(
          "optionsFrom"        -> s"/bo/api/proxy/apis/catalogs.otoroshi.io/v1/remote-catalogs",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      )
    )
  )
  val format                         = new Format[RemoteCatalogDeploySingleConfig] {
    override def reads(json: JsValue): JsResult[RemoteCatalogDeploySingleConfig]  =
      JsSuccess(RemoteCatalogDeploySingleConfig(json))
    override def writes(o: RemoteCatalogDeploySingleConfig): JsValue = o.json
  }
}

class RemoteCatalogDeploySingle extends NgBackendCall {

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Remote Catalog Deploy Single"
  override def description: Option[String]                 = "This plugin deploys entities from a single remote catalog".some
  override def defaultConfigObject: Option[NgPluginConfig] = RemoteCatalogDeploySingleConfig().some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Remote Catalogs"))
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def noJsForm: Boolean                 = true
  override def configFlow: Seq[String]           = RemoteCatalogDeploySingleConfig.configFlow
  override def configSchema: Option[JsObject]    = RemoteCatalogDeploySingleConfig.configSchema

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(RemoteCatalogDeploySingleConfig.format)
      .getOrElse(RemoteCatalogDeploySingleConfig())
    env.adminExtensions
      .extension[RemoteCatalogAdminExtension]
      .flatMap(ext => ext.states.catalog(config.catalogRef).map(c => (ext, c))) match {
      case None                       =>
        NgProxyEngineError
          .NgResultProxyEngineError(Results.NotFound(Json.obj("error" -> "catalog not found")))
          .leftf
      case Some((extension, catalog)) =>
        ctx.jsonWithTypedBody.flatMap { input =>
          extension.engine.deploy(catalog, input.asOpt[JsObject].getOrElse(Json.obj())).map {
            case Left(err)     =>
              BackendCallResponse(
                NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("error" -> err))),
                None
              ).right
            case Right(report) =>
              BackendCallResponse(
                NgPluginHttpResponse.fromResult(Results.Ok(report.json)),
                None
              ).right
          }
        }
    }
  }
}

case class RemoteCatalogDeployManyConfig(json: JsValue = Json.obj()) extends NgPluginConfig {
  lazy val catalogRefs: Seq[String] = json.select("catalog_refs").asOpt[Seq[String]].getOrElse(Seq.empty)
}

object RemoteCatalogDeployManyConfig {
  val configFlow: Seq[String]        = Seq("catalog_refs")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "catalog_refs" -> Json.obj(
        "type"  -> "array-select",
        "label" -> s"Remote Catalogs",
        "props" -> Json.obj(
          "optionsFrom"        -> s"/bo/api/proxy/apis/catalogs.otoroshi.io/v1/remote-catalogs",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      )
    )
  )
  val format                         = new Format[RemoteCatalogDeployManyConfig] {
    override def reads(json: JsValue): JsResult[RemoteCatalogDeployManyConfig]  =
      JsSuccess(RemoteCatalogDeployManyConfig(json))
    override def writes(o: RemoteCatalogDeployManyConfig): JsValue = o.json
  }
}

class RemoteCatalogDeployMany extends NgBackendCall {

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Remote Catalog Deploy Many"
  override def description: Option[String]                 = "This plugin deploys entities from multiple remote catalogs".some
  override def defaultConfigObject: Option[NgPluginConfig] = RemoteCatalogDeployManyConfig().some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Remote Catalogs"))
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def noJsForm: Boolean                 = true
  override def configFlow: Seq[String]           = RemoteCatalogDeployManyConfig.configFlow
  override def configSchema: Option[JsObject]    = RemoteCatalogDeployManyConfig.configSchema

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(RemoteCatalogDeployManyConfig.format)
      .getOrElse(RemoteCatalogDeployManyConfig())
    env.adminExtensions
      .extension[RemoteCatalogAdminExtension] match {
      case None      =>
        NgProxyEngineError
          .NgResultProxyEngineError(Results.NotFound(Json.obj("error" -> "extension not found")))
          .leftf
      case Some(ext) =>
        ctx.jsonWithTypedBody.flatMap { input =>
          val items = input.asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          items
            .filter(item => config.catalogRefs.contains(item.select("id").asOpt[String].getOrElse("")))
            .mapAsync { item =>
              val catalogId = item.select("id").asString
              val args      = item.select("args").asOpt[JsObject].getOrElse(Json.obj())
              ext.states.catalog(catalogId) match {
                case None          =>
                  Json.obj("catalog_id" -> catalogId, "error" -> "catalog not found").vfuture
                case Some(catalog) =>
                  ext.engine.deploy(catalog, args).map {
                    case Left(err)     => Json.obj("catalog_id" -> catalogId, "error" -> err)
                    case Right(report) => report.json
                  }
              }
            }
            .map { results =>
              BackendCallResponse(
                NgPluginHttpResponse.fromResult(Results.Ok(JsArray(results))),
                None
              ).right
            }
        }
    }
  }
}

case class RemoteCatalogDeployWebhookConfig(json: JsValue = Json.obj()) extends NgPluginConfig {
  lazy val catalogRefs: Seq[String] = json.select("catalog_refs").asOpt[Seq[String]].getOrElse(Seq.empty)
  lazy val sourceType: String       = json.select("source_type").asOpt[String].getOrElse("github")
}

object RemoteCatalogDeployWebhookConfig {
  val configFlow: Seq[String]        = Seq("catalog_refs", "source_type")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "catalog_refs" -> Json.obj(
        "type"  -> "array-select",
        "label" -> s"Remote Catalogs",
        "props" -> Json.obj(
          "optionsFrom"        -> s"/bo/api/proxy/apis/catalogs.otoroshi.io/v1/remote-catalogs",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      ),
      "source_type" -> Json.obj(
        "type"  -> "select",
        "label" -> "Source type",
        "props" -> Json.obj(
          "options" -> Json.arr(
            Json.obj("label" -> "GitHub", "value" -> "github"),
            Json.obj("label" -> "GitLab", "value" -> "gitlab")
          )
        )
      )
    )
  )
  val format                         = new Format[RemoteCatalogDeployWebhookConfig] {
    override def reads(json: JsValue): JsResult[RemoteCatalogDeployWebhookConfig]  =
      JsSuccess(RemoteCatalogDeployWebhookConfig(json))
    override def writes(o: RemoteCatalogDeployWebhookConfig): JsValue = o.json
  }
}

class RemoteCatalogDeployWebhook extends NgBackendCall {

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Remote Catalog Deploy Webhook"
  override def description: Option[String]                 =
    "This plugin handles webhooks from Git providers to deploy entities from remote catalogs".some
  override def defaultConfigObject: Option[NgPluginConfig] = RemoteCatalogDeployWebhookConfig().some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Remote Catalogs"))
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def noJsForm: Boolean                 = true
  override def configFlow: Seq[String]           = RemoteCatalogDeployWebhookConfig.configFlow
  override def configSchema: Option[JsObject]    = RemoteCatalogDeployWebhookConfig.configSchema

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(RemoteCatalogDeployWebhookConfig.format)
      .getOrElse(RemoteCatalogDeployWebhookConfig())
    env.adminExtensions
      .extension[RemoteCatalogAdminExtension] match {
      case None      =>
        NgProxyEngineError
          .NgResultProxyEngineError(Results.NotFound(Json.obj("error" -> "extension not found")))
          .leftf
      case Some(ext) =>
        ctx.jsonWithTypedBody.flatMap { payload =>
          CatalogSources.source(config.sourceType) match {
            case None         =>
              BackendCallResponse(
                NgPluginHttpResponse.fromResult(
                  Results.BadRequest(Json.obj("error" -> s"Unknown source type: ${config.sourceType}"))
                ),
                None
              ).rightf
            case Some(source) if !source.supportsWebhook =>
              BackendCallResponse(
                NgPluginHttpResponse.fromResult(
                  Results.BadRequest(Json.obj("error" -> s"Source ${config.sourceType} does not support webhooks"))
                ),
                None
              ).rightf
            case Some(source) =>
              val possibleCatalogs = ext.states.allCatalogs().filter { c =>
                c.enabled && config.catalogRefs.contains(c.id)
              }
              source.webhookDeploySelect(possibleCatalogs, payload).flatMap {
                case Left(err)       =>
                  BackendCallResponse(
                    NgPluginHttpResponse.fromResult(Results.BadRequest(Json.obj("error" -> err))),
                    None
                  ).rightf
                case Right(catalogs) =>
                  catalogs
                    .mapAsync { catalog =>
                      source.webhookDeployExtractArgs(catalog, payload).flatMap {
                        case Left(err)   =>
                          Json.obj("catalog_id" -> catalog.id, "error" -> err).vfuture
                        case Right(args) =>
                          ext.engine.deploy(catalog, args).map {
                            case Left(err)     => Json.obj("catalog_id" -> catalog.id, "error" -> err)
                            case Right(report) => report.json
                          }
                      }
                    }
                    .map { results =>
                      BackendCallResponse(
                        NgPluginHttpResponse.fromResult(Results.Ok(JsArray(results))),
                        None
                      ).right
                    }
              }
          }
        }
    }
  }
}
