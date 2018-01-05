extern crate reqwest;

use std::io::Read;
use client::OtoroshiClient;
use reqwest::{Client, RequestBuilder};
use reqwest::header::Headers;
use opt::OptionExtension;
use json_color::Colorizer;

header! { (Host, "Host") => [String] }
header! { (UserAgent, "User-Agent") => [String] }
header! { (Accept, "Accept") => [String] }
header! { (ContentType, "Content-Type") => [String] }
header! { (OpunClientId, "Opun-Client-Id") => [String] }
header! { (OtoroshiClientId, "Otoroshi-Client-Id") => [String] }
header! { (OpunClientSecret, "Opun-Client-Secret") => [String] }
header! { (OtoroshiClientSecret, "Otoroshi-Client-Secret") => [String] }

pub fn fetch_data(oto_cli: &OtoroshiClient, path: &str) -> String {
    let url = format!("{}{}", oto_cli.url, path);

    let mut res = oto_cli
        .client
        .get(url.as_str())
        .unwrap()
        .header(UserAgent("Otoroshi-CLI".to_string()))
        .header(Host(oto_cli.host.to_string()))
        .header(OpunClientId(oto_cli.client_id.to_string()))
        .header(OtoroshiClientId(oto_cli.client_id.to_string()))
        .header(OpunClientSecret(oto_cli.client_secret.to_string()))
        .header(OtoroshiClientSecret(oto_cli.client_secret.to_string()))
        .send()
        .unwrap();

    if oto_cli.debug {
        println!("[DEBUG] HTTP status: {}", res.status().as_u16());
    }
    let mut content = String::new();
    res.read_to_string(&mut content);
    content
}

pub fn delete_resource(oto_cli: &OtoroshiClient, path: &str) -> String {
    let url = format!("{}{}", oto_cli.url, path);

    let mut res = oto_cli
        .client
        .delete(url.as_str())
        .unwrap()
        .header(UserAgent("Otoroshi-CLI".to_string()))
        .header(Host(oto_cli.host.to_string()))
        .header(OpunClientId(oto_cli.client_id.to_string()))
        .header(OtoroshiClientId(oto_cli.client_id.to_string()))
        .header(OpunClientSecret(oto_cli.client_secret.to_string()))
        .header(OtoroshiClientSecret(oto_cli.client_secret.to_string()))
        .send()
        .unwrap();

    if oto_cli.debug {
        println!("HTTP status: {}", res.status().as_u16());
    }
    let mut content = String::new();
    res.read_to_string(&mut content);
    content
}

pub fn delete_resource_with_body(oto_cli: &OtoroshiClient, path: &str, body: String) -> String {
    let url = format!("{}{}", oto_cli.url, path);

    let mut res = oto_cli
        .client
        .delete(url.as_str())
        .unwrap()
        .header(ContentType("application/json".to_string()))
        .header(UserAgent("Otoroshi-CLI".to_string()))
        .header(Host(oto_cli.host.to_string()))
        .header(OpunClientId(oto_cli.client_id.to_string()))
        .header(OtoroshiClientId(oto_cli.client_id.to_string()))
        .header(OpunClientSecret(oto_cli.client_secret.to_string()))
        .header(OtoroshiClientSecret(oto_cli.client_secret.to_string()))
        .body(body)
        .send()
        .unwrap();

    if oto_cli.debug {
        println!("[DEBUG] HTTP status: {}", res.status().as_u16());
    }
    let mut content = String::new();
    res.read_to_string(&mut content);
    content
}

pub fn post_resource(oto_cli: &OtoroshiClient, path: &str, body: String) -> String {
    let url = format!("{}{}", oto_cli.url, path);

    let mut res = oto_cli
        .client
        .post(url.as_str())
        .unwrap()
        .header(ContentType("application/json".to_string()))
        .header(UserAgent("Otoroshi-CLI".to_string()))
        .header(Host(oto_cli.host.to_string()))
        .header(OpunClientId(oto_cli.client_id.to_string()))
        .header(OtoroshiClientId(oto_cli.client_id.to_string()))
        .header(OpunClientSecret(oto_cli.client_secret.to_string()))
        .header(OtoroshiClientSecret(oto_cli.client_secret.to_string()))
        .body(body)
        .send()
        .unwrap();

    if oto_cli.debug {
        println!("[DEBUG] HTTP status: {}", res.status().as_u16());
    }
    let mut content = String::new();
    res.read_to_string(&mut content);
    content
}

pub fn put_resource(oto_cli: &OtoroshiClient, path: &str, body: String) -> String {
    let url = format!("{}{}", oto_cli.url, path);

    let mut res = oto_cli
        .client
        .put(url.as_str())
        .unwrap()
        .header(ContentType("application/json".to_string()))
        .header(UserAgent("Otoroshi-CLI".to_string()))
        .header(Host(oto_cli.host.to_string()))
        .header(OpunClientId(oto_cli.client_id.to_string()))
        .header(OtoroshiClientId(oto_cli.client_id.to_string()))
        .header(OpunClientSecret(oto_cli.client_secret.to_string()))
        .header(OtoroshiClientSecret(oto_cli.client_secret.to_string()))
        .body(body)
        .send()
        .unwrap();

    if oto_cli.debug {
        println!("[DEBUG] HTTP status: {}", res.status().as_u16());
    }
    let mut content = String::new();
    res.read_to_string(&mut content);
    content
}

pub fn patch_resource(oto_cli: &OtoroshiClient, path: &str, body: String) -> String {
    let url = format!("{}{}", oto_cli.url, path);

    let mut res = oto_cli
        .client
        .patch(url.as_str())
        .unwrap()
        .header(ContentType("application/json".to_string()))
        .header(UserAgent("Otoroshi-CLI".to_string()))
        .header(Host(oto_cli.host.to_string()))
        .header(OpunClientId(oto_cli.client_id.to_string()))
        .header(OtoroshiClientId(oto_cli.client_id.to_string()))
        .header(OpunClientSecret(oto_cli.client_secret.to_string()))
        .header(OtoroshiClientSecret(oto_cli.client_secret.to_string()))
        .body(body)
        .send()
        .unwrap();

    if oto_cli.debug {
        println!("[DEBUG] HTTP status: {}", res.status().as_u16());
    }
    let mut content = String::new();
    res.read_to_string(&mut content);
    content
}

pub fn curl_call(url: &str, method: &str, header_values: Vec<&str>, body: Option<&str>) -> String {
    let mut cli: RequestBuilder = match method {
        "GET" => Client::new().unwrap().get(url).unwrap(),
        "POST" => Client::new().unwrap().post(url).unwrap(),
        "PUT" => Client::new().unwrap().put(url).unwrap(),
        "DELETE" => Client::new().unwrap().delete(url).unwrap(),
        "PATCH" => Client::new().unwrap().patch(url).unwrap(),
        "HEAD" => Client::new().unwrap().head(url).unwrap(),
        _ => Client::new().unwrap().get(url).unwrap(),
    };
    let mut headers = Headers::new();
    header_values
        .iter()
        .map(|raw_header| {
            let header = raw_header.to_string();
            let parts: Vec<&str> = header.split(":").collect();
            let header_name: &str = parts[0];
            let header_value: &str = parts[1];
            if header_name.trim().eq("Content-Type") {
                headers.set(ContentType(header_value.to_string()));
            }
            if header_name.trim().eq("Accept") {
                headers.set(Accept(header_value.to_string()));
            }
            if header_name.trim().eq("Host") {
                headers.set(Host(header_value.to_string()));
            }
            if header_name.trim().eq("User-Agent") {
                headers.set(UserAgent(header_value.to_string()));
            }
            if header_name.trim().eq("Opun-Client-Id") {
                headers.set(OpunClientId(header_value.to_string()));
            }
            if header_name.trim().eq("Opun-Client-Secret") {
                headers.set(OpunClientSecret(header_value.to_string()));
            }
            ()
        })
        .collect::<Vec<_>>();
    if header_values.is_empty() {
        headers.set(Accept("application/json".to_string()));
    }
    cli.headers(headers);
    body.m_foreach(|body| cli.body(body.to_string()));
    let mut res = cli.send().unwrap();
    let mut content = String::new();
    res.read_to_string(&mut content);
    let json = ContentType("application/json".to_string());
    if let Some(ctype) = res.headers().get::<ContentType>() {
        if ctype.eq(&json) {
            let colorizer = Colorizer::arbitrary();
            let json_colorized_str = colorizer.colorize_json_str(content.as_ref()).unwrap();
            println!("{}", json_colorized_str);
        } else {
            println!("{}", content);
        }
    } else {
        println!("{}", content);
    }
    content
}
