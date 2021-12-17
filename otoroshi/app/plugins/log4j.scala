package otoroshi.plugins.log4j

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.script.{HttpRequest, RequestTransformer, TransformerRequestBodyContext, TransformerRequestContext}
import otoroshi.utils.body.BodyUtils
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Json}
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

sealed trait Log4jExpressionPart {
  def hasJNDI: Boolean = {
    Try(computed.contains("jndi:")).getOrElse(false)
  }
  def computed: String
}

case class Log4jExpressionText(value: String) extends Log4jExpressionPart {
  override def toString: String = s"Text('$value')"
  def hasSeparator: Boolean = value.contains(":")
  def hasDefaultValue: Boolean = value.contains(":-")
  def defaultValue: String = Try(value.split(":-").apply(1)) match {
    case Failure(e) => value
    case Success(v) => v
  }
  def key: String = value.split(":").apply(0)
  def keyval: String = Try(value.split(":").apply(1)).getOrElse(value)
  def computed: String = {
    (if (hasDefaultValue) defaultValue else value) //(if (hasSeparator) keyval else value))
      .replaceAll("::", "").toLowerCase()
  }
}
case class Log4jExpression(parts: Seq[Log4jExpressionPart]) extends Log4jExpressionPart {
  override def toString: String = s"Expression(${parts.mkString(", ")})"
  def computed: String = parts.map(_.computed).mkString("")
}

// https://blog.cloudflare.com/exploitation-of-cve-2021-44228-before-public-disclosure-and-evolution-of-waf-evasion-patterns/
object Log4jExpressionParser {

  private def parseExpression(value: String): (Log4jExpression, Int) = {
    var parts = Seq.empty[Log4jExpressionPart]
    var i = 0
    var size = 0
    var buffer = ""
    while(i < value.length) {
      value.apply(i) match {
        case '$' if value.apply(i + 1) == '{' => {
          if (buffer.nonEmpty) {
            val texp = Log4jExpressionText(buffer)
            parts = parts :+ texp
            buffer = ""
          }
          val (exp, size) = parseExpression(value.substring(i + 2))
          parts = parts :+ exp
          i = i + size + 1
        }
        case '}' =>
          size = i + 1
          i = value.length
        case c =>
          buffer = buffer + c
      }
      i = i + 1
    }
    if (buffer.nonEmpty) {
      val texp = Log4jExpressionText(buffer)
      parts = parts :+ texp
      buffer = ""
    }
    (Log4jExpression(parts), size)
  }

  def parseAsExp(value: String): Log4jExpression = {
    Log4jExpression(parse(value))
  }

  def parse(value: String): Seq[Log4jExpression] = {
    var expressions = Seq.empty[Log4jExpression]
    var i = 0
    while(i < value.length) {
      value.apply(i) match {
        case '$' if value.apply(i + 1) == '{' => {
          val (exp, size) = parseExpression(value.substring(i + 2))
          expressions = expressions :+ exp
          i = i + size + 1
        }
        case c => // nothing to do here
      }
      i = i + 1
    }
    expressions
  }
}

class Log4jShellFilter extends RequestTransformer {

  private val requestBodyKey = TypedKey[Future[Source[ByteString, _]]]("otoroshi.plugins.log4j.Log4jShellFilterRequestBody")

  override def name: String = "Log4jShell mitigation plugin"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "Log4jShellFilter" -> Json.obj(
          "status"  -> 200,
          "body"  -> "",
          "parseBody" -> false
        )
      )
    )

  override def description: Option[String] =
    Some(
      s"""This plugin try to detect Log4jShell attacks in request and block them.
         |
         |This plugin can accept the following configuration
         |
         |```javascript
         |{
         |  "Log4jShellFilter": {
         |    "status": 200, // the status send back when an attack expression is found
         |    "body": "", // the body send back when an attack expression is found
         |    "parseBody": false // enables request body parsing to find attack expression
         |  }
         |}
         |```
    """.stripMargin
    )

  def containsBadValue(value: String): Boolean = {
    if (value.contains("${")) {
      value.toLowerCase().contains("${jndi:rmi://") ||
        value.toLowerCase().contains("${jndi:http://") ||
        value.toLowerCase().contains("${jndi:ldap://") ||
        value.toLowerCase().contains("${jndi:") ||
        Log4jExpression(Log4jExpressionParser.parse(value)).hasJNDI
    } else {
      false
    }
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = ctx.configFor("Log4jShellFilter")
    val status = config.select("status").asOpt[Int].getOrElse(200)
    val body = config.select("body").asOpt[String].getOrElse("")
    val parseBody = config.select("parseBody").asOpt[Boolean].getOrElse(false)
    val promise = Promise[Source[ByteString, _]]()
    ctx.attrs.put(requestBodyKey -> promise.future)
    val newHeaders = ctx.request.headers.toSimpleMap.filterNot {
      case (_, value) => containsBadValue(value)
    }
    val hasBadHeaders = newHeaders.nonEmpty
    val hasBadMethod = containsBadValue(ctx.request.method)
    val hasBadPath = containsBadValue(ctx.request.thePath)
    val hasBadQueryParam = containsBadValue(ctx.request.rawQueryString)
    if (hasBadHeaders || hasBadMethod || hasBadPath || hasBadQueryParam) {
      Results.Status(status)(body).as("text/plain").leftf
    } else {
      if (parseBody && BodyUtils.hasBody(ctx.request)) {
        ctx.rawRequest.body().runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
          val bodyStr = bodyRaw.utf8String
          if (containsBadValue(bodyStr)) {
            Results.Status(status)(body).as("text/plain").left
          } else {
            val source = Source(bodyRaw.grouped(32 * 1024).toList)
            promise.trySuccess(source)
            ctx.otoroshiRequest.right
          }
        }
      } else {
        ctx.otoroshiRequest.rightf
      }
    }
  }

  override def transformRequestBodyWithCtx(
    ctx: TransformerRequestBodyContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    ctx.attrs.get(requestBodyKey) match {
      case None       => ctx.body
      case Some(body) => Source.future(body).flatMapConcat(b => b)
    }
  }
}
