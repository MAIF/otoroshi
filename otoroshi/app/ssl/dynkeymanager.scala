package ssl

import java.net.Socket
import java.security.{Principal, PrivateKey}
import java.security.cert.X509Certificate

import com.github.blemale.scaffeine._
import env.Env
import javax.net.ssl.{KeyManager, SSLEngine, SSLSession, X509ExtendedKeyManager, X509KeyManager}

import scala.concurrent.duration._

object KeyManagerCompatibility {

  def keyManager(manager: X509KeyManager, env: Env): KeyManager = {
    // new X509KeyManagerSnitch(manager)
    new DynamicKeyManager(manager, env)
  }
  def session(key: String): Option[(SSLSession, PrivateKey, Array[X509Certificate])] = {
    // Option(X509KeyManagerSnitch._sslSessions.getIfPresent(key))
    DynamicKeyManager.sessions.getIfPresent(key)
  }
}

object DynamicKeyManager {
  val cache = Scaffeine().maximumSize(1000).expireAfterWrite(5.seconds).build[String, Cert]
  val sessions = Scaffeine().maximumSize(1000).expireAfterWrite(5.seconds).build[String, (SSLSession, PrivateKey, Array[X509Certificate])]
}

class DynamicKeyManager(manager: X509KeyManager, env: Env) extends X509ExtendedKeyManager {

  override def getClientAliases(keyType: String, issuers: Array[Principal]): Array[String] = manager.getClientAliases(keyType, issuers)

  override def chooseClientAlias(keyType: Array[String], issuers: Array[Principal], socket: Socket): String = manager.chooseClientAlias(keyType, issuers, socket)

  override def getServerAliases(keyType: String, issuers: Array[Principal]): Array[String] = manager.getServerAliases(keyType, issuers)

  override def chooseServerAlias(keyType: String, issuers: Array[Principal], socket: Socket): String = manager.chooseServerAlias(keyType, issuers, socket)

  override def chooseEngineClientAlias(keyType: Array[String], issuers: Array[Principal], engine: SSLEngine): String = chooseClientAlias(keyType, issuers, null)

  def findCertMatching(domain: String): Option[Cert] = {
    DynamicKeyManager.cache.getIfPresent(domain) match {
      case Some(cert) => Some(cert)
      case None => {
        DynamicSSLEngineProvider.certificates
          .values.toSeq
          .map(_.enrich())
          .filter(_.matchesDomain(domain))
          .filter(_.notExpired)
          .sortWith((c1, c2) => c1.to.compareTo(c2.to) > 0)
          .headOption.map { c =>
            DynamicKeyManager.cache.put(domain, c)
            c
          }
      }
    }
  }

  override def getCertificateChain(domain: String): Array[X509Certificate] = {
    findCertMatching(domain) match {
      case None => manager.getCertificateChain(domain)
      case Some(cert) => cert.certificatesChain
    }
  }

  override def getPrivateKey(domain: String): PrivateKey = {
    findCertMatching(domain) match {
      case None => manager.getPrivateKey(domain)
      case Some(cert) => cert.cryptoKeyPair.getPrivate
    }
  }

  override def chooseEngineServerAlias(keyType: String, issuers: Array[Principal], engine: SSLEngine): String = {
    Option(engine.getPeerHost).map { domain =>
      val autoCertEnabled = env.datastores.globalConfigDataStore.latestSafe.exists(_.autoCert.enabled)
      val replyNicelyEnabled = env.datastores.globalConfigDataStore.latestSafe.exists(_.autoCert.replyNicely)
      val sessionKey = SSLSessionJavaHelper.computeKey(engine.getHandshakeSession)
      val matchesAutoCertDomains = env.datastores.globalConfigDataStore.latestSafe.exists(_.autoCert.matches(domain))
      findCertMatching(domain) match {
        case Some(cert) => sessionKey.foreach(key => DynamicKeyManager.sessions.put(key, (engine.getSession, cert.cryptoKeyPair.getPrivate, cert.certificatesChain)))
        case None if autoCertEnabled && !matchesAutoCertDomains => ()
        case None if autoCertEnabled && !replyNicelyEnabled && !matchesAutoCertDomains => ()
        case None if autoCertEnabled => env.datastores.certificatesDataStore.jautoGenerateCertificateForDomain(domain, env) match {
          case Some(genCert) =>
            DynamicSSLEngineProvider.addCertificates(Seq(genCert), env)
            DynamicKeyManager.cache.put(domain, genCert)
            sessionKey.foreach(key => DynamicKeyManager.sessions.put(key, (engine.getSession, genCert.cryptoKeyPair.getPrivate, genCert.certificatesChain)))
          case None => ()
        }
        case None => ()
      }
      domain
    }.getOrElse {
      throw new NoHostnameFoundException()
    }
  }
}
