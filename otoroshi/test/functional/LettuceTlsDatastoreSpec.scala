package functional

import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.GenericContainer.FileSystemBind
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.wait.strategy.Wait
import play.api.Configuration

import java.nio.file.{Files, Path, Paths}

/**
 * Boots a redis that only accepts TLS connections presenting a client certificate, and runs
 * otoroshi on top of it with the `lettuce` datastore. The whole TLS material is handed over
 * through `app.redis.lettuce.ssl`, ie. what this spec is really about: no `javax.net.ssl.*Store`
 * system property is involved.
 *
 * The CA is passed as a `file://` path and the client material as inline PEM, so that both shapes
 * are exercised on a real handshake.
 *
 * Run it alone with:
 *   sbt 'testOnly functional.LettuceTlsDatastoreSpec'
 */
class LettuceTlsDatastoreSpec extends ContainerizedDatastoreSpec {

  private val redisPort = 6379

  private def certPath(name: String): Path =
    Paths.get(getClass.getResource(s"/certificates/redis-tls/$name").toURI)

  private def certPem(name: String): String = Files.readString(certPath(name))

  private def containerBind(name: String): FileSystemBind =
    FileSystemBind(s"certificates/redis-tls/$name", s"/certs/$name", BindMode.READ_ONLY)

  override def storeName: String = "lettuce (lettuce-core) over TLS with a client certificate"

  override lazy val container: GenericContainer = GenericContainer(
    dockerImage = "redis:7-alpine",
    exposedPorts = Seq(redisPort),
    // plain connections are disabled, and a client certificate signed by the test CA is required
    command = Seq(
      "redis-server",
      "--port",
      "0",
      "--tls-port",
      redisPort.toString,
      "--tls-cert-file",
      "/certs/server-cert.pem",
      "--tls-key-file",
      "/certs/server-key.pem",
      "--tls-ca-cert-file",
      "/certs/ca-cert.pem",
      "--tls-auth-clients",
      "yes"
    ),
    classpathResourceMapping = Seq(
      containerBind("server-cert.pem"),
      containerBind("server-key.pem"),
      containerBind("ca-cert.pem")
    ),
    waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)
  )

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration
      .from(
        Map(
          "app.storage"                        -> "lettuce",
          "app.redis.lettuce.connection"       -> "standalone",
          // rediss:// is what turns the ssl material below into an actual TLS handshake
          "app.redis.lettuce.uri"              -> s"rediss://${container.host}:${container.mappedPort(redisPort)}/0",
          "app.redis.lettuce.pooling.enabled"  -> false,
          "app.redis.lettuce.ssl.trustedCerts" -> s"file://${certPath("ca-cert.pem")}",
          "app.redis.lettuce.ssl.clientCert"   -> certPem("client-cert.pem"),
          "app.redis.lettuce.ssl.clientKey"    -> certPem("client-key.pem")
        )
      )
      .withFallback(configuration)
  }
}
