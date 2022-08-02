use clap::{Parser};

#[derive(Parser, Clone, Debug)]
#[clap(name = "otoroshi_remote_tunnel_agent")]
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

impl Opts {
  pub fn build_from_command_line() -> Opts {
    Opts::parse()
  }
}
