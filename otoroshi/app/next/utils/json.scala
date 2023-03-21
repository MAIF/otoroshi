package otoroshi.next.utils

import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.next.plugins.api.{NgPluginHttpRequest, NgPluginHttpResponse}
import otoroshi.utils.http.DN
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSCookie, WSCookie}
import play.api.mvc.{Cookie, RequestHeader}

import java.security.cert.X509Certificate
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object JsonHelpers {

  def requestBody(request: NgPluginHttpRequest)(implicit ec: ExecutionContext, mat: Materializer): Future[JsValue] = {
    if (request.hasBody) {
      request.body.runFold(ByteString.empty)(_ ++ _).map { b =>
        val arr = b.toArray[Byte]
        Writes.arrayWrites[Byte].writes(arr)
      }
    } else {
      JsNull.vfuture
    }
  }

  def responseBody(response: NgPluginHttpResponse)(implicit ec: ExecutionContext, mat: Materializer): Future[JsValue] = {
    response.body.runFold(ByteString.empty)(_ ++ _).map { b =>
      val arr = b.toArray[Byte]
      if (arr.isEmpty) {
        JsNull
      } else {
        Writes.arrayWrites[Byte].writes(arr)
      }
    }
  }

  def reader[A](f: => A): JsResult[A] = Try {
    f
  } match {
    case Failure(err)   => JsError(err.getMessage)
    case Success(value) => JsSuccess(value)
  }

  def clientCertChainToJson(chain: Option[Seq[X509Certificate]]): JsValue = chain match {
    case None      => JsNull
    case Some(seq) => {
      JsArray(
        seq.map(c =>
          Json.obj(
            "subjectDN"    -> DN(c.getSubjectDN.getName).stringify,
            "issuerDN"     -> DN(c.getIssuerDN.getName).stringify,
            "notAfter"     -> c.getNotAfter.getTime,
            "notBefore"    -> c.getNotBefore.getTime,
            "serialNumber" -> c.getSerialNumber.toString(16),
            "subjectCN"    -> Option(DN(c.getSubjectDN.getName).stringify)
              .flatMap(_.split(",").toSeq.map(_.trim).find(_.toLowerCase().startsWith("cn=")))
              .map(_.replace("CN=", "").replace("cn=", ""))
              .getOrElse(DN(c.getSubjectDN.getName).stringify)
              .asInstanceOf[String],
            "issuerCN"     -> Option(DN(c.getIssuerDN.getName).stringify)
              .flatMap(_.split(",").toSeq.map(_.trim).find(_.toLowerCase().startsWith("cn=")))
              .map(_.replace("CN=", "").replace("cn=", ""))
              .getOrElse(DN(c.getIssuerDN.getName).stringify)
              .asInstanceOf[String]
          )
        )
      )
    }
  }
  def requestToJson(request: RequestHeader): JsValue = {
    Json.obj(
      "id"                -> request.id,
      "method"            -> request.method,
      "headers"           -> request.headers.toSimpleMap,
      "cookies"           -> JsArray(request.cookies.toSeq.map(c => cookieToJson(c))),
      "tls"               -> request.theSecuredTrusted,
      "uri"               -> request.uri,
      "query"             -> request.queryString,
      "path"              -> request.path,
      "version"           -> request.version,
      "has_body"          -> request.theHasBody,
      "remote"            -> request.remoteAddress,
      "client_cert_chain" -> JsonHelpers.clientCertChainToJson(request.clientCertificateChain)
    )
  }
  def cookieToJson(cookie: Cookie): JsValue = {
    Json.obj(
      "name"      -> cookie.name,
      "value"     -> cookie.value,
      "path"      -> cookie.path,
      "domain"    -> cookie.domain,
      "http_only" -> cookie.httpOnly,
      "max_age"   -> cookie.maxAge,
      "secure"    -> cookie.secure,
      "same_site" -> cookie.sameSite.map(_.value)
    )
  }
  def wsCookieToJson(cookie: WSCookie): JsValue = {
    cookie match {
      case cc: otoroshi.utils.http.WSCookieWithSameSite => wsCookieWithSameSiteToJson(cc)
      case _ => Json.obj(
        "name" -> cookie.name,
        "value" -> cookie.value,
        "path" -> cookie.path,
        "domain" -> cookie.domain,
        "http_only" -> cookie.httpOnly,
        "max_age" -> cookie.maxAge,
        "secure" -> cookie.secure
      )
    }
  }

  def wsCookieWithSameSiteToJson(cookie: otoroshi.utils.http.WSCookieWithSameSite): JsValue = {
    Json.obj(
      "name" -> cookie.name,
      "value" -> cookie.value,
      "path" -> cookie.path,
      "domain" -> cookie.domain,
      "http_only" -> cookie.httpOnly,
      "max_age" -> cookie.maxAge,
      "secure" -> cookie.secure,
      "sameSite" -> cookie.sameSite.map(ss => JsString.apply(ss.value)).getOrElse(JsNull).as[JsValue],
    )
  }
  def cookieFromJson(cookie: JsValue): WSCookie = {
    DefaultWSCookie(
      name = cookie.select("name").as[String],
      value = cookie.select("value").as[String],
      domain = cookie.select("domain").asOpt[String],
      path = cookie.select("path").asOpt[String],
      maxAge = cookie.select("max_age").asOpt[Long],
      secure = cookie.select("secure").asOpt[Boolean].getOrElse(false),
      httpOnly = cookie.select("http_only").asOpt[Boolean].getOrElse(false)
    )
  }
  def errToJson(error: Throwable): JsValue = {
    Json.obj(
      "message" -> error.getMessage,
      "cause"   -> Option(error.getCause).map(errToJson).getOrElse(JsNull).as[JsValue],
      "stack"   -> JsArray(
        error.getStackTrace.toSeq.map(el =>
          Json.obj(
            "class_loader_name" -> el.getClassLoaderName,
            "module_name"       -> el.getModuleName,
            "module_version"    -> el.getModuleVersion,
            "declaring_class"   -> el.getClassName,
            "method_name"       -> el.getMethodName,
            "file_name"         -> el.getFileName,
            "line_number"       -> el.getLineNumber
          )
        )
      )
    )
  }
}
