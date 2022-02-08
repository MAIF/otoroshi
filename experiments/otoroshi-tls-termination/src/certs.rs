use tokio_rustls::rustls::{self, Certificate, PrivateKey};
use base64;

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

pub struct OtoroshiCerts {
  pub certificates: Vec<OtoroshiCert>,
  pub trusted_certificates: Vec<OtoroshiCert>,
  pub d_domain: Option<String>,
  pub random: bool,
}
