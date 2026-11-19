package plugins

import functional.PluginsTestSpecBase
import org.joda.time.DateTime
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, DynamicSSLEngineProvider}
import otoroshi.ssl.pki.models.GenCsrQuery

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

// the ocsp responder answers the validity read from the chain of a certificate, not the one its entity says: a
// certificate written without being enriched is not answered as expired
class OcspProjectionFromChainTests(parent: PluginsTestSpecBase) {
  import parent.*

  private given ec: ExecutionContext = env.otoroshiExecutionContext

  private val ca        = env.proxyState
    .certificate(Cert.OtoroshiIntermediateCA)
    .getOrElse(fail("no otoroshi intermediate ca"))
  private val generated = env.pki
    .genCert(
      GenCsrQuery(hosts = Seq("ocsp.example.test"), subject = Some("CN=ocsp.example.test"), duration = 30.days),
      ca.certificates.head,
      ca.certificates.tail,
      ca.cryptoKeyPair.getPrivate
    )
    .futureValue
    .fold(err => fail(err), _.toCert)
  // the entity says the certificate expired when it was written
  private val now       = DateTime.now()
  private val stale     = generated.copy(id = s"cert_${IdGenerator.uuid}", from = now, to = now)
  stale.expired mustBe true

  try {
    env.datastores.certificatesDataStore.set(stale).futureValue
    env.proxyState.sync().futureValue
    DynamicSSLEngineProvider.forceUpdate(env)
    val projection = DynamicSSLEngineProvider._ocspProjectionCertificates
      .get(stale.serialNumberLng.get)
      .getOrElse(fail("the certificate is not in the ocsp projection"))
    projection.expired mustBe false
    projection.from mustBe generated.from.toDate
    projection.to mustBe generated.to.toDate
  } finally {
    env.datastores.certificatesDataStore.delete(stale.id).futureValue
    env.proxyState.sync().futureValue
    DynamicSSLEngineProvider.forceUpdate(env)
  }
}
