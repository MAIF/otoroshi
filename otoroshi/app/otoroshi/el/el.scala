package otoroshi.el

import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import next.models.{Api, ApiDocumentationPlan}
import otoroshi.next.extensions.HttpListenerNames
import otoroshi.next.models.NgRoute
import otoroshi.security.IdGenerator
import otoroshi.ssl.SSLImplicits.EnhancedX509Certificate
import otoroshi.utils.http.DN
import otoroshi.utils.http.RequestImplicits.given
import otoroshi.utils.syntax.implicits.given
import otoroshi.utils.{ReplaceAllWith, TypedMap}
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.RequestHeader

import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

// Regex extractors replacing kaleidoscope `r"..."` patterns. Dots are kept unescaped to mirror
// kaleidoscope's verbatim semantics (where unescaped `.` is regex any-char), preserving the
// pre-migration behavior exactly. Each `private val P = """...""".r` is used directly as a
// pattern via `Regex.unapplySeq`, e.g. `case P(field) => ...`.
private object ELPatterns {
  // single-capture prefix lookups
  val Item             = """item.(.*)""".r
  val Params           = """params.(.*)""".r
  val Token            = """token.(.*)""".r
  val TokenWithDv      = """token.(.*):(.*)""".r
  val Ctx              = """ctx.(.*)""".r
  val CtxWithDv        = """ctx.(.*):(.*)""".r
  val CtxUserAgent     = """ctx.useragent.(.*)""".r
  val CtxGeolocation   = """ctx.geolocation.(.*)""".r
  val EnvVar           = """env.(.*)""".r
  val EnvVarWithDv     = """env.(.*):(.*)""".r
  val Cfg              = """config.(.*)""".r
  val CfgWithDv        = """config.(.*):(.*)""".r
  val ApikeyJwt        = """apikeyjwt.(.*)""".r

  // date/now bare and parameterized forms
  val DateFormatNow    = """date.format\('(.*)'\)""".r
  val NowFormat        = """now.format\('(.*)'\)""".r
  val NowPlusFmt       = """now.plus_ms\((.*)\).format\('(.*)'\)""".r
  val NowPlusEpochMs   = """now.plus_ms\((.*)\).epoch_ms""".r
  val NowPlusEpochSec  = """now.plus_ms\((.*)\).epoch_sec""".r
  val NowPlus          = """now.plus_ms\((.*)\)""".r
  val NowMinusFmt      = """now.minus_ms\((.*)\).format\('(.*)'\)""".r
  val NowMinusEpochMs  = """now.minus_ms\((.*)\).epoch_ms""".r
  val NowMinusEpochSec = """now.minus_ms\((.*)\).epoch_sec""".r
  val NowMinus         = """now.minus_ms\((.*)\)""".r

  // date(x).…
  val DatePlusFmt      = """date\((.*)\).plus_ms\((.*)\).format\('(.*)'\)""".r
  val DatePlusEpochMs  = """date\((.*)\).plus_ms\((.*)\).epoch_ms""".r
  val DatePlusEpochSec = """date\((.*)\).plus_ms\((.*)\).epoch_sec""".r
  val DatePlus         = """date\((.*)\).plus_ms\((.*)\)""".r
  val DateMinusFmt     = """date\((.*)\).minus_ms\((.*)\).format\('(.*)'\)""".r
  val DateMinusEpochMs = """date\((.*)\).minus_ms\((.*)\).epoch_ms""".r
  val DateMinusEpochSec= """date\((.*)\).minus_ms\((.*)\).epoch_sec""".r
  val DateMinus        = """date\((.*)\).minus_ms\((.*)\)""".r
  val DateFormat       = """date\((.*)\).format\('(.*)'\)""".r
  val DateEpochMs      = """date\((.*)\).epoch_ms""".r
  val DateEpochSec     = """date\((.*)\).epoch_sec""".r

  // date_el(x).…  (mirror of date(x).… but with EL-resolved date arg)
  val DateElPlusFmt      = """date_el\((.*)\).plus_ms\((.*)\).format\('(.*)'\)""".r
  val DateElPlusEpochMs  = """date_el\((.*)\).plus_ms\((.*)\).epoch_ms""".r
  val DateElPlusEpochSec = """date_el\((.*)\).plus_ms\((.*)\).epoch_sec""".r
  val DateElPlus         = """date_el\((.*)\).plus_ms\((.*)\)""".r
  val DateElMinusFmt     = """date_el\((.*)\).minus_ms\((.*)\).format\('(.*)'\)""".r
  val DateElMinusEpochMs = """date_el\((.*)\).minus_ms\((.*)\).epoch_ms""".r
  val DateElMinusEpochSec= """date_el\((.*)\).minus_ms\((.*)\).epoch_sec""".r
  val DateElMinus        = """date_el\((.*)\).minus_ms\((.*)\)""".r
  val DateElFormat       = """date_el\((.*)\).format\('(.*)'\)""".r
  val DateElEpochMs      = """date_el\((.*)\).epoch_ms""".r
  val DateElEpochSec     = """date_el\((.*)\).epoch_sec""".r

  // service.* / route.* lookup with bracketed quoted indices
  val ServiceGroupsWithDv  = """service.groups\['(.*)':'(.*)'\]""".r
  val ServiceGroupsNoDv    = """service.groups\['(.*)'\]""".r
  val ServiceMetaWithDv    = """service.metadata.(.*):(.*)""".r
  val ServiceMetaNoDv      = """service.metadata.(.*)""".r
  val RouteDomainsWithDv   = """route.domains\['(.*)':'(.*)'\]""".r
  val RouteDomainsNoDv     = """route.domains\['(.*)'\]""".r
  val RouteMetaWithDv      = """route.metadata.(.*):(.*)""".r
  val RouteMetaNoDv        = """route.metadata.(.*)""".r

  // req.* lookup family
  val ReqHeadersWithDv     = """req.headers.(.*):(.*)""".r
  val ReqHeadersNoDv       = """req.headers.(.*)""".r
  val ReqQueryWithDv       = """req.query.(.*):(.*)""".r
  val ReqQueryNoDv         = """req.query.(.*)""".r
  val ReqCookiesWithDv     = """req.cookies.(.*):(.*)""".r
  val ReqCookiesNoDv       = """req.cookies.(.*)""".r
  val ReqPathParamsWithDv  = """req.pathparams.(.*):(.*)""".r
  val ReqPathParamsNoDv    = """req.pathparams.(.*)""".r

  // apikey.* additional patterns
  val ApikeyMetaWithDv     = """apikey.metadata.(.*):(.*)""".r
  val ApikeyMetaNoDv       = """apikey.metadata.(.*)""".r
  val ApikeyTagsWithDv     = """apikey.tags\['(.*)':'(.*)'\]""".r
  val ApikeyTagsNoDv       = """apikey.tags\['(.*)'\]""".r

  // token / ctx replace family
  val TokenReplaceSpace    = """token.(.*).replace\('(.*)', '(.*)'\)""".r
  val TokenReplaceNoSpace  = """token.(.*).replace\('(.*)','(.*)'\)""".r
  val TokenReplaceAll      = """token.(.*).replaceAll\('(.*)','(.*)'\)""".r
  val TokenAlt             = """token.(.*)\|token.(.*)""".r
  val TokenAltWithDv       = """token.(.*)\|token.(.*):(.*)""".r
  val CtxReplaceSpace      = """ctx.(.*).replace\('(.*)', '(.*)'\)""".r
  val CtxReplaceNoSpace    = """ctx.(.*).replace\('(.*)','(.*)'\)""".r
  val CtxReplaceAll        = """ctx.(.*).replaceAll\('(.*)','(.*)'\)""".r
  val CtxAlt               = """ctx.(.*)\|ctx.(.*)""".r
  val CtxAltWithDv         = """ctx.(.*)\|ctx.(.*):(.*)""".r

  // jwt patterns
  val InJwtWithDv          = """in_jwt.(.*):(.*)""".r
  val InJwtNoDv            = """in_jwt.(.*)""".r
  val OutJwtWithDv         = """out_jwt.(.*):(.*)""".r
  val OutJwtNoDv           = """out_jwt.(.*)""".r

  // misc
  val Vault                = """vault://(.*)""".r
  val GlobalConfigMeta     = """global_config.metadata.(.*)""".r
  val GlobalConfigEnv      = """global_config.env.(.*)""".r

  // user.* token / metadata / profile families
  val UserTokensNoDv       = """user.tokens.(.*)""".r
  val UserTokensWithDv     = """user.tokens.(.*):(.*)""".r
  val UserMetaWithDv       = """user.metadata.(.*):(.*)""".r
  val UserMetaNoDv         = """user.metadata.(.*)""".r
  val UserProfileWithDv    = """user.profile.(.*):(.*)""".r
  val UserProfileNoDv      = """user.profile.(.*)""".r

  // consumer.metadata
  val ConsumerMetaWithDv   = """consumer.metadata.(.*):(.*)""".r
  val ConsumerMetaNoDv     = """consumer.metadata.(.*)""".r

  // numeric coercion (used in JSON typing)
  val Decimal              = """([0-9\.,]+)""".r
  val Integer              = """([0-9]+)""".r
}

object GlobalExpressionLanguage {

  import ELPatterns.*

  lazy val logger: Logger = Logger("otoroshi-global-el")

  val expressionReplacer: ReplaceAllWith = ReplaceAllWith("\\$\\{([^}]*)\\}")

  def applyOutsideContext(
      value: String,
      env: Env,
      context: Map[String, String] = Map.empty,
      attrs: TypedMap = TypedMap.empty
  ): String = {
    apply(
      value = value,
      req = None,
      service = None,
      route = None,
      apiKey = None,
      user = None,
      context =
        Some(context).filter(_.nonEmpty).getOrElse(attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty)),
      attrs = attrs,
      env = env
    )
  }

  def apply(
      value: String,
      attrs: TypedMap,
      env: Env
  ): String = {
    apply(
      value = value,
      req = attrs.get(otoroshi.plugins.Keys.RequestKey),
      service = attrs.get(otoroshi.next.plugins.Keys.RouteKey).map(_.legacy),
      route = attrs.get(otoroshi.next.plugins.Keys.RouteKey),
      apiKey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
      user = attrs.get(otoroshi.plugins.Keys.UserKey),
      context = attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
      attrs = attrs,
      env = env,
      plan = attrs.get(otoroshi.plugins.Keys.PlanKey),
      api = attrs.get(otoroshi.plugins.Keys.ApiKey)
    )
  }

  private def jsValueToString(value: JsValue): String = value match {
    case JsString(s)       => s
    case JsNumber(s)       => s.toString()
    case JsTrue            => "true"
    case JsFalse           => "false"
    case JsNull            => "null"
    case obj @ JsObject(_) => obj.stringify
    case arr @ JsArray(_)  => arr.stringify
  }

  def apply(
      value: String,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      route: Option[NgRoute],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: TypedMap,
      env: Env,
      plan: Option[ApiDocumentationPlan] = None,
      api: Option[Api] = None
  ): String = env.metrics.withTimer(s"el.apply") {

    given Env = env
    // println(s"${req}:${service}:${apiKey}:${user}:${context}")
    value match {
      case v if v.contains("${") =>
        val userAgentDetails                       = attrs.get(otoroshi.plugins.Keys.UserAgentInfoKey)
        val geolocDetails                          = attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey)
        val matchedRoute                           = attrs.get(otoroshi.next.plugins.Keys.MatchedRouteKey)
        val matchedInputJwtToken                   = attrs.get(otoroshi.plugins.Keys.MatchedInputTokenKey)
        val matchedOutputJwtToken                  = attrs.get(otoroshi.plugins.Keys.MatchedOutputTokenKey)
        val matchedRawInputToken                   = attrs.get(otoroshi.plugins.Keys.MatchedRawInputTokenKey)
        val matchedRawOutputToken                  = attrs.get(otoroshi.plugins.Keys.MatchedRawOutputTokenKey)
        lazy val headCert: Option[X509Certificate] = req.flatMap(_.clientCertificateChain).flatMap(_.headOption)
        Try {
          expressionReplacer.replaceOn(value) {
            case expr if expr.contains("||")     =>
              env.metrics.withTimer(s"el.apply.chain") {
                val _parts                  = expr.split("\\|\\|").toList
                val last                    = _parts.lastOption
                val hasDefaultValue         = last.exists(_.contains("::"))
                val lastParts: List[String] =
                  if (hasDefaultValue) last.map(_.split("::").toList.map(_.trim)).getOrElse(List.empty) else List.empty
                val parts: List[String]     = if (hasDefaultValue) _parts.init ++ lastParts.headOption else _parts
                val defaultValue            =
                  if (hasDefaultValue) lastParts.lastOption.getOrElse("no-chain-value-d") else "no-chain-value"
                parts.iterator
                  .map { str =>
                    apply(s"""$${${str.trim}}""", req, service, route, apiKey, user, context, attrs, env, plan, api)
                  }
                  .find { str =>
                    str != "bad-expr" && !str.startsWith("no-")
                  }
                  .getOrElse(defaultValue)
              }
            case Item(field)                     =>
              context.getOrElse(s"item.$field", s"no-item-$field")
            case Params(field)                   =>
              context.getOrElse(s"params.$field", s"no-params-$field")

            // legacy notation
            case "date"                          => DateTime.now().toString()
            case DateFormatNow(format)           => DateTime.now().toString(format)
            case "date.epoch_ms"                 => DateTime.now().getMillis.toString
            case "date.epoch_sec"                => TimeUnit.MILLISECONDS.toSeconds(DateTime.now().getMillis).toString

            // specific date notation
            case str if str.startsWith("date(")    =>
              str match {
                case DatePlus(date, field)                =>
                  DateTime.parse(date).plusMillis(field.toInt).toString()
                case DatePlusFmt(date, field, format)     =>
                  DateTime.parse(date).plusMillis(field.toInt).toString(format)
                case DatePlusEpochMs(date, field)         =>
                  DateTime.parse(date).plusMillis(field.toInt).getMillis.toString
                case DatePlusEpochSec(date, field)        =>
                  TimeUnit.MILLISECONDS.toSeconds(DateTime.parse(date).plusMillis(field.toInt).getMillis).toString
                case DateMinus(date, field)               =>
                  DateTime.parse(date).minusMillis(field.toInt).toString()
                case DateMinusFmt(date, field, format)    =>
                  DateTime.parse(date).minusMillis(field.toInt).toString(format)
                case DateMinusEpochMs(date, field)        =>
                  DateTime.parse(date).minusMillis(field.toInt).getMillis.toString
                case DateMinusEpochSec(date, field)       =>
                  TimeUnit.MILLISECONDS.toSeconds(DateTime.parse(date).minusMillis(field.toInt).getMillis).toString
                case DateFormat(date, format)             => DateTime.parse(date).toString(format)
                case DateEpochMs(date)                    => DateTime.parse(date).getMillis.toString
                case DateEpochSec(date)                   =>
                  TimeUnit.MILLISECONDS.toSeconds(DateTime.parse(date).getMillis).toString
              }
            // date from EL notation
            case str if str.startsWith("date_el(") =>
              def resolveDate(date: String): DateTime =
                DateTime.parse(apply(s"""$${${date.trim}}""", req, service, route, apiKey, user, context, attrs, env, plan, api))
              str match {
                case DateElPlus(date, field)                =>
                  resolveDate(date).plusMillis(field.toInt).toString()
                case DateElPlusFmt(date, field, format)     =>
                  resolveDate(date).plusMillis(field.toInt).toString(format)
                case DateElPlusEpochMs(date, field)         =>
                  resolveDate(date).plusMillis(field.toInt).getMillis.toString
                case DateElPlusEpochSec(date, field)        =>
                  TimeUnit.MILLISECONDS.toSeconds(resolveDate(date).plusMillis(field.toInt).getMillis).toString
                case DateElMinus(date, field)               =>
                  resolveDate(date).minusMillis(field.toInt).toString()
                case DateElMinusFmt(date, field, format)    =>
                  resolveDate(date).minusMillis(field.toInt).toString(format)
                case DateElMinusEpochMs(date, field)        =>
                  resolveDate(date).minusMillis(field.toInt).getMillis.toString
                case DateElMinusEpochSec(date, field)       =>
                  TimeUnit.MILLISECONDS.toSeconds(resolveDate(date).minusMillis(field.toInt).getMillis).toString
                case DateElFormat(date, format)             =>
                  resolveDate(date).toString(format)
                case DateElEpochMs(date)                    =>
                  resolveDate(date).getMillis.toString
                case DateElEpochSec(date)                   =>
                  TimeUnit.MILLISECONDS.toSeconds(resolveDate(date).getMillis).toString
              }

            // relative date notation
            case NowFormat(format)             => DateTime.now().toString(format)
            case "now.epoch_ms"                => DateTime.now().getMillis.toString
            case "now.epoch_sec"               => TimeUnit.MILLISECONDS.toSeconds(DateTime.now().getMillis).toString

            case NowPlusFmt(field, format)     =>
              DateTime.now().plusMillis(field.toInt).toString(format)
            case NowPlusEpochMs(field)         => DateTime.now().plusMillis(field.toInt).getMillis.toString
            case NowPlusEpochSec(field)        =>
              TimeUnit.MILLISECONDS.toSeconds(DateTime.now().plusMillis(field.toInt).getMillis).toString
            case NowPlus(field)                => DateTime.now().plusMillis(field.toInt).toString()

            case NowMinusFmt(field, format)    =>
              DateTime.now().minusMillis(field.toInt).toString(format)
            case NowMinusEpochMs(field)        => DateTime.now().minusMillis(field.toInt).getMillis.toString
            case NowMinusEpochSec(field)       =>
              TimeUnit.MILLISECONDS.toSeconds(DateTime.now().minusMillis(field.toInt).getMillis).toString
            case NowMinus(field)               => DateTime.now().minusMillis(field.toInt).toString()

            case "now" => DateTime.now().toString()

            case "service.domain" if service.isDefined                            => service.get._domain
            case "service.subdomain" if service.isDefined                         => service.get.subdomain
            case "service.tld" if service.isDefined                               => service.get.domain
            case "service.env" if service.isDefined                               => service.get.env
            case ServiceGroupsWithDv(field, dv) if service.isDefined =>
              Option(service.get.groups(field.toInt)).getOrElse(dv)
            case ServiceGroupsNoDv(field) if service.isDefined       =>
              Option(service.get.groups(field.toInt)).getOrElse(s"no-group-$field")
            case "service.id" if service.isDefined                   => service.get.id
            case "service.name" if service.isDefined                 => service.get.name
            case ServiceMetaWithDv(field, dv) if service.isDefined   =>
              service.get.metadata.getOrElse(field, dv)
            case ServiceMetaNoDv(field) if service.isDefined         =>
              service.get.metadata.getOrElse(field, s"no-meta-$field")

            case RouteDomainsWithDv(field, dv) if route.isDefined =>
              Option(route.get.frontend.domains(field.toInt)).map(_.raw).getOrElse(dv)
            case RouteDomainsNoDv(field) if route.isDefined       =>
              Option(route.get.frontend.domains(field.toInt)).map(_.raw).getOrElse(s"no-domain-$field")
            case "route.id" if route.isDefined                    => route.get.id
            case "route.name" if route.isDefined                  => route.get.name
            case "route.json.pretty" if route.isDefined           => route.get.json.prettify
            case "route.json" if route.isDefined                  => route.get.json.stringify
            case RouteMetaWithDv(field, dv) if route.isDefined    =>
              route.get.metadata.getOrElse(field, dv)
            case RouteMetaNoDv(field) if route.isDefined          =>
              route.get.metadata.getOrElse(field, s"no-meta-$field")

            case "req.fullUrl" if req.isDefined                                           =>
              s"${req.get.theProtocol}://${req.get.theHost}${req.get.relativeUri}"
            case "req.listener" if req.isDefined                                          =>
              attrs.get(otoroshi.plugins.Keys.CurrentListenerKey).getOrElse(HttpListenerNames.Standard)
            case "req.id" if req.isDefined                                                => req.get.id.toString
            case "req.path" if req.isDefined                                              => req.get.path
            case "req.uri" if req.isDefined                                               => req.get.relativeUri
            case "req.host" if req.isDefined                                              => req.get.theHost
            case "req.domain" if req.isDefined                                            => req.get.theDomain
            case "req.method" if req.isDefined                                            => req.get.method
            case "req.protocol" if req.isDefined                                          => req.get.theProtocol
            case "req.ip" if req.isDefined                                                => req.get.theIpAddress
            case "req.ip_address" if req.isDefined                                        => req.get.theIpAddress
            case "req.secured" if req.isDefined                                           => req.get.theSecured.toString
            case "req.version" if req.isDefined                                           => req.get.version
            case ReqHeadersWithDv(field, defaultValue) if req.isDefined             =>
              req.get.headers.get(field).getOrElse(defaultValue)
            case ReqHeadersNoDv(field) if req.isDefined                             =>
              req.get.headers.get(field).getOrElse(s"no-header-$field")
            case ReqQueryWithDv(field, defaultValue) if req.isDefined               =>
              req.get.getQueryString(field).getOrElse(defaultValue)
            case ReqQueryNoDv(field) if req.isDefined                               =>
              req.get.getQueryString(field).getOrElse(s"no-query-$field")
            case ReqCookiesWithDv(field, defaultValue) if req.isDefined             =>
              req.get.cookies.get(field).map(_.value).getOrElse(defaultValue)
            case ReqCookiesNoDv(field) if req.isDefined                             =>
              req.get.cookies.get(field).map(_.value).getOrElse(s"no-query-$field")
            case ReqPathParamsWithDv(field, defaultValue) if matchedRoute.isDefined =>
              matchedRoute.get.pathParams.getOrElse(field, defaultValue)
            case ReqPathParamsNoDv(field) if matchedRoute.isDefined                 =>
              matchedRoute.get.pathParams.getOrElse(field, s"no-path-param-$field")

            case "apikey.name" if apiKey.isDefined                                  => apiKey.get.clientName
            case "apikey.id" if apiKey.isDefined                                    => apiKey.get.clientId
            case "apikey.clientId" if apiKey.isDefined                              => apiKey.get.clientId
            case "apikey.json.pretty" if apiKey.isDefined                           => apiKey.get.lightJson.prettify
            case "apikey.json" if apiKey.isDefined                                  => apiKey.get.lightJson.stringify
            case ApikeyMetaWithDv(field, dv) if apiKey.isDefined  =>
              apiKey.get.metadata.get(field).getOrElse(dv)
            case ApikeyMetaNoDv(field) if apiKey.isDefined        =>
              apiKey.get.metadata.get(field).getOrElse(s"no-meta-$field")
            case ApikeyTagsWithDv(field, dv) if apiKey.isDefined  =>
              Option(apiKey.get.tags.apply(field.toInt)).getOrElse(dv)
            case ApikeyTagsNoDv(field) if apiKey.isDefined        =>
              Option(apiKey.get.tags.apply(field.toInt)).getOrElse(s"no-tag-$field")
            case "apikey.json.pretty" if apiKey.isDefined         =>
              apiKey.get.lightJson.prettify
            case "apikey.json" if apiKey.isDefined                =>
              apiKey.get.lightJson.stringify

            // for jwt comptab only
            case TokenReplaceSpace(field, a, b)                            =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-token-$field")
            case TokenReplaceNoSpace(field, a, b)                          =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-token-$field")
            case TokenReplaceAll(field, a, b)                              =>
              context.get(field).map(v => v.replaceAll(a, b)).getOrElse(s"no-token-$field")
            case TokenAltWithDv(field, field2, dv)                         =>
              context.get(field).orElse(context.get(field2)).getOrElse(dv)
            case TokenAlt(field, field2)                                   =>
              context.get(field).orElse(context.get(field2)).getOrElse(s"no-token-$field-$field2")
            case TokenWithDv(field, dv)                                    => context.getOrElse(field, dv)
            case Token(field)                                              => context.getOrElse(field, s"no-token-$field")
            case InJwtWithDv(field, dv) if matchedInputJwtToken.isDefined  =>
              val json = matchedInputJwtToken.get
              if (field.contains(".")) {
                json.at(field).asOpt[JsValue].map(v => jsValueToString(v)).getOrElse(dv)
              } else {
                json.select(field).asOpt[JsValue].map(v => jsValueToString(v)).getOrElse(dv)
              }

            case InJwtNoDv(field) if matchedInputJwtToken.isDefined        =>
              val json = matchedInputJwtToken.get
              if (field.contains(".")) {
                json.at(field).asOpt[JsValue].map(v => jsValueToString(v)).getOrElse(s"no-jwt-$field")
              } else {
                json.select(field).asOpt[JsValue].map(v => jsValueToString(v)).getOrElse(s"no-jwt-$field")
              }

            case OutJwtWithDv(field, dv) if matchedOutputJwtToken.isDefined =>
              val json = matchedOutputJwtToken.get
              if (field.contains(".")) {
                json.at(field).asOpt[JsValue].map(v => jsValueToString(v)).getOrElse(dv)
              } else {
                json.select(field).asOpt[JsValue].map(v => jsValueToString(v)).getOrElse(dv)
              }

            case OutJwtNoDv(field) if matchedOutputJwtToken.isDefined       =>
              val json = matchedOutputJwtToken.get
              if (field.contains(".")) {
                json.at(field).asOpt[JsValue].map(v => jsValueToString(v)).getOrElse(s"no-jwt-$field")
              } else {
                json.select(field).asOpt[JsValue].map(v => jsValueToString(v)).getOrElse(s"no-jwt-$field")
              }
            case "in_raw_jwt" if matchedRawInputToken.isDefined   => matchedRawInputToken.get
            case "out_raw_jwt" if matchedRawOutputToken.isDefined => matchedRawOutputToken.get

            case ApikeyJwt(field) if field.contains(".")    =>
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.at(field).strConvert())
                .getOrElse(s"no-path-at-$field")
            case ApikeyJwt(field) if field.contains("/")    =>
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.atPointer(field).strConvert())
                .getOrElse(s"no-path-at-$field")
            case ApikeyJwt(field) if field.startsWith("$.") =>
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.atPath(field).strConvert())
                .getOrElse(s"no-path-at-$field")
            case ApikeyJwt(field)                           =>
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.select(field).strConvert())
                .getOrElse(s"no-path-at-$field")
            case EnvVarWithDv(field, dv) if env.elSettings.allowEnvAccess =>
              Option(System.getenv(field)).getOrElse(dv)
            case EnvVar(field) if env.elSettings.allowEnvAccess           =>
              Option(System.getenv(field)).getOrElse(s"no-env-var-$field")

            case CfgWithDv(field, dv) if env.elSettings.allowConfigAccess =>
              env.configuration
                .getOptionalWithFileSupport[String](field)
                .orElse(env.configuration.getOptionalWithFileSupport[Int](field).map(_.toString))
                .orElse(env.configuration.getOptionalWithFileSupport[Double](field).map(_.toString))
                .orElse(env.configuration.getOptionalWithFileSupport[Long](field).map(_.toString))
                .orElse(env.configuration.getOptionalWithFileSupport[Boolean](field).map(_.toString))
                .getOrElse(dv)
            case Cfg(field) if env.elSettings.allowConfigAccess           =>
              env.configuration
                .getOptionalWithFileSupport[String](field)
                .orElse(env.configuration.getOptionalWithFileSupport[Int](field).map(_.toString))
                .orElse(env.configuration.getOptionalWithFileSupport[Double](field).map(_.toString))
                .orElse(env.configuration.getOptionalWithFileSupport[Long](field).map(_.toString))
                .orElse(env.configuration.getOptionalWithFileSupport[Boolean](field).map(_.toString))
                .getOrElse(s"no-config-$field")

            case CtxReplaceSpace(field, a, b)                =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-ctx-$field")
            case CtxReplaceNoSpace(field, a, b)              =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-ctx-$field")
            case CtxReplaceAll(field, a, b)                  =>
              context.get(field).map(v => v.replaceAll(a, b)).getOrElse(s"no-ctx-$field")
            case CtxAltWithDv(field, field2, dv)             =>
              context.get(field).orElse(context.get(field2)).getOrElse(dv)
            case CtxAlt(field, field2)                       =>
              context.get(field).orElse(context.get(field2)).getOrElse(s"no-ctx-$field-$field2")
            case CtxWithDv(field, dv)                        => context.getOrElse(field, dv)
            case Ctx(field)                                  => context.getOrElse(field, s"no-ctx-$field")
            case CtxUserAgent(field) if userAgentDetails.isDefined =>
              val lookup: JsLookupResult = userAgentDetails.get.\(field)
              lookup
                .asOpt[String]
                .orElse(lookup.asOpt[Long].map(_.toString))
                .orElse(lookup.asOpt[Double].map(_.toString))
                .orElse(lookup.asOpt[Boolean].map(_.toString))
                .getOrElse(s"no-ctx-$field")
            case CtxGeolocation(field) if geolocDetails.isDefined =>
              val lookup: JsLookupResult = geolocDetails.get.\(field)
              lookup
                .asOpt[String]
                .orElse(lookup.asOpt[Long].map(_.toString))
                .orElse(lookup.asOpt[Double].map(_.toString))
                .orElse(lookup.asOpt[Boolean].map(_.toString))
                .getOrElse(s"no-ctx-$field")
            case Vault(path)                                 =>
              Await.result(
                env.vaults.fillSecretsAsync("el-exp", s"vault://$path")(using env.otoroshiExecutionContext),
                5.seconds
              )
            case GlobalConfigMeta(name)                      =>
              env.datastores.globalConfigDataStore
                .latest()(using env.otoroshiExecutionContext, env)
                .metadata
                .getOrElse(name, s"no-metadata-$name")
            case GlobalConfigEnv(path)                       =>
              env.datastores.globalConfigDataStore
                .latest()(using env.otoroshiExecutionContext, env)
                .env
                .at(path)
                .asOpt[JsValue]
                .map {
                  case JsString(str)   => str
                  case JsNumber(nbr)   => nbr.toString()
                  case JsBoolean(bool) => bool.toString
                  case JsNull          => "null"
                  case o               => o.stringify
                }
                .getOrElse(s"no-global-env-at-$path")
            case "user.name" if user.isDefined                                                 => user.get.name
            case "user.email" if user.isDefined                                                => user.get.email
            case "user.json.pretty" if user.isDefined                                          => user.get.lightJson.prettify
            case "user.json" if user.isDefined                                                 => user.get.lightJson.stringify
            case "user.tokens.id_token" if user.isDefined                                      =>
              user.get.token.select("id_token").asOpt[String].getOrElse("no-id_token")
            case "user.tokens.access_token" if user.isDefined                                  =>
              user.get.token.select("access_token").asOpt[String].getOrElse("no-access_token")
            case "user.tokens.refresh_token" if user.isDefined                                 =>
              user.get.token.select("refresh_token").asOpt[String].getOrElse("no-refresh_token")
            case "user.tokens.token_type" if user.isDefined                                    =>
              user.get.token.select("token_type").asOpt[String].getOrElse("no-token_type")
            case "user.tokens.expires_in" if user.isDefined                                    =>
              user.get.token.select("expires_in").asOpt[String].getOrElse("no-expires_in")
            case UserTokensNoDv(field) if user.isDefined          =>
              user.get.token.select(field).asOpt[String].getOrElse(s"no-$field")
            case UserTokensWithDv(field, dv) if user.isDefined    =>
              user.get.token.select(field).asOpt[String].getOrElse(dv)
            case UserMetaWithDv(field, dv) if user.isDefined      =>
              user
                .flatMap(_.otoroshiData)
                .map(json =>
                  json.at(field).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case Some(v: JsValue)       => v.stringify
                    case _                      => dv
                  }
                )
                .getOrElse(dv)
            case UserMetaNoDv(field) if user.isDefined            =>
              user
                .flatMap(_.otoroshiData)
                .map(json =>
                  json.at(field).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case Some(v: JsValue)       => v.stringify
                    case _                      => s"no-meta-$field"
                  }
                )
                .getOrElse(s"no-meta-$field")
            case UserProfileWithDv(field, dv) if user.isDefined   =>
              user
                .map(_.profile)
                .map(json =>
                  json.at(field).asOpt[JsValue] match {
                    case Some(v: JsValue) => v.stringify
                    case _                => dv
                  }
                )
                .getOrElse(dv)
            case "rand"                                                                          => IdGenerator.token(64)
            case "plan.id" if plan.isDefined                                                     => plan.get.id
            case "plan.name" if plan.isDefined                                                   => plan.get.name
            case "api.id" if api.isDefined                                                       => api.get.id
            case "api.name" if api.isDefined                                                     => api.get.name
            case UserProfileNoDv(field) if user.isDefined =>
              user
                .map(_.profile)
                .map(json =>
                  json.at(field).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case Some(v: JsValue)       => v.stringify
                    case _                      => s"no-meta-$field"
                  }
                )
                .getOrElse(s"no-profile-$field")
            case "consumer.id" if user.isDefined                                                 => user.get.email
            case "consumer.id" if apiKey.isDefined                                               => apiKey.get.clientId
            case "consumer.long_id" if user.isDefined                                            => s"${user.get.realm}-${user.get.email}"
            case "consumer.long_id" if apiKey.isDefined                                          => apiKey.get.clientId
            case "consumer.name" if user.isDefined                                               => user.get.name
            case "consumer.name" if apiKey.isDefined                                             => apiKey.get.clientName
            case "consumer.kind" if user.isDefined                                               => "user"
            case "consumer.kind" if apiKey.isDefined                                             => "apikey"
            case "consumer.kind" if apiKey.isEmpty && user.isEmpty                               => "public"
            case "consumer.json.pretty" if apiKey.isEmpty && user.isEmpty                        =>
              Json.obj("kind" -> "public", "consumer" -> JsNull).prettify
            case "consumer.json.pretty" if apiKey.isDefined                                      =>
              Json.obj("kind" -> "apikey", "consumer" -> apiKey.get.lightJson).prettify
            case "consumer.json.pretty" if user.isDefined                                        =>
              Json.obj("kind" -> "user", "consumer" -> user.get.lightJson).prettify
            case "consumer.json" if apiKey.isEmpty && user.isEmpty                               =>
              Json.obj("kind" -> "public", "consumer" -> JsNull).stringify
            case "consumer.json" if apiKey.isDefined                                             =>
              Json.obj("kind" -> "apikey", "consumer" -> apiKey.get.lightJson).stringify
            case "consumer.json" if user.isDefined                                               =>
              Json.obj("kind" -> "user", "consumer" -> user.get.lightJson).stringify
            case ConsumerMetaWithDv(field, dv) if user.isDefined || apiKey.isDefined =>
              user
                .flatMap(_.otoroshiData)
                .orElse(apiKey.map(v => JsObject(v.metadata.view.mapValues(_.json).toMap)))
                .map(json =>
                  json.at(field).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case Some(v: JsValue)       => v.stringify
                    case _                      => dv
                  }
                )
                .getOrElse(dv)
            case ConsumerMetaNoDv(field) if user.isDefined || apiKey.isDefined       =>
              user
                .flatMap(_.otoroshiData)
                .orElse(apiKey.map(v => JsObject(v.metadata.view.mapValues(_.json).toMap)))
                .map(json =>
                  json.at(field).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case Some(v: JsValue)       => v.stringify
                    case _                      => s"no-meta-$field"
                  }
                )
                .getOrElse(s"no-meta-$field")
            case "nbf" => "{nbf}"
            case "iat" => "{iat}"
            case "exp" => "{exp}"

            case "req.client_cert.dn" if req.isDefined && headCert.isDefined                                         =>
              DN(headCert.get.getSubjectX500Principal.getName).stringify
            case "req.client_cert.id" if req.isDefined && headCert.isDefined                                         =>
              headCert.get.getSerialNumber.toString(16)
            case "req.client_cert.domain" if req.isDefined && headCert.isDefined && headCert.get.rawDomain.isDefined =>
              headCert.get.rawDomain.get
            case "req.client_cert.cn" if req.isDefined && headCert.isDefined && headCert.get.rawDomain.isDefined     =>
              headCert.get.rawDomain.get
            case "req.client_cert.issuer_dn" if req.isDefined && headCert.isDefined                                  =>
              DN(headCert.get.getIssuerX500Principal.getName).stringify
            case expr                                                                                                => "bad-expr" //s"$${$expr}"
          }
        } recover { case e =>
          logger.error(s"Error while parsing expression, returning raw value: $value", e)
          value
        } get
      case _                     => value
    }
  }
}

object HeadersExpressionLanguage {

  def apply(
      value: String,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      route: Option[NgRoute],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: TypedMap,
      env: Env,
      plan: Option[ApiDocumentationPlan] = None,
      api: Option[Api] = None
  ): String = {
    GlobalExpressionLanguage.apply(
      value = value,
      req = req.orElse(attrs.get(otoroshi.plugins.Keys.RequestKey)),
      service = service.orElse(attrs.get(otoroshi.next.plugins.Keys.RouteKey).map(_.legacy)),
      route = route.orElse(attrs.get(otoroshi.next.plugins.Keys.RouteKey)),
      apiKey = apiKey.orElse(attrs.get(otoroshi.plugins.Keys.ApiKeyKey)),
      user = user.orElse(attrs.get(otoroshi.plugins.Keys.UserKey)),
      context =
        Some(context).filter(_.nonEmpty).getOrElse(attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty)),
      attrs = attrs,
      env = env,
      plan = plan,
      api = api
    )
  }
}

object RedirectionExpressionLanguage {

  def apply(
      value: String,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      route: Option[NgRoute],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: TypedMap,
      env: Env,
      plan: Option[ApiDocumentationPlan] = None,
      api: Option[Api] = None
  ): String = {
    GlobalExpressionLanguage.apply(
      value = value,
      req = req.orElse(attrs.get(otoroshi.plugins.Keys.RequestKey)),
      service = service.orElse(attrs.get(otoroshi.next.plugins.Keys.RouteKey).map(_.legacy)),
      route = route.orElse(attrs.get(otoroshi.next.plugins.Keys.RouteKey)),
      apiKey = apiKey.orElse(attrs.get(otoroshi.plugins.Keys.ApiKeyKey)),
      user = user.orElse(attrs.get(otoroshi.plugins.Keys.UserKey)),
      context =
        Some(context).filter(_.nonEmpty).getOrElse(attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty)),
      attrs = attrs,
      env = env,
      plan,
      api
    )
  }
}

object TargetExpressionLanguage {

  def apply(
      value: String,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      route: Option[NgRoute],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: TypedMap,
      env: Env,
      plan: Option[ApiDocumentationPlan] = None,
      api: Option[Api] = None
  ): String = {
    GlobalExpressionLanguage.apply(
      value = value,
      req = req.orElse(attrs.get(otoroshi.plugins.Keys.RequestKey)),
      service = service.orElse(attrs.get(otoroshi.next.plugins.Keys.RouteKey).map(_.legacy)),
      route = route.orElse(attrs.get(otoroshi.next.plugins.Keys.RouteKey)),
      apiKey = apiKey.orElse(attrs.get(otoroshi.plugins.Keys.ApiKeyKey)),
      user = user.orElse(attrs.get(otoroshi.plugins.Keys.UserKey)),
      context =
        Some(context).filter(_.nonEmpty).getOrElse(attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty)),
      attrs = attrs,
      env = env,
      plan,
      api
    )
  }
}

object JwtExpressionLanguage {

  def apply(
      value: String,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      route: Option[NgRoute],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: TypedMap,
      env: Env,
      plan: Option[ApiDocumentationPlan] = None,
      api: Option[Api] = None
  ): String = {
    GlobalExpressionLanguage.apply(
      value = value,
      req = req.orElse(attrs.get(otoroshi.plugins.Keys.RequestKey)),
      service = service.orElse(attrs.get(otoroshi.next.plugins.Keys.RouteKey).map(_.legacy)),
      route = route.orElse(attrs.get(otoroshi.next.plugins.Keys.RouteKey)),
      apiKey = apiKey.orElse(attrs.get(otoroshi.plugins.Keys.ApiKeyKey)),
      user = user.orElse(attrs.get(otoroshi.plugins.Keys.UserKey)),
      context =
        Some(context).filter(_.nonEmpty).getOrElse(attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty)),
      attrs = attrs,
      env = env,
      plan,
      api
    )
  }

  def fromJson(
      value: JsValue,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      route: Option[NgRoute],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: TypedMap,
      env: Env,
      plan: Option[ApiDocumentationPlan] = None,
      api: Option[Api] = None
  ): JsValue = {
    value match {
      case JsObject(map)   =>
        new JsObject(map.toSeq.map {
          case (key, JsString(str))     =>
            (key, JsString(apply(str, req, service, route, apiKey, user, context, attrs, env, plan, api)))
          case (key, obj @ JsObject(_)) =>
            (key, fromJson(obj, req, service, route, apiKey, user, context, attrs, env, plan, api))
          case (key, arr @ JsArray(_))  =>
            (key, fromJson(arr, req, service, route, apiKey, user, context, attrs, env, plan, api))
          case (key, v)                 => (key, v)
        }.toMap)
      case JsArray(values) =>
        new JsArray(values.map {
          case JsString(str) => JsString(apply(str, req, service, route, apiKey, user, context, attrs, env, plan, api))
          case obj: JsObject => fromJson(obj, req, service, route, apiKey, user, context, attrs, env, plan, api)
          case arr: JsArray  => fromJson(arr, req, service, route, apiKey, user, context, attrs, env, plan, api)
          case v             => v
        })
      case JsString(str)   =>
        apply(str, req, service, route, apiKey, user, context, attrs, env, plan, api) match {
          case "true"              => JsBoolean(true)
          case "false"             => JsBoolean(false)
          case ELPatterns.Decimal(nbr) => JsNumber(nbr.toDouble)
          case ELPatterns.Integer(nbr) => JsNumber(nbr.toInt)
          case "null"              => JsNull
          case s                   => JsString(s)
        }
      case _               => value
    }
  }
}
