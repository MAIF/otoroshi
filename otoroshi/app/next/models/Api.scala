package next.models

import akka.http.scaladsl.model.Uri
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.api.WriteAction.{Create, Update}
import otoroshi.api.{DeleteAction, WriteAction}
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, Alerts, Audit}
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.{ApikeyQuotas, _}
import otoroshi.next.plugins.api.NgPluginHelper.pluginId
import otoroshi.next.services.ApiConsistencyService
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.TypedMap
import otoroshi.utils.UrlSanitizer.sanitize
import otoroshi.utils.controllers.{GenericAlert, SendAuditAndAlert}
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml
import play.api.libs.json._

import java.net.URI
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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
  def empty(implicit env: Env): ApiFlows = ApiFlows(
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
        version = json.select("version").asOptString.getOrElse("0.0.1")
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
      "version"       -> o.version
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
    raw.select("links").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => ApiDocumentationSidebarLink(o))
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
    raw.select("path").asOpt[Seq[String]].orElse(raw.select("path").asOptString.map(s => Seq(s))).getOrElse(Seq.empty)
  lazy val items: Seq[ApiDocumentationSidebarItem] =
    raw.select("items").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { v =>
      v.select("kind").asOptString.getOrElse("link") match {
        case "category" => ApiDocumentationSidebarCategory(v.asObject)
        case _          => ApiDocumentationSidebarLink(v.asObject)
      }
    }
}

case class ApiDocumentationResource(raw: JsObject) {
  lazy val path: Seq[String]                  =
    raw.select("path").asOpt[Seq[String]].orElse(raw.select("path").asOptString.map(s => Seq(s))).getOrElse(Seq.empty)
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

trait ApiDocumentationAccessModeConfiguration {
  def apiKind: ApiKind
}

case class JWTAccessModeConfiguration(
    verifier: Option[String] = None
) extends ApiDocumentationAccessModeConfiguration {
  override def apiKind: ApiKind = ApiKind.JWT
}

object JWTAccessModeConfiguration {
  def fmt = new Format[JWTAccessModeConfiguration] {
    override def reads(json: JsValue): JsResult[JWTAccessModeConfiguration] = Try {
      JWTAccessModeConfiguration(
        verifier = json.select("verifier").asOpt[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: JWTAccessModeConfiguration): JsValue             = Json.obj(
      "verifier" -> o.verifier
    )
  }
}

case class MtlsAccessModeConfiguration(
    regexSubjectDNs: Seq[String] = Seq.empty,
    regexIssuerDNs: Seq[String] = Seq.empty
) extends ApiDocumentationAccessModeConfiguration {
  override def apiKind: ApiKind = ApiKind.Mtls
}

object MtlsAccessModeConfiguration {
  def fmt = new Format[MtlsAccessModeConfiguration] {
    override def reads(json: JsValue): JsResult[MtlsAccessModeConfiguration] = Try {
      MtlsAccessModeConfiguration(
        regexSubjectDNs = json.select("regex_subject_dns").asOpt[Seq[String]].getOrElse(Seq.empty),
        regexIssuerDNs = json.select("regex_issuer_dns").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: MtlsAccessModeConfiguration): JsValue             = Json.obj(
      "regex_subject_dns" -> o.regexSubjectDNs,
      "regex_issuer_dns"  -> o.regexIssuerDNs
    )
  }
}

case class OAuth2RemoteAccessModeConfiguration(
    verifier: Option[String] = None
) extends ApiDocumentationAccessModeConfiguration {
  override def apiKind: ApiKind = ApiKind.OAuth2Remote
}

object OAuth2RemoteAccessModeConfiguration {
  def fmt = new Format[OAuth2RemoteAccessModeConfiguration] {
    override def reads(json: JsValue): JsResult[OAuth2RemoteAccessModeConfiguration] = Try {
      OAuth2RemoteAccessModeConfiguration(
        verifier = json.selectAsOptString("verifier")
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: OAuth2RemoteAccessModeConfiguration): JsValue             = Json.obj(
      "verifier" -> o.verifier
    )
  }
}

case class OAuth2AccessModeConfiguration(
    defaultKeyPair: String,
    expiration: Int
) extends ApiDocumentationAccessModeConfiguration {
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
    restrictions: Restrictions = Restrictions(),
    validUntil: Option[DateTime] = None,
    rotation: ApiKeyRotation = ApiKeyRotation(),
    tags: Seq[String] = Seq.empty[String],
    metadata: Map[String, String] = Map.empty[String, String]
) extends ApiDocumentationAccessModeConfiguration {
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
      "tags"                    -> JsArray(tags.map(JsString.apply)),
      "metadata"                -> JsObject(metadata.filter(_._1.nonEmpty).mapValues(JsString.apply))
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
              .getOrElse(Seq.empty[EntityIdentifier])
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
                .getOrElse(Seq.empty[EntityIdentifier])
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
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
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

case class ApiDocumentationPlanVisibility(kind: ApiDocumentationPlanVisibilityKind, config: JsObject = Json.obj()) {
  def json: JsValue = ApiDocumentationPlanVisibility.format.writes(this)
}

object ApiDocumentationPlanVisibility {
  val Public: ApiDocumentationPlanVisibility = ApiDocumentationPlanVisibility(ApiDocumentationPlanVisibilityKind.Public)
  val format                                 = new Format[ApiDocumentationPlanVisibility] {
    override def reads(json: JsValue): JsResult[ApiDocumentationPlanVisibility] = Try {
      ApiDocumentationPlanVisibility(
        kind = ApiDocumentationPlanVisibilityKind(json.select("kind").asOptString.getOrElse("public")),
        config = json.select("config").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: ApiDocumentationPlanVisibility): JsValue             = Json.obj(
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

  def apply(str: String): ApiDocumentationPlanValidationKind = str match {
    case "auto"     => Auto
    case "manual"   => Manual
    case "webhook"  => Webhook
    case "workflow" => Workflow
    case "wasm"     => Wasm
    case "custom"   => Custom
    case _          => Auto
  }
}

case class ApiDocumentationPlanValidation(kind: ApiDocumentationPlanValidationKind, config: JsObject = Json.obj()) {
  def isAuto: Boolean = kind == ApiDocumentationPlanValidationKind.Auto
  def json: JsValue   = ApiDocumentationPlanValidation.format.writes(this)
}
object ApiDocumentationPlanValidation                                                                              {
  val Auto   = ApiDocumentationPlanValidation(ApiDocumentationPlanValidationKind.Auto)
  val format = new Format[ApiDocumentationPlanValidation] {

    override def reads(json: JsValue): JsResult[ApiDocumentationPlanValidation] = Try {
      ApiDocumentationPlanValidation(
        kind = ApiDocumentationPlanValidationKind(json.select("kind").asOptString.getOrElse("auto")),
        config = json.select("config").asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: ApiDocumentationPlanValidation): JsValue = Json.obj(
      "kind"   -> o.kind.json,
      "config" -> o.config
    )
  }
}

case class ApiDocumentationPlan(raw: JsObject) {
  lazy val accessModeConfigurationType                                              = raw.selectAsOptString("access_mode_configuration_type").getOrElse("keyless")
  lazy val id: String                                                               = raw.selectAsString("id")
  lazy val name: String                                                             = raw.selectAsString("name")
  lazy val description: String                                                      = raw.selectAsOptString("description").getOrElse("No description")
  lazy val pricing: ApiPricing                                                      = raw.select("pricing").as(ApiPricing.format)
  lazy val rateLimiting: Option[ThrottlingStrategyConfig]                           =
    raw
      .select("rateLimiting")
      .asOpt[JsObject]
      .flatMap(rateLimiting => rateLimiting.select("strategy").asOpt(ThrottlingStrategyConfig.fmt))
  lazy val accessModeConfiguration: Option[ApiDocumentationAccessModeConfiguration] =
    accessModeConfigurationType match {
      case "apikey"        => (raw \ "access_mode_configuration").asOpt(ApikeyAccessModeConfiguration.fmt)
      case "jwt"           => (raw \ "access_mode_configuration").asOpt(JWTAccessModeConfiguration.fmt)
      case "mtls"          => (raw \ "access_mode_configuration").asOpt(MtlsAccessModeConfiguration.fmt)
      case "oauth2-local"  => (raw \ "access_mode_configuration").asOpt(OAuth2AccessModeConfiguration.fmt)
      case "oauth2-remote" => (raw \ "access_mode_configuration").asOpt(OAuth2RemoteAccessModeConfiguration.fmt)
      case _               => None
    }
  lazy val status: ApiPlanStatus                                                    = raw.selectAsOptString("status").getOrElse("published").toLowerCase match {
    case "staging"    => ApiPlanStatus.Staging
    case "published"  => ApiPlanStatus.Published
    case "deprecated" => ApiPlanStatus.Deprecated
    case _            => ApiPlanStatus.Closed
  }
  lazy val tags: Seq[String]                                                        = raw.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty)
  lazy val metadata: Map[String, String]                                            = raw.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val validation: ApiDocumentationPlanValidation                               = raw
    .select("validation")
    .asOpt[JsObject]
    .flatMap(o => ApiDocumentationPlanValidation.format.reads(o).asOpt)
    .getOrElse(ApiDocumentationPlanValidation.Auto)
  lazy val visibility: ApiDocumentationPlanVisibility                               = raw
    .select("visibility")
    .asOpt[JsObject]
    .flatMap(o => ApiDocumentationPlanVisibility.format.reads(o).asOpt)
    .getOrElse(ApiDocumentationPlanVisibility.Public)
}

case class ApiDocumentationSource(raw: JsObject) {
  lazy val url: Option[String]              = raw.select("url").asOpt[String]
  lazy val httpHeaders: Map[String, String] = raw.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val httpTimeout: FiniteDuration      = raw.select("timeout").asOpt[Long].getOrElse(30000L).millis
  lazy val httpFollowRedirects: Boolean     = raw.select("follow_redirects").asOpt[Boolean].getOrElse(true)
  def resolve(doc: ApiDocumentation)(implicit env: Env, ec: ExecutionContext): Future[Option[ApiDocumentation]] = {
    url match {
      case None      => None.vfuture
      case Some(url) => {
        env.Ws
          .url(url)
          .withFollowRedirects(httpFollowRedirects)
          .withHttpHeaders(httpHeaders.toSeq: _*)
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
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String])
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
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        source = json.select("source").asOpt[JsObject].map(o => ApiDocumentationSource(o)),
        home = ApiDocumentationResource(json.select("home").asOpt[JsObject].getOrElse(Json.obj())),
        logo = ApiDocumentationResource(json.select("logo").asOpt[JsObject].getOrElse(Json.obj())),
        references =
          json.select("references").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => ApiDocumentationResourceRef(o)),
        resources =
          json.select("resources").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => ApiDocumentationResource(o)),
        navigation =
          json.select("navigation").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => ApiDocumentationSidebar(o)),
        redirections = json
          .select("redirections")
          .asOpt[Seq[JsObject]]
          .getOrElse(Seq.empty)
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
    tokenRefs: Seq[JsValue] // ref to apikey, cert, etc
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = ApiSubscription.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object ApiSubscription {

  val PLAN_METADATA_KEY           = "plan_metadata"
  val SUBSCRIPTION_METADATA_KEY   = "subscription_metadata"
  val PLAN_TAGS_KEY               = "plan_tags"
  val SUBSCRIPTION_TAGS_KEY       = "subscription_tags"
  val METADATA_AND_TAGS_SEPARATOR = " | "
  val CORE_METADATA               = Seq("updated_at")

  private def generateNewApikeyFromPlan(api: Api, plan: ApiDocumentationPlan, subscription: ApiSubscription)(implicit
      env: Env
  ) = {
    val configPlan = plan.accessModeConfiguration
      .map(_.asInstanceOf[ApikeyAccessModeConfiguration])
      .getOrElse(ApikeyAccessModeConfiguration())

    val attrs = TypedMap(
      otoroshi.plugins.Keys.PlanKey -> plan,
      otoroshi.plugins.Keys.ApiKey  -> api
    )

    val defaultApikey = ApiKey(
      enabled = false,
      clientId = IdGenerator.lowerCaseToken(16),
      clientSecret = IdGenerator.lowerCaseToken(64),
      clientName = configPlan.clientNamePattern
        .map(_.evaluateEl(attrs)(env))
        .getOrElse(IdGenerator.lowerCaseToken(22)),
      description = configPlan.description.getOrElse(""),
      validUntil = configPlan.validUntil,
      readOnly = configPlan.readOnly,
      constrainedServicesOnly = configPlan.constrainedServicesOnly,
      restrictions = configPlan.restrictions,
      rotation = configPlan.rotation,
      authorizedEntities = Seq(ApiIdentifier(api.id)),
      throttlingStrategy = plan.rateLimiting,
      metadata = configPlan.metadata ++ subscription.metadata +
        (PLAN_METADATA_KEY         -> configPlan.metadata.keySet.mkString(METADATA_AND_TAGS_SEPARATOR)) +
        (SUBSCRIPTION_METADATA_KEY -> subscription.metadata.keySet.mkString(METADATA_AND_TAGS_SEPARATOR)) +
        (PLAN_TAGS_KEY             -> configPlan.tags.mkString(METADATA_AND_TAGS_SEPARATOR)) +
        (SUBSCRIPTION_TAGS_KEY     -> subscription.tags.mkString(METADATA_AND_TAGS_SEPARATOR)),
      tags = configPlan.tags ++ subscription.tags
    )

    defaultApikey
      .copy(enabled = subscription.status != ApiSubscriptionDisabled)
  }

  private def createNewApikeyFromPlan(api: Api, plan: ApiDocumentationPlan, subscription: ApiSubscription)(implicit
      env: Env
  ) = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    val defaultApikey = generateNewApikeyFromPlan(api, plan, subscription)

    defaultApikey.save().map {
      case false => "failed to create the apikey".left
      case true  =>
        subscription
          .copy(tokenRefs = subscription.tokenRefs :+ Json.obj("apikey" -> defaultApikey.clientId))
          .right
    }
  }

  private def removeManagedMetadata(currentApikeyMetadata: Map[String, String]): Map[String, String] = {
    val managed_keys: Seq[String] =
      currentApikeyMetadata.get(PLAN_METADATA_KEY).map(_.split(METADATA_AND_TAGS_SEPARATOR)).getOrElse(Array.empty) ++
      currentApikeyMetadata
        .get(SUBSCRIPTION_METADATA_KEY)
        .map(_.split(SUBSCRIPTION_METADATA_KEY))
        .getOrElse(Array.empty)

    currentApikeyMetadata.filterKeys(key => !managed_keys.contains(key) && !CORE_METADATA.contains(key))
  }

  private def removeManagedTags(
      currentApikeyMetadata: Map[String, String],
      currentApikeyTags: Seq[String]
  ): Seq[String] = {
    val managed_keys: Seq[String] =
      currentApikeyMetadata.get(PLAN_TAGS_KEY).map(_.split(METADATA_AND_TAGS_SEPARATOR)).getOrElse(Array.empty) ++
      currentApikeyMetadata.get(SUBSCRIPTION_TAGS_KEY).map(_.split(SUBSCRIPTION_METADATA_KEY)).getOrElse(Array.empty)

    currentApikeyTags.filter(key => !managed_keys.contains(key))
  }

  private def updateApikeyFromPlan(api: Api, plan: ApiDocumentationPlan, subscription: ApiSubscription)(implicit
      env: Env
  ): Future[Seq[Either[String, Boolean]]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    val newApikey = generateNewApikeyFromPlan(api, plan, subscription)

    val apikeys: Seq[String] = subscription.tokenRefs.collect {
      case JsObject(values) if values.contains("apikey") => values("apikey").asString
    }

    Future.sequence(
      apikeys.map(apikeyId =>
        env.datastores.apiKeyDataStore
          .findById(apikeyId)
          .flatMap {
            case Some(apikey) =>
              newApikey
                .copy(
                  enabled = newApikey.enabled,
                  clientId = apikey.clientId,
                  clientSecret = apikey.clientSecret,
                  metadata = newApikey.metadata ++ removeManagedMetadata(apikey.metadata),
                  tags = newApikey.tags ++ removeManagedTags(apikey.metadata, apikey.tags)
                )
                .save()
                .map(_.right)
            case None         => s"apikey with $apikeyId not found".leftf
          }
      )
    )
  }

  def handleSubscriptionChanged(
      api: Api,
      plan: ApiDocumentationPlan,
      subscription: ApiSubscription,
      action: WriteAction,
      isDraft: Boolean
  )(implicit
      env: Env
  ): Future[Either[String, ApiSubscription]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    if (action == WriteAction.Create) {
      subscription.subscriptionKind match {
        case ApiKind.Apikey => createNewApikeyFromPlan(api, plan, subscription)
        case _              => subscription.rightf
      }
    } else if (action == Update) {
      subscription.subscriptionKind match {
        case ApiKind.Apikey if plan.status == ApiPlanStatus.Closed =>
          (if (isDraft)
             env.datastores.draftsDataStore.delete(subscription.id)
           else
             env.datastores.apiSubscriptionDataStore.delete(subscription.id))
            .map(_ => subscription.right)
        case ApiKind.Apikey                                        =>
          updateApikeyFromPlan(api, plan, subscription)
            .map(results =>
              results.collectFirst { case Left(err) => err } match {
                case Some(err) => Left(err)
                case None      => subscription.right
              }
            )
        case _                                                     => subscription.rightf
      }
    } else {
      subscription.rightf
    }
  }

  private def findDraft[A](id: String, fmt: Reads[A])(implicit env: Env): Future[Option[A]] =
    env.proxyState
      .allDrafts()
      .find(_.id == id)
      .flatMap(draft => fmt.reads(draft.content).asOpt)
      .future

  private def findApi(apiId: String, isDraft: Boolean)(implicit env: Env, ec: ExecutionContext): Future[Option[Api]] = {
    if (isDraft) findDraft(apiId, Api.format)
    else env.datastores.apiDataStore.findById(apiId)
  }

  def validate(apiRef: String, entity: ApiSubscription, action: WriteAction, isDraft: Boolean)(implicit
      env: Env
  ): Future[Either[String, ApiSubscription]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext

    findApi(apiRef, isDraft)
      .flatMap {
        case Some(api) if api.state == ApiStaging || api.state == ApiPublished =>
          api.plans.find(_.id == entity.planRef) match {
            case None                                                                                         =>
              "plan not found".leftf
            case Some(plan) if plan.status == ApiPlanStatus.Staging || plan.status == ApiPlanStatus.Published =>
              handleSubscriptionChanged(api, plan, entity, action, isDraft)
            case _                                                                                            =>
              "wrong status plan".leftf
          }
        case _                                                                 => "wrong status api".leftf
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

    implicit val ec = env.otoroshiExecutionContext
    implicit val e  = env

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

  def deleteValidator(
      subscription: ApiSubscription,
      body: JsValue,
      singularName: String,
      id: String,
      action: DeleteAction,
      env: Env
  ): Future[Either[JsValue, Unit]] = {
//    implicit val ec = env.otoroshiExecutionContext
//    implicit val e  = env

    ().rightf
  }

  val format: Format[ApiSubscription] = new Format[ApiSubscription] {
    override def reads(json: JsValue): JsResult[ApiSubscription] = Try {
      ApiSubscription(
        location = json.select("location").as(EntityLocation.format),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asString,
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
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
        dates = json.select("dates").as(ApiSubscriptionDates._fmt),
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
        tokenRefs = json.select("token_refs").asOpt[Seq[JsValue]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: ApiSubscription): JsValue             = Json.obj(
      "location"          -> o.location.json,
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
      "token_refs"        -> o.tokenRefs
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
}

case class ApiBackend(id: String, name: String, backend: NgBackend, client: String)

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
    ),
    client = "default_backend_client"
  )

  val _fmt: Format[ApiBackend] = new Format[ApiBackend] {
    override def reads(json: JsValue): JsResult[ApiBackend] = Try {
      ApiBackend(
        id = json.select("id").asString,
        name = json.select("name").asString,
        backend = json.select("backend").as(NgBackend.fmt),
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
        client = json.select("client").asOpt(NgClientConfig.format).getOrElse(NgClientConfig.default)
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
    plans: Seq[ApiDocumentationPlan] = Seq.empty,
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

  def resolveDocumentation()(implicit env: Env, ec: ExecutionContext): Future[Option[ApiDocumentation]] = {
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

  private def buildDraftRoutes()(implicit env: Env): Future[Seq[RouteWithApi]] = {
    implicit val ec = env.otoroshiExecutionContext

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

  private def applyPlan(route: NgRoute, plan: ApiDocumentationPlan): NgRoute = {
    val plugins = plan.accessModeConfigurationType match {
      case "apikey"        =>
        Seq(
          PluginWithConfig(
            pluginId[ApikeyCalls],
            plan.accessModeConfiguration
              .map(_.asInstanceOf[ApikeyAccessModeConfiguration].json.asObject)
              .getOrElse(Json.obj())
              .deepMerge(
                NgApikeyCallsConfig(
                  mandatory = false,
                  extractors = NgApikeyExtractors(
                    otoBearer = NgApikeyExtractorOtoBearer(enabled = true),
                    basic = NgApikeyExtractorBasic(enabled = false),
                    customHeaders = NgApikeyExtractorCustomHeaders(enabled = false),
                    clientId = NgApikeyExtractorClientId(enabled = false),
                    jwt = NgApikeyExtractorJwt(enabled = false)
                  )
                ).json.asObject
              ),
            pluginIndex = PluginIndex(
              validateAccess = 2.00.some
            ).some
          )
        )
      case "jwt"           =>
        Seq(
          PluginWithConfig(
            pluginId[NgJwtUserExtractor],
            plan.accessModeConfiguration
              .map(conf => {
                NgJwtUserExtractorConfig(
                  verifier = conf.asInstanceOf[JWTAccessModeConfiguration].verifier.getOrElse(""),
                  strict = false
                ).json
              })
              .getOrElse(Json.obj())
          )
        )
      case "mtls"          =>
        Seq(
          PluginWithConfig(
            pluginId[NgHasClientCertMatchingValidator],
            NgHasClientCertMatchingValidatorConfig(
              mandatory = false
            ).json.asObject
          )
        )
      case "oauth2-local"  => Seq(PluginWithConfig(pluginId[ApikeyCalls]))
      case "oauth2-remote" =>
        Seq(
          PluginWithConfig(
            pluginId[OIDCJwtVerifier],
            plan.accessModeConfiguration
              .map(conf => {
                OIDCJwtVerifierConfig(
                  mandatory = false,
                  ref = conf.asInstanceOf[OAuth2RemoteAccessModeConfiguration].verifier
                ).json.asObject
              })
              .getOrElse(Json.obj())
          )
        )
      // "keyless"
      case _               => Seq.empty
    }

    route.copy(
      plugins = addPluginsToFlow(route.plugins, plugins)
    )
  }

  private def applyPlansPolicies(routeWithApi: RouteWithApi): NgRoute = {
    val route = plans
      .filter(plan => plan.status == ApiPlanStatus.Published)
      .foldLeft(routeWithApi.route) { case (route, plan) =>
        applyPlan(route, plan)
      }
    // TODO - replace chain of plugins by MandatoryConsumerPreset plugin

    if (
      plans
        .exists(plan =>
          plan.accessModeConfigurationType != "keyless" &&
          plan.status == ApiPlanStatus.Published
        )
    ) {
      route.copy(
        plugins = addPluginsToFlow(
          route.plugins,
          Seq(
            PluginWithConfig(
              pluginId[NgExpectedConsumer],
              Json.obj(),
              Some(PluginIndex(validateAccess = 1000.00.some))
            )
          )
        )
      )
    } else {
      route
    }

  }

  def toRoutes(implicit env: Env): Future[Seq[NgRoute]] = {
    implicit val ec = env.otoroshiExecutionContext

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
        val all                  = routeFutures ++ draftRoutes
        val apisWithPlanPolicies = all.map(applyPlansPolicies)

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

  def routeToNgRoute(apiRoute: ApiRoute, optApi: Option[Api] = None)(implicit env: Env): Future[Option[NgRoute]] = {
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
            metadata = metadata ++ Map("Otoroshi-Api-Ref" -> id),
            enabled = apiRoute.enabled,
            capture = capture,
            debugFlow = debugFlow,
            exportReporting = exportReporting,
            groups = Seq("virtual_group_for_" + id) ++ groups,
            frontend = apiRoute.frontend.copy(
              domains = apiRoute.frontend.domains
                .map(domain => s"${api.domain}${api.contextPath}${domain.path}")
                .map(NgDomainAndPath)
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

  def apiRouteToNgRoute(routeId: String)(implicit env: Env): Future[Option[NgRoute]] = {
    routes.find(_.id == routeId) match {
      case Some(apiRoute) => routeToNgRoute(apiRoute)
      case None           => None.vfuture
    }
  }
}

object Api {

  def writeValidator(
      entity: Api,
      body: JsValue,
      oldEntity: Option[(Api, JsValue)],
      singularName: String,
      id: Option[String],
      action: WriteAction,
      env: Env
  ): Future[Either[JsValue, Api]] = {

    implicit val ec = env.otoroshiExecutionContext
    implicit val e  = env

    oldEntity
      .map(old => ApiConsistencyService.applyApiChanges(old._1, entity, isDraft = false).flatMap(_.rightf))
      .getOrElse(entity.rightf)
  }

  def fromOpenApi(domain: String, openapi: String, contextPath: String, backendHostname: String, backendPath: String)(
      implicit
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
      val targets     = json.select("servers").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { server =>
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
        flows = Seq(ApiFlows.empty(env)),
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
        kind = (json \ "kind").asOpt[String].getOrElse("Api"),
        id = (json \ "id").asString,
        name = (json \ "name").asString,
        description = (json \ "description").asString,
        domain = (json \ "domain").asOpt[String].getOrElse(""),
        contextPath = (json \ "contextPath").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        visibility = (json \ "visibility")
          .asOpt[JsObject]
          .flatMap(o => ApiVisibility.format.reads(o).asOpt)
          .getOrElse(ApiVisibility.Public),
        owner = (json \ "owner").asOpt[JsObject].flatMap(o => UserRef.format.reads(o).asOpt).getOrElse(UserRef.empty),
        members = (json \ "members")
          .asOpt[Seq[JsObject]]
          .map(seq => seq.flatMap(o => UserRef.format.reads(o).asOpt))
          .getOrElse(Seq.empty),
        version = (json \ "version").asOptString.getOrElse("0.0.1"),
        debugFlow = (json \ "debug_flow").asOpt[Boolean].getOrElse(false),
        capture = (json \ "capture").asOpt[Boolean].getOrElse(false),
        exportReporting = (json \ "export_reporting").asOpt[Boolean].getOrElse(false),
        groups = (json \ "groups").asOpt[Seq[String]].getOrElse(Seq.empty),
        state = (json \ "state").asOptString
          .map {
            case "staging"    => ApiStaging
            case "published"  => ApiPublished
            case "deprecated" => ApiDeprecated
            case "removed"    => ApiRemoved
            case _            => ApiStaging
          }
          .getOrElse(ApiStaging),
        enabled = (json \ "enabled").asOptBoolean.getOrElse(true),
        blueprint = (json \ " blueprint").asOptString
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
          .getOrElse(Seq.empty),
        backends = (json \ "backends")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiBackend._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty),
        flows = (json \ "flows")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiFlows._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty),
        clientsBackendConfig = (json \ "clients_backend_config")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiBackendClient._fmt.reads(v).asOpt))
          .getOrElse(Seq(ApiBackendClient.defaultClient)),
        documentation = (json \ "documentation")
          .asOpt[ApiDocumentation](ApiDocumentation._fmt.reads),
        deployments = (json \ "deployments")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiDeployment._fmt.reads(v).asOpt))
          .getOrElse(Seq.empty),
        versions = json
          .select("versions")
          .asOpt[Seq[String]]
          .getOrElse(Seq.empty),
        testing = json
          .select("testing")
          .asOpt(ApiTesting._fmt.reads)
          .getOrElse(ApiTesting()),
        clients = (json \ "clients")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiClient.format.reads(v).asOpt))
          .getOrElse(Seq.empty),
        hooks = (json \ "hooks")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => ApiStateHook.format.reads(v).asOpt))
          .getOrElse(Seq.empty),
        plans = json.select("plans").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => ApiDocumentationPlan(o))
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
      backends = Seq(ApiBackend.empty(env)),
      flows = Seq(ApiFlows.empty(env)),
      clients = Seq.empty,
      clientsBackendConfig = Seq(ApiBackendClient.defaultClient),
      documentation = None,
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
  override def fmt: Format[Api]                        = Api.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
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
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .apiSubscription
      .map { template =>
        ApiSubscription.format.reads(defaultSubscription.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        defaultSubscription
      }
  }
}

class KvApiSubscriptionDataStore(redisCli: RedisLike, _env: Env)
    extends ApiSubscriptionDataStore
    with RedisLikeStore[ApiSubscription] {
  override def fmt: Format[ApiSubscription]              = ApiSubscription.format
  override def redisLike(implicit env: Env): RedisLike   = redisCli
  override def key(id: String): String                   = s"${_env.storageRoot}:apisubscriptions:$id"
  override def extractId(value: ApiSubscription): String = value.id
}
