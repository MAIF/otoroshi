package otoroshi.events.pulsar

import com.sksamuel.pulsar4s._
import org.apache.pulsar.client.impl.auth.{AuthenticationBasic, AuthenticationToken}
import PulsarPlayJsonSchema._
import play.api.libs.json._

object PulsarSetting {
  def client(_env: otoroshi.env.Env, config: PulsarConfig): PulsarClient = {
    if (config.mtlsConfig.mtls) {
      val (_, jks, password) = config.mtlsConfig.toJKS(using _env)

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
