#![allow(unused_variables)]
#![allow(dead_code)]
#![warn(unused_imports)]
#![allow(unused_must_use)]

extern crate clap;
extern crate env_logger;
extern crate futures;
#[macro_use]
extern crate hyper;
extern crate json_color;
extern crate rand;
extern crate reqwest;
#[macro_use]
extern crate serde_json;
extern crate toml;

mod client;
mod http;
mod opt;
mod tryout;
mod file;

use std::io::Result;
use clap::{App, Arg, ArgMatches, SubCommand};
use client::OtoroshiClient;
use std::io::{Error, ErrorKind};
use toml::Value as ToMlValue;
use opt::OptionExtension;
use std::path::{Path, PathBuf};
use file::load_from_path;

fn empty_config() -> Result<ToMlValue> {
    match toml::from_str("") {
        Ok(config) => Ok(config),
        Err(e) => {
            println!("decoding error: {:?}", e);
            Err(Error::new(
                ErrorKind::InvalidData,
                format!("decoding error: {:?}", e),
            ))
        }
    }
}

fn extract_oto_args(
    default_config: ToMlValue,
    subcommand: &ArgMatches,
) -> (String, String, String, bool, bool, String, Option<String>) {
    // TODO : if oto-file, then extract from file

    let selector = subcommand.value_of("selector");
    let no_colors = subcommand.is_present("no-colors");
    let debug = subcommand.is_present("debug");

    match subcommand.value_of("oto-file") {
        Some(file) => {
            // let path = Path::new(f);
            let actual_config = load_from_path(file).unwrap();
            let host = actual_config
                .get("host")
                .and_then(|i| i.as_str())
                .expect("You have to specify Otoroshi Host in config file");
            let url = default_config
                .get("url")
                .and_then(|i| i.as_str())
                .expect("You have to specify Otoroshi URL in config file");
            let client_id = default_config
                .get("client_id")
                .and_then(|i| i.as_str())
                .expect("You have to specify Otoroshi client id in config file");
            let client_secret = default_config
                .get("client_secret")
                .and_then(|i| i.as_str())
                .expect("You have to specify Otoroshi client secret in config file");
            let args = (
                host.to_string(),
                client_id.to_string(),
                client_secret.to_string(),
                no_colors,
                debug,
                url.to_string(),
                selector.map(|i| i.to_string()),
            );
            if debug {
                println!(
                    r#"[DEBUG] Accessing Otoroshi located at {} on {} with credentials {}/{}"#,
                    url, host, client_id, client_secret
                );
            }
            args
        }
        _ => {
            let host = subcommand
                .value_of("oto-host")
                .or(default_config.get("host").and_then(|i| i.as_str()))
                .m_get_or_else("otoroshi-api.foo.bar");
            //.expect("You have to specify Otoroshi Host");
            let url = subcommand
                .value_of("oto-url")
                .or(default_config.get("url").and_then(|i| i.as_str()))
                .m_get_or_else("http://127.0.0.1:8080");
            //.expect("You have to specify Otoroshi URL");
            let client_id = subcommand
                .value_of("oto-client-id")
                .or(default_config.get("client_id").and_then(|i| i.as_str()))
                .m_get_or_else("admin-api-apikey-id");
            //.expect("You have to specify Otoroshi client id");
            let client_secret = subcommand
                .value_of("oto-client-secret")
                .or(default_config.get("client_secret").and_then(|i| i.as_str()))
                .m_get_or_else("admin-api-apikey-secret");
            //.expect("You have to specify Otoroshi client secret");
            let args = (
                host.to_string(),
                client_id.to_string(),
                client_secret.to_string(),
                no_colors,
                debug,
                url.to_string(),
                selector.map(|i| i.to_string()),
            );
            if debug {
                println!(
                    r#"[DEBUG] Accessing Otoroshi located at {} on {} with credentials {}/{}"#,
                    url, host, client_id, client_secret
                );
            }
            args
        }
    }
}

fn noop(message: &str) -> String {
    println!("{}", message);
    String::new()
}

fn main() {
    env_logger::init().unwrap();

    let local_config_path: Option<&Path> =
        Some(Path::new("./otoroshicli.toml")).m_filter(|i| i.exists());
    let global_config_path: Option<PathBuf> = std::env::home_dir()
        .map(|mut i| {
            i.push(".otoroshicli.toml");
            i
        })
        .m_filter(|i| i.exists());
    let default_config: ToMlValue = match (local_config_path, global_config_path) {
        (Some(path), _) => load_from_path(path.to_str().unwrap()),
        (_, Some(path)) => load_from_path(path.to_str().unwrap()),
        (_, _) => empty_config(),
    }.unwrap();

    let app = App::new("otoroshicli")
        .version("1.1.2")
        .author("Mathieu ANCELIN <mathieu.ancelin@gmail.com>")
        .about(
            r#"   ____  __________  ____  ____  _____ __  ______     ________    ____
  / __ \/_  __/ __ \/ __ \/ __ \/ ___// / / /  _/    / ____/ /   /  _/
 / / / / / / / / / / /_/ / / / /\__ \/ /_/ // /_____/ /   / /    / /  
/ /_/ / / / / /_/ / _, _/ /_/ /___/ / __  // /_____/ /___/ /____/ /   
\____/ /_/  \____/_/ |_|\____//____/_/ /_/___/     \____/_____/___/   
                                                                      
A simple CLI to control the Otoroshi reverse proxy (https://maif.github.io/otoroshi).
You have to provide a $HOME/.otoroshicli.toml, a $PWD/otoroshicli.toml or a --oto-file="..." config file with
'host', 'url', 'client_id', 'client_secret' values to access the Otoroshi instance you need. You can also
pass those values as args. If those values arn't present, default one will be provided to access a standard
development version of Otoroshi."#,
        )
        .arg(
            Arg::with_name("otoroshi_file")
                .long("oto-file")
                .value_name("PATH")
                .help("A config file to access Otoroshi instance")
                .takes_value(true)
                .global(true),
        )
        .arg(
            Arg::with_name("otoroshi_host")
                .long("oto-host")
                .value_name("HOST")
                .help("Sets the host where Otoroshi is available")
                .takes_value(true)
                .global(true),
        )
        .arg(
            Arg::with_name("otoroshi_url")
                .long("oto-url")
                .value_name("URL")
                .help("Sets the URL where Otoroshi is available")
                .takes_value(true)
                .global(true),
        )
        .arg(
            Arg::with_name("otoroshi_client_id")
                .long("oto-client-id")
                .value_name("CLIENT_ID")
                .help("Sets the client ID for the Otoroshi instance")
                .takes_value(true)
                .global(true),
        )
        .arg(
            Arg::with_name("otoroshi_client_secret")
                .long("oto-client-secret")
                .value_name("CLIENT_SECRET")
                .help("Sets the client secret for the Otoroshi instance")
                .takes_value(true)
                .global(true),
        )
        .arg(
            Arg::with_name("selector")
                .long("select")
                .value_name("JSON_POINTER")
                .help("Select subset of the response")
                .takes_value(true)
                .global(true),
        )
        .arg(
            Arg::with_name("no-colors")
                .long("no-colors")
                .short("n")
                .help("Does not color output")
                .global(true),
        )
        .arg(
            Arg::with_name("debug")
                .long("debug")
                .help("Debug output")
                .global(true),
        )
        .subcommand(
            SubCommand::with_name("lines")
                .about("Control Otoroshi lines")
                .subcommand(SubCommand::with_name("all").about("List Otoroshi lines"))
                .subcommand(SubCommand::with_name("count").about("Count Otoroshi lines")),
        )
        .subcommand(
            SubCommand::with_name("groups")
                .about("Control Otoroshi service groups")
                .subcommand(SubCommand::with_name("all").about("List Otoroshi service groups"))
                .subcommand(SubCommand::with_name("count").about("Count Otoroshi service groups"))
                .subcommand(
                    SubCommand::with_name("fetch")
                        .about("Fetch an Otoroshi group")
                        .arg(
                            Arg::with_name("id")
                                .required(true)
                                .help("Group id")
                                .index(1),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("delete")
                        .about("Delete an Otoroshi group")
                        .arg(
                            Arg::with_name("id")
                                .required(true)
                                .help("Group id")
                                .index(1),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("create")
                        .about("Create an Otoroshi group")
                        .arg(
                            Arg::with_name("id")
                                .long("id")
                                .help("Group id")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("name")
                                .required(true)
                                .long("name")
                                .help("Group name")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("description")
                                .long("desc")
                                .help("Group description")
                                .takes_value(true),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("update")
                        .about("Update an Otoroshi group")
                        .arg(
                            Arg::with_name("id")
                                .required(true)
                                .help("Group name")
                                .index(1),
                        )
                        .arg(
                            Arg::with_name("name")
                                .long("name")
                                .help("Group name")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("description")
                                .long("desc")
                                .help("Group description")
                                .takes_value(true),
                        ),
                ),
        )
        .subcommand(
            SubCommand::with_name("apikeys")
                .about("Control Otoroshi Api Keys")
                .subcommand(SubCommand::with_name("all").about("List Otoroshi Api Keys"))
                .subcommand(
                    SubCommand::with_name("from")
                        .about("List Otoroshi Api Keys for a service group")
                        .arg(
                            Arg::with_name("group")
                                .required(true)
                                .help("Group id for the Api Keys")
                                .index(1),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("count")
                        .about("Count Otoroshi API Keys for a service group")
                        .arg(
                            Arg::with_name("group")
                                .required(true)
                                .help("Group id for the Api Keys")
                                .index(1),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("delete")
                        .about("Delete an Otoroshi Api Key")
                        .arg(
                            Arg::with_name("clientId")
                                .required(true)
                                .help("Api Key client id")
                                .index(1),
                        )
                        .arg(
                            Arg::with_name("group")
                                .long("group")
                                .required(true)
                                .help("Api Key client id")
                                .takes_value(true),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("create")
                        .about("Create an Otoroshi Api Key")
                        .arg(
                            Arg::with_name("name")
                                .long("name")
                                .required(true)
                                .help("Api Key name")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("group")
                                .long("group")
                                .required(true)
                                .help("Api Key authorized group id")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("id")
                                .long("clientId")
                                .required(true)
                                .help("Api Key client id")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("secret")
                                .long("clientSecret")
                                .required(true)
                                .help("Api Key client secret")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("enabled")
                                .long("enabled")
                                .help("Api Key is enabled")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("throttlingQuota")
                                .long("throttlingQuota")
                                .help("Api Key quota per second")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("dailyQuota")
                                .long("dailyQuota")
                                .help("Api Key quota per second")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("monthlyQuota")
                                .long("monthlyQuota")
                                .help("Api Key quota per second")
                                .takes_value(true),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("update")
                        .about("Update an Otoroshi Api Key")
                        .arg(
                            Arg::with_name("id")
                                .long("id")
                                .required(true)
                                .help("Api Key client id")
                                .index(1),
                        )
                        .arg(
                            Arg::with_name("secret")
                                .long("clientSecret")
                                .help("Api Key client secret")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("group")
                                .long("group")
                                .help("Api Key authorized group id")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("name")
                                .long("name")
                                .help("Api Key name")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("enabled")
                                .long("enabled")
                                .help("Api Key is enabled")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("throttlingQuota")
                                .long("throttlingQuota")
                                .help("Api Key quota per second")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("dailyQuota")
                                .long("dailyQuota")
                                .help("Api Key quota per second")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("monthlyQuota")
                                .long("monthlyQuota")
                                .help("Api Key quota per second")
                                .takes_value(true),
                        ),
                ),
        )
        .subcommand(
            SubCommand::with_name("services")
                .about("Control Otoroshi services")
                .subcommand(SubCommand::with_name("all").about("List Otoroshi services"))
                .subcommand(SubCommand::with_name("count").about("Count Otoroshi services"))
                .subcommand(
                    SubCommand::with_name("fetch")
                        .about("Fetch an Otoroshi service")
                        .arg(
                            Arg::with_name("id")
                                .required(true)
                                .help("service id")
                                .index(1),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("delete")
                        .about("Delete an Otoroshi service")
                        .arg(
                            Arg::with_name("id")
                                .required(true)
                                .help("service id")
                                .index(1),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("for_group")
                        .about("List Otoroshi services for a group")
                        .arg(
                            Arg::with_name("group")
                                .required(true)
                                .help("Group id")
                                .index(1),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("for-line")
                        .about("List Otoroshi services for a line")
                        .arg(
                            Arg::with_name("line")
                                .required(true)
                                .help("Line id")
                                .index(1),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("stats")
                        .about("Live stats for a service")
                        .arg(
                            Arg::with_name("id")
                                .required(true)
                                .help("Service id")
                                .index(1),
                        )
                        .arg(Arg::with_name("tail").short("f").help("Tail stats")),
                )
                .subcommand(
                    SubCommand::with_name("add-target")
                        .about("Add a target for a service")
                        .arg(
                            Arg::with_name("id")
                                .required(true)
                                .help("Service id")
                                .index(1),
                        )
                        .arg(
                            Arg::with_name("target")
                                .long("target")
                                .help("New target")
                                .takes_value(true),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("rem-target")
                        .about("Remove a target for a service")
                        .arg(
                            Arg::with_name("id")
                                .required(true)
                                .help("Service id")
                                .index(1),
                        )
                        .arg(
                            Arg::with_name("target")
                                .long("target")
                                .help("New target")
                                .takes_value(true),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("create")
                        .about("Create an Otoroshi service")
                        .arg(Arg::with_name("id").long("id").takes_value(true))
                        .arg(
                            Arg::with_name("group")
                                .required(true)
                                .long("group")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("name")
                                .required(true)
                                .long("name")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("env")
                                .required(true)
                                .long("env")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("domain")
                                .required(true)
                                .long("domain")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("subdomain")
                                .required(true)
                                .long("subdomain")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("target")
                                .required(true)
                                .long("target")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(Arg::with_name("root").long("root").takes_value(true))
                        .arg(
                            Arg::with_name("matching-root")
                                .long("matching-root")
                                .takes_value(true),
                        )
                        .arg(Arg::with_name("not-enabled").long("not-enabled"))
                        .arg(Arg::with_name("private-app").long("private-app"))
                        .arg(Arg::with_name("no-force-https").long("no-force-https"))
                        .arg(Arg::with_name("maintenance-mode").long("maintenance-mode"))
                        .arg(Arg::with_name("build-mode").long("build-mode"))
                        .arg(
                            Arg::with_name("enforce-secure-communication")
                                .long("enforce-secure-communication"),
                        )
                        .arg(
                            Arg::with_name("send-headers-back")
                                .long("send-headers-back"),
                        )
                        .arg(
                            Arg::with_name("secure-communication-exclusion")
                                .long("secure-communication-exclusion")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("public-pattern")
                                .long("public-pattern")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("private-pattern")
                                .long("private-pattern")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("additional-header")
                                .long("additional-header")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("matching-header")
                                .long("matching-header")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("whitelisted-url")
                                .long("whitelisted-url")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("blacklisted-url")
                                .long("blacklisted-url")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("healthcheck-enabled")
                                .long("healthcheck-enabled")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("healthcheck-url")
                                .long("healthcheck-url")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("canary-enabled")
                                .long("canary-enabled")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("canary-traffic")
                                .long("canary-traffic")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("canary-target")
                                .long("canary-target")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("canary-root")
                                .long("canary-root")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("no-client-circuit-breaker")
                                .long("no-client-circuit-breaker"),
                        )
                        .arg(
                            Arg::with_name("client-retries")
                                .long("client-retries")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-max-errors")
                                .long("client-max-errors")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-retry-delay")
                                .long("client-retry-delay")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-backoff-factor")
                                .long("client-backoff-factor")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-call-timeout")
                                .long("client-call-timeout")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-global-timeout")
                                .long("client-global-timeout")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-sample-interval")
                                .long("client-sample-interval")
                                .takes_value(true),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("update")
                        .about("Update an Otoroshi service")
                        .arg(
                            Arg::with_name("id")
                                .required(true)
                                .help("Service id")
                                .index(1),
                        )
                        .arg(Arg::with_name("group").long("group").takes_value(true))
                        .arg(Arg::with_name("name").long("name").takes_value(true))
                        .arg(Arg::with_name("env").long("env").takes_value(true))
                        .arg(Arg::with_name("domain").long("domain").takes_value(true))
                        .arg(
                            Arg::with_name("subdomain")
                                .long("subdomain")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("target")
                                .long("target")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(Arg::with_name("root").long("root").takes_value(true))
                        .arg(Arg::with_name("enabled").long("enabled").takes_value(true))
                        .arg(
                            Arg::with_name("private-app")
                                .long("private-app")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("force-https")
                                .long("force-https")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("maintenance-mode")
                                .long("maintenance-mode")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("build-mode")
                                .long("build-mode")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("matching-root")
                                .long("matching-root")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("enforce-secure-communication")
                                .long("enforce-secure-communication")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("send-headers-back")
                                .long("send-headers-back")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("secure-communication-exclusion")
                                .long("secure-communication-exclusion")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("public-pattern")
                                .long("public-pattern")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("private-pattern")
                                .long("private-pattern")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("additional-header")
                                .long("additional-header")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("matching-header")
                                .long("matching-header")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("whitelisted-url")
                                .long("whitelisted-url")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("blacklisted-url")
                                .long("blacklisted-url")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("healthcheck-enabled")
                                .long("healthcheck-enabled")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("healthcheck-url")
                                .long("healthcheck-url")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("canary-enabled")
                                .long("canary-enabled")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("canary-traffic")
                                .long("canary-traffic")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("canary-target")
                                .long("canary-target")
                                .multiple(true)
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("canary-root")
                                .long("canary-root")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-circuit-breaker")
                                .long("client-circuit-breaker")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-retries")
                                .long("client-retries")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-max-errors")
                                .long("client-max-errors")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-retry-delay")
                                .long("client-retry-delay")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-backoff-factor")
                                .long("client-backoff-factor")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-call-timeout")
                                .long("client-call-timeout")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-global-timeout")
                                .long("client-global-timeout")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("client-sample-interval")
                                .long("client-sample-interval")
                                .takes_value(true),
                        ),
                ),
        )
        .subcommand(SubCommand::with_name("export").about("Export the full Otoroshi state"))
        .subcommand(
            SubCommand::with_name("stats")
                .about("Fetch Otoroshi's global stats")
                .arg(Arg::with_name("tail").short("f").help("Tail on stats")),
        )
        .subcommand(
            SubCommand::with_name("config")
                .about("Control global config of Otoroshi")
                .subcommand(SubCommand::with_name("get").about("Fetch global config of Otoroshi"))
                .subcommand(
                    SubCommand::with_name("update")
                        .about("Update global config of Otoroshi")
                        .arg(
                            Arg::with_name("stream-entity-only")
                                .long("stream-entity-only")
                                .value_name("BOOL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("auto-link-to-default-group")
                                .long("auto-link-to-default-group")
                                .value_name("BOOL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("limit-concurrent-requests")
                                .long("limit-concurrent-requests")
                                .value_name("BOOL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("max-concurrent-requests")
                                .long("max-concurrent-requests")
                                .value_name("LONG")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("max-http10-response-size")
                                .long("max-http10-response-size")
                                .value_name("LONG")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("use-circuit-breakers")
                                .long("use-circuit-breakers")
                                .value_name("BOOL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("api-read-only")
                                .long("api-read-only")
                                .value_name("BOOL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("u2f-login-only")
                                .long("u2f-login-only")
                                .value_name("BOOL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("blacklisted-ip")
                                .long("blacklisted-ip")
                                .multiple(true)
                                .value_name("IP")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("whitelisted-ip")
                                .long("whitelisted-ip")
                                .multiple(true)
                                .value_name("IP")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("throttling-quota")
                                .long("throttling-quota")
                                .value_name("LONG")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("per-ip-throttling-quota")
                                .long("per-ip-throttling-quota")
                                .value_name("LONG")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("alerts-email")
                                .long("alerts-email")
                                .multiple(true)
                                .value_name("EMAIL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("endless-ip")
                                .long("endless-ip")
                                .multiple(true)
                                .value_name("IP")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("backoffice-auth0-client-secret")
                                .long("backoffice-auth0-client-secret")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("backoffice-auth0-client-id")
                                .long("backoffice-auth0-client-id")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("backoffice-auth0-callback-url")
                                .long("backoffice-auth0-callback-url")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("backoffice-auth0-domain")
                                .long("backoffice-auth0-domain")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("private-apps-auth0-client-secret")
                                .long("private-apps-auth0-client-secret")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("private-apps-auth0-client-id")
                                .long("private-apps-auth0-client-id")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("private-apps-auth0-callback-url")
                                .long("private-apps-auth0-callback-url")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("private-apps-auth0-domain")
                                .long("private-apps-auth0-domain")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("mailgun-api-key")
                                .long("mailgun-api-key")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("mailgun-domain")
                                .long("mailgun-domain")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("clever-consumer-key")
                                .long("clever-consumer-key")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("clever-consumer-secret")
                                .long("clever-consumer-secret")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("clever-oauth-token")
                                .long("clever-oauth-token")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("clever-oauth-secret")
                                .long("clever-oauth-secret")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("clever-oauth-orga-id")
                                .long("clever-oauth-orga-id")
                                .value_name("VAL")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("statsd-host")
                                .long("statsd-host")
                                .value_name("HOST")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("statsd-port")
                                .long("statsd-port")
                                .value_name("PORT")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("statsd-datadog")
                                .long("statsd-datadog")
                                .value_name("BOOL")
                                .takes_value(true),
                        ),
                ),
        )
        .subcommand(
            SubCommand::with_name("import")
                .about("Import the full Otoroshi state, erase all previous state")
                .arg(
                    Arg::with_name("from")
                        .long("from")
                        .required(true)
                        .help("Imported file absolute path")
                        .takes_value(true),
                ),
        )
        .subcommand(
            SubCommand::with_name("tryout")
                .about("Features for Otoroshi tryout")
                .subcommand(
                    SubCommand::with_name("serve")
                        .about("Start a small HTTP serve with a simple API")
                        .arg(
                            Arg::with_name("port")
                                .required(true)
                                .help("Server port")
                                .index(1),
                        ),
                )
                .subcommand(
                    SubCommand::with_name("call")
                        .about("Simulates a curl client")
                        .arg(
                            Arg::with_name("url")
                                .help("URL to call")
                                .index(1)
                                .required(true),
                        )
                        .arg(
                            Arg::with_name("header")
                                .short("H")
                                .multiple(true)
                                .help("HTTP Header")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("method")
                                .short("X")
                                .help("HTTP Method")
                                .takes_value(true),
                        )
                        .arg(
                            Arg::with_name("data")
                                .short("d")
                                .help("HTTP body")
                                .takes_value(true),
                        ),
                ),
        );

    let mut cloned = app.clone();
    let matches = app.get_matches();

    match matches.subcommand() {
        ("tryout", Some(subcommand)) => match subcommand.subcommand() {
            ("serve", Some(subcommand)) => {
                tryout::server_tryout_service(
                    subcommand
                        .value_of("port")
                        .expect("You need to specify a port"),
                );
                String::new()
            }
            ("call", Some(subcommand)) => http::curl_call(
                subcommand
                    .value_of("url")
                    .expect("You have to provide an URL to call"),
                subcommand.value_of("method").m_get_or_else("GET"),
                subcommand
                    .values_of("header")
                    .map(|i| i.collect())
                    .unwrap_or(Vec::new()),
                subcommand.value_of("data"),
            ),
            _ => {
                cloned.print_help();
                String::new()
            }
        },
        ("stats", Some(subcommand)) => {
            OtoroshiClient::new(extract_oto_args(default_config, subcommand))
                .stats(subcommand.is_present("tail"))
        }
        ("import", Some(subcommand)) => {
            OtoroshiClient::new(extract_oto_args(default_config, subcommand)).import_datastore(
                subcommand
                    .value_of("from")
                    .expect("You need to specify a file path"),
            )
        }
        ("export", Some(subcommand)) => {
            OtoroshiClient::new(extract_oto_args(default_config, subcommand)).export()
        }
        ("config", Some(subcommand)) => match subcommand.subcommand() {
            ("fetch", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).fetch_config()
            }
            ("update", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).update_config(
                    subcommand.value_of("stream-entity-only"),
                    subcommand.value_of("auto-link-to-default-group"),
                    subcommand.value_of("limit-concurrent-requests"),
                    subcommand.value_of("max-concurrent-requests"),
                    subcommand.value_of("max-http10-response-size"),
                    subcommand.value_of("use-circuit-breakers"),
                    subcommand.value_of("api-read-only"),
                    subcommand.value_of("u2f-login-only"),
                    subcommand.value_of("throttling-quota"),
                    subcommand.value_of("per-ip-throttling-quota"),
                    subcommand
                        .values_of("blacklisted-ip")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("whitelisted-ip")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("alerts-email")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("endless-ip")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand.value_of("backoffice-auth0-client-secret"),
                    subcommand.value_of("backoffice-auth0-client-id"),
                    subcommand.value_of("backoffice-auth0-callback-url"),
                    subcommand.value_of("backoffice-auth0-domain"),
                    subcommand.value_of("private-apps-auth0-client-secret"),
                    subcommand.value_of("private-apps-auth0-client-id"),
                    subcommand.value_of("private-apps-auth0-callback-url"),
                    subcommand.value_of("private-apps-auth0-domain"),
                    subcommand.value_of("mailgun-api-key"),
                    subcommand.value_of("mailgun-domain"),
                    subcommand.value_of("clever-consumer-key"),
                    subcommand.value_of("clever-consumer-secret"),
                    subcommand.value_of("clever-oauth-token"),
                    subcommand.value_of("clever-oauth-secret"),
                    subcommand.value_of("clever-oauth-orga-id"),
                    subcommand.value_of("statsd-host"),
                    subcommand.value_of("statsd-port"),
                    subcommand.value_of("statsd-datadog"),
                )
            }
            _ => OtoroshiClient::new(extract_oto_args(default_config, subcommand)).fetch_config(),
        },
        ("apikeys", Some(subcommand)) => match subcommand.subcommand() {
            ("all", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand))
                    .list_all_api_keys()
            }
            ("from", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).list_api_keys(
                    subcommand
                        .value_of("group")
                        .expect("You need to specify a group id"),
                )
            }
            ("count", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).count_api_keys(
                    subcommand
                        .value_of("group")
                        .expect("You need to specify a group id"),
                )
            }
            ("delete", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).delete_api_key(
                    subcommand
                        .value_of("clientId")
                        .expect("You need to specify an apikey id"),
                    subcommand
                        .value_of("group")
                        .expect("You need to specify a group id"),
                )
            }
            ("create", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).create_api_key(
                    subcommand.value_of("id"),
                    subcommand.value_of("secret"),
                    subcommand
                        .value_of("group")
                        .expect("Api Key authorized group id expected"),
                    subcommand.value_of("name").expect("Api Key name expected"),
                    subcommand.is_present("not-enabled"),
                    subcommand.value_of("throttlingQuota"),
                    subcommand.value_of("dailyQuota"),
                    subcommand.value_of("monthlyQuota"),
                )
            }
            ("update", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).update_api_key(
                    subcommand
                        .value_of("id")
                        .expect("Api Key client id expected"),
                    subcommand.value_of("secret"),
                    subcommand
                        .value_of("group")
                        .expect("Api Key authorized group id expected"),
                    subcommand.value_of("name"),
                    subcommand.value_of("enabled"),
                    subcommand.value_of("throttlingQuota"),
                    subcommand.value_of("dailyQuota"),
                    subcommand.value_of("monthlyQuota"),
                )
            }
            _ => OtoroshiClient::new(extract_oto_args(default_config, subcommand))
                .list_all_api_keys(),
        },
        ("lines", Some(subcommand)) => match subcommand.subcommand() {
            ("all", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).list_lines()
            }
            ("count", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).count_lines()
            }
            _ => OtoroshiClient::new(extract_oto_args(default_config, subcommand)).list_lines(),
        },
        ("groups", Some(subcommand)) => match subcommand.subcommand() {
            ("fetch", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).fetch_group(
                    subcommand
                        .value_of("id")
                        .expect("You need to specify a group id"),
                )
            }
            ("all", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).list_groups()
            }
            ("count", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).count_groups()
            }
            ("delete", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).delete_group(
                    subcommand
                        .value_of("id")
                        .expect("You need to specify a group id"),
                )
            }
            ("create", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).create_group(
                    subcommand.value_of("id"),
                    subcommand
                        .value_of("name")
                        .expect("You need to specify a group name"),
                    subcommand
                        .value_of("description")
                        .m_get_or_else("No description"),
                )
            }
            ("update", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).update_group(
                    subcommand
                        .value_of("id")
                        .expect("You need to specify a group id"),
                    subcommand.value_of("name"),
                    subcommand.value_of("description"),
                )
            }
            _ => OtoroshiClient::new(extract_oto_args(default_config, subcommand)).list_groups(),
        },
        ("services", Some(subcommand)) => match subcommand.subcommand() {
            ("stats", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).service_stats(
                    subcommand
                        .value_of("id")
                        .expect("You need to specify a service id"),
                    subcommand.is_present("tail"),
                )
            }
            ("fetch", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).fetch_service(
                    subcommand
                        .value_of("id")
                        .expect("You need to specify a service id"),
                )
            }
            ("all", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).list_services()
            }
            ("for_group", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand))
                    .list_services_for_group(
                        subcommand
                            .value_of("group")
                            .expect("You need to specify a group id"),
                    )
            }
            ("for-line", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand))
                    .list_services_for_line(
                        subcommand
                            .value_of("line")
                            .expect("You need to specify a line id"),
                    )
            }
            ("count", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).count_services()
            }
            ("delete", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).delete_service(
                    subcommand
                        .value_of("id")
                        .expect("You need to specify a service id"),
                )
            }
            ("add-target", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand))
                    .add_target_to_service(
                        subcommand
                            .value_of("id")
                            .expect("You need to specify a service id"),
                        subcommand
                            .value_of("target")
                            .expect("You need to specify a target"),
                    )
            }
            ("rem-target", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand))
                    .rem_target_from_service(
                        subcommand
                            .value_of("id")
                            .expect("You need to specify a service id"),
                        subcommand
                            .value_of("target")
                            .expect("You need to specify a target"),
                    )
            }
            ("create", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).create_service(
                    subcommand.value_of("id"),
                    subcommand.value_of("group"),
                    subcommand.value_of("name"),
                    subcommand.value_of("env"),
                    subcommand.value_of("domain"),
                    subcommand.value_of("subdomain"),
                    subcommand
                        .values_of("target")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand.value_of("root"),
                    subcommand.value_of("matching-root"),
                    subcommand.is_present("not-enabled"),
                    subcommand.is_present("private-app"),
                    subcommand.is_present("no-force-https"),
                    subcommand.is_present("maintenance-mode"),
                    subcommand.is_present("build-mode"),
                    subcommand.is_present("enforce-secure-communication"),
                    subcommand.is_present("send-headers-back"),
                    subcommand
                        .values_of("secure-communication-exclusion")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("public-pattern")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("private-pattern")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("additional-header")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("matching-header")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("whitelisted-url")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("blacklisted-url")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand.is_present("healthcheck-enabled"),
                    subcommand.value_of("healthcheck-url"),
                    subcommand.is_present("no-client-circuit-breaker"),
                    subcommand.value_of("canary-enabled"),
                    subcommand.value_of("canary-traffic"),
                    subcommand
                        .values_of("canary-target")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand.value_of("canary-root"),
                    subcommand.value_of("client-retries"),
                    subcommand.value_of("client-max-errors"),
                    subcommand.value_of("client-retry-delay"),
                    subcommand.value_of("client-backoff-factor"),
                    subcommand.value_of("client-call-timeout"),
                    subcommand.value_of("client-global-timeout"),
                    subcommand.value_of("client-sample-interval"),
                )
            }
            ("update", Some(subcommand)) => {
                OtoroshiClient::new(extract_oto_args(default_config, subcommand)).update_service(
                    subcommand
                        .value_of("id")
                        .expect("You have to provide a service id"),
                    subcommand.value_of("group"),
                    subcommand.value_of("name"),
                    subcommand.value_of("env"),
                    subcommand.value_of("domain"),
                    subcommand.value_of("subdomain"),
                    subcommand
                        .values_of("target")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand.value_of("root"),
                    subcommand.value_of("matching-root"),
                    subcommand.value_of("enabled"),
                    subcommand.value_of("private-app"),
                    subcommand.value_of("force-https"),
                    subcommand.value_of("maintenance-mode"),
                    subcommand.value_of("build-mode"),
                    subcommand.value_of("enforce-secure-communication"),
                    subcommand.value_of("send-headers-back"),
                    subcommand
                        .values_of("secure-communication-exclusion")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("public-pattern")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("private-pattern")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("additional-header")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("matching-header")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("whitelisted-url")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand
                        .values_of("blacklisted-url")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand.value_of("healthcheck-enabled"),
                    subcommand.value_of("healthcheck-url"),
                    subcommand.value_of("canary-enabled"),
                    subcommand.value_of("canary-traffic"),
                    subcommand
                        .values_of("canary-target")
                        .map(|i| i.collect())
                        .unwrap_or(Vec::new()),
                    subcommand.value_of("canary-root"),
                    subcommand.value_of("client-circuit-breaker"),
                    subcommand.value_of("client-retries"),
                    subcommand.value_of("client-max-errors"),
                    subcommand.value_of("client-retry-delay"),
                    subcommand.value_of("client-backoff-factor"),
                    subcommand.value_of("client-call-timeout"),
                    subcommand.value_of("client-global-timeout"),
                    subcommand.value_of("client-sample-interval"),
                )
            }
            _ => OtoroshiClient::new(extract_oto_args(default_config, subcommand)).list_services(),
        },
        _ => {
            cloned.print_help();
            String::new()
        }
    };
}
