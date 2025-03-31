package next.models

import akka.util.ByteString
import diffson.PatchOps
import org.joda.time.DateTime
import otoroshi.api.{DeleteAction, WriteAction}
import otoroshi.env.Env
import otoroshi.models.{Draft, EntityLocation, EntityLocationSupport, LoadBalancing, RemainingQuotas, RoundRobin, ServiceDescriptor}
import otoroshi.next.models.{NgPluginInstance, _}
import otoroshi.next.plugins.api.{NgPlugin, NgPluginConfig}
import otoroshi.next.plugins.api.NgPluginHelper.pluginId
import otoroshi.next.plugins.{ApikeyCalls, ForceHttpsTraffic, JwtVerificationOnly, NgApikeyCallsConfig, NgClientCredentialTokenEndpoint, NgClientCredentialTokenEndpointConfig, NgHasClientCertMatchingValidator, NgHasClientCertMatchingValidatorConfig, NgJwtVerificationOnlyConfig, OIDCAccessTokenValidator, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue, BetterSyntax}
import play.api.libs.json.{Format, JsArray, JsBoolean, JsError, JsNull, JsNumber, JsObject, JsResult, JsString, JsSuccess, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

sealed trait ApiState {
  def name: String
}

case object ApiStaging extends ApiState {
  def name: String = "staging"
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

case class ApiRoute(id: String,
                    enabled: Boolean = true,
                    name: Option[String],
                    frontend: NgFrontend,
                    flowRef: String,
                    backend: String)

object ApiRoute {
  val _fmt: Format[ApiRoute] = new Format[ApiRoute] {

    override def reads(json: JsValue): JsResult[ApiRoute] = Try {
      ApiRoute(
        id = json.select("id").asString,
        enabled = json.select("enabled").asOptBoolean.getOrElse(true),
        name = json.select("name").asOptString,
        frontend = NgFrontend.readFrom(json \ "frontend"),
        flowRef = (json \ "flow_ref").asString,
        backend = (json \ "backend").as[String]
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiRoute): JsValue = Json.obj(
      "id"        -> o.id,
      "enabled"   -> o.enabled,
      "name"      -> o.name,
      "frontend"  -> o.frontend.json,
      "backend"   -> o.backend,
      "flow_ref"  -> o.flowRef
    )
  }
}

case class ApiFlows(id: String,
                    name: String,
                    consumers: Seq[String] = Seq.empty,
                    plugins: NgPlugins)

object ApiFlows {
  def empty(implicit env: Env): ApiFlows = ApiFlows(
    IdGenerator.namedId("api_flows", env),
    "default_flow",
    plugins = NgPlugins.apply(
      slots = Seq(NgPluginInstance(
        plugin = pluginId[OverrideHost],
        include = Seq.empty,
        exclude = Seq.empty,
        config = NgPluginInstanceConfig()
      ))
    )
  )

  val _fmt = new Format[ApiFlows] {

    override def reads(json: JsValue): JsResult[ApiFlows] = Try {
      ApiFlows(
        id = json.select("id").asString,
        name = json.select("name").asString,
        plugins = NgPlugins.readFrom(json.select("plugins")),
        consumers = (json \ "consumers")
          .asOpt[Seq[String]]
          .getOrElse(Seq.empty)
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
      "plugins" -> o.plugins.json,
      "consumers" -> o.consumers,
    )
  }
}

case class ApiDeployment(
    id: String,
    apiRef: String,
    owner: String,
    at: DateTime,
    apiDefinition: JsValue,
    version: String
)

object ApiDeployment {
  val _fmt: Format[ApiDeployment] = new Format[ApiDeployment] {
    override def reads(json: JsValue): JsResult[ApiDeployment] = Try {
      ApiDeployment(
        id = json.select("id").asOptString.getOrElse(IdGenerator.namedId("api_deployment", IdGenerator.uuid)),
        apiRef = json.select("apiRef").asString,
        owner = json.select("owner").asString,
        at = json.select("at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        apiDefinition = json.select("apiDefinition").as[JsValue],
        version = json.select("version").asOptString.getOrElse("0.0.1"),
      )
    } match {
      case Failure(ex) =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: ApiDeployment): JsValue             = Json.obj(
      "id"          -> o.id,
      "apiRef"      -> o.apiRef,
      "owner"       -> o.owner,
      "at"          -> o.at.getMillis,
      "apiDefinition" -> o.apiDefinition,
      "version"     -> o.version
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
    description: Option[String] = None,
    autoValidation: Boolean,
    consumerKind: ApiConsumerKind,
    settings: ApiConsumerSettings,
    status: ApiConsumerStatus,
    subscriptions: Seq[ApiConsumerSubscriptionRef] = Seq.empty
)

object ApiConsumer {
  val _fmt: Format[ApiConsumer] = new Format[ApiConsumer] {
    override def reads(json: JsValue): JsResult[ApiConsumer] = Try {
      ApiConsumer(
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOptString,
        autoValidation = json.select("auto_validation").asOpt[Boolean].getOrElse(false),
        consumerKind = json.select("consumer_kind").asString.toLowerCase match {
          case "apikey"  => ApiConsumerKind.Apikey
          case "mtls"    => ApiConsumerKind.Mtls
          case "keyless" => ApiConsumerKind.Keyless
          case "oauth2"  => ApiConsumerKind.OAuth2
          case "jwt"     => ApiConsumerKind.JWT
        },
        settings = json.select("consumer_kind").asString.toLowerCase  match {
          case "apikey"   => ApiConsumerSettings.Apikey(
            wipeBackendRequest = (json \ "settings" \ "wipe_backend_request").asOptBoolean.getOrElse(true),
            validate = (json \ "settings" \ "validate").asOptBoolean.getOrElse(true),
            mandatory = (json \ "settings" \ "mandatory").asOptBoolean.getOrElse(true),
            passWithUser = (json \ "settings" \ "pass_with_user").asOptBoolean.getOrElse(false),
            updateQuotas = (json \ "settings" \ "update_quotas").asOptBoolean.getOrElse(true),
          )
          case "mtls"     => ApiConsumerSettings.Mtls(
            consumerConfig = (json \ "settings").asOpt(NgHasClientCertMatchingValidatorConfig.format)
          )
          case "keyless"  => ApiConsumerSettings.Keyless()
          case "oauth2"   => ApiConsumerSettings.OAuth2(
            consumerConfig = (json \ "settings").asOpt(NgClientCredentialTokenEndpointConfig.format)
          )
          case "jwt"      => ApiConsumerSettings.JWT(
            consumerConfig = (json \ "settings").asOpt(NgJwtVerificationOnlyConfig.format)
          )
        },
        status = json.select("status").asString.toLowerCase match {
          case "staging"      => ApiConsumerStatus.Staging
          case "published"    => ApiConsumerStatus.Published
          case "deprecated"   => ApiConsumerStatus.Deprecated
          case "closed"       => ApiConsumerStatus.Closed
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
      "auto_validation" -> o.autoValidation,
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
    paused_at: DateTime,
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
        paused_at     = json.select("paused_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
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
      "paused_at"     -> o.paused_at.getMillis,
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

//  def deleteValidatorForApiConsumerSubscription(entity: ApiConsumerSubscription,
//                                                body: JsValue,
//                                                singularName: String,
//                                                id: String,
//                                                action: DeleteAction,
//                                                env: Env): Future[Either[JsValue, Unit]] = {
//    println(s"delete validation foo: ${singularName} - ${id} - ${action} - ${body.prettify}")
//    id match {
//      case "foo_2" => Json.obj("error" -> "bad id", "http_status_code" -> 400).leftf
//      case _ => ().rightf
//    }
//  }
}

object ApiConsumerSubscription {

  def deleteValidator(entity: ApiConsumerSubscription,
                      body: JsValue,
                      singularName: String,
                      id: String,
                      action: DeleteAction,
                      env: Env): Future[Either[JsValue, Unit]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    implicit val e: Env = env

    env.datastores.apiDataStore.findById(entity.apiRef) flatMap {
      case Some(api) => env.datastores.apiDataStore.set(api.copy(consumers = api.consumers.map(consumer => {
        if (consumer.id == entity.consumerRef) {
          consumer.copy(subscriptions = consumer.subscriptions.filter(_.ref != id))
        }
        else
          consumer
      }))).map(_ => ().right)
      case None => Json.obj(
        "error" -> "api not found",
        "http_status_code" -> 404
      ).as[JsValue].left.vfuture
    }
  }

  def writeValidator(entity: ApiConsumerSubscription,
                     body: JsValue,
                     oldEntity: Option[(ApiConsumerSubscription, JsValue)],
                     singularName: String,
                     id: Option[String],
                     action: WriteAction,
                     env: Env): Future[Either[JsValue, ApiConsumerSubscription]] = {

    implicit val ec = env.otoroshiExecutionContext
    implicit val e = env

    def onError(error: String): Either[JsValue, ApiConsumerSubscription] = Json.obj(
      "error" -> s"api consumer has rejected your demand : $error",
      "http_status_code" -> 400
    ).left


    val isDraft = body.select("draft").asOptBoolean.getOrElse(false)

    def addSubscriptionToConsumer(api: Api): Future[Option[ApiConsumer]] = {
      val newApi = api.copy(consumers = api.consumers.map(consumer => {
        if (consumer.id == entity.consumerRef) {
          if (action == WriteAction.Update) {
            consumer.copy(subscriptions = consumer.subscriptions.map(subscription => {
              if (subscription.ref == entity.id) {
                subscription.copy(entity.id)
              } else {
                subscription
              }
            }))
          } else {
            consumer.copy(subscriptions = consumer.subscriptions :+ ApiConsumerSubscriptionRef(entity.id))
          }
        } else {
          consumer
        }
      }))

      (if (isDraft) {
        env.datastores.draftsDataStore.findById(entity.apiRef)
          .map(_.get)
          .flatMap(draft => env.datastores.draftsDataStore.set(draft.copy(content = newApi.json)))
      } else {
        env.datastores.apiDataStore.set(newApi)
      }) flatMap (result =>
        if (result) {
          api.consumers.find(consumer => consumer.id == entity.consumerRef).future
        } else {
          None.vfuture
        }
        )
    }

    (if (!isDraft) {
      env.datastores.apiDataStore.findById(entity.apiRef)
    } else {
      env.datastores.draftsDataStore.findById(entity.apiRef).map(_.map(draft => Api.format.reads(draft.content).get))
    }) flatMap {
      case Some(api) => api.consumers.find(_.id == entity.consumerRef) match {
        case None => onError("consumer not found").vfuture
        case Some(consumer) if consumer.status == ApiConsumerStatus.Published =>
          addSubscriptionToConsumer(api).map {
            case Some(consumer) => entity.right
            case None => onError("failed to add subscription to api")
          }
        case _ => onError("wrong status").vfuture
      }
      case _ => onError("api not found").vfuture
    }
  }

  val format: Format[ApiConsumerSubscription] = new Format[ApiConsumerSubscription] {
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
  def config: Option[NgPluginConfig] = None
  def json: JsValue = config
      .map(_.json.asObject)
      .getOrElse(Json.obj())
}
object ApiConsumerSettings {
  case class Apikey(
    wipeBackendRequest: Boolean = true,
    validate: Boolean = true,
    mandatory: Boolean = true,
    passWithUser: Boolean = false,
    updateQuotas: Boolean = true)         extends ApiConsumerSettings {
    override def json: JsValue = Json.obj(
      "validate"             -> validate,
      "mandatory"            -> mandatory,
      "pass_with_user"       -> passWithUser,
      "wipe_backend_request" -> wipeBackendRequest,
      "update_quotas"        -> updateQuotas
    )
  }
  case class Mtls(consumerConfig: Option[NgHasClientCertMatchingValidatorConfig])                          extends ApiConsumerSettings {
    override def config = consumerConfig
  }
  case class Keyless()                       extends ApiConsumerSettings {
  }
  case class OAuth2(consumerConfig: Option[NgClientCredentialTokenEndpointConfig])                        extends ApiConsumerSettings {
    override def config: Option[NgClientCredentialTokenEndpointConfig] = consumerConfig
  }
  case class JWT(consumerConfig: Option[NgJwtVerificationOnlyConfig])                           extends ApiConsumerSettings {
    override def config: Option[NgJwtVerificationOnlyConfig] = consumerConfig
  }
}

trait ApiConsumerStatus {
  def name: String
  def orderPosition: Int
}
object ApiConsumerStatus {
  case object Staging    extends ApiConsumerStatus  {
    override def name: String ="staging"
    override def orderPosition: Int = 1
  }
  case object Published  extends ApiConsumerStatus {
    override def name: String ="published"
    override def orderPosition: Int = 2
  }
  case object Deprecated extends ApiConsumerStatus {
    override def name: String ="deprecated"
    override def orderPosition: Int = 3
  }
  case object Closed      extends ApiConsumerStatus {
    override def name: String ="closed"
    override def orderPosition: Int = 4
  }
}

case class ApiBackend(id: String, name: String, backend: NgBackend)

object ApiBackend {

  def empty(implicit env: Env): ApiBackend = ApiBackend(
    IdGenerator.namedId("api_backend", env),
    name = "default_backend",
    backend = NgBackend.empty.copy(
        targets = Seq(
          NgTarget(
            id = "target_1",
            hostname = "request.otoroshi.io",
            port = 443,
            tls = true
          )
        )
    )
  )

  val _fmt: Format[ApiBackend] = new Format[ApiBackend] {
    override def reads(json: JsValue): JsResult[ApiBackend] = Try {
      ApiBackend(
        id = json.select("id").asString,
        name = json.select("name").asString,
        backend = json.select("backend").as(NgBackend.fmt)
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiBackend): JsValue = {
      Json.obj(
        "id"      -> o.id,
        "name"    -> o.name,
        "backend" -> NgBackend.fmt.writes(o.backend)
      )
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
      case Failure(ex) =>
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

case class ApiTesting(enabled: Boolean = false,
                      headerKey: String = "X-OTOROSHI-TESTING",
                      headerValue: String = IdGenerator.uuid)

object ApiTesting {
  val _fmt: Format[ApiTesting] = new Format[ApiTesting] {
    override def writes(o: ApiTesting): JsValue             = Json.obj(
      "enabled"           -> o.enabled,
      "headerKey"         -> o.headerKey,
      "headerValue"       -> o.headerValue,
    )
    override def reads(json: JsValue): JsResult[ApiTesting] = Try {
      ApiTesting(
        enabled = json.select("enabled").asOptBoolean.getOrElse(false),
        headerKey = json.select("headerKey").asOptString.getOrElse("X-OTOROSHI-TESTING"),
        headerValue = json.select("headerValue").asOptString.getOrElse(IdGenerator.uuid)
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
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
    versions: Seq[String] = Seq("0.0.1"),
    debugFlow: Boolean,
    capture: Boolean,
    exportReporting: Boolean,
    state: ApiState,
    enabled: Boolean = true,
    blueprint: ApiBlueprint,
    routes: Seq[ApiRoute],
    backends: Seq[ApiBackend],
    flows: Seq[ApiFlows],
    clients: Seq[ApiBackendClient],
    documentation: Option[ApiDocumentation],
    consumers: Seq[ApiConsumer],
    deployments: Seq[ApiDeployment],
    testing: ApiTesting
    // TODO: monitoring and heath ????
) extends EntityLocationSupport {
  override def internalId: String = id

  override def json: JsValue = Api.format.writes(this)

  override def theName: String = name

  override def theDescription: String = description

  override def theTags: Seq[String] = tags

  override def theMetadata: Map[String, String] = metadata

  def toRoutes(implicit env: Env): Future[Seq[NgRoute]] = {
    implicit val ec = env.otoroshiExecutionContext

    if (state == ApiRemoved || !enabled) {
      Seq.empty.vfuture
    } else {
      env.datastores.draftsDataStore.findById(id)
        .flatMap(optDraft => {
          val draftApis: Option[Api] = optDraft.map(draft => Api.format.reads(draft.content))
            .collect { case JsSuccess(api, _) if api.testing.enabled =>
              api.copy(routes = api.routes.map(route =>route.copy(
              id = s"testing_route_${route.id}",
              frontend =
                route.frontend.copy(headers = route.frontend.headers + (api.testing.headerKey -> api.testing.headerValue)))))
            }

          val draftRoutes = draftApis.map(api => api.routes.map(route => routeToNgRoute(route, api.some))).getOrElse(Seq.empty)

          Future.sequence(routes.map(route => routeToNgRoute(route, this.some)) ++ draftRoutes)
            .map(routes => routes.collect {
              case Some(value) => value
            }
              .filter(_.enabled))
        })
    }
  }

  def legacy: ServiceDescriptor = {
    NgRoute(
      location = location,
      id = id,
      name = name,
      description = description,
      tags = tags,
      metadata = metadata,
      enabled = true,
      capture = capture,
      debugFlow = debugFlow,
      exportReporting = exportReporting,
      groups = Seq.empty,
      frontend = NgFrontend.empty,
      backend = NgBackend.empty,
      backendRef = None,
      plugins = NgPlugins.empty
    ).legacy
  }

  def routeToNgRoute(apiRoute: ApiRoute, optApi: Option[Api] = None)(implicit env: Env): Future[Option[NgRoute]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    for {
      globalBackendEntity <- env.datastores.backendsDataStore.findById(apiRoute.backend)
      apiBackend          <- optApi.map(api => api).getOrElse(this).backends.find(_.id == apiRoute.backend).map(_.backend).vfuture
    } yield {
      globalBackendEntity.map(_.backend).orElse(apiBackend).map(backend =>
          NgRoute(
            location = location,
            id = apiRoute.id,
            name = apiRoute.name + " - " + apiRoute.frontend.methods
              .mkString(", ") + " - " + apiRoute.frontend.domains.map(_.path).mkString(", "),
            description = description,
            tags = tags,
            metadata = metadata,
            enabled = apiRoute.enabled,
            capture = capture,
            debugFlow = debugFlow,
            exportReporting = exportReporting,
            groups = Seq.empty,
            frontend = apiRoute.frontend,
            backend = backend,
            backendRef = None,
            plugins = optApi.map(api => api).getOrElse(this)
              .flows
              .find(_.id == apiRoute.flowRef)
              .map(_.plugins)
              .getOrElse(NgPlugins.empty)
          ))
    }
  }

  def apiRouteToNgRoute(routeId: String)(implicit env: Env): Future[Option[NgRoute]] = {
    routes.find(_.id == routeId) match {
      case Some(apiRoute) => routeToNgRoute(apiRoute)
      case None => None.vfuture
    }
  }
}

object Api {
//  def addPluginToFlows[T <: NgPlugin](api: Api, consumer: ApiConsumer)(implicit ct: ClassTag[T]): Api = {
//    api.copy(flows = api.flows.map(flow => addPluginToFlow[T](consumer, flow)))
//  }

  private def addPluginToFlow[T <: NgPlugin](consumer: ApiConsumer, flow: ApiFlows)(implicit ct: ClassTag[T]): ApiFlows = {
    if (flow.consumers.contains(consumer.id)) {
      if (flow.plugins.slots.exists(_.plugin == pluginId[T])) {
        flow.copy(plugins = flow.plugins.copy(slots = flow.plugins.slots.map(slot =>
          if (slot.plugin == pluginId) {
            slot.copy(enabled = true, config = slot.config.copy(slot.config.raw.deepMerge(consumer.settings.json.asObject)))
          } else {
            slot
          })))
      } else {
        flow.copy(plugins = flow.plugins.add(NgPluginInstance(
          plugin = pluginId[T],
          include = Seq.empty,
          exclude = Seq.empty,
          config = NgPluginInstanceConfig(consumer.settings.json.asObject)
        )))
      }
    } else {
      flow
    }
  }

  def applyConsumersOnFlow(flow: ApiFlows, api: Api): ApiFlows = {
    val outFlow = api.consumers.foldLeft(flow) { case (flow, consumer) => flow.copy(plugins = consumer.consumerKind match {
        case ApiConsumerKind.Apikey => flow.plugins.togglePluginState(pluginId[ApikeyCalls], enabled = false)
        case ApiConsumerKind.JWT => flow.plugins.togglePluginState(pluginId[JwtVerificationOnly], enabled = false)
        case ApiConsumerKind.OAuth2 => flow.plugins.togglePluginState(pluginId[NgClientCredentialTokenEndpoint], enabled = false)
        case ApiConsumerKind.Mtls => flow.plugins.togglePluginState(pluginId[NgHasClientCertMatchingValidator], enabled = false)
        case _ => flow.plugins
      })
    }

    flow.consumers.foldLeft(outFlow) { case (flow, item) =>
      api.consumers.find(_.id == item) match {
        case Some(consumer) =>
          consumer.consumerKind match {
            case ApiConsumerKind.Apikey   => addPluginToFlow[ApikeyCalls](consumer, flow)
            case ApiConsumerKind.JWT      => addPluginToFlow[JwtVerificationOnly](consumer, flow)
            case ApiConsumerKind.OAuth2   => addPluginToFlow[NgClientCredentialTokenEndpoint](consumer, flow)
            case ApiConsumerKind.Mtls     => addPluginToFlow[NgHasClientCertMatchingValidator](consumer, flow)
            case ApiConsumerKind.Keyless  => flow
          }
        case None => flow
      }
    }
    //    if (!updateConsumerStatus(oldConsumer, consumer)) {
    //      None
    //    } else {
    //      applyConsumerRulesOnApi(consumer, api)
    //    }
  }

//  def removePluginToFlows[T <: NgPlugin](api: Api, consumer: ApiConsumer)(implicit ct: ClassTag[T]): Api = {
//    api.copy(flows = api.flows
//      .map(flow => {
//        if (flow.consumers.contains(consumer.id)) {
//          flow.copy(plugins = NgPlugins(flow.plugins.slots.filter(plugin => {
//            val name = s"cp:${ct.runtimeClass.getName}"
//            plugin.plugin != name
//          })))
//        } else {
//          flow
//        }
//      }))
//  }

  def writeValidator(newApi: Api,
                      _body: JsValue,
                      oldEntity: Option[(Api, JsValue)],
                      _singularName: String,
                      _id: Option[String],
                      action: WriteAction,
                      env: Env): Future[Either[JsValue, Api]] = {
     implicit val ec: ExecutionContext = env.otoroshiExecutionContext
     implicit val e: Env = env

     if(action == WriteAction.Update) {
       oldEntity.map(_._1.vfuture)
         .getOrElse(env.datastores.apiDataStore.findById(newApi.id).map(_.get))
         .map(api => {
           newApi.copy(flows = newApi.flows.map(flow => applyConsumersOnFlow(flow, api))).right
         })
     } else {
      newApi.rightf
     }
   }

  def updateConsumerStatus(oldConsumer: ApiConsumer, consumer: ApiConsumer): Boolean = {
     // staging     -> published  = ok
     // published   -> deprecated = ok
     // deprecated  -> closed     = ok
     // deprecated  -> published  = ok
     if (consumer.status == ApiConsumerStatus.Published && oldConsumer.status == ApiConsumerStatus.Deprecated) {
      true
     } else if (oldConsumer.status.orderPosition > consumer.status.orderPosition) {
       false
     } else {
       true
     }
  }

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
      "enabled"           -> o.enabled,
      "blueprint"         -> o.blueprint.name,
      "routes"            -> o.routes.map(ApiRoute._fmt.writes),
      "backends"          -> o.backends.map(ApiBackend._fmt.writes),
      "flows"             -> o.flows.map(ApiFlows._fmt.writes),
      // TODO - list of HTTP clients
      "clients"           -> o.clients.map(ApiBackendClient._fmt.writes),
      "documentation"     -> o.documentation.map(ApiDocumentation._fmt.writes),
      "consumers"         -> o.consumers.map(ApiConsumer._fmt.writes),
      "deployments"       -> o.deployments.map(ApiDeployment._fmt.writes),
      "versions"          -> o.versions,
      "testing"           -> ApiTesting._fmt.writes(o.testing)
    )
    override def reads(json: JsValue): JsResult[Api] = Try {
      Api(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").asString,
        name = (json \ "name").asString,
        description = (json \ "description").asString,
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        version = (json \ "version").asOptString.getOrElse("0.0.1"),
        debugFlow = (json \ "debug_flow").asOpt[Boolean].getOrElse(false),
        capture = (json \ "capture").asOpt[Boolean].getOrElse(false),
        exportReporting = (json \ "export_reporting").asOpt[Boolean].getOrElse(false),
        state = (json \ "state").asOptString.map {
          case "staging" => ApiStaging
          case "published" => ApiPublished
          case "deprecated" => ApiDeprecated
          case "removed" => ApiRemoved
          case _ => ApiStaging
        }.getOrElse(ApiStaging),
        enabled = (json \ "enabled").asOptBoolean.getOrElse(true),
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
          .getOrElse(Seq.empty),
        versions    = json.select("versions")
          .asOpt[Seq[String]]
          .getOrElse(Seq.empty),
        testing     = json.select("testing")
          .asOpt(ApiTesting._fmt.reads)
          .getOrElse(ApiTesting())
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
      version = "0.0.1",
      debugFlow = false,
      capture = false,
      exportReporting = false,
      state = ApiStaging,
      blueprint = ApiBlueprint.REST,
      routes = Seq.empty,
      backends = Seq(ApiBackend.empty(env)),
      flows = Seq(ApiFlows.empty(env)),
      clients = Seq.empty,
      documentation = None,
      consumers = Seq.empty,
      deployments = Seq.empty,
      testing = ApiTesting()
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
        created_at    = DateTime.now(),
        processed_at  = DateTime.now(),
        started_at    = DateTime.now(),
        paused_at     = DateTime.now(),
        ending_at     = DateTime.now(),
        closed_at     = DateTime.now()
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
