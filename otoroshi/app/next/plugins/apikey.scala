package otoroshi.next.plugins

import akka.stream.Materializer
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.env.Env
import otoroshi.models.{ApiKeyConstraints, ApiKeyHelper, ApikeyLocationKind, ApikeyTuple}
import otoroshi.next.plugins.api._
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

  override def core: Boolean = true
  override def usesCallbacks: Boolean = false
  override def name: String = "Apikeys"
  override def description: Option[String] = "This plugin expects to find an apikey to allow the request to pass".some
  override def defaultConfig: Option[JsObject] = ApiKeyConstraints().json.asObject.some

  override def matches(ctx: NgRouteMatcherContext)(implicit env: Env): Boolean = {
    val constraints = configCache.get(ctx.route.id, _ => configReads.reads(ctx.config).getOrElse(NgApikeyCallsConfig()))
    if (constraints.routing.enabled) {
      if (constraints.routing.hasNoRoutingConstraints) {
        true
      } else {
        ApiKeyHelper.detectApikeyTuple(ctx.request, constraints.apiKeyConstraints, ctx.attrs) match {
          case None => true
          case Some(tuple) =>
            ctx.attrs.put(otoroshi.next.plugins.Keys.PreExtractedApikeyTupleKey -> tuple)
            ApiKeyHelper.validateApikeyTuple(ctx.request, tuple, constraints.apiKeyConstraints, ctx.route.id, ctx.attrs).applyOn { either =>
              ctx.attrs.put(otoroshi.next.plugins.Keys.PreExtractedApikeyKey -> either)
              either
            } match {
              case Left(_) => false
              case Right(apikey) => apikey.matchRouting(constraints.apiKeyConstraints.routing)
            }
        }
      }
    } else {
      true
    }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val validate = ctx.config.select("validate").asOpt[Boolean].getOrElse(false)
    val maybeUser = ctx.attrs.get(otoroshi.plugins.Keys.UserKey)
    val pass = ctx.config.select("pass_with_user").asOpt[Boolean].getOrElse(false) match {
      case true => maybeUser.isDefined
      case false => false
    }
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case None if validate && !pass => {
        val constraints = configCache.get(ctx.route.id, _ => configReads.reads(ctx.config).getOrElse(NgApikeyCallsConfig()))
        // Here are 2 + 12 datastore calls to handle quotas
        ApiKeyHelper.passWithApiKeyFromCache(ctx.request, constraints.apiKeyConstraints, ctx.attrs, ctx.route.id).map {
          case Left(result) => NgAccess.NgDenied(result)
          case Right(apikey) =>
            ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apikey)
            NgAccess.NgAllowed
        }
      }
      case _ => NgAccess.NgAllowed.vfuture
    }
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result,NgPluginHttpRequest]] = {
    ctx.attrs.get(otoroshi.next.plugins.Keys.PreExtractedApikeyTupleKey) match {
      case Some(ApikeyTuple(_, _, _, Some(location))) => {
        location.kind match {
          case ApikeyLocationKind.Header => ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers.filterNot(_._1.toLowerCase() == location.name.toLowerCase())).right.vfuture
          case ApikeyLocationKind.Query  => {
            val uri = ctx.otoroshiRequest.uri
            val newQuery = uri.rawQueryString.map(_ => uri.query().filterNot(_._1 == location.name).toString())
            val newUrl = uri.copy(rawQueryString = newQuery).toString()
            ctx.otoroshiRequest.copy(url = newUrl).right.vfuture
          }
          case ApikeyLocationKind.Cookie => ctx.otoroshiRequest.copy(cookies = ctx.otoroshiRequest.cookies.filterNot(_.name == location.name)).right.vfuture
        }
      }
      case _ => ctx.otoroshiRequest.right.vfuture
    }
  }
}

case class NgBasicAuthConstraints(
  enabled: Boolean = true,
  headerName: Option[String] = None,
  queryName: Option[String] = None
) {
  def json: JsValue =
    Json.obj( // TODO
      "enabled"    -> enabled,
      "header_name" -> headerName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "query_name"  -> queryName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
}

object NgBasicAuthConstraints {
  val format = new Format[NgBasicAuthConstraints] {
    override def writes(o: NgBasicAuthConstraints): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgBasicAuthConstraints] =
      Try { // TODO
        NgBasicAuthConstraints(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
          headerName = (json \ "header_name").asOpt[String].filterNot(_.trim.isEmpty),
          queryName = (json \ "query_name").asOpt[String].filterNot(_.trim.isEmpty)
        )
      } match {
        case Failure(err) => JsError(err.getMessage)
        case Success(value) => JsSuccess(value)
      }
  }
}

case class NgClientIdAuthConstraints(
  enabled: Boolean = true,
  headerName: Option[String] = None,
  queryName: Option[String] = None
) {
  def json: JsValue =
    Json.obj(
      "enabled"    -> enabled,
      "header_name" -> headerName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "query_name"  -> queryName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
}

object NgClientIdAuthConstraints {
  val format = new Format[NgClientIdAuthConstraints] {
    override def writes(o: NgClientIdAuthConstraints): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgClientIdAuthConstraints] =
      Try { // TODO
        NgClientIdAuthConstraints(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
          headerName = (json \ "header_name").asOpt[String].filterNot(_.trim.isEmpty),
          queryName = (json \ "query_name").asOpt[String].filterNot(_.trim.isEmpty)
        )
      } match {
        case Failure(err) => JsError(err.getMessage)
        case Success(value) => JsSuccess(value)
      }
  }
}

case class NgCustomHeadersAuthConstraints(
  enabled: Boolean = true,
  clientIdHeaderName: Option[String] = None,
  clientSecretHeaderName: Option[String] = None
) {
  def json: JsValue =
    Json.obj(
      "enabled"                -> enabled,
      "client_id_header_name"     -> clientIdHeaderName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "client_secret_header_name" -> clientSecretHeaderName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
}

object NgCustomHeadersAuthConstraints {
  val format = new Format[NgCustomHeadersAuthConstraints] {
    override def writes(o: NgCustomHeadersAuthConstraints): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgCustomHeadersAuthConstraints] =
      Try { // TODO
        NgCustomHeadersAuthConstraints(
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
          clientIdHeaderName = (json \ "client_id_header_name").asOpt[String].filterNot(_.trim.isEmpty),
          clientSecretHeaderName = (json \ "client_secret_header_name").asOpt[String].filterNot(_.trim.isEmpty)
        )
      } match {
        case Failure(err) => JsError(err.getMessage)
        case Success(value) => JsSuccess(value)
      }
  }
}

case class NgJwtAuthConstraints(
  enabled: Boolean = true,
  secretSigned: Boolean = true,
  keyPairSigned: Boolean = true,
  includeRequestAttributes: Boolean = false,
  maxJwtLifespanSecs: Option[Long] = None, //Some(10 * 365 * 24 * 60 * 60),
  headerName: Option[String] = None,
  queryName: Option[String] = None,
  cookieName: Option[String] = None
) {
  def json: JsValue = // TODO
    Json.obj(
      "enabled"                  -> enabled,
      "secretSigned"             -> secretSigned,
      "keyPairSigned"            -> keyPairSigned,
      "includeRequestAttributes" -> includeRequestAttributes,
      "maxJwtLifespanSecs"       -> maxJwtLifespanSecs.map(l => JsNumber(BigDecimal.exact(l))).getOrElse(JsNull).as[JsValue],
      "headerName"               -> headerName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "queryName"                -> queryName.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "cookieName"               -> cookieName.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
}

object NgJwtAuthConstraints {
  val format = new Format[NgJwtAuthConstraints] {
    override def writes(o: NgJwtAuthConstraints): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgJwtAuthConstraints] =
      Try { // TODO
        NgJwtAuthConstraints( // TODO
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
          secretSigned = (json \ "secretSigned").asOpt[Boolean].getOrElse(true),
          keyPairSigned = (json \ "keyPairSigned").asOpt[Boolean].getOrElse(true),
          includeRequestAttributes = (json \ "includeRequestAttributes").asOpt[Boolean].getOrElse(false),
          maxJwtLifespanSecs =
            (json \ "maxJwtLifespanSecs").asOpt[Long].filter(_ > -1), //.getOrElse(10 * 365 * 24 * 60 * 60),
          headerName = (json \ "headerName").asOpt[String].filterNot(_.trim.isEmpty),
          queryName = (json \ "queryName").asOpt[String].filterNot(_.trim.isEmpty),
          cookieName = (json \ "cookieName").asOpt[String].filterNot(_.trim.isEmpty)
        )
      } match {
        case Failure(err) => JsError(err.getMessage)
        case Success(value) => JsSuccess(value)
      }
  }
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
  def json: JsValue = gentleJson
  def gentleJson: JsValue = Json.obj("enabled" -> enabled) // TODO
    .applyOnIf(noneTagIn.nonEmpty)(obj => obj ++ Json.obj("noneTagIn" -> noneTagIn))
    .applyOnIf(oneTagIn.nonEmpty)(obj => obj ++ Json.obj("oneTagIn" -> oneTagIn))
    .applyOnIf(allTagsIn.nonEmpty)(obj => obj ++ Json.obj("allTagsIn" -> allTagsIn))
    .applyOnIf(noneMetaIn.nonEmpty)(obj => obj ++ Json.obj("noneMetaIn" -> noneMetaIn))
    .applyOnIf(oneMetaIn.nonEmpty)(obj => obj ++ Json.obj("oneMetaIn" -> oneMetaIn))
    .applyOnIf(allMetaIn.nonEmpty)(obj => obj ++ Json.obj("allMetaIn" -> allMetaIn))
    .applyOnIf(noneMetaKeysIn.nonEmpty)(obj => obj ++ Json.obj("noneMetaKeysIn" -> noneMetaKeysIn))
    .applyOnIf(oneMetaKeyIn.nonEmpty)(obj => obj ++ Json.obj("oneMetaKeyIn" -> oneMetaKeyIn))
    .applyOnIf(allMetaKeysIn.nonEmpty)(obj => obj ++ Json.obj("allMetaKeysIn" -> allMetaKeysIn))
  lazy val isActive: Boolean = !hasNoRoutingConstraints
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
  val format = new Format[NgApikeyMatcher] {
    override def writes(o: NgApikeyMatcher): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgApikeyMatcher] =
      Try {
        NgApikeyMatcher( // TODO
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          noneTagIn = (json \ "noneTagIn").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          oneTagIn = (json \ "oneTagIn").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          allTagsIn = (json \ "allTagsIn").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          noneMetaIn = (json \ "noneMetaIn").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          oneMetaIn = (json \ "oneMetaIn").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          allMetaIn = (json \ "allMetaIn").asOpt[Map[String, String]].getOrElse(Map.empty[String, String]),
          noneMetaKeysIn = (json \ "noneMetaKeysIn").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          oneMetaKeyIn = (json \ "oneMetaKeyIn").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          allMetaKeysIn = (json \ "allMetaKeysIn").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        )
      } match {
        case Failure(err) => JsError(err.getMessage)
        case Success(value) => JsSuccess(value)
      }
  }
}

case class NgApikeyExtractors(
  basic: NgBasicAuthConstraints = NgBasicAuthConstraints(),
  customHeaders: NgCustomHeadersAuthConstraints = NgCustomHeadersAuthConstraints(),
  clientId: NgClientIdAuthConstraints = NgClientIdAuthConstraints(),
  jwt: NgJwtAuthConstraints = NgJwtAuthConstraints(),
) {
  def json: JsValue = Json.obj(
    "basic"         -> basic.json,
    "custom_headers" -> customHeaders.json,
    "client_id"      -> clientId.json,
    "jwt"           -> jwt.json,
  )
}

object NgApikeyExtractors {
  val format = new Format[NgApikeyExtractors] {
    override def writes(o: NgApikeyExtractors): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgApikeyExtractors] =
      Try { // TODO
        NgApikeyExtractors(
          basic = (json \ "basic").as(NgBasicAuthConstraints.format),
          customHeaders = (json \ "custom_headers").as(NgCustomHeadersAuthConstraints.format),
          clientId = (json \ "client_id").as(NgClientIdAuthConstraints.format),
          jwt = (json \ "jwt").as(NgJwtAuthConstraints.format),
        )
      } match {
        case Failure(err) => JsError(err.getMessage)
        case Success(value) => JsSuccess(value)
      }
  }
}

case class NgApikeyCallsConfig(
  extractors: NgApikeyExtractors = NgApikeyExtractors(),
  routing: NgApikeyMatcher = NgApikeyMatcher(),
) {
  def json: JsValue = Json.obj(
    "extractors" -> extractors.json,
    "routing" -> routing.json,
  )
  lazy val apiKeyConstraints: ApiKeyConstraints = ApiKeyConstraints(
    // TODO
  )
}

object NgApikeyCallsConfig {
  val format = new Format[NgApikeyCallsConfig] {
    override def writes(o: NgApikeyCallsConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgApikeyCallsConfig] =
      Try { // TODO
        NgApikeyCallsConfig(
          extractors = (json \ "extractors").as(NgApikeyExtractors.format),
          routing = (json \ "routing").as(NgApikeyMatcher.format)
        )
      } match {
        case Failure(err) => JsError(err.getMessage)
        case Success(value) => JsSuccess(value)
      }
  }
  def from(constraints: ApiKeyConstraints): NgApikeyCallsConfig = NgApikeyCallsConfig(
    // TODO
  )
}