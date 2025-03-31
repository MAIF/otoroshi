package otoroshi.next.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, RawCertificate}
import otoroshi.utils.controllers._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}

class NgRoutesController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[NgRoute, JsValue]
    with CrudControllerHelper[NgRoute, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-routes-api")

  override def singularName: String = "route"

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: NgRoute): String = entity.id

  override def readEntity(json: JsValue): Either[JsValue, NgRoute] =
    NgRoute.fmt.reads(json).asEither match {
      case Left(e)  => Left(JsError.toJson(e))
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: NgRoute): JsValue = NgRoute.fmt.writes(entity)

  override def findByIdOps(
      id: String,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[NgRoute]]] = {
    env.datastores.routeDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_ROUTE",
          message = "User accessed a route",
          metadata = Json.obj("RouteId" -> id),
          alert = "RouteAccessed"
        )
      )
    }
  }

  override def findAllOps(
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[NgRoute]]] = {
    env.datastores.routeDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_ROUTES",
          message = "User accessed all routes",
          metadata = Json.obj(),
          alert = "RoutesAccessed"
        )
      )
    }
  }

  override def createEntityOps(
      entity: NgRoute,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[NgRoute]]] = {
    env.datastores.routeDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_ROUTE",
            message = "User created a route",
            metadata = entity.json.as[JsObject],
            alert = "RouteCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "route not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
      entity: NgRoute,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[NgRoute]]] = {
    env.datastores.routeDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_ROUTE",
            message = "User updated a route",
            metadata = entity.json.as[JsObject],
            alert = "RouteUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "route not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
      id: String,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[NgRoute]]] = {
    env.datastores.routeDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_ROUTE",
            message = "User deleted a route",
            metadata = Json.obj("RouteId" -> id),
            alert = "RouteDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "route not deleted ...")
          )
        )
      }
    }
  }

  def form() = ApiAction {
    env.openApiSchema.asForms.get("otoroshi.next.models.NgRoute") match {
      case Some(value) =>
        Ok(
          Json.obj(
            "schema" -> value.schema,
            "flow"   -> value.flow
          )
        )
      case _           => NotFound(Json.obj("error" -> "Schema and flow not found"))
    }
  }

  def initiateRoute() = ApiAction {
    val defaultRoute = NgRoute(
      location = EntityLocation.default,
      id = s"route_${IdGenerator.uuid}",
      name = "New route",
      description = "A new route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      groups = Seq("default"),
      frontend = NgFrontend(
        domains = Seq(NgDomainAndPath(env.routeBaseDomain)),
        headers = Map.empty,
        query = Map.empty,
        methods = Seq.empty,
        stripPath = true,
        exact = false
      ),
      backend = NgBackend(
        targets = Seq(
          NgTarget(
            id = "target_1",
            hostname = "request.otoroshi.io",
            port = 443,
            tls = true,
            backup = false
          )
        ),
        root = "/",
        rewrite = false,
        loadBalancing = RoundRobin,
        client = NgClientConfig.default
      ),
      plugins = NgPlugins(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          )
        )
      )
    )
    env.datastores.globalConfigDataStore
      .latest()
      .templates
      .route
      .map { template =>
        Ok(defaultRoute.json.asObject.deepMerge(template))
      }
      .getOrElse {
        Ok(defaultRoute.json)
      }
  }

  def domainsAndCertificates() = ApiAction { ctx =>
    import otoroshi.ssl.SSLImplicits._

    val routes           = env.proxyState.allRoutes()
    val domains          = routes.flatMap(_.frontend.domains).map(_.domainLowerCase).distinct
    val certs            = env.proxyState.allCertificates()
    val jsonDomains      = domains
      .map { domain =>
        val certsForDomain = certs.filter(c => c.matchesDomain(domain))
        Json.obj(domain -> JsArray(certsForDomain.map(_.id.json)))
      }
      .fold(Json.obj())(_ ++ _)
    def certToJson(cert: Cert): Option[JsValue] = {
      RawCertificate.fromChainAndKey(cert.chain, cert.privateKey) match {
        case None        => None
        case Some(rcert) => {
          val ecs: Seq[String] = rcert.certificatesChain.map(_.encoded)
          Json
            .obj(
              "id"      -> cert.id,
              "chain"   -> ecs,
              "key"     -> rcert.cryptoKeyPair.getPrivate.encoded,
              "domains" -> domains.filter(d => cert.matchesDomain(d)).distinct,
              "sans"    -> cert.allDomains
            )
            .some
        }
      }
    }
    val jsonCerts        =
      JsArray(certs.filter(_.isUsable).filterNot(_.ca).filterNot(_.keypair).filterNot(_.client).flatMap(certToJson))
    val jsonTrustedCerts = JsArray(
      env.datastores.globalConfigDataStore
        .latest()
        .tlsSettings
        .trustedCAsServer
        .flatMap(id => env.proxyState.certificate(id))
        .flatMap(certToJson)
    )
    Ok(
      Json.obj(
        "trusted_certificates" -> jsonTrustedCerts,
        "certificates"         -> jsonCerts,
        "domains"              -> jsonDomains,
        "tls_settings"         -> env.datastores.globalConfigDataStore.latest().tlsSettings.json
      )
    )
  }
}
