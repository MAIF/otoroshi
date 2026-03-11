package next.models

import akka.http.scaladsl.model.Uri
import akka.util.ByteString
import next.models.ApiKind.Keyless
import org.joda.time.DateTime
import otoroshi.api.{DeleteAction, WriteAction}
import otoroshi.env.Env
import otoroshi.models.{
  ApiKeyRotation,
  EntityIdentifier,
  EntityLocation,
  EntityLocationSupport,
  RemainingQuotas,
  Restrictions,
  RoundRobin,
  ServiceDescriptor,
  ServiceGroupIdentifier
}
import otoroshi.next.models._
import otoroshi.next.plugins._
import otoroshi.next.plugins.api.NgPluginHelper.pluginId
import otoroshi.next.plugins.api.{NgPlugin, NgPluginConfig}
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.UrlSanitizer.sanitize
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml
import play.api.libs.json._

import java.net.URI
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
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
  lazy val label: String                           = raw.select("label").asString
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

trait ApiDocumentationAccessModeConfiguration

case class JWTAccessModeConfiguration(
    verifier: Option[String] = None,
    failIfAbsent: Boolean = true,
    customResponse: Boolean = false,
    customResponseStatus: Int = 401,
    customResponseHeaders: Map[String, String] = Map.empty,
    customResponseBody: String = Json.obj("error" -> "unauthorized").stringify
) extends ApiDocumentationAccessModeConfiguration

object JWTAccessModeConfiguration {
  def fmt() = new Format[JWTAccessModeConfiguration] {
    override def reads(json: JsValue): JsResult[JWTAccessModeConfiguration] = Try {
      JWTAccessModeConfiguration(
        verifier = json.select("verifier").asOpt[String],
        failIfAbsent = json.select("fail_if_absent").asOpt[Boolean].getOrElse(true),
        customResponse = json.select("custom_response").asOpt[Boolean].getOrElse(false),
        customResponseStatus = json.select("custom_response_status").asOpt[Int].getOrElse(401),
        customResponseHeaders = json.select("custom_response_headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        customResponseBody =
          json.select("custom_response_body").asOpt[String].getOrElse(Json.obj("error" -> "unauthorized").stringify)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: JWTAccessModeConfiguration): JsValue             = Json.obj(
      "verifier"                -> o.verifier,
      "fail_if_absent"          -> o.failIfAbsent,
      "custom_response"         -> o.customResponse,
      "custom_response_status"  -> o.customResponseStatus,
      "custom_response_headers" -> o.customResponseHeaders,
      "custom_response_body"    -> o.customResponseBody
    )
  }
}

case class ApikeyAccessModeConfiguration(
    clientIdPattern: Option[String] = None,
    clientNamePattern: Option[String] = None,
    description: Option[String] = None,
    authorizedEntities: Seq[EntityIdentifier] = Seq.empty,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    allowClientIdOnly: Boolean = false,
    throttlingQuota: Long = RemainingQuotas.MaxValue,
    dailyQuota: Long = RemainingQuotas.MaxValue,
    monthlyQuota: Long = RemainingQuotas.MaxValue,
    constrainedServicesOnly: Boolean = false,
    restrictions: Restrictions = Restrictions(),
    validUntil: Option[DateTime] = None,
    rotation: ApiKeyRotation = ApiKeyRotation(),
    tags: Seq[String] = Seq.empty[String],
    metadata: Map[String, String] = Map.empty[String, String]
) extends ApiDocumentationAccessModeConfiguration

object ApikeyAccessModeConfiguration {
  def fmt: Format[ApikeyAccessModeConfiguration] =
    new Format[ApikeyAccessModeConfiguration] {
      override def reads(json: JsValue): JsResult[ApikeyAccessModeConfiguration] = Try {
        ApikeyAccessModeConfiguration(
          clientIdPattern = json.selectAsOptString("client_id_pattern"),
          clientNamePattern = json.selectAsOptString("client_name_pattern"),
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
          throttlingQuota = (json \ "throttlingQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
          dailyQuota = (json \ "dailyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
          monthlyQuota = (json \ "monthlyQuota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
          constrainedServicesOnly = (json \ "constrainedServicesOnly").asOpt[Boolean].getOrElse(false),
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

      override def writes(apk: ApikeyAccessModeConfiguration): JsValue = {
        val enabled            = apk.validUntil match {
          case Some(date) if date.isBeforeNow => false
          case _                              => apk.enabled
        }
        val authGroup: JsValue = apk.authorizedEntities
          .find {
            case ServiceGroupIdentifier(_) => true
            case _                         => false
          }
          .map(_.id)
          .map(JsString.apply)
          .getOrElse(JsNull) // simulate old behavior
        Json.obj(
          "clientIdPattern"         -> apk.clientIdPattern,
          "clientNamePattern"       -> apk.clientNamePattern,
          "description"             -> apk.description,
          "authorizedGroup"         -> authGroup,
          "authorizedEntities"      -> JsArray(apk.authorizedEntities.map(_.json)),
          "authorizations"          -> JsArray(apk.authorizedEntities.map(_.modernJson)),
          "enabled"                 -> enabled, //apk.enabled,
          "readOnly"                -> apk.readOnly,
          "allowClientIdOnly"       -> apk.allowClientIdOnly,
          "throttlingQuota"         -> apk.throttlingQuota,
          "dailyQuota"              -> apk.dailyQuota,
          "monthlyQuota"            -> apk.monthlyQuota,
          "constrainedServicesOnly" -> apk.constrainedServicesOnly,
          "restrictions"            -> apk.restrictions.json,
          "rotation"                -> apk.rotation.json,
          "validUntil"              -> apk.validUntil.map(v => JsNumber(v.toDate.getTime)).getOrElse(JsNull).as[JsValue],
          "tags"                    -> JsArray(apk.tags.map(JsString.apply)),
          "metadata"                -> JsObject(apk.metadata.filter(_._1.nonEmpty).mapValues(JsString.apply))
        )
      }
    }
}

case class ApiDocumentationPlan(raw: JsObject) {
  lazy val accessModeConfigurationType                                              = raw.selectAsOptString("access_mode_configuration_type")
  lazy val id: String                                                               = raw.selectAsString("id")
  lazy val name: String                                                             = raw.selectAsString("name")
  lazy val description: String                                                      = raw.selectAsOptString("description").getOrElse("No description")
  lazy val accessModeConfiguration: Option[ApiDocumentationAccessModeConfiguration] =
    accessModeConfigurationType match {
      case Some("apikey") => (raw \ "access_mode_configuration").asOpt(ApikeyAccessModeConfiguration.fmt)
//      case Some("mtls")  => (raw \ "access_mode_configuration").asOpt(MtlsAccessModeConfiguration.fmt)
//      case Some("keyless")  => (raw \ "access_mode_configuration").asOpt(KeylessAccessModeConfiguration.fmt)
//      case Some("oauth2")  => (raw \ "access_mode_configuration").asOpt(OAuth2AccessModeConfiguration.fmt)
      case Some("jwt")    => (raw \ "access_mode_configuration").asOpt(JWTAccessModeConfiguration.fmt)
      case _              => None
    }
  lazy val status: ApiPlanStatus                                                    = raw.selectAsOptString("status").getOrElse("published").toLowerCase match {
    case "staging"    => ApiPlanStatus.Staging
    case "published"  => ApiPlanStatus.Published
    case "deprecated" => ApiPlanStatus.Deprecated
    case "closed"     => ApiPlanStatus.Closed
  }
  lazy val tags: Seq[String]                                                        = raw.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty)
  lazy val metadata: Map[String, String]                                            = raw.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty)
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
                banner = remoteDoc.banner.orElse(doc.banner),
                plans = remoteDoc.plans ++ doc.plans
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
    plans: Seq[ApiDocumentationPlan] = Seq.empty,
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
        plans = json.select("plans").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => ApiDocumentationPlan(o)),
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
      "plans"        -> JsArray(o.plans.map(_.raw)),
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

case class ApiSubscription(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    enabled: Boolean,
    dates: ApiSubscriptionDates,
    ownerRef: String,
    planRef: String,
    apiRef: String,
    subscriptionKind: ApiKind,
    tokenRefs: Seq[String] // ref to apikey, cert, etc
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = ApiSubscription.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object ApiSubscription {

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

    env.datastores.apiDataStore
      .findById(entity.apiRef)
      .map {
        case Some(api) if api.state == ApiStaging || api.state == ApiPublished =>
          api.documentation match {
            case Some(documentation) =>
              documentation.plans.find(_.id == entity.planRef) match {
                case None                                                                                         => onError("plan not found")
                case Some(plan) if plan.status == ApiPlanStatus.Staging || plan.status == ApiPlanStatus.Published =>
                  entity.right
                case _                                                                                            => onError("wrong status plan")
              }
            case None                => onError("plan not found")
          }
        case _                                                                 => onError("wrong status api")
      }
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
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(false),
        dates = json.select("dates").as(ApiSubscriptionDates._fmt),
        ownerRef = json.selectAsString("owner_ref"),
        planRef = json.select("plan_ref").asString,
        subscriptionKind = json.select("subscription_kind").asString.toLowerCase match {
          case "apikey"  => ApiKind.Apikey
          case "mtls"    => ApiKind.Mtls
          case "keyless" => ApiKind.Keyless
          case "oauth2"  => ApiKind.OAuth2
          case "jwt"     => ApiKind.JWT
        },
        apiRef = json.select("api_ref").asString,
        tokenRefs = json.select("token_refs").asOpt[Seq[String]].getOrElse(Seq.empty)
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
      "enabled"           -> o.enabled,
      "dates"             -> ApiSubscriptionDates._fmt.writes(o.dates),
      "owner_ref"         -> o.ownerRef,
      "plan_ref"          -> o.planRef,
      "api_ref"           -> o.apiRef,
      "subscription_kind" -> o.subscriptionKind.name,
      "token_refs"        -> o.tokenRefs
    )
  }
}

case class ApiSubscriptionRef(ref: String)

trait ApiKind  {
  def name: String
}
object ApiKind {
  case object Apikey  extends ApiKind {
    override def name: String = "apikey"
  }
  case object Mtls    extends ApiKind {
    override def name: String = "mtls"
  }
  case object Keyless extends ApiKind {
    override def name: String = "keyless"
  }
  case object OAuth2  extends ApiKind {
    override def name: String = "oauth2"
  }
  case object JWT     extends ApiKind {
    override def name: String = "jwt"
  }
}

trait ApiAcessModeSettings  {
  def config: Option[NgPluginConfig] = None
  def json: JsValue                  = config
    .map(_.json.asObject)
    .getOrElse(Json.obj())
}
object ApiAcessModeSettings {
  case class Apikey(
      wipeBackendRequest: Boolean = true,
      validate: Boolean = true,
      mandatory: Boolean = true,
      passWithUser: Boolean = false,
      updateQuotas: Boolean = true
  )                                                                           extends ApiAcessModeSettings {
    override def json: JsValue = Json.obj(
      "validate"             -> validate,
      "mandatory"            -> mandatory,
      "pass_with_user"       -> passWithUser,
      "wipe_backend_request" -> wipeBackendRequest,
      "update_quotas"        -> updateQuotas
    )
  }
  case class Mtls(rawConfig: Option[NgHasClientCertMatchingValidatorConfig])  extends ApiAcessModeSettings {
    override def config = rawConfig
  }
  case class Keyless()                                                        extends ApiAcessModeSettings {}
  case class OAuth2(rawConfig: Option[NgClientCredentialTokenEndpointConfig]) extends ApiAcessModeSettings {
    override def config: Option[NgClientCredentialTokenEndpointConfig] = rawConfig
  }
  case class JWT(rawConfig: Option[NgJwtVerificationOnlyConfig])              extends ApiAcessModeSettings {
    override def config: Option[NgJwtVerificationOnlyConfig] = rawConfig
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
        client = json.select("client").as(NgClientConfig.format)
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
    flows: Seq[ApiFlows] = Seq.empty,
    clientsBackendConfig: Seq[ApiBackendClient] = Seq.empty,
    documentation: Option[ApiDocumentation] = None,
    deployments: Seq[ApiDeployment] = Seq.empty,
    clients: Seq[ApiClient] = Seq.empty,
    testing: ApiTesting
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

  def toRoutes(implicit env: Env): Future[Seq[NgRoute]] = {
    implicit val ec = env.otoroshiExecutionContext

    if (state == ApiRemoved || !enabled) {
      Seq.empty.vfuture
    } else {
      env.datastores.draftsDataStore
        .findById(id)
        .flatMap(optDraft => {
          val draftApis: Option[Api] = optDraft
            .map(draft => Api.format.reads(draft.content))
            .collect {
              case JsSuccess(draftApi, _) if draftApi.testing.enabled =>
                draftApi.copy(
                  backends = draftApi.backends.map(backend =>
                    backend
                      .copy(backend =
                        backend.backend.copy(
                          client = draftApi.clientsBackendConfig
                            .find(_.id == backend.client)
                            .map(_.client)
                            .getOrElse(NgClientConfig.default)
                        )
                      )
                  ),
                  routes = draftApi.routes.map(route =>
                    route.copy(
                      id = s"testing_route_${route.id}",
                      frontend = route.frontend
                        .copy(
                          headers =
                            route.frontend.headers + (draftApi.testing.headerKey -> draftApi.testing.headerValue)
                        )
                    )
                  )
                )
            }

          val draftRoutes =
            draftApis.map(api => api.routes.map(route => routeToNgRoute(route, api.some))).getOrElse(Seq.empty)

          Future
            .sequence(
              routes
                .filter(_ => state == ApiPublished || state == ApiDeprecated)
                .map(route => routeToNgRoute(route, this.some)) ++ draftRoutes
            )
            .map(routes => {
              val r = routes
                .collect { case Some(value) =>
                  value
                }
                .filter(_.enabled)

//              println(r.map(_.json))

              r
            })
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

  def writeValidator(
      newApi: Api,
      _body: JsValue,
      oldEntity: Option[(Api, JsValue)],
      _singularName: String,
      _id: Option[String],
      action: WriteAction,
      env: Env
  ): Future[Either[JsValue, Api]] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    implicit val e: Env               = env

    if (action == WriteAction.Update) {
      // newApi.flows.
      // keyless :
      // mtls :
      // apikey :
      // oauth2 :
      // jwt :
      newApi.rightf
    } else {
      newApi.rightf
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
      "version"                -> o.version,
      "debug_flow"             -> o.debugFlow,
      "capture"                -> o.capture,
      "export_reporting"       -> o.exportReporting,
      "groups"                 -> o.groups,
      "state"                  -> o.state.name,
      "enabled"                -> o.enabled,
      "blueprint"              -> o.blueprint.name,
      "routes"                 -> o.routes.map(ApiRoute._fmt.writes),
      "backends"               -> o.backends.map(ApiBackend._fmt.writes),
      "flows"                  -> o.flows.map(ApiFlows._fmt.writes),
      "clients_backend_config" -> (if (o.clientsBackendConfig.isEmpty) { Seq(ApiBackendClient.defaultClient) }
                                   else o.clientsBackendConfig).map(ApiBackendClient._fmt.writes),
      "documentation"          -> o.documentation.map(ApiDocumentation._fmt.writes),
      "deployments"            -> o.deployments.map(ApiDeployment._fmt.writes),
      "versions"               -> o.versions,
      "testing"                -> ApiTesting._fmt.writes(o.testing),
      "clients"                -> o.clients.map(ApiClient.format.writes)
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
      enabled = true,
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
