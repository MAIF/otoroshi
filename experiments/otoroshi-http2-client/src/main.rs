#[macro_use]
extern crate log;

use reqwest;
use tokio;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use hyper::{Body, Method, Request, Response, Server, StatusCode};
use hyper::body::Buf;
use hyper::service::{make_service_fn, service_fn};
use clap::{Parser};

type GenericError = Box<dyn std::error::Error + Send + Sync>;
type HyperResult<T> = std::result::Result<T, GenericError>;
static NOTFOUND: &[u8] = b"Not Found";

#[derive(Parser, Clone, Debug)]
#[clap(name = "otoroshi_http2_client")]
#[clap(version = "0.1.0")]
#[clap(about = "Handle http2 backend calls (experimental)", long_about = None)]
pub struct Opts {
    /// the local exposed port
    #[clap(long, default_value = "8555")]
    pub port: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
struct TlsConfig {
    certs: Vec<String>,
    #[serde(alias = "trustedCerts")]
    trusted_certs: Vec<String>,
    enabled: bool,
    loose: bool,
    #[serde(alias = "trustAll")]
    trust_all: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
struct Target {
    port: u16,
    #[serde(alias = "ipAddress")]
    ip_address: Option<String>,
    #[serde(alias = "tlsConfig")]
    tls_config: TlsConfig
}

#[derive(Serialize, Deserialize, Clone, Debug)]
struct Http2Request {
    method: String,
    url: String, 
    headers: HashMap<String, String>, 
    body: Option<String>,
    target: Option<Target>,
}

impl Http2Request {
    fn opt_body_as_bytes(&self) -> Option<Vec<u8>> {
        match &self.body {
            None => None,
            Some(body) => {
                Some(base64::decode(body).unwrap())
            }
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
struct Http2Response {
    status: u16,
    version: String,
    headers: HashMap<String, String>, 
    body: String
}

impl Http2Response {
    fn json(&self) -> String {
        serde_json::json!({
            "status": self.status,
            "version": self.version,
            "body": self.body,
            "headers": self.headers,
        }).to_string()
    }
}

async fn call_backend_with_request(req: Http2Request) -> Result<Http2Response, reqwest::Error> {
    let target = req.target.clone().unwrap_or(Target {
        port: 443,
        ip_address: None,
        tls_config: TlsConfig {
            certs: Vec::new(),
            trusted_certs: Vec::new(),
            enabled: false,
            loose: false,
            trust_all: false
        }
    });
    let mut builder = reqwest::Client::builder()
        .no_gzip()
        .no_brotli()
        .no_deflate()
        .http2_prior_knowledge()
        .use_rustls_tls()
        .tls_built_in_root_certs(true)
        .danger_accept_invalid_hostnames(target.tls_config.enabled && target.tls_config.loose)
        .danger_accept_invalid_certs(target.tls_config.enabled && target.tls_config.trust_all);
    
    if target.tls_config.enabled && !target.tls_config.certs.is_empty() {
        let cert = target.tls_config.certs.get(0).unwrap();
        builder = builder.identity(reqwest::Identity::from_pem(cert.as_bytes()).unwrap());
    }
    if target.tls_config.enabled && !target.tls_config.trusted_certs.is_empty() {
        let cert = target.tls_config.trusted_certs.get(0).unwrap();
        builder = builder.add_root_certificate(reqwest::Certificate::from_pem(cert.as_bytes()).unwrap());
    }
    if target.ip_address.is_some() {
        let url = req.url.clone().replace("https://", "").replace("http://", "");
        let parts: Vec<&str> = url.split("/").collect();
        let domain = parts.get(0).unwrap();
        let addr: std::net::SocketAddr = format!("{}:{}", target.ip_address.unwrap(), target.port).parse().unwrap();
        builder = builder.resolve(domain, addr)
    }
    let client: reqwest::Client = builder.build()?;
    let url = req.url.clone();
    let headerz = req.headers.clone();
    let method = req.method.clone();
    let mut headers = reqwest::header::HeaderMap::new();
    for header in headerz {
        headers.insert(
            reqwest::header::HeaderName::from_bytes(header.0.as_bytes()).unwrap(), 
            reqwest::header::HeaderValue::from_bytes(header.1.as_bytes()).unwrap()
        );
    }
    
    let meth = reqwest::Method::from_bytes(method.as_bytes()).unwrap();
    let req = match req.opt_body_as_bytes() {
        None => {
            client
                .request(meth, url)
                .headers(headers)
                .build()?
        },
        Some(body) => {
            client
                .request(meth, url)
                .headers(headers)
                .body(reqwest::Body::from(body))
                .build()?
        }
    };
    let res = client.execute(req).await?;
    let headers = res.headers().clone();
    let status = res.status().clone();
    let version = res.version().clone();
    let body = res.bytes().await?;
    let mut headers_out: HashMap<String, String> = HashMap::new();
    let body_out = base64::encode(body.as_ref());
    for header in headers {
        headers_out.insert(
            header.0.unwrap().as_str().to_string(),
            header.1.to_str().unwrap().to_string()
        );
    }

    Ok(Http2Response {
        status: status.as_u16(),
        version: format!("{:?}", version),
        headers: headers_out,
        body: body_out,
    })
}

async fn handle_http2_request(req: Request<Body>) -> HyperResult<Response<Body>> {
    let whole_body = hyper::body::aggregate(req).await?;
    let data: serde_json::Value = serde_json::from_reader(whole_body.reader())?;
    let request = serde_json::from_value::<Http2Request>(data)?;
    let resp = call_backend_with_request(request).await?;
    let response = Response::builder()
        .status(StatusCode::OK)
        .header(hyper::header::CONTENT_TYPE, "application/json")
        .body(Body::from(resp.json()))?;
    Ok(response)
}

async fn handle_request(
    req: Request<Body>,
) -> HyperResult<Response<Body>> {
    match (req.method(), req.uri().path()) {
        (&Method::POST, "/http2_request") => handle_http2_request(req).await,
        _ => {
            Ok(Response::builder()
                .status(StatusCode::NOT_FOUND)
                .body(NOTFOUND.into())
                .unwrap())
        }
    }
}

#[tokio::main]
async fn main() -> HyperResult<()> {

    let def_log = if cfg!(debug_assertions) {
        "debug"
    } else {
        "info"
    };
    env_logger::Builder::from_env(
        env_logger::Env::new()
        .filter_or("OTOROSHI_HTTP2_CLIENT_LOG", def_log)
        .write_style_or("OTOROSHI_HTTP2_CLIENT_LOG_STYLE", "always")
    )
    .format_timestamp(None)
    .format_module_path(false)
    .format_target(false)
    .init();

    let opts = Opts::parse();
    debug!("{:?}", opts);

    // https://github.com/hyperium/hyper/blob/master/examples/web_api.rs
    let addr = format!("127.0.0.1:{}", opts.port).parse().unwrap();
    let new_service = make_service_fn(move |_| {
        async {
            Ok::<_, GenericError>(service_fn(move |req| {
                handle_request(req)
            }))
        }
    });

    let server = Server::bind(&addr).serve(new_service);
    info!("Listening on http://{}", addr);
    server.await?;
    Ok(())
}