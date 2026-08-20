package otoroshi.storage.drivers.lettuce

import io.lettuce.core.SslOptions
import otoroshi.security.IdGenerator
import otoroshi.ssl.{DynamicSSLEngineProvider, PemHeaders}
import otoroshi.utils.syntax.implicits.*
import play.api.{Configuration, Logger}

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.{KeyManagerFactory, TrustManagerFactory}
import scala.util.Try

/**
 * Builds the lettuce [[SslOptions]] from PEM material read in the otoroshi configuration, so that
 * connecting to a redis exposing a custom certificate (or asking for a client one) does not require
 * jvm wide `javax.net.ssl.*Store` system properties anymore.
 *
 * The datastore is the very first thing otoroshi starts, long before certificates can be read from
 * it, so the material can only come from the configuration and from environment variables here.
 */
object LettuceSslOptions {

  private val logger = Logger("otoroshi-lettuce-ssl")

  private val pemMarker  = "-----BEGIN"
  private val filePrefix = "file://"

  /**
   * A configured value holds the PEM itself, its base64 encoded form, or `file:///path/to/file.pem`.
   * The file is read here rather than by `getOptionalWithFileSupport`, whose own file support never
   * triggers for String values.
   */
  private[lettuce] def readPem(value: String): Option[String] = {
    val content = value.trim
    if (content.isEmpty) {
      None
    } else if (content.startsWith(filePrefix)) {
      val file = new File(content.substring(filePrefix.length))
      if (!file.exists() || !file.isFile) {
        throw new RuntimeException(s"the PEM file '${file.getAbsolutePath}' does not exist")
      }
      readPem(Files.readString(file.toPath))
    } else if (content.contains(pemMarker)) {
      content.some
    } else {
      // base64 is the only way to pass a multi line PEM through some environment variable setups
      Try(content.fromBase64).toOption.map(_.trim).filter(_.contains(pemMarker))
    }
  }

  private def holdsPrivateKey(pem: String): Boolean = {
    pem.contains(PemHeaders.BeginPrivateKey) || pem.contains(PemHeaders.BeginPrivateRSAKey) ||
    pem.contains(PemHeaders.BeginPrivateECKey)
  }

  private def readCerts(id: String, pem: String): Seq[X509Certificate] = {
    val certs = DynamicSSLEngineProvider.readCertificateChain(id, pem, log = false)
    if (certs.isEmpty) {
      throw new RuntimeException(s"no certificate found in the PEM content of '$id'")
    }
    certs
  }

  private def trustManagerFactory(pem: String): TrustManagerFactory = {
    val certs    = readCerts("redis trusted certs", pem)
    val keystore = KeyStore.getInstance("JKS")
    keystore.load(null, null)
    certs.zipWithIndex.foreach { case (cert, idx) =>
      keystore.setCertificateEntry(s"redis-trusted-cert-$idx", cert)
    }
    val factory  = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    factory.init(keystore)
    if (logger.isDebugEnabled) logger.debug(s"redis trust material built from ${certs.size} certificate(s)")
    factory
  }

  private def keyManagerFactory(certPem: String, keyPem: String, keyPassword: Option[String]): KeyManagerFactory = {
    val chain    = readCerts("redis client cert", certPem)
    val maybeKey = DynamicSSLEngineProvider.readPrivateKeyUniversal("redis client cert", keyPem, keyPassword, false)
    val key      = maybeKey match {
      case Left(err)  => throw new RuntimeException(s"unable to read the redis client private key: $err")
      case Right(key) => key
    }
    // the in memory keystore needs a password of its own, a JKS refuses to hold a key entry without one
    val password = IdGenerator.token(32).toCharArray
    val keystore = KeyStore.getInstance("JKS")
    keystore.load(null, null)
    keystore.setKeyEntry("redis-client-cert", key, password, chain.toArray)
    val factory  = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    factory.init(keystore, password)
    if (logger.isDebugEnabled) logger.debug(s"redis client key material built from ${chain.size} certificate(s)")
    factory
  }

  /**
   * Reads `$prefix.trustedCerts`, `$prefix.clientCert`, `$prefix.clientKey` and
   * `$prefix.clientKeyPassword`, and returns the matching [[SslOptions]], or `None` when nothing is
   * configured. Any material that cannot be read raises, as booting with a silently ignored trust
   * or key material would either fail later with an opaque TLS error or, worse, connect without the
   * client certificate the operator asked for.
   */
  def apply(configuration: Configuration, prefix: String): Option[SslOptions] = {
    def read(key: String): Option[String] =
      configuration.getOptionalWithFileSupport[String](s"$prefix.$key").flatMap(readPem)

    val trustedCerts      = read("trustedCerts")
    val clientCert        = read("clientCert")
    // the client key is often shipped in the same PEM bundle as its certificate
    val clientKey         = read("clientKey").orElse(clientCert.filter(holdsPrivateKey))
    val clientKeyPassword = configuration
      .getOptionalWithFileSupport[String](s"$prefix.clientKeyPassword")
      .map(_.trim)
      .filter(_.nonEmpty)

    if (clientCert.isDefined && clientKey.isEmpty) {
      throw new RuntimeException(s"'$prefix.clientCert' is set without any private key, set '$prefix.clientKey' too")
    }
    if (clientCert.isEmpty && clientKey.isDefined) {
      throw new RuntimeException(s"'$prefix.clientKey' is set without any certificate, set '$prefix.clientCert' too")
    }

    (trustedCerts, clientCert.zip(clientKey)) match {
      case (None, None)                => None
      case (maybeTrusted, maybeClient) => {
        val builder = SslOptions.builder()
        maybeTrusted.foreach(pem => builder.trustManager(trustManagerFactory(pem)))
        maybeClient.foreach { case (certPem, keyPem) =>
          builder.keyManager(keyManagerFactory(certPem, keyPem, clientKeyPassword))
        }
        logger.info(
          s"redis TLS material read from the configuration (trusted certs: ${maybeTrusted.isDefined}, " +
          s"client cert: ${maybeClient.isDefined})"
        )
        builder.build().some
      }
    }
  }
}
