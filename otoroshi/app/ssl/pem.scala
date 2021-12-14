package otoroshi.ssl

import org.joda.time.DateTime

import java.io.File
import java.nio.file.Files
import java.util.UUID
import scala.util.Try

object PemCertificate {
  def fromBundle(bundle: String): Try[PemCertificate] = Try {

    var started = false
    var chain = List.empty[String]
    var pkey = ""
    var csr = ""
    var current = ""

    bundle.split("\\n").foreach { raw =>
      raw.trim match {
        case line if !started && line.startsWith(PemHeaders.BeginCertificate) => {
          started = true
          current = line
        }
        case line if started && line.startsWith(PemHeaders.EndCertificate) => {
          started = false
          chain = chain :+ (current + "\n" + line)
        }
        case line if !started && line.startsWith(PemHeaders.BeginCertificateRequest) => {
          started = true
          current = line
        }
        case line if started && line.startsWith(PemHeaders.EndCertificateRequest) => {
          started = false
          csr = (current + "\n" + line)
        }
        case line if !started && line.startsWith(PemHeaders.BeginPrivateKey) => {
          started = true
          current = line
        }
        case line if started && line.startsWith(PemHeaders.EndPrivateKey) => {
          started = false
          pkey = (current + "\n" + line)
        }
        case line if !started && line.startsWith(PemHeaders.BeginPrivateRSAKey) => {
          started = true
          current = line
        }
        case line if started && line.startsWith(PemHeaders.EndPrivateRSAKey) => {
          started = false
          pkey = (current + "\n" + line)
        }
        case line if !started && line.startsWith(PemHeaders.BeginPrivateECKey) => {
          started = true
          current = line
        }
        case line if started && line.startsWith(PemHeaders.EndPrivateECKey) => {
          started = false
          pkey = (current + "\n" + line)
        }
        case line if !started && line.isEmpty => ()
        case line if started && line.isEmpty => ()
        case line if started => current = current + "\n" + line
        case _ => ()
      }
    }
    PemCertificate(chain, pkey, Option(csr).filter(_.trim.nonEmpty))
  }

  def fromChainAndKey(chain: String, pkey: String): Try[PemCertificate] = fromBundle(pkey + "\n\n" + chain)

  def fromChainAndKeyFiles(chainFile: File, pkeyFile: File): Try[PemCertificate] = fromChainAndKey(Files.readString(chainFile.toPath), Files.readString(pkeyFile.toPath))
  def fromBundleFile(file: File): Try[PemCertificate] = fromBundle(Files.readString(file.toPath))

  def fromChainAndKeyFilesPath(chainPath: String, pkeyPath: String): Try[PemCertificate] = fromChainAndKeyFiles(new File(chainPath), new File(pkeyPath))
  def fromBundleFilePath(path: String): Try[PemCertificate] = fromBundleFile(new File(path))

  def from(path: String): Try[PemCertificate] = fromBundleFile(new File(path))
  def from(file: File): Try[PemCertificate] = fromBundleFile(file)
}

case class PemCertificate(pemChain: List[String], pemPrivateKey: String, pemCsr: Option[String], password: Option[String] = None, ca: Boolean = false, client: Boolean = false) {
  def toCert(): Try[Cert] = Try {
    Cert(
      id = "cert_" + UUID.randomUUID().toString,
      name = s"Bundle import from ${DateTime.now().toString()}",
      description = s"Bundle import from ${DateTime.now().toString()}",
      chain = pemChain.mkString("\n\n"),
      privateKey = pemPrivateKey,
      caRef = None,
      revoked = false,
      entityMetadata = pemCsr.map(csr => Map("raw_csr" -> csr)).getOrElse(Map.empty),
    ).enrich()
  }
}