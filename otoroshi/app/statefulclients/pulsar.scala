package otoroshi.statefulclients

import com.sksamuel.pulsar4s.{DefaultPulsarClient, PulsarClient, PulsarClientConfig}
import org.apache.pulsar.client.impl.auth.{AuthenticationBasic, AuthenticationToken}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Json}

import java.util.concurrent.atomic.AtomicBoolean

object PulsarStatefulClientConfig {
  def apply(obj: JsObject) = new PulsarStatefulClientConfig(
    uri = obj.select("uri").asString,
    tlsTrustCertsFilePath = obj.select("tlsTrustCertsFilePath").asOpt[String],
    token = obj.select("token").asOpt[String].filter(_.trim.nonEmpty),
    username = obj.select("username").asOpt[String].filter(_.trim.nonEmpty),
    password = obj.select("password").asOpt[String].filter(_.trim.nonEmpty),
    tlsHostnameVerification = obj.select("tlsHostnameVerification").asOpt[Boolean].getOrElse(false),
    tlsAllowInsecure = obj.select("tlsAllowInsecure").asOpt[Boolean].getOrElse(false)
  )
}

case class PulsarStatefulClientConfig(
  uri: String,
  tlsTrustCertsFilePath: Option[String] = None,
  token: Option[String] = None,
  username: Option[String] = None,
  password: Option[String] = None,
  tlsHostnameVerification: Boolean = false,
  tlsAllowInsecure: Boolean = false
) extends StatefulClientConfig[PulsarClient] {

  private val open = new AtomicBoolean(false)

  override def start(): PulsarClient = {
    val client = if (tlsTrustCertsFilePath.isDefined) {
      val builder = org.apache.pulsar.client.api.PulsarClient
        .builder()
        .serviceUrl(uri)
        .enableTlsHostnameVerification(tlsHostnameVerification)
        .allowTlsInsecureConnection(tlsAllowInsecure)
      tlsTrustCertsFilePath.foreach(builder.tlsTrustCertsFilePath)
      token.foreach(t => builder.authentication(new AuthenticationToken(t)))
      new DefaultPulsarClient(builder.build())
    } else {
      val config = PulsarClientConfig(
        serviceUrl = uri,
        authentication = token.map(t => new AuthenticationToken(t))
          .orElse(for {
            u <- username
            p <- password
          } yield {
            val auth = new AuthenticationBasic()
            auth.configure(Json.stringify(Json.obj("userId" -> u, "password" -> p)))
            auth
          })
      )
      PulsarClient(config)
    }
    open.set(true)
    client
  }

  override def stop(client: PulsarClient): Unit = {
    open.set(false)
    client.close()
  }

  override def isOpen(client: PulsarClient): Boolean = open.get()

  override def sameConfig(other: StatefulClientConfig[_]): Boolean = other match {
    case p: PulsarStatefulClientConfig =>
      p.uri == uri &&
      p.tlsTrustCertsFilePath == tlsTrustCertsFilePath &&
      p.token == token &&
      p.username == username &&
      p.password == password
    case _ => false
  }
}
