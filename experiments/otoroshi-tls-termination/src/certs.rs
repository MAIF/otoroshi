use tokio_rustls::rustls::{self, Certificate, PrivateKey};
use serde::{Deserialize, Serialize};
use base64;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct OtoroshiCert {
  pub chain: Vec<String>,
  pub key: String,
  pub domains: Vec<String>,
  pub sans: Vec<String>,
}

impl OtoroshiCert {
  pub fn cert(&self) -> Option<Certificate> {
      Some(Certificate(base64::decode(self.chain.get(0).unwrap()).unwrap()))
  }
  pub fn cert_key(&self) -> Option<rustls::sign::CertifiedKey> {
      let chain: Vec<Certificate> = self.chain.iter().map(|c| {
          Certificate(base64::decode(c).unwrap())
      }).collect();
      let pkey = PrivateKey(base64::decode(self.key.clone()).unwrap());
      let skey = rustls::sign::any_supported_type(&pkey).unwrap();
      let key = rustls::sign::CertifiedKey::new(chain, skey);
      Some(key)
  }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct TlsSettings {
  #[serde(alias = "defaultDomain")]
  pub default_domain: Option<String>,
  #[serde(alias = "randomIfNotFound")]
  pub random_if_not_found: bool,
  #[serde(alias = "trustedCAsServer")]
  pub trusted_ca_servers: Vec<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct OtoroshiCerts {
  pub certificates: Vec<OtoroshiCert>,
  pub trusted_certificates: Vec<OtoroshiCert>,
  pub tls_settings: TlsSettings,
}
