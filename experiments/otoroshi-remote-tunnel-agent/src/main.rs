#![forbid(unsafe_code)]

#[macro_use]
extern crate log;

pub mod opts;
pub mod otoroshi;
pub mod tunnel;

use crate::opts::Opts;
use crate::otoroshi::Otoroshi;
use crate::tunnel::Tunnel;

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

    let cli_opts = Opts::build_from_command_line();
    let infos = Otoroshi::get_infos(cli_opts.clone()).await.unwrap();

    if cli_opts.expose {
        let domain = Otoroshi::maybe_expose_local_process(cli_opts.clone(), infos.clone()).await;
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
        match Tunnel::connect(cli_opts.clone()).await {
            Ok(_) => debug!("connection closed"),
            Err(e) => debug!("connection closed with error: {}", e),
        };
        std::thread::sleep(std::time::Duration::from_secs(2));
    }
}