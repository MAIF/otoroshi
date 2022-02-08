use tokio_rustls::rustls::{self};
use tokio_rustls::TlsAcceptor;
use std::sync::{Arc};
use serde_json::{Value};
use hyper::{Method, Request, Client};
use moka::future::{Cache, CacheBuilder};

use crate::certs::{OtoroshiCert, OtoroshiCerts};
use crate::opts::AppConfig;

async fn load_otoroshi_certs(oto_url_base: String, host: String, client_id: String, client_sec: String) -> Option<OtoroshiCerts> {
  trace!("loading otoroshi certificates from - {} - {}", oto_url_base, host);
  let client = Client::new();
  let uri: String = format!("{}/api/experimental/certificates/_by_domain", oto_url_base);
  let req: Request<hyper::Body> = Request::builder()
      .method(Method::GET)
      .uri(uri)
      .header("host", host.clone())
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
      let tls_settings = payload.get("tls_settings").unwrap().as_object().unwrap();
      let d_domain = tls_settings.get("defaultDomain").unwrap().as_str().map(|s| s.to_string());
      let random = tls_settings.get("randomIfNotFound").unwrap().as_bool().unwrap_or(false);
      info!("loading otoroshi certificates from - {} - {} - {} certificates - {} trusted certificates", oto_url_base, host, certificates_json.len(), tusted_certificates_json.len());
      let mut certificates: Vec<OtoroshiCert> = Vec::new();
      let mut trusted_certificates: Vec<OtoroshiCert> = Vec::new();
      for cert in certificates_json {
          let domains: Vec<String> = cert.get("domains").unwrap().as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
          let sans: Vec<String> = cert.get("sans").unwrap().as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
          let chain: Vec<String> = cert.get("chain").unwrap().as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
          let key: String = cert.get("key").unwrap().as_str().unwrap().to_string();
          let cert = OtoroshiCert {
              chain: chain,
              key: key,
              domains: domains,
              sans: sans,
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
              sans: Vec::new()
          };
          trusted_certificates.push(cert);
      }
      Some(OtoroshiCerts {
          certificates,
          trusted_certificates,
          d_domain,
          random
      })
  } else {
      error!("bad status - {} - {:?}", status, body_bytes);
      None
  }
}

pub struct CustomResolvesServerCertUsingSni {
    by_name: std::collections::HashMap<String, Arc<rustls::sign::CertifiedKey>>,
    wildcards: std::collections::HashMap<String, Arc<rustls::sign::CertifiedKey>>,
    default_domain: Option<String>,
    random_if_not_found: bool,
}

impl CustomResolvesServerCertUsingSni {
    pub fn new(default_domain: Option<String>, random_if_not_found: bool) -> Self {
        Self {
            by_name: std::collections::HashMap::new(),
            wildcards: std::collections::HashMap::new(),
            default_domain,
            random_if_not_found,
        }
    }

    pub fn add(&mut self, name: &str, ck: rustls::sign::CertifiedKey) {
        if name.starts_with("*.") {
            let domain = &name[name.len() - (name.len() - 2)..];
            println!("domain: {}", domain);
            self.wildcards.insert(domain.into(), Arc::new(ck));
        } else {
            self.by_name.insert(name.into(), Arc::new(ck));
        }
    }
}

impl rustls::server::ResolvesServerCert for CustomResolvesServerCertUsingSni {
    fn resolve(&self, client_hello: rustls::server::ClientHello) -> Option<Arc<rustls::sign::CertifiedKey>> {
        if let Some(name) = client_hello.server_name() {
            match self.by_name.get(name).map(Arc::clone) {
                Some(arc) => Some(arc),
                None => {
                    let parts: Vec<&str> = name.split(".").collect();
                    let domain = &parts[1..].join(".");
                    match self.wildcards.get(domain).map(Arc::clone) {
                        Some(arc) => Some(arc),
                        None => {
                            match &self.default_domain {
                                Some(d_domain) => self.by_name.get(d_domain).map(Arc::clone),
                                None => {
                                    if self.random_if_not_found {
                                        self.by_name.values().nth(0).map(Arc::clone)
                                    } else {
                                        None
                                    }
                                }
                            }
                            
                        },
                    }
                },
            }
        } else {
            None
        }
    }
}

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

        let AppConfig { host, cid, csec, mtls, listen_addr: _, server_addr: _, oto_url_base, auto_refresh:_, workers: _, refresh_every_sec: _, whole_chain: _, client_auth_mandatory } = self.app_config.clone();

        // let mut resolver = CustomResolvesServerCertUsingSni::new(); // rustls::server::ResolvesServerCertUsingSni::new();
        let mut root = rustls::RootCertStore::empty(); 
     
        let resolver: CustomResolvesServerCertUsingSni = match load_otoroshi_certs(oto_url_base, host, cid, csec).await {
            None => CustomResolvesServerCertUsingSni::new(None, false),
            Some(certs) => {
                let mut resolver = CustomResolvesServerCertUsingSni::new(certs.d_domain, certs.random);
                for cert in certs.certificates {
                    match cert.cert_key() {
                        None => (),
                        Some(skey) => {
                            for domain in cert.domains {
                                resolver.add(&domain[..], skey.clone()); //.unwrap();
                            }
                            for domain in cert.sans {
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
                resolver
            }
        };
        let client_auth = if client_auth_mandatory {
            rustls::server::AllowAnyAuthenticatedClient::new(root)
        } else {
            rustls::server::AllowAnyAnonymousOrAuthenticatedClient::new(root)
        };
        let cert_resolver = Arc::new(resolver);
    
        let config: Arc<rustls::ServerConfig> = if mtls || client_auth_mandatory {
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


// use rustls::RootCertStore;
// use rustls::server::AllowAnyAuthenticatedClient;
// use rustls::DistinguishedNames;
// use rustls::Certificate;
// use rustls::server::ClientCertVerified;
// use std::time::SystemTime;
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