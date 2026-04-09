package otoroshi.statefulclients

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.{KafkaProducer, Producer}
import org.apache.kafka.common.config.{SaslConfigs, SslConfigs}
import org.apache.kafka.common.config.internals.BrokerSecurityConfigs
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsObject

import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

object KafkaStatefulClientConfig {
  def apply(obj: JsObject) = new KafkaStatefulClientConfig(
    servers = obj.select("servers").asOpt[Seq[String]].getOrElse(Seq("localhost:9092")),
    securityProtocol = obj.select("securityProtocol").asOpt[String].getOrElse("PLAINTEXT"),
    keyPass = obj.select("keyPass").asOpt[String],
    keystore = obj.select("keystore").asOpt[String],
    truststore = obj.select("truststore").asOpt[String],
    hostValidation = obj.select("hostValidation").asOpt[Boolean].getOrElse(true),
    saslMechanism = obj.select("saslMechanism").asOpt[String],
    saslUsername = obj.select("saslUsername").asOpt[String],
    saslPassword = obj.select("saslPassword").asOpt[String],
    saslJaasConfig = obj.select("saslJaasConfig").asOpt[String],
    properties = obj.select("properties").asOpt[Map[String, String]].getOrElse(Map.empty)
  )
}

case class KafkaStatefulClientConfig(
  servers: Seq[String],
  securityProtocol: String = "PLAINTEXT",
  keyPass: Option[String] = None,
  keystore: Option[String] = None,
  truststore: Option[String] = None,
  hostValidation: Boolean = true,
  saslMechanism: Option[String] = None,
  saslUsername: Option[String] = None,
  saslPassword: Option[String] = None,
  saslJaasConfig: Option[String] = None,
  properties: Map[String, String] = Map.empty
) extends StatefulClientConfig[Producer[Array[Byte], String]] {

  private val open = new AtomicBoolean(false)

  private def getSaslJaasClass(mechanism: String): String = mechanism match {
    case "SCRAM-SHA-512" | "SCRAM-SHA-256" => "org.apache.kafka.common.security.scram.ScramLoginModule"
    case _                                 => "org.apache.kafka.common.security.plain.PlainLoginModule"
  }

  override def start(): Producer[Array[Byte], String] = {
    val props = new Properties()
    props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, servers.mkString(","))
    props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol)
    securityProtocol match {
      case "SSL" | "SASL_SSL" if keystore.isDefined && truststore.isDefined && keyPass.isDefined =>
        props.put(BrokerSecurityConfigs.SSL_CLIENT_AUTH_CONFIG, "required")
        props.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, keyPass.get)
        props.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, keystore.get)
        props.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, keyPass.get)
        props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, truststore.get)
        props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, keyPass.get)
        if (!hostValidation) props.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "")
      case _ => ()
    }
    if (securityProtocol == "SASL_SSL" || securityProtocol == "SASL_PLAINTEXT") {
      val mechanism = saslMechanism.getOrElse("PLAIN")
      val username = saslUsername.getOrElse("")
      val password = saslPassword.getOrElse("")
      props.put(SaslConfigs.SASL_MECHANISM, mechanism)
      props.put(SaslConfigs.SASL_JAAS_CONFIG, saslJaasConfig.getOrElse(
        s"""${getSaslJaasClass(mechanism)} required username="$username" password="$password";"""
      ))
    }
    properties.foreach { case (k, v) => props.put(k, v) }
    val producer = new KafkaProducer[Array[Byte], String](props, new ByteArraySerializer(), new StringSerializer())
    open.set(true)
    producer
  }

  override def stop(client: Producer[Array[Byte], String]): Unit = {
    open.set(false)
    client.close()
  }

  override def isOpen(client: Producer[Array[Byte], String]): Boolean = open.get()

  override def sameConfig(other: StatefulClientConfig[_]): Boolean = other match {
    case k: KafkaStatefulClientConfig =>
      k.servers == servers &&
      k.securityProtocol == securityProtocol &&
      k.keyPass == keyPass &&
      k.keystore == keystore &&
      k.truststore == truststore &&
      k.saslMechanism == saslMechanism &&
      k.saslUsername == saslUsername &&
      k.saslPassword == saslPassword &&
      k.properties == properties
    case _ => false
  }
}
