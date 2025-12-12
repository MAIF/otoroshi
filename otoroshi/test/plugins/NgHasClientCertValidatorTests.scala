package plugins

import akka.stream.scaladsl.Source
import com.typesafe.config.ConfigFactory
import functional.{CustomInetNameResolver, PluginsTestSpec, TargetService}
import io.netty.handler.ssl.SslContextBuilder
import io.netty.resolver.{AddressResolver, AddressResolverGroup, InetSocketAddressResolver}
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.EventExecutor
import otoroshi.api.Otoroshi
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{NgHasClientCertValidator, OverrideHost}
import otoroshi.security.IdGenerator
import otoroshi.ssl.Cert
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.Configuration
import play.api.http.Status
import play.core.server.ServerConfig
import reactor.netty.http.client.HttpClient

import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.cert.CertificateFactory
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}

class NgHasClientCertValidatorTests(parent: PluginsTestSpec) {
  import parent._

  case class OtoroshiInstance(port: Int, configuration: String) {
    private val ref: AtomicReference[Otoroshi] = new AtomicReference[Otoroshi]()
    def stop() = {
      ref.get().stop()
      Source
        .tick(1.millisecond, 1.second, ())
        .mapAsync(1) { _ =>
          ws
            .url(s"http://127.0.0.1:${port}/health")
            .withRequestTimeout(1.second)
            .get()
            .map(r => r.status)
            .recover { case e =>
              0
            }
        }
        .filter(_ != play.mvc.Http.Status.OK)
        .take(1)
        .runForeach(_ => ())
        .futureValue
    }

    def start() = {
      val instanceId = IdGenerator.uuid
      val otoroshi   = Otoroshi(
        ServerConfig(
          address = "0.0.0.0",
          port = port.some,
          sslPort = customHttpsPort.some,
          rootDir = Files.createTempDirectory(s"otoroshi-test-helper-$instanceId").toFile
        ),
        getTestConfiguration(
          Configuration(
            ConfigFactory
              .parseString(configuration.stripMargin)
              .resolve()
          )
        ).underlying
      )
      otoroshi.env.logger.debug(s"Starting Otoroshi on $port!!!")
      ref.set(otoroshi.startAndStopOnShutdown())
      Source
        .tick(1.second, 1.second, ())
        .mapAsync(1) { _ =>
          ws
            .url(s"http://127.0.0.1:${port}/health")
            .withRequestTimeout(1.second)
            .get()
            .map(r => r.status)
            .recover { case e =>
              0
            }
        }
        .filter(_ == play.mvc.Http.Status.OK)
        .take(1)
        .runForeach(_ => ())
        .futureValue
    }
  }

  val customHttpsPort = TargetService.freePort
  val publicInstance  = OtoroshiInstance(
    TargetService.freePort,
    s"""
       |otoroshi.next.state-sync-interval=2000
       |play.server.https.wantClientAuth=true
       |otoroshi.ssl.fromOutside.clientAuth=Want
       |"""
  )
  publicInstance.start()

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[NgHasClientCertValidator]
      )
    ),
    domain = "test-clientcertificate-chain-validator.oto.bar".some,
    customOtoroshiPort = publicInstance.port.some
  )

  val certificateTemplate: Cert = Cert._fmt.reads(getOtoroshiCertificate().futureValue._1).get

  // resources/certificates/oto.bar/server-fullchain.pem + server-key.pem
  val certificate = createOtoroshiCertificate(
    certificateTemplate.copy(
      domain = "*.oto.bar",
      name = "a new certificate",
      description = "A test server certificate",
      chain = """-----BEGIN CERTIFICATE-----
                |MIIFJTCCAw2gAwIBAgIUD3eOu04hnFF0MQXhsrKMhlFq9x0wDQYJKoZIhvcNAQEL
                |BQAwXTELMAkGA1UEBhMCRlIxDjAMBgNVBAgMBVBhcmlzMQ4wDAYDVQQHDAVQYXJp
                |czEPMA0GA1UECgwGVGVzdENBMQwwCgYDVQQLDANEZXYxDzANBgNVBAMMBlRlc3RD
                |QTAgFw0yNTEyMTExMDU3MDdaGA8yMTI1MTExNzEwNTcwN1owgYIxCzAJBgNVBAYT
                |AkZSMRswGQYDVQQIDBJOb3V2ZWxsZS1BcXVpdGFpbmUxFTATBgNVBAcMDFNhaW50
                |LUp1bGllbjEQMA4GA1UECgwHT3RvIEJhcjEZMBcGA1UECwwQT3RvIEJhciBTZXJ2
                |aWNlczESMBAGA1UEAwwJKi5vdG8uYmFyMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A
                |MIIBCgKCAQEArAWwZxcx5DAvCzAMn+HE6Eb8aNh9k+kzjryf0ZBdGL8RQtAckL6+
                |3saBN2hwzAjgZ/uTSZ6BhmK7rfF2ZEYOHIfgn1P2EdiTZPs2tORg0PM9FF6kHzAN
                |acQ3lGhahEcxwJPeokYf18OEhUqaclrEKvz4qBp8cV1qY6+F33RTXlnI89Yyok9K
                |icOhTXpQMWxpANSMBA6sBYqcG2L8LzR7S58xSVh8ny6l5V3+l9yUKe/lNShvwiPO
                |E2jYdWnBFtYGpmcAI0Yaevw3ucU6NO3vmFhYwJb/QOHll9hI37k6iAGJVihGrQ6b
                |fy+BjMbMmUxksbJODCNFUjLEqAhiK1Do5QIDAQABo4G0MIGxME0GA1UdEQRGMESC
                |B290by5iYXKCCSoub3RvLmJhcoIudGVzdC1jbGllbnRjZXJ0aWZpY2F0ZS1jaGFp
                |bi12YWxpZGF0b3Iub3RvLmJhcjALBgNVHQ8EBAMCBaAwEwYDVR0lBAwwCgYIKwYB
                |BQUHAwEwHQYDVR0OBBYEFDp4sHunz8PN+YJVX7hdN12C3u8uMB8GA1UdIwQYMBaA
                |FEi065E60axl/v2dBjwzYwdyGzZvMA0GCSqGSIb3DQEBCwUAA4ICAQBq/dojk4DS
                |chgPfbY2e+UWksQqv2MYuLNXFWi195QukxqCWABIAool11CAyK6v4/VhrKwRojoP
                |Tjo3ApUPCPmRDckxJ+f6T78rrs4bg/KMlFcpkJrnTQwIJUWAICDGLtaVLtX+0F4P
                |RIoSI+SN1ZEpzXzGwxE6XXF715VuV+NIgm+ApkUdvvjcLoC7SUPGHTR19E+nvdDX
                |DXJn/lYpGZ/+rrA/246Tm8xcxFAVjtG6+l6+YM6sNxd78EgbLeIY4jWm5M0EV1BJ
                |UW4SO2VpoFqjc7ZUWM7rAu97TKgUQpUs9tlxir9+ZPGFqJq8qB3ndjpLd0yNO1Ig
                |0bd2o+NqXNyCcHx9QYV1QcIPqTKZOoMzF/GF8uQkwrxC/mY/KJVpaxfSbCfupjU8
                |2eh6VIPKkiEYjtBbFVSX8xIDYoh8j6jU6UetFxT5n1RfbwjBI1NXg3s7SoyrU1AN
                |TZIOc83LX31c2S1AIF6lb50yU2KWCZ5MOoFo2LDQYGLeGJkRCUZlQcT4fRjkjZdK
                |YrEDwdJr0SDXZY0IlaMn1uX8UdM6bZuWbmg2K2aqoSHr2j1PyviYLObV9JQrgYrD
                |mbt75OAHNi8s7EHHZusLFet3Hjmfov1PyHT5r+avjfwOmCEWTGmuwF/3YPrM9AKM
                |hysIPGlKNqH2fKF8E6cp5a4ny5JAtxGNdw==
                |-----END CERTIFICATE-----
                |-----BEGIN CERTIFICATE-----
                |MIIFnTCCA4WgAwIBAgIUL+wAZQyZwReo3GPAxx3DC8howEEwDQYJKoZIhvcNAQEL
                |BQAwXTELMAkGA1UEBhMCRlIxDjAMBgNVBAgMBVBhcmlzMQ4wDAYDVQQHDAVQYXJp
                |czEPMA0GA1UECgwGVGVzdENBMQwwCgYDVQQLDANEZXYxDzANBgNVBAMMBlRlc3RD
                |QTAgFw0yNTEyMTExMDU3MDdaGA8yMTI1MTExNzEwNTcwN1owXTELMAkGA1UEBhMC
                |RlIxDjAMBgNVBAgMBVBhcmlzMQ4wDAYDVQQHDAVQYXJpczEPMA0GA1UECgwGVGVz
                |dENBMQwwCgYDVQQLDANEZXYxDzANBgNVBAMMBlRlc3RDQTCCAiIwDQYJKoZIhvcN
                |AQEBBQADggIPADCCAgoCggIBANud0iO4s2CzdfUHtg2sBOL0g+RmYNAoIDvqURhI
                |h/epTF2ZYrStUtgTxG1cL51LBuv2tbxxuRCmR8sw/BnWQQIBgk6wQ6+L4E69/YuF
                |99exlhQJo8nY+7KB2bOJhk18836jGG2C4xI3WWwg2ZhVlZO9/USTfFHViN2wfApI
                |wFmuA8LrlnT8Ie41BDDYaUX87jmAKmX7GpEOCffZbeDFfO1EQ3HQVYrqY7xO3KK6
                |g8wjqvmJTf5QlbHCrYHzMO+cxzSpUjE04O6r/esAbG13BogeiuOTsFAadj+abU92
                |RGp3W2ZSz2c8wnJI98oHmBsVS9HTFE+w/IcuGKTDuU/+QdbbB876YIhcZt+YQkQL
                |h+wjnBlGwkLf83mhD2evd2CdpgRutb3hRAsIuqvcXxeB0nDUawNnqi5qiJBTmDA/
                |uGU4QBW7eATujvlQE+uL/v5m733O5XMN0kYF7AC0Y3lpUT2jz1TuUMUTpi3Eoq/j
                |8Oa4QVKN+y/+E/70EmUZgEFqE8KlKygQVPIBNFS5cQgCEI6891EpF/13YkMWivP2
                |Z7fvX1BhHZdEgfQrCe3vMQoX1BzLJY59hMsS21x1BsveC/yBIgnEtpHzi3yMuzXu
                |Iaumpt6sGVgDIdnY+b8UBeSSflULIQyuXtSqBCRRz9BaG2h9i6tN1GBnE+o+h47j
                |1VSrAgMBAAGjUzBRMB0GA1UdDgQWBBRItOuROtGsZf79nQY8M2MHchs2bzAfBgNV
                |HSMEGDAWgBRItOuROtGsZf79nQY8M2MHchs2bzAPBgNVHRMBAf8EBTADAQH/MA0G
                |CSqGSIb3DQEBCwUAA4ICAQAiQ6ULzZzbeLDOoV5G5pPYhSXQT/7iRIJ+c6dKyMSd
                |3AdIv/cTU7Z4Dn4+fQXYwklVhdKbDfyAxsEg4kBAaePbLwOgL5nnJX7V3CgPswdZ
                |SsCzH8Ic7NSZB9MviRY+fXnXSBrnYtNWivyKndUq5RkJBKyIa/ilJWjWVBTwoH2x
                |fqWSfv2V/kcpd3YdBCs6kwk1k/Xm7/9X1lCPpyhthB4MCr/48DPhKvWKsvDfSGye
                |jf9wm4oEP7KjdWhyMPoq3Zmb/gzozJgojtUP8j4C18IS3AfKRkTn5pYWtkdBeOCy
                |7GmdCZNjbgFm0hH6OdIvFKSd4ZpXov0rDWsWn+MHoAied4RG+a7H5MTV6SSbLca6
                |y/wuzRaDB5A8IIqrm9PS/qtTIOHoBh7j1ztICCMcAEVDtGB5zSnMKDjF3KjD6RZn
                |Bk7Du01dLz5cYQ/HDldMuvotjqLzzUMOL/nXe6PofIlNeNCcplH43Mi40ijp72oq
                |Pk1Jwv2TTvmGbg1IL0aIT7XS+JVg9CqTxy0cA2LXD+6WywUmRcLrNdJexeQMnHMn
                |w1yTir+9+iUXzmeZfYM1ND+7GdrPehB1mzeJCW4O7ug6xZ1Cg1iNQDdZxEAT2qzX
                |j1Bb6RjWIAKIs3YlDuouk5LvnfwckTQ19z4jTRMbEphu0Fp38+FCSYp/Unj1ojU9
                |rQ==
                |-----END CERTIFICATE-----""".stripMargin,
      caRef = None,
      privateKey = """-----BEGIN PRIVATE KEY-----
                     |MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCsBbBnFzHkMC8L
                     |MAyf4cToRvxo2H2T6TOOvJ/RkF0YvxFC0ByQvr7exoE3aHDMCOBn+5NJnoGGYrut
                     |8XZkRg4ch+CfU/YR2JNk+za05GDQ8z0UXqQfMA1pxDeUaFqERzHAk96iRh/Xw4SF
                     |SppyWsQq/PioGnxxXWpjr4XfdFNeWcjz1jKiT0qJw6FNelAxbGkA1IwEDqwFipwb
                     |YvwvNHtLnzFJWHyfLqXlXf6X3JQp7+U1KG/CI84TaNh1acEW1gamZwAjRhp6/De5
                     |xTo07e+YWFjAlv9A4eWX2EjfuTqIAYlWKEatDpt/L4GMxsyZTGSxsk4MI0VSMsSo
                     |CGIrUOjlAgMBAAECggEAFGEIKO5ihrn+mMC0fixs+2eNd45OMjuqU/qcpGMJ5Gie
                     |TuAAwlUWn6W8oSfKVSGoFCmFpW8VwSnpOg4lDHQQL+kY/0cfG7YgoBHyxTNZFOf8
                     |EHG118wFisYoH3jNYGZeyoW6FldgZltPU8smyO1f2AfoHWIl4/hBJlYg8fwB9GdU
                     |2sggTaiL22/6lNJRXRumsJv4UK7KvLx/C4b6OlDhiWVDc2Ic7IRJjc88Kk+Ur5Qr
                     |xaWVqJYliscRnPdqEe9idVNRRZ1OKgMVKDV+qi3uBH++kJw7dlm0MHx5W9MTq5ea
                     |88Meg5Iyust+nOgwb9ovV8YL0O7XyWjarzRYOUrC8QKBgQDZOVj9wPkhgC/8W121
                     |yXmV7Z27b7mkR2XAWvnee0RV37y6OhgwbcXYT3dclg/WwMbgASGh8IMOeCtYt07M
                     |ayUcaUbbihHOIIUU2qA/CglO7V/oG3ZjdI9clGuZ8Wb5iG81zuvJpaX5ktbkn9fU
                     |o6P7Rgsfia9oExlHvaQLqAI02wKBgQDKurh0qBFlLh2q2rzHRJF+648mrr1f/kSm
                     |uxF7kQQyzJmiKqIEwN3aktdmeStWshFNJ9kPj46b9yXgrmBcsCCdDytEnf/QIoCo
                     |5WNFTQh+0uASMxTeL5kDVfdfII6tMVF76F8ZwiSAQiI6Qgril4iFURjikvuwIaqy
                     |VFUOtdrlPwKBgQCeWtAlLKhxY6GXtoN6IoYgZji2i5wpxmLG94twRSxr7c8Hc5Ju
                     |u5efOU8qj7q8M4zHgAukolDoG3J+GiO3oeRL8fNV2DFisxJRQY/QZOCkSSfBbUPA
                     |/RgFxa0rbHBFONDZyR7awYddiU5fHKeavDCu3UD+nMDifgnP4s/UL4ZsQQKBgHDs
                     |Svyn9XCPnHTj/I1ek1DIM2fPo6rJvkHFJ7rVjyogr18WMkNFjw5GBveMfOiArYR1
                     |ssGpLD2SECYz23cloDT8ExTYkXrFDTeG9qHOg/Ho0mkwzOnqR2gFRZJWV0L/mqzT
                     |Rc3aR2yt6dTbnqaS07e28Y6bYti8GBHXSb207GYPAoGBAL0M1GweOh8ev7Ai4ZGf
                     |ZfCsAT/smWAfP2tzwBGMYY9KLeHPeVsik8FDJuTlhjwabAa/XHUIHW1pMjaxdNZl
                     |jPJxDH0dZAt0u614HIJmmuO2MyCgNHHkyUbq5dSpDlGUIqkIodwrBJ/J/3yYKZ2Z
                     |l7Tu/ohNidulLORKySi39vvx
                     |-----END PRIVATE KEY-----""".stripMargin,
      selfSigned = false,
      ca = false,
      valid = true,
      exposed = false,
      revoked = false,
      autoRenew = false,
      letsEncrypt = false,
      sans = Seq("oto.bar", "*.oto.bar", "test-clientcertificate-chain-validator.oto.bar")
    ),
    customPort = publicInstance.port.some
  )

  val dnsMappings = Map(
    "oto.bar"   -> "127.0.0.1",
    "*.oto.bar" -> "127.0.0.1"
  )

  val resolverGroup = new AddressResolverGroup[InetSocketAddress]() {
    override def newResolver(executor: EventExecutor): AddressResolver[InetSocketAddress] = {
      val nameResolver = new CustomInetNameResolver(executor, dnsMappings)
      new InetSocketAddressResolver(executor, nameResolver)
    }
  }

  Thread.sleep(5000)

  // resources/certificates/oto.bar/ca-cert.pem
  val caCertPem = """-----BEGIN CERTIFICATE-----
                    |MIIFnTCCA4WgAwIBAgIUL+wAZQyZwReo3GPAxx3DC8howEEwDQYJKoZIhvcNAQEL
                    |BQAwXTELMAkGA1UEBhMCRlIxDjAMBgNVBAgMBVBhcmlzMQ4wDAYDVQQHDAVQYXJp
                    |czEPMA0GA1UECgwGVGVzdENBMQwwCgYDVQQLDANEZXYxDzANBgNVBAMMBlRlc3RD
                    |QTAgFw0yNTEyMTExMDU3MDdaGA8yMTI1MTExNzEwNTcwN1owXTELMAkGA1UEBhMC
                    |RlIxDjAMBgNVBAgMBVBhcmlzMQ4wDAYDVQQHDAVQYXJpczEPMA0GA1UECgwGVGVz
                    |dENBMQwwCgYDVQQLDANEZXYxDzANBgNVBAMMBlRlc3RDQTCCAiIwDQYJKoZIhvcN
                    |AQEBBQADggIPADCCAgoCggIBANud0iO4s2CzdfUHtg2sBOL0g+RmYNAoIDvqURhI
                    |h/epTF2ZYrStUtgTxG1cL51LBuv2tbxxuRCmR8sw/BnWQQIBgk6wQ6+L4E69/YuF
                    |99exlhQJo8nY+7KB2bOJhk18836jGG2C4xI3WWwg2ZhVlZO9/USTfFHViN2wfApI
                    |wFmuA8LrlnT8Ie41BDDYaUX87jmAKmX7GpEOCffZbeDFfO1EQ3HQVYrqY7xO3KK6
                    |g8wjqvmJTf5QlbHCrYHzMO+cxzSpUjE04O6r/esAbG13BogeiuOTsFAadj+abU92
                    |RGp3W2ZSz2c8wnJI98oHmBsVS9HTFE+w/IcuGKTDuU/+QdbbB876YIhcZt+YQkQL
                    |h+wjnBlGwkLf83mhD2evd2CdpgRutb3hRAsIuqvcXxeB0nDUawNnqi5qiJBTmDA/
                    |uGU4QBW7eATujvlQE+uL/v5m733O5XMN0kYF7AC0Y3lpUT2jz1TuUMUTpi3Eoq/j
                    |8Oa4QVKN+y/+E/70EmUZgEFqE8KlKygQVPIBNFS5cQgCEI6891EpF/13YkMWivP2
                    |Z7fvX1BhHZdEgfQrCe3vMQoX1BzLJY59hMsS21x1BsveC/yBIgnEtpHzi3yMuzXu
                    |Iaumpt6sGVgDIdnY+b8UBeSSflULIQyuXtSqBCRRz9BaG2h9i6tN1GBnE+o+h47j
                    |1VSrAgMBAAGjUzBRMB0GA1UdDgQWBBRItOuROtGsZf79nQY8M2MHchs2bzAfBgNV
                    |HSMEGDAWgBRItOuROtGsZf79nQY8M2MHchs2bzAPBgNVHRMBAf8EBTADAQH/MA0G
                    |CSqGSIb3DQEBCwUAA4ICAQAiQ6ULzZzbeLDOoV5G5pPYhSXQT/7iRIJ+c6dKyMSd
                    |3AdIv/cTU7Z4Dn4+fQXYwklVhdKbDfyAxsEg4kBAaePbLwOgL5nnJX7V3CgPswdZ
                    |SsCzH8Ic7NSZB9MviRY+fXnXSBrnYtNWivyKndUq5RkJBKyIa/ilJWjWVBTwoH2x
                    |fqWSfv2V/kcpd3YdBCs6kwk1k/Xm7/9X1lCPpyhthB4MCr/48DPhKvWKsvDfSGye
                    |jf9wm4oEP7KjdWhyMPoq3Zmb/gzozJgojtUP8j4C18IS3AfKRkTn5pYWtkdBeOCy
                    |7GmdCZNjbgFm0hH6OdIvFKSd4ZpXov0rDWsWn+MHoAied4RG+a7H5MTV6SSbLca6
                    |y/wuzRaDB5A8IIqrm9PS/qtTIOHoBh7j1ztICCMcAEVDtGB5zSnMKDjF3KjD6RZn
                    |Bk7Du01dLz5cYQ/HDldMuvotjqLzzUMOL/nXe6PofIlNeNCcplH43Mi40ijp72oq
                    |Pk1Jwv2TTvmGbg1IL0aIT7XS+JVg9CqTxy0cA2LXD+6WywUmRcLrNdJexeQMnHMn
                    |w1yTir+9+iUXzmeZfYM1ND+7GdrPehB1mzeJCW4O7ug6xZ1Cg1iNQDdZxEAT2qzX
                    |j1Bb6RjWIAKIs3YlDuouk5LvnfwckTQ19z4jTRMbEphu0Fp38+FCSYp/Unj1ojU9
                    |rQ==
                    |-----END CERTIFICATE-----""".stripMargin

  // resources/certificates/oto.bar/client-cert.pem
  val clientCertPem = """-----BEGIN CERTIFICATE-----
                        |MIIEjzCCAnegAwIBAgIUD3eOu04hnFF0MQXhsrKMhlFq9x4wDQYJKoZIhvcNAQEL
                        |BQAwXTELMAkGA1UEBhMCRlIxDjAMBgNVBAgMBVBhcmlzMQ4wDAYDVQQHDAVQYXJp
                        |czEPMA0GA1UECgwGVGVzdENBMQwwCgYDVQQLDANEZXYxDzANBgNVBAMMBlRlc3RD
                        |QTAeFw0yNTEyMTExMDU3MDdaFw0yNjEyMTExMDU3MDdaMGIxCzAJBgNVBAYTAkZS
                        |MQ4wDAYDVQQIDAVQYXJpczEOMAwGA1UEBwwFUGFyaXMxDzANBgNVBAoMBkNsaWVu
                        |dDEMMAoGA1UECwwDRGV2MRQwEgYDVQQDDAt0ZXN0LWNsaWVudDCCASIwDQYJKoZI
                        |hvcNAQEBBQADggEPADCCAQoCggEBALFc5W5h2QR76LgBj8IE9MxVYnDpDwVGfWxR
                        |azBpdyE61TL291lqi6D4SSpOFJ0xetMqp0U14cHB2ev+OPU9Xzxg016aPgxpummC
                        |Keq1EMrDNTo0+dQLzmJdy//3Pnv+XIbh71XDlI+Nk2QATFubOdZSZ/pNTBTORUU1
                        |OnkVG/JhvBRdAFccoPA/H4GXWCUv4IYiJgjc4ve/TQWYZtZOoadG9z93Nljr1xio
                        |56XrLe6fFY6ePgqJPFzzRoIRRDyHhFUtbuDSlofhjLrFbuUK0bXwbTf6QgKX1PtR
                        |cNe8ffdVyDDtxQJ7ndpaJxZDzu2q5Sr2BKfRks+dnYqWEnkXfZkCAwEAAaNCMEAw
                        |HQYDVR0OBBYEFETT5Oh/EnA7aorvC4DDc7SVznVgMB8GA1UdIwQYMBaAFEi065E6
                        |0axl/v2dBjwzYwdyGzZvMA0GCSqGSIb3DQEBCwUAA4ICAQCfXbSyPET35yyfzSaK
                        |LLTtOV0UKqn098E6XshIOYyOnk3rlJeMkleWdBoQ3zu9himbhMBw2y11Y5fmzAsL
                        |8LCAVJfX3roNpJpDOer9bCL261QjYDopYNKgiq4sYZE5uqh9O0FNhUeef8pOSoaJ
                        |rmQbrBOfmL4OjRqouhwGwq5vA5r7EmxUjzS8kiQIJcw55j6YyZW8AZxQN5HZdP65
                        |qCFT8m1CFBtVoSIkSAIX5rshuJZdzrZnB68wSc4WKhDIpEK7Bw2mwkm3k481H2bo
                        |Lac+y7RMVnHHA4gp1zcV/tRsjk81/0gbbD+pIZC8QZFhzrJKD0sUIlUzOwekM0c2
                        |+3HdGuxrGVSowVqTnNGfNeirSFgSgaDmDgYxmLK+oNJkQStZflb0s+V+OEff5Kvo
                        |gPYiyCpV/1rOvZrFcgJbQ5ww1N9J7z+WSNgoMqmLkWCWa7fDG3brtj3CzDIDpTL4
                        |1ymUN/8jkSpLdHpRUwyFy2uTX5WVE5kl+2Bx0CHsKeTUCAG8HOAVbhwRlQkC4AhO
                        |y0G5hOt5jOXiHAV8Ax560E9R8krzA/G4qA2v7akwqOXJw/E/xqz7xb3ixkD/RjNb
                        |91TzEurOO1448Nmp1HEfj2NZzp26Ovr/26BUSFKvNdfIm6/e0E0tacvZMAMWWBhv
                        |goFL0H2+JVbGjPwAWldtAcJaiA==
                        |-----END CERTIFICATE-----
                        |""".stripMargin

  // resources/certificates/oto.bar/client-key.pem
  val clientKeyPem = """-----BEGIN PRIVATE KEY-----
                       |MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCxXOVuYdkEe+i4
                       |AY/CBPTMVWJw6Q8FRn1sUWswaXchOtUy9vdZaoug+EkqThSdMXrTKqdFNeHBwdnr
                       |/jj1PV88YNNemj4MabppginqtRDKwzU6NPnUC85iXcv/9z57/lyG4e9Vw5SPjZNk
                       |AExbmznWUmf6TUwUzkVFNTp5FRvyYbwUXQBXHKDwPx+Bl1glL+CGIiYI3OL3v00F
                       |mGbWTqGnRvc/dzZY69cYqOel6y3unxWOnj4KiTxc80aCEUQ8h4RVLW7g0paH4Yy6
                       |xW7lCtG18G03+kICl9T7UXDXvH33Vcgw7cUCe53aWicWQ87tquUq9gSn0ZLPnZ2K
                       |lhJ5F32ZAgMBAAECggEAHU7vhB0GgozYjkeGckDlaY/r03f/s8HL31wDmB1lbTKj
                       |9mcEcAcriZmzEBrqTHYtCLtcpr2E+m1GeI6AP6445ZOjz1cyTP96VA2z+tqZgBgK
                       |h1LSZxgzA42NCgAuQskWkHIXXZcTHoxTlcvNfQ8ahfBaHuzVAf8lR+QdhH1Y1TrL
                       |PIPTzbDDyX7x3B3L51hMjLNY3QBz0nFLx5Llsuic0wvrcTvHUA/E/1AAP8op3MMB
                       |YmbKaNkkKx3kMvGeUdmN+pxdnbfIWmtvTSytcM8X3ia93D49U/z6Mzd9BfM/l+tq
                       |qZSlHCSGqJdrgZx9IW+YovTjpt3/Z4P+hMs7O7bw7QKBgQDpJm3Dd/LjwvS9df/d
                       |v6E8e500hEB8uf0ZXQZoUJfLeuyIkC82TIu1bIHHw+47dEzlLvo1DB8gxOlX/g3K
                       |7S6/zecwjQOx4AhZkUIOsKoDaul9UMQ5jgNKVthqXBrUaZK0xtSZyJTkgz3ZpfMl
                       |WEhRLxwiB8gRoZANIrD9j7gOKwKBgQDCvs4gytoOoUTJtzGUKuGHV/hhwSh6oNNt
                       |4D9Knr+Iu5o0whmx1tz/fSavjmhKiri7AV+CxAF9dc72FX+udmShab3NkYcHlsdD
                       |vf8mTT/QUfpuk2jFn4pZS7+AqU+Za5aG0akOtwemTOeV8iu8pWMYrcSIbuiFqLyV
                       |sK89Y+2FSwKBgERQuUrUI7xvDI1TPQS5uMHjKp4PvmGNkcbTxiPcsY2y2AnvzZWo
                       |eitPvvtMwspy+nERAcl3YsuURxc8Xsg+FTuoVnyYZSPClE/CS0vIohhcREwu1UBU
                       |B0zKA6GumUHyKUWFGRhyeIY4mm/iu0sPAimflyu1gf8Aixf+OUQiz3h/AoGAPqzx
                       |4hPnAfZdER5oUmtKkih5w9xKjszbvu0A8Z8NvY/DHV+qrVWdYoyAutd2MSdcNLNI
                       |belvIVWc+g4JPbjUwi4p69L+Ri78iozHZShPdVgs5RGEErY57Gs4gsv61kGN/G3V
                       |nurBB4CQt+1srhX8QqEKM26eXwXjS7nFbFiQxOkCgYBog4Fj96sbYBJM9muYufDO
                       |VHIfs8Qty1TcSYnoV6DmlR08lN3XyjyEUus2aZKB3PXI5PjOVS1bpb+lPd14M3hV
                       |uZ+UUcN/BmsWXxKKJbhLa5JtDHYHwPDLuqAdxKJWO3VfKd9EJeW1AiGfgNUd0Dgj
                       |iZU+jmM4znlhjjsPr5i5tQ==
                       |-----END PRIVATE KEY-----
                       |""".stripMargin

  val caCertInputStream     = caCertPem.getBytes(CharsetUtil.UTF_8)
  val clientCertInputStream = clientCertPem.getBytes(CharsetUtil.UTF_8)
  val clientKeyInputStream  = clientKeyPem.getBytes(CharsetUtil.UTF_8)

  def callRoute(
      useClientCert: Boolean,
      status: Int
  ): Unit = {

    val pureNettyClient = HttpClient
      .create()
      .host("test-clientcertificate-chain-validator.oto.bar")
      .port(customHttpsPort)
      .protocol(reactor.netty.http.HttpProtocol.HTTP11)
      .secure { spec =>
        val certFactory = CertificateFactory.getInstance("X.509")
        val caCert      = certFactory
          .generateCertificate(new ByteArrayInputStream(caCertInputStream))
          .asInstanceOf[java.security.cert.X509Certificate]

        val sslCtxBuilder = SslContextBuilder.forClient().trustManager(caCert)

        if (useClientCert)
          sslCtxBuilder.keyManager(
            new ByteArrayInputStream(clientCertInputStream),
            new ByteArrayInputStream(clientKeyInputStream)
          )

        spec.sslContext(sslCtxBuilder)
      }
      .resolver(resolverGroup)

    val responseFuture: Future[Int] = {
      val promise = Promise[Int]()
      pureNettyClient
        .get()
        .uri("/foo")
        .response()
        .doOnNext(response => promise.success(response.status().code()))
        .doOnError(error => promise.failure(error))
        .subscribe()
      promise.future
    }

    val code = responseFuture.futureValue
    code mustBe status
  }

  callRoute(useClientCert = false, Status.FORBIDDEN)
  callRoute(useClientCert = true, Status.OK)

  publicInstance.stop()
}
