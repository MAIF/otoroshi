package otoroshi.next.utils

import otoroshi.env.Env
import otoroshi.utils.http.DN
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import play.api.libs.json.{JsArray, JsNull, JsValue, Json}
import play.api.mvc.RequestHeader

import java.security.cert.X509Certificate

object JsonHelpers {
  def clientCertChainToJson(chain: Option[Seq[X509Certificate]]): JsValue = chain match {
    case None => JsNull
    case Some(seq) => {
      JsArray(seq.map(c => Json.obj(
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
      )))
    }
  }
  def requestToJson(request: RequestHeader): JsValue = {
    Json.obj(
      "id" -> request.id,
      "method" -> request.method,
      "headers" -> request.headers.toSimpleMap,
      "tls" -> request.theSecuredTrusted,
      "uri" -> request.uri,
      "path" -> request.path,
      "version" -> request.version,
      "has_body" -> request.hasBody,
      "remote" -> request.remoteAddress,
      "client_cert_chain" -> JsonHelpers.clientCertChainToJson(request.clientCertificateChain)
    )
  }
  def errToJson(error: Throwable): JsValue = {
    Json.obj(
      "message" -> error.getMessage,
      "cause" -> Option(error.getCause).map(errToJson).getOrElse(JsNull).as[JsValue],
      "stack" -> JsArray(error.getStackTrace.toSeq.map(el => Json.obj(
        "class_loader_name" -> el.getClassLoaderName,
        "module_name" -> el.getModuleName,
        "module_version" -> el.getModuleVersion,
        "declaring_class" -> el.getClassName,
        "method_name" -> el.getMethodName,
        "file_name" -> el.getFileName,
        "line_number" -> el.getLineNumber
      )))
    )
  }
}
