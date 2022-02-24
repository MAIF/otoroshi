package otoroshi.ssl

import java.net.Socket
import java.security.{Principal, PrivateKey}
import java.security.cert.X509Certificate
import com.github.blemale.scaffeine._
import otoroshi.env.Env

import javax.net.ssl.{KeyManager, SSLEngine, SSLSession, X509ExtendedKeyManager, X509KeyManager}
import otoroshi.models.{GlobalConfig, TlsSettings}
import otoroshi.utils.http.DN
import otoroshi.utils.syntax.implicits._
import play.api.Logger

import scala.concurrent.duration._

object KeyManagerCompatibility {

  def keyManager(allCerts: () => Seq[Cert], client: Boolean, manager: X509KeyManager, env: Env): KeyManager = {
    // new X509KeyManagerSnitch(manager)
    new DynamicKeyManager(allCerts, client, manager, env)
  }
  def session(key: String): Option[(SSLSession, PrivateKey, Array[X509Certificate])] = {
    // Option(X509KeyManagerSnitch._sslSessions.getIfPresent(key))
    DynamicKeyManager.sessions.getIfPresent(key)
  }
}

object DynamicKeyManager {
  val cache    = Scaffeine().maximumSize(1000).expireAfterWrite(5.seconds).build[String, Cert]
  val sessions = Scaffeine()
    .maximumSize(1000)
    .expireAfterWrite(5.seconds)
    .build[String, (SSLSession, PrivateKey, Array[X509Certificate])]
}

class DynamicKeyManager(allCerts: () => Seq[Cert], client: Boolean, manager: X509KeyManager, env: Env)
    extends X509ExtendedKeyManager {

  private val logger                                 = Logger("otoroshi-dyn-key-manager")
  private lazy val allCertificates: Seq[Cert]        = allCerts()
  private lazy val validCerts                        = allCertificates
    .map(_.enrich())
    .filter(c => c.notRevoked && c.notExpired && !c.ca && !c.keypair)
    .sortWith((c1, c2) => c1.to.compareTo(c2.to) > 0)
  private lazy val certsByDomains: Map[String, Cert] = validCerts.flatMap(c => c.allDomains.map(d => (d, c))).toMap

  override def getClientAliases(keyType: String, issuers: Array[Principal]): Array[String] =
    manager.getClientAliases(keyType, issuers)

  override def chooseClientAlias(keyType: Array[String], issuers: Array[Principal], socket: Socket): String =
    manager.chooseClientAlias(keyType, issuers, socket)

  override def getServerAliases(keyType: String, issuers: Array[Principal]): Array[String] =
    manager.getServerAliases(keyType, issuers)

  override def chooseServerAlias(keyType: String, issuers: Array[Principal], socket: Socket): String =
    manager.chooseServerAlias(keyType, issuers, socket)

  override def chooseEngineClientAlias(keyType: Array[String], issuers: Array[Principal], engine: SSLEngine): String = {
    // val res = chooseClientAlias(keyType, issuers, null)
    // println("chooseEngineClientAlias", res, issuers.map(_.getName()).mkString("|"))
    issuers.map(_.getName()).mkString("|")
  }

  def findCertMatching(domain: String): Option[Cert] = {
    // logger.debug(s"[${domain}] trying to find cert")
    if (client) {
      if (allCertificates.isEmpty) {
        None
      } else if (allCertificates.size == 1) {
        allCertificates.headOption
      } else {
        val dns   = domain.split("\\|").toSeq.map(DN.apply)
        val certs = validCerts
          .filter { c =>
            val dnses = c.certificates.map(_.getSubjectDN.getName).map(DN.apply)
            dnses.exists(dn => dns.exists(v => v.isEqualsTo(dn)))
          }
          .sortWith((c1, c2) => c1.to.compareTo(c2.to) > 0)
        certs.headOption
      }
    } else {
      DynamicKeyManager.cache.getIfPresent(domain) match {
        case Some(cert) =>
          // logger.debug(s"[${domain}] found cert from cache: ${cert.id} - '${cert.name}'")
          Some(cert)
        case None       => {

          val tlsSettings = env.datastores.globalConfigDataStore.latestSafe.map(_.tlsSettings).getOrElse(TlsSettings())

          val directCert = certsByDomains.get(domain)

          val maybeCert: Option[Cert] = directCert
            .orElse {
              // foundCert: no * before with * then longer before smaller
              validCerts
                .flatMap(c => c.allDomains.map(d => (d, c)))
                .filter(c => c._2.sanMatchesDomain(domain, c._1))
                .sortWith {
                  case ((d1, _), (d2, _)) if d1.contains("*") && d2.contains("*")   => d1.size > d2.size
                  case ((d1, _), (d2, _)) if d1.contains("*") && !d2.contains("*")  => false
                  case ((d1, _), (d2, _)) if !d1.contains("*") && d2.contains("*")  => true
                  case ((d1, _), (d2, _)) if !d1.contains("*") && !d2.contains("*") => true
                }
                .seffectOnIf(logger.isDebugEnabled)(certs =>
                  logger.debug(s"possible certificates for '$domain': \n${certs
                    .map(c => s"  * '${c._2.name}' | '${c._1}' | - ${c._2.allDomains.mkString(", ")}")
                    .mkString("\n")}")
                )
                .map(_._2)
                .headOption
                .seffectOnIf(logger.isDebugEnabled)(opt =>
                  logger.debug(s"choosing '${opt.map(_.name).getOrElse("--")}'")
                )
            }
            .orElse {
              //foundCertDef
              tlsSettings.defaultDomain.flatMap { d =>
                validCerts
                  .flatMap(c => c.allDomains.map(d => (d, c)))
                  .filter(c => c._2.sanMatchesDomain(domain, c._1))
                  .sortWith {
                    case ((d1, _), (d2, _)) if d1.contains("*") && d2.contains("*")   => d1.size > d2.size
                    case ((d1, _), (d2, _)) if d1.contains("*") && !d2.contains("*")  => false
                    case ((d1, _), (d2, _)) if !d1.contains("*") && d2.contains("*")  => true
                    case ((d1, _), (d2, _)) if !d1.contains("*") && !d2.contains("*") => true
                  }
                  .map(_._2)
                  .headOption
              }
            }

          // certs.find(_.matchesDomain(domain)).orElse(tlsSettings.defaultDomain.flatMap(d => certs.find(_.matchesDomain(d)))).map { c =>
          maybeCert.map { c =>
            DynamicKeyManager.cache.put(domain, c)
            c
          } match {
            case None if tlsSettings.randomIfNotFound => {
              validCerts
                .filterNot(_.client)
                .headOption
                .map { c =>
                  DynamicKeyManager.cache.put(domain, c)
                  // logger.debug(s"[${domain}] found random cert : ${c.id} - '${c.name}'")
                  c
                }
            }
            case None                                 =>
              // logger.debug(s"[${domain}] no cert found !")
              None
            case s @ Some(cert)                       =>
              // logger.debug(s"[${domain}] found cert : ${cert.id} - '${cert.name}'")
              s
          }
        }
      }
    }
  }

  override def getCertificateChain(domain: String): Array[X509Certificate] = {
    // logger.debug(s"[${domain}] trying to get cert chain ...")
    findCertMatching(domain) match {
      case None       => manager.getCertificateChain(domain)
      case Some(cert) => cert.certificatesChain
    }
  }

  override def getPrivateKey(domain: String): PrivateKey = {
    // logger.debug(s"[${domain}] trying to get private key ...")
    findCertMatching(domain) match {
      case None       => manager.getPrivateKey(domain)
      case Some(cert) => cert.cryptoKeyPair.getPrivate
    }
  }

  override def chooseEngineServerAlias(keyType: String, issuers: Array[Principal], engine: SSLEngine): String = {
    val latestConfig: Option[GlobalConfig] = env.datastores.globalConfigDataStore.latestSafe
    val defaultDomain: Option[String]      = latestConfig.flatMap(_.tlsSettings.defaultDomain)
    Option(engine.getPeerHost)
      .orElse(defaultDomain)
      .map { domain =>
        val autoCertEnabled        = latestConfig.exists(_.autoCert.enabled)
        val replyNicelyEnabled     = latestConfig.exists(_.autoCert.replyNicely)
        val sessionKey             = SSLSessionJavaHelper.computeKey(engine.getHandshakeSession)
        val matchesAutoCertDomains = latestConfig.exists(_.autoCert.matches(domain))
        // logger.debug(s"trying to extract domain from SNI. default is '${defaultDomain}'")
        findCertMatching(domain) match {
          case Some(cert)                                                                =>
            sessionKey.foreach(key =>
              DynamicKeyManager.sessions
                .put(key, (engine.getSession, cert.cryptoKeyPair.getPrivate, cert.certificatesChain))
            )
          case None if autoCertEnabled && !replyNicelyEnabled && !matchesAutoCertDomains => ()
          case None if autoCertEnabled                                                   =>
            env.datastores.certificatesDataStore.jautoGenerateCertificateForDomain(domain, env) match {
              case Some(genCert) =>
                if (!genCert.subject.contains(SSLSessionJavaHelper.NotAllowed)) {
                  DynamicSSLEngineProvider.addCertificates(Seq(genCert), env)
                }
                DynamicKeyManager.cache.put(domain, genCert)
                sessionKey.foreach(key =>
                  DynamicKeyManager.sessions
                    .put(key, (engine.getSession, genCert.cryptoKeyPair.getPrivate, genCert.certificatesChain))
                )
              case None          => ()
            }
          case None                                                                      => ()
        }
        domain
      }
      .getOrElse {
        throw new NoHostnameFoundException()
      }
  }
}
