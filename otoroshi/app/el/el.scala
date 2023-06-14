package otoroshi.el

import otoroshi.env.Env
import otoroshi.models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.RequestHeader

import scala.util.Try
import otoroshi.utils.http.RequestImplicits._
import kaleidoscope._
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.Keys
import otoroshi.utils.{ReplaceAllWith, TypedMap}
import otoroshi.utils.syntax.implicits._

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object GlobalExpressionLanguage {

  lazy val logger = Logger("otoroshi-global-el")

  val expressionReplacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

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
    // println(s"${req}:${service}:${apiKey}:${user}:${context}")
    value match {
      case v if v.contains("${") =>
        val userAgentDetails = attrs.get(otoroshi.plugins.Keys.UserAgentInfoKey)
        val geolocDetails    = attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey)
        val matchedRoute     = attrs.get(otoroshi.next.plugins.Keys.MatchedRouteKey)
        Try {
          expressionReplacer.replaceOn(value) {
            case r"item.$field@(.*)"                                                =>
              context.getOrElse(s"item.$field", s"no-item-$field")
            case r"params.$field@(.*)"                                              =>
              context.getOrElse(s"params.$field", s"no-params-$field")
            case "date"                                                             => DateTime.now().toString()
            case r"date.format\('$format@(.*)'\)"                                   => DateTime.now().toString(format)

            case "service.domain" if service.isDefined                              => service.get._domain
            case "service.subdomain" if service.isDefined                           => service.get.subdomain
            case "service.tld" if service.isDefined                                 => service.get.domain
            case "service.env" if service.isDefined                                 => service.get.env
            case r"service.groups\['$field@(.*)':'$dv@(.*)'\]" if service.isDefined =>
              Option(service.get.groups(field.toInt)).getOrElse(dv)
            case r"service.groups\['$field@(.*)'\]" if service.isDefined            =>
              Option(service.get.groups(field.toInt)).getOrElse(s"no-group-$field")
            case "service.id" if service.isDefined                                  => service.get.id
            case "service.name" if service.isDefined                                => service.get.name
            case r"service.metadata.$field@(.*):$dv@(.*)" if service.isDefined      =>
              service.get.metadata.get(field).getOrElse(dv)
            case r"service.metadata.$field@(.*)" if service.isDefined               =>
              service.get.metadata.get(field).getOrElse(s"no-meta-$field")

            case r"route.domains\['$field@(.*)':'$dv@(.*)'\]" if route.isDefined =>
              Option(route.get.frontend.domains(field.toInt)).map(_.raw).getOrElse(dv)
            case r"route.domains\['$field@(.*)'\]" if route.isDefined =>
              Option(route.get.frontend.domains(field.toInt)).map(_.raw).getOrElse(s"no-domain-$field")
            case "route.id" if route.isDefined => route.get.id
            case "route.name" if route.isDefined => route.get.name
            case r"route.metadata.$field@(.*):$dv@(.*)" if route.isDefined =>
              route.get.metadata.get(field).getOrElse(dv)
            case r"route.metadata.$field@(.*)" if route.isDefined =>
              route.get.metadata.get(field).getOrElse(s"no-meta-$field")

            case "req.fullUrl" if req.isDefined                                             =>
              s"${req.get.theProtocol(env)}://${req.get.theHost(env)}${req.get.relativeUri}"
            case "req.path" if req.isDefined                                                => req.get.path
            case "req.uri" if req.isDefined                                                 => req.get.relativeUri
            case "req.host" if req.isDefined                                                => req.get.theHost(env)
            case "req.domain" if req.isDefined                                              => req.get.theDomain(env)
            case "req.method" if req.isDefined                                              => req.get.method
            case "req.protocol" if req.isDefined                                            => req.get.theProtocol(env)
            case r"req.headers.$field@(.*):$defaultValue@(.*)" if req.isDefined             =>
              req.get.headers.get(field).getOrElse(defaultValue)
            case r"req.headers.$field@(.*)" if req.isDefined                                =>
              req.get.headers.get(field).getOrElse(s"no-header-$field")
            case r"req.query.$field@(.*):$defaultValue@(.*)" if req.isDefined               =>
              req.get.getQueryString(field).getOrElse(defaultValue)
            case r"req.query.$field@(.*)" if req.isDefined                                  =>
              req.get.getQueryString(field).getOrElse(s"no-query-$field")
            case r"req.pathparams.$field@(.*):$defaultValue@(.*)" if matchedRoute.isDefined =>
              matchedRoute.get.pathParams.get(field).getOrElse(defaultValue)
            case r"req.pathparams.$field@(.*)" if matchedRoute.isDefined                    =>
              matchedRoute.get.pathParams.get(field).getOrElse(s"no-path-param-$field")

            case "apikey.name" if apiKey.isDefined                              => apiKey.get.clientName
            case "apikey.id" if apiKey.isDefined                                => apiKey.get.clientId
            case r"apikey.metadata.$field@(.*):$dv@(.*)" if apiKey.isDefined    =>
              apiKey.get.metadata.get(field).getOrElse(dv)
            case r"apikey.metadata.$field@(.*)" if apiKey.isDefined             =>
              apiKey.get.metadata.get(field).getOrElse(s"no-meta-$field")
            case r"apikey.tags\['$field@(.*)':'$dv@(.*)'\]" if apiKey.isDefined =>
              Option(apiKey.get.tags.apply(field.toInt)).getOrElse(dv)
            case r"apikey.tags\['$field@(.*)'\]" if apiKey.isDefined            =>
              Option(apiKey.get.tags.apply(field.toInt)).getOrElse(s"no-tag-$field")

            // for jwt comptab only
            case r"token.$field@(.*).replace\('$a@(.*)', '$b@(.*)'\)"           =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-token-$field")
            case r"token.$field@(.*).replace\('$a@(.*)','$b@(.*)'\)"            =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-token-$field")
            case r"token.$field@(.*).replaceAll\('$a@(.*)','$b@(.*)'\)"         =>
              context.get(field).map(v => v.replaceAll(a, b)).getOrElse(s"no-token-$field")
            case r"token.$field@(.*).replaceAll\('$a@(.*)','$b@(.*)'\)"         =>
              context.get(field).map(v => v.replaceAll(a, b)).getOrElse(s"no-token-$field")
            case r"token.$field@(.*)\|token.$field2@(.*):$dv@(.*)"              =>
              context.get(field).orElse(context.get(field2)).getOrElse(dv)
            case r"token.$field@(.*)\|token.$field2@(.*)"                       =>
              context.get(field).orElse(context.get(field2)).getOrElse(s"no-token-$field-$field2")
            case r"token.$field@(.*):$dv@(.*)"                                  => context.getOrElse(field, dv)
            case r"token.$field@(.*)"                                           => context.getOrElse(field, s"no-token-$field")

            case r"apikeyjwt.$field@(.*)" if field.contains(".")    => {
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.at(field).strConvert())
                .getOrElse(s"no-path-at-$field")
            }
            case r"apikeyjwt.$field@(.*)" if field.contains("/")    => {
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.atPointer(field).strConvert())
                .getOrElse(s"no-path-at-$field")
            }
            case r"apikeyjwt.$field@(.*)" if field.startsWith("$.") => {
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.atPath(field).strConvert())
                .getOrElse(s"no-path-at-$field")
            }
            case r"apikeyjwt.$field@(.*)"                           => {
              attrs
                .get(otoroshi.plugins.Keys.ApiKeyJwtKey)
                .flatMap(_.select(field).strConvert())
                .getOrElse(s"no-path-at-$field")
            }
            case r"env.$field@(.*):$dv@(.*)"                        => Option(System.getenv(field)).getOrElse(dv)
            case r"env.$field@(.*)"                                 => Option(System.getenv(field)).getOrElse(s"no-env-var-$field")

            case r"config.$field@(.*):$dv@(.*)" =>
              env.configuration
                .getOptionalWithFileSupport[String](field)
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Int](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Double](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Long](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Boolean](field).map(_.toString)
                )
                .getOrElse(dv)
            case r"config.$field@(.*)"          =>
              env.configuration
                .getOptionalWithFileSupport[String](field)
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Int](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Double](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Long](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Boolean](field).map(_.toString)
                )
                .getOrElse(s"no-config-$field")

            case r"ctx.$field@(.*).replace\('$a@(.*)', '$b@(.*)'\)"         =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field@(.*).replace\('$a@(.*)','$b@(.*)'\)"          =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field@(.*).replaceAll\('$a@(.*)','$b@(.*)'\)"       =>
              context.get(field).map(v => v.replaceAll(a, b)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field@(.*).replaceAll\('$a@(.*)','$b@(.*)'\)"       =>
              context.get(field).map(v => v.replaceAll(a, b)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field@(.*)\|ctx.$field2@(.*):$dv@(.*)"              =>
              context.get(field).orElse(context.get(field2)).getOrElse(dv)
            case r"ctx.$field@(.*)\|ctx.$field2@(.*)"                       =>
              context.get(field).orElse(context.get(field2)).getOrElse(s"no-ctx-$field-$field2")
            case r"ctx.$field@(.*):$dv@(.*)"                                => context.getOrElse(field, dv)
            case r"ctx.$field@(.*)"                                         => context.getOrElse(field, s"no-ctx-$field")
            case r"ctx.useragent.$field@(.*)" if userAgentDetails.isDefined =>
              val lookup: JsLookupResult = userAgentDetails.get.\(field)
              lookup
                .asOpt[String]
                .orElse(lookup.asOpt[Long].map(_.toString))
                .orElse(lookup.asOpt[Double].map(_.toString))
                .orElse(lookup.asOpt[Boolean].map(_.toString))
                .getOrElse(s"no-ctx-$field")
            case r"ctx.geolocation.$field@(.*)" if geolocDetails.isDefined  =>
              val lookup: JsLookupResult = geolocDetails.get.\(field)
              lookup
                .asOpt[String]
                .orElse(lookup.asOpt[Long].map(_.toString))
                .orElse(lookup.asOpt[Double].map(_.toString))
                .orElse(lookup.asOpt[Boolean].map(_.toString))
                .getOrElse(s"no-ctx-$field")
            case r"vault://$path@(.*)"                                      =>
              Await.result(
                env.vaults.fillSecretsAsync("el-exp", s"vault://$path")(env.otoroshiExecutionContext),
                5.seconds
              )
            case r"global_config.metadata.$name@(.*)"                       =>
              env.datastores.globalConfigDataStore
                .latest()(env.otoroshiExecutionContext, env)
                .metadata
                .get(name)
                .getOrElse(s"no-metadata-${name}")
            case r"global_config.env.$path@(.*)"                            =>
              env.datastores.globalConfigDataStore
                .latest()(env.otoroshiExecutionContext, env)
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
            case "user.name" if user.isDefined                              => user.get.name
            case "user.email" if user.isDefined                             => user.get.email
            case "user.tokens.id_token" if user.isDefined                   =>
              user.get.token.select("id_token").asOpt[String].getOrElse("no-id_token")
            case "user.tokens.access_token" if user.isDefined               =>
              user.get.token.select("access_token").asOpt[String].getOrElse("no-access_token")
            case "user.tokens.refresh_token" if user.isDefined              =>
              user.get.token.select("refresh_token").asOpt[String].getOrElse("no-refresh_token")
            case "user.tokens.token_type" if user.isDefined                 =>
              user.get.token.select("token_type").asOpt[String].getOrElse("no-token_type")
            case "user.tokens.expires_in" if user.isDefined                 =>
              user.get.token.select("expires_in").asOpt[String].getOrElse("no-expires_in")
            case r"user.metadata.$field@(.*):$dv@(.*)" if user.isDefined    =>
              user
                .flatMap(_.otoroshiData)
                .map(json =>
                  (json \ field).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case _                      => dv
                  }
                )
                .getOrElse(dv)
            case r"user.metadata.$field@(.*)" if user.isDefined             =>
              user
                .flatMap(_.otoroshiData)
                .map(json =>
                  (json \ field).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case _                      => s"no-meta-$field"
                  }
                )
                .getOrElse(s"no-meta-$field")
            case r"user.profile.$field@(.*):$dv@(.*)" if user.isDefined     =>
              user
                .map(_.profile)
                .map(json =>
                  (json \ field).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case _                      => dv
                  }
                )
                .getOrElse(dv)
            case r"user.profile.$field@(.*)" if user.isDefined              =>
              user
                .map(_.profile)
                .map(json =>
                  (json \ field).asOpt[JsValue] match {
                    case Some(JsNumber(number)) => number.toString()
                    case Some(JsString(str))    => str
                    case Some(JsBoolean(b))     => b.toString
                    case _                      => s"no-meta-$field"
                  }
                )
                .getOrElse(s"no-profile-$field")
            case expr                                                       => "bad-expr" //s"$${$expr}"
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
      req = req,
      service = service,
      route = route,
      apiKey = apiKey,
      user = user,
      context = context,
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
      req = req,
      service = service,
      route = route,
      apiKey = apiKey,
      user = user,
      context = context,
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
      req = req,
      service = service,
      route = route,
      apiKey = apiKey,
      user = user,
      context = context,
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
      req = req,
      service = service,
      route = route,
      apiKey = apiKey,
      user = user,
      context = context,
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
          case (key, JsString(str))     => (key, JsString(apply(str, req, service, route, apiKey, user, context, attrs, env)))
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
      case JsString(str)   => {
        apply(str, req, service, route, apiKey, user, context, attrs, env) match {
          case "true"               => JsBoolean(true)
          case "false"              => JsBoolean(false)
          case r"$nbr@([0-9\\.,]+)" => JsNumber(nbr.toDouble)
          case r"$nbr@([0-9]+)"     => JsNumber(nbr.toInt)
          case "null"               => JsNull
          case s                    => JsString(s)

        }
      }
      case _               => value
    }
  }
}
