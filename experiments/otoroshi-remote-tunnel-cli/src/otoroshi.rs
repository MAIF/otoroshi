use base64::{encode};
use hyper::{Method, Request, Client};
use serde::{Deserialize, Serialize};

use crate::opts::Opts;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct OtoroshInfos {
    pub domain: String,
    pub scheme: String,
    pub exposed_port_http: i16,
    pub exposed_port_https: i16,
}

pub struct Otoroshi {}

impl Otoroshi {
  
    pub async fn get_infos(opts: Opts) -> Option<OtoroshInfos> {
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

    pub async fn maybe_expose_local_process(opts: Opts, infos: OtoroshInfos) -> String {
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
            Self::expose_local_process(cloned, infos.clone()).await
        }
    }

    pub async fn expose_local_process(opts: Opts, infos: OtoroshInfos) -> String {
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
}