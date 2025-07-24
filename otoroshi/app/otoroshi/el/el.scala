package otoroshi.el

import otoroshi.env.Env
import otoroshi.models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.RequestHeader

import scala.util.Try
import otoroshi.utils.http.RequestImplicits._
import kaleidoscope.*
import anticipation.Text
import otoroshi.next.extensions.HttpListenerNames
import otoroshi.next.models.NgRoute
import otoroshi.ssl.SSLImplicits.EnhancedX509Certificate
import otoroshi.utils.http.DN
import otoroshi.utils.{ReplaceAllWith, TypedMap}
import otoroshi.utils.syntax.implicits._

import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object GlobalExpressionLanguage {

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
      env = env
    )
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
      env: Env
  ): String = env.metrics.withTimer(s"el.apply") {
    // println(s"${req}:${service}:${apiKey}:${user}:${context}")
    value match {
      case v if v.contains("${") =>
        val userAgentDetails                       = attrs.get(otoroshi.plugins.Keys.UserAgentInfoKey)
        val geolocDetails                          = attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey)
        val matchedRoute                           = attrs.get(otoroshi.next.plugins.Keys.MatchedRouteKey)
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
                    apply(s"""$${${str.trim}}""", req, service, route, apiKey, user, context, attrs, env)
                  }
                  .find { str =>
                    str != "bad-expr" && !str.startsWith("no-")
                  }
                  .getOrElse(defaultValue)
              }
            case r"item.$field(.*)"              =>
              context.getOrElse(s"item.$field", s"no-item-$field")
            case r"params.$field(.*)"            =>
              context.getOrElse(s"params.$field", s"no-params-$field")

            // legacy notation
            case "date"                          => DateTime.now().toString()
            case r"date.format\('$format(.*)'\)" => DateTime.now().toString(format.s)
            case r"date.epoch_ms"                => DateTime.now().getMillis.toString
            case r"date.epoch_sec"               => TimeUnit.MILLISECONDS.toSeconds(DateTime.now().getMillis).toString

            // specific date notation
            case str if str.startsWith("date(")    =>
              str match {
                case r"date\($date(.*)\).plus_ms\($field(.*)\)"                          =>
                  DateTime.parse(date.s).plusMillis(field.s.toInt).toString()
                case r"date\($date(.*)\).plus_ms\($field(.*)\).format\('$format(.*)'\)"  =>
                  DateTime.parse(date.s).plusMillis(field.s.toInt).toString(format.s)
                case r"date\($date(.*)\).plus_ms\($field(.*)\).epoch_ms"                 =>
                  DateTime.parse(date.s).plusMillis(field.s.toInt).getMillis.toString
                case r"date\($date(.*)\).plus_ms\($field(.*)\).epoch_sec"                =>
                  TimeUnit.MILLISECONDS.toSeconds(DateTime.parse(date.s).plusMillis(field.s.toInt).getMillis).toString
                case r"date\($date(.*)\).minus_ms\($field(.*)\)"                         =>
                  DateTime.parse(date.s).minusMillis(field.s.toInt).toString()
                case r"date\($date(.*)\).minus_ms\($field(.*)\).format\('$format(.*)'\)" =>
                  DateTime.parse(date.s).minusMillis(field.s.toInt).toString(format.s)
                case r"date\($date(.*)\).minus_ms\($field(.*)\).epoch_ms"                =>
                  DateTime.parse(date.s).minusMillis(field.s.toInt).getMillis.toString
                case r"date\($date(.*)\).minus_ms\($field(.*)\).epoch_sec"               =>
                  TimeUnit.MILLISECONDS.toSeconds(DateTime.parse(date.s).minusMillis(field.s.toInt).getMillis).toString
                case r"date\($date(.*)\).format\('$format(.*)'\)"                        => DateTime.parse(date.s).toString(format.s)
                case r"date\($date(.*)\).epoch_ms"                                       => DateTime.parse(date.s).getMillis.toString
                case r"date\($date(.*)\).epoch_sec"                                      =>
                  TimeUnit.MILLISECONDS.toSeconds(DateTime.parse(date.s).getMillis).toString
              }
            // date from EL notation
            case str if str.startsWith("date_el(") =>
              str match {
                case r"date_el\($date(.*)\).plus_ms\($field(.*)\)"                          =>
                  DateTime
                    .parse(apply(s"""$${${date.s.trim}}""", req, service, route, apiKey, user, context, attrs, env))
                    .plusMillis(field.s.toInt)
                    .toString()
                case r"date_el\($date(.*)\).plus_ms\($field(.*)\).format\('$format(.*)'\)"  =>
                  DateTime
                    .parse(apply(s"""$${${date.s.trim}}""", req, service, route, apiKey, user, context, attrs, env))
                    .plusMillis(field.s.toInt)
                    .toString(format.s)
                case r"date_el\($date(.*)\).plus_ms\($field(.*)\).epoch_ms"                 =>
                  DateTime
                    .parse(apply(s"""$${${date.s.trim}}""", req, service, route, apiKey, user, context, attrs, env))
                    .plusMillis(field.s.toInt)
                    .getMillis
                    .toString
                case r"date_el\($date(.*)\).plus_ms\($field(.*)\).epoch_sec"                =>
                  TimeUnit.MILLISECONDS
                    .toSeconds(
                      DateTime
                        .parse(apply(s"""$${${date.s.trim}}""", req, service, route, apiKey, user, context, attrs, env))
                        .plusMillis(field.s.toInt)
                        .getMillis
                    )
                    .toString
                case r"date_el\($date(.*)\).minus_ms\($field(.*)\)"                         =>
                  DateTime
                    .parse(apply(s"""$${${date.s.trim}}""", req, service, route, apiKey, user, context, attrs, env))
                    .minusMillis(field.s.toInt)
                    .toString()
                case r"date_el\($date(.*)\).minus_ms\($field(.*)\).format\('$format(.*)'\)" =>
                  DateTime
                    .parse(apply(s"""$${${date.s.trim}}""", req, service, route, apiKey, user, context, attrs, env))
                    .minusMillis(field.s.toInt)
                    .toString(format.s)
                case r"date_el\($date(.*)\).minus_ms\($field(.*)\).epoch_ms"                =>
                  DateTime
                    .parse(apply(s"""$${${date.s.trim}}""", req, service, route, apiKey, user, context, attrs, env))
                    .minusMillis(field.s.toInt)
                    .getMillis
                    .toString
                case r"date_el\($date(.*)\).minus_ms\($field(.*)\).epoch_sec"               =>
                  TimeUnit.MILLISECONDS
                    .toSeconds(
                      DateTime
                        .parse(apply(s"""$${${date.s.trim}}""", req, service, route, apiKey, user, context, attrs, env))
                        .minusMillis(field.s.toInt)
                        .getMillis
                    )
                    .toString
                case r"date_el\($date(.*)\).format\('$format(.*)'\)"                        =>
                  DateTime
                    .parse(apply(s"""$${${date.s.trim}}""", req, service, route, apiKey, user, context, attrs, env))
                    .toString(format.s)
                case r"date_el\($date(.*)\).epoch_ms"                                       =>
                  DateTime
                    .parse(apply(s"""$${${date.s.trim}}""", req, service, route, apiKey, user, context, attrs, env))
                    .getMillis
                    .toString
                case r"date_el\($date(.*)\).epoch_sec"                                      =>
                  TimeUnit.MILLISECONDS
                    .toSeconds(
                      DateTime
                        .parse(apply(s"""$${${date.s.trim}}""", req, service, route, apiKey, user, context, attrs, env))
                        .getMillis
                    )
                    .toString
              }

            // relative date notation
            case r"now.format\('$format(.*)'\)"    => DateTime.now().toString(format.s)
            case r"now.epoch_ms"                   => DateTime.now().getMillis.toString
            case r"now.epoch_sec"                  => TimeUnit.MILLISECONDS.toSeconds(DateTime.now().getMillis).toString

            case r"now.plus_ms\($field(.*)\).format\('$format(.*)'\)" =>
              DateTime.now().plusMillis(field.s.toInt).toString(format.s)
            case r"now.plus_ms\($field(.*)\).epoch_ms"                => DateTime.now().plusMillis(field.s.toInt).getMillis.toString
            case r"now.plus_ms\($field(.*)\).epoch_sec"               =>
              TimeUnit.MILLISECONDS.toSeconds(DateTime.now().plusMillis(field.s.toInt).getMillis).toString
            case r"now.plus_ms\($field(.*)\)"                         => DateTime.now().plusMillis(field.s.toInt).toString()

            case r"now.minus_ms\($field(.*)\).format\('$format(.*)'\)" =>
              DateTime.now().minusMillis(field.s.toInt).toString(format.s)
            case r"now.minus_ms\($field(.*)\).epoch_ms"                => DateTime.now().minusMillis(field.s.toInt).getMillis.toString
            case r"now.minus_ms\($field(.*)\).epoch_sec"               =>
              TimeUnit.MILLISECONDS.toSeconds(DateTime.now().minusMillis(field.s.toInt).getMillis).toString
            case r"now.minus_ms\($field(.*)\)"                         => DateTime.now().minusMillis(field.s.toInt).toString()

            case "now" => DateTime.now().toString()

            case "service.domain" if service.isDefined                            => service.get._domain
            case "service.subdomain" if service.isDefined                         => service.get.subdomain
            case "service.tld" if service.isDefined                               => service.get.domain
            case "service.env" if service.isDefined                               => service.get.env
            case r"service.groups\['$field(.*)':'$dv(.*)'\]" if service.isDefined =>
              Option(service.get.groups(field.s.toInt)).getOrElse(dv.s)
            case r"service.groups\['$field(.*)'\]" if service.isDefined           =>
              Option(service.get.groups(field.s.toInt)).getOrElse(s"no-group-$field")
            case "service.id" if service.isDefined                                => service.get.id
            case "service.name" if service.isDefined                              => service.get.name
            case r"service.metadata.$field(.*):$dv(.*)" if service.isDefined      =>
              service.get.metadata.getOrElse(field.s, dv.s)
            case r"service.metadata.$field(.*)" if service.isDefined              =>
              service.get.metadata.getOrElse(field.s, s"no-meta-${field.s}")

            case r"route.domains\['$field(.*)':'$dv(.*)'\]" if route.isDefined =>
              Option(route.get.frontend.domains(field.s.toInt)).map(_.raw).getOrElse(dv.s)
            case r"route.domains\['$field(.*)'\]" if route.isDefined           =>
              Option(route.get.frontend.domains(field.s.toInt)).map(_.raw).getOrElse(s"no-domain-$field")
            case "route.id" if route.isDefined                                 => route.get.id
            case "route.name" if route.isDefined                               => route.get.name
            case r"route.metadata.$field(.*):$dv(.*)" if route.isDefined       =>
              route.get.metadata.getOrElse(field.s, dv.s)
            case r"route.metadata.$field(.*)" if route.isDefined               =>
              route.get.metadata.getOrElse(field.s, s"no-meta-${field.s}")

            case "req.fullUrl" if req.isDefined                                           =>
              s"${req.get.theProtocol(env)}://${req.get.theHost(env)}${req.get.relativeUri}"
            case "req.listener" if req.isDefined                                          =>
              attrs.get(otoroshi.plugins.Keys.CurrentListenerKey).getOrElse(HttpListenerNames.Standard)
            case "req.id" if req.isDefined                                                => req.get.id.toString
            case "req.path" if req.isDefined                                              => req.get.path
            case "req.uri" if req.isDefined                                               => req.get.relativeUri
            case "req.host" if req.isDefined                                              => req.get.theHost(env)
            case "req.domain" if req.isDefined                                            => req.get.theDomain(env)
            case "req.method" if req.isDefined                                            => req.get.method
            case "req.protocol" if req.isDefined                                          => req.get.theProtocol(env)
            case "req.ip" if req.isDefined                                                => req.get.theIpAddress(env)
            case "req.ip_address" if req.isDefined                                        => req.get.theIpAddress(env)
            case "req.secured" if req.isDefined                                           => req.get.theSecured(env).toString
            case "req.version" if req.isDefined                                           => req.get.version
            case r"req.headers.$field(.*):$defaultValue(.*)" if req.isDefined             =>
              req.get.headers.get(field.s).getOrElse(defaultValue.s)
            case r"req.headers.$field(.*)" if req.isDefined                               =>
              req.get.headers.get(field.s).getOrElse(s"no-header-${field.s}")
            case r"req.query.$field(.*):$defaultValue(.*)" if req.isDefined               =>
              req.get.getQueryString(field.s).getOrElse(defaultValue.s)
            case r"req.query.$field(.*)" if req.isDefined                                 =>
              req.get.getQueryString(field.s).getOrElse(s"no-query-${field.s}")
            case r"req.cookies.$field(.*):$defaultValue(.*)" if req.isDefined             =>
              req.get.cookies.get(field.s).map(_.value).getOrElse(defaultValue.s)
            case r"req.cookies.$field(.*)" if req.isDefined                               =>
              req.get.cookies.get(field.s).map(_.value).getOrElse(s"no-query-${field.s}")
            case r"req.pathparams.$field(.*):$defaultValue(.*)" if matchedRoute.isDefined =>
              matchedRoute.get.pathParams.getOrElse(field.s, defaultValue.s)
            case r"req.pathparams.$field(.*)" if matchedRoute.isDefined                   =>
              matchedRoute.get.pathParams.getOrElse(field.s, s"no-path-param-${field.s}")

            case "apikey.name" if apiKey.isDefined                            => apiKey.get.clientName
            case "apikey.id" if apiKey.isDefined                              => apiKey.get.clientId
            case "apikey.clientId" if apiKey.isDefined                        => apiKey.get.clientId
            case r"apikey.metadata.$field(.*):$dv(.*)" if apiKey.isDefined    =>
              apiKey.get.metadata.getOrElse(field.s, dv.s)
            case r"apikey.metadata.$field(.*)" if apiKey.isDefined            =>
              apiKey.get.metadata.getOrElse(field.s, s"no-meta-$field")
            case r"apikey.tags\['$field(.*)':'$dv(.*)'\]" if apiKey.isDefined =>
              Option(apiKey.get.tags.apply(field.s.toInt)).getOrElse(dv.s)
            case r"apikey.tags\['$field(.*)'\]" if apiKey.isDefined           =>
              Option(apiKey.get.tags.apply(field.s.toInt)).getOrElse(s"no-tag-$field")
            case r"apikey.json.pretty" if apiKey.isDefined                    =>
              apiKey.get.lightJson.prettify
            case r"apikey.json" if apiKey.isDefined                           =>
              apiKey.get.lightJson.stringify

            // for jwt comptab only
            case r"token.$field(.*).replace\('$a(.*)', '$b(.*)'\)"            =>
              context.get(field.s).map(v => v.replace(a.s, b.s)).getOrElse(s"no-token-$field")
            case r"token.$field(.*).replace\('$a(.*)','$b(.*)'\)"             =>
              context.get(field.s).map(v => v.replace(a.s, b.s)).getOrElse(s"no-token-$field")
            case r"token.$field(.*).replaceAll\('$a(.*)','$b(.*)'\)"          =>
              context.get(field.s).map(v => v.replaceAll(a.s, b.s)).getOrElse(s"no-token-$field")
            case r"token.$field(.*).replaceAll\('$a(.*)','$b(.*)'\)"          =>
              context.get(field.s).map(v => v.replaceAll(a.s, b.s)).getOrElse(s"no-token-$field")
            case r"token.$field(.*)\|token.$field2(.*):$dv(.*)"               =>
              context.get(field.s).orElse(context.get(field2.s)).getOrElse(dv.s)
            case r"token.$field(.*)\|token.$field2(.*)"                       =>
              context.get(field.s).orElse(context.get(field2.s)).getOrElse(s"no-token-$field-$field2")
            case r"token.$field(.*):$dv(.*)"                                  => context.getOrElse(field.s, dv.s)
            case r"token.$field(.*)"                                          => context.getOrElse(field.s, s"no-token-$field")

            case r"apikeyjwt.$field(.*)" if field.s.contains(".")           =>
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.at(field.s).strConvert())
                .getOrElse(s"no-path-at-$field")
            case r"apikeyjwt.$field(.*)" if field.s.contains("/")           =>
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.atPointer(field.s).strConvert())
                .getOrElse(s"no-path-at-$field")
            case r"apikeyjwt.$field(.*)" if field.s.startsWith("$.")        =>
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.atPath(field.s).strConvert())
                .getOrElse(s"no-path-at-$field")
            case r"apikeyjwt.$field(.*)"                                    =>
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.select(field.s).strConvert())
                .getOrElse(s"no-path-at-$field")
            case r"env.$field(.*):$dv(.*)" if env.elSettings.allowEnvAccess =>
              Option(System.getenv(field.s)).getOrElse(dv.s)
            case r"env.$field(.*)" if env.elSettings.allowEnvAccess         =>
              Option(System.getenv(field.s)).getOrElse(s"no-env-var-$field")

            case r"config.$field(.*):$dv(.*)" if env.elSettings.allowConfigAccess =>
              env.configuration
                .getOptionalWithFileSupport[String](field.s)
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Int](field.s).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Double](field.s).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Long](field.s).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Boolean](field.s).map(_.toString)
                )
                .getOrElse(dv.s)
            case r"config.$field(.*)" if env.elSettings.allowConfigAccess         =>
              env.configuration
                .getOptionalWithFileSupport[String](field.s)
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Int](field.s).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Double](field.s).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Long](field.s).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Boolean](field.s).map(_.toString)
                )
                .getOrElse(s"no-config-$field")

            case r"ctx.$field(.*).replace\('$a(.*)', '$b(.*)'\)"                               =>
              context.get(field.s).map(v => v.replace(a.s, b.s)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field(.*).replace\('$a(.*)','$b(.*)'\)"                                =>
              context.get(field.s).map(v => v.replace(a.s, b.s)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field(.*).replaceAll\('$a(.*)','$b(.*)'\)"                             =>
              context.get(field.s).map(v => v.replaceAll(a.s, b.s)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field(.*).replaceAll\('$a(.*)','$b(.*)'\)"                             =>
              context.get(field.s).map(v => v.replaceAll(a.s, b.s)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field(.*)\|ctx.$field2(.*):$dv(.*)"                                    =>
              context.get(field.s).orElse(context.get(field2.s)).getOrElse(dv.s)
            case r"ctx.$field(.*)\|ctx.$field2(.*)"                                            =>
              context.get(field.s).orElse(context.get(field2.s)).getOrElse(s"no-ctx-$field-$field2")
            case r"ctx.$field(.*):$dv(.*)"                                                     => context.getOrElse(field.s, dv.s)
            case r"ctx.$field(.*)"                                                             => context.getOrElse(field.s, s"no-ctx-$field")
            case r"ctx.useragent.$field(.*)" if userAgentDetails.isDefined                     =>
              val lookup: JsLookupResult = userAgentDetails.get.\(field.s)
              lookup
                .asOpt[String]
                .orElse(lookup.asOpt[Long].map(_.toString))
                .orElse(lookup.asOpt[Double].map(_.toString))
                .orElse(lookup.asOpt[Boolean].map(_.toString))
                .getOrElse(s"no-ctx-$field")
            case r"ctx.geolocation.$field(.*)" if geolocDetails.isDefined                      =>
              val lookup: JsLookupResult = geolocDetails.get.\(field.s)
              lookup
                .asOpt[String]
                .orElse(lookup.asOpt[Long].map(_.toString))
                .orElse(lookup.asOpt[Double].map(_.toString))
                .orElse(lookup.asOpt[Boolean].map(_.toString))
                .getOrElse(s"no-ctx-$field")
            case r"vault://$path(.*)"                                                          =>
              Await.result(
                env.vaults.fillSecretsAsync("el-exp", s"vault://$path")(env.otoroshiExecutionContext),
                5.seconds
              )
            case r"global_config.metadata.$name(.*)"                                           =>
              env.datastores.globalConfigDataStore
                .latest()(env.otoroshiExecutionContext, env)
                .metadata
                .getOrElse(name.s, s"no-metadata-${name}")
            case r"global_config.env.$path(.*)"                                                =>
              env.datastores.globalConfigDataStore
                .latest()(env.otoroshiExecutionContext, env)
                .env
                .at(path.s)
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
            case r"user.tokens.$field(.*)" if user.isDefined                                   =>
              user.get.token.select(field.s).asOpt[String].getOrElse(s"no-$field")
            case r"user.tokens.$field(.*):$dv(.*)" if user.isDefined                           =>
              user.get.token.select(field.s).asOpt[String].getOrElse(dv.s)
            case r"user.metadata.$field(.*):$dv(.*)" if user.isDefined                         =>
              user
                .flatMap(_.otoroshiData)
                .map(json =>
                  (json \ field.s).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case _                      => dv.s
                  }
                )
                .getOrElse(dv.s)
            case r"user.metadata.$field(.*)" if user.isDefined                                 =>
              user
                .flatMap(_.otoroshiData)
                .map(json =>
                  (json \ field.s).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case _                      => s"no-meta-$field"
                  }
                )
                .getOrElse(s"no-meta-$field")
            case r"user.profile.$field(.*):$dv(.*)" if user.isDefined                          =>
              user
                .map(_.profile)
                .map(json =>
                  (json \ field.s).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case _                      => dv.s
                  }
                )
                .getOrElse(dv.s)
            case r"user.profile.$field(.*)" if user.isDefined                                  =>
              user
                .map(_.profile)
                .map(json =>
                  (json \ field.s).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case _                      => s"no-meta-$field"
                  }
                )
                .getOrElse(s"no-profile-$field")
            case "consumer.id" if user.isDefined                                               => user.get.email
            case "consumer.id" if apiKey.isDefined                                             => apiKey.get.clientId
            case "consumer.long_id" if user.isDefined                                          => s"${user.get.realm}-${user.get.email}"
            case "consumer.long_id" if apiKey.isDefined                                        => apiKey.get.clientId
            case "consumer.name" if user.isDefined                                             => user.get.name
            case "consumer.name" if apiKey.isDefined                                           => apiKey.get.clientName
            case "consumer.kind" if user.isDefined                                             => "user"
            case "consumer.kind" if apiKey.isDefined                                           => "apikey"
            case "consumer.kind" if apiKey.isEmpty && user.isEmpty                             => "public"
            case "consumer.json.pretty" if apiKey.isEmpty && user.isEmpty                      =>
              Json.obj("kind" -> "public", "consumer" -> JsNull).prettify
            case "consumer.json.pretty" if apiKey.isDefined                                    =>
              Json.obj("kind" -> "apikey", "consumer" -> apiKey.get.lightJson).prettify
            case "consumer.json.pretty" if user.isDefined                                      =>
              Json.obj("kind" -> "user", "consumer" -> user.get.lightJson).prettify
            case "consumer.json" if apiKey.isEmpty && user.isEmpty                             =>
              Json.obj("kind" -> "public", "consumer" -> JsNull).stringify
            case "consumer.json" if apiKey.isDefined                                           =>
              Json.obj("kind" -> "apikey", "consumer" -> apiKey.get.lightJson).stringify
            case "consumer.json" if user.isDefined                                             =>
              Json.obj("kind" -> "user", "consumer" -> user.get.lightJson).stringify
            case r"consumer.metadata.$field(.*):$dv(.*)" if user.isDefined || apiKey.isDefined =>
              user
                .flatMap(_.otoroshiData)
                .orElse(apiKey.map(v => JsObject(v.metadata.view.mapValues(_.json).toMap)))
                .map(json =>
                  (json \ field.s).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case _                      => dv.s
                  }
                )
                .getOrElse(dv.s)
            case r"consumer.metadata.$field(.*)" if user.isDefined || apiKey.isDefined         =>
              user
                .flatMap(_.otoroshiData)
                .orElse(apiKey.map(v => JsObject(v.metadata.view.mapValues(_.json).toMap)))
                .map(json =>
                  (json \ field.s).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case _                      => s"no-meta-$field"
                  }
                )
                .getOrElse(s"no-meta-$field")
            case r"nbf"                                                                        => "{nbf}"
            case r"iat"                                                                        => "{iat}"
            case r"exp"                                                                        => "{exp}"

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
      env: Env
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
      env = env
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
      env: Env
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
      env = env
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
      env: Env
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
      env = env
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
      env: Env
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
      env = env
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
      env: Env
  ): JsValue = {
    value match {
      case JsObject(map)   =>
        new JsObject(map.toSeq.map {
          case (key, JsString(str))     =>
            (key, JsString(apply(str, req, service, route, apiKey, user, context, attrs, env)))
          case (key, obj @ JsObject(_)) => (key, fromJson(obj, req, service, route, apiKey, user, context, attrs, env))
          case (key, arr @ JsArray(_))  => (key, fromJson(arr, req, service, route, apiKey, user, context, attrs, env))
          case (key, v)                 => (key, v)
        }.toMap)
      case JsArray(values) =>
        new JsArray(values.map {
          case JsString(str) => JsString(apply(str, req, service, route, apiKey, user, context, attrs, env))
          case obj: JsObject => fromJson(obj, req, service, route, apiKey, user, context, attrs, env)
          case arr: JsArray  => fromJson(arr, req, service, route, apiKey, user, context, attrs, env)
          case v             => v
        })
      case JsString(str)   =>
        apply(str, req, service, route, apiKey, user, context, attrs, env) match {
          case "true"              => JsBoolean(true)
          case "false"             => JsBoolean(false)
          case r"$nbr([0-9\\.,]+)" => JsNumber(nbr.s.toDouble)
          case r"$nbr([0-9]+)"     => JsNumber(nbr.s.toInt)
          case "null"              => JsNull
          case s                   => JsString(s)

        }
      case _               => value
    }
  }
}
