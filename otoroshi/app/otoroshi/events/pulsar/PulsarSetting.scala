package otoroshi.events.pulsar

import org.apache.pulsar.client.api.{PulsarClient as ApachePulsarClient, Producer as ApacheProducer, Consumer as ApacheConsumer, Schema}
import org.apache.pulsar.client.impl.auth.{AuthenticationBasic, AuthenticationToken}
import otoroshi.events.pulsar.PulsarPlayJsonSchema.*
import play.api.libs.json.*

object PulsarSetting {
  def client(_env: otoroshi.env.Env, config: PulsarConfig): ApachePulsarClient = {
    val builder = ApachePulsarClient
      .builder()
      .serviceUrl(config.uri)

    if (config.mtlsConfig.mtls) {
      val (_, jks, password) = config.mtlsConfig.toJKS(using _env)

      builder
        .enableTlsHostnameVerification(false)
        .allowTlsInsecureConnection(config.mtlsConfig.trustAll)
        .tlsTrustStoreType("JKS")
        .tlsTrustStorePassword(password)
        .tlsTrustCertsFilePath(jks.getAbsolutePath)
        .useKeyStoreTls(true)

      config.tlsTrustCertsFilePath.foreach(builder.tlsTrustCertsFilePath)
    } else {
      config.token
        .map(token => builder.authentication(new AuthenticationToken(token)))
        .orElse(for {
          username <- config.username
          password <- config.password
        } yield {
          val auth = new AuthenticationBasic()
          auth.configure(Json.stringify(Json.obj("userId" -> username, "password" -> password)))
          builder.authentication(auth)
        })
    }

    builder.build()
  }

  def producer(_env: otoroshi.env.Env, config: PulsarConfig): ApacheProducer[JsValue] = {
    val topic = s"persistent://${config.tenant}/${config.namespace}/${config.topic}"
    val cli   = client(_env, config)
    cli.newProducer(playSchema[JsValue]).topic(topic).create()
  }

  def consumer(_env: otoroshi.env.Env, config: PulsarConfig): ApacheConsumer[JsValue] = {
    val topic = s"persistent://${config.tenant}/${config.namespace}/${config.topic}"
    val cli   = client(_env, config)
    cli.newConsumer(playSchema[JsValue]).topic(topic).subscriptionName("otoroshi").subscribe()
  }
}
