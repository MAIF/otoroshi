#![forbid(unsafe_code)]

#[macro_use]
extern crate log;

pub mod certs;
pub mod opts;
pub mod tls;
pub mod proxy;

use std::error::Error;
use clap::Parser;

use crate::opts::Opts;
use crate::tls::Tls;
use crate::proxy::Proxy;

// otoroshi companion that will handle tls termination and mtls validation
#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {

    let def_log = if cfg!(debug_assertions) {
        "debug"
    } else {
        "info"
    };
    env_logger::Builder::from_env(
        env_logger::Env::new()
        .filter_or("OTOROSHI_TLS_TERMINATION_LOG", def_log)
        .write_style_or("OTOROSHI_TLS_TERMINATION_LOG_STYLE", "always")
    )
    .format_timestamp(None)
    .format_module_path(false)
    .format_target(false)
    .init();

    let app_config = if cfg!(debug_assertions) {
        Opts::parse().as_config_debug()
    } else {
        Opts::parse().as_config()
    };

    info!("");
    let tls: &'static Tls = Box::leak(Box::new(Tls::create(app_config.clone()).await));

    info!("");
    info!("listening on '{}' and forwarding to '{}'", app_config.listen_addr, app_config.server_addr);
    if app_config.mtls {
        info!(" - mTLS is enabled")
    } 
    if app_config.auto_refresh {
        info!("- auto refresh is enabled")
    }
    info!("");
    if cfg!(debug_assertions) {
        // dbg!(app_config.clone());
        debug!("{:?}", app_config.clone());
        info!("");
    }

    if app_config.auto_refresh {
        tokio::spawn(async move {
            loop {
                std::thread::sleep(std::time::Duration::from_secs(10));
                tls.update().await;
            }
        });
    }

    let mut handles = vec![];
    for idx in 0..app_config.workers {
        handles.push(Proxy::create(format!("worker-{}", idx).to_string(), tls, app_config.clone()));
    }
    futures::future::join_all(handles).await;

    Ok(())
}

