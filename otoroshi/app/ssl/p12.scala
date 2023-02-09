package otoroshi.ssl

import java.io.ByteArrayInputStream
import java.security.KeyStore

import akka.util.ByteString
import otoroshi.security.IdGenerator
import otoroshi.ssl.SSLImplicits._

object P12Helper {

  def extractCertificate(file: ByteString, password: String = "", client: Boolean = true): Seq[Cert] = {
    var certs    = Seq.empty[Cert]
    val kspkcs12 = KeyStore.getInstance("pkcs12")
    kspkcs12.load(new ByteArrayInputStream(file.toArray), password.toCharArray)
    val eAliases = kspkcs12.aliases()
    while (eAliases.hasMoreElements) {
      val strAlias = eAliases.nextElement()
      if (kspkcs12.isKeyEntry(strAlias)) {
        val key   = kspkcs12.getKey(strAlias, password.toCharArray)
        val chain = kspkcs12.getCertificateChain(strAlias)
        val cert  = Cert(
          id = IdGenerator.token,
          name = "Imported Certificate",
          description = "Imported Certificate",
          chain = chain.map(_.asPem).mkString("\n\n"),
          privateKey = key.asPrivateKeyPem,
          caRef = None,
          client = client,
          exposed = false,
          revoked = false
        ).enrich()
        certs = certs :+ cert
      }
    }
    certs
  }
}
