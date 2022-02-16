use reqwest;
use tokio;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use hyper::{Body, Method, Request, Response, Server, StatusCode};
use hyper::body::Buf;
use hyper::service::{make_service_fn, service_fn};

type GenericError = Box<dyn std::error::Error + Send + Sync>;
type HyperResult<T> = std::result::Result<T, GenericError>;
static NOTFOUND: &[u8] = b"Not Found";

#[derive(Serialize, Deserialize, Clone, Debug)]
struct Http2Request {
    method: String,
    url: String, 
    headers: HashMap<String, String>, 
    body: Option<String>
}

impl Http2Request {
    fn body_as_bytes(&self) -> Vec<u8> {
        match &self.body {
            None => Vec::new(),
            Some(body) => {
                base64::decode(body).unwrap()
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

async fn call_backend(method: String, url: String, headerz: HashMap<String, String>, body: &'static [u8]) -> Result<(), reqwest::Error> {
    let client: reqwest::Client = reqwest::Client::builder()
        .no_gzip()
        .no_brotli()
        .no_deflate()
        //.proxy()
        //.timeout()
        //.connection_timeout()
        //.resolve("foo.bar.foo", "127.0.0.1")
        .http2_prior_knowledge()
        .use_rustls_tls()
        .tls_built_in_root_certs(true)
        //.add_root_certificate(cert)
        //.identity(reqwest::Identity::from_pem(&buf)) // client cert
        .danger_accept_invalid_hostnames(true)
        .danger_accept_invalid_certs(true)
        .build()?;


    let mut headers = reqwest::header::HeaderMap::new();
    for header in headerz {
        headers.insert(
            reqwest::header::HeaderName::from_bytes(header.0.as_bytes()).unwrap(), 
            reqwest::header::HeaderValue::from_bytes(header.1.as_bytes()).unwrap()
        );
    }
    
    let meth = reqwest::Method::from_bytes(method.as_bytes()).unwrap();

    eprintln!("Fetching {:?} {:?} {:?} ...", meth, url, headers);

    let req = client
        .request(meth, url)
        .headers(headers)
        .body(reqwest::Body::from(body))
        .build()?;
    let res = client.execute(req).await?;

    eprintln!("Response: {:?} {}", res.version(), res.status());
    eprintln!("Headers: {:#?}\n", res.headers());

    let body = res.text().await?;

    println!("{}", body);

    Ok(())
}

async fn call_backend_with_request(req: Http2Request) -> Result<Http2Response, reqwest::Error> {
    let client: reqwest::Client = reqwest::Client::builder()
        .no_gzip()
        .no_brotli()
        .no_deflate()
        //.proxy()
        //.timeout()
        //.connection_timeout()
        //.resolve("foo.bar.foo", "127.0.0.1")
        .http2_prior_knowledge()
        .use_rustls_tls()
        .tls_built_in_root_certs(true)
        //.add_root_certificate(cert)
        //.identity(reqwest::Identity::from_pem(&buf)) // client cert
        .danger_accept_invalid_hostnames(true)
        .danger_accept_invalid_certs(true)
        .build()?;

    let url = req.url.clone();
    let headerz = req.headers.clone();
    let method = req.method.clone();
    let body = req.body_as_bytes();
    let mut headers = reqwest::header::HeaderMap::new();
    for header in headerz {
        headers.insert(
            reqwest::header::HeaderName::from_bytes(header.0.as_bytes()).unwrap(), 
            reqwest::header::HeaderValue::from_bytes(header.1.as_bytes()).unwrap()
        );
    }
    
    let meth = reqwest::Method::from_bytes(method.as_bytes()).unwrap();
    let req = client
        .request(meth, url)
        .headers(headers)
        .body(reqwest::Body::from(body))
        .build()?;
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
    // https://github.com/hyperium/hyper/blob/master/examples/web_api.rs
    let addr = "127.0.0.1:1337".parse().unwrap();
    let new_service = make_service_fn(move |_| {
        async {
            Ok::<_, GenericError>(service_fn(move |req| {
                handle_request(req)
            }))
        }
    });

    let server = Server::bind(&addr).serve(new_service);
    println!("Listening on http://{}", addr);
    server.await?;
    Ok(())
}

// #[tokio::main]
// async fn main() -> Result<(), reqwest::Error> {
//     let mut headers = HashMap::new();
//     headers.insert("Host".to_string(), "foo.oto.tools".to_string());
//     call_backend("GET".to_string(), "https://localhost:8444/hello".to_string(), headers, "".as_bytes()).await
// }