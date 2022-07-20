#![forbid(unsafe_code)]

#[macro_use]
extern crate log;

use std::collections::HashMap;
use hyper::http::request::Builder;
use futures_util::{SinkExt, StreamExt};
use tokio_tungstenite::{
    connect_async,
    tungstenite::{Result, Message},
};
use url::Url;
use base64::{encode, decode};
use hyper::{Method, Request, Client};
use clap::{Parser};
use std::time::Instant;
use serde::{Deserialize, Serialize};

#[derive(Parser, Clone, Debug)]
#[clap(name = "otoroshi_remote_tunnel_cli")]
#[clap(version = "0.1.0")]
#[clap(about = "Route local process to a remote Otoroshi through a tunnel (experimental)", long_about = None)]
pub struct Opts {
    /// the otoroshi api hostname
    #[clap(long, default_value = "otoroshi-api.oto.tools:9999")]
    pub host: String,
    /// the otoroshi client-id
    #[clap(long, default_value = "admin-api-apikey-id")]
    pub cid: String,
    /// the otoroshi client-secret
    #[clap(long, default_value = "admin-api-apikey-secret")]
    pub csec: String,
    /// the otoroshi client-secret
    #[clap(long, default_value = "cli")]
    pub tunnel: String,
    /// enable tls want mode
    #[clap(long, action, default_value = "false")]
    pub tls: bool,

    /// the local host forwarded to
    #[clap(long, default_value = "localhost")]
    pub local_host: String,
    /// the local port forwarded to
    #[clap(long, default_value = "8080")]
    pub local_port: i32,
    /// local process exposed as tls ?
    #[clap(long, action, default_value = "false")]
    pub local_tls: bool,

    /// enable expose mode
    #[clap(long, action, default_value = "false")]
    pub expose: bool,
    /// the exposed domain
    #[clap(long)]
    pub remote_domain: Option<String>,
    /// the exposed subdomain
    #[clap(long)]
    pub remote_subdomain: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct OtoroshInfos {
    domain: String,
    scheme: String,
    exposed_port_http: i16,
    exposed_port_https: i16,
}

async fn get_infos(opts: Opts) -> Option<OtoroshInfos> {
    let client_id = opts.cid;
    let client_secret = opts.csec;
    let scheme = if opts.tls {
        "https"
    } else {
        "http"
    };
    let host = opts.host;
    let client = Client::new();
    let uri: String = format!("{}://{}/api/tunnels/infos", scheme, host);
    let req: Request<hyper::Body> = Request::builder()
        .method(Method::GET)
        .uri(uri)
        .header("host", host.clone())
        .header("accept", "application/json")
        .header("Authorization", format!("Basic {}", encode(format!("{}:{}", client_id, client_secret))))
        .header("Otoroshi-Client-Id", client_id)
        .header("Otoroshi-Client-Secret", client_secret)
        .body(hyper::Body::empty())
        .unwrap();
    let resp = client.request(req).await.unwrap();
    if resp.status().as_u16() == 200 {
        let body_bytes = hyper::body::to_bytes(resp).await.unwrap();
        match serde_json::from_slice::<OtoroshInfos>(&body_bytes) {
            Ok(infos) => Some(infos),
            Err(e) => {
                debug!("parse error: {}", e);
                None
            },
        }
    } else {
        None
    }
}

async fn maybe_expose_local_process(opts: Opts, infos: OtoroshInfos) -> String {
    let cloned = opts.clone();
    let tunnel_id = opts.tunnel;
    let client_id = opts.cid;
    let client_secret = opts.csec;
    let scheme = if opts.tls {
        "https"
    } else {
        "http"
    };
    let host = opts.host;
    let client = Client::new();
    let uri: String = format!("{}://{}/api/experimental/routes/route_{}", scheme, host, tunnel_id);
    let req: Request<hyper::Body> = Request::builder()
        .method(Method::GET)
        .uri(uri)
        .header("host", host.clone())
        .header("accept", "application/json")
        .header("Authorization", format!("Basic {}", encode(format!("{}:{}", client_id, client_secret))))
        .header("Otoroshi-Client-Id", client_id)
        .header("Otoroshi-Client-Secret", client_secret)
        .body(hyper::Body::empty())
        .unwrap();
    let resp = client.request(req).await.unwrap();
    if resp.status().as_u16() == 200 {
        debug!("route already exists ...");
        let body_bytes = hyper::body::to_bytes(resp).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body_bytes).unwrap();
        let domain = json.get("frontend").unwrap().as_object().unwrap().get("domains").unwrap().as_array().unwrap().get(0).unwrap().as_str().unwrap();
        String::from(domain)
    } else {
        debug!("creating route ...");
        expose_local_process(cloned, infos.clone()).await
    }
}

// TODO: exposed port
// TODO: default domain
async fn expose_local_process(opts: Opts, infos: OtoroshInfos) -> String {
    let tunnel_id = opts.tunnel;
    let client_id = opts.cid;
    let client_secret = opts.csec;
    let local_host = opts.local_host;
    let local_port = opts.local_port;
    let local_tls = opts.local_tls;
    let local_port_str = format!("{}", local_port);
    let local_tls_str = format!("{}", local_tls);
    let id = uuid::Uuid::new_v4().to_string();
    let domain = format!("{}.{}", opts.remote_subdomain.unwrap_or(id + "-tunnel"), opts.remote_domain.unwrap_or(infos.domain));
    let scheme = if opts.tls {
        "https"
    } else {
        "http"
    };
    let host = opts.host;
    let client = Client::new();
    let uri: String = format!("{}://{}/api/experimental/routes", scheme, host);
    let json = r###"{
        "id": "route_$tunnel_id",
        "name": "exposed-cli-tunnel-$tunnel_id",
        "description": "exposed-cli-tunnel-$tunnel_id",
        "tags": [],
        "metadata": {},
        "enabled": true,
        "debug_flow": false,
        "export_reporting": false,
        "capture": false,
        "groups": [
          "default"
        ],
        "frontend": {
          "domains": [
            "$domain"
          ],
          "strip_path": true,
          "exact": false,
          "headers": {},
          "query": {},
          "methods": []
        },
        "backend": {
          "targets": [
            {
              "id": "target_1",
              "hostname": "$local_host",
              "port": $local_port,
              "tls": $local_tls,
              "weight": 1,
              "predicate": {
                "type": "AlwaysMatch"
              },
              "protocol": "HTTP/1.1",
              "ip_address": null
            }
          ],
          "target_refs": [],
          "root": "/",
          "rewrite": false,
          "load_balancing": {
            "type": "RoundRobin"
          },
          "health_check": null
        },
        "backend_ref": null,
        "plugins": [
          {
            "enabled": true,
            "debug": false,
            "plugin": "cp:otoroshi.next.tunnel.TunnelPlugin",
            "include": [],
            "exclude": [],
            "config": {
              "tunnel_id": "$tunnel_id"
            },
            "plugin_index": { }
          }
        ]
      }"###
      .replace("$tunnel_id", tunnel_id.as_str())
      .replace("$domain", domain.as_str())
      .replace("$local_host", local_host.as_str())
      .replace("$local_port", local_port_str.as_str())
      .replace("$local_tls", local_tls_str.as_str());
    //println!("{}", json);
    let req: Request<hyper::Body> = Request::builder()
        .method(Method::POST)
        .uri(uri)
        .header("host", host.clone())
        .header("content-type", "application/json")
        .header("accept", "application/json")
        .header("Authorization", format!("Basic {}", encode(format!("{}:{}", client_id, client_secret))))
        .header("Otoroshi-Client-Id", client_id)
        .header("Otoroshi-Client-Secret", client_secret)
        .body(hyper::Body::from(json))
        .unwrap();
    let resp = client.request(req).await.unwrap();
    let status = resp.status();
    // let body_bytes = hyper::body::to_bytes(resp).await.unwrap();
    // let body_str = std::str::from_utf8(&body_bytes).unwrap();
    debug!("route created ! - {}", status.as_u16());
    domain
}

async fn connect_tunnel(opts: Opts) -> Result<()> {

    let tunnel_id = opts.tunnel;
    let client_id = opts.cid;
    let client_secret = opts.csec;
    let scheme = if opts.tls {
        "wss"
    } else {
        "ws"
    };
    let host = opts.host;

    let client = Client::new();
    let credentials = encode(format!("{}:{}", client_id, client_secret));
    let url_raw = format!("{}://{}/api/tunnels/register?tunnel_id={}&basic_auth={}&pong_ping=true", scheme, host, tunnel_id, credentials);
    // debug!("url_raw {} {}", opts.tls, url_raw);
    let url =  Url::parse(url_raw.as_str()).expect("Bad URL");
    // TODO: connect tls
    let (ws_stream, _) = Box::leak(Box::new(connect_async(url).await?));

    info!("connection done !");
    info!("");
    
    while let Some(msg) = ws_stream.next().await {
        let msg = msg?;
        if msg.is_binary() {
            let json: serde_json::Value = serde_json::from_slice(&msg.into_data()).unwrap();
            let msg_type = json.get("type").unwrap().as_str().unwrap();
            // info!("{}", msg_type);
            if msg_type == "request" {
                // TODO: cookies
                let start = Instant::now();
                let request_id = json.get("request_id").unwrap().as_i64().unwrap();
                let addr = json.get("addr").unwrap().as_str().unwrap();
                let version = json.get("version").unwrap().as_str().unwrap();
                let path = json.get("path").unwrap().as_str().unwrap();
                let method = json.get("method").unwrap().as_str().unwrap();
                let uri = json.get("url").unwrap().as_str().unwrap();
                let headers = json.get("headers").unwrap().as_object().unwrap();
                // debug!("headers: {:?}", headers);
                let mut builder: Builder = Request::builder()
                    .method(method)
                    .uri(uri);
                for header in headers.iter() {
                    builder = builder.header(header.0, header.1.as_str().unwrap());
                }
                let maybe_body = json.get("body");
                let req: Request<hyper::Body> = if maybe_body.is_some() {
                    let body_bytes = decode(maybe_body.unwrap().as_str().unwrap()).unwrap();
                    builder.body(hyper::Body::from(body_bytes)).unwrap()
                } else {
                    builder.body(hyper::Body::empty()).unwrap()
                };
                let resp = client.request(req).await.unwrap();
                let status = resp.status();
                let resp_headers = resp.headers().clone();
                let mut resp_headers_map = HashMap::new();
                for (key, value) in resp_headers.iter() {
                    resp_headers_map.insert(key.as_str(), value.to_str().unwrap());
                }
                let body_bytes = hyper::body::to_bytes(resp).await.unwrap();
                let body_str = encode(body_bytes);
                let resp_json = serde_json::json!({
                    "status": status.as_u16(),
                    "headers": resp_headers_map,
                    "body": body_str,
                    "request_id": request_id,
                    "type": "response",
                });
                let resp_json_bin = serde_json::to_vec(&resp_json).unwrap();
                let elasped = start.elapsed();
                info!("{} - - [{:?}] \"{} {} {}\" {} {}", addr, chrono::offset::Utc::now(), method, path, version, status.as_u16(), elasped.as_millis());
                match ws_stream.send(Message::Binary(resp_json_bin)).await {
                    Ok(_) => (),
                    Err(e) => debug!("error while sending response: {}", e)
                };
            } else if msg_type == "pong" {
                let json = serde_json::json!({
                    "tunnel_id": tunnel_id.clone(), 
                    "type": "ping"
                });
                let bytes = serde_json::to_vec(&json).unwrap();
                match ws_stream.send(Message::Binary(bytes)).await {
                    Ok(_) => (),
                    Err(e) => debug!("error while sending pong: {}", e)
                };
            }
        }
    }
    Ok(())
}

#[tokio::main]
async fn main() {

    let def_log = if cfg!(debug_assertions) {
        "debug"
    } else {
        "info"
    };
    env_logger::Builder::from_env(
        env_logger::Env::new()
        .filter_or("OTOROSHI_REMOTE_CLI_TUNNEL", def_log)
        .write_style_or("OTOROSHI_REMOTE_TUNNEL_CLI_LOG_STYLE", "always")
    )
    .format_timestamp(None)
    .format_module_path(false)
    .format_target(false)
    .init();

    let cli_opts = Opts::parse();
    let infos = get_infos(cli_opts.clone()).await.unwrap();

    if cli_opts.expose {
        let domain = maybe_expose_local_process(cli_opts.clone(), infos.clone()).await;
        let port = if cli_opts.tls {
            if infos.exposed_port_https == 443 {
                String::from("")
            } else {
                format!(":{}", infos.exposed_port_https)
            }
        } else {
            if infos.exposed_port_https == 443 {
                String::from("")
            } else {
                format!(":{}", infos.exposed_port_http)
            }
        };
        if cli_opts.tls {
            info!("");
            info!("your service will be available at: https://{}{}", domain, port);
            info!("");
        } else {
            info!("");
            info!("your service will be available at: http://{}{}", domain, port);
            info!("");
        }
    }
    loop {
        info!("connecting the tunnel ...");
        match connect_tunnel(cli_opts.clone()).await {
            Ok(_) => debug!("connection closed"),
            Err(e) => debug!("connection closed with error: {}", e),
        };
        std::thread::sleep(std::time::Duration::from_secs(2));
    }
}