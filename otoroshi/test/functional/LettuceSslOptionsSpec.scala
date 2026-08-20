package functional

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.storage.drivers.lettuce.LettuceSslOptions
import play.api.Configuration

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Base64

/**
 * The redis TLS material is read from the configuration, way before otoroshi can read anything from
 * its datastore. This spec checks every accepted shape (inline PEM, base64 PEM, file:// path) and
 * that lettuce and netty actually accept the resulting material.
 */
class LettuceSslOptionsSpec extends AnyWordSpec with Matchers {

  private val prefix = "app.redis.lettuce.ssl"

  private def path(name: String): Path = Paths.get(getClass.getResource(s"/certificates/oto.bar/$name").toURI)

  private def pem(name: String): String = Files.readString(path(name))

  private def base64(content: String): String =
    Base64.getEncoder.encodeToString(content.getBytes(StandardCharsets.UTF_8))

  private def config(values: (String, String)*): Configuration =
    Configuration.from(values.map { case (key, value) => s"$prefix.$key" -> value }.toMap)

  // building the netty context is what lettuce does when it opens a TLS connection, so it is the
  // actual proof that the material we handed over is usable
  private def buildsANettySslContext(configuration: Configuration): Boolean = {
    LettuceSslOptions(configuration, prefix).exists { options =>
      options.createSslContextBuilder().build().isClient
    }
  }

  "LettuceSslOptions" should {

    "return nothing when no material is configured" in {
      LettuceSslOptions(config(), prefix) mustBe None
      LettuceSslOptions(config("trustedCerts" -> "  "), prefix) mustBe None
    }

    "read trusted certs from an inline PEM" in {
      buildsANettySslContext(config("trustedCerts" -> pem("ca-cert.pem"))) mustBe true
    }

    "read trusted certs from a base64 encoded PEM" in {
      buildsANettySslContext(config("trustedCerts" -> base64(pem("ca-cert.pem")))) mustBe true
    }

    "read trusted certs from a file:// path" in {
      buildsANettySslContext(config("trustedCerts" -> s"file://${path("ca-cert.pem")}")) mustBe true
    }

    "read a whole CA bundle, not only its first certificate" in {
      val bundle = pem("ca-cert.pem") + "\n" + pem("server-cert.pem")
      buildsANettySslContext(config("trustedCerts" -> bundle)) mustBe true
    }

    "read a client certificate and its key for mTLS" in {
      buildsANettySslContext(
        config(
          "trustedCerts" -> pem("ca-cert.pem"),
          "clientCert"   -> pem("client-fullchain.pem"),
          "clientKey"    -> pem("client-key.pem")
        )
      ) mustBe true
    }

    "read a client certificate and its key from file:// paths" in {
      buildsANettySslContext(
        config(
          "clientCert" -> s"file://${path("client-fullchain.pem")}",
          "clientKey"  -> s"file://${path("client-key.pem")}"
        )
      ) mustBe true
    }

    "read a client certificate whose PEM bundle also holds the key" in {
      val bundle = pem("client-fullchain.pem") + "\n" + pem("client-key.pem")
      buildsANettySslContext(config("clientCert" -> bundle)) mustBe true
    }

    "reject a client certificate without any key" in {
      val error = intercept[RuntimeException] {
        LettuceSslOptions(config("clientCert" -> pem("client-cert.pem")), prefix)
      }
      error.getMessage must include(s"'$prefix.clientKey'")
    }

    "reject a key without any client certificate" in {
      val error = intercept[RuntimeException] {
        LettuceSslOptions(config("clientKey" -> pem("client-key.pem")), prefix)
      }
      error.getMessage must include(s"'$prefix.clientCert'")
    }

    "reject material that is not readable instead of silently connecting without it" in {
      intercept[RuntimeException] {
        LettuceSslOptions(config("trustedCerts" -> "-----BEGIN CERTIFICATE-----\nnot a certificate\n"), prefix)
      }
      intercept[RuntimeException] {
        LettuceSslOptions(
          config(
            "clientCert" -> pem("client-fullchain.pem"),
            "clientKey"  -> "-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----"
          ),
          prefix
        )
      }
    }

    "ignore a value that is neither a PEM nor a base64 encoded one" in {
      LettuceSslOptions(config("trustedCerts" -> "/etc/ssl/certs/ca.pem"), prefix) mustBe None
    }
  }
}
