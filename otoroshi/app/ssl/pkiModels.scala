package otoroshi.ssl.pki.models

import org.bouncycastle.pkcs.PKCS10CertificationRequest
import otoroshi.security.IdGenerator
import otoroshi.ssl.Cert
import play.api.libs.json.{Format, JsError, JsNull, JsResult, JsString, JsSuccess, JsValue, Json}

import java.security.{KeyPair, PrivateKey, PublicKey}
import java.security.cert.X509Certificate
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

import otoroshi.ssl.SSLImplicits._
import scala.concurrent.duration._
import otoroshi.utils.syntax.implicits._

case class GenKeyPairQuery(algo: String = "rsa", size: Int = 2048) {
  def json: JsValue = GenKeyPairQuery.format.writes(this)
}

object GenKeyPairQuery {
  private val format                                           = Json.format[GenKeyPairQuery]
  def fromJson(json: JsValue): Either[String, GenKeyPairQuery] =
    format.reads(json).asEither match {
      case Left(errs) => Left("error while parsing json")
      case Right(q)   => Right(q)
    }
}

case class GenCsrQuery(
    hosts: Seq[String] = Seq.empty,
    key: GenKeyPairQuery = GenKeyPairQuery(),
    name: Map[String, String] = Map.empty,
    subject: Option[String] = None,
    client: Boolean = false,
    ca: Boolean = false,
    includeAIA: Boolean = false,
    duration: FiniteDuration = 365.days,
    signatureAlg: String = "SHA256WithRSAEncryption",
    digestAlg: String = "SHA-256",
    existingKeyPair: Option[KeyPair] = None,
    existingSerialNumber: Option[Long] = None
)                  {
  def subj: String               = subject.getOrElse(name.map(t => s"${t._1}=${t._2}").mkString(","))
  def json: JsValue              = GenCsrQuery.format.writes(this)
  def hasDnsNameOrCName: Boolean =
    hosts.nonEmpty || subject.exists(_.toLowerCase().contains("cn=")) || name.contains("CN") || name.contains("cn")
}
object GenCsrQuery {
  private val format                                       = new Format[GenCsrQuery] {
    override def reads(json: JsValue): JsResult[GenCsrQuery] =
      Try {
        GenCsrQuery(
          hosts = (json \ "hosts").asOpt[Seq[String]].getOrElse(Seq.empty),
          key = (json \ "key")
            .asOpt[JsValue]
            .flatMap(v => GenKeyPairQuery.fromJson(v).toOption)
            .orElse(
              GenKeyPairQuery
                .fromJson(
                  Json.obj(
                    "algo" -> (json \ "keyType").as[JsValue],
                    "size" -> (json \ "keySize").as[JsValue]
                  )
                )
                .toOption
            )
            .map(q => q.copy(algo = q.algo.toLowerCase()))
            .getOrElse(GenKeyPairQuery()),
          name = (json \ "name").asOpt[Map[String, String]].getOrElse(Map.empty),
          subject = (json \ "subject").asOpt[String],
          client = (json \ "client").asOpt[Boolean].getOrElse(false),
          ca = (json \ "ca").asOpt[Boolean].getOrElse(false),
          includeAIA = (json \ "includeAIA").asOpt[Boolean].getOrElse(false),
          duration = (json \ "duration").asOpt[Long].map(_.millis).getOrElse(365.days),
          signatureAlg = (json \ "signatureAlg").asOpt[String].getOrElse("SHA256WithRSAEncryption"),
          digestAlg = (json \ "digestAlg").asOpt[String].getOrElse("SHA-256")
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(s) => JsSuccess(s)
      }

    override def writes(o: GenCsrQuery): JsValue =
      Json.obj(
        "hosts"        -> o.hosts,
        "key"          -> o.key.json,
        "name"         -> o.name,
        "subject"      -> o.subject.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "client"       -> o.client,
        "ca"           -> o.ca,
        "duration"     -> o.duration.toMillis,
        "signatureAlg" -> o.signatureAlg,
        "digestAlg"    -> o.digestAlg
      )
  }
  def fromJson(json: JsValue): Either[String, GenCsrQuery] =
    format.reads(json).asEither match {
      case Left(errs) => Left("error while parsing json")
      case Right(q)   => Right(q)
    }
}

case class GenKeyPairResponse(publicKey: PublicKey, privateKey: PrivateKey) {
  def json: JsValue    =
    Json.obj(
      "publicKey"  -> publicKey.asPem,
      "privateKey" -> privateKey.asPem
    )
  def chain: String    = s"${publicKey.asPem}\n${privateKey.asPem}"
  def keyPair: KeyPair = new KeyPair(publicKey, privateKey)
}

case class GenCsrResponse(csr: PKCS10CertificationRequest, publicKey: PublicKey, privateKey: PrivateKey) {
  def json: JsValue =
    Json.obj(
      "csr"        -> csr.asPem,
      "publicKey"  -> publicKey.asPem,
      "privateKey" -> privateKey.asPem
    )
  def chain: String = s"${csr.asPem}\n${privateKey.asPem}\n${publicKey.asPem}"
}

case class GenCertResponse(
    serial: java.math.BigInteger,
    cert: X509Certificate,
    csr: PKCS10CertificationRequest,
    csrQuery: Option[GenCsrQuery],
    key: PrivateKey,
    ca: X509Certificate,
    caChain: Seq[X509Certificate]
) {
  def json: JsValue        =
    Json.obj(
      "serial" -> serial,
      "cert"   -> cert.asPem,
      "csr"    -> csr.asPem,
      "key"    -> key.asPem,
      "ca"     -> ca.asPem,
      "query"  -> csrQuery.map(_.json).getOrElse(JsNull).as[JsValue]
    )
  def chain: String        = s"${key.asPem}\n${cert.asPem}\n${ca.asPem}"
  def chainWithCsr: String = s"${key.asPem}\n${cert.asPem}\n${ca.asPem}\n${csr.asPem}"
  def keyPair: KeyPair     = new KeyPair(cert.getPublicKey, key)
  def toCert: Cert = {
    Cert(
      id = IdGenerator.token(32),
      name = "none",
      description = "none",
      chain = if (cert == ca) {
        cert.asPem
      } else {
        cert.asPem + "\n" + ca.asPem + "\n" + caChain.map(_.asPem).mkString("\n")
      },
      privateKey = keyPair.getPrivate.asPem,
      caRef = None,
      entityMetadata = Map(
        "csr" -> csrQuery.map(_.json.stringify).getOrElse("--")
      ),
      exposed = false,
      revoked = false
    ).enrich()
  }
}

case class SignCertResponse(cert: X509Certificate, csr: PKCS10CertificationRequest, ca: Option[X509Certificate]) {
  def json: JsValue        =
    Json.obj(
      "cert" -> cert.asPem,
      "csr"  -> csr.asPem
    ) ++ ca.map(c => Json.obj("ca" -> c.asPem)).getOrElse(Json.obj())
  def chain: String        = s"${cert.asPem}\n${ca.map(_.asPem + "\n").getOrElse("")}"
  def chainWithCsr: String = s"${cert.asPem}\n${ca.map(_.asPem + "\n").getOrElse("")}${csr.asPem}"
}
