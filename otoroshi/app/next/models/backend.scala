package otoroshi.next.models

import akka.http.scaladsl.model.{HttpProtocol, HttpProtocols}
import otoroshi.models.{AlwaysMatch, TargetPredicate}
import otoroshi.utils.http.MtlsConfig
import play.api.libs.json.{JsNull, JsString, JsValue, Json}

/*
case class Backend(id: String, name: String, description: String, tags: Seq[String], metadata: Map[String, String], location: EntityLocation) extends EntityLocationSupport {

  override def internalId: String = id
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata
  override def json: JsValue = ???
}
*/

// TODO: handle 2 kind of backend, one inline, one that reference a stored backend
case class Backend(
  hostname: String,
  port: Int,
  tls: Boolean,
  weight: Int = 1,
  protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
  predicate: TargetPredicate = AlwaysMatch,
  ipAddress: Option[String] = None,
  tlsConfig: MtlsConfig = MtlsConfig(),
) {
  lazy val defaultPortString = port match {
    case 443 => ""
    case 80 => ""
    case _ => s":${port}"
  }
  def toTarget: otoroshi.models.Target = otoroshi.models.Target(
    host = s"${hostname}${defaultPortString}",
    scheme = if (tls) "https" else "http",
    weight = weight,
    protocol = protocol,
    predicate = predicate,
    ipAddress = ipAddress,
    mtlsConfig = tlsConfig,
    tags = Seq.empty,
    metadata = Map.empty,
  )
  def json: JsValue = Json.obj(
    "hostname" -> hostname,
    "port" -> port,
    "tls" -> tls,
    "weight" -> weight,
    "protocol" -> protocol.value,
    "ip_address" -> ipAddress.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "tls_config" -> tlsConfig.json
  )
}
