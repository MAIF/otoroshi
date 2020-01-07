package ssl

import java.io.ByteArrayInputStream
import java.security.KeyStore

import akka.util.ByteString
import security.IdGenerator
import ssl.SSLImplicits._

object P12Helper {

  def extractCertificate(file: ByteString, password: String = ""): Seq[Cert] = {
    var certs = Seq.empty[Cert]
    val kspkcs12 = KeyStore.getInstance("pkcs12")
    kspkcs12.load(new ByteArrayInputStream(file.toArray), password.toCharArray)
    val eAliases = kspkcs12.aliases()
    while (eAliases.hasMoreElements) {
      val strAlias = eAliases.nextElement()
      if (kspkcs12.isKeyEntry(strAlias)) {
        val key = kspkcs12.getKey(strAlias, password.toCharArray)
        val chain = kspkcs12.getCertificateChain(strAlias)
        val cert = Cert(
          id = IdGenerator.token,
          name = "Client Certificate",
          description = "Client Certificate",
          chain = chain.map(_.asPem).mkString("\n\n"),
          privateKey = key.asPrivateKeyPem,
          caRef = None,
          client = true
        ).enrich()
        certs = certs :+ cert
      }
    }
    certs
  }
}
