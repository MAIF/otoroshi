package otoroshi.next.models

import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.util.ByteString
import org.joda.time.DateTime
import otoroshi.api.WriteAction.{Create, Update}
import otoroshi.api.{DeleteAction, WriteAction}
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, Alerts, ApiDeploymentEvent, Audit}
import otoroshi.models.*
import otoroshi.next.models.*
import otoroshi.next.plugins.{ApikeyQuotas, *}
import otoroshi.next.plugins.api.NgPluginHelper.pluginId
import otoroshi.next.services.ApiConsistencyService
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.TypedMap
import otoroshi.utils.UrlSanitizer.sanitize
import otoroshi.utils.controllers.{GenericAlert, SendAuditAndAlert}
import otoroshi.utils.syntax.implicits.*
import otoroshi.utils.yaml.Yaml
import play.api.Logger
import play.api.libs.json.*

import java.net.URI
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgPluginsWithOverride(plugins: NgPlugins, overrides: Boolean) {
  def json: JsValue = Json.obj(
    "plugins" -> plugins.json,
    "overrides" -> overrides,
  )
}

object NgPluginsWithOverride {
  val format = new Format[NgPluginsWithOverride] {
    override def writes(o: NgPluginsWithOverride): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgPluginsWithOverride] = Try {
      NgPluginsWithOverride(
        plugins = NgPlugins.readFrom(json.select("plugins")),
        overrides = json.select("overrides").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
  }
}

// what a plan declares: the same thing an apikey does, plus the ability to point at a flow of its
// api rather than carry the chain inline, so that several plans share one chain. Both can be set,
// the flow comes first and the inline slots extend it. ApiPlan.computedPlugins resolves the
// reference and hands back a plain NgPluginsWithOverride.
case class ApiPlanPlugins(plugins: NgPlugins, overrides: Boolean, flowRef: Option[String] = None) {
  def json: JsValue = Json.obj(
    "plugins" -> plugins.json,
    "overrides" -> overrides,
    "flow_ref" -> flowRef.map(JsString.apply).getOrElse(JsNull).as[JsValue],
  )
}

object ApiPlanPlugins {
  val format = new Format[ApiPlanPlugins] {
    override def writes(o: ApiPlanPlugins): JsValue = o.json
    override def reads(json: JsValue): JsResult[ApiPlanPlugins] = Try {
      ApiPlanPlugins(
        plugins = NgPlugins.readFrom(json.select("plugins")),
        overrides = json.select("overrides").asOpt[Boolean].getOrElse(false),
        flowRef = json.select("flow_ref").asOpt[String].filter(_.trim.nonEmpty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
  }
}

// credential: the client id of the api key of another subscription this one shares (an alias key): the consumer calls
// the api of this subscription with that client id and its secret, and is identified as this api key, with the quotas,
// the restrictions and the plugins of this plan. None for every other api key
case class ApiRef(api: String, plan: String, subscription: String, credential: Option[String] = None) {
  def json: JsValue = {
    val base = Json.obj(
      "api"  -> api,
      "plan" -> plan,
      "sub"  -> subscription
    )
    credential match {
      case Some(clientId) => base ++ Json.obj("credential" -> clientId)
      case None           => base
    }
  }
}

object ApiRef {
  val format = new Format[ApiRef] {
    override def writes(o: ApiRef): JsValue = o.json
    override def reads(json: JsValue): JsResult[ApiRef] = Try {
      ApiRef(
        api = json.selectAsString("api"),
        plan = json.selectAsString("plan"),
        subscription = json.selectAsString("sub"),
        credential = json.select("credential").asOpt[String].filter(_.trim.nonEmpty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }
  }
}

sealed trait ApiState {
  def name: String
}

case object ApiStaging    extends ApiState {
  def name: String = "staging"
}
case object ApiPublished  extends ApiState {
  def name: String = "published"
}
case object ApiDeprecated extends ApiState {
  def name: String = "deprecated"
}
case object ApiRemoved    extends ApiState {
  def name: String = "removed"
}

sealed trait ApiSubscriptionState {
  def name: String
}

case object ApiSubscriptionPending              extends ApiSubscriptionState {
  def name: String = "pending"
}
case object ApiSubscriptionEnabled              extends ApiSubscriptionState {
  def name: String = "enabled"
}
case object ApiSubscriptionDisabled             extends ApiSubscriptionState {
  def name: String = "disabled"
}
case object ApiSubscriptionDeprecated           extends ApiSubscriptionState {
  def name: String = "deprecated"
}
case class ApiSubscriptionCustom(value: String) extends ApiSubscriptionState {
  def name: String = value
}

case class ApiRoute(
    id: String,
    enabled: Boolean = true,
    name: Option[String] = None,
    frontend: NgFrontend,
    flowRef: String,
    backend: String
)

object ApiRoute {
  val _fmt: Format[ApiRoute] = new Format[ApiRoute] {

    override def reads(json: JsValue): JsResult[ApiRoute] = Try {
      ApiRoute(
        id = json.select("id").asString,
        enabled = json.select("enabled").asOptBoolean.getOrElse(true),
        name = json.select("name").asOptString,
        frontend = NgFrontend.readFrom(json \ "frontend"),
        flowRef = json.selectAsOptString("plugin_chain").orElse(json.selectAsOptString("flow_ref")).getOrElse(""),
        backend = (json \ "backend").as[String]
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiRoute): JsValue = Json.obj(
      "id"       -> o.id,
      "enabled"  -> o.enabled,
      "name"     -> o.name,
      "frontend" -> o.frontend.json,
      "backend"  -> o.backend,
      "flow_ref" -> o.flowRef
    )
  }
}

case class ApiFlows(id: String, name: String, plugins: NgPlugins)

object ApiFlows {
  def empty(using env: Env): ApiFlows = ApiFlows(
    "default_plugin_chain",
    "default_plugin_chain",
    plugins = NgPlugins.apply(
      slots = Seq(
        NgPluginInstance(
          plugin = pluginId[OverrideHost],
          include = Seq.empty,
          exclude = Seq.empty,
          config = NgPluginInstanceConfig()
        )
      )
    )
  )

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
      "id"      -> o.id,
      "name"    -> o.name,
      "plugins" -> o.plugins.json
    )
  }
}

case class ApiDeployment(
    id: String,
    apiRef: String,
    owner: String,
    at: DateTime,
    apiDefinition: JsValue,
    version: String,
    // the identifier, in the tool that deployed, of the version deployed (a commit sha, a release number, a tag).
    // Otoroshi does not interpret it
    ref: Option[String] = None
)

object ApiDeployment {

  val maxRefLength = 256

  // a trimmed ref, a blank one is no ref
  def readRef(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)

  def refTooLong(ref: Option[String]): Boolean = ref.exists(_.length > maxRefLength)

  val refTooLongError: JsObject = Json.obj(
    "error"             -> "bad_request",
    "error_description" -> s"the ref of a deployment is longer than $maxRefLength characters"
  )

  // how many deployments an api keeps, the last ones: the full history is the stream of the ApiDeploymentEvent
  // sent to the data exporters
  def historySize(env: Env): Int =
    env.configuration.getOptional[Int]("otoroshi.apis.deployments-history-size").getOrElse(5).max(1)

  val _fmt: Format[ApiDeployment] = new Format[ApiDeployment] {
    override def reads(json: JsValue): JsResult[ApiDeployment] = Try {
      ApiDeployment(
        id = json.select("id").asOptString.getOrElse(IdGenerator.namedId("api_deployment", IdGenerator.uuid)),
        apiRef = json.select("apiRef").asString,
        owner = json.select("owner").asString,
        at = json.select("at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        apiDefinition = json.select("apiDefinition").as[JsValue],
        version = json.select("version").asOptString.getOrElse("0.0.1"),
        ref = readRef(json.select("ref").asOpt[String])
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: ApiDeployment): JsValue             = Json.obj(
      "id"            -> o.id,
      "apiRef"        -> o.apiRef,
      "owner"         -> o.owner,
      "at"            -> o.at.getMillis,
      "apiDefinition" -> o.apiDefinition,
      "version"       -> o.version,
      "ref"           -> o.ref
    )
  }
}

sealed trait ApiSpecification {
  def content: JsValue
  def name: String
}
object ApiSpecification       {
  case class OpenApiSpecification(content: JsValue, name: String) extends ApiSpecification

  case class AsyncApiSpecification(content: JsValue, name: String) extends ApiSpecification

  val _fmt: Format[ApiSpecification] = new Format[ApiSpecification] {
    override def reads(json: JsValue): JsResult[ApiSpecification] = Try {
      val content = json.select("content").as[JsValue]

      json.select("name").asString match {
        case name if name == "OpenApiSpecification" => OpenApiSpecification(content, name)
        case _                                      => AsyncApiSpecification(content, name = "AsyncApiSpecification")
      }
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiSpecification): JsValue = Json.obj(
      "name"    -> o.name,
      "content" -> o.content
    )
  }
}

sealed trait ApiDocumentationSidebarItem
case class ApiDocumentationSidebarCategory(raw: JsObject) extends ApiDocumentationSidebarItem {
  lazy val icon: Option[ApiDocumentationResource]  =
    raw.select("icon").asOpt[JsObject].map(o => ApiDocumentationResource(o))
  lazy val label: String                           = raw.select("label").asOptString.getOrElse("No label")
  lazy val links: Seq[ApiDocumentationSidebarLink] =
    raw.select("links").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.map(o => ApiDocumentationSidebarLink(o))
}
case class ApiDocumentationSidebarLink(raw: JsObject)     extends ApiDocumentationSidebarItem {
  lazy val icon: Option[ApiDocumentationResource] =
    raw.select("icon").asOpt[JsObject].map(o => ApiDocumentationResource(o))
  lazy val label: String                          = raw.select("label").asOptString.getOrElse("No label")
  lazy val link: String                           = raw.select("link").asOptString.getOrElse("#")
}

case class ApiDocumentationSidebar(raw: JsObject) {
  lazy val label: String                           = raw.select("label").asOptString.getOrElse("")
  lazy val icon: Option[ApiDocumentationResource]  =
    raw.select("icon").asOpt[JsObject].map(o => ApiDocumentationResource(o))
  lazy val path: Seq[String]                       =
    raw.select("path").asOpt[Seq[String]].orElse(raw.select("path").asOptString.map(s => Seq(s))).getOrElse(Seq.empty).toSeq
  lazy val items: Seq[ApiDocumentationSidebarItem] =
    raw.select("items").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.map { v =>
      v.select("kind").asOptString.getOrElse("link") match {
        case "category" => ApiDocumentationSidebarCategory(v.asObject)
        case _          => ApiDocumentationSidebarLink(v.asObject)
      }
    }
}

case class ApiDocumentationResource(raw: JsObject) {
  lazy val path: Seq[String]                  =
    raw.select("path").asOpt[Seq[String]].orElse(raw.select("path").asOptString.map(s => Seq(s))).getOrElse(Seq.empty).toSeq
  lazy val title: Option[String]              = raw.select("title").asOptString
  lazy val description: Option[String]        = raw.select("description").asOptString
  lazy val contentType: String                = raw.select("content_type").asOpt[String].getOrElse("text/markdown")
  lazy val text_content: Option[String]       = raw.select("text_content").asOpt[String]
  lazy val css_icon_class: Option[String]     = raw.select("css_icon_class").asOpt[String]
  lazy val json_content: Option[JsValue]      = raw.select("json_content").asOpt[JsValue].filterNot(_ == JsNull)
  lazy val base64_content: Option[ByteString] =
    raw.select("base64_content").asOpt[String].map(_.byteString.decodeBase64)
  lazy val site_page: Boolean                 = raw.select("site_page").asOpt[Boolean].getOrElse(false)
  lazy val transform: Option[String]          = raw.select("transform").asOpt[String]
  lazy val transform_wrapper: Option[String]  = raw.select("transform_wrapper").asOpt[String]
  lazy val url: Option[String]                = raw.select("url").asOpt[String]
  lazy val httpHeaders: Map[String, String]   = raw.select("http_headers").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val httpTimeout: FiniteDuration        = raw.select("http_timeout").asOpt[Long].getOrElse(30000L).millis
  lazy val httpFollowRedirects: Boolean       = raw.select("http_follow_redirects").asOpt[Boolean].getOrElse(true)
  def resolveUrl(doc: ApiDocumentation): Option[String] = {
    url match {
      case Some(url) if url.startsWith(".") && doc.source.isDefined => {
        val uri  = new URI(doc.source.get.url.get + "/" + url)
        val norm = uri.normalize()
        norm.toString.some
      }
      case Some(url)                                                => Some(url)
      case None                                                     => None
    }
  }
}

case class ApiDocumentationSearch(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOpt[Boolean].getOrElse(true)
}

object ApiDocumentationSearch {
  val default = ApiDocumentationSearch(Json.obj("enabled" -> true))
}

case class ApiDocumentationRedirection(raw: JsObject) {
  lazy val from: String = raw.select("from").as[String]
  lazy val to: String   = raw.select("to").as[String]
}

case class ApiDocumentationResourceRef(raw: JsObject) {
  lazy val title: String                          = raw.select("title").asString
  lazy val description: Option[String]            = raw.select("description").asOptString
  lazy val link: String                           = raw.select("link").asString
  lazy val icon: Option[ApiDocumentationResource] =
    raw.select("icon").asOpt[JsObject].map(o => ApiDocumentationResource(o))
}

trait ApiAccessModeConfiguration {
  def apiKind: ApiKind
  def plugins: NgPlugins = NgPlugins(Seq.empty) // TODO: implement for each kind with dedicated plugin when needed
  // the restrictions of the api key of each consumer of the plan, enforced by the consumer enforcer
  def restrictions: Restrictions = Restrictions()
}

case class KeylessAccessModeConfiguration(
  expr: Option[String] = None,
  createIfMissing: Boolean = true,
  override val restrictions: Restrictions = Restrictions()
) extends ApiAccessModeConfiguration {
  override def apiKind: ApiKind = ApiKind.Keyless
}

object KeylessAccessModeConfiguration {
  def fmt = new Format[KeylessAccessModeConfiguration] {
    override def reads(json: JsValue): JsResult[KeylessAccessModeConfiguration] = Try {
      KeylessAccessModeConfiguration(
        expr = json.selectAsOptString("expr"),
        createIfMissing = json.select("create_if_missing").asOpt[Boolean].getOrElse(true),
        restrictions = Restrictions.format.reads((json \ "restrictions").asOpt[JsValue].getOrElse(JsNull)).getOrElse(Restrictions())
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: KeylessAccessModeConfiguration): JsValue             = Json.obj(
      "expr"              -> o.expr,
      "create_if_missing" -> o.createIfMissing,
      "restrictions"      -> o.restrictions.json
    )
  }
}

case class JWTAccessModeConfiguration(
    verifier: Option[String] = None,
    clientIdPath: Option[String] = Some("client_id"),
    createIfMissing: Boolean = true,
    // only the consumers with an enabled subscription to the plan are served, create_if_missing is then ignored
    subscriptionRequired: Boolean = false,
    override val restrictions: Restrictions = Restrictions()
) extends ApiAccessModeConfiguration {
  override def apiKind: ApiKind = ApiKind.JWT
}

object JWTAccessModeConfiguration {
  def fmt = new Format[JWTAccessModeConfiguration] {
    override def reads(json: JsValue): JsResult[JWTAccessModeConfiguration] = Try {
      JWTAccessModeConfiguration(
        verifier = json.select("verifier").asOpt[String],
        clientIdPath = json.select("client_id_path").asOpt[String],
        createIfMissing = json.select("create_if_missing").asOpt[Boolean].getOrElse(true),
        subscriptionRequired = json.select("subscription_required").asOpt[Boolean].getOrElse(false),
        restrictions = Restrictions.format.reads((json \ "restrictions").asOpt[JsValue].getOrElse(JsNull)).getOrElse(Restrictions())
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: JWTAccessModeConfiguration): JsValue             = Json.obj(
      "verifier" -> o.verifier,
      "client_id_path" -> o.clientIdPath,
      "create_if_missing" -> o.createIfMissing,
      "subscription_required" -> o.subscriptionRequired,
      "restrictions" -> o.restrictions.json
    )
  }
}

case class MtlsAccessModeConfiguration(
    regexSubjectDNs: Seq[String] = Seq.empty,
    regexIssuerDNs: Seq[String] = Seq.empty,
    clientIdField: Option[String] = None,
    createIfMissing: Boolean = true,
    // only the consumers with an enabled subscription to the plan are served, create_if_missing is then ignored
    subscriptionRequired: Boolean = false,
    override val restrictions: Restrictions = Restrictions(),
    // how the subject and the issuer patterns combine: `all` (every non-empty list) or `any` (one of them)
    dnMatch: String = MtlsAccessModeConfiguration.DnMatchAny,
    // the ids of the certificates of the store that must have signed the client certificate, checked on its chain
    trustedCaRefs: Seq[String] = Seq.empty
) extends ApiAccessModeConfiguration {
  override def apiKind: ApiKind = ApiKind.Mtls
}

object MtlsAccessModeConfiguration {

  val DnMatchAll = "all"
  val DnMatchAny = "any"

  // absent reads as `any`, the behaviour of the plans written before the field. A value that is neither, which
  // the write of an api refuses, reads as the stricter `all`
  def readDnMatch(json: JsValue): String =
    json.select("dn_match").asOpt[JsValue] match {
      case None | Some(JsNull)                                           => DnMatchAny
      case Some(JsString(value)) if value.trim.toLowerCase == DnMatchAny => DnMatchAny
      case Some(_)                                                       => DnMatchAll
    }

  // a value that is not an id never resolves, so a list written wrong refuses every certificate rather than
  // checking none
  def readTrustedCaRefs(json: JsValue): Seq[String] =
    json.select("trusted_ca_refs").asOpt[JsValue] match {
      case None | Some(JsNull)   => Seq.empty
      case Some(JsString(value)) => Seq(value)
      case Some(JsArray(values)) =>
        values.toSeq.map {
          case JsString(value) => value
          case other           => other.toString
        }
      case Some(other)           => Seq(other.toString)
    }

  def fmt = new Format[MtlsAccessModeConfiguration] {
    override def reads(json: JsValue): JsResult[MtlsAccessModeConfiguration] = Try {
      MtlsAccessModeConfiguration(
        regexSubjectDNs = json.select("regex_subject_dns").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        regexIssuerDNs = json.select("regex_issuer_dns").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        clientIdField = json.select("client_id_field").asOpt[String].filter(_.trim.nonEmpty),
        createIfMissing = json.select("create_if_missing").asOpt[Boolean].getOrElse(true),
        subscriptionRequired = json.select("subscription_required").asOpt[Boolean].getOrElse(false),
        restrictions = Restrictions.format.reads((json \ "restrictions").asOpt[JsValue].getOrElse(JsNull)).getOrElse(Restrictions()),
        dnMatch = readDnMatch(json),
        trustedCaRefs = readTrustedCaRefs(json)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: MtlsAccessModeConfiguration): JsValue             = Json.obj(
      "regex_subject_dns" -> o.regexSubjectDNs,
      "regex_issuer_dns"  -> o.regexIssuerDNs,
      "dn_match"          -> o.dnMatch,
      "trusted_ca_refs"   -> o.trustedCaRefs,
      "client_id_field"   -> o.clientIdField,
      "create_if_missing" -> o.createIfMissing,
      "subscription_required" -> o.subscriptionRequired,
      "restrictions"      -> o.restrictions.json
    )
  }
}

case class OAuth2RemoteAccessModeConfiguration(
    verifier: Option[String] = None,
    clientIdPath: Option[String] = Some("client_id"),
    fetchUser: Boolean = false,
    userMetadataKey: String = "user_profile",
    createIfMissing: Boolean = true,
    // the iss a token must carry: the issuer of the authorization server. Not checked when empty
    issuer: Option[String] = None,
    // a token must carry one of them in aud. Not checked when empty
    audiences: Seq[String] = Seq.empty,
    // only the consumers with an enabled subscription to the plan are served, create_if_missing is then ignored
    subscriptionRequired: Boolean = false,
    override val restrictions: Restrictions = Restrictions()
) extends ApiAccessModeConfiguration {
  override def apiKind: ApiKind = ApiKind.OAuth2Remote
}

object OAuth2RemoteAccessModeConfiguration {

  // a blank issuer is no issuer, compared as it is otherwise (oidc requires the exact value)
  def readIssuer(json: JsValue): Option[String] = json.select("issuer").asOpt[String].filter(_.trim.nonEmpty)

  // a string reads as a one element list, blank entries are dropped
  def readAudiences(json: JsValue): Seq[String] = (json.select("audiences").asOpt[JsValue] match {
    case Some(JsString(audience)) => Seq(audience)
    case Some(JsArray(values))    => values.collect { case JsString(audience) => audience }.toSeq
    case _                        => Seq.empty
  }).filter(_.trim.nonEmpty)

  def fmt = new Format[OAuth2RemoteAccessModeConfiguration] {
    override def reads(json: JsValue): JsResult[OAuth2RemoteAccessModeConfiguration] = Try {
      OAuth2RemoteAccessModeConfiguration(
        verifier = json.selectAsOptString("verifier"),
        clientIdPath = json.select("client_id_path").asOpt[String],
        fetchUser = json.select("fetch_user").asOpt[Boolean].getOrElse(false),
        userMetadataKey = json.select("user_metadata_key").asOpt[String].getOrElse("user_profile"),
        createIfMissing = json.select("create_if_missing").asOpt[Boolean].getOrElse(true),
        issuer = readIssuer(json),
        audiences = readAudiences(json),
        subscriptionRequired = json.select("subscription_required").asOpt[Boolean].getOrElse(false),
        restrictions = Restrictions.format.reads((json \ "restrictions").asOpt[JsValue].getOrElse(JsNull)).getOrElse(Restrictions())
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: OAuth2RemoteAccessModeConfiguration): JsValue             = Json.obj(
      "verifier"          -> o.verifier,
      "client_id_path"    -> o.clientIdPath,
      "fetch_user"        -> o.fetchUser,
      "user_metadata_key" -> o.userMetadataKey,
      "create_if_missing" -> o.createIfMissing,
      "issuer"            -> o.issuer,
      "audiences"         -> o.audiences,
      "subscription_required" -> o.subscriptionRequired,
      "restrictions"      -> o.restrictions.json
    )
  }
}

case class OAuth2AccessModeConfiguration(
    defaultKeyPair: String,
    expiration: Int
) extends ApiAccessModeConfiguration {
  override def apiKind: ApiKind = ApiKind.OAuth2Local
}

object OAuth2AccessModeConfiguration {
  def fmt = new Format[OAuth2AccessModeConfiguration] {
    override def reads(json: JsValue): JsResult[OAuth2AccessModeConfiguration] = Try {
      OAuth2AccessModeConfiguration(
        defaultKeyPair = json.selectAsString("default_key_pair"),
        expiration = json.selectAsInt("expiration")
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: OAuth2AccessModeConfiguration): JsValue             = Json.obj(
      "default_key_pair" -> o.defaultKeyPair,
      "expiration"       -> o.expiration
    )
  }
}

case class ApikeyAccessModeConfiguration(
    clientNamePattern: Option[String] = None,
    description: Option[String] = None,
    authorizedEntities: Seq[EntityIdentifier] = Seq.empty,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    allowClientIdOnly: Boolean = false,
    constrainedServicesOnly: Boolean = false,
    override val restrictions: Restrictions = Restrictions(),
    validUntil: Option[DateTime] = None,
    rotation: ApiKeyRotation = ApiKeyRotation(),
    tags: Seq[String] = Seq.empty[String],
    metadata: Map[String, String] = Map.empty[String, String],
    // how long a key is valid, in milliseconds, counted from the first time it is enabled. Combined with validUntil,
    // the key expires at the earlier of the two
    validity: Option[Long] = None
) extends ApiAccessModeConfiguration {
  override def apiKind: ApiKind = ApiKind.Apikey
  def json: JsValue = {
    val enabled            = validUntil match {
      case Some(date) if date.isBeforeNow => false
      case _                              => this.enabled
    }
    val authGroup: JsValue = authorizedEntities
      .find {
        case ServiceGroupIdentifier(_) => true
        case _                         => false
      }
      .map(_.id)
      .map(JsString.apply)
      .getOrElse(JsNull) // simulate old behavior
    Json.obj(
      "clientNamePattern"       -> clientNamePattern,
      "description"             -> description,
      "authorizedGroup"         -> authGroup,
      "authorizedEntities"      -> JsArray(authorizedEntities.map(_.json)),
      "authorizations"          -> JsArray(authorizedEntities.map(_.modernJson)),
//      "throttlingQuota"         -> throttlingQuota,
//      "dailyQuota"              -> dailyQuota,
//      "monthlyQuota"            -> monthlyQuota,
      "enabled"                 -> enabled,
      "readOnly"                -> readOnly,
      "allowClientIdOnly"       -> allowClientIdOnly,
      "constrainedServicesOnly" -> constrainedServicesOnly,
      "restrictions"            -> restrictions.json,
      "rotation"                -> rotation.json,
      "validUntil"              -> validUntil.map(v => JsNumber(v.toDate.getTime)).getOrElse(JsNull).as[JsValue],
      "validity"                -> validity.map(JsNumber(_)).getOrElse(JsNull).as[JsValue],
      "tags"                    -> JsArray(tags.map(JsString.apply)),
      "metadata"                -> JsObject(metadata.filter(_._1.nonEmpty).view.mapValues(JsString.apply).toMap)
    )
  }
}

object ApikeyAccessModeConfiguration {
  def fmt: Format[ApikeyAccessModeConfiguration] =
    new Format[ApikeyAccessModeConfiguration] {
      override def reads(json: JsValue): JsResult[ApikeyAccessModeConfiguration] = Try {
        ApikeyAccessModeConfiguration(
          clientNamePattern = json.selectAsOptString("clientNamePattern"),
          description = (json \ "description").asOpt[String],
          authorizedEntities = {
            val authorizations: Seq[EntityIdentifier]     = json
              .select("authorizations")
              .asOpt[Seq[JsValue]]
              .map { values =>
                values
                  .collect {
                    case JsString(value)     => EntityIdentifier.apply(value)
                    case value @ JsObject(_) => EntityIdentifier.applyModern(value)
                  }
                  .collect { case Some(id) =>
                    id
                  }
              }
              .getOrElse(Seq.empty[EntityIdentifier]).toSeq
            val authorizedGroup: Seq[EntityIdentifier]    =
              (json \ "authorizedGroup").asOpt[String].map(ServiceGroupIdentifier.apply).toSeq
            val authorizedEntities: Seq[EntityIdentifier] =
              (json \ "authorizedEntities")
                .asOpt[Seq[String]]
                .map { identifiers =>
                  identifiers.map(EntityIdentifier.apply).collect { case Some(id) =>
                    id
                  }
                }
                .getOrElse(Seq.empty[EntityIdentifier]).toSeq
            (authorizations ++ authorizedEntities ++ authorizedGroup).distinct
          },
          enabled = json.selectAsOptBoolean("enabled").getOrElse(true),
          readOnly = (json \ "readOnly").asOpt[Boolean].getOrElse(false),
          allowClientIdOnly = (json \ "allowClientIdOnly").asOpt[Boolean].getOrElse(false),
          constrainedServicesOnly = (json \ "constrainedServicesOnly").asOpt[Boolean].getOrElse(false),
//          throttlingQuota = json
//            .select("throttlingQuota")
//            .asOptLong
//            .orElse(json.select("throttling_quota").asOptLong)
//            .getOrElse(RemainingQuotas.MaxValue),
//          dailyQuota = json
//            .select("dailyQuota")
//            .asOptLong
//            .orElse(json.select("daily_quota").asOptLong)
//            .getOrElse(RemainingQuotas.MaxValue),
//          monthlyQuota = json
//            .select("monthlyQuota")
//            .asOptLong
//            .orElse(json.select("monthly_quota").asOptLong)
//            .getOrElse(RemainingQuotas.MaxValue),
          restrictions = Restrictions.format
            .reads((json \ "restrictions").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(Restrictions()),
          rotation = ApiKeyRotation.fmt
            .reads((json \ "rotation").asOpt[JsValue].getOrElse(JsNull))
            .getOrElse(ApiKeyRotation()),
          validUntil = (json \ "validUntil").asOpt[Long].map(l => new DateTime(l)),
          // the write of an api refuses a validity that is not a positive number of milliseconds
          validity = (json \ "validity").asOpt[Long].filter(_ > 0),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]).toSeq,
          metadata = (json \ "metadata")
            .asOpt[Map[String, String]]
            .map(m => m.filter(_._1.nonEmpty))
            .getOrElse(Map.empty[String, String])
        )
      } match {
        case Failure(e)  => JsError(e.getMessage)
        case Success(ur) => JsSuccess(ur)
      }

      override def writes(apk: ApikeyAccessModeConfiguration): JsValue = apk.json
    }
}

sealed trait ApiDocumentationPlanVisibilityKind {
  def name: String
  def json: JsValue = name.json
}
object ApiDocumentationPlanVisibilityKind       {

  case object Public     extends ApiDocumentationPlanVisibilityKind { def name: String = "public"      }
  case object SemiPublic extends ApiDocumentationPlanVisibilityKind { def name: String = "semi_public" }
  case object Private    extends ApiDocumentationPlanVisibilityKind { def name: String = "private"     }
  case object Custom     extends ApiDocumentationPlanVisibilityKind { def name: String = "custom"      }

  def apply(str: String): ApiDocumentationPlanVisibilityKind = str match {
    case "public"      => Public
    case "semi_public" => SemiPublic
    case "private"     => Private
    case "custom"      => Custom
    case _             => Private
  }
}

case class ApiPlanVisibility(kind: ApiDocumentationPlanVisibilityKind, config: JsObject = Json.obj()) {
  def json: JsValue = ApiPlanVisibility.format.writes(this)
}

object ApiPlanVisibility {
  val Public: ApiPlanVisibility = ApiPlanVisibility(ApiDocumentationPlanVisibilityKind.Public)
  val format                                 = new Format[ApiPlanVisibility] {
    override def reads(json: JsValue): JsResult[ApiPlanVisibility] = Try {
      ApiPlanVisibility(
        kind = ApiDocumentationPlanVisibilityKind(json.select("kind").asOptString.getOrElse("public")),
        config = json.select("config").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: ApiPlanVisibility): JsValue             = Json.obj(
      "kind"   -> o.kind.json,
      "config" -> o.config
    )
  }
}

sealed trait ApiDocumentationPlanValidationKind {
  def name: String
  def json: JsValue = name.json
}
object ApiDocumentationPlanValidationKind       {

  case object Auto     extends ApiDocumentationPlanValidationKind { def name: String = "auto"     }
  case object Manual   extends ApiDocumentationPlanValidationKind { def name: String = "manual"   }
  case object Webhook  extends ApiDocumentationPlanValidationKind { def name: String = "webhook"  }
  case object Workflow extends ApiDocumentationPlanValidationKind { def name: String = "workflow" }
  case object Wasm     extends ApiDocumentationPlanValidationKind { def name: String = "wasm"     }
  case object Custom   extends ApiDocumentationPlanValidationKind { def name: String = "custom"   }

  // an unknown kind needs a manual validation rather than accepting every subscription
  def apply(str: String): ApiDocumentationPlanValidationKind = str.trim.toLowerCase match {
    case "auto"     => Auto
    case "manual"   => Manual
    case "webhook"  => Webhook
    case "workflow" => Workflow
    case "wasm"     => Wasm
    case "custom"   => Custom
    case _          => Manual
  }
}

case class ApiPlanValidation(kind: ApiDocumentationPlanValidationKind, config: JsObject = Json.obj()) {
  def isAuto: Boolean = kind == ApiDocumentationPlanValidationKind.Auto
  def json: JsValue   = ApiPlanValidation.format.writes(this)
}
object ApiPlanValidation                                                                              {
  val Auto   = ApiPlanValidation(ApiDocumentationPlanValidationKind.Auto)
  val format = new Format[ApiPlanValidation] {

    override def reads(json: JsValue): JsResult[ApiPlanValidation] = Try {
      ApiPlanValidation(
        kind = ApiDocumentationPlanValidationKind(json.select("kind").asOptString.getOrElse("auto")),
        config = json.select("config").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiPlanValidation): JsValue = Json.obj(
      "kind"   -> o.kind.json,
      "config" -> o.config
    )
  }
}

case class ApiPlan(raw: JsObject) {
  lazy val accessModeConfigurationType                                              = raw.selectAsOptString("access_mode_configuration_type").getOrElse("keyless")
  lazy val id: String                                                               = raw.selectAsString("id")
  lazy val name: String                                                             = raw.selectAsString("name")
  lazy val description: String                                                      = raw.selectAsOptString("description").getOrElse("No description")
  lazy val pricing: ApiPricing                                                      = raw.select("pricing").as(using ApiPricing.format)
  // a plan always carries a throttling strategy, so that an apikey it mints is rate limited exactly
  // like a classic one: without an explicit strategy it falls back on the legacy one with the
  // default quotas, which is what an apikey created by hand gets.
  lazy val rateLimiting: Option[ThrottlingStrategyConfig]                           =
    raw
      .select("rateLimiting")
      .asOpt[JsObject]
      .orElse(raw.select("rate_limiting").asOpt[JsObject])
      .flatMap(rateLimiting => rateLimiting.select("strategy").asOpt(using ThrottlingStrategyConfig.fmt))
      .orElse(LegacyThrottlingStrategyConfig().some)
  lazy val accessModeConfiguration: Option[ApiAccessModeConfiguration] =
    accessModeConfigurationType match {
      case "keyless"       => (raw \ "access_mode_configuration").asOpt(using KeylessAccessModeConfiguration.fmt)
      case "apikey"        => (raw \ "access_mode_configuration").asOpt(using ApikeyAccessModeConfiguration.fmt)
      case "jwt"           => (raw \ "access_mode_configuration").asOpt(using JWTAccessModeConfiguration.fmt)
      case "mtls"          => (raw \ "access_mode_configuration").asOpt(using MtlsAccessModeConfiguration.fmt)
      case "oauth2-local"  => (raw \ "access_mode_configuration").asOpt(using OAuth2AccessModeConfiguration.fmt)
      case "oauth2-remote" => (raw \ "access_mode_configuration").asOpt(using OAuth2RemoteAccessModeConfiguration.fmt)
      case _               => None
    }
  lazy val status: ApiPlanStatus                                                    = raw.selectAsOptString("status").getOrElse("published").toLowerCase match {
    case "staging"    => ApiPlanStatus.Staging
    case "published"  => ApiPlanStatus.Published
    case "deprecated" => ApiPlanStatus.Deprecated
    case _            => ApiPlanStatus.Closed
  }
  // whether the plan installs its access plugins on the routes of its api
  def isServed: Boolean                                                             = ApiPlanStatus.isServed(status)
  lazy val tags: Seq[String]                                                        = raw.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq
  lazy val metadata: Map[String, String]                                            = raw.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val validation: ApiPlanValidation                               = raw
    .select("validation")
    .asOpt[JsObject]
    .flatMap(o => ApiPlanValidation.format.reads(o).asOpt)
    .getOrElse(ApiPlanValidation.Auto)
  lazy val visibility: ApiPlanVisibility                               = raw
    .select("visibility")
    .asOpt[JsObject]
    .flatMap(o => ApiPlanVisibility.format.reads(o).asOpt)
    .getOrElse(ApiPlanVisibility.Public)
  // what happens to a call when the counter of the quotas of the plan cannot be reached
  lazy val counterFailure: ApiPlanCounterFailure =
    raw.select("counter_failure").asOpt[JsObject].map(ApiPlanCounterFailure.read).getOrElse(ApiPlanCounterFailure())
  // the circuit breaker of the backend the consumers of the plan share: the one of the route, or one per consumer
  lazy val circuitBreakerScope: String =
    raw.select("circuit_breaker_scope").asOpt[String].map(_.trim.toLowerCase) match {
      case Some(ApiPlan.ConsumerScope) => ApiPlan.ConsumerScope
      case _                           => ApiPlan.RouteScope
    }
  // the conditions a call has to meet for the plan to apply to it, on a keyless, jwt, mtls or oauth2-remote plan: the
  // plan of a call that fails one does not identify anyone, and the next plan gets the call. Empty: every call its
  // extractor can identify
  lazy val selection: Seq[otoroshi.utils.JsonPathValidator] = raw
    .select("selection")
    .asOpt[JsArray]
    .map(_.value.flatMap(v => otoroshi.utils.JsonPathValidator.format.reads(v).asOpt).toSeq)
    .getOrElse(Seq.empty)
  // the prefix of the client ids of the api keys the extractor of a keyless, jwt, mtls or oauth2-remote plan mints for
  // its consumers, and the subscriptions to it provision: <kind>_<api id>_<plan id>_. The id of a plan is only unique
  // in its api, so the api id keeps two apis with a plan of the same id from sharing their consumers
  def consumerClientIdPrefix(apiId: String): Option[String] =
    ApiPlan.consumerClientIdKind(accessModeConfigurationType).map(kind => s"${kind}_${apiId}_${id}_")
  // the apikey settings a plan hands over when an apikey has to be minted for a consumer. only an
  // apikey plan carries a full template, the other kinds only have what the plan itself declares and the
  // restrictions of their access mode.
  lazy val apikeyTemplate: ApikeyAccessModeConfiguration = accessModeConfiguration match {
    case Some(conf: ApikeyAccessModeConfiguration) => conf
    // the restrictions of the access mode of the other kinds, none for an oauth2-local plan
    case conf                                      =>
      ApikeyAccessModeConfiguration(
        tags = tags,
        metadata = metadata,
        restrictions = conf.map(_.restrictions).getOrElse(Restrictions())
      )
  }
  lazy val plugins: Option[ApiPlanPlugins] =
    raw.select("plugins").asOpt[JsObject].flatMap(o => ApiPlanPlugins.format.reads(o).asOpt)
  lazy val hasPlugins: Boolean =
    accessModeConfiguration.exists(_.plugins.nonEmpty) || plugins.exists(p => p.plugins.nonEmpty || p.flowRef.isDefined)
  // takes the api because a plan can reference one of its flows instead of listing the plugins:
  // the reference is resolved here, and an inline chain extends whatever the flow brought.
  def computedPlugins(api: Api): NgPluginsWithOverride = {
    val fromFlow = plugins
      .flatMap(_.flowRef)
      .flatMap(ref => api.flows.find(_.id == ref))
      .map(_.plugins.slots)
      .getOrElse(Seq.empty)
    NgPluginsWithOverride(
      plugins = NgPlugins(
        accessModeConfiguration.map(_.plugins.slots).getOrElse(Seq.empty)
          ++ fromFlow
          ++ plugins.map(_.plugins.slots).getOrElse(Seq.empty)
      ),
      overrides = plugins.exists(_.overrides),
    )
  }
}

case class ApiDocumentationSource(raw: JsObject) {
  lazy val url: Option[String]              = raw.select("url").asOpt[String]
  lazy val httpHeaders: Map[String, String] = raw.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val httpTimeout: FiniteDuration      = raw.select("timeout").asOpt[Long].getOrElse(30000L).millis
  lazy val httpFollowRedirects: Boolean     = raw.select("follow_redirects").asOpt[Boolean].getOrElse(true)
  def resolve(doc: ApiDocumentation)(using env: Env, ec: ExecutionContext): Future[Option[ApiDocumentation]] = {
    url match {
      case None      => None.vfuture
      case Some(url) => {
        env.Ws
          .url(url)
          .withFollowRedirects(httpFollowRedirects)
          .withHttpHeaders(httpHeaders.toSeq*)
          .withRequestTimeout(httpTimeout)
          .get() map { resp =>
          if (resp.status == 200) {
            ApiDocumentation._fmt.reads(resp.json).asOpt.map { remoteDoc =>
              remoteDoc.copy(
                source = doc.source,
                references = remoteDoc.references ++ doc.references,
                resources = remoteDoc.resources ++ doc.resources,
                navigation = remoteDoc.navigation ++ doc.navigation,
                redirections = remoteDoc.redirections ++ doc.redirections,
                footer = remoteDoc.footer.orElse(doc.footer),
                banner = remoteDoc.banner.orElse(doc.banner)
              )
            }
          } else {
            None
          }
        }
      }
    }
  }
}

case class ApiDocumentation(
    enabled: Boolean = true,
    source: Option[ApiDocumentationSource] = None,
    home: ApiDocumentationResource = ApiDocumentationResource(Json.obj()),
    logo: ApiDocumentationResource = ApiDocumentationResource(Json.obj()),
    references: Seq[ApiDocumentationResourceRef] = Seq.empty,
    resources: Seq[ApiDocumentationResource] = Seq.empty,
    navigation: Seq[ApiDocumentationSidebar] = Seq.empty,
    redirections: Seq[ApiDocumentationRedirection] = Seq.empty,
    footer: Option[ApiDocumentationResource] = None,
    search: ApiDocumentationSearch = ApiDocumentationSearch.default,
    banner: Option[ApiDocumentationResource] = None,
    metadata: Map[String, String] = Map.empty,
    tags: Seq[String] = Seq.empty
) {
  def json: JsValue = ApiDocumentation._fmt.writes(this)
}

case class ApiClient(
    id: String,
    name: String,
    description: Option[String] = None,
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty
)

object ApiClient {
  def format = new Format[ApiClient] {
    override def reads(json: JsValue): JsResult[ApiClient] = Try {
      ApiClient(
        id = json.selectAsString("id"),
        name = json.selectAsString("name"),
        description = json.selectAsOptString("description"),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]).toSeq
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiClient): JsValue = Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "tags"        -> o.tags,
      "metadata"    -> o.metadata
    )
  }
}

object ApiDocumentation {
  val _fmt: Format[ApiDocumentation] = new Format[ApiDocumentation] {
    override def reads(json: JsValue): JsResult[ApiDocumentation] = Try {
      ApiDocumentation(
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        source = json.select("source").asOpt[JsObject].map(o => ApiDocumentationSource(o)),
        home = ApiDocumentationResource(json.select("home").asOpt[JsObject].getOrElse(Json.obj())),
        logo = ApiDocumentationResource(json.select("logo").asOpt[JsObject].getOrElse(Json.obj())),
        references =
          json.select("references").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.map(o => ApiDocumentationResourceRef(o)),
        resources =
          json.select("resources").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.map(o => ApiDocumentationResource(o)),
        navigation =
          json.select("navigation").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.map(o => ApiDocumentationSidebar(o)),
        redirections = json
          .select("redirections")
          .asOpt[Seq[JsObject]]
          .getOrElse(Seq.empty).toSeq
          .map(o => ApiDocumentationRedirection(o)),
        footer = json.select("footer").asOpt[JsObject].map(o => ApiDocumentationResource(o)),
        search = json
          .select("search")
          .asOpt[JsObject]
          .map(o => ApiDocumentationSearch(o))
          .getOrElse(ApiDocumentationSearch.default),
        banner = json.select("banner").asOpt[JsObject].map(o => ApiDocumentationResource(o))
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiDocumentation): JsValue = Json.obj(
      "enabled"      -> o.enabled,
      "source"       -> o.source.map(_.raw).getOrElse(JsNull).asValue,
      "home"         -> o.home.raw,
      "logo"         -> o.logo.raw,
      "references"   -> JsArray(o.references.map(_.raw)),
      "resources"    -> JsArray(o.resources.map(_.raw)),
      "navigation"   -> JsArray(o.navigation.map(_.raw)),
      "redirections" -> JsArray(o.redirections.map(_.raw)),
      "footer"       -> o.footer.map(_.raw).getOrElse(JsNull).asValue,
      "search"       -> o.search.raw,
      "banner"       -> o.banner.map(_.raw).getOrElse(JsNull).asValue,
      "metadata"     -> o.metadata,
      "tags"         -> o.tags
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

case class ApiSubscriptionDates(
    created_at: DateTime,
    processed_at: DateTime,
    started_at: DateTime,
    paused_at: DateTime,
    ending_at: DateTime,
    closed_at: DateTime
)

object ApiSubscriptionDates {
  val _fmt: Format[ApiSubscriptionDates] = new Format[ApiSubscriptionDates] {
    override def reads(json: JsValue): JsResult[ApiSubscriptionDates] = Try {
      ApiSubscriptionDates(
        created_at = json.select("created_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        processed_at = json.select("processed_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        started_at = json.select("started_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        paused_at = json.select("paused_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        ending_at = json.select("ending_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now()),
        closed_at = json.select("closed_at").asOpt[Long].map(l => new DateTime(l)).getOrElse(DateTime.now())
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiSubscriptionDates): JsValue = Json.obj(
      "created_at"   -> o.created_at.getMillis,
      "processed_at" -> o.processed_at.getMillis,
      "started_at"   -> o.started_at.getMillis,
      "paused_at"    -> o.paused_at.getMillis,
      "ending_at"    -> o.ending_at.getMillis,
      "closed_at"    -> o.closed_at.getMillis
    )
  }
}

case class ApiPricing(
    id: String,
    enabled: Boolean,
    name: String,
    price: Double,
    currency: String,
    params: JsValue
) {
  def json = Json.obj(
    "id"       -> id,
    "enabled"  -> enabled,
    "name"     -> name,
    "price"    -> price,
    "currency" -> currency,
    "params"   -> params
  )
}

object ApiPricing {
  def format = new Format[ApiPricing] {

    override def reads(json: JsValue): JsResult[ApiPricing] = Try {
      ApiPricing(
        id = json.selectAsString("id"),
        name = json.selectAsString("name"),
        enabled = json.selectAsBoolean("enabled"),
        price = json.selectAsDouble("price"),
        currency = json.selectAsString("currency"),
        params = json.selectAsOptValue("params").getOrElse(Json.obj())
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiPricing): JsValue = o.json
  }
}

case class ApiSubscription(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    status: ApiSubscriptionState,
    dates: ApiSubscriptionDates,
    ownerRef: String,
    planRef: String,
    apiRef: String,
    paymentRef: JsObject = Json.obj(),
    subscriptionKind: ApiKind,
    tokenRefs: Seq[JsValue], // ref to apikey, cert, etc
    // the client id a consumer of a jwt, mtls or oauth2-remote plan calls with, as the plan reads it (token claim,
    // certificate field), without prefix: the subscription provisions the api key the extractor of the plan resolves
    clientId: Option[String] = None
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = ApiSubscription.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  def save()(using ec: ExecutionContext, env: Env) = env.datastores.apiSubscriptionDataStore.set(this)
}

object ApiSubscription {

  val PLAN_METADATA_KEY           = "plan_metadata"
  val SUBSCRIPTION_METADATA_KEY   = "subscription_metadata"
  val PLAN_TAGS_KEY               = "plan_tags"
  val SUBSCRIPTION_TAGS_KEY       = "subscription_tags"
  val METADATA_AND_TAGS_SEPARATOR = " | "
  val CORE_METADATA               = Seq("updated_at")

  private val logger = Logger("otoroshi-api-subscriptions")

  // the kinds whose consumers the extractor of their plan identifies at call time, from a token claim or a certificate
  // field: a subscription with a client_id holds the key the extractor resolves for it
  def isConsumerKind(kind: ApiKind): Boolean =
    kind == ApiKind.JWT || kind == ApiKind.Mtls || kind == ApiKind.OAuth2Remote

  // the api keys minted for an apikey or an oauth2-local subscription, the one provisioned for the client_id of a
  // subscription to a jwt, mtls or oauth2-remote plan. A subscription without client_id holds none, as before
  private def holdsKeys(subscription: ApiSubscription): Boolean = subscription.subscriptionKind match {
    case ApiKind.Apikey | ApiKind.OAuth2Local => true
    case kind if isConsumerKind(kind)         => subscription.clientId.isDefined
    case _                                    => false
  }

  // the metadata telling the key of a consumer of a jwt, mtls or oauth2-remote plan, the same as on the keys the
  // extractor mints on the fly
  private def consumerMetadata(api: Api, plan: ApiPlan, subscription: ApiSubscription): Map[String, String] =
    if (isConsumerKind(subscription.subscriptionKind))
      ApikeyFromPlan.consumerMetadata(api.id, plan.id, subscription.subscriptionKind.name)
    else Map.empty

  // a key of the token_refs of a subscription is its own when it was minted for it: its apiRef names the subscription
  // (whatever the plan, which an update can change). The keys minted before the apiRef existed, from 17.15.0 to
  // 18.0.0-preview3, have none: such a key is its own when it is authorized on the api of the subscription and carries
  // the metadata a subscription writes on its keys. Any other key, the key of another subscription or a key no
  // subscription minted, is not: whoever can write a subscription can name any client id in its token_refs
  def ownsKey(subscription: ApiSubscription, apikey: ApiKey): Boolean = apikey.apiRef match {
    case Some(ref) => ref.subscription == subscription.id
    case None      =>
      apikey.authorizedEntities.contains(ApiIdentifier(subscription.apiRef)) &&
      apikey.metadata.contains(SUBSCRIPTION_METADATA_KEY)
  }

  private def notOwnedWarning(subscription: ApiSubscription, apikey: ApiKey, participle: String): Unit = {
    val owner = apikey.apiRef match {
      case Some(ref) => s"belongs to ${ref.json.stringify}"
      case None      => "was not minted by a subscription of its api"
    }
    logger.warn(
      s"api key '${apikey.clientId}' of subscription '${subscription.id}' $owner, it is not $participle with the subscription"
    )
  }

  // the entry of the token_refs of a subscription sharing the credential of its owner, an api key attached to nothing
  // that is only called through the alias keys of the subscriptions sharing it (ApiKey.sharedCredential):
  // {"apikey": "<client id>", "shared": true} names it, {"shared": true} takes the one of the owner of the
  // subscription, created when there is none. The subscription gets an api key of its own, built from its plan, that
  // the consumer calls through the credential (an alias key, ApiRef.credential). The credential is not the
  // subscription's: it is never updated, disabled or deleted with it
  val SHARED_REF_KEY = "shared"

  def isSharedRef(ref: JsValue): Boolean =
    ref.select(SHARED_REF_KEY).asOpt[Boolean].contains(true) && ref.select("apikey").asOpt[String].isDefined

  // {"shared": true}: the credential of the owner, whatever its client id
  def isOwnerCredentialRef(ref: JsValue): Boolean =
    ref.select(SHARED_REF_KEY).asOpt[Boolean].contains(true) && ref.select("apikey").asOpt[String].isEmpty

  def sharesOwnerCredential(subscription: ApiSubscription): Boolean = subscription.tokenRefs.exists(isOwnerCredentialRef)

  def sharedCredentials(subscription: ApiSubscription): Seq[String] =
    subscription.tokenRefs.collect { case ref if isSharedRef(ref) => ref.select("apikey").asString }

  def sharedCredential(subscription: ApiSubscription): Option[String] = sharedCredentials(subscription).headOption

  // the credentials of the owner of a subscription: the shared credentials of that owner a subscription minted
  // (SharedCredential.minted), located where the subscription is, in its tenant and in its teams. The owner_ref is free
  // text, and a subscription mints the credential of any owner where its author writes, who then reads its secret: the
  // one located elsewhere, in another tenant or in other teams, is not the one the subscriptions of that owner take
  // here, whoever writes them. Nor is one written by hand for that owner. Both are only shared when they are named
  def ownerCredentials(apikeys: Seq[ApiKey], subscription: ApiSubscription): Seq[ApiKey] =
    apikeys.filter(apikey =>
      apikey.sharedCredential.exists(credential => credential.owner == subscription.ownerRef && credential.minted) &&
      apikey.location.tenant == subscription.location.tenant &&
      apikey.location.teams.toSet == subscription.location.teams.toSet
    )

  // the consumers of an apikey and of an oauth2-local plan call with an api key, the only ones that share one
  def sharesCredentials(kind: ApiKind): Boolean = kind == ApiKind.Apikey || kind == ApiKind.OAuth2Local

  // the alias keys of a credential, read from the datastore
  def aliasesOf(credential: String)(using env: Env, ec: ExecutionContext): Future[Seq[ApiKey]] =
    env.datastores.apiKeyDataStore.findAliasesOf(Seq(credential))

  // whether an alias key is the one a subscription minted sharing its credential: an alias only serves while its
  // subscription exists, shares the credential, and lists the alias among its own keys (whatever the plan, which an
  // update can change, like ownsKey). An alias written by hand does not, even one naming a subscription that shares
  // the credential
  def mintedAlias(subscription: ApiSubscription, alias: ApiKey): Boolean =
    alias.apiRef.exists { ref =>
      ref.subscription == subscription.id && ref.api == subscription.apiRef && ref.credential.exists { credential =>
        subscription.tokenRefs.exists(tokenRef =>
          isSharedRef(tokenRef) && tokenRef.select("apikey").asOpt[String].contains(credential)
        ) &&
        subscription.tokenRefs.exists(tokenRef =>
          !isSharedRef(tokenRef) && tokenRef.select("apikey").asOpt[String].contains(alias.clientId)
        )
      }
    }

  // the aliases of a credential whose subscription still shares it, read from the datastore
  private def mintedAliasesOf(credential: String)(using env: Env, ec: ExecutionContext): Future[Seq[ApiKey]] =
    aliasesOf(credential).flatMap { aliases =>
      aliases
        .mapAsync { alias =>
          env.datastores.apiSubscriptionDataStore
            .findById(alias.apiRef.map(_.subscription).getOrElse(""))
            .map(_.filter(subscription => mintedAlias(subscription, alias)).map(_ => alias))
        }
        .map(_.flatten)
    }

  // whether a user can share an api key: the rights to write it, read from the proxy state. A key the proxy state does
  // not have yet is only shared by a super admin
  def canUserShare(credential: String, user: otoroshi.models.BackOfficeUser)(using env: Env): Boolean =
    otoroshi.actions.ApiActionContext.canUserWriteCredential(credential, user)

  // a subscription sharing api keys its author cannot write: refused, and its stored version is not rewritten nor
  // deleted by that author. The drafts share nothing (checkSharedCredential)
  // creating: the subscription is being created, and the credentials the proxy state does not have yet (written a
  // moment ago) are left to the check made on the datastore (sharesStoredCredentialMoreThan). A stored subscription is
  // only rewritten or deleted by the ones who can write a credential the proxy state has
  def sharesMoreThan(subscription: ApiSubscription, user: otoroshi.models.BackOfficeUser, creating: Boolean = false)(
      using env: Env
  ): Boolean =
    sharedCredentials(subscription).exists(clientId =>
      if (env.proxyState.apikey(clientId).isEmpty && (creating || sharedDeletedCredential(subscription, clientId))) false
      else !canUserShare(clientId, user)
    ) ||
    // the credential of the owner is not named: the ones the proxy state has are the ones that would be shared. A
    // credential created since the last sync of the proxy state is not seen here: the creation of a subscription
    // checks the ones of the datastore too (sharesStoredCredentialMoreThan). The one a subscription created at once
    // writes between these checks and the validation (withOwnerCredential) is read by none of them: it is located
    // where the subscription is, like the one the subscription would create, and its author has to be allowed to write
    // an api key there, whether the credential exists or not
    (sharesOwnerCredential(subscription) && (
      !otoroshi.actions.ApiActionContext.canUserWriteApikeysAt(subscription.location, user) ||
      ownerCredentials(env.proxyState.allApikeys(), subscription).exists(apikey => !canUserShare(apikey.clientId, user))
    ))

  // whether the credential a stored subscription shares was deleted: the proxy state has the subscription sharing it,
  // and not the credential, which was there before the subscription. A credential deleted by hand takes its aliases and
  // leaves its subscriptions: there is nothing left to check the rights against, and the ones who can write such a
  // subscription update and delete it, or only a super admin would. The proxy state reads the api keys before the
  // subscriptions: a credential and its subscription both created between these two reads look the same until the next
  // sync, and the shared entry, the api and the owner of a subscription never change (checkSharedCredential)
  private def sharedDeletedCredential(subscription: ApiSubscription, credential: String)(using env: Env): Boolean =
    env.proxyState.apiSubscription(subscription.id).exists(stored => sharedCredential(stored).contains(credential))

  // the same, of the credential a subscription shares, read from the datastore like the validation reads it
  // (checkShareable, withOwnerCredential): the one that is shared is the one that is checked, whatever the proxy state
  // has. The one it names, and the one of its owner when it names none ({"shared": true})
  def sharesStoredCredentialMoreThan(subscription: ApiSubscription, user: otoroshi.models.BackOfficeUser)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Boolean] = {
    def cannotWrite(apikey: ApiKey): Boolean = !otoroshi.actions.ApiActionContext.canUserWriteCredential(apikey, user)
    sharedCredentials(subscription)
      .mapAsync(clientId => env.datastores.apiKeyDataStore.findById(clientId))
      .flatMap { named =>
        if (named.flatten.exists(cannotWrite)) true.vfuture
        else if (!sharesOwnerCredential(subscription)) false.vfuture
        else
          env.datastores.apiKeyDataStore
            .findAll(force = true)
            .map(apikeys => ownerCredentials(apikeys, subscription).exists(cannotWrite))
      }
  }

  // applies f to the api keys of the token_refs of a subscription that are its own (ownsKey), the others are left alone
  private def onOwnKeys(subscription: ApiSubscription, verb: String, participle: String)(
      f: ApiKey => Future[Any]
  )(using env: Env): Future[Either[String, Unit]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    subscription.tokenRefs
      .filterNot(isSharedRef)
      .flatMap(ref => ref.select("apikey").asOpt[String])
      .mapAsync { clientId =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None                                            => ().vfuture
          case Some(apikey) if !ownsKey(subscription, apikey) =>
            notOwnedWarning(subscription, apikey, participle)
            ().vfuture
          case Some(apikey)                                    => f(apikey).map(_ => ())
        }
      }
      .map(_ => Right(()): Either[String, Unit])
      .recover { case e: Throwable =>
        logger.error(s"failed to $verb the api keys of subscription '${subscription.id}'", e)
        Left(s"failed to $verb the api keys of subscription '${subscription.id}'")
      }
  }

  // deletes the api keys a subscription holds, whatever its kind: what a closed plan does, since its extractor no
  // longer resolves them
  def deleteKeys(subscription: ApiSubscription)(using env: Env): Future[Either[String, Unit]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    // the credential a subscription shares is not its own: it stays, and is deleted by hand
    val deleted =
      if (holdsKeys(subscription))
        onOwnKeys(subscription, "delete", "deleted")(apikey => env.datastores.apiKeyDataStore.delete(apikey.clientId))
      else Right(()).vfuture
    // its alias is gone: the api is free for the credential again (reservingAlias)
    deleted.flatMap(result => releaseAliasReservation(subscription).map(_ => result))
  }

  // what a deleted subscription does to its api keys. The keys minted for an apikey or an oauth2-local subscription
  // are deleted: they were minted for it and are nobody else's. The key of a consumer of a jwt, mtls or oauth2-remote
  // plan is disabled, not deleted: the extractor of the plan would mint it again, enabled, at the next call
  def revokeKeys(subscription: ApiSubscription)(using env: Env): Future[Either[String, Unit]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    subscription.subscriptionKind match {
      case kind if isConsumerKind(kind) && holdsKeys(subscription) =>
        onOwnKeys(subscription, "disable", "disabled") { apikey =>
          if (apikey.enabled) apikey.copy(enabled = false).save() else ().vfuture
        }
      case _                                                       => deleteKeys(subscription)
    }
  }

  private def generateNewApikeyFromPlan(
      api: Api,
      plan: ApiPlan,
      subscription: ApiSubscription,
      clientId: Option[String] = None
  )(using env: Env) = {
    // never cast the configuration of the plan: an oauth2-local plan carries its own, and a plan whose
    // kind changed still has subscriptions holding apikeys that have to be kept in sync
    val configPlan = plan.apikeyTemplate

    val attrs = TypedMap(
      otoroshi.plugins.Keys.PlanKey -> plan,
      otoroshi.plugins.Keys.ApiKey  -> api
    )

    val keyId = clientId.getOrElse(IdGenerator.lowerCaseToken(16))

    // the key of a subscription sharing the key of another one is an alias: the consumer calls with the credential,
    // whose secret rotates with the plan that minted it, the alias has no secret of its own to rotate
    val credential = sharedCredential(subscription)

    val defaultApikey = ApiKey(
      enabled = false,
      clientId = keyId,
      clientSecret = IdGenerator.lowerCaseToken(64),
      // the key of a consumer is named after its client id, like the ones the extractor mints on the fly
      clientName = configPlan.clientNamePattern
        .map(_.evaluateEl(attrs)(using env))
        .getOrElse(if (isConsumerKind(subscription.subscriptionKind)) keyId else IdGenerator.lowerCaseToken(22)),
      description = configPlan.description.getOrElse(""),
      validUntil = configPlan.validUntil,
      readOnly = configPlan.readOnly,
      constrainedServicesOnly = configPlan.constrainedServicesOnly,
      restrictions = configPlan.restrictions,
      rotation = if (credential.isDefined) configPlan.rotation.copy(enabled = false, nextSecret = None) else configPlan.rotation,
      authorizedEntities = Seq(ApiIdentifier(api.id)),
      throttlingStrategy = plan.rateLimiting,
      apiRef = ApiRef(api = api.id, plan = plan.id, subscription = subscription.id, credential = credential).some,
      metadata = (configPlan.metadata ++ subscription.metadata +
        (PLAN_METADATA_KEY         -> configPlan.metadata.keySet.mkString(METADATA_AND_TAGS_SEPARATOR)) +
        (SUBSCRIPTION_METADATA_KEY -> subscription.metadata.keySet.mkString(METADATA_AND_TAGS_SEPARATOR)) +
        (PLAN_TAGS_KEY             -> configPlan.tags.mkString(METADATA_AND_TAGS_SEPARATOR)) +
        (SUBSCRIPTION_TAGS_KEY     -> subscription.tags.mkString(METADATA_AND_TAGS_SEPARATOR))) ++
        consumerMetadata(api, plan, subscription),
      tags = configPlan.tags ++ subscription.tags
    )

    // only an enabled subscription gives access: a pending one (not confirmed yet), a disabled, a deprecated or a
    // custom one gets a disabled key. Create, update, plan changes and confirm all build the key here, so the key
    // follows the status of its subscription
    withValidity(defaultApikey.copy(enabled = subscription.status == ApiSubscriptionEnabled), None, configPlan)
  }

  // the start of the validity of a key, in milliseconds: from then on, the date of the key is its own
  val VALIDITY_STARTED_AT_KEY = "validity_started_at"

  // the earlier of two expiries, none meaning never
  private def earlier(a: Option[DateTime], b: Option[DateTime]): Option[DateTime] = (a, b) match {
    case (Some(x), Some(y)) => Some(if (x.isBefore(y)) x else y)
    case _                  => a.orElse(b)
  }

  // the validUntil of a key built from the template of a plan. A key whose validity has started keeps its own date,
  // capped by the one of the template, whatever the plan says later: a date written on it (a renewal) survives the
  // updates of its subscription and of its plan. Otherwise, with a validity in the template, the validity starts the
  // first time the key is enabled, not while its subscription waits for an approval. Without, the template decides
  private def withValidity(built: ApiKey, existing: Option[ApiKey], template: ApikeyAccessModeConfiguration): ApiKey =
    existing.flatMap(_.metadata.get(VALIDITY_STARTED_AT_KEY)) match {
      case Some(startedAt) =>
        built.copy(
          validUntil = earlier(existing.flatMap(_.validUntil), template.validUntil),
          metadata = built.metadata + (VALIDITY_STARTED_AT_KEY -> startedAt)
        )
      case None            =>
        template.validity match {
          case Some(validity) if built.enabled =>
            val now = DateTime.now()
            built.copy(
              validUntil = earlier(Some(now.plus(validity)), template.validUntil),
              metadata = built.metadata + (VALIDITY_STARTED_AT_KEY -> now.getMillis.toString)
            )
          case _                               => built.copy(validUntil = template.validUntil)
        }
    }

  // the credential of the owner of a subscription sharing it without naming it ({"shared": true}): the one there is,
  // or a new one, attached to nothing, located where the subscription is. The entry then names it, like any other
  private def withOwnerCredential(subscription: ApiSubscription)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Either[String, (ApiSubscription, Option[String])]] = {
    // the subscription naming its credential, and the client id of that credential when it was created here
    def naming(credential: ApiKey, created: Boolean = false): (ApiSubscription, Option[String]) =
      (
        subscription.copy(tokenRefs = subscription.tokenRefs.map { ref =>
          if (isOwnerCredentialRef(ref)) Json.obj("apikey" -> credential.clientId, SHARED_REF_KEY -> true) else ref
        }),
        if (created) Some(credential.clientId) else None
      )
    if (!sharesOwnerCredential(subscription)) (subscription, None).rightf
    else
      env.datastores.apiKeyDataStore.findAll(force = true).flatMap { apikeys =>
        ownerCredentials(apikeys, subscription) match {
          case Seq(credential) => naming(credential).rightf
          case Seq()           =>
            // two subscriptions of an owner created at once both find no credential: the one that reserves it creates
            // it, the other one shares what was reserved
            val clientId    = IdGenerator.lowerCaseToken(16)
            val reservation = ownerCredentialReservation(subscription)
            env.datastores.rawDataStore
              .setnx(reservation, ByteString(clientId), Some(ownerCredentialReservationTtl.toMillis))
              .flatMap {
                case true  =>
                  val credential = ApiKey(
                    clientId = clientId,
                    clientSecret = IdGenerator.lowerCaseToken(64),
                    clientName = s"credential of ${subscription.ownerRef}",
                    description = s"The shared credential of '${subscription.ownerRef}', called through the api keys of its subscriptions",
                    authorizedEntities = Seq.empty,
                    sharedCredential = Some(SharedCredential(subscription.ownerRef, minted = true)),
                    location = subscription.location
                  )
                  credential.save().flatMap {
                    case true  => naming(credential, created = true).rightf
                    // nothing was created: the next subscription reserves it again
                    case false =>
                      env.datastores.rawDataStore
                        .del(Seq(reservation))
                        .map(_ => "failed to create the shared credential".left)
                  }
                case false =>
                  env.datastores.rawDataStore.get(reservation).flatMap {
                    case None           => ownerCredentialBeingCreated(subscription).leftf
                    case Some(reserved) =>
                      reservedOwnerCredential(reserved.utf8String, ownerCredentialReservationAttempts).map {
                        // where this subscription would have created it (ownerCredentials). The rights of the author
                        // on that credential were not checked, it did not exist yet: the ones to write an api key
                        // there were (sharesMoreThan)
                        case Some(credential) if ownerCredentials(Seq(credential), subscription).nonEmpty =>
                          naming(credential).right
                        case _ => ownerCredentialBeingCreated(subscription).left
                      }
                  }
              }
          case _               => severalOwnerCredentials(subscription).leftf
        }
      }
  }

  // the reservation of the credential of an owner where a subscription is located (its tenant, its teams), the client
  // id of the credential that is being created for it: what makes its creation by {"shared": true} happen once. It only
  // has to outlive the time a subscription takes between its read of the api keys and its own reservation, and expires
  // by itself: the credential it names is then found among the api keys. A credential deleted by hand is created again
  // once its reservation has expired
  private val ownerCredentialReservationTtl      = 10.seconds
  private val ownerCredentialReservationAttempts = 20

  // a key space of its own: under the one of an entity (apis, apikeys), the reservation would be read with the entities
  // (findAll), and it is not one. Under a prefix of its own too (owners): the owner_ref is free text, and without it a
  // tenant named like the prefix of the reservations of the aliases (aliasReservation) would write their keys. The
  // credential of an owner is the one of a location (ownerCredentials): the subscriptions of other teams reserve theirs
  private def ownerCredentialReservation(subscription: ApiSubscription)(using env: Env): String = {
    val tenant = subscription.location.tenant.value
    val teams  = subscription.location.teams.map(_.value).distinct.sorted.mkString(",")
    s"${env.storageRoot}:shared-credential-reservations:owners:$tenant:$teams:${subscription.ownerRef}"
  }

  private def ownerCredentialBeingCreated(subscription: ApiSubscription): String =
    s"the shared credential of owner '${subscription.ownerRef}' is being created by another subscription: try again"

  // the credential another subscription reserved, once it is written
  private def reservedOwnerCredential(clientId: String, attempts: Int)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Option[ApiKey]] =
    env.datastores.apiKeyDataStore.findById(clientId).flatMap {
      case None if attempts > 0 =>
        org.apache.pekko.pattern.after(100.millis, env.otoroshiScheduler)(
          reservedOwnerCredential(clientId, attempts - 1)
        )
      case found                => found.vfuture
    }

  private def severalOwnerCredentials(subscription: ApiSubscription): String =
    s"owner '${subscription.ownerRef}' has several shared credentials: name one with {\"apikey\": \"<client id>\", \"shared\": true}"

  private def createNewApikeyFromPlan(api: Api, plan: ApiPlan, subscription: ApiSubscription)(using
      env: Env
  ): Future[Either[String, ApiSubscription]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    withOwnerCredential(subscription).flatMap {
      case Left(error)             => error.leftf
      case Right((named, created)) =>
        reservingAlias(api, named)(mintApikeyFromPlan(api, plan, named)).flatMap {
          // the credential created for a subscription that is refused goes with it, and its reservation: nobody asked
          // for it, and the next subscription of the owner creates its own. Unless a subscription of that owner created
          // at once took it already (withOwnerCredential): it stays, or its alias would go with it
          case Left(error) if created.isDefined =>
            sharedByAnother(created.get).flatMap {
              case true  => error.leftf
              case false =>
                for {
                  _ <- env.datastores.apiKeyDataStore.delete(created.get)
                  _ <- env.datastores.rawDataStore.del(Seq(ownerCredentialReservation(named)))
                } yield error.left
            }
          case other                            => other.vfuture
        }
    }
  }

  // whether a subscription shares a credential, or is about to: an alias names it, or an api is reserved for it
  // (reservingAlias). A subscription that is refused releases its own reservation before it is told so: the ones that
  // are left are the ones of the other subscriptions. The aliases are read first: a subscription reserves the api
  // before it mints its alias
  def sharedByAnother(credential: String)(using env: Env, ec: ExecutionContext): Future[Boolean] =
    aliasesOf(credential).flatMap {
      case Seq() =>
        val reservations = aliasReservations(credential)
        // one pattern for every credential: the in memory datastore keeps every pattern it compiles
        env.datastores.rawDataStore.keys(s"$aliasReservationsPrefix*").map(_.exists(_.startsWith(reservations)))
      case _     => true.vfuture
    }

  // a credential is shared once per api (checkShareable), and two subscriptions sharing it on one api created at once
  // both find no alias there: the one that reserves the pair mints its alias, the other one is refused. The reservation
  // holds the id of its subscription, only has to outlive the time a subscription takes to be stored once its alias is
  // minted, and expires by itself. It goes with a subscription that is refused, and with the keys of the one that
  // made it (deleteKeys), so that the api is subscribed again at once
  private def aliasReservation(credential: String, api: String)(using env: Env): String =
    s"${aliasReservations(credential)}$api"

  // the prefix of the reservations of the apis for a credential, and the one of all of them
  private def aliasReservations(credential: String)(using env: Env): String = s"$aliasReservationsPrefix$credential:"

  private def aliasReservationsPrefix(using env: Env): String =
    s"${env.storageRoot}:shared-credential-reservations:aliases:"

  private def alreadyShared(credential: String, api: String): String =
    s"api key '$credential' is already shared with a subscription to api '$api'"

  private def reservingAlias(api: Api, subscription: ApiSubscription)(
      mint: => Future[Either[String, ApiSubscription]]
  )(using env: Env, ec: ExecutionContext): Future[Either[String, ApiSubscription]] =
    sharedCredential(subscription) match {
      case None             => mint
      case Some(credential) =>
        val reservation = aliasReservation(credential, api.id)
        def release()   = env.datastores.rawDataStore.del(Seq(reservation))
        env.datastores.rawDataStore
          .setnx(reservation, ByteString(subscription.id), Some(ownerCredentialReservationTtl.toMillis))
          .flatMap {
            case false =>
              s"api key '$credential' is being shared with another subscription to api '${api.id}': try again".leftf
            case true  =>
              // the subscription that held the pair before may be stored by now: the check of checkShareable, again
              mintedAliasesOf(credential).flatMap {
                case aliases if aliases.exists(_.apiRef.exists(_.api == api.id)) =>
                  release().map(_ => alreadyShared(credential, api.id).left)
                case _                                                           =>
                  mint.flatMap {
                    case left @ Left(_) => release().map(_ => left)
                    case right          => right.vfuture
                  }
              }
          }
    }

  // the reservation a subscription made of the api for the credential it shares, when it still holds it
  private def releaseAliasReservation(subscription: ApiSubscription)(using env: Env, ec: ExecutionContext): Future[Unit] =
    sharedCredential(subscription) match {
      case None             => ().vfuture
      case Some(credential) =>
        val reservation = aliasReservation(credential, subscription.apiRef)
        env.datastores.rawDataStore.get(reservation).flatMap {
          case Some(holder) if holder.utf8String == subscription.id =>
            env.datastores.rawDataStore.del(Seq(reservation)).map(_ => ())
          case _                                                    => ().vfuture
        }
    }

  private def mintApikeyFromPlan(api: Api, plan: ApiPlan, subscription: ApiSubscription)(using
                                                                                              env: Env
  ): Future[Either[String, ApiSubscription]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    val defaultApikey = generateNewApikeyFromPlan(api, plan, subscription)

    // a key of the token_refs that is not its own (ownsKey) is left alone and not listed: the subscription gets its
    // own key, and does not claim one that serves nothing on its api. A ref to a key that does not exist is kept, as
    // before
    val ownRefs: Future[Seq[JsValue]] = subscription.tokenRefs.flatmapAsync { ref =>
      ref.select("apikey").asOpt[String] match {
        case None                       => Seq(ref).vfuture
        // the credential the subscription shares, checked by check
        case Some(_) if isSharedRef(ref) => Seq(ref).vfuture
        case Some(clientId) =>
          env.datastores.apiKeyDataStore.findById(clientId).map {
            case Some(apikey) if !ownsKey(subscription, apikey) =>
              notOwnedWarning(subscription, apikey, "listed")
              Seq.empty
            case _                                               => Seq(ref)
          }
      }
    }

    ownRefs.flatMap { refs =>
      defaultApikey.save().flatMap {
        case false => "failed to create the apikey".leftf
        case true  =>
          val created = subscription.copy(tokenRefs = refs :+ Json.obj("apikey" -> defaultApikey.clientId))
          sharedCredential(subscription) match {
            case None             => created.rightf
            // a credential deleted while its alias was minted took none of it: the alias goes, and the subscription
            // is refused
            case Some(credential) =>
              env.datastores.apiKeyDataStore.findById(credential).flatMap {
                case Some(_) => created.rightf
                case None    =>
                  env.datastores.apiKeyDataStore
                    .delete(defaultApikey.clientId)
                    .map(_ => s"api key '$credential' not found".left)
              }
          }
      }
    }
  }

  // the key a subscription to a jwt, mtls or oauth2-remote plan provisions for its client_id: the very key the
  // extractor of the plan resolves for that consumer, so that the subscription decides for it, enabled or not. A key
  // the extractor minted on the fly before, or that a deleted subscription left disabled, is taken over. A key of
  // another api or another plan is not: the plan ids of two apis can be the same
  private def createConsumerApikey(api: Api, plan: ApiPlan, subscription: ApiSubscription)(using
      env: Env
  ): Future[Either[String, ApiSubscription]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    (plan.consumerClientIdPrefix(api.id), subscription.clientId) match {
      case (Some(prefix), Some(clientId)) =>
        val keyId = s"$prefix$clientId"
        val built = generateNewApikeyFromPlan(api, plan, subscription, Some(keyId))
        env.datastores.apiKeyDataStore.findById(keyId).flatMap {
          case Some(existing) if existing.apiRef.exists(ref => ref.api != api.id || ref.plan != plan.id) =>
            val ref = existing.apiRef.get
            s"api key '$keyId' belongs to plan '${ref.plan}' of api '${ref.api}'".leftf
          case existing                                                                                =>
            existing.map(e => rebuildApikey(e, built, api, plan, subscription)).getOrElse(built).save().map {
              case false => "failed to create the apikey".left
              case true  => subscription.copy(tokenRefs = Seq(Json.obj("apikey" -> keyId))).right
            }
        }
      // without client_id, nothing, as before
      case _                              => subscription.rightf
    }
  }

  // the key rebuilt from the plan keeps its id, its secret, and what was added to it outside of the plan and of the
  // subscription. The metadata of a consumer come last, so that nothing overrides them
  private def rebuildApikey(
      existing: ApiKey,
      built: ApiKey,
      api: Api,
      plan: ApiPlan,
      subscription: ApiSubscription
  ): ApiKey =
    withValidity(
      built.copy(
        clientId = existing.clientId,
        clientSecret = existing.clientSecret,
        metadata = built.metadata ++ removeManagedMetadata(existing.metadata) ++ consumerMetadata(api, plan, subscription),
        tags = built.tags ++ removeManagedTags(existing.metadata, existing.tags)
      ),
      Some(existing),
      plan.apikeyTemplate
    )

  private def removeManagedMetadata(currentApikeyMetadata: Map[String, String]): Map[String, String] = {
    val managed_keys: Seq[String] =
      currentApikeyMetadata.get(PLAN_METADATA_KEY).map(_.split(METADATA_AND_TAGS_SEPARATOR)).getOrElse(Array.empty[String]).toIndexedSeq ++
      currentApikeyMetadata
        .get(SUBSCRIPTION_METADATA_KEY)
        .map(_.split(METADATA_AND_TAGS_SEPARATOR))
        .getOrElse(Array.empty[String])

    currentApikeyMetadata.view.filterKeys(key => !managed_keys.contains(key) && !CORE_METADATA.contains(key)).toMap
  }

  private def removeManagedTags(
      currentApikeyMetadata: Map[String, String],
      currentApikeyTags: Seq[String]
  ): Seq[String] = {
    val managed_keys: Seq[String] =
      currentApikeyMetadata.get(PLAN_TAGS_KEY).map(_.split(METADATA_AND_TAGS_SEPARATOR)).getOrElse(Array.empty[String]).toIndexedSeq ++
      currentApikeyMetadata.get(SUBSCRIPTION_TAGS_KEY).map(_.split(METADATA_AND_TAGS_SEPARATOR)).getOrElse(Array.empty[String])

    currentApikeyTags.filter(key => !managed_keys.contains(key))
  }

  private def updateApikeyFromPlan(api: Api, plan: ApiPlan, subscription: ApiSubscription)(using
                                                                                           env: Env
  ): Future[Seq[Either[String, Boolean]]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    val apikeys: Seq[String] = subscription.tokenRefs.collect {
      case ref @ JsObject(values) if values.contains("apikey") && !isSharedRef(ref) => values("apikey").asString
    }

    // a shared credential deleted by hand took the alias of the subscription with it: there is no key left to rebuild,
    // and the subscription is still updated. Any other key that is gone refuses the update, as before
    val credentialGone: Future[Boolean] = sharedCredential(subscription) match {
      case Some(credential) => env.datastores.apiKeyDataStore.findById(credential).map(_.isEmpty)
      case None             => false.vfuture
    }

    for {
      found   <- Future.sequence(apikeys.map(apikeyId => env.datastores.apiKeyDataStore.findById(apikeyId).map(apikeyId -> _)))
      gone    <- credentialGone
      // a subscription mints one alias: the only key the deleted credential took. The alias is deleted, nothing tells
      // which of several missing keys it was, and none of them is excused then
      aliasGone = gone && found.count(_._2.isEmpty) == 1
      results <- Future.sequence(found.map {
                   // rebuilt from the plan of this subscription, the key would become its own: only its own keys are
                   case (_, Some(apikey)) if !ownsKey(subscription, apikey) =>
                     notOwnedWarning(subscription, apikey, "updated")
                     false.rightf
                   case (_, Some(apikey))                                    =>
                     rebuildApikey(
                       apikey,
                       generateNewApikeyFromPlan(api, plan, subscription, Some(apikey.clientId)),
                       api,
                       plan,
                       subscription
                     ).save()
                       .map(_.right)
                   case (_, None) if aliasGone                               => false.rightf
                   case (apikeyId, None)                                     => s"apikey with $apikeyId not found".leftf
                 })
    } yield results
  }

  def handleSubscriptionChanged(
                                 api: Api,
                                 plan: ApiPlan,
                                 subscription: ApiSubscription,
                                 action: WriteAction,
                                 isDraft: Boolean
  )(using
      env: Env
  ): Future[Either[String, ApiSubscription]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    // the consumers of an apikey plan and of an oauth2-local plan both call with an apikey, and a
    // subscription is the only way to get one. The consumers of a jwt, mtls or oauth2-remote plan are
    // built at call time by the extractor of the plan: a subscription with a client_id provisions the
    // key the extractor resolves for it. A keyless subscription has nothing to mint
    if (action == WriteAction.Create) {
      subscription.subscriptionKind match {
        case ApiKind.Apikey | ApiKind.OAuth2Local => createNewApikeyFromPlan(api, plan, subscription)
        case kind if isConsumerKind(kind)         => createConsumerApikey(api, plan, subscription)
        case _                                    => subscription.rightf
      }
    } else if (action == Update) {
      subscription.subscriptionKind match {
        case _ if holdsKeys(subscription) && plan.status == ApiPlanStatus.Closed =>
          // Cascade-delete the subscription AND the apikeys it points at.
          // Otherwise the apikey lingers in the datastore unreachable from
          // its owning subscription. A subscription whose keys could not be
          // deleted is kept.
          deleteKeys(subscription).flatMap {
            case Left(err) => err.leftf
            case Right(_)  =>
              val deleteSubscription =
                if (isDraft) env.datastores.draftsDataStore.delete(subscription.id)
                else env.datastores.apiSubscriptionDataStore.delete(subscription.id)
              deleteSubscription.map(_ => subscription.right)
          }
        case _ if holdsKeys(subscription)                                        =>
          updateApikeyFromPlan(api, plan, subscription)
            .map(results =>
              results.collectFirst { case Left(err) => err } match {
                case Some(err) => Left(err)
                case None      => subscription.right
              }
            )
        case _                                                                   => subscription.rightf
      }
    } else {
      subscription.rightf
    }
  }

  private def findDraft[A](id: String, fmt: Reads[A])(using env: Env): Future[Option[A]] =
    env.proxyState
      .allDrafts()
      .find(_.id == id)
      .flatMap(draft => fmt.reads(draft.content).asOpt)
      .future

  private def findApi(apiId: String, isDraft: Boolean)(using env: Env, ec: ExecutionContext): Future[Option[Api]] = {
    if (isDraft) findDraft(apiId, Api.format)
    else env.datastores.apiDataStore.findById(apiId)
  }

  def validate(apiRef: String, entity: ApiSubscription, action: WriteAction, isDraft: Boolean)(using
      env: Env
  ): Future[Either[String, ApiSubscription]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    check(apiRef, entity, action, isDraft).flatMap {
      case Left(error)        => error.leftf
      case Right((api, plan)) => handleSubscriptionChanged(api, plan, entity, action, isDraft)
    }
  }

  // the checks of validate, without the changes handleSubscriptionChanged applies to the apikeys
  def check(apiRef: String, entity: ApiSubscription, action: WriteAction, isDraft: Boolean)(using
      env: Env
  ): Future[Either[String, (Api, ApiPlan)]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    findApi(apiRef, isDraft)
      .map {
        case Some(api) if api.state == ApiStaging || api.state == ApiPublished =>
          api.plans.find(_.id == entity.planRef) match {
            case None => Left("plan not found")
            // a subscription only gets what its plan hands over, so it has to be of the kind of the
            // plan. Only checked on creation, so that the subscriptions of a plan whose kind changed
            // afterwards can still be managed.
            case Some(plan)
                if action == WriteAction.Create && entity.subscriptionKind.name != plan.accessModeConfigurationType =>
              Left(
                s"subscription kind '${entity.subscriptionKind.name}' does not match the access mode '${plan.accessModeConfigurationType}' of plan '${plan.id}'"
              )
            // Active plans (Staging/Published): Create + Update both go.
            // Inactive plans (Deprecated/Closed): only Update goes — required
            // so existing subs on a deprecated plan can still be managed, and
            // the Closed cascade-delete (in handleSubscriptionChanged) fires.
            case Some(plan)
                if plan.status == ApiPlanStatus.Staging ||
                  plan.status == ApiPlanStatus.Published ||
                  action == WriteAction.Update =>
              Right((api, plan))
            case _    => Left("wrong status plan")
          }
        case _                                                                 => Left("wrong status api")
      }
      .flatMap {
        case Right((api, plan)) if isConsumerKind(entity.subscriptionKind) && !isDraft =>
          checkClientId(entity, action).map(_.map(_ => (api, plan)))
        case other                                                                      => other.vfuture
      }
      .flatMap {
        case Right((api, plan)) => checkSharedCredential(api, entity, action, isDraft).map(_.map(_ => (api, plan)))
        case other              => other.vfuture
      }
  }

  // an apikey or oauth2-local subscription shares one credential at most: a shared credential of its owner
  // (ApiKey.sharedCredential), that has no alias on its api yet. It is chosen at the creation and never changes, so
  // that an alias key never changes its credential, and the subscription keeps its api and its owner, the ones the
  // credential was checked for. The rights of the author on the credential are checked by the admin api
  // (ApiSubscriptionsResourceAccess.grantsMoreThan) and by subscribe
  private def checkSharedCredential(api: Api, entity: ApiSubscription, action: WriteAction, isDraft: Boolean)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Either[String, Unit]] = {
    val shared        = sharedCredentials(entity)
    val ofOwner       = entity.tokenRefs.count(isOwnerCredentialRef)
    val sharesNothing = shared.isEmpty && ofOwner == 0
    action match {
      // the keys of a draft are real keys, authorized on the api the draft shares its id with: a draft would open the
      // live api to the credential. A credential is shared by the subscription itself
      case _ if isDraft                                              =>
        if (sharesNothing) Right(()).vfuture
        else Left("the draft of a subscription cannot share an api key: share it with the subscription").vfuture
      case WriteAction.Create if sharesNothing                       => Right(()).vfuture
      case _ if shared.size + ofOwner > 1                            => Left("a subscription shares one api key at most").vfuture
      case _ if !sharesNothing && !sharesCredentials(entity.subscriptionKind) =>
        Left(s"a subscription of kind '${entity.subscriptionKind.name}' cannot share an api key").vfuture
      case WriteAction.Update                                        =>
        env.datastores.apiSubscriptionDataStore.findById(entity.id).map {
          // the stored subscription names its credential, {"shared": true} would be another one
          case Some(stored) if ofOwner > 0 || sharedCredential(stored) != shared.headOption                              =>
            Left("the api key a subscription shares cannot change: delete the subscription and create another one")
          case Some(stored) if shared.nonEmpty && (stored.apiRef != entity.apiRef || stored.ownerRef != entity.ownerRef) =>
            Left(
              "the api and the owner of a subscription that shares an api key cannot change: delete the subscription and create another one"
            )
          case _                                                                                                           => Right(())
        }
      case WriteAction.Create if ofOwner > 0                         =>
        env.datastores.apiKeyDataStore.findAll(force = true).flatMap { apikeys =>
          ownerCredentials(apikeys, entity) match {
            // created with the subscription
            case Seq()           => Right(()).vfuture
            case Seq(credential) => checkShareable(api, entity, credential.clientId)
            case _               => Left(severalOwnerCredentials(entity)).vfuture
          }
        }
      case WriteAction.Create                                        => checkShareable(api, entity, shared.head)
    }
  }

  // only a shared credential is shared, never the api key of a subscription: by the subscriptions of its owner, once
  // per api
  private def checkShareable(api: Api, entity: ApiSubscription, credentialId: String)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Either[String, Unit]] = {
    env.datastores.apiKeyDataStore.findById(credentialId).flatMap {
      case None                                                     => Left(s"api key '$credentialId' not found").vfuture
      case Some(credential) if credential.sharedCredential.isEmpty =>
        Left(
          s"api key '$credentialId' is not a shared credential: only an api key created as one, attached to nothing, is shared"
        ).vfuture
      case Some(credential) if !credential.sharedCredential.exists(_.owner == entity.ownerRef) =>
        Left(s"api key '$credentialId' belongs to another owner").vfuture
      case Some(_)                                                  =>
        // the aliases their subscription minted: one written by hand serves nothing, and refuses nothing
        mintedAliasesOf(credentialId).map {
          case aliases if aliases.exists(_.apiRef.exists(_.api == api.id)) => Left(alreadyShared(credentialId, api.id))
          case _                                                           => Right(())
        }
    }
  }

  // the client_id of a subscription to a jwt, mtls or oauth2-remote plan names the key of one consumer of the plan: no
  // two subscriptions of a plan share it, and it never changes, so that no previous key has to be kept disabled
  private def checkClientId(entity: ApiSubscription, action: WriteAction)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Either[String, Unit]] = action match {
    case WriteAction.Create =>
      entity.clientId match {
        case None           => Right(()).vfuture
        case Some(clientId) =>
          env.datastores.apiSubscriptionDataStore.findAll().map { subscriptions =>
            if (
              subscriptions.exists(sub =>
                sub.id != entity.id && sub.apiRef == entity.apiRef && sub.planRef == entity.planRef &&
                sub.clientId.contains(clientId)
              )
            ) Left(s"client_id '$clientId' already subscribed to plan '${entity.planRef}'")
            else Right(())
          }
      }
    case WriteAction.Update =>
      env.datastores.apiSubscriptionDataStore.findById(entity.id).map {
        case Some(stored) if stored.clientId != entity.clientId =>
          Left("client_id cannot change: delete the subscription and create another one")
        case _                                                  => Right(())
      }
  }

  def writeValidator(
      entity: ApiSubscription,
      body: JsValue,
      oldEntity: Option[(ApiSubscription, JsValue)],
      singularName: String,
      id: Option[String],
      action: WriteAction,
      env: Env
  ): Future[Either[JsValue, ApiSubscription]] = {

    implicit val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext
    implicit val e: otoroshi.env.Env = env
    def onError(error: String): Either[JsValue, ApiSubscription] = Json
      .obj(
        "error"            -> error,
        "http_status_code" -> 400
      )
      .left

    validate(entity.apiRef, entity, action, isDraft = false)
      .map {
        case Left(error) => onError(error)
        case Right(r)    => Right(r)
      }
  }

  def writeChecker(
      entity: ApiSubscription,
      body: JsValue,
      oldEntity: Option[(ApiSubscription, JsValue)],
      singularName: String,
      id: Option[String],
      action: WriteAction,
      env: Env
  ): Future[Either[JsValue, ApiSubscription]] = {

    implicit val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext
    implicit val e: otoroshi.env.Env = env

    check(entity.apiRef, entity, action, isDraft = false)
      .map {
        case Left(error) => Json.obj("error" -> error, "http_status_code" -> 400).left
        case Right(_)    => Right(entity)
      }
  }

  def deleteValidator(
      subscription: ApiSubscription,
      body: JsValue,
      singularName: String,
      id: String,
      action: DeleteAction,
      env: Env
  ): Future[Either[JsValue, Unit]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    implicit val e: Env               = env

    // the api keys of the subscription go with it: a subscription whose keys could not be revoked is kept, so that
    // it never disappears while its keys still give access
    revokeKeys(subscription).map {
      case Left(error) => Left(Json.obj("error" -> error, "http_status_code" -> 500))
      case Right(_)    => Right(())
    }
  }

  // what deleteMany removes without any validation (remote catalogs, kubernetes jobs) loses its api keys too: a
  // subscription whose keys could not be deleted fails the whole deletion, and nothing is deleted
  def beforeDeleteMany(subscriptions: Seq[ApiSubscription], env: Env): Future[Unit] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    implicit val e: Env               = env

    subscriptions.mapAsync(sub => revokeKeys(sub)).flatMap { results =>
      results.collect { case Left(error) => error } match {
        case Seq()  => ().vfuture
        case errors => Future.failed(new RuntimeException(errors.mkString(", ")))
      }
    }
  }

  def fromJsons(value: JsValue): ApiSubscription =
    try {
      format.reads(value).get
    } catch {
      case e: Throwable => throw e
    }

  val format: Format[ApiSubscription] = new Format[ApiSubscription] {
    override def reads(json: JsValue): JsResult[ApiSubscription] = Try {
      ApiSubscription(
        // under _loc like every other entity: the admin api checks the rights on that key
        location = EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asString,
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        status = (json \ "status").asOptString
          .map {
            case "enabled"    => ApiSubscriptionEnabled
            case "disabled"   => ApiSubscriptionDisabled
            case "deprecated" => ApiSubscriptionDeprecated
            case "pending"    => ApiSubscriptionPending
            case value        => ApiSubscriptionCustom(value)
          }
          .getOrElse(ApiSubscriptionDisabled),
        dates = json.select("dates").as(using ApiSubscriptionDates._fmt),
        ownerRef = json.selectAsString("owner_ref"),
        planRef = json.select("plan_ref").asString,
        paymentRef = json.selectAsOptObject("payment_ref").getOrElse(Json.obj()),
        subscriptionKind = json.select("subscription_kind").asString.toLowerCase match {
          case "apikey"        => ApiKind.Apikey
          case "mtls"          => ApiKind.Mtls
          case "keyless"       => ApiKind.Keyless
          case "oauth2-local"  => ApiKind.OAuth2Local
          case "oauth2-remote" => ApiKind.OAuth2Remote
          case "jwt"           => ApiKind.JWT
        },
        apiRef = json.select("api_ref").asString,
        tokenRefs = json.select("token_refs").asOpt[Seq[JsValue]].getOrElse(Seq.empty).toSeq,
        clientId = json.select("client_id").asOpt[String].filter(_.trim.nonEmpty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: ApiSubscription): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"                -> o.id,
      "name"              -> o.name,
      "description"       -> o.description,
      "tags"              -> o.tags,
      "metadata"          -> o.metadata,
      "status"            -> o.status.name,
      "dates"             -> ApiSubscriptionDates._fmt.writes(o.dates),
      "owner_ref"         -> o.ownerRef,
      "plan_ref"          -> o.planRef,
      "payment_ref"       -> o.paymentRef,
      "api_ref"           -> o.apiRef,
      "subscription_kind" -> o.subscriptionKind.name,
      "token_refs"        -> o.tokenRefs,
      "client_id"         -> o.clientId
    )
  }
}

trait ApiKind  {
  def name: String
}
object ApiKind {
  case object Apikey       extends ApiKind {
    override def name: String = "apikey"
  }
  case object Mtls         extends ApiKind {
    override def name: String = "mtls"
  }
  case object Keyless      extends ApiKind {
    override def name: String = "keyless"
  }
  case object OAuth2Local  extends ApiKind {
    override def name: String = "oauth2-local"
  }
  case object OAuth2Remote extends ApiKind {
    override def name: String = "oauth2-remote"
  }
  case object JWT          extends ApiKind {
    override def name: String = "jwt"
  }
}

object ApiPlan {
  val RouteScope    = "route"
  val ConsumerScope = "consumer"
  // the first part of the client ids of the consumers of a plan, by access mode
  def consumerClientIdKind(accessMode: String): Option[String] = accessMode match {
    case "keyless"       => Some("keyless")
    case "jwt"           => Some("jwt")
    case "mtls"          => Some("mtls")
    case "oauth2-remote" => Some("oauth2")
    case _               => None
  }
}

// what a plan does when the counter of its quotas cannot be reached: deny the call with a 503 (the default), or let it
// through uncounted. With a timeout, a counter that has not answered by then is a failure
case class ApiPlanCounterFailure(allow: Boolean = false, timeout: Option[Long] = None)

object ApiPlanCounterFailure {
  def read(json: JsValue): ApiPlanCounterFailure = ApiPlanCounterFailure(
    allow = json.select("mode").asOpt[String].map(_.trim.toLowerCase).contains("allow"),
    timeout = json.select("timeout").asOpt[Long].filter(_ > 0)
  )
}

trait ApiPlanStatus  {
  def name: String
  def orderPosition: Int
}
object ApiPlanStatus {
  case object Staging    extends ApiPlanStatus {
    override def name: String       = "staging"
    override def orderPosition: Int = 1
  }
  case object Published  extends ApiPlanStatus {
    override def name: String       = "published"
    override def orderPosition: Int = 2
  }
  case object Deprecated extends ApiPlanStatus {
    override def name: String       = "deprecated"
    override def orderPosition: Int = 3
  }
  case object Closed     extends ApiPlanStatus {
    override def name: String       = "closed"
    override def orderPosition: Int = 4
  }

  // a served plan puts its extractor on the routes of its api and its consumers are enforced: a published plan, and a
  // deprecated one, which takes no new subscription but keeps its consumers. A staging or a closed plan is not served
  def isServed(status: ApiPlanStatus): Boolean = status == Published || status == Deprecated
}

case class ApiBackend(id: String, name: String, backend: NgBackend, client: String)

object ApiBackend {
  def empty(using env: Env): ApiBackend = ApiBackend(
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
    ),
    client = "default_backend_client"
  )

  val _fmt: Format[ApiBackend] = new Format[ApiBackend] {
    override def reads(json: JsValue): JsResult[ApiBackend] = Try {
      ApiBackend(
        id = json.select("id").asString,
        name = json.select("name").asString,
        backend = json.select("backend").as(using NgBackend.fmt),
        client = json.select("client").asOpt[String].getOrElse("default_backend_client")
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
        "backend" -> NgBackend.fmt.writes(o.backend),
        "client"  -> o.client
      )
    }
  }
}
case class ApiBackendClient(id: String, name: String, client: NgClientConfig)

object ApiBackendClient {

  val defaultClient = ApiBackendClient(
    id = "default_backend_client",
    name = "default_backend_client",
    client = NgClientConfig.default
  )

  val _fmt: Format[ApiBackendClient] = new Format[ApiBackendClient] {

    override def reads(json: JsValue): JsResult[ApiBackendClient] = Try {
      ApiBackendClient(
        id = json.select("id").asString,
        name = json.select("name").asString,
        client = json.select("client").asOpt(using NgClientConfig.format).getOrElse(NgClientConfig.default)
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiBackendClient): JsValue = Json.obj(
      "id"     -> o.id,
      "name"   -> o.name,
      "client" -> o.client.json
    )
  }
}

case class ApiTesting(
    enabled: Boolean = false,
    headerKey: String = "X-OTOROSHI-TESTING",
    headerValue: String = IdGenerator.uuid
)

object ApiTesting {

  val default = ApiTesting(
    headerValue = IdGenerator.uuid
  )

  val _fmt: Format[ApiTesting] = new Format[ApiTesting] {
    override def writes(o: ApiTesting): JsValue             = Json.obj(
      "enabled"     -> o.enabled,
      "headerKey"   -> o.headerKey,
      "headerValue" -> o.headerValue
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

case class UserRef(ref: String, config: JsObject) {
  def json: JsValue = UserRef.format.writes(this)
}
object UserRef                                    {
  val empty  = UserRef("owner", Json.obj())
  val format = new Format[UserRef] {
    override def writes(o: UserRef): JsValue             = Json.obj(
      "ref"    -> o.ref,
      "config" -> o.config
    )
    override def reads(json: JsValue): JsResult[UserRef] = Try {
      UserRef(
        ref = json.select("ref").asString,
        config = json.select("config").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

sealed trait ApiVisibilityKind {
  def name: String
  def json: JsValue = name.json
}
object ApiVisibilityKind       {

  case object Public     extends ApiVisibilityKind { def name: String = "public"      }
  case object SemiPublic extends ApiVisibilityKind { def name: String = "semi_public" }
  case object Private    extends ApiVisibilityKind { def name: String = "private"     }
  case object Custom     extends ApiVisibilityKind { def name: String = "custom"      }

  def apply(str: String): ApiVisibilityKind = str match {
    case "public"      => Public
    case "semi_public" => SemiPublic
    case "private"     => Private
    case "custom"      => Custom
    case _             => Private
  }
}

case class ApiVisibility(kind: ApiVisibilityKind, config: JsObject = Json.obj()) {
  def json: JsValue = ApiVisibility.format.writes(this)
}

object ApiVisibility {
  val Public: ApiVisibility = ApiVisibility(ApiVisibilityKind.Public)
  val format                = new Format[ApiVisibility] {
    override def reads(json: JsValue): JsResult[ApiVisibility] = Try {
      ApiVisibility(
        kind = ApiVisibilityKind(json.select("kind").asOptString.getOrElse("public")),
        config = json.select("config").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: ApiVisibility): JsValue             = Json.obj(
      "kind"   -> o.kind.json,
      "config" -> o.config
    )
  }
}

case class ApiStateHook(ref: String, config: JsObject) {
  def json: JsValue = ApiStateHook.format.writes(this)
}

object ApiStateHook {
  val format = new Format[ApiStateHook] {
    override def reads(json: JsValue): JsResult[ApiStateHook] = Try {
      ApiStateHook(
        ref = json.select("ref").asOptString.getOrElse(""),
        config = json.select("config").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: ApiStateHook): JsValue             = Json.obj(
      "ref"    -> o.ref,
      "config" -> o.config
    )
  }
}

case class Api(
                kind: String = "Api",
                location: EntityLocation,
                id: String,
                name: String,
                description: String,
                domain: String,
                contextPath: String,
                tags: Seq[String] = Seq.empty,
                metadata: Map[String, String] = Map.empty,
                owner: UserRef = UserRef.empty,
                members: Seq[UserRef] = Seq.empty,
                visibility: ApiVisibility = ApiVisibility.Public,
                version: String,
                versions: Seq[String] = Seq("0.0.1"),
                debugFlow: Boolean,
                capture: Boolean,
                exportReporting: Boolean,
                groups: Seq[String],
                state: ApiState,
                enabled: Boolean = true,
                blueprint: ApiBlueprint,
                routes: Seq[ApiRoute] = Seq.empty,
                backends: Seq[ApiBackend] = Seq.empty,
                plans: Seq[ApiPlan] = Seq.empty,
                flows: Seq[ApiFlows] = Seq.empty,
                clientsBackendConfig: Seq[ApiBackendClient] = Seq.empty,
                documentation: Option[ApiDocumentation] = None,
                deployments: Seq[ApiDeployment] = Seq.empty,
                clients: Seq[ApiClient] = Seq.empty,
                testing: ApiTesting,
                hooks: Seq[ApiStateHook] = Seq.empty
                // TODO: monitoring and heath ????
) extends EntityLocationSupport {
  override def internalId: String = id

  override def json: JsValue = Api.format.writes(this)

  override def theName: String = name

  override def theDescription: String = description

  override def theTags: Seq[String] = tags

  override def theMetadata: Map[String, String] = metadata

  def save()(using ec: ExecutionContext, env: Env) = env.datastores.apiDataStore.set(this)

  // the plugin chain a plan contributes to a call never changes for a given api, but ApiKey.pluginFlow
  // needs it on every request that carries an apiRef. it is computed once per api instance here,
  // instead of being rebuilt per call: the proxy state rebuilds those instances when the api changes,
  // so this stays in sync while costing a single map lookup on the hot path. plans without plugins
  // are left out, they behave like no plan at all.
  lazy val computedPluginsByPlan: Map[String, NgPluginsWithOverride] =
    plans.collect { case plan if plan.hasPlugins => (plan.id, plan.computedPlugins(this)) }.toMap

  // what each plan does when the counter of its quotas cannot be reached, read by the consumer enforcer on every call
  lazy val counterFailureByPlan: Map[String, ApiPlanCounterFailure] = plans.map(plan => (plan.id, plan.counterFailure)).toMap

  // the plans whose consumers get a circuit breaker of their own
  lazy val consumerScopedBreakerPlans: Set[String] =
    plans.filter(_.circuitBreakerScope == ApiPlan.ConsumerScope).map(_.id).toSet

  def resolveDocumentation()(using env: Env, ec: ExecutionContext): Future[Option[ApiDocumentation]] = {
    documentation.flatMap(_.source) match {
      case None         => documentation.vfuture
      case Some(source) => source.resolve(documentation.get)
    }
  }

  private def addSecurityOnDrafRoute(route: ApiRoute, api: Api) = {
    route.copy(
      id = s"testing_route_${route.id}",
      frontend = route.frontend
        .copy(
          headers = route.frontend.headers + (api.testing.headerKey -> api.testing.headerValue)
        )
    )
  }

  private def replaceBackendClientIdByRealBackend(backend: ApiBackend, api: Api): NgBackend = {
    backend.backend.copy(
      client = api.clientsBackendConfig
        .find(_.id == backend.client)
        .map(_.client)
        .getOrElse(NgClientConfig.default)
    )
  }

  private def buildDraftRoutes()(using env: Env): Future[Seq[RouteWithApi]] = {
    implicit val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext

    env.datastores.draftsDataStore
      .findById(id)
      .map {
        case Some(draft) => Api.format.reads(draft.content)
        case None        => JsError("draft not found")
      }
      .flatMap { draft =>
        val optApi = draft.asOpt
          .filter(api => api.enabled && api.testing.enabled)
          .map { draftApi =>
            draftApi
              .copy(
                backends = draftApi.backends
                  .map(backend => backend.copy(backend = replaceBackendClientIdByRealBackend(backend, draftApi))),
                routes = draftApi.routes.map(route => addSecurityOnDrafRoute(route, draftApi))
              )
          }

        optApi match {
          case Some(api) =>
            Future
              .sequence(api.routes.map(route => routeToNgRoute(route, api.some)))
              .map(routes => routes.flatten.map(route => RouteWithApi(route, api)))
          case None      => Seq.empty.future
        }
      }
  }

  case class RouteWithApi(route: NgRoute, api: Api)

  private def addPluginsToFlow(
      plugins: NgPlugins,
      pluginsWithConfig: Seq[PluginWithConfig] = Seq.empty
  ): NgPlugins = {
    pluginsWithConfig.foldLeft(plugins) { case (acc, pluginWithConfig: PluginWithConfig) =>
      acc.add(
        NgPluginInstance(
          plugin = pluginWithConfig.pluginId,
          include = Seq.empty,
          exclude = Seq.empty,
          config = NgPluginInstanceConfig(pluginWithConfig.config.asObject),
          pluginIndex = pluginWithConfig.pluginIndex
        )
      )
    }
  }

  case class PluginWithConfig(pluginId: String, config: JsValue = Json.obj(), pluginIndex: Option[PluginIndex] = None)

  private def pluginsForPlan(plan: ApiPlan, api: Api): Seq[PluginWithConfig] = {
    plan.accessModeConfigurationType match {
      case "keyless"                 =>
        val keylessConfig = plan.accessModeConfiguration
          .map(_.asInstanceOf[KeylessAccessModeConfiguration])
          .getOrElse(KeylessAccessModeConfiguration())
        Seq(
          PluginWithConfig(
            pluginId[NgExpressionApikeyExtractor],
            // a keyless access still gets an identity, so that the quotas and throttling of the plan
            // can be enforced per caller without asking for any credential
            NgExpressionApikeyExtractorConfig(
              expression = keylessConfig.expr.getOrElse("${req.ip}"),
              clientIdPrefix = plan.consumerClientIdPrefix(api.id).get,
              strict = false,
              createIfMissing = keylessConfig.createIfMissing,
              apiId = api.id.some,
              planId = plan.id.some,
              throttlingStrategy = plan.rateLimiting,
              apikey = plan.apikeyTemplate,
              selection = plan.selection
            ).json,
            // a public plan always resolves an identity, so it is the last resort: it has to run
            // after every credential based extractor, and still before the consumer enforcer at 1000
            pluginIndex = PluginIndex(
              validateAccess = 900.00.some
            ).some
          )
        )
      // case "oauth2-local"  => Seq(PluginWithConfig(pluginId[ApikeyCalls]))
      case "apikey" | "oauth2-local" =>
        Seq(
          PluginWithConfig(
            pluginId[ApikeyCalls],
            //plan.accessModeConfiguration
            //  .map(_.asInstanceOf[ApikeyAccessModeConfiguration].json.asObject)
            //  .getOrElse(Json.obj())
            //  .deepMerge(
                NgApikeyCallsConfig(
                  // identification and early rejection only: NgApiConsumerEnforcer is the one
                  // counting the call, once the whole access validation of the route has passed
                  updateQuotas = false,
                  mandatory = false,
                  // extractors = NgApikeyExtractors(
                  //   otoBearer = NgApikeyExtractorOtoBearer(enabled = true),
                  //   basic = NgApikeyExtractorBasic(enabled = false),
                  //   customHeaders = NgApikeyExtractorCustomHeaders(enabled = false),
                  //   clientId = NgApikeyExtractorClientId(enabled = false),
                  //   jwt = NgApikeyExtractorJwt(enabled = true)
                  // )
                ).json.asObject ++ Json.obj(ApikeyFromPlan.SendEventWhenUnidentifiedKey -> false),
            //  ),
            pluginIndex = PluginIndex(
              validateAccess = 2.00.some
            ).some
          )
        )
      case "jwt"           =>
        val jwtConfig = plan.accessModeConfiguration
          .map(_.asInstanceOf[JWTAccessModeConfiguration])
          .getOrElse(JWTAccessModeConfiguration())
        Seq(
          PluginWithConfig(
            pluginId[NgJwtApikeyExtractor],
            NgJwtApikeyExtractorConfig(
              verifier = jwtConfig.verifier.getOrElse(""),
              clientIdPath = jwtConfig.clientIdPath.getOrElse("client_id"),
              clientIdPrefix = plan.consumerClientIdPrefix(api.id).get,
              strict = false,
              createIfMissing = jwtConfig.createIfMissing,
              subscriptionRequired = jwtConfig.subscriptionRequired,
              // an apikey minted from a token has to carry the very same quotas, restrictions and
              // rotation as one obtained through a subscription on that plan
              apiId = api.id.some,
              planId = plan.id.some,
              throttlingStrategy = plan.rateLimiting,
              apikey = plan.apikeyTemplate,
              selection = plan.selection
            ).json.asObject ++ Json.obj(ApikeyFromPlan.SendEventWhenUnidentifiedKey -> false),
            pluginIndex = PluginIndex(
              validateAccess = 2.01.some
            ).some
          )
        )
      case "mtls"          =>
        val mtlsConfig = plan.accessModeConfiguration
          .map(_.asInstanceOf[MtlsAccessModeConfiguration])
          .getOrElse(MtlsAccessModeConfiguration())
        Seq(
          PluginWithConfig(
            pluginId[NgClientCertApikeyExtractor],
            NgClientCertApikeyExtractorConfig(
              regexSubjectDNs = mtlsConfig.regexSubjectDNs,
              regexIssuerDNs = mtlsConfig.regexIssuerDNs,
              dnMatch = mtlsConfig.dnMatch,
              trustedCaRefs = mtlsConfig.trustedCaRefs,
              clientIdField = mtlsConfig.clientIdField,
              clientIdPrefix = plan.consumerClientIdPrefix(api.id).get,
              strict = false,
              createIfMissing = mtlsConfig.createIfMissing,
              subscriptionRequired = mtlsConfig.subscriptionRequired,
              apiId = api.id.some,
              planId = plan.id.some,
              throttlingStrategy = plan.rateLimiting,
              apikey = plan.apikeyTemplate,
              selection = plan.selection
            ).json,
            pluginIndex = PluginIndex(
              validateAccess = 2.02.some
            ).some
          )
        )
      case "oauth2-remote" =>
        val oidcConfig = plan.accessModeConfiguration
          .map(_.asInstanceOf[OAuth2RemoteAccessModeConfiguration])
          .getOrElse(OAuth2RemoteAccessModeConfiguration())
        Seq(
          PluginWithConfig(
            pluginId[NgOidcApikeyExtractor],
            NgOidcApikeyExtractorConfig(
              ref = oidcConfig.verifier,
              clientIdPath = oidcConfig.clientIdPath.getOrElse("client_id"),
              clientIdPrefix = plan.consumerClientIdPrefix(api.id).get,
              fetchUser = oidcConfig.fetchUser,
              userMetadataKey = oidcConfig.userMetadataKey,
              strict = false,
              createIfMissing = oidcConfig.createIfMissing,
              subscriptionRequired = oidcConfig.subscriptionRequired,
              apiId = api.id.some,
              planId = plan.id.some,
              throttlingStrategy = plan.rateLimiting,
              apikey = plan.apikeyTemplate,
              selection = plan.selection,
              issuer = oidcConfig.issuer,
              audiences = oidcConfig.audiences
            ).json.asObject ++ Json.obj(ApikeyFromPlan.SendEventWhenUnidentifiedKey -> false),
            pluginIndex = PluginIndex(
              validateAccess = 2.03.some
            ).some
          )
        )
      case _               => Seq.empty
    }
  }

  // several plans of an api can contribute the very same plugin with the very same configuration:
  // two apikey plans, or an apikey plan next to an oauth2-local one, both yield an identical
  // ApikeyCalls. Keeping only the first of each (plugin, config) pair collapses those without ever
  // dropping a plan that genuinely configures the plugin differently.
  private def dedupePlugins(plugins: Seq[PluginWithConfig]): Seq[PluginWithConfig] = {
    plugins.foldLeft(Seq.empty[PluginWithConfig]) { case (acc, plugin) =>
      if (acc.exists(p => p.pluginId == plugin.pluginId && p.config == plugin.config)) acc
      else acc :+ plugin
    }
  }

  // live: a route of the live api always ends with the consumer enforcer, so that an api without a served plan does not
  // answer every caller. The draft routes of the testing mode, only reachable with the testing header and whose plans
  // are usually not published yet, only get it from a served plan
  private def applyPlansPolicies(routeWithApi: RouteWithApi, live: Boolean): NgRoute = {
    val routeApiPlans = routeWithApi.api.plans
    // published and deprecated plans: a deprecated plan takes no new subscription, but its consumers keep calling
    val servedPlans   = routeApiPlans.filter(_.isServed)
    val route         = routeWithApi.route.copy(
      plugins = addPluginsToFlow(
        routeWithApi.route.plugins,
        dedupePlugins(servedPlans.flatMap(plan => pluginsForPlan(plan, routeWithApi.api)))
      )
    )

    if (servedPlans.nonEmpty || live) {
      route.copy(
        plugins = addPluginsToFlow(
          route.plugins,
          Seq(
            // at the end of the access validation (index 1000, after the extractors of the plans and the
            // indexed plugins of the flows, the ones without index run after it): it requires a consumer and
            // is the only place where the call is counted.
            // TODO: check that the apikey really belongs to this api. every plan kind namespaces the
            // identities it mints now, so an apikey reaching a route it does not belong to can be
            // turned away rather than served under the settings of another plan.
            PluginWithConfig(
              pluginId[NgApiConsumerEnforcer],
              // without a served plan, only the callers a plugin of the flows of the api identified go through
              if (servedPlans.nonEmpty) Json.obj() else Json.obj("no_published_plan" -> true),
              Some(PluginIndex(validateAccess = 1000.00.some)) // still valid
            ),
            PluginWithConfig(
              pluginId[otoroshi.next.plugins.SendOtoroshiHeadersBack],
              Json.obj(),
            ),
          )
        )
      )
    } else {
      route
    }

  }

  def toRoutes(using env: Env): Future[Seq[NgRoute]] = {
    implicit val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext

    val isRemovedOrDisabled = state == ApiRemoved || !enabled

    if (isRemovedOrDisabled) {
      Seq.empty.vfuture
    } else {
      for {
        draftRoutes  <- buildDraftRoutes()
        routeFutures <- Future
                          .sequence(
                            routes
                              .filter(_ => (state == ApiPublished || state == ApiDeprecated) && enabled)
                              .map { route => routeToNgRoute(route, this.some) }
                          )
                          .map { routes => routes.flatten.map(route => RouteWithApi(route, this)) }
      } yield {
        val apisWithPlanPolicies =
          routeFutures.map(applyPlansPolicies(_, live = true)) ++ draftRoutes.map(applyPlansPolicies(_, live = false))

//        apisWithPlanPolicies.foreach(route =>
//          println(route.frontend.domains, route.plugins.slots.map(p => (p.plugin, p.config)))
//        )

        apisWithPlanPolicies
      }
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

  def routeToNgRoute(apiRoute: ApiRoute, optApi: Option[Api] = None)(using env: Env): Future[Option[NgRoute]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    val api: Api = optApi.map(api => api).getOrElse(this)

    for {
      globalBackendEntity <- env.datastores.backendsDataStore.findById(apiRoute.backend)
      apiBackend          <- api.backends.find(_.id == apiRoute.backend).vfuture
    } yield {
      globalBackendEntity
        .map(_.backend)
        .orElse(
          apiBackend.map(back =>
            back.backend
              .copy(client =
                api.clientsBackendConfig.find(_.id == back.client).map(_.client).getOrElse(NgClientConfig.default)
              )
          )
        )
        .map(backend =>
          NgRoute(
            location = location,
            id = apiRoute.id,
            name = apiRoute.name.getOrElse("route") + " - " + apiRoute.frontend.methods
              .mkString(", ") + " - " + apiRoute.frontend.domains.map(_.path).mkString(", "),
            description = description,
            tags = tags,
            metadata = metadata ++ Map(RouteApiRef.metadataKey -> id),
            apiRef = RouteApiRef(id).some,
            enabled = apiRoute.enabled,
            capture = capture,
            debugFlow = debugFlow,
            exportReporting = exportReporting,
            groups = Seq("virtual_group_for_" + id) ++ groups,
            frontend = apiRoute.frontend.copy(
              domains = apiRoute.frontend.domains
                .map(domain => s"${api.domain}${api.contextPath}${domain.path}")
                .map(NgDomainAndPath.apply)
            ),
            backend = backend,
            backendRef = None,
            plugins = api.flows
              .find(_.id == apiRoute.flowRef)
              .map(_.plugins)
              .getOrElse(NgPlugins.empty)
          )
        )
    }
  }

  def apiRouteToNgRoute(routeId: String)(using env: Env): Future[Option[NgRoute]] = {
    routes.find(_.id == routeId) match {
      case Some(apiRoute) => routeToNgRoute(apiRoute)
      case None           => None.vfuture
    }
  }
}

object Api {

  private val logger = Logger("otoroshi-api-model")

  // the routes of a live api always end with the consumer enforcer: an api without a published or a deprecated plan,
  // that relied on being open, refuses the anonymous calls now. Told at startup for the ones whose flows do not
  // identify their callers either
  def warnApisWithoutServedPlan()(using env: Env, ec: ExecutionContext): Future[Unit] = {
    def validatesAccess(slot: NgPluginInstance): Boolean =
      slot.enabled && env.scriptManager
        .getAnyScript[otoroshi.next.plugins.api.NgNamedPlugin](slot.plugin)
        .toOption
        .exists(_.steps.contains(otoroshi.next.plugins.api.NgStep.ValidateAccess))
    env.datastores.apiDataStore
      .findAll()
      .map { apis =>
        apis
          .filter(api => api.state == ApiPublished || api.state == ApiDeprecated)
          .filterNot(api => api.plans.exists(_.isServed))
          .filterNot(api => api.flows.exists(_.plugins.slots.exists(validatesAccess)))
          .foreach { api =>
            logger.warn(
              s"api '${api.id}' (${api.name}) is ${api.state.name} without a published or a deprecated plan: its routes now refuse the anonymous calls. Publish a keyless plan to serve them"
            )
          }
      }
      .recover { case e: Throwable =>
        logger.error("error while looking for the apis without a published plan", e)
      }
  }

  // what was minted for an api goes with it: its subscriptions and the keys they hold, and the keys the extractors of
  // its plans minted for their consumers. The keys of the other apis are untouched. An api whose subscriptions or keys
  // could not be deleted is kept, so that nothing minted for it outlives it
  def deleteMintedFor(api: Api)(using env: Env): Future[Either[String, Unit]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    def minted(apikey: ApiKey): Boolean =
      apikey.apiRef.exists(_.api == api.id) &&
      (apikey.metadata.get("created_by").contains("apikey-extractor") ||
      apikey.metadata.get("plan_consumer").contains("true"))
    (for {
      subscriptions <- env.datastores.apiSubscriptionDataStore.findAll()
      results       <- subscriptions.filter(_.apiRef == api.id).mapAsync { sub =>
                         ApiSubscription.deleteKeys(sub).flatMap {
                           case Left(error) => (Left(error): Either[String, Unit]).vfuture
                           case Right(_)    =>
                             env.datastores.apiSubscriptionDataStore.delete(sub.id).map(_ => Right(()): Either[String, Unit])
                         }
                       }
      keys          <- env.datastores.apiKeyDataStore.findAll()
      // at once: each deletion of an api key scans the keys for its aliases
      _             <- env.datastores.apiKeyDataStore.deleteByIds(keys.filter(minted).map(_.clientId))
    } yield results.collectFirst { case Left(error) => error }.toLeft(())).recover { case e: Throwable =>
      logger.error(s"failed to delete the subscriptions and the api keys of api '${api.id}'", e)
      Left(s"failed to delete the subscriptions and the api keys of api '${api.id}'")
    }
  }

  def deleteValidator(
      api: Api,
      body: JsValue,
      singularName: String,
      id: String,
      action: DeleteAction,
      env: Env
  ): Future[Either[JsValue, Unit]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    deleteMintedFor(api)(using env).map {
      case Left(error) => Left(Json.obj("error" -> error, "http_status_code" -> 500))
      case Right(_)    => Right(())
    }
  }

  // what deleteMany removes without any validation (remote catalogs, kubernetes jobs) loses what was minted for it too:
  // an api whose subscriptions or keys could not be deleted fails the whole deletion, and nothing is deleted
  def beforeDeleteMany(apis: Seq[Api], env: Env): Future[Unit] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    implicit val e: Env               = env
    apis.mapAsync(api => deleteMintedFor(api)).flatMap { results =>
      results.collect { case Left(error) => error } match {
        case Seq()  => ().vfuture
        case errors => Future.failed(new RuntimeException(errors.mkString(", ")))
      }
    }
  }

  def isValidStateTransition(from: ApiState, to: ApiState, viaDeploy: Boolean): Boolean = (from, to) match {
    // staging
    case (ApiStaging, ApiStaging)       => true
    case (ApiStaging, ApiPublished)     => viaDeploy // only via deploy
    case (ApiStaging, _)                => false
    // published
    case (ApiPublished, ApiPublished)   => true
    case (ApiPublished, ApiDeprecated)  => true
    case (ApiPublished, ApiRemoved)     => true
    case (ApiPublished, _)              => false
    // deprecated
    case (ApiDeprecated, ApiPublished)  => true      // republish, direct (no deploy needed)
    case (ApiDeprecated, ApiDeprecated) => true
    case (ApiDeprecated, ApiRemoved)    => true
    case (ApiDeprecated, _)             => false
    // removed
    case (ApiRemoved, ApiStaging)       => true      // reopen
    case (ApiRemoved, ApiRemoved)       => true
    case (ApiRemoved, _)                => false
  }

  def transitionError(from: ApiState, to: ApiState): JsValue = Json.obj(
    "error"             -> "invalid_state_transition",
    "error_description" -> s"transition from '${from.name}' to '${to.name}' is not allowed",
    "from"              -> from.name,
    "to"                -> to.name,
    "http_status_code"  -> 400
  )

  // Fields that MUST go through Draft + deploy when the API is not in staging.
  // Every other field on the Api case class is freely editable in production.
  def diffProtectedFields(oldApi: Api, newApi: Api): Seq[String] = {
    val diffs = scala.collection.mutable.ListBuffer.empty[String]
    if (oldApi.domain != newApi.domain) diffs += "domain"
    if (oldApi.contextPath != newApi.contextPath) diffs += "contextPath"
    if (oldApi.routes != newApi.routes) diffs += "routes"
    if (oldApi.backends != newApi.backends) diffs += "backends"
    if (oldApi.flows != newApi.flows) diffs += "flows"
    if (oldApi.documentation != newApi.documentation) diffs += "documentation"
    if (oldApi.testing != newApi.testing) diffs += "testing"
    diffs.toSeq
  }

  // the design a deploy puts in production: the fields locked in production, and the plans, which decide who gets in
  // and how much (an audit or a comparison of two deployments needs them). The subscriptions, clients, testing and
  // metadata are left out
  def deploymentDefinition(api: Api): JsValue = Json.obj(
    "routes"        -> JsArray(api.routes.map(ApiRoute._fmt.writes)),
    "backends"      -> JsArray(api.backends.map(ApiBackend._fmt.writes)),
    "flows"         -> JsArray(api.flows.map(ApiFlows._fmt.writes)),
    "documentation" -> api.documentation.map(ApiDocumentation._fmt.writes).getOrElse[JsValue](JsNull),
    "plans"         -> JsArray(api.plans.map(_.raw))
  )

  // the plans that cannot be enforced as written: a selection on a plan whose callers present a key naming its plan, or
  // one that cannot be read
  def invalidPlan(api: Api): Option[String] =
    api.plans.iterator
      .flatMap(plan => invalidSelection(plan).orElse(invalidMtlsSettings(plan)).orElse(invalidValidity(plan)))
      .nextOption()

  // the validity of the keys of a plan is a positive number of milliseconds
  private def invalidValidity(plan: ApiPlan): Option[String] =
    plan.raw.select("access_mode_configuration").select("validity").asOpt[JsValue] match {
      case None | Some(JsNull)                                                     => None
      case Some(JsNumber(value)) if value.isWhole && value.isValidLong && value > 0 => None
      case Some(_)                                                                 =>
        Some(s"plan '${plan.id}': validity must be a positive number of milliseconds")
    }

  // the ids of trusted_ca_refs are not checked to exist: an import or a catalog may write the api before its
  // certificates, and a missing one refuses every certificate at call time
  private def invalidMtlsSettings(plan: ApiPlan): Option[String] =
    if (plan.accessModeConfigurationType != "mtls") None
    else {
      val conf = plan.raw.select("access_mode_configuration").asOpt[JsObject].getOrElse(Json.obj())
      val dnMatch = conf.select("dn_match").asOpt[JsValue] match {
        case None | Some(JsNull)                                                                                => None
        case Some(JsString(value))
            if value == MtlsAccessModeConfiguration.DnMatchAll || value == MtlsAccessModeConfiguration.DnMatchAny =>
          None
        case Some(_)                                                                                            =>
          Some(s"plan '${plan.id}': dn_match must be 'all' or 'any'")
      }
      dnMatch.orElse(conf.select("trusted_ca_refs").asOpt[JsValue] match {
        case None | Some(JsNull)                                                                         => None
        case Some(JsArray(values)) if values.forall { case JsString(id) => id.trim.nonEmpty; case _ => false } => None
        case Some(_)                                                                                     =>
          Some(s"plan '${plan.id}': trusted_ca_refs must be an array of certificate ids")
      })
    }

  private def invalidSelection(plan: ApiPlan): Option[String] =
      plan.raw.select("selection").asOpt[JsValue] match {
        case None | Some(JsNull)                                                                             => None
        case Some(JsArray(values)) if values.isEmpty                                                         => None
        case Some(_) if plan.accessModeConfigurationType == "apikey" || plan.accessModeConfigurationType == "oauth2-local" =>
          Some(s"plan '${plan.id}': selection is not supported on '${plan.accessModeConfigurationType}' plans")
        case Some(JsArray(values))                                                                           =>
          values.zipWithIndex.collectFirst {
            case (value, index) if otoroshi.utils.JsonPathValidator.format.reads(value).isError =>
              s"plan '${plan.id}': selection[$index] is not a valid json path validator"
          }
        case Some(_)                                                                                         =>
          Some(s"plan '${plan.id}': selection is not an array of json path validators")
      }

  // the ref a source puts on the api it writes, given to its deployments
  def deploymentRef(api: Api): Option[String] = ApiDeployment.readRef(api.metadata.get("deployment_ref"))

  // a remote catalog marks what it writes with its id in `created_by`
  def isWrittenByCatalog(api: Api): Boolean =
    api.metadata.get("created_by").exists(_.startsWith("remote_catalog="))

  // an outside tool (a gitops pipeline, a deployment job) marks the apis it manages as code with its id in `created_by`
  val externalToolPrefix = "external="

  private val externalToolId = "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$".r

  def externalTool(api: Api): Option[String] =
    api.metadata.get("created_by").filter(_.startsWith(externalToolPrefix)).map(_.drop(externalToolPrefix.length))

  def isWrittenByExternalTool(api: Api): Boolean = externalTool(api).isDefined

  // an api written as code, by a remote catalog or by an outside tool: each of its writes is a deploy
  def isWrittenAsCode(api: Api): Boolean = isWrittenByCatalog(api) || isWrittenByExternalTool(api)

  private def invalidExternalTool(api: Api): Option[JsValue] =
    externalTool(api).filterNot(id => externalToolId.matches(id)).map { id =>
      Json.obj(
        "error"             -> "invalid_created_by",
        "error_description" -> s"created_by '${externalToolPrefix}$id': the id of the tool must match [A-Za-z0-9][A-Za-z0-9._-]{0,127}",
        "http_status_code"  -> 400
      )
    }

  private def isLive(state: ApiState): Boolean = state == ApiPublished || state == ApiDeprecated

  // A remote catalog, or an outside tool, writes the api as code: its source plays the role of the draft, so each of
  // its writes is a deploy. The api takes the state and the fields locked in production from the source, and a write
  // that changes what is live is recorded in the deployment history, owned by the value of `created_by`. This opens
  // nothing to the other writers: whoever can read an api can already publish it with `_start` or a deployment.
  private def asCodeDeploy(entity: Api, oldApi: Option[Api], env: Env): Future[Either[JsValue, Api]] = oldApi match {
    case None                                                                            =>
      // created in the state of the source: created live, it is its first deploy
      val api = entity.copy(deployments = Seq.empty)
      (if (isLive(api.state)) withDeployment(api, env) else api).rightf
    case Some(old) if !isValidStateTransition(old.state, entity.state, viaDeploy = true) =>
      transitionError(old.state, entity.state).leftf
    case Some(old)                                                                       =>
      // the source carries neither the deployment history nor the testing secret. The secret cannot be
      // told apart from the one generated when the source was read, so the one of the live api stays
      val api      = entity.copy(
        deployments = old.deployments,
        testing = entity.testing.copy(headerValue = old.testing.headerValue)
      )
      // a new version of the source put live, or a change of the plans of a live api, is a deployment too
      val deployed = isLive(api.state) && (
        !isLive(old.state) ||
        diffProtectedFields(old, api).nonEmpty ||
        old.plans.map(_.raw) != api.plans.map(_.raw) ||
        deploymentRef(api) != old.deployments.headOption.flatMap(_.ref)
      )
      (if (deployed) withDeployment(api, env) else api).rightf
  }

  // records the design of the api as its last deployment, the last ones are kept as for a deploy
  private def withDeployment(api: Api, env: Env): Api = {
    val deployment = ApiDeployment(
      id = IdGenerator.namedId("api_deployment", env),
      apiRef = api.id,
      owner = api.metadata.getOrElse("created_by", ""),
      at = DateTime.now(),
      apiDefinition = deploymentDefinition(api),
      version = api.version,
      ref = deploymentRef(api)
    )
    api.copy(deployments = (deployment +: api.deployments).slice(0, ApiDeployment.historySize(env)))
  }

  def writeValidator(
      entity: Api,
      body: JsValue,
      oldEntity: Option[(Api, JsValue)],
      singularName: String,
      id: Option[String],
      action: WriteAction,
      env: Env
  ): Future[Either[JsValue, Api]] = {

    implicit val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext
    implicit val e: otoroshi.env.Env = env
    writeChecker(entity, body, oldEntity, singularName, id, action, env).flatMap {
      case Right(api) =>
        // the deploy of an api written as code is audited as the ones of the backoffice
        if (
          isWrittenAsCode(api) && api.deployments.nonEmpty &&
          !oldEntity.exists(old => old._1.deployments.nonEmpty && old._1.deployments.head.id == api.deployments.head.id)
        ) {
          val deployment = api.deployments.head
          ApiDeploymentEvent(
            `@id` = env.snowflakeGenerator.nextIdStr(),
            `@timestamp` = DateTime.now(),
            apiRef = deployment.apiRef,
            owner = deployment.owner,
            at = deployment.at,
            apiDefinition = deployment.apiDefinition,
            version = api.version,
            `@service` = api.name,
            `@serviceId` = api.id,
            deploymentId = deployment.id,
            ref = deployment.ref
          ).toAnalytics()
        }
        oldEntity match {
          case Some(old) =>
            ApiConsistencyService
              .applyApiChanges(old._1, api, isDraft = false)
              .map(a => Right(a): Either[JsValue, Api])
              // the api is not written: a plan whose subscriptions keep their api keys stays in it, to be retried
              .recover { case t: Throwable => Left(Json.obj("error" -> t.getMessage, "http_status_code" -> 500)) }
          case None      => api.rightf
        }
      case left       => left.vfuture
    }
  }

  // the checks of writeValidator, without the changes it applies to the subscriptions
  def writeChecker(
      entity: Api,
      body: JsValue,
      oldEntity: Option[(Api, JsValue)],
      singularName: String,
      id: Option[String],
      action: WriteAction,
      env: Env
  ): Future[Either[JsValue, Api]] = {
    oldEntity match {
      case _ if invalidPlan(entity).isDefined                   =>
        Json
          .obj("error" -> "invalid_plan", "error_description" -> invalidPlan(entity).get, "http_status_code" -> 400)
          .leftf
      // the ref a source gives to the deployments of its api
      case _ if ApiDeployment.refTooLong(deploymentRef(entity)) =>
        (ApiDeployment.refTooLongError ++ Json.obj("http_status_code" -> 400)).leftf
      case _ if invalidExternalTool(entity).isDefined          => invalidExternalTool(entity).get.leftf
      case _ if isWrittenAsCode(entity)                         => asCodeDeploy(entity, oldEntity.map(_._1), env)
      case None      =>
        // Create path: API must start in staging.
        if (entity.state != ApiStaging) {
          Json
            .obj(
              "error"             -> "cannot_create_in_state",
              "error_description" -> s"new API must be created in 'staging' state, got '${entity.state.name}'",
              "state"             -> entity.state.name,
              "http_status_code"  -> 400
            )
            .leftf
        } else {
          entity.rightf
        }
      case Some(old) =>
        val oldApi = old._1
        // 1. State transition guard. Plain PUT cannot move staging -> published; that goes through deploy.
        if (!isValidStateTransition(oldApi.state, entity.state, viaDeploy = false)) {
          transitionError(oldApi.state, entity.state).leftf
        } else if (oldApi.state != ApiStaging && diffProtectedFields(oldApi, entity).nonEmpty) {
          // 2. Production read-only guard: locked fields must go through Draft + deploy.
          val fields = diffProtectedFields(oldApi, entity)
          Json
            .obj(
              "error"             -> "production_readonly",
              "error_description" -> s"fields ${fields.mkString("[", ", ", "]")} are read-only in '${oldApi.state.name}' state; edit them via Draft and deploy",
              "fields"            -> fields,
              "http_status_code"  -> 400
            )
            .leftf
        } else {
          entity.rightf
        }
    }
  }

  def fromOpenApi(domain: String, openapi: String, contextPath: String, backendHostname: String, backendPath: String)(
      using
      ec: ExecutionContext,
      env: Env
  ): Future[Api] = {
    val codef = env.Ws.url(openapi).get()

    codef.map { response =>
      val contentType: String = response.headers.getIgnoreCase("Content-Type").map(_.head).getOrElse("application/json")

      val json = contentType match {
        case "application/yaml" => Yaml.parseSafe(response.body).getOrElse(Json.obj())
        case "text/yaml"        => Yaml.parseSafe(response.body).getOrElse(Json.obj())
        case _                  => Json.parse(response.body)
      }

      val name        = json.select("info").select("title").asOpt[String].getOrElse("unknown-name")
      val description = json.select("info").select("description").asOpt[String].getOrElse("")
      val version     = json.select("info").select("version").asOpt[String].getOrElse("")
      val targets     = json.select("servers").asOpt[Seq[JsObject]].getOrElse(Seq.empty).toSeq.map { server =>
        val serverUrl = server.selectAsOptString("url").getOrElse("/")
        val serverUri = Uri(serverUrl)

        val tls  = if (openapi.startsWith("https")) { true }
        else { serverUri.scheme.toLowerCase().contains("https") }
        val port = if (serverUri.authority.port == 0) (if (tls) 443 else 80) else serverUri.authority.port
        NgTarget(
          id = serverUrl,
          hostname = backendHostname,
          port = port,
          tls = tls
        )
      }
      val paths       = json.select("paths").asOpt[JsObject].getOrElse(Json.obj())

      val backend = ApiBackend(
        id = s"${name}_backend",
        name = s"${name}_backend",
        backend = NgBackend.empty.copy(
          targets = targets,
          root = backendPath,
          rewrite = false,
          loadBalancing = RoundRobin
        ),
        client = "default_backend_client"
      )

      val routes: Seq[ApiRoute] = paths.value.toSeq.map { case (path, obj) =>
        val cleanPath = path.replace("{", ":").replace("}", "")
        val methods   = obj.as[JsObject].value.toSeq.map(_._1.toUpperCase())

        val name = obj.as[JsObject].value.toSeq.map(_._2.select("summary").asOptString.getOrElse(cleanPath)).headOption

        ApiRoute(
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(cleanPath)),
            headers = Map.empty,
            cookies = Map.empty,
            query = Map.empty,
            methods = methods,
            stripPath = false,
            exact = true
          ),
          backend = backend.id,
          flowRef = "default_plugin_chain",
          id = name.getOrElse(sanitize(cleanPath)),
          name = name.getOrElse(cleanPath).some
        )
      }

      Api(
        location = EntityLocation.default,
        id = "api_route" + IdGenerator.uuid,
        name = name,
        description = description,
        domain = domain,
        contextPath = contextPath,
        debugFlow = false,
        capture = false,
        exportReporting = false,
        routes = routes,
        testing = ApiTesting.default,
        version = version,
        blueprint = ApiBlueprint.REST,
        state = ApiStaging,
        backends = Seq(backend),
        flows = Seq(ApiFlows.empty(using env)),
        groups = Seq.empty
      )
    }
  }

  def fromJsons(value: JsValue): Api =
    try {
      format.reads(value).get
    } catch {
      case e: Throwable => throw e
    }
  val format: Format[Api]            = new Format[Api] {
    override def writes(o: Api): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "kind"                   -> o.kind,
      "id"                     -> o.id,
      "name"                   -> o.name,
      "description"            -> o.description,
      "domain"                 -> o.domain,
      "contextPath"            -> o.contextPath,
      "metadata"               -> o.metadata,
      "tags"                   -> JsArray(o.tags.map(JsString.apply)),
      "visibility"             -> o.visibility.json,
      "owner"                  -> o.owner.json,
      "members"                -> JsArray(o.members.map(_.json)),
      "version"                -> o.version,
      "debug_flow"             -> o.debugFlow,
      "capture"                -> o.capture,
      "export_reporting"       -> o.exportReporting,
      "groups"                 -> o.groups,
      "state"                  -> o.state.name,
      "enabled"                -> o.enabled,
      "blueprint"              -> o.blueprint.name,
      "plans"                  -> JsArray(o.plans.map(_.raw)),
      "routes"                 -> o.routes.map(ApiRoute._fmt.writes),
      "backends"               -> o.backends.map(ApiBackend._fmt.writes),
      "flows"                  -> o.flows.map(ApiFlows._fmt.writes),
      "clients_backend_config" -> (if (o.clientsBackendConfig.isEmpty) { Seq(ApiBackendClient.defaultClient) }
                                   else o.clientsBackendConfig).map(ApiBackendClient._fmt.writes),
      "documentation"          -> o.documentation.map(ApiDocumentation._fmt.writes),
      "deployments"            -> o.deployments.map(ApiDeployment._fmt.writes),
      "versions"               -> o.versions,
      "testing"                -> ApiTesting._fmt.writes(o.testing),
      "clients"                -> o.clients.map(ApiClient.format.writes),
      "hooks"                  -> JsArray(o.hooks.map(_.json))
    )
    override def reads(json: JsValue): JsResult[Api] = Try {
      Api(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        // the admin api answers the kind with its group, `apis.otoroshi.io/Api`: a put of what a get answered keeps `Api`
        kind = (json \ "kind").asOpt[String].map(_.split("/").last).getOrElse("Api"),
        id = (json \ "id").asString,
        name = (json \ "name").asString,
        description = (json \ "description").asString,
        domain = (json \ "domain").asOpt[String].getOrElse(""),
        contextPath = (json \ "contextPath").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]).toSeq,
        visibility = (json \ "visibility")
          .asOpt[JsObject]
          .flatMap(o => ApiVisibility.format.reads(o).asOpt)
          .getOrElse(ApiVisibility.Public),
        owner = (json \ "owner").asOpt[JsObject].flatMap(o => UserRef.format.reads(o).asOpt).getOrElse(UserRef.empty),
        members = (json \ "members")
          .asOpt[Seq[JsObject]]
          .map(seq => seq.flatMap(o => UserRef.format.reads(o).asOpt))
          .getOrElse(Seq.empty).toSeq,
        version = (json \ "version").asOptString.getOrElse("0.0.1"),
        debugFlow = (json \ "debug_flow").asOpt[Boolean].getOrElse(false),
        capture = (json \ "capture").asOpt[Boolean].getOrElse(false),
        exportReporting = (json \ "export_reporting").asOpt[Boolean].getOrElse(false),
        groups = (json \ "groups").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        state = (json \ "state").asOptString
          .map {
            case "staging"    => ApiStaging
            case "published"  => ApiPublished
            case "deprecated" => ApiDeprecated
            case "removed"    => ApiRemoved
            case other        =>
              logger.warn(s"unknown api state '$other', falling back to 'staging'")
              ApiStaging
          }
          .getOrElse(ApiStaging),
        enabled = (json \ "enabled").asOptBoolean.getOrElse(true),
        blueprint = (json \ "blueprint").asOptString
          .map {
            case "REST"      => ApiBlueprint.REST
            case "GraphQL"   => ApiBlueprint.GraphQL
            case "gRPC"      => ApiBlueprint.gRPC
            case "Http"      => ApiBlueprint.Http
            case "Websocket" => ApiBlueprint.Websocket
          }
          .getOrElse(ApiBlueprint.REST),
        routes = (json \ "routes")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiRoute._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty).toSeq,
        backends = (json \ "backends")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiBackend._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty).toSeq,
        flows = (json \ "flows")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiFlows._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty).toSeq,
        clientsBackendConfig = (json \ "clients_backend_config")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiBackendClient._fmt.reads(v).asOpt))
          .getOrElse(Seq(ApiBackendClient.defaultClient)),
        // a missing documentation is written as null, which must not read back as an enabled empty documentation
        documentation = (json \ "documentation")
          .asOpt[JsObject]
          .flatMap(ApiDocumentation._fmt.reads(_).asOpt),
        deployments = (json \ "deployments")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiDeployment._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty).toSeq,
        versions = json
          .select("versions")
          .asOpt[Seq[String]]
          .getOrElse(Seq.empty).toSeq,
        testing = json
          .select("testing")
          .asOpt(using ApiTesting._fmt)
          .getOrElse(ApiTesting()),
        clients = (json \ "clients")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiClient.format.reads(v).asOpt))
          .getOrElse(Seq.empty).toSeq,
        hooks = (json \ "hooks")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiStateHook.format.reads(v).asOpt))
          .getOrElse(Seq.empty).toSeq,
        // the entries that are not objects are left out one by one: dropping every plan would leave the routes of the
        // api without any access plugin
        plans = json
          .select("plans")
          .asOpt[Seq[JsValue]]
          .getOrElse(Seq.empty)
          .collect { case o: JsObject => ApiPlan(o) }
          .toSeq
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
      domain = "api.oto.tools",
      contextPath = "/v1",
      metadata = Map.empty,
      tags = Seq.empty,
      version = "0.0.1",
      debugFlow = false,
      capture = false,
      exportReporting = false,
      groups = Seq.empty,
      state = ApiStaging,
      blueprint = ApiBlueprint.REST,
      routes = Seq.empty,
      backends = Seq(ApiBackend.empty(using env)),
      flows = Seq(ApiFlows.empty(using env)),
      clients = Seq.empty,
      clientsBackendConfig = Seq(ApiBackendClient.defaultClient),
      documentation = None,
      deployments = Seq.empty,
      testing = ApiTesting()
    )
    env.datastores.globalConfigDataStore
      .latest()(using env.otoroshiExecutionContext, env)
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
  override def fmt: Format[Api]                        = Api.format
  override def redisLike(using env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:apis:$id"
  override def extractId(value: Api): String           = value.id
}

trait ApiSubscriptionDataStore extends BasicStore[ApiSubscription] {
  def template(env: Env): ApiSubscription = {
    val defaultSubscription = ApiSubscription(
      location = EntityLocation.default,
      id = IdGenerator.namedId("api-subscription", env),
      name = "New API Subscription",
      description = "New API Subscription description",
      metadata = Map.empty,
      tags = Seq.empty,
      status = ApiSubscriptionDisabled,
      dates = ApiSubscriptionDates(
        created_at = DateTime.now(),
        processed_at = DateTime.now(),
        started_at = DateTime.now(),
        paused_at = DateTime.now(),
        ending_at = DateTime.now(),
        closed_at = DateTime.now()
      ),
      ownerRef = "",
      planRef = "",
      subscriptionKind = ApiKind.Apikey,
      tokenRefs = Seq.empty,
      apiRef = ""
    )

    env.datastores.globalConfigDataStore
      .latest()(using env.otoroshiExecutionContext, env)
      .templates
      .apiSubscription
      .map { template =>
        ApiSubscription.format.reads(defaultSubscription.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        defaultSubscription
      }
  }

  def keysFollowStatusMigrationKey(using env: Env): String =
    s"${env.storageRoot}:migrations:apisubscriptions:keys-follow-status"

  // the api keys of the subscriptions to an apikey or an oauth2-local plan used to be enabled for every status but
  // `disabled`. Only `enabled` gives access now: the keys stored before that, of the subscriptions in any other status,
  // are disabled once. Nothing else of the keys or of the subscriptions is changed
  def migrate()(using ec: ExecutionContext, env: Env): Future[Unit] = {
    val logger = Logger("otoroshi-apisubscriptions-migration")
    env.datastores.rawDataStore.get(keysFollowStatusMigrationKey).flatMap {
      case Some(_) => ().vfuture
      case None    =>
        findAll()
          .flatMap { subscriptions =>
            subscriptions
              .filter(sub =>
                sub.status != ApiSubscriptionEnabled &&
                (sub.subscriptionKind == ApiKind.Apikey || sub.subscriptionKind == ApiKind.OAuth2Local)
              )
              .flatmapAsync { sub =>
                sub.tokenRefs
                  .flatMap(ref => ref.select("apikey").asOpt[String])
                  .mapAsync { clientId =>
                    env.datastores.apiKeyDataStore.findById(clientId).flatMap {
                      // only the keys of the subscription: a key of another one, or a key no subscription minted, is
                      // not its to disable
                      case Some(apikey) if apikey.enabled && ApiSubscription.ownsKey(sub, apikey) =>
                        apikey.copy(enabled = false).save().map(saved => Some((sub, clientId, saved)))
                      case _                                                                     => None.vfuture
                    }
                  }
                  .map(_.flatten)
              }
          }
          .flatMap { results =>
            val (done, failed) = results.partition(_._3)
            done.groupBy(_._1).foreach { case (sub, keys) =>
              logger.warn(
                s"the api keys ${keys.map(_._2).mkString(", ")} of the subscription '${sub.id}' (status '${sub.status.name}') were disabled"
              )
            }
            if (done.nonEmpty) {
              logger.warn(s"${done.size} api keys of subscriptions that are not 'enabled' were disabled")
            }
            if (failed.nonEmpty) {
              // not recorded, so that the migration runs again at the next start
              logger.error(s"unable to disable the api keys ${failed.map(_._2).mkString(", ")}, will retry at the next start")
              ().vfuture
            } else {
              env.datastores.rawDataStore
                .set(keysFollowStatusMigrationKey, ByteString(DateTime.now().toString), None)
                .map(_ => ())
            }
          }
          .recover { case e: Throwable =>
            logger.error("error while disabling the api keys of the subscriptions that are not 'enabled'", e)
          }
    }
  }
}

class KvApiSubscriptionDataStore(redisCli: RedisLike, _env: Env)
    extends ApiSubscriptionDataStore
    with RedisLikeStore[ApiSubscription] {
  override def fmt: Format[ApiSubscription]              = ApiSubscription.format
  override def redisLike(using env: Env): RedisLike   = redisCli
  override def key(id: String): String                   = s"${_env.storageRoot}:apisubscriptions:$id"
  override def extractId(value: ApiSubscription): String = value.id
}
