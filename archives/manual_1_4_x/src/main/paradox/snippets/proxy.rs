extern crate futures;
extern crate tokio;
extern crate rand;

use futures::{Future, Stream};
use rand::Rng;
use std::net::SocketAddr;
use tokio::io::copy;
use tokio::net::{TcpListener, TcpStream};
use tokio::prelude::*;

fn main() -> Result<(), Box<std::error::Error>> {
    let urls: Vec<std::net::SocketAddr> = vec![
        std::net::SocketAddr::new("192.168.1.40".parse().unwrap(), 8080),
        std::net::SocketAddr::new("192.168.1.41".parse().unwrap(), 8080),
        std::net::SocketAddr::new("192.168.1.42".parse().unwrap(), 8080),
    ];
    let addr: SocketAddr = "0.0.0.0:80".to_string().parse().unwrap();
    let sock = TcpListener::bind(&addr).unwrap();
    println!("TCP load balancer listening on {}", addr);
    let done = sock
        .incoming()
        .map_err(move |e| println!("Error accepting socket; error = {:?}", e))
        .for_each(move |server_socket| {
            let index = rand::thread_rng().gen_range(0, urls.len());
            let url = &urls[index];
            let client_pair = TcpStream::connect(&url).map(|socket| socket.split());
            let msg = client_pair
                .and_then(move |(client_reader, client_writer)| {
                    let (server_reader, server_writer) = server_socket.split();
                    let upload = copy(server_reader, client_writer);
                    let download = copy(client_reader, server_writer);
                    upload.join(download)
                }).then(|_res| {
                    Ok(())
                });
            tokio::spawn(msg);
            Ok(())
        });
    tokio::run(done);
    Ok(())
}