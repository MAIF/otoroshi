package next.models

import akka.util.ByteString
import diffson.PatchOps
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport, LoadBalancing, RemainingQuotas}
import otoroshi.next.models._
import otoroshi.next.plugins.NgApikeyCallsConfig
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue}
import play.api.libs.json.{Format, JsArray, JsBoolean, JsError, JsNull, JsNumber, JsObject, JsResult, JsString, JsSuccess, JsValue, Json}

import scala.util.{Failure, Success, Try}

sealed trait ApiState {
  def name: String
}

case object ApiStarted extends ApiState {
  def name: String = "started"
}
case object ApiPublished extends ApiState {
  def name: String = "published"
}
case object ApiDeprecated extends ApiState {
  def name: String = "deprecated"
}
case object ApiRemoved extends ApiState {
  def name: String = "removed"
}
//
//object ApiFrontend {
//  val _fmt = new Format[ApiFrontend] {
//
//    override def reads(json: JsValue): JsResult[ApiFrontend] = Try {
//      val optDomain = json.select("domain").asOptString.map(NgDomainAndPath.apply)
//      ApiFrontend(
//        domains = optDomain
//          .map(d => Seq(d))
//          .orElse(json.select("domains").asOpt[Seq[String]].map(_.map(NgDomainAndPath.apply)))
//          .getOrElse(Seq.empty),
//        stripPath = json.select("strip_path").asOpt[Boolean].getOrElse(true),
//        exact = json.select("exact").asOpt[Boolean].getOrElse(false),
//        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
//        query = json.select("query").asOpt[Map[String, String]].getOrElse(Map.empty),
//        methods = json.select("methods").asOpt[Seq[String]].getOrElse(Seq.empty)
//      )
//    } match {
//      case Failure(ex)    =>
//        ex.printStackTrace()
//        JsError(ex.getMessage)
//      case Success(value) => JsSuccess(value)
//    }
//
//    override def writes(o: ApiFrontend): JsValue = Json.obj(
//      "domains"    -> JsArray(o.domains.map(_.json)),
//      "strip_path" -> o.stripPath,
//      "exact"      -> o.exact,
//      "headers"    -> o.headers,
//      "query"      -> o.query,
//      "methods"    -> o.methods
//    )
//  }
//}

case class ApiRoute(id: String, name: Option[String], frontend: NgFrontend, flowRef: String, backend: ApiBackend)

object ApiRoute {
  val _fmt = new Format[ApiRoute] {

    override def reads(json: JsValue): JsResult[ApiRoute] = Try {
      ApiRoute(
        id = json.select("id").asString,
        name = json.select("name").asOptString,
        frontend = NgFrontend.readFrom(json \ "frontend"),
        flowRef = (json \ "flow_ref").asString,
        backend = (json \ "backend").as[ApiBackend](ApiBackend._fmt)
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiRoute): JsValue = Json.obj(
      "id"        -> o.id,
      "name"      -> o.name,
      "frontend"  -> o.frontend.json,
      "backend"   -> ApiBackend._fmt.writes(o.backend),
      "flow_ref"  -> o.flowRef
    )
  }
}

case class ApiPredicate()

case class ApiFlows(id: String, name: String, /*predicate: ApiPredicate,*/ plugins: NgPlugins)

object ApiFlows {
  val _fmt = new Format[ApiFlows] {

    override def reads(json: JsValue): JsResult[ApiFlows] = Try {
      ApiFlows(
        id = json.select("id").asString,
        name = json.select("name").asString,
        plugins = NgPlugins.readFrom(json.select("plugins"))
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiFlows): JsValue = Json.obj(
      "id" -> o.id,
      "name" -> o.name,
      "plugins" -> o.plugins.json
    )
  }
}

case class ApiDeployment(
    location: EntityLocation,
    id: String,
    apiRef: String,
    owner: String,
    at: DateTime,
    apiDefinition: JsValue
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = ApiDeployment._fmt.writes(this)
  override def theName: String                  = id
  override def theDescription: String           = id
  override def theTags: Seq[String]             = Seq.empty
  override def theMetadata: Map[String, String] = Map.empty
}

object ApiDeployment {
  val _fmt: Format[ApiDeployment] = new Format[ApiDeployment] {
    override def reads(json: JsValue): JsResult[ApiDeployment] = Try {
      ApiDeployment(
        location = json.select("location").as(EntityLocation.format),
        id = json.select("id").asString,
        apiRef = json.select("apiRef").asString,
        owner = json.select("owner").asString,
        at = json.select("at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        apiDefinition = json.select("apiDefinition").as[JsValue]
      )
    } match {
      case Failure(ex) =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: ApiDeployment): JsValue             = Json.obj(
      "location"    -> o.location.json,
      "id"          -> o.id,
      "apiRef"      -> o.apiRef,
      "owner"       -> o.owner,
      "at"          -> o.at.getMillis,
      "apiDefinition" -> o.apiDefinition
    )
  }
}

sealed trait ApiSpecification {
  def content: JsValue
  def name: String
}
object ApiSpecification {
  case class OpenApiSpecification(content: JsValue, name: String) extends ApiSpecification

  case class AsyncApiSpecification(content: JsValue, name: String) extends ApiSpecification

  val _fmt: Format[ApiSpecification] = new Format[ApiSpecification] {
    override def reads(json: JsValue): JsResult[ApiSpecification] = Try {
      val content = json.select("content").as[JsValue]

      json.select("name").asString match {
        case name if name == "OpenApiSpecification" => OpenApiSpecification(content, name)
        case _ => AsyncApiSpecification(content, name = "AsyncApiSpecification")
      }
    } match {
      case Failure(ex) =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiSpecification): JsValue = Json.obj(
      "name" -> o.name,
      "content" -> o.content,
    )
  }
}

trait ApiPage {
  def path: String
  def name: String
}

object ApiPage {
  case class ApiPageDir(path: String, name: String, leafs: Seq[ApiPage]) extends ApiPage
  case class ApiPageLeaf(path: String, name: String, content: ByteString) extends ApiPage

  val _fmt: Format[ApiPage] = new Format[ApiPage] {
    override def reads(json: JsValue): JsResult[ApiPage] = Try {
      val path = json.select("path").asString

      json.select("name").asString match {
        case name if name == "ApiPageDir" => ApiPageDir(path, name, leafs = (json \ "leafs")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiPage._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty))
        case _ => ApiPageLeaf(path, "ApiPageLeaf", content = ByteString(json.select("content").asString))
      }
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiPage): JsValue = {
      o match {
        case ApiPageDir(path, name, leafs) => Json.obj(
          "name" -> name,
          "path" -> path,
          "leafs" -> leafs.map(ApiPage._fmt.writes)
        )
        case ApiPageLeaf(path, name, content) => Json.obj(
          "name" -> name,
          "path" -> path,
          "content" -> content
        )
      }
    }
  }
}

case class ApiDocumentation(
    specification: ApiSpecification,
    home: ApiPage,
    pages: Seq[ApiPage],
    metadata: Map[String, String],
    logos: Seq[ByteString]
)

object ApiDocumentation {
  val _fmt: Format[ApiDocumentation] = new Format[ApiDocumentation] {

    override def reads(json: JsValue): JsResult[ApiDocumentation] = Try {
      ApiDocumentation(
        specification = json.select("specification").as(ApiSpecification._fmt),
        home = json.select("home").as(ApiPage._fmt),
        pages = (json \ "pages")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiPage._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        logos = (json \ "logos")
          .asOpt[Seq[String]]
          .map(seq => seq.map(ByteString.apply))
          .getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiDocumentation): JsValue = Json.obj(
      "specification" -> ApiSpecification._fmt.writes(o.specification),
      "home"      -> ApiPage._fmt.writes(o.home),
      "pages"     -> o.pages.map(ApiPage._fmt.writes),
      "metadata"  -> o.metadata,
      "logos"     -> o.logos,
    )
  }
}

trait ApiBlueprint {
  def name: String
}

object ApiBlueprint {
  case object REST      extends ApiBlueprint { def name: String = "REST"      }
  case object GraphQL   extends ApiBlueprint { def name: String = "GraphQL"   }
  case object gRPC      extends ApiBlueprint { def name: String = "gRPC"      }
  case object Http      extends ApiBlueprint { def name: String = "Http"      }
  case object Websocket extends ApiBlueprint { def name: String = "Websocket" }
}

case class ApiConsumer(
    id: String,
    name: String,
    description: Option[String],
    autoValidation: Boolean,
    consumerKind: ApiConsumerKind,
    settings: ApiConsumerSettings,
    status: ApiConsumerStatus,
    subscriptions: Seq[ApiConsumerSubscriptionRef]
)

object ApiConsumer {
  val _fmt: Format[ApiConsumer] = new Format[ApiConsumer] {
    override def reads(json: JsValue): JsResult[ApiConsumer] = Try {
      ApiConsumer(
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOptString,
        autoValidation = json.select("autoValidation").asOpt[Boolean].getOrElse(false),
        consumerKind = json.select("consumer_kind").asOptString.map(_.toLowerCase match {
          case "apikey"  => ApiConsumerKind.Apikey
          case "mtls"    => ApiConsumerKind.Mtls
          case "keyless" => ApiConsumerKind.Keyless
          case "oauth2"  => ApiConsumerKind.OAuth2
          case "jwt"     => ApiConsumerKind.JWT
        }).getOrElse(ApiConsumerKind.Apikey),
        settings = (json \ "settings" \ "name").asString match {
          case "apikey"   => {
            ApiConsumerSettings.Apikey(
              throttlingQuota = (json \ "settings" \ "config" \ "throttlingQuota").as[Long],
              monthlyQuota = (json \ "settings" \ "config" \ "monthlyQuota").as[Long],
              dailyQuota = (json \ "settings" \ "config" \ "dailyQuota").as[Long],
              name = "apikey")
          }
          case "mtls"     => ApiConsumerSettings.Mtls(
            caRefs = (json \ "settings" \ "config" \ "caRefs").as[Seq[String]],
            certRefs = (json \ "settings" \ "config" \ "certRefs").as[Seq[String]],
            name = "mtls"
          )
          case "keyless"  => ApiConsumerSettings.Keyless(name = "Keyless")
          case "oauth2"   => ApiConsumerSettings.OAuth2(
            config = (json \ "settings" \ "config").as[JsValue],
            name = "oauth2")
          case "jwt"      => ApiConsumerSettings.JWT(
            jwtVerifierRefs = (json \ "settings" \ "config" \ "jwtVerifierRefs").as[Seq[String]],
            name = "jwt")
        },
        status = json.select("status").asString.toLowerCase match {
          case "staging"      => ApiConsumerStatus.Staging()
          case "published"    => ApiConsumerStatus.Published()
          case "deprecated"   => ApiConsumerStatus.Deprecated()
          case "closed"       => ApiConsumerStatus.Closed()
        },
        subscriptions = json.select("subscriptions")
          .asOpt[Seq[String]]
          .map(refs => refs.map(ref => ApiConsumerSubscriptionRef(ref)))
          .getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiConsumer): JsValue = Json.obj(
      "id" -> o.id,
      "name" -> o.name,
      "description" -> o.description,
      "autoValidation" -> o.autoValidation,
      "consumer_kind" -> o.consumerKind.name,
      "settings" -> o.settings.json,
      "status" -> o.status.name,
      "subscriptions" -> o.subscriptions.map(_.ref)
    )
  }
}

case class ApiConsumerSubscriptionDates(
    created_at: DateTime,
    processed_at: DateTime,
    started_at: DateTime,
    ending_at: DateTime,
    closed_at: DateTime
)

object ApiConsumerSubscriptionDates {
  val _fmt: Format[ApiConsumerSubscriptionDates] = new Format[ApiConsumerSubscriptionDates] {
    override def reads(json: JsValue): JsResult[ApiConsumerSubscriptionDates] = Try {
      ApiConsumerSubscriptionDates(
        created_at    = json.select("created_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        processed_at  = json.select("processed_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        started_at    = json.select("started_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        ending_at     = json.select("ending_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        closed_at     = json.select("closed_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now())
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiConsumerSubscriptionDates): JsValue = Json.obj(
      "created_at"    -> o.created_at.getMillis,
      "processed_at"  -> o.processed_at.getMillis,
      "started_at"    -> o.started_at.getMillis,
      "ending_at"     -> o.ending_at.getMillis,
      "closed_at"     -> o.closed_at.getMillis
    )
  }
}

case class ApiConsumerSubscription(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    enabled: Boolean,
    dates: ApiConsumerSubscriptionDates,
    ownerRef: String,
    consumerRef: String,
    apiRef: String,
    subscriptionKind: ApiConsumerKind,
    tokenRefs: Seq[String] // ref to apikey, cert, etc
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = ApiConsumerSubscription.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object ApiConsumerSubscription {
  val format = new Format[ApiConsumerSubscription] {
    override def reads(json: JsValue): JsResult[ApiConsumerSubscription] = Try {
      ApiConsumerSubscription(
        location    = json.select("location").as(EntityLocation.format),
        id          = json.select("id").asString,
        name        = json.select("name").asString,
        description = json.select("description").asString,
        tags        = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata    = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        enabled     = json.select("enabled").asOpt[Boolean].getOrElse(false),
        dates       = json.select("dates").as(ApiConsumerSubscriptionDates._fmt),
        ownerRef    = json.select("owner_ref").asString,
        consumerRef = json.select("consumer_ref").asString,
        subscriptionKind = json.select("subscription_kind").asString.toLowerCase match {
          case "apikey"  => ApiConsumerKind.Apikey
          case "mtls"    => ApiConsumerKind.Mtls
          case "keyless" => ApiConsumerKind.Keyless
          case "oauth2"  => ApiConsumerKind.OAuth2
          case "jwt"     => ApiConsumerKind.JWT
        },
        apiRef      = json.select("api_ref").asString,
        tokenRefs   = json.select("token_refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: ApiConsumerSubscription): JsValue             = Json.obj(
        "location"      -> o.location.json,
        "id"            -> o.id,
        "name"          -> o.name,
        "description"   -> o.description,
        "tags"          -> o.tags,
        "metadata"      -> o.metadata,
        "enabled"       -> o.enabled,
        "dates"         -> ApiConsumerSubscriptionDates._fmt.writes(o.dates),
        "owner_ref"     -> o.ownerRef,
        "consumer_ref"  -> o.consumerRef,
        "api_ref"       -> o.apiRef,
        "subscription_kind"          -> o.subscriptionKind.name,
        "token_refs"    -> o.tokenRefs
    )
  }
}

case class ApiConsumerSubscriptionRef(ref: String)

trait ApiConsumerKind {
  def name: String
}
object ApiConsumerKind {
  case object Apikey  extends ApiConsumerKind {
    override def name: String ="apikey"
  }
  case object Mtls    extends ApiConsumerKind {
    override def name: String ="mtls"
  }
  case object Keyless extends ApiConsumerKind {
    override def name: String ="keyless"
  }
  case object OAuth2  extends ApiConsumerKind {
    override def name: String ="oauth2"
  }
  case object JWT     extends ApiConsumerKind {
    override def name: String ="jwt"
  }
}

trait ApiConsumerSettings {
  def name: String
  def json: JsValue
}
object ApiConsumerSettings {
  case class Apikey(name: String,
                    throttlingQuota: Long = RemainingQuotas.MaxValue,
                    dailyQuota: Long = RemainingQuotas.MaxValue,
                    monthlyQuota: Long = RemainingQuotas.MaxValue)              extends ApiConsumerSettings {
    def json: JsValue = Json.obj(
      "name"            -> name,
      "config"         -> Json.obj(
        "throttlingQuota"  -> throttlingQuota,
        "dailyQuota"      -> dailyQuota,
        "monthlyQuota"    -> monthlyQuota
      ),
    )
  }
  case class Mtls(name: String, caRefs: Seq[String], certRefs: Seq[String]) extends ApiConsumerSettings {
    def json: JsValue = Json.obj(
      "name"      -> name,
      "config"    -> Json.obj(
        "caRefs"    -> caRefs,
        "certRefs"  -> certRefs,
      )
    )
  }
  case class Keyless(name: String)                                          extends ApiConsumerSettings {
    def json: JsValue = Json.obj(
      "name"      -> name,
      "config"         -> Json.obj()
    )
  }
  case class OAuth2(name: String, config: JsValue)              extends ApiConsumerSettings { // using client credential stuff
    def json: JsValue = Json.obj(
      "name"      -> name,
      "config"    -> config
    )
  }
  case class JWT(name: String, jwtVerifierRefs: Seq[String])                extends ApiConsumerSettings {
    def json: JsValue = Json.obj(
      "name"            -> name,
      "config"         -> Json.obj(
        "jwtVerifierRefs" -> jwtVerifierRefs
      )
    )
  }
}

trait ApiConsumerStatus {
  def name: String
}
object ApiConsumerStatus {
  case class Staging()    extends ApiConsumerStatus  {
    override def name: String ="staging"
  }
  case class Published()  extends ApiConsumerStatus {
    override def name: String ="published"
  }
  case class Deprecated() extends ApiConsumerStatus {
    override def name: String ="deprecated"
  }
  case class Closed()     extends ApiConsumerStatus {
    override def name: String ="closed"
  }
}

sealed trait ApiBackend

object ApiBackend {
  case class ApiBackendRef(ref: String) extends ApiBackend
  case class ApiBackendInline(id: String, name: String, backend: NgBackend) extends ApiBackend

  val _fmt: Format[ApiBackend] = new Format[ApiBackend] {
    override def reads(json: JsValue): JsResult[ApiBackend] = Try {
      json.asOptString match {
        case Some(ref)  => ApiBackendRef(ref)
        case None       => ApiBackendInline(
          id = json.select("id").asString,
          name = json.select("name").asString,
          backend = json.select("backend").as(NgBackend.fmt)
        )
      }
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiBackend): JsValue = {
      o match {
        case ApiBackendRef(ref) => JsString(ref)
        case ApiBackendInline(id, name, backend) => Json.obj(
          "id"      -> id,
          "name"    -> name,
          "backend" -> NgBackend.fmt.writes(backend)
        )
      }
    }
  }
}

case class ApiBackendClient(name: String, client: NgClientConfig)

object ApiBackendClient {
  val _fmt: Format[ApiBackendClient] = new Format[ApiBackendClient] {

    override def reads(json: JsValue): JsResult[ApiBackendClient] = Try {
      ApiBackendClient(
        name = json.select("name").asString,
        client = json.select("client").as(NgClientConfig.format)
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiBackendClient): JsValue = Json.obj(
      "name" -> o.name,
      "client" -> o.client.json
    )
  }
}

case class Api(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    version: String,
    // or versions: Seq[ApiVersion] with ApiVersion being the following ?
    // versions: Seq[ApiVersion],
    //// ApiVersion
    debugFlow: Boolean,
    capture: Boolean,
    exportReporting: Boolean,
    state: ApiState,
    blueprint: ApiBlueprint,
    routes: Seq[ApiRoute],
    backends: Seq[ApiBackend],
    flows: Seq[ApiFlows],
    clients: Seq[ApiBackendClient],
    documentation: Option[ApiDocumentation],
    consumers: Seq[ApiConsumer],
    deployments: Seq[ApiDeployment]
    // TODO: monitoring and heath ????
    //// ApiVersion
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = Api.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def toRoutes: Seq[NgRoute]                    = ???
}

object Api {
  def fromJsons(value: JsValue): Api =
    try {
      format.reads(value).get
    } catch {
      case e: Throwable => throw e
    }
  val format: Format[Api] = new Format[Api] {
    override def writes(o: Api): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"                -> o.id,
      "name"              -> o.name,
      "description"       -> o.description,
      "metadata"          -> o.metadata,
      "tags"              -> JsArray(o.tags.map(JsString.apply)),
      "version"           -> o.version,
      "debug_flow"        -> o.debugFlow,
      "capture"           -> o.capture,
      "export_reporting"  -> o.exportReporting,
      "state"             -> o.state.name,
      "blueprint"         -> o.blueprint.name,
      "routes"            -> o.routes.map(ApiRoute._fmt.writes),
      "backends"          -> o.backends.map(ApiBackend._fmt.writes),
      "flows"             -> o.flows.map(ApiFlows._fmt.writes),
      "clients"           -> o.clients.map(ApiBackendClient._fmt.writes),
      "documentation"     -> o.documentation.map(ApiDocumentation._fmt.writes),
      "consumers"         -> o.consumers.map(ApiConsumer._fmt.writes),
      "deployments"       -> o.deployments.map(ApiDeployment._fmt.writes)
    )
    override def reads(json: JsValue): JsResult[Api] = Try {
      Api(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").asString,
        name = (json \ "name").asString,
        description = (json \ "description").asString,
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        version = (json \ "version").asOptString.getOrElse("1.0.0"),
        debugFlow = (json \ "debug_flow").asOpt[Boolean].getOrElse(false),
        capture = (json \ "capture").asOpt[Boolean].getOrElse(false),
        exportReporting = (json \ "export_reporting").asOpt[Boolean].getOrElse(false),
        state = (json \ "state").asOptString.map {
          case "started" => ApiStarted
          case "published" => ApiPublished
          case "deprecated" => ApiDeprecated
          case "removed" => ApiRemoved
        }.getOrElse(ApiStarted),
        blueprint = (json \ " blueprint").asOptString.map {
          case "REST"      => ApiBlueprint.REST
          case "GraphQL"   => ApiBlueprint.GraphQL
          case "gRPC"      => ApiBlueprint.gRPC
          case "Http"      => ApiBlueprint.Http
          case "Websocket" => ApiBlueprint.Websocket
        }.getOrElse(ApiBlueprint.REST),
        routes = (json \ "routes")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiRoute._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty),
        backends = (json \ "backends")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiBackend._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty),
        flows = (json \ "flows")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiFlows._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty),
        clients = (json \ "clients")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiBackendClient._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty),
        documentation = (json \ "documentation")
          .asOpt[ApiDocumentation](ApiDocumentation._fmt.reads),
        consumers = (json \ "consumers")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiConsumer._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty),
        deployments = (json \ "deployments")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiDeployment._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

trait ApiDataStore extends BasicStore[Api] {
  def template(env: Env): Api = {
    val defaultApi = Api(
      location = EntityLocation.default,
      id = IdGenerator.namedId("api", env),
      name = "New API",
      description = "New API description",
      metadata = Map.empty,
      tags = Seq.empty,
      version = "1.0.0",
      debugFlow = false,
      capture = false,
      exportReporting = false,
      state = ApiStarted,
      blueprint = ApiBlueprint.REST,
      routes = Seq.empty,
      backends = Seq.empty,
      flows = Seq.empty,
      clients = Seq.empty,
      documentation = None,
      consumers = Seq.empty,
      deployments = Seq.empty,
    )
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .api
      .map { template =>
        Api.format.reads(defaultApi.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        defaultApi
      }
  }
}

class KvApiDataStore(redisCli: RedisLike, _env: Env) extends ApiDataStore with RedisLikeStore[Api] {
  override def fmt: Format[Api]                      = Api.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:apis:$id"
  override def extractId(value: Api): String         = value.id
}


trait ApiConsumerSubscriptionDataStore extends BasicStore[ApiConsumerSubscription] {
  def template(env: Env): ApiConsumerSubscription = {
    val defaultSubscription = ApiConsumerSubscription(
      location = EntityLocation.default,
      id = IdGenerator.namedId("api-consumer-subscription", env),
      name = "New API Consumer Subscription",
      description = "New API Consumer Subscription description",
      metadata = Map.empty,
      tags = Seq.empty,
      enabled = true,
      dates = ApiConsumerSubscriptionDates(
        created_at = DateTime.now(),
        processed_at = DateTime.now(),
        started_at = DateTime.now(),
        ending_at = DateTime.now(),
        closed_at = DateTime.now()
      ),
      ownerRef = "",
      consumerRef = "",
      subscriptionKind = ApiConsumerKind.Apikey,
      tokenRefs = Seq.empty,
      apiRef = ""
    )

    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .apiConsumerSubscription
      .map { template =>
        ApiConsumerSubscription.format.reads(defaultSubscription.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        defaultSubscription
      }
  }
}

class KvApiConsumerSubscriptionDataStore(redisCli: RedisLike, _env: Env) extends ApiConsumerSubscriptionDataStore with RedisLikeStore[ApiConsumerSubscription] {
  override def fmt: Format[ApiConsumerSubscription]               = ApiConsumerSubscription.format
  override def redisLike(implicit env: Env): RedisLike            = redisCli
  override def key(id: String): String                            = s"${_env.storageRoot}:apiconsumersubscriptions:$id"
  override def extractId(value: ApiConsumerSubscription): String  = value.id
}
