package otoroshi.events.pulsar

import otoroshi.models.Exporter
import otoroshi.utils.http.MtlsConfig
import play.api.libs.json.{Format, JsError, JsNull, JsResult, JsString, JsSuccess, JsValue, Json}

import scala.util.{Failure, Success, Try}

case class PulsarConfig(
                           uri: String,
                           tlsTrustCertsFilePath: Option[String],
                           tenant: String,
                           namespace: String,
                           topic: String,
                           token: Option[String],
                           username: Option[String],
                           password: Option[String],
                           mtlsConfig: MtlsConfig = MtlsConfig()
                       ) extends Exporter {
    override def toJson: JsValue = PulsarConfig.format.writes(this)
}

object PulsarConfig {
    implicit val format: Format[PulsarConfig] = new Format[PulsarConfig] {
        override def writes(o: PulsarConfig): JsValue =
            Json.obj(
                "uri" -> o.uri,
                "tlsTrustCertsFilePath" -> o.tlsTrustCertsFilePath.map(JsString.apply).getOrElse(JsNull).as[JsValue],
                "tenant" -> o.tenant,
                "namespace" -> o.namespace,
                "topic" -> o.topic,
                "token" -> o.token,
                "username" -> o.username,
                "password" -> o.password,
                "mtlsConfig" -> o.mtlsConfig.json
            )

        override def reads(json: JsValue): JsResult[PulsarConfig] =
            Try {
                PulsarConfig(
                    uri = (json \ "uri").as[String],
                    tlsTrustCertsFilePath = (json \ "tlsTrustCertsFilePath").asOpt[String],
                    tenant = (json \ "tenant").as[String],
                    namespace = (json \ "namespace").as[String],
                    topic = (json \ "topic").as[String],
                    token = (json \ "token").asOpt[String].filter(_.trim.nonEmpty),
                    username = (json \ "username").asOpt[String].filter(_.trim.nonEmpty),
                    password = (json \ "password").asOpt[String].filter(_.trim.nonEmpty),
                    mtlsConfig = MtlsConfig.read((json \ "mtlsConfig").asOpt[JsValue])
                )
            } match {
                case Failure(e) => JsError(e.getMessage)
                case Success(kc) => JsSuccess(kc)
            }
    }
}