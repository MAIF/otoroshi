package otoroshi.el

import models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.RequestHeader
import utils.ReplaceAllWith

import scala.util.Try
import utils.RequestImplicits._
import kaleidoscope._

object GlobalExpressionLanguage {

  lazy val logger = Logger("otoroshi-global-el")

  val expressionReplacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

  def apply(
      value: String,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: utils.TypedMap
  ): String = {
    // println(s"${req}:${service}:${apiKey}:${user}:${context}")
    value match {
      case v if v.contains("${") =>
        val userAgentDetails = attrs.get(otoroshi.plugins.Keys.UserAgentInfoKey)
        val geolocDetails = attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey)
        Try {
          expressionReplacer.replaceOn(value) {
            case "date"                                   => DateTime.now().toString()
            case r"date.format\('$format@(.*)'\)"         => DateTime.now().toString(format)
            case "service.domain" if service.isDefined    => service.get._domain
            case "service.subdomain" if service.isDefined => service.get.subdomain
            case "service.tld" if service.isDefined       => service.get.domain
            case "service.env" if service.isDefined       => service.get.env
            case "service.group" if service.isDefined     => service.get.groupId
            case "service.id" if service.isDefined        => service.get.id
            case "service.name" if service.isDefined      => service.get.name
            case r"service.metadata.$field@(.*):$dv@(.*)" if service.isDefined =>
              service.get.metadata.get(field).getOrElse(dv)
            case r"service.metadata.$field@(.*)" if service.isDefined =>
              service.get.metadata.get(field).getOrElse(s"no-meta-$field")

            case "req.path" if req.isDefined     => req.get.path
            case "req.uri" if req.isDefined      => req.get.relativeUri
            case "req.host" if req.isDefined     => req.get.host
            case "req.domain" if req.isDefined   => req.get.domain
            case "req.method" if req.isDefined   => req.get.method
            case "req.protocol" if req.isDefined => req.get.theProtocol
            case r"req.headers.$field@(.*):$defaultValue@(.*)" if req.isDefined =>
              req.get.headers.get(field).getOrElse(defaultValue)
            case r"req.headers.$field@(.*)" if req.isDefined =>
              req.get.headers.get(field).getOrElse(s"no-header-$field")
            case r"req.query.$field@(.*):$defaultValue@(.*)" if req.isDefined =>
              req.get.getQueryString(field).getOrElse(defaultValue)
            case r"req.query.$field@(.*)" if req.isDefined =>
              req.get.getQueryString(field).getOrElse(s"no-query-$field")

            case "apikey.name" if apiKey.isDefined => apiKey.get.clientName
            case "apikey.id" if apiKey.isDefined   => apiKey.get.clientId
            case r"apikey.metadata.$field@(.*):$dv@(.*)" if apiKey.isDefined =>
              apiKey.get.metadata.get(field).getOrElse(dv)
            case r"apikey.metadata.$field@(.*)" if apiKey.isDefined =>
              apiKey.get.metadata.get(field).getOrElse(s"no-meta-$field")
            case r"apikey.tags\\[$field@(.*):$dv@(.*)\\]" if apiKey.isDefined =>
              Option(apiKey.get.tags.apply(field.toInt)).getOrElse(dv)
            case r"apikey.tags\\[$field@(.*)\\]" if apiKey.isDefined =>
              Option(apiKey.get.tags.apply(field.toInt)).getOrElse(s"no-tag-$field")

            // for jwt comptab only
            case r"token.$field@(.*).replace\('$a@(.*)', '$b@(.*)'\)" =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-token-$field")
            case r"token.$field@(.*).replace\('$a@(.*)','$b@(.*)'\)" =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-token-$field")
            case r"token.$field@(.*).replaceAll\('$a@(.*)','$b@(.*)'\)" =>
              context.get(field).map(v => v.replaceAll(a, b)).getOrElse(s"no-token-$field")
            case r"token.$field@(.*).replaceAll\('$a@(.*)','$b@(.*)'\)" =>
              context.get(field).map(v => v.replaceAll(a, b)).getOrElse(s"no-token-$field")
            case r"token.$field@(.*)\|token.$field2@(.*):$dv@(.*)" =>
              context.get(field).orElse(context.get(field2)).getOrElse(dv)
            case r"token.$field@(.*)\|token.$field2@(.*)" =>
              context.get(field).orElse(context.get(field2)).getOrElse(s"no-token-$field-$field2")
            case r"token.$field@(.*):$dv@(.*)" => context.getOrElse(field, dv)
            case r"token.$field@(.*)"          => context.getOrElse(field, s"no-token-$field")

            case r"ctx.$field@(.*).replace\('$a@(.*)', '$b@(.*)'\)" =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field@(.*).replace\('$a@(.*)','$b@(.*)'\)" =>
              context.get(field).map(v => v.replace(a, b)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field@(.*).replaceAll\('$a@(.*)','$b@(.*)'\)" =>
              context.get(field).map(v => v.replaceAll(a, b)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field@(.*).replaceAll\('$a@(.*)','$b@(.*)'\)" =>
              context.get(field).map(v => v.replaceAll(a, b)).getOrElse(s"no-ctx-$field")
            case r"ctx.$field@(.*)\|ctx.$field2@(.*):$dv@(.*)" =>
              context.get(field).orElse(context.get(field2)).getOrElse(dv)
            case r"ctx.$field@(.*)\|ctx.$field2@(.*)" =>
              context.get(field).orElse(context.get(field2)).getOrElse(s"no-ctx-$field-$field2")
            case r"ctx.$field@(.*):$dv@(.*)" => context.getOrElse(field, dv)
            case r"ctx.$field@(.*)"          => context.getOrElse(field, s"no-ctx-$field")
            case r"ctx.useragent.$field@(.*)" if userAgentDetails.isDefined =>
              val lookup: JsLookupResult = (userAgentDetails.get.\(field))
              lookup.asOpt[String]
                .orElse(lookup.asOpt[Long].map(_.toString))
                .orElse(lookup.asOpt[Double].map(_.toString))
                .orElse(lookup.asOpt[Boolean].map(_.toString))
                .getOrElse(s"no-ctx-$field")
            case r"ctx.geolocation.$field@(.*)" if geolocDetails.isDefined =>
              val lookup: JsLookupResult = (geolocDetails.get.\(field))
              lookup.asOpt[String]
                .orElse(lookup.asOpt[Long].map(_.toString))
                .orElse(lookup.asOpt[Double].map(_.toString))
                .orElse(lookup.asOpt[Boolean].map(_.toString))
                .getOrElse(s"no-ctx-$field")
            case "user.name" if user.isDefined  => user.get.name
            case "user.email" if user.isDefined => user.get.email
            case r"user.metadata.$field@(.*):$dv@(.*)" if user.isDefined =>
              user
                .flatMap(_.otoroshiData)
                .map(
                  json =>
                    (json \ field).asOpt[JsValue] match {
                      case Some(JsNumber(number)) => number.toString()
                      case Some(JsString(str))    => str
                      case Some(JsBoolean(b))     => b.toString
                      case _                      => dv
                  }
                )
                .getOrElse(dv)
            case r"user.metadata.$field@(.*)" if user.isDefined =>
              user
                .flatMap(_.otoroshiData)
                .map(
                  json =>
                    (json \ field).asOpt[JsValue] match {
                      case Some(JsNumber(number)) => number.toString()
                      case Some(JsString(str))    => str
                      case Some(JsBoolean(b))     => b.toString
                      case _                      => s"no-meta-$field"
                  }
                )
                .getOrElse(s"no-meta-$field")

            case expr => "bad-expr" //s"$${$expr}"
          }
        } recover {
          case e =>
            logger.error(s"Error while parsing expression, returning raw value: $value", e)
            value
        } get
      case _ => value
    }
  }
}

object HeadersExpressionLanguage {

  // lazy val logger = Logger("otoroshi-headers-el")
  // val expressionReplacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

  def apply(
      value: String,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: utils.TypedMap
  ): String = {
    GlobalExpressionLanguage.apply(
      value = value,
      req = req,
      service = service,
      apiKey = apiKey,
      user = user,
      context = context,
      attrs = attrs
    )
  }

  /*
  def apply(value: String,
            service: ServiceDescriptor,
            apiKey: Option[ApiKey],
            user: Option[PrivateAppsUser]): String = {
    value match {
      case v if v.contains("${") =>
        Try {
          expressionReplacer.replaceOn(value) {
            case "service.domain"                => service._domain
            case "service.subdomain"             => service.subdomain
            case "service.tld"                   => service.domain
            case "service.env"                   => service.env
            case "service.group"                 => service.groupId
            case "service.id"                    => service.id
            case "service.name"                  => service.name
            case r"service.metadata.$field@(.*)" => service.metadata.get(field).getOrElse("bad-expr")

            case "apikey.name" if apiKey.isDefined => apiKey.get.clientName
            case "apikey.id" if apiKey.isDefined   => apiKey.get.clientId
            case r"apikey.metadata.$field@(.*)" if apiKey.isDefined =>
              apiKey.get.metadata.get(field).getOrElse("bad-expr")
            case r"apikey.tags\\[$field@(.*)\\]" if apiKey.isDefined =>
              Option(apiKey.get.tags.apply(field.toInt)).getOrElse("bad-expr")

            case "user.name" if user.isDefined  => user.get.name
            case "user.email" if user.isDefined => user.get.email
            case r"user.metadata.$field@(.*)" if user.isDefined =>
              user
                .flatMap(_.otoroshiData)
                .map(
                  json =>
                    (json \ field).asOpt[JsValue] match {
                      case Some(JsNumber(number)) => number.toString()
                      case Some(JsString(str))    => str
                      case Some(JsBoolean(b))     => b.toString
                      case _                      => "bad-expr"
                  }
                )
                .getOrElse("bad-expr")

            case expr => "bad-expr" //s"$${$expr}"
          }
        } recover {
          case e =>
            logger.error(s"Error while parsing expression, returning raw value: $value", e)
            value
        } get
      case _ => value
    }
  }
 */
}

object RedirectionExpressionLanguage {

  // lazy val logger = Logger("otoroshi-redirection-el")
  // val expressionReplacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

  def apply(
      value: String,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: utils.TypedMap
  ): String = {
    GlobalExpressionLanguage.apply(
      value = value,
      req = req,
      service = service,
      apiKey = apiKey,
      user = user,
      context = context,
      attrs = attrs
    )
  }

  /*
  def apply(value: String, req: RequestHeader): String = {
    value match {
      case v if v.contains("${") =>
        Try {
          expressionReplacer.replaceOn(value) { expression =>
            expression match {
              case "req.path"                 => req.path
              case "req.uri"                  => req.relativeUri
              case "req.host"                 => req.host
              case "req.domain"               => req.domain
              case "req.method"               => req.method
              case "req.protocol"             => req.theProtocol
              case r"req.headers.$field@(.*):$defaultValue@(.*)" => req.headers.get(field).getOrElse(defaultValue)
              case r"req.headers.$field@(.*)" => req.headers.get(field).getOrElse(s"no-header-$field")
              case r"req.query.$field@(.*):$defaultValue@(.*)" => req.getQueryString(field).getOrElse(defaultValue)
              case r"req.query.$field@(.*)"   => req.getQueryString(field).getOrElse(s"no-query-$field")
              case _                          => "bad-expr"
            }
          }
        } recover {
          case e =>
            logger.error(s"Error while parsing expression, returning raw value: $value", e)
            value
        } get
      case _ => value
    }
  }
 */
}

object TargetExpressionLanguage {

  // lazy val logger = Logger("otoroshi-target-el")
  // val expressionReplacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

  def apply(
      value: String,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: utils.TypedMap
  ): String = {
    GlobalExpressionLanguage.apply(
      value = value,
      req = req,
      service = service,
      apiKey = apiKey,
      user = user,
      context = context,
      attrs = attrs
    )
  }

  /*
  def apply(value: String, req: RequestHeader): String = {
    value match {
      case v if v.contains("${") =>
        Try {
          expressionReplacer.replaceOn(value) { expression =>
            expression match {
              case "req.path"                 => req.path
              case "req.uri"                  => req.relativeUri
              case "req.host"                 => req.host
              case "req.domain"               => req.domain
              case "req.method"               => req.method
              case "req.protocol"             => req.theProtocol
              case r"req.headers.$field@(.*):$defaultValue@(.*)" => req.headers.get(field).getOrElse(defaultValue)
              case r"req.headers.$field@(.*)" => req.headers.get(field).getOrElse(s"no-header-$field")
              case r"req.query.$field@(.*):$defaultValue@(.*)" => req.getQueryString(field).getOrElse(defaultValue)
              case r"req.query.$field@(.*)"   => req.getQueryString(field).getOrElse(s"no-query-$field")
              case _                          => "bad-expr"
            }
          }
        } recover {
          case e =>
            logger.error(s"Error while parsing expression, returning raw value: $value", e)
            value
        } get
      case _ => value
    }
  }
 */
}

object JwtExpressionLanguage {

  def apply(
      value: String,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: utils.TypedMap
  ): String = {
    GlobalExpressionLanguage.apply(
      value = value,
      req = req,
      service = service,
      apiKey = apiKey,
      user = user,
      context = context,
      attrs = attrs
    )
  }

  /*
  lazy val logger = Logger("otoroshi-jwt-el")

  val expressionReplacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

  def apply(value: String, context: Map[String, String]): String = {
    value match {
      case v if v.contains("${") =>
        Try {
          expressionReplacer.replaceOn(value) { expression =>
            expression match {
              case "date"                           => DateTime.now().toString()
              case r"date.format\('$format@(.*)'\)" => DateTime.now().toString(format)
              case r"token.$field@(.*).replace\('$a@(.*)', '$b@(.*)'\)" =>
                context.get(field).map(v => v.replace(a, b)).getOrElse("no-value")
              case r"token.$field@(.*).replace\('$a@(.*)','$b@(.*)'\)" =>
                context.get(field).map(v => v.replace(a, b)).getOrElse("no-value")
              case r"token.$field@(.*).replaceAll\('$a@(.*)','$b@(.*)'\)" =>
                context.get(field).map(v => v.replaceAll(a, b)).getOrElse("no-value")
              case r"token.$field@(.*).replaceAll\('$a@(.*)','$b@(.*)'\)" =>
                context.get(field).map(v => v.replaceAll(a, b)).getOrElse("no-value")
              case r"token.$field@(.*)" => context.getOrElse(field, value)
              case _                    => "bad-expr"
            }
          }
        } recover {
          case e =>
            logger.error(s"Error while parsing expression, returning raw value: $value", e)
            value
        } get
      case _ => value
    }
  }
   */

  def fromJson(
      value: JsValue,
      req: Option[RequestHeader],
      service: Option[ServiceDescriptor],
      apiKey: Option[ApiKey],
      user: Option[PrivateAppsUser],
      context: Map[String, String],
      attrs: utils.TypedMap
  ): JsValue = {
    value match {
      case JsObject(map) =>
        new JsObject(map.toSeq.map {
          case (key, JsString(str))     => (key, JsString(apply(str, req, service, apiKey, user, context, attrs)))
          case (key, obj @ JsObject(_)) => (key, fromJson(obj, req, service, apiKey, user, context, attrs))
          case (key, arr @ JsArray(_))  => (key, fromJson(arr, req, service, apiKey, user, context, attrs))
          case (key, v)                 => (key, v)
        }.toMap)
      case JsArray(values) =>
        new JsArray(values.map {
          case JsString(str) => JsString(apply(str, req, service, apiKey, user, context, attrs))
          case obj: JsObject => fromJson(obj, req, service, apiKey, user, context, attrs)
          case arr: JsArray  => fromJson(arr, req, service, apiKey, user, context, attrs)
          case v             => v
        })
      case JsString(str) => {
        apply(str, req, service, apiKey, user, context, attrs) match {
          case "true"               => JsBoolean(true)
          case "false"              => JsBoolean(false)
          case r"$nbr@([0-9\\.,]+)" => JsNumber(nbr.toDouble)
          case r"$nbr@([0-9]+)"     => JsNumber(nbr.toInt)
          case "null"               => JsNull
          case s                    => JsString(s)

        }
      }
      case _ => value
    }
  }

  /*
  def apply(value: JsValue, context: Map[String, String]): JsValue = {
    value match {
      case JsObject(map) =>
        new JsObject(map.toSeq.map {
          case (key, JsString(str))     => (key, JsString(apply(str, context)))
          case (key, obj @ JsObject(_)) => (key, apply(obj, context))
          case (key, arr @ JsArray(_))  => (key, apply(arr, context))
          case (key, v)                 => (key, v)
        }.toMap)
      case JsArray(values) =>
        new JsArray(values.map {
          case JsString(str) => JsString(apply(str, context))
          case obj: JsObject => apply(obj, context)
          case arr: JsArray  => apply(arr, context)
          case v             => v
        })
      case JsString(str) => {
        apply(str, context) match {
          case "true"               => JsBoolean(true)
          case "false"              => JsBoolean(false)
          case r"$nbr@([0-9\\.,]+)" => JsNumber(nbr.toDouble)
          case r"$nbr@([0-9]+)"     => JsNumber(nbr.toInt)
          case s                    => JsString(s)

        }
      }
      case _ => value
    }
  }
 */
}
