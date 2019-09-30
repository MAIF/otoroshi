package otoroshi.el

import models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.RequestHeader
import utils.ReplaceAllWith

import scala.util.Try

object HeadersExpressionLanguage {

  import kaleidoscope._

  lazy val logger = Logger("otoroshi-headers-el")

  val expressionReplacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

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
}

object RedirectionExpressionLanguage {

  import kaleidoscope._
  import utils.RequestImplicits._

  lazy val logger = Logger("otoroshi-redirection-el")

  val expressionReplacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

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
}

object TargetExpressionLanguage {

  import kaleidoscope._
  import utils.RequestImplicits._

  lazy val logger = Logger("otoroshi-target-el")

  val expressionReplacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

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
}

object JwtExpressionLanguage {

  import kaleidoscope._

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
}