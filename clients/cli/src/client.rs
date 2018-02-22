extern crate clap;
extern crate env_logger;
extern crate json_color;
extern crate reqwest;
extern crate serde_json;

use std::time::Duration;
use std::thread;

use http;
use json_color::Colorizer;
use reqwest::Client;
use serde_json::Value;
use opt::OptionExtension;
use file::load_file;

header! { (UserAgent, "User-Agent") => [String] }
header! { (Accept, "Accept") => [String] }
header! { (ContentType, "Content-Type") => [String] }
header! { (OpunClientId, "Opun-Client-Id") => [String] }
header! { (OpunClientSecret, "Opun-Client-Secret") => [String] }
header! { (Host, "Host") => [String] }

fn opt_to_json(opt: Option<&str>) -> String {
    match opt {
        Some(val) => format!(r#""{}""#, val),
        None => "null".to_string(),
    }
}

fn opt_bool_to_json(opt: bool) -> String {
    match opt {
        true => "true".to_string(),
        false => "false".to_string(),
    }
}

fn opt_nbr_to_json(opt: Option<&str>) -> String {
    match opt {
        Some(val) => format!(r#"{}"#, val),
        None => "null".to_string(),
    }
}

pub struct OtoroshiClient {
    pub client: Client,
    colorizer: Colorizer,
    no_colors: bool,
    pub debug: bool,
    pub host: String,
    pub url: String,
    pub client_id: String,
    pub client_secret: String,
    pub selector: String,
}

impl OtoroshiClient {
    pub fn new(
        tuple: (String, String, String, bool, bool, String, Option<String>),
    ) -> OtoroshiClient {
        OtoroshiClient {
            client: Client::new().unwrap(),
            colorizer: Colorizer::arbitrary(),
            no_colors: tuple.3,
            debug: tuple.4,
            host: tuple.0,
            url: tuple.5,
            client_id: tuple.1,
            client_secret: tuple.2,
            selector: tuple.6.unwrap_or("".to_string()),
        }
    }

    fn output_json(&self, content: String, void_value: Value) -> String {
        let rawjson: Value = serde_json::from_str(content.as_str()).unwrap();
        let json: &Value = rawjson
            .pointer(self.selector.as_str())
            .unwrap_or(&void_value);
        let json_prettyfied_str = serde_json::to_string_pretty(&json).unwrap();
        if self.no_colors {
            println!("{}", json_prettyfied_str);
            json_prettyfied_str
        } else {
            let json_colorized_str = self.colorizer
                .colorize_json_str(json_prettyfied_str.as_str())
                .unwrap();
            println!("{}", json_colorized_str);
            json_colorized_str
        }
    }

    pub fn list_lines(&self) -> String {
        let data = http::fetch_data(self, "/api/lines");
        self.output_json(data, json!([]))
    }

    pub fn count_lines(&self) -> String {
        let data = http::fetch_data(self, "/api/lines");
        self.output_json(self.count_in_array(data), json!({}))
    }

    pub fn list_groups(&self) -> String {
        let data = http::fetch_data(self, "/api/groups");
        self.output_json(data, json!([]))
    }

    pub fn count_groups(&self) -> String {
        let data = http::fetch_data(self, "/api/groups");
        self.output_json(self.count_in_array(data), json!({}))
    }

    pub fn list_services(&self) -> String {
        let data = http::fetch_data(self, "/api/services");
        self.output_json(data, json!([]))
    }

    pub fn count_services(&self) -> String {
        let data = http::fetch_data(self, "/api/services");
        self.output_json(self.count_in_array(data), json!({}))
    }

    pub fn list_api_keys(&self, group_id: &str) -> String {
        let data = http::fetch_data(self, format!("/api/groups/{}/apikeys", group_id).as_str());
        self.output_json(data, json!([]))
    }

    pub fn count_api_keys(&self, group_id: &str) -> String {
        let data = http::fetch_data(self, format!("/api/groups/{}/apikeys", group_id).as_str());
        self.output_json(self.count_in_array(data), json!({}))
    }

    pub fn export(&self) -> String {
        let data = http::fetch_data(self, "/api/otoroshi.json");
        self.output_json(data, json!({}))
    }

    pub fn stats(&self, tail: bool) -> String {
        if tail {
            loop {
                let data = http::fetch_data(self, "/api/live");
                print!("{}[2J", 27 as char);
                println!("Otoroshi live stats\n===================\n");
                self.output_json(data, json!({}));
                thread::sleep(Duration::from_millis(5000));
            }
        } else {
            let data = http::fetch_data(self, "/api/live");
            self.output_json(data, json!({}))
        }
    }

    pub fn service_stats(&self, service_id: &str, tail: bool) -> String {
        if tail {
            loop {
                let data = http::fetch_data(self, format!("/api/live/{}", service_id).as_str());
                print!("{}[2J", 27 as char);
                println!("Otoroshi service live stats\n===========================\n");
                self.output_json(data, json!({}));
                thread::sleep(Duration::from_millis(5000));
            }
        } else {
            let data = http::fetch_data(self, format!("/api/live/{}", service_id).as_str());
            self.output_json(data, json!({}))
        }
    }

    pub fn fetch_config(&self) -> String {
        let data = http::fetch_data(self, "/api/globalconfig");
        self.output_json(data, json!({}))
    }

    pub fn update_config(
        &self,
        stream_entity_only: Option<&str>,
        auto_link_to_default_group: Option<&str>,
        limit_concurrent_requests: Option<&str>,
        max_concurrent_requests: Option<&str>,
        max_http10_response_size: Option<&str>,
        use_circuit_breakers: Option<&str>,
        api_read_only: Option<&str>,
        u2f_login_only: Option<&str>,
        throttling_quota: Option<&str>,
        per_ip_throttling_quota: Option<&str>,

        blacklisted_ip: Vec<&str>,
        whitelisted_ip: Vec<&str>,
        alerts_email: Vec<&str>,
        endless_ip: Vec<&str>,

        bo_auth0_client_secret: Option<&str>,
        bo_auth0_client_id: Option<&str>,
        bo_auth0_callback_url: Option<&str>,
        bo_auth0_domain: Option<&str>,
        papps_auth0_client_secret: Option<&str>,
        papps_auth0_client_id: Option<&str>,
        papps_auth0_callback_url: Option<&str>,
        papps_auth0_domain: Option<&str>,
        mailgun_api_key: Option<&str>,
        mailgun_domain: Option<&str>,
        clever_consumer_key: Option<&str>,
        clever_consumer_secret: Option<&str>,
        clever_oauth_token: Option<&str>,
        clever_oauth_secret: Option<&str>,
        clever_oauth_orga_id: Option<&str>,

        statsd_host: Option<&str>,
        statsd_port: Option<&str>,
        statsd_datadog: Option<&str>,
    ) -> String {
        let mut updates: Vec<String> = Vec::new();
        stream_entity_only.m_foreach(|stream_entity_only| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/streamEntityOnly", "value": {} }}"#,
                stream_entity_only
            ))
        });
        auto_link_to_default_group.m_foreach(|auto_link_to_default_group| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/autoLinkToDefaultGroup", "value": {} }}"#,
                auto_link_to_default_group
            ))
        });
        limit_concurrent_requests.m_foreach(|limit_concurrent_requests| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/limitConcurrentRequests", "value": {} }}"#,
                limit_concurrent_requests
            ))
        });
        max_concurrent_requests.m_foreach(|max_concurrent_requests| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/maxConcurrentRequests", "value": {} }}"#,
                max_concurrent_requests
            ))
        });
        max_http10_response_size.m_foreach(|max_http10_response_size| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/maxHttp10ResponseSize", "value": {} }}"#,
                max_http10_response_size
            ))
        });
        use_circuit_breakers.m_foreach(|use_circuit_breakers| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/useCircuitBreakers", "value": {} }}"#,
                use_circuit_breakers
            ))
        });
        api_read_only.m_foreach(|api_read_only| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/apiReadOnly", "value": {} }}"#,
                api_read_only
            ))
        });
        u2f_login_only.m_foreach(|u2f_login_only| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/u2fLoginOnly", "value": {} }}"#,
                u2f_login_only
            ))
        });
        throttling_quota.m_foreach(|throttling_quota| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/throttlingQuota", "value": {} }}"#,
                throttling_quota
            ))
        });
        per_ip_throttling_quota.m_foreach(|per_ip_throttling_quota| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/perIpThrottlingQuota", "value": {} }}"#,
                per_ip_throttling_quota
            ))
        });
        if !blacklisted_ip.is_empty() {
            let values = blacklisted_ip
                .iter()
                .map(|ip| format!(r#""{}""#, ip))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/ipFiltering/blacklist", "value": {} }}"#,
                array
            ));
        }
        if !whitelisted_ip.is_empty() {
            let values = whitelisted_ip
                .iter()
                .map(|ip| format!(r#""{}""#, ip))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/ipFiltering/whitelist", "value": {} }}"#,
                array
            ));
        }
        if !alerts_email.is_empty() {
            let values = alerts_email
                .iter()
                .map(|ip| format!(r#""{}""#, ip))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/alertsEmails", "value": {} }}"#,
                array
            ));
        }
        if !endless_ip.is_empty() {
            let values = endless_ip
                .iter()
                .map(|ip| format!(r#""{}""#, ip))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/endlessIpAddresses", "value": {} }}"#,
                array
            ));
        }

        bo_auth0_client_secret.m_foreach(|bo_auth0_client_secret| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/backofficeAuth0Config/secret", "value": "{}" }}"#,
                bo_auth0_client_secret
            ))
        });
        bo_auth0_client_id.m_foreach(|bo_auth0_client_id| {
            updates.push(format!(
                r#"{{"op":"replace","path":"/backofficeAuth0Config/clientId","value":"{}"}}"#,
                bo_auth0_client_id
            ))
        });
        bo_auth0_callback_url.m_foreach(|bo_auth0_callback_url| {
            updates.push(format!(
                r#"{{"op":"replace","path":"/backofficeAuth0Config/callbackURL","value":"{}"}}"#,
                bo_auth0_callback_url
            ))
        });
        bo_auth0_domain.m_foreach(|bo_auth0_domain| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/backofficeAuth0Config/domain", "value": "{}" }}"#,
                bo_auth0_domain
            ))
        });
        papps_auth0_client_secret.m_foreach(|papps_auth0_client_secret| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/privateAppsAuth0Config/secret", "value": "{}" }}"#,
                papps_auth0_client_secret
            ))
        });
        papps_auth0_client_id.m_foreach(|papps_auth0_client_id| {
            updates.push(format!(
                r#"{{"op":"replace","path":"/privateAppsAuth0Config/clientId","value":"{}"}}"#,
                papps_auth0_client_id
            ))
        });
        papps_auth0_callback_url.m_foreach(|papps_auth0_callback_url| {
            updates.push(format!(
                r#"{{"op":"replace","path":"/privateAppsAuth0Config/callbackURL","value":"{}"}}"#,
                papps_auth0_callback_url
            ))
        });
        papps_auth0_domain.m_foreach(|papps_auth0_domain| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/privateAppsAuth0Config/domain", "value": "{}" }}"#,
                papps_auth0_domain
            ))
        });
        mailgun_api_key.m_foreach(|mailgun_api_key| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/mailGunSettings/apiKey", "value": "{}" }}"#,
                mailgun_api_key
            ))
        });
        mailgun_domain.m_foreach(|mailgun_domain| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/mailGunSettings/domain", "value": "{}" }}"#,
                mailgun_domain
            ))
        });
        clever_consumer_key.m_foreach(|clever_consumer_key| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/cleverSettings/consumerKey", "value": "{}" }}"#,
                clever_consumer_key
            ))
        });
        clever_consumer_secret.m_foreach(|clever_consumer_secret| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/cleverSettings/consumerSecret", "value": "{}" }}"#,
                clever_consumer_secret
            ))
        });
        clever_oauth_token.m_foreach(|clever_oauth_token| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/cleverSettings/token", "value": "{}" }}"#,
                clever_oauth_token
            ))
        });
        clever_oauth_secret.m_foreach(|clever_oauth_secret| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/cleverSettings/secret", "value": "{}" }}"#,
                clever_oauth_secret
            ))
        });
        clever_oauth_orga_id.m_foreach(|clever_oauth_orga_id| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/cleverSettings/orgaId", "value": "{}" }}"#,
                clever_oauth_orga_id
            ))
        });
        statsd_host.m_foreach(|statsd_host| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/statsdConfig/host", "value": "{}" }}"#,
                statsd_host
            ))
        });
        statsd_port.m_foreach(|statsd_port| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/statsdConfig/port", "value": {} }}"#,
                statsd_port
            ))
        });
        statsd_datadog.m_foreach(|statsd_datadog| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/statsdConfig/datadog", "value": {} }}"#,
                statsd_datadog
            ))
        });

        let update_body = format!(r#"[{}]"#, updates.join(","));
        let data = http::patch_resource(self, "/api/globalconfig", update_body);
        self.output_json(data, json!({}))
    }

    pub fn fetch_group(&self, group_id: &str) -> String {
        let data = http::fetch_data(self, format!("/api/groups/{}", group_id).as_str());
        self.output_json(data, json!({}))
    }

    pub fn fetch_service(&self, service_id: &str) -> String {
        let data = http::fetch_data(self, format!("/api/services/{}", service_id).as_str());
        self.output_json(data, json!({}))
    }

    pub fn list_all_api_keys(&self) -> String {
        let data = http::fetch_data(self, "/api/apikeys");
        self.output_json(data, json!({}))
    }

    pub fn list_services_for_group(&self, group_id: &str) -> String {
        let data = http::fetch_data(self, format!("/api/services?group={}", group_id).as_str());
        self.output_json(data, json!({}))
    }

    pub fn list_services_for_line(&self, line: &str) -> String {
        let data = http::fetch_data(self, format!("/api/services?env={}", line).as_str());
        self.output_json(data, json!({}))
    }

    pub fn delete_api_key(&self, api_key_id: &str, group_id: &str) -> String {
        let data = http::delete_resource(
            self,
            format!("/api/groups/{}/apikeys/{}", group_id, api_key_id).as_str(),
        );
        self.output_json(data, json!({}))
    }

    pub fn delete_group(&self, group_id: &str) -> String {
        let data = http::delete_resource(self, format!("/api/groups/{}", group_id).as_str());
        self.output_json(data, json!({}))
    }

    pub fn delete_service(&self, service_id: &str) -> String {
        let data = http::delete_resource(self, format!("/api/services/{}", service_id).as_str());
        self.output_json(data, json!({}))
    }

    pub fn add_target_to_service(&self, service_id: &str, target: &str) -> String {
        let data = http::post_resource(
            self,
            format!("/api/services/{}/targets", service_id).as_str(),
            format!(r#"{{"target":"{}"}}"#, target),
        );
        self.output_json(data, json!({}))
    }

    pub fn rem_target_from_service(&self, service_id: &str, target: &str) -> String {
        let data = http::delete_resource_with_body(
            self,
            format!("/api/services/{}/targets", service_id).as_str(),
            format!(r#"{{"target":"{}"}}"#, target),
        );
        self.output_json(data, json!({}))
    }

    pub fn import_datastore(&self, from: &str) -> String {
        let file_content = load_file(from).unwrap();
        let data = http::post_resource(self, "/api/otoroshi.json", file_content);
        self.output_json(data, json!({}))
    }

    pub fn create_group(&self, group_id: Option<&str>, name: &str, description: &str) -> String {
        let mut fields: Vec<String> = Vec::new();
        group_id.m_foreach(|group_id| fields.push(format!(r#""id":"{}""#, group_id)));
        fields.push(format!(r#""name":"{}""#, name));
        fields.push(format!(r#""description":"{}""#, description));
        let body = format!(r#"{{{}}}"#, fields.join(","));
        let data = http::post_resource(self, "/api/groups", body);
        self.output_json(data, json!({}))
    }

    pub fn update_group(
        &self,
        group_id: &str,
        name: Option<&str>,
        description: Option<&str>,
    ) -> String {
        let mut fields: Vec<String> = Vec::new();
        fields.push(format!(r#""id":"{}""#, group_id));
        name.m_foreach(|name| fields.push(format!(r#""name":"{}""#, name)));
        description
            .m_foreach(|description| fields.push(format!(r#""description":"{}""#, description)));
        let body = format!(r#"{{{}}}"#, fields.join(","));
        let data = http::put_resource(self, format!("/api/groups/{}", group_id).as_str(), body);
        self.output_json(data, json!({}))
    }

    pub fn create_api_key(
        &self,
        id: Option<&str>,
        secret: Option<&str>,
        group: &str,
        name: &str,
        not_enabled: bool,
        throttling_quota: Option<&str>,
        daily_quota: Option<&str>,
        monthly_quota: Option<&str>,
    ) -> String {
        let mut fields: Vec<String> = Vec::new();
        id.m_foreach(|id| fields.push(format!(r#""clientId":"{}""#, id)));
        secret.m_foreach(|secret| fields.push(format!(r#""clientSecret":"{}""#, secret)));
        fields.push(format!(r#""clientName":"{}""#, name));
        fields.push(format!(r#""authorizedGroup":"{}""#, group));
        fields.push(format!(r#""enabled":{}"#, !not_enabled));
        throttling_quota.m_foreach(|throttling_quota| {
            fields.push(format!(r#""throttlingQuota":{}"#, throttling_quota))
        });
        daily_quota
            .m_foreach(|daily_quota| fields.push(format!(r#""dailyQuota":{}"#, daily_quota)));
        monthly_quota
            .m_foreach(|monthly_quota| fields.push(format!(r#""monthlyQuota":{}"#, monthly_quota)));
        let body = format!(r#"{{{}}}"#, fields.join(","));
        let data = http::post_resource(
            self,
            format!("/api/groups/{}/apikeys", group).as_str(),
            body,
        );
        self.output_json(data, json!({}))
    }

    pub fn update_api_key(
        &self,
        id: &str,
        secret: Option<&str>,
        group: &str,
        name: Option<&str>,
        enabled: Option<&str>,
        throttling_quota: Option<&str>,
        daily_quota: Option<&str>,
        monthly_quota: Option<&str>,
    ) -> String {
        let mut updates: Vec<String> = Vec::new();
        secret.m_foreach(|secret| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/clientSecret", "value": "{}" }}"#,
                secret
            ))
        });
        name.m_foreach(|name| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/clientName", "value": "{}" }}"#,
                name
            ))
        });
        enabled.m_foreach(|enabled| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/enabled", "value": {} }}"#,
                enabled
            ))
        });
        throttling_quota.m_foreach(|throttling_quota| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/throttlingQuota", "value": {} }}"#,
                throttling_quota
            ))
        });
        daily_quota.m_foreach(|daily_quota| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/dailyQuota", "value": {} }}"#,
                daily_quota
            ))
        });
        monthly_quota.m_foreach(|monthly_quota| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/monthlyQuota", "value": {} }}"#,
                monthly_quota
            ))
        });
        let update_body = format!(r#"[{}]"#, updates.join(","));
        let data = http::patch_resource(
            self,
            format!("/api/groups/{}/apikeys/{}", group, id).as_str(),
            update_body,
        );
        self.output_json(data, json!({}))
    }

    pub fn create_service(
        &self,
        id: Option<&str>,
        group: Option<&str>,
        name: Option<&str>,
        env: Option<&str>,
        domain: Option<&str>,
        subdomain: Option<&str>,
        target: Vec<&str>,
        root: Option<&str>,
        matching_root: Option<&str>,
        not_enabled: bool,
        private_app: bool,
        no_force_https: bool,
        maintenance_mode: bool,
        build_mode: bool,
        enforce_secure_communication: bool,
        send_headers_back: bool,
        sec_com_excluded_patterns: Vec<&str>,
        public_pattern: Vec<&str>,
        private_pattern: Vec<&str>,
        additional_header: Vec<&str>,
        matching_header: Vec<&str>,
        whitelisted_url: Vec<&str>,
        blacklisted_url: Vec<&str>,
        healthcheck_enabled: bool,
        healthcheck_url: Option<&str>,
        no_client_circuit_breaker: bool,
        canary_enabled: Option<&str>,
        canary_traffic: Option<&str>,
        canary_target: Vec<&str>,
        canary_root: Option<&str>,
        client_retries: Option<&str>,
        client_max_errors: Option<&str>,
        client_retry_delay: Option<&str>,
        client_backoff_factor: Option<&str>,
        client_call_timeout: Option<&str>,
        client_global_timeout: Option<&str>,
        client_sample_interval: Option<&str>,
    ) -> String {
        let mut fields: Vec<String> = Vec::new();
        id.m_foreach(|id| fields.push(format!(r#""id":"{}""#, id)));
        group.m_foreach(|group| fields.push(format!(r#""groupId":"{}""#, group)));
        name.m_foreach(|name| fields.push(format!(r#""name":"{}""#, name)));
        env.m_foreach(|env| fields.push(format!(r#""env":"{}""#, env)));
        domain.m_foreach(|domain| fields.push(format!(r#""domain":"{}""#, domain)));
        subdomain.m_foreach(|subdomain| fields.push(format!(r#""subdomain":"{}""#, subdomain)));
        if !target.is_empty() {
            let values = target
                .iter()
                .map(|pattern| {
                    let parts: Vec<&str> = pattern.split("://").collect();
                    let scheme: String = parts[0].to_string();
                    let host: String = parts[1].to_string();
                    format!(r#"{{ "scheme":"{}", "host":"{}"}}"#, scheme, host)
                })
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            fields.push(format!(r#""targets":{}"#, array))
        }
        root.m_foreach(|root| fields.push(format!(r#""root":"{}""#, root)));
        matching_root.m_foreach(|matching_root| {
            fields.push(format!(r#""matching_root":"{}""#, matching_root))
        });
        fields.push(format!(r#""enabled":{}"#, !not_enabled));
        fields.push(format!(r#""privateApp":{}"#, private_app));
        fields.push(format!(r#""forceHttps":{}"#, !no_force_https));
        fields.push(format!(r#""maintenanceMode":{}"#, maintenance_mode));
        fields.push(format!(r#""buildMode":{}"#, build_mode));
        fields.push(format!(
            r#""enforceSecureCommunication":{}"#,
            enforce_secure_communication
        ));
        fields.push(format!(
            r#""sendOtoroshiHeadersBack":{}"#,
            send_headers_back
        ));
        if !sec_com_excluded_patterns.is_empty() {
            let values = sec_com_excluded_patterns
                .iter()
                .map(|pattern| format!(r#""{}""#, pattern))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            fields.push(format!(r#""secComExcludedPatterns": {}"#, array));
        }
        if !public_pattern.is_empty() {
            let values = public_pattern
                .iter()
                .map(|pattern| format!(r#""{}""#, pattern))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            fields.push(format!(r#""publicPatterns": {}"#, array));
        }
        if !private_pattern.is_empty() {
            let values = private_pattern
                .iter()
                .map(|pattern| format!(r#""{}""#, pattern))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            fields.push(format!(r#""privatePatterns":{}"#, array));
        }
        if !additional_header.is_empty() {
            let values = additional_header
                .iter()
                .map(|pattern| {
                    let parts: Vec<&str> = pattern.split(":").collect();
                    let key: String = parts[0].to_string();
                    let value: String = parts[1].to_string();
                    format!(r#""{}":"{}""#, key, value)
                })
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("{{{}}}", values);
            fields.push(format!(r#""additionalHeaders": {} }}"#, array));
        }
        if !matching_header.is_empty() {
            let values = matching_header
                .iter()
                .map(|pattern| {
                    let parts: Vec<&str> = pattern.split(":").collect();
                    let key: String = parts[0].to_string();
                    let value: String = parts[1].to_string();
                    format!(r#""{}":"{}""#, key, value)
                })
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("{{{}}}", values);
            fields.push(format!(r#""matchingHeaders": {}"#, array));
        }

        let mut client_config: Vec<String> = Vec::new();

        client_retries.m_foreach(|client_retries| {
            client_config.push(format!(r#""retries":{}"#, client_retries))
        });
        client_config.push(format!(
            r#""useCircuitBreaker":{}"#,
            !no_client_circuit_breaker
        ));
        client_max_errors.m_foreach(|client_max_errors| {
            client_config.push(format!(r#""maxErrors":{}"#, client_max_errors))
        });
        client_retry_delay.m_foreach(|client_retry_delay| {
            client_config.push(format!(r#""retryInitialDelay":{}"#, client_retry_delay))
        });
        client_backoff_factor.m_foreach(|client_backoff_factor| {
            client_config.push(format!(r#""backoffFactor":{}"#, client_backoff_factor))
        });
        client_call_timeout.m_foreach(|client_call_timeout| {
            client_config.push(format!(r#""callTimeout":{}"#, client_call_timeout))
        });
        client_global_timeout.m_foreach(|client_global_timeout| {
            client_config.push(format!(r#""globalTimeout":{}"#, client_global_timeout))
        });
        client_sample_interval.m_foreach(|client_sample_interval| {
            client_config.push(format!(r#""sampleInterval":{}"#, client_sample_interval))
        });

        fields.push(format!(
            r#""clientConfig": {{{}}}"#,
            client_config.join(",")
        ));

        let mut canary_config: Vec<String> = Vec::new();

        canary_enabled.m_foreach(|canary_enabled| {
            canary_config.push(format!(r#""enabled":{}"#, canary_enabled))
        });
        canary_traffic.m_foreach(|canary_traffic| {
            canary_config.push(format!(r#""traffic":{}"#, canary_traffic))
        });
        canary_root
            .m_foreach(|canary_root| canary_config.push(format!(r#""root":"{}""#, canary_root)));

        if !canary_target.is_empty() {
            let values = canary_target
                .iter()
                .map(|pattern| {
                    let parts: Vec<&str> = pattern.split("://").collect();
                    let scheme: String = parts[0].to_string();
                    let host: String = parts[1].to_string();
                    format!(r#"{{ "scheme":"{}", "host":"{}"}}"#, scheme, host)
                })
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            canary_config.push(format!(r#""targets":{}"#, array))
        }

        fields.push(format!(r#""canary": {{{}}}"#, canary_config.join(",")));

        let mut filtering_config: Vec<String> = Vec::new();

        if !whitelisted_url.is_empty() {
            let values = whitelisted_url
                .iter()
                .map(|pattern| format!(r#""{}""#, pattern))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            filtering_config.push(format!(r#""whitelist":{}"#, array))
        }

        if !blacklisted_url.is_empty() {
            let values = blacklisted_url
                .iter()
                .map(|pattern| format!(r#""{}""#, pattern))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            filtering_config.push(format!(r#""blacklist":{}"#, array))
        }

        fields.push(format!(
            r#""ipFiltering": {{{}}}"#,
            filtering_config.join(",")
        ));

        if healthcheck_enabled && healthcheck_url.m_is_defined() {
            fields.push(format!(
                r#""healthCheck": {{ "enabled":{}, "url":"{}" }}"#,
                healthcheck_enabled,
                healthcheck_url.m_get()
            ));
        }
        let body = format!(r#"{{{}}}"#, fields.join(","));
        // println!("{}", body);
        let data = http::post_resource(self, "/api/services", body);
        self.output_json(data, json!({}))
    }

    pub fn update_service(
        &self,
        id: &str,
        group: Option<&str>,
        name: Option<&str>,
        env: Option<&str>,
        domain: Option<&str>,
        subdomain: Option<&str>,
        target: Vec<&str>,
        root: Option<&str>,
        matching_root: Option<&str>,
        enabled: Option<&str>,
        private_app: Option<&str>,
        force_https: Option<&str>,
        maintenance_mode: Option<&str>,
        build_mode: Option<&str>,
        enforce_secure_communication: Option<&str>,
        send_headers_back: Option<&str>,
        sec_com_excluded_patterns: Vec<&str>,
        public_pattern: Vec<&str>,
        private_pattern: Vec<&str>,
        additional_header: Vec<&str>,
        matching_header: Vec<&str>,
        whitelisted_url: Vec<&str>,
        blacklisted_url: Vec<&str>,
        healthcheck_enabled: Option<&str>,
        healthcheck_url: Option<&str>,
        canary_enabled: Option<&str>,
        canary_traffic: Option<&str>,
        canary_target: Vec<&str>,
        canary_root: Option<&str>,
        client_circuit_breaker: Option<&str>,
        client_retries: Option<&str>,
        client_max_errors: Option<&str>,
        client_retry_delay: Option<&str>,
        client_backoff_factor: Option<&str>,
        client_call_timeout: Option<&str>,
        client_global_timeout: Option<&str>,
        client_sample_interval: Option<&str>,
    ) -> String {
        let mut updates: Vec<String> = Vec::new();
        group.m_foreach(|group| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/groupId", "value": "{}" }}"#,
                group
            ))
        });
        name.m_foreach(|name| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/name", "value": "{}" }}"#,
                name
            ))
        });
        env.m_foreach(|env| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/env", "value": "{}" }}"#,
                env
            ))
        });
        domain.m_foreach(|domain| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/domain", "value": "{}" }}"#,
                domain
            ))
        });
        subdomain.m_foreach(|subdomain| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/subdomain", "value": "{}" }}"#,
                subdomain
            ))
        });
        if !target.is_empty() {
            let values = target
                .iter()
                .map(|pattern| {
                    let parts: Vec<&str> = pattern.split("://").collect();
                    let scheme: String = parts[0].to_string();
                    let host: String = parts[1].to_string();
                    format!(r#"{{ "scheme":"{}", "host":"{}"}}""#, scheme, host)
                })
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/targets", "value": {} }}"#,
                array
            ));
        }
        root.m_foreach(|root| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/root", "value": "{}" }}"#,
                root
            ))
        });
        matching_root.m_foreach(|matching_root| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/matching_root", "value": "{}" }}"#,
                matching_root
            ))
        });
        enabled.m_foreach(|enabled| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/enabled", "value": {} }}"#,
                enabled
            ))
        });
        private_app.m_foreach(|private_app| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/privateApp", "value": {} }}"#,
                private_app
            ))
        });
        force_https.m_foreach(|force_https| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/forceHttps", "value": {} }}"#,
                force_https
            ))
        });
        maintenance_mode.m_foreach(|maintenance_mode| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/maintenanceMode", "value": {} }}"#,
                maintenance_mode
            ))
        });
        build_mode.m_foreach(|build_mode| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/buildMode", "value": {} }}"#,
                build_mode
            ))
        });
        enforce_secure_communication.m_foreach(|enforce_secure_communication| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/enforceSecureCommunication", "value": {} }}"#,
                enforce_secure_communication
            ))
        });
        send_headers_back.m_foreach(|send_headers_back| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/sendOtoroshiHeadersBack", "value": {} }}"#,
                send_headers_back
            ))
        });
        if !sec_com_excluded_patterns.is_empty() {
            let values = sec_com_excluded_patterns
                .iter()
                .map(|pattern| format!(r#""{}""#, pattern))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/secComExcludedPatterns", "value": {} }}"#,
                array
            ));
        }
        if !public_pattern.is_empty() {
            let values = public_pattern
                .iter()
                .map(|pattern| format!(r#""{}""#, pattern))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/publicPatterns", "value": {} }}"#,
                array
            ));
        }
        if !private_pattern.is_empty() {
            let values = private_pattern
                .iter()
                .map(|pattern| format!(r#""{}""#, pattern))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/privatePatterns", "value": {} }}"#,
                array
            ));
        }
        if !additional_header.is_empty() {
            let values = additional_header
                .iter()
                .map(|pattern| {
                    let parts: Vec<&str> = pattern.split(":").collect();
                    let key: String = parts[0].to_string();
                    let value: String = parts[1].to_string();
                    format!(r#""{}":"{}""#, key, value)
                })
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("{{{}}}", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/additionalHeaders", "value": {} }}"#,
                array
            ));
        }
        if !matching_header.is_empty() {
            let values = matching_header
                .iter()
                .map(|pattern| {
                    let parts: Vec<&str> = pattern.split(":").collect();
                    let key: String = parts[0].to_string();
                    let value: String = parts[1].to_string();
                    format!(r#""{}":"{}""#, key, value)
                })
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("{{{}}}", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/matchingHeaders", "value": {} }}"#,
                array
            ));
        }
        if !whitelisted_url.is_empty() {
            let values = whitelisted_url
                .iter()
                .map(|pattern| format!(r#""{}""#, pattern))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/ipFiltering/whitelist", "value": {} }}"#,
                array
            ));
        }
        if !blacklisted_url.is_empty() {
            let values = blacklisted_url
                .iter()
                .map(|pattern| format!(r#""{}""#, pattern))
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/ipFiltering/blacklist", "value": {} }}"#,
                array
            ));
        }
        healthcheck_enabled.m_foreach(|healthcheck_enabled| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/healthCheck/enabled", "value": {} }}"#,
                healthcheck_enabled
            ))
        });
        healthcheck_url.m_foreach(|healthcheck_url| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/healthCheck/url", "value": "{}" }}"#,
                healthcheck_url
            ))
        });

        canary_enabled.m_foreach(|canary_enabled| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/canary/enabled", "value": {} }}"#,
                canary_enabled
            ))
        });
        canary_traffic.m_foreach(|canary_traffic| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/canary/traffic", "value": {} }}"#,
                canary_traffic
            ))
        });
        canary_root.m_foreach(|canary_root| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/canary/root", "value": "{}" }}"#,
                canary_root
            ))
        });
        if !canary_target.is_empty() {
            let values = canary_target
                .iter()
                .map(|pattern| {
                    let parts: Vec<&str> = pattern.split("://").collect();
                    let scheme: String = parts[0].to_string();
                    let host: String = parts[1].to_string();
                    format!(r#"{{ "scheme":"{}", "host":"{}"}}""#, scheme, host)
                })
                .collect::<Vec<_>>()
                .join(",");
            let array = format!("[{}]", values);
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/canary/targets", "value": {} }}"#,
                array
            ));
        }

        client_circuit_breaker.m_foreach(|client_circuit_breaker| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/clientConfig/useCircuitBreaker", "value": {} }}"#,
                client_circuit_breaker
            ))
        });
        client_retries.m_foreach(|client_retries| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/clientConfig/retries", "value": {} }}"#,
                client_retries
            ))
        });
        client_max_errors.m_foreach(|client_max_errors| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/clientConfig/maxErrors", "value": {} }}"#,
                client_max_errors
            ))
        });
        client_retry_delay.m_foreach(|client_retry_delay| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/clientConfig/retryInitialDelay", "value": {} }}"#,
                client_retry_delay
            ))
        });
        client_backoff_factor.m_foreach(|client_backoff_factor| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/clientConfig/backoffFactor", "value": {} }}"#,
                client_backoff_factor
            ))
        });
        client_call_timeout.m_foreach(|client_call_timeout| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/clientConfig/callTimeout", "value": {} }}"#,
                client_call_timeout
            ))
        });
        client_global_timeout.m_foreach(|client_global_timeout| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/clientConfig/globalTimeout", "value": {} }}"#,
                client_global_timeout
            ))
        });
        client_sample_interval.m_foreach(|client_sample_interval| {
            updates.push(format!(
                r#"{{ "op": "replace", "path": "/clientConfig/sampleInterval", "value": {} }}"#,
                client_sample_interval
            ))
        });
        let update_body = format!(r#"[{}]"#, updates.join(","));
        let data =
            http::patch_resource(self, format!("/api/services/{}", id).as_str(), update_body);
        self.output_json(data, json!({}))
    }

    fn count_in_array(&self, data: String) -> String {
        let json: Value = serde_json::from_str(data.as_str()).unwrap();
        let len = json.as_array().unwrap().len();
        format!(r#"{{"count":{}}}"#, len)
    }
}
