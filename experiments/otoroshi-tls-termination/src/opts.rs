use clap::{arg, App, ArgMatches, Parser};

#[derive(Clone, Debug)]
pub struct AppConfig {
    pub host: String,
    pub cid: String,
    pub csec: String,
    pub mtls: bool,
    pub workers: usize,
    pub auto_refresh: bool,
    pub refresh_every_sec: u64,
    pub listen_addr: String,
    pub server_addr: String, 
    pub oto_url_base: String,
    pub whole_chain: bool
}

impl AppConfig {
  pub fn parse() -> AppConfig {
    get_app_config()
  }
}

#[derive(Parser, Clone, Debug)]
#[clap(name = "otoroshi_tls_termination")]
#[clap(version = "0.1.0")]
#[clap(about = "Handles otoroshi TLS termination", long_about = None)]
pub struct Opts {
    /// the otoroshi api hostname
    #[clap(long, default_value = "otoroshi-api.oto.tools")]
    pub host: String,
    /// the otoroshi client-id
    #[clap(long, default_value = "admin-api-apikey-id")]
    pub cid: String,
    /// the otoroshi client-secret
    #[clap(long, default_value = "admin-api-apikey-secret")]
    pub csec: String,
    /// enable mTLS want mode
    #[clap(long)]
    pub mtls: bool,
    /// pass the whole client certificate chain to otoroshi through a header
    #[clap(long)]
    pub whole_chain: bool,
    /// the number of workers
    #[clap(long, default_value = "1")]
    pub workers: usize,
    /// disable auto refresh
    #[clap(long)]
    pub no_refresh: bool,
    /// fetch otoroshi certificates every n seconds
    #[clap(long, default_value = "30")]
    pub refresh_every: u64,
    /// the input TCP port
    #[clap(long, default_value = "8443")]
    pub input: usize,
    /// the output TCP port
    #[clap(long, default_value = "8080")]
    pub output: usize, 
    /// the otoroshi ip address
    #[clap(long, default_value = "127.0.0.1")]
    pub ip: String,
    /// the otoroshi url to fetch certs
    #[clap(long)]
    pub oto_url: Option<String>
}

impl Opts {
  pub fn as_config(&self) -> AppConfig {
    let listen_addr = format!("0.0.0.0:{}", self.input);
    let server_addr = format!("{}:{}", self.ip, self.output);
    let oto_url_base = self.oto_url.clone().unwrap_or(format!("http://{}", server_addr));
    AppConfig {
      host: self.host.clone(),
      cid: self.cid.clone(),
      csec: self.csec.clone(),
      mtls: self.mtls,
      auto_refresh: !self.no_refresh,
      refresh_every_sec: self.refresh_every,
      workers: self.workers,
      listen_addr: listen_addr,
      server_addr: server_addr,
      oto_url_base: oto_url_base,
      whole_chain: self.whole_chain,
    }
  }
  pub fn as_config_debug(&self) -> AppConfig {
    let listen_addr = format!("0.0.0.0:{}", self.input);
    let server_addr = format!("{}:{}", self.ip, "9999");
    let oto_url_base = self.oto_url.clone().unwrap_or(format!("http://{}", server_addr));
    AppConfig {
      host: self.host.clone(),
      cid: self.cid.clone(),
      csec: self.csec.clone(),
      mtls: true, //self.mtls,
      auto_refresh: !self.no_refresh,
      refresh_every_sec: self.refresh_every,
      workers: self.workers,
      listen_addr: listen_addr,
      server_addr: server_addr,
      oto_url_base: oto_url_base,
      whole_chain: self.whole_chain,
    }
  }
}

fn get_app_desc() -> ArgMatches {
  App::new("otoroshi_tls_termination")
      .version("0.1.0")
      .about("Handles otoroshi TLS termination")
      .arg(arg!(--input <VALUE> "the input TCP port. Default is 8443").required(false).validator(|s| s.parse::<i64>()))
      .arg(arg!(--output <VALUE> "the output TCP port. Default is 8080").required(false).validator(|s| s.parse::<i64>()))
      .arg(arg!(--ip <VALUE> "the otoroshi ip address. Default is 127.0.0.1").required(false))
      .arg(arg!(--host <VALUE> "the otoroshi api hostname. Default is otoroshi-api.oto.tools").required(false))
      .arg(arg!(--oto-url <VALUE> "the otoroshi url to fetch certificates from").required(false))
      .arg(arg!(--cid <VALUE> "the otoroshi client-id. Default is admin-api-apikey-id").required(false))
      .arg(arg!(--csec <VALUE> "the otoroshi client-secret. Default is admin-api-apikey-secret").required(false))
      .arg(arg!(--workers <VALUE> "the number of workers. Default is 1").required(false))
      .arg(arg!(--refresh_every <VALUE> "refresh certificats every n seconds. Default is 30").required(false))
      .arg(arg!(--mtls "enable mTLS want mode. Default is disabled").required(false))
      .arg(arg!(--whole_chain "pass the whole client certificate chain to otoroshi through a header. Default is disabled").required(false))
      .arg(arg!(--no_refresh "disable auto refresh").required(false))
      .get_matches()
}

fn get_app_config() -> AppConfig {
  let matches = get_app_desc();
  let port_in: usize = matches.value_of_t("input").unwrap_or(8443);
  let port_out: usize = matches.value_of_t("output").unwrap_or(8080);
  let ip: String = matches.value_of_t("ip").unwrap_or("127.0.0.1".to_string());
  let host: String = matches.value_of_t("host").unwrap_or("otoroshi-api.oto.tools".to_string());
  let cid: String = matches.value_of_t("cid").unwrap_or("admin-api-apikey-id".to_string());
  let csec: String = matches.value_of_t("csec").unwrap_or("admin-api-apikey-secret".to_string());
  let workers: usize = matches.value_of_t("workers").unwrap_or(1);
  let refresh_every: u64 = matches.value_of_t("refresh_every").unwrap_or(30);
  let oto_url: Option<String> = matches.value_of("oto-url").map(|s| s.to_string());
  let mtls = matches.is_present("mtls");
  let whole_chain = matches.is_present("whole_chain");
  let no_refresh = matches.is_present("no_refresh");
  let listen_addr = format!("0.0.0.0:{}", port_in);
  let server_addr = format!("{}:{}", ip, port_out);
  let oto_url_base = oto_url.clone().unwrap_or(format!("http://{}", server_addr));
  let cfg = AppConfig {
      host: host,
      cid: cid,
      csec: csec,
      mtls: mtls,
      auto_refresh: !no_refresh,
      refresh_every_sec: !refresh_every,
      workers: workers,
      listen_addr: listen_addr,
      server_addr: server_addr,
      oto_url_base: oto_url_base,
      whole_chain: whole_chain
  };
  // dbg!(cfg.clone());
  cfg
}
