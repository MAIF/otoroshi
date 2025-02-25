package otoroshi.events

import java.util.concurrent.TimeUnit
import com.sksamuel.pulsar4s.{Consumer, ConsumerConfig, DefaultPulsarClient, Producer, ProducerConfig, PulsarClient, PulsarClientConfig, Subscription, Topic}
import otoroshi.models.Exporter
import com.sksamuel.pulsar4s.playjson._
import org.apache.pulsar.client.impl.auth.{AuthenticationBasic, AuthenticationToken}
import otoroshi.models.Exporter
import otoroshi.utils.http.MtlsConfig
import play.api.libs.json._

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
  implicit val format = new Format[PulsarConfig] {
    override def writes(o: PulsarConfig): JsValue =
      Json.obj(
        "uri"                   -> o.uri,
        "tlsTrustCertsFilePath" -> o.tlsTrustCertsFilePath.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "tenant"                -> o.tenant,
        "namespace"             -> o.namespace,
        "topic"                 -> o.topic,
        "token"                 -> o.token,
        "username"              -> o.username,
        "password"              -> o.password,
        "mtlsConfig"            -> o.mtlsConfig.json
      )

    override def reads(json: JsValue): JsResult[PulsarConfig] =
      Try {
        PulsarConfig(
          uri = ((json \ "uri").as[String]),
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
        case Failure(e)  => JsError(e.getMessage)
        case Success(kc) => JsSuccess(kc)
      }
  }
}

object PulsarSetting {
  def client(_env: otoroshi.env.Env, config: PulsarConfig): PulsarClient = {
    if (config.mtlsConfig.mtls) {
      val (_, jks, password) = config.mtlsConfig.toJKS(_env)

      val builder = org.apache.pulsar.client.api.PulsarClient
        .builder()
        .serviceUrl(config.uri)
        .enableTlsHostnameVerification(false)
        .allowTlsInsecureConnection(config.mtlsConfig.trustAll)
        .tlsTrustStoreType("JKS")
        .tlsTrustStorePassword(password)
        .tlsTrustCertsFilePath(jks.getAbsolutePath)
        .useKeyStoreTls(true)

      config.tlsTrustCertsFilePath.foreach(builder.tlsTrustCertsFilePath)

      new DefaultPulsarClient(builder.build())
    } else {
      val c = PulsarClientConfig(
        serviceUrl = config.uri,
        authentication = {
          config.token
            .map(token => new AuthenticationToken(token))
            .orElse(for {
              username <- config.username
              password <- config.password
            } yield {
              val auth = new AuthenticationBasic()
              auth.configure(Json.stringify(Json.obj("userId" -> username, "password" -> password)))
              auth
            })
        }
      )
      PulsarClient(c)
    }
  }

  def producer(_env: otoroshi.env.Env, config: PulsarConfig): Producer[JsValue] = {

    val topic          = Topic(s"persistent://${config.tenant}/${config.namespace}/${config.topic}")
    val producerConfig = ProducerConfig(topic)
    val cli            = client(_env, config)
    cli.producer[JsValue](producerConfig)
  }

  def consumer(_env: otoroshi.env.Env, config: PulsarConfig): Consumer[JsValue] = {
    val topic          = Topic(s"persistent://${config.tenant}/${config.namespace}/${config.topic}")
    val consumerConfig = ConsumerConfig(topics = Seq(topic), subscriptionName = Subscription("otoroshi"))
    val cli            = client(_env, config)
    cli.consumer[JsValue](consumerConfig)
  }
}
