package otoroshi.next.plugins

import akka.stream.Materializer
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.plugins.api._
import otoroshi.next.utils.JsonHelpers
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ApikeyCalls extends NgAccessValidator with NgRequestTransformer with NgRouteMatcher {

  private val configCache: Cache[String, NgApikeyCallsConfig] = Scaffeine()
    .expireAfterWrite(5.seconds)
    .maximumSize(1000)
    .build()

  private val configReads: Reads[NgApikeyCallsConfig] = NgApikeyCallsConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.MatchRoute, NgStep.ValidateAccess, NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = false
  override def isAccessAsync: Boolean                      = true
  override def name: String                                = "Apikeys"
  override def description: Option[String]                 = "This plugin expects to find an apikey to allow the request to pass".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgApikeyCallsConfig().some

  override def matches(ctx: NgRouteMatcherContext)(implicit env: Env): Boolean = {
    val config =
      configCache.get(ctx.route.cacheableId, _ => configReads.reads(ctx.config).getOrElse(NgApikeyCallsConfig()))
    if (config.routing.enabled) {
      if (config.routing.hasNoRoutingConstraints) {
        true
      } else {
        ApiKeyHelper.detectApikeyTuple(ctx.request, config.legacy, ctx.attrs) match {
          case None        => true
          case Some(tuple) =>
            ctx.attrs.put(otoroshi.next.plugins.Keys.PreExtractedApikeyTupleKey -> tuple)
            ApiKeyHelper.validateApikeyTuple(ctx.request, tuple, config.legacy, ctx.route.id, ctx.attrs).applyOn {
              either =>
                ctx.attrs.put(otoroshi.next.plugins.Keys.PreExtractedApikeyKey -> either)
                either
            } match {
              case Left(_)       => false
              case Right(apikey) => apikey.matchRouting(config.legacy.routing)
            }
        }
      }
    } else {
      true
    }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config    =
      configCache.get(ctx.route.cacheableId, _ => configReads.reads(ctx.config).getOrElse(NgApikeyCallsConfig()))
    val maybeUser = ctx.attrs.get(otoroshi.plugins.Keys.UserKey)
    (config.passWithUser match {
      case true  =>
        maybeUser match {
          case Some(_) => true.future
          case None    =>
            PrivateAppsUserHelper.isPrivateAppsSessionValid(ctx.request, ctx.route.legacy, ctx.attrs).map {
              case Some(user) =>
                ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> user)
                true
              case None       => false
            }
        }
      case false => false.future
    }).flatMap { pass =>
      ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
        case None if config.validate && !pass => {
          // Here are 2 + 12 datastore calls to handle quotas
          val routeId = ctx.route.cacheableId // handling route groups
          ApiKeyHelper.passWithApiKeyFromCache(ctx.request, config.legacy, ctx.attrs, routeId).map {
            case Left(result)  => NgAccess.NgDenied(result)
            case Right(apikey) =>
              ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
              NgAccess.NgAllowed
          }
        }
        case _                                => NgAccess.NgAllowed.vfuture
      }
    }
  }

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config =
      configCache.get(ctx.route.cacheableId, _ => configReads.reads(ctx.config).getOrElse(NgApikeyCallsConfig()))
    if (config.wipeBackendRequest) {
      ctx.attrs.get(otoroshi.next.plugins.Keys.PreExtractedApikeyTupleKey) match {
        case Some(ApikeyTuple(_, _, _, Some(location))) => {
          location.kind match {
            case ApikeyLocationKind.Header =>
              ctx.otoroshiRequest
                .copy(headers =
                  ctx.otoroshiRequest.headers.filterNot(_._1.toLowerCase() == location.name.toLowerCase())
                )
                .right
            case ApikeyLocationKind.Query  => {
              val uri      = ctx.otoroshiRequest.uri
              val newQuery = uri.rawQueryString.map(_ => uri.query().filterNot(_._1 == location.name).toString())
              val newUrl   = uri.copy(rawQueryString = newQuery).toString()
              ctx.otoroshiRequest.copy(url = newUrl).right
            }
            case ApikeyLocationKind.Cookie =>
              ctx.otoroshiRequest.copy(cookies = ctx.otoroshiRequest.cookies.filterNot(_.name == location.name)).right
          }
        }
        case _                                          => ctx.otoroshiRequest.right
      }
    } else {
      ctx.otoroshiRequest.right
    }
  }
}

case class NgApikeyExtractorBasic(
    enabled: Boolean = true,
    headerName: Option[String] = None,
    queryName: Option[String] = None
) {
  lazy val legacy: BasicAuthConstraints = BasicAuthConstraints(
    enabled = enabled,
    headerName = headerName,
    queryName = queryName
  )
  def json: JsValue                     = Json.obj(
    "enabled"     -> enabled,
    "header_name" -> headerName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "query_name"  -> queryName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
  )
}

object NgApikeyExtractorBasic {
  val format                                                      = new Format[NgApikeyExtractorBasic] {
    override def writes(o: NgApikeyExtractorBasic): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgApikeyExtractorBasic] = JsonHelpers.reader {
      NgApikeyExtractorBasic(
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        headerName = (json \ "header_name").asOpt[String].filterNot(_.trim.isEmpty),
        queryName = (json \ "query_name").asOpt[String].filterNot(_.trim.isEmpty)
      )
    }
  }
  def fromLegacy(s: BasicAuthConstraints): NgApikeyExtractorBasic = NgApikeyExtractorBasic(
    enabled = s.enabled,
    headerName = s.headerName,
    queryName = s.queryName
  )
}

case class NgApikeyExtractorClientId(
    enabled: Boolean = true,
    headerName: Option[String] = None,
    queryName: Option[String] = None
) {
  lazy val legacy: ClientIdAuthConstraints = ClientIdAuthConstraints(
    enabled = enabled,
    headerName = headerName,
    queryName = queryName
  )
  def json: JsValue                        = Json.obj(
    "enabled"     -> enabled,
    "header_name" -> headerName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "query_name"  -> queryName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
  )
}

object NgApikeyExtractorClientId {
  val format                                                            = new Format[NgApikeyExtractorClientId] {
    override def writes(o: NgApikeyExtractorClientId): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgApikeyExtractorClientId] = JsonHelpers.reader {
      NgApikeyExtractorClientId(
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        headerName = (json \ "header_name").asOpt[String].filterNot(_.trim.isEmpty),
        queryName = (json \ "query_name").asOpt[String].filterNot(_.trim.isEmpty)
      )
    }
  }
  def fromLegacy(s: ClientIdAuthConstraints): NgApikeyExtractorClientId = NgApikeyExtractorClientId(
    enabled = s.enabled,
    headerName = s.headerName,
    queryName = s.queryName
  )
}

case class NgApikeyExtractorCustomHeaders(
    enabled: Boolean = true,
    clientIdHeaderName: Option[String] = None,
    clientSecretHeaderName: Option[String] = None
) {
  lazy val legacy: CustomHeadersAuthConstraints = CustomHeadersAuthConstraints(
    enabled = enabled,
    clientIdHeaderName = clientIdHeaderName,
    clientSecretHeaderName = clientSecretHeaderName
  )
  def json: JsValue                             = Json.obj(
    "enabled"                   -> enabled,
    "client_id_header_name"     -> clientIdHeaderName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "client_secret_header_name" -> clientSecretHeaderName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
  )
}

object NgApikeyExtractorCustomHeaders {
  val format                                                                      = new Format[NgApikeyExtractorCustomHeaders] {
    override def writes(o: NgApikeyExtractorCustomHeaders): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgApikeyExtractorCustomHeaders] = JsonHelpers.reader {
      NgApikeyExtractorCustomHeaders(
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        clientIdHeaderName = (json \ "client_id_header_name").asOpt[String].filterNot(_.trim.isEmpty),
        clientSecretHeaderName = (json \ "client_secret_header_name").asOpt[String].filterNot(_.trim.isEmpty)
      )
    }
  }
  def fromLegacy(s: CustomHeadersAuthConstraints): NgApikeyExtractorCustomHeaders = NgApikeyExtractorCustomHeaders(
    enabled = s.enabled,
    clientIdHeaderName = s.clientIdHeaderName,
    clientSecretHeaderName = s.clientSecretHeaderName
  )
}

case class NgApikeyExtractorJwt(
    enabled: Boolean = true,
    secretSigned: Boolean = true,
    keyPairSigned: Boolean = true,
    includeRequestAttrs: Boolean = false,
    maxJwtLifespanSec: Option[Long] = None, //Some(10 * 365 * 24 * 60 * 60),
    headerName: Option[String] = None,
    queryName: Option[String] = None,
    cookieName: Option[String] = None
) {
  lazy val legacy: JwtAuthConstraints = JwtAuthConstraints(
    enabled = enabled,
    secretSigned = secretSigned,
    keyPairSigned = keyPairSigned,
    includeRequestAttributes = includeRequestAttrs,
    maxJwtLifespanSecs = maxJwtLifespanSec,
    headerName = headerName,
    queryName = queryName,
    cookieName = cookieName
  )
  def json: JsValue                   = Json.obj(
    "enabled"               -> enabled,
    "secret_signed"         -> secretSigned,
    "keypair_signed"        -> keyPairSigned,
    "include_request_attrs" -> includeRequestAttrs,
    "max_jwt_lifespan_sec"  -> maxJwtLifespanSec.map(l => JsNumber(BigDecimal.exact(l))).getOrElse(JsNull).as[JsValue],
    "header_name"           -> headerName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "query_name"            -> queryName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "cookie_name"           -> cookieName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
  )
}

object NgApikeyExtractorJwt {
  val format                                                  = new Format[NgApikeyExtractorJwt] {
    override def writes(o: NgApikeyExtractorJwt): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgApikeyExtractorJwt] = JsonHelpers.reader {
      NgApikeyExtractorJwt(
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        secretSigned = (json \ "secret_signed").asOpt[Boolean].getOrElse(true),
        keyPairSigned = (json \ "keypair_signed").asOpt[Boolean].getOrElse(true),
        includeRequestAttrs = (json \ "include_request_attrs").asOpt[Boolean].getOrElse(false),
        maxJwtLifespanSec =
          (json \ "max_jwt_lifespan_sec").asOpt[Long].filter(_ > -1), //.getOrElse(10 * 365 * 24 * 60 * 60),
        headerName = (json \ "header_name").asOpt[String].filterNot(_.trim.isEmpty),
        queryName = (json \ "query_name").asOpt[String].filterNot(_.trim.isEmpty),
        cookieName = (json \ "cookie_name").asOpt[String].filterNot(_.trim.isEmpty)
      )
    }
  }
  def fromLegacy(s: JwtAuthConstraints): NgApikeyExtractorJwt = NgApikeyExtractorJwt(
    enabled = s.enabled,
    secretSigned = s.secretSigned,
    keyPairSigned = s.keyPairSigned,
    includeRequestAttrs = s.includeRequestAttributes,
    maxJwtLifespanSec = s.maxJwtLifespanSecs,
    headerName = s.headerName,
    queryName = s.queryName,
    cookieName = s.cookieName
  )
}

case class NgApikeyMatcher(
    enabled: Boolean = false,
    noneTagIn: Seq[String] = Seq.empty,
    oneTagIn: Seq[String] = Seq.empty,
    allTagsIn: Seq[String] = Seq.empty,
    noneMetaIn: Map[String, String] = Map.empty,
    oneMetaIn: Map[String, String] = Map.empty,
    allMetaIn: Map[String, String] = Map.empty,
    noneMetaKeysIn: Seq[String] = Seq.empty,
    oneMetaKeyIn: Seq[String] = Seq.empty,
    allMetaKeysIn: Seq[String] = Seq.empty
) extends {
  lazy val legacy: ApiKeyRouteMatcher       = ApiKeyRouteMatcher(
    noneTagIn = noneTagIn,
    oneTagIn = oneTagIn,
    allTagsIn = allTagsIn,
    noneMetaIn = noneMetaIn,
    oneMetaIn = oneMetaIn,
    allMetaIn = allMetaIn,
    noneMetaKeysIn = noneMetaKeysIn,
    oneMetaKeyIn = oneMetaKeyIn,
    allMetaKeysIn = allMetaKeysIn
  )
  def json: JsValue                         = gentleJson
  def gentleJson: JsValue                   = Json
    .obj("enabled" -> enabled)
    .applyOnIf(noneTagIn.nonEmpty)(obj => obj ++ Json.obj("none_tagIn" -> noneTagIn))
    .applyOnIf(oneTagIn.nonEmpty)(obj => obj ++ Json.obj("one_tag_in" -> oneTagIn))
    .applyOnIf(allTagsIn.nonEmpty)(obj => obj ++ Json.obj("all_tags_in" -> allTagsIn))
    .applyOnIf(noneMetaIn.nonEmpty)(obj => obj ++ Json.obj("none_meta_in" -> noneMetaIn))
    .applyOnIf(oneMetaIn.nonEmpty)(obj => obj ++ Json.obj("one_meta_in" -> oneMetaIn))
    .applyOnIf(allMetaIn.nonEmpty)(obj => obj ++ Json.obj("all_meta_in" -> allMetaIn))
    .applyOnIf(noneMetaKeysIn.nonEmpty)(obj => obj ++ Json.obj("none_meta_keys_in" -> noneMetaKeysIn))
    .applyOnIf(oneMetaKeyIn.nonEmpty)(obj => obj ++ Json.obj("one_meta_key_in" -> oneMetaKeyIn))
    .applyOnIf(allMetaKeysIn.nonEmpty)(obj => obj ++ Json.obj("all_meta_keys_in" -> allMetaKeysIn))
  lazy val isActive: Boolean                = !hasNoRoutingConstraints
  lazy val hasNoRoutingConstraints: Boolean =
    oneMetaIn.isEmpty &&
    allMetaIn.isEmpty &&
    oneTagIn.isEmpty &&
    allTagsIn.isEmpty &&
    noneTagIn.isEmpty &&
    noneMetaIn.isEmpty &&
    oneMetaKeyIn.isEmpty &&
    allMetaKeysIn.isEmpty &&
    noneMetaKeysIn.isEmpty
}

object NgApikeyMatcher {
  val format                                             = new Format[NgApikeyMatcher] {
    override def writes(o: NgApikeyMatcher): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgApikeyMatcher] = JsonHelpers.reader {
      NgApikeyMatcher(
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
        noneTagIn = (json \ "none_tag_in").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        oneTagIn = (json \ "one_tag_in").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        allTagsIn = (json \ "all_tags_in").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        noneMetaIn = (json \ "none_meta_in").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
        oneMetaIn = (json \ "one_meta_in").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
        allMetaIn = (json \ "all_meta_in").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
        noneMetaKeysIn = (json \ "none_meta_keys_in").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        oneMetaKeyIn = (json \ "one_meta_key_in").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        allMetaKeysIn = (json \ "all_meta_keys_in").asOpt[Seq[String]].getOrElse(Seq.empty[String])
      )
    }
  }
  def fromLegacy(s: ApiKeyRouteMatcher): NgApikeyMatcher = NgApikeyMatcher(
    enabled = false,
    noneTagIn = s.noneTagIn,
    oneTagIn = s.oneTagIn,
    allTagsIn = s.allTagsIn,
    noneMetaIn = s.noneMetaIn,
    oneMetaIn = s.oneMetaIn,
    allMetaIn = s.allMetaIn,
    noneMetaKeysIn = s.noneMetaKeysIn,
    oneMetaKeyIn = s.oneMetaKeyIn,
    allMetaKeysIn = s.allMetaKeysIn
  ).applyOnWithPredicate(_.isActive)(_.copy(enabled = true))
}

case class NgApikeyExtractors(
    basic: NgApikeyExtractorBasic = NgApikeyExtractorBasic(),
    customHeaders: NgApikeyExtractorCustomHeaders = NgApikeyExtractorCustomHeaders(),
    clientId: NgApikeyExtractorClientId = NgApikeyExtractorClientId(),
    jwt: NgApikeyExtractorJwt = NgApikeyExtractorJwt()
) {
  def json: JsValue = Json.obj(
    "basic"          -> basic.json,
    "custom_headers" -> customHeaders.json,
    "client_id"      -> clientId.json,
    "jwt"            -> jwt.json
  )
}

object NgApikeyExtractors {
  val format = new Format[NgApikeyExtractors] {
    override def writes(o: NgApikeyExtractors): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgApikeyExtractors] = JsonHelpers.reader {
      NgApikeyExtractors(
        basic = (json \ "basic").asOpt(NgApikeyExtractorBasic.format).getOrElse(NgApikeyExtractorBasic()),
        customHeaders = (json \ "custom_headers")
          .asOpt(NgApikeyExtractorCustomHeaders.format)
          .getOrElse(NgApikeyExtractorCustomHeaders()),
        clientId = (json \ "client_id").asOpt(NgApikeyExtractorClientId.format).getOrElse(NgApikeyExtractorClientId()),
        jwt = (json \ "jwt").asOpt(NgApikeyExtractorJwt.format).getOrElse(NgApikeyExtractorJwt())
      )
    }
  }
}

case class NgApikeyCallsConfig(
    extractors: NgApikeyExtractors = NgApikeyExtractors(),
    routing: NgApikeyMatcher = NgApikeyMatcher(),
    wipeBackendRequest: Boolean = true,
    validate: Boolean = true,
    passWithUser: Boolean = false
) extends NgPluginConfig {
  def json: JsValue                  = Json.obj(
    "extractors"           -> extractors.json,
    "routing"              -> routing.json,
    "validate"             -> validate,
    "pass_with_user"       -> passWithUser,
    "wipe_backend_request" -> wipeBackendRequest
  )
  lazy val legacy: ApiKeyConstraints = ApiKeyConstraints(
    basicAuth = extractors.basic.legacy,
    customHeadersAuth = extractors.customHeaders.legacy,
    clientIdAuth = extractors.clientId.legacy,
    jwtAuth = extractors.jwt.legacy,
    routing = routing.legacy
  )
}

object NgApikeyCallsConfig {
  val format                                                = new Format[NgApikeyCallsConfig] {
    override def writes(o: NgApikeyCallsConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgApikeyCallsConfig] = Try {
      NgApikeyCallsConfig(
        extractors = (json \ "extractors").asOpt(NgApikeyExtractors.format).getOrElse(NgApikeyExtractors()),
        routing = (json \ "routing").asOpt(NgApikeyMatcher.format).getOrElse(NgApikeyMatcher()),
        validate = (json \ "validate").asOpt[Boolean].getOrElse(true),
        passWithUser = (json \ "pass_with_user").asOpt[Boolean].getOrElse(false),
        wipeBackendRequest = (json \ "wipe_backend_request").asOpt[Boolean].getOrElse(true)
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   =>
        ApiKeyConstraints.format.reads(json) match {
          case s @ JsSuccess(_, _) => s.map(NgApikeyCallsConfig.fromLegacy)
          case e @ JsError(_)      =>
            err.printStackTrace()
            e
        }
    }
  }
  def fromLegacy(o: ApiKeyConstraints): NgApikeyCallsConfig = NgApikeyCallsConfig(
    extractors = NgApikeyExtractors(
      basic = NgApikeyExtractorBasic.fromLegacy(o.basicAuth),
      customHeaders = NgApikeyExtractorCustomHeaders.fromLegacy(o.customHeadersAuth),
      clientId = NgApikeyExtractorClientId.fromLegacy(o.clientIdAuth),
      jwt = NgApikeyExtractorJwt.fromLegacy(o.jwtAuth)
    ),
    routing = NgApikeyMatcher.fromLegacy(o.routing),
    validate = true,
    passWithUser = false,
    wipeBackendRequest = true
  )
}
