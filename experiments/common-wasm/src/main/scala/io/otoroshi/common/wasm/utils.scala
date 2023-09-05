package io.otoroshi.common.wasm

import play.api.libs.json._
import play.api.libs.ws.{DefaultWSProxyServer, WSProxyServer}

import scala.util.{Failure, Success, Try}

object WSProxyServerJson {
  def maybeProxyToJson(p: Option[WSProxyServer]): JsValue =
    p match {
      case Some(proxy) => proxyToJson(proxy)
      case None        => JsNull
    }
  def proxyToJson(p: WSProxyServer): JsValue              =
    Json.obj(
      "host"          -> p.host, // host: String
      "port"          -> p.port, // port: Int
      "protocol"      -> p.protocol.map(JsString.apply).getOrElse(JsNull).as[JsValue], // protocol: Option[String]
      "principal"     -> p.principal.map(JsString.apply).getOrElse(JsNull).as[JsValue], // principal: Option[String]
      "password"      -> p.password.map(JsString.apply).getOrElse(JsNull).as[JsValue], // password: Option[String]
      "ntlmDomain"    -> p.ntlmDomain.map(JsString.apply).getOrElse(JsNull).as[JsValue], // ntlmDomain: Option[String]
      "encoding"      -> p.encoding.map(JsString.apply).getOrElse(JsNull).as[JsValue], // encoding: Option[String]
      "nonProxyHosts" -> p.nonProxyHosts
        .map(nph => JsArray(nph.map(JsString.apply)))
        .getOrElse(JsNull)
        .as[JsValue] // nonProxyHosts: Option[Seq[String]]
    )
  def proxyFromJson(json: JsValue): Option[WSProxyServer] = {
    val maybeHost = (json \ "host").asOpt[String].filterNot(_.trim.isEmpty)
    val maybePort = (json \ "port").asOpt[Int]
    (maybeHost, maybePort) match {
      case (Some(host), Some(port)) => {
        Some(DefaultWSProxyServer(host, port))
          .map { proxy =>
            (json \ "protocol")
              .asOpt[String]
              .filterNot(_.trim.isEmpty)
              .map(v => proxy.copy(protocol = Some(v)))
              .getOrElse(proxy)
          }
          .map { proxy =>
            (json \ "principal")
              .asOpt[String]
              .filterNot(_.trim.isEmpty)
              .map(v => proxy.copy(principal = Some(v)))
              .getOrElse(proxy)
          }
          .map { proxy =>
            (json \ "password")
              .asOpt[String]
              .filterNot(_.trim.isEmpty)
              .map(v => proxy.copy(password = Some(v)))
              .getOrElse(proxy)
          }
          .map { proxy =>
            (json \ "ntlmDomain")
              .asOpt[String]
              .filterNot(_.trim.isEmpty)
              .map(v => proxy.copy(ntlmDomain = Some(v)))
              .getOrElse(proxy)
          }
          .map { proxy =>
            (json \ "encoding")
              .asOpt[String]
              .filterNot(_.trim.isEmpty)
              .map(v => proxy.copy(encoding = Some(v)))
              .getOrElse(proxy)
          }
          .map { proxy =>
            (json \ "nonProxyHosts").asOpt[Seq[String]].map(v => proxy.copy(nonProxyHosts = Some(v))).getOrElse(proxy)
          }
      }
      case _                        => None
    }
  }
}

case class TlsConfig(
                        certs: Seq[String] = Seq.empty,
                        trustedCerts: Seq[String] = Seq.empty,
                        enabled: Boolean = false,
                        loose: Boolean = false,
                        trustAll: Boolean = false
                      ) {
  def json: JsValue           = TlsConfig.format.writes(this)
}

object TlsConfig {
  val default                                     = TlsConfig()
  val format                                      = new Format[TlsConfig] {
    override def reads(json: JsValue): JsResult[TlsConfig] = {
      Try {
        TlsConfig(
          certs = (json \ "certs")
            .asOpt[Seq[String]]
            .orElse((json \ "certId").asOpt[String].map(v => Seq(v)))
            .orElse((json \ "cert_id").asOpt[String].map(v => Seq(v)))
            .map(_.filter(_.trim.nonEmpty))
            .getOrElse(Seq.empty),
          trustedCerts = (json \ "trusted_certs")
            .asOpt[Seq[String]]
            .map(_.filter(_.trim.nonEmpty))
            .getOrElse(Seq.empty),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          loose = (json \ "loose").asOpt[Boolean].getOrElse(false),
          trustAll = (json \ "trust_all").asOpt[Boolean].getOrElse(false)
        )
      } match {
        case Failure(e) => JsError(e.getMessage())
        case Success(v) => JsSuccess(v)
      }
    }

    override def writes(o: TlsConfig): JsValue = {
      Json.obj(
        "certs"         -> JsArray(o.certs.map(JsString.apply)),
        "trusted_certs" -> JsArray(o.trustedCerts.map(JsString.apply)),
        "enabled"       -> o.enabled,
        "loose"         -> o.loose,
        "trust_all"     -> o.trustAll
      )
    }
  }
}