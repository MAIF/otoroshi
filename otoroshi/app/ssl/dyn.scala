package otoroshi.ssl

import java.security.{Provider, SecureRandom}
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

import com.typesafe.config.Config
import com.typesafe.sslconfig.ssl._
import com.typesafe.sslconfig.util.{LoggerFactory, NoDepsLogger}
import javax.net.ssl._
import play.api.Logger

case class ConfigAndHash(config: Config, hash: String)
case class SSLConfigAndHash(config: SSLConfigSettings, hash: String)

class PlayLoggerFactory(logger: Logger) extends LoggerFactory {
  override def apply(clazz: Class[_]): NoDepsLogger = new PlayLoggerFactoryLogger(logger)
  override def apply(name: String): NoDepsLogger    = new PlayLoggerFactoryLogger(logger)
}

class PlayLoggerFactoryLogger(logger: Logger) extends NoDepsLogger {
  override def isDebugEnabled: Boolean                        = logger.isDebugEnabled
  override def debug(msg: String): Unit                       = logger.debug(msg)
  override def info(msg: String): Unit                        = logger.info(msg)
  override def warn(msg: String): Unit                        = logger.warn(msg)
  override def error(msg: String): Unit                       = logger.error(msg)
  override def error(msg: String, throwable: Throwable): Unit = logger.error(msg, throwable)
}

object DynamicSSLContext {

  private val logger   = Logger("otoroshi-dynamic-sslcontext")
  private val mkLogger = new PlayLoggerFactory(logger)

  def fromConfig(config: => ConfigAndHash): SSLContext =
    fromSSLConfig {
      val ConfigAndHash(cfg, hash) = config
      SSLConfigAndHash(SSLConfigFactory.parse(cfg), hash)
    }

  def fromSSLConfig(config: => SSLConfigAndHash): SSLContext = {
    val sslContext = new SSLContext(
      new SSLContextSpi() {

        private val lastHash   = new AtomicReference[String]("none")
        private val lastCtx    = new AtomicReference[SSLContext]()
        private val lastConfig = new AtomicReference[SSLConfigSettings]()

        private def looseDisableSNI(sslConfig: SSLConfigSettings, defaultParams: SSLParameters): Unit =
          if (sslConfig.loose.disableSNI) {
            defaultParams.setServerNames(Collections.emptyList())
            defaultParams.setSNIMatchers(Collections.emptyList())
          }

        private def buildKeyManagerFactory(ssl: SSLConfigSettings): KeyManagerFactoryWrapper = {
          val keyManagerAlgorithm = ssl.keyManagerConfig.algorithm
          new DefaultKeyManagerFactoryWrapper(keyManagerAlgorithm)
        }

        private def buildTrustManagerFactory(ssl: SSLConfigSettings): TrustManagerFactoryWrapper = {
          val trustManagerAlgorithm = ssl.trustManagerConfig.algorithm
          new DefaultTrustManagerFactoryWrapper(trustManagerAlgorithm)
        }

        private def configureProtocols(
            existingProtocols: Array[String],
            sslConfig: SSLConfigSettings
        ): Array[String] = {
          val definedProtocols = sslConfig.enabledProtocols match {
            case Some(configuredProtocols) =>
              // If we are given a specific list of protocols, then return it in exactly that order,
              // assuming that it's actually possible in the SSL context.
              configuredProtocols.filter(existingProtocols.contains).toArray

            case None =>
              // Otherwise, we return the default protocols in the given list.
              Protocols.recommendedProtocols.filter(existingProtocols.contains)
          }

          val allowWeakProtocols = sslConfig.loose.allowWeakProtocols
          if (!allowWeakProtocols) {
            val deprecatedProtocols = Protocols.deprecatedProtocols
            for (deprecatedProtocol <- deprecatedProtocols) {
              if (definedProtocols.contains(deprecatedProtocol)) {
                throw new IllegalStateException(s"Weak protocol $deprecatedProtocol found in ssl-config.protocols!")
              }
            }
          }
          definedProtocols
        }

        private def configureCipherSuites(
            existingCiphers: Array[String],
            sslConfig: SSLConfigSettings
        ): Array[String] = {
          val definedCiphers = sslConfig.enabledCipherSuites match {
            case Some(configuredCiphers) =>
              // If we are given a specific list of ciphers, return it in that order.
              configuredCiphers.filter(existingCiphers.contains(_)).toArray

            case None =>
              Ciphers.recommendedCiphers.filter(existingCiphers.contains(_)).toArray
          }

          val allowWeakCiphers = sslConfig.loose.allowWeakCiphers
          if (!allowWeakCiphers) {
            val deprecatedCiphers = Ciphers.deprecatedCiphers
            for (deprecatedCipher <- deprecatedCiphers) {
              if (definedCiphers.contains(deprecatedCipher)) {
                throw new IllegalStateException(s"Weak cipher $deprecatedCipher found in ssl-config.ciphers!")
              }
            }
          }
          definedCiphers
        }

        private def getConfig(): SSLConfigSettings = lastConfig.get()

        private def getCtx(): SSLContext = {
          val currentConfig                     = lastConfig.get()
          val currentCtx                        = lastCtx.get()
          val SSLConfigAndHash(sslConfig, hash) = config
          if (currentConfig == null || currentCtx == null || hash != lastHash.get()) {
            val keyManagerFactory   = buildKeyManagerFactory(sslConfig)
            val trustManagerFactory = buildTrustManagerFactory(sslConfig)
            val newCtx              =
              new ConfigSSLContextBuilder(mkLogger, sslConfig, keyManagerFactory, trustManagerFactory).build()
            lastConfig.set(sslConfig)
            lastCtx.set(newCtx)
            newCtx
          } else {
            currentCtx
          }
        }

        private def createSSLEngine(): SSLEngine = {
          // protocols!
          val defaultParams    = getCtx().getDefaultSSLParameters
          val defaultProtocols = defaultParams.getProtocols

          val sslConfig: SSLConfigSettings = getConfig()
          val protocols                    = configureProtocols(defaultProtocols, sslConfig)
          // ciphers!
          val defaultCiphers               = defaultParams.getCipherSuites
          val cipherSuites                 = configureCipherSuites(defaultCiphers, sslConfig)
          // apply "loose" settings
          looseDisableSNI(sslConfig, defaultParams)

          val engine = getCtx().createSSLEngine()
          engine.setSSLParameters(getCtx().getDefaultSSLParameters)
          engine.setEnabledProtocols(protocols)
          engine.setEnabledCipherSuites(cipherSuites)
          engine
        }

        override def engineCreateSSLEngine(): SSLEngine                     = createSSLEngine()
        override def engineCreateSSLEngine(s: String, i: Int): SSLEngine    = engineCreateSSLEngine()
        override def engineInit(
            keyManagers: Array[KeyManager],
            trustManagers: Array[TrustManager],
            secureRandom: SecureRandom
        ): Unit                                                             = ()
        override def engineGetClientSessionContext(): SSLSessionContext     = getCtx().getClientSessionContext
        override def engineGetServerSessionContext(): SSLSessionContext     = getCtx().getServerSessionContext
        override def engineGetSocketFactory(): SSLSocketFactory             = getCtx().getSocketFactory
        override def engineGetServerSocketFactory(): SSLServerSocketFactory = getCtx().getServerSocketFactory
      },
      new Provider("Otoroshi dynamic SSLContext", 1d, "A dynamic SSLContext that can be reconfigured on the fly") {},
      "Otoroshi dynamic SSLContext"
    ) {}
    sslContext
  }

}
