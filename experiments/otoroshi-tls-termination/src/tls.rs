use tokio_rustls::rustls::{self};
use tokio_rustls::TlsAcceptor;
use std::sync::{Arc};
use serde_json::{Value};
use hyper::{Method, Request, Client};
use moka::future::{Cache, CacheBuilder};

// use rustls::RootCertStore;
// use rustls::server::AllowAnyAuthenticatedClient;
// use rustls::DistinguishedNames;
// use rustls::Certificate;
// use rustls::server::ClientCertVerified;
// use std::time::SystemTime;

use crate::certs::{OtoroshiCert, OtoroshiCerts};
use crate::opts::AppConfig;

async fn load_otoroshi_certs(oto_url_base: String, host: String, client_id: String, client_sec: String) -> Option<OtoroshiCerts> {
  info!("loading otoroshi certificates from - {} - {}", oto_url_base, host);
  let client = Client::new();
  let uri: String = format!("{}/api/experimental/certificates/_by_domain", oto_url_base);
  let req: Request<hyper::Body> = Request::builder()
      .method(Method::GET)
      .uri(uri)
      .header("host", host)
      .header("accept", "application/json")
      .header("Otoroshi-Client-Id", client_id)
      .header("Otoroshi-Client-Secret", client_sec)
      .body(hyper::Body::empty())
      .unwrap();
  let resp = client.request(req).await.unwrap();
  let status = resp.status();
  let body_bytes = hyper::body::to_bytes(resp).await.unwrap();
  let body_str = std::str::from_utf8(&body_bytes).unwrap();
  if status.as_str() == "200" {
      trace!("status: {} - {:?}k", status, body_bytes.len() / 1024);
      let payload: Value = serde_json::from_str(body_str).unwrap(); 
      let certificates_json: &Vec<Value> = payload.get("certificates").unwrap().as_array().unwrap();
      let tusted_certificates_json: &Vec<Value> = payload.get("trusted_certificates").unwrap().as_array().unwrap();
      let mut certificates: Vec<OtoroshiCert> = Vec::new();
      let mut trusted_certificates: Vec<OtoroshiCert> = Vec::new();
      for cert in certificates_json {
          let domains: Vec<String> = cert.get("domains").unwrap().as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
          let chain: Vec<String> = cert.get("chain").unwrap().as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
          let key: String = cert.get("key").unwrap().as_str().unwrap().to_string();
          let cert = OtoroshiCert {
              chain: chain,
              key: key,
              domains: domains,
          };
          certificates.push(cert);
      }
      for cert in tusted_certificates_json {
          let domains: Vec<String> = cert.get("domains").unwrap().as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
          let chain: Vec<String> = cert.get("chain").unwrap().as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
          let key: String = cert.get("key").unwrap().as_str().unwrap().to_string();
          let cert = OtoroshiCert {
              chain: chain,
              key: key,
              domains: domains,
          };
          trusted_certificates.push(cert);
      }
      Some(OtoroshiCerts {
          certificates: certificates,
          trusted_certificates: trusted_certificates
      })
  } else {
      None
  }
}

pub struct CustomResolvesServerCertUsingSni {
    by_name: std::collections::HashMap<String, Arc<rustls::sign::CertifiedKey>>,
}

impl CustomResolvesServerCertUsingSni {
    pub fn new() -> Self {
        Self {
            by_name: std::collections::HashMap::new(),
        }
    }

    pub fn add(&mut self, name: &str, ck: rustls::sign::CertifiedKey) {
        self.by_name.insert(name.into(), Arc::new(ck));
    }
}

impl rustls::server::ResolvesServerCert for CustomResolvesServerCertUsingSni {
    fn resolve(&self, client_hello: rustls::server::ClientHello) -> Option<Arc<rustls::sign::CertifiedKey>> {
        // TODO: handle certs with wildcards
        // TODO: handle default certificate domain from otoroshi
        if let Some(name) = client_hello.server_name() {
            self.by_name.get(name).map(Arc::clone)
        } else {
            None
        }
    }
}

// pub struct CustomClientAuth {
//     inner: rustls::server::AllowAnyAuthenticatedClient
// }
// 
// impl CustomClientAuth {
//     pub fn new(roots: RootCertStore) -> Arc<dyn rustls::server::ClientCertVerifier> {
//         Arc::new(Self {
//             inner: AllowAnyAuthenticatedClient { roots },
//         })
//     }
// }
// 
// impl rustls::server::ClientCertVerifier for CustomClientAuth {
//     fn offer_client_auth(&self) -> bool {
//         self.inner.offer_client_auth()
//     }
// 
//     fn client_auth_mandatory(&self) -> Option<bool> {
//         Some(false)
//     }
// 
//     fn client_auth_root_subjects(&self) -> Option<DistinguishedNames> {
//         self.inner.client_auth_root_subjects()
//     }
// 
//     fn verify_client_cert(
//         &self,
//         end_entity: &Certificate,
//         intermediates: &[Certificate],
//         now: SystemTime,
//     ) -> Result<ClientCertVerified, tokio_rustls::rustls::TLSError> {
//         self.inner
//             .verify_client_cert(end_entity, intermediates, now)
//     }
// }

pub struct Tls {
    app_config: AppConfig,
    cache: Cache<String, Arc<TlsAcceptor>>,
}

impl Tls {
    pub fn new(app_config: AppConfig) -> Tls {
        Tls {
            app_config: app_config,
            cache: CacheBuilder::new(2)
                .time_to_live(std::time::Duration::from_secs(60 * 60 * 24))
                .build(),
        }
    }

    pub async fn create(app_config: AppConfig) -> Tls {
        let tls = Tls::new(app_config);
        tls.update().await;
        tls
    }

    pub async fn update(&self) {

        let AppConfig { host, cid, csec, mtls, listen_addr: _, server_addr: _, oto_url_base, auto_refresh:_, workers: _, refresh_every_sec: _ } = self.app_config.clone();

        let mut resolver = CustomResolvesServerCertUsingSni::new(); // rustls::server::ResolvesServerCertUsingSni::new();
        let mut root = rustls::RootCertStore::empty(); 
     
        match load_otoroshi_certs(oto_url_base, host, cid, csec).await {
            None => (),
            Some(certs) => {
                for cert in certs.certificates {
                    match cert.cert_key() {
                        None => (),
                        Some(skey) => {
                            for domain in cert.domains {
                                resolver.add(&domain[..], skey.clone()); //.unwrap();
                            }
                        }
                    }
                }
                for cert in certs.trusted_certificates {
                    match cert.cert() {
                        None => (),
                        Some(c) => {
                            root.add(&c).unwrap();
                        }
                    }
                }
            }
        }
        let client_auth = rustls::server::AllowAnyAnonymousOrAuthenticatedClient::new(root);
        let cert_resolver = Arc::new(resolver);
    
        let config: Arc<rustls::ServerConfig> = if mtls {
            Arc::new(rustls::ServerConfig::builder()
                .with_safe_defaults()
                .with_client_cert_verifier(client_auth)
                .with_cert_resolver(cert_resolver))
        } else {
            Arc::new(rustls::ServerConfig::builder()
                .with_safe_defaults()
                .with_no_client_auth()
                .with_cert_resolver(cert_resolver))
        };
        let acceptor = TlsAcceptor::from(config);
        self.cache.insert("singleton".to_string(), Arc::new(acceptor)).await;
    }

    pub fn acceptor(&self) -> Option<Arc<TlsAcceptor>> {
        self.cache.get(&"singleton".to_string())
    }

    pub fn current_acceptor(&self) -> TlsAcceptor {
        let arc: Arc<TlsAcceptor> = self.acceptor().unwrap();
        let acceptor = arc.as_ref().clone();
        acceptor
    }
}
