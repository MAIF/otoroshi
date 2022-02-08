use tokio::io;
use tokio::net::{TcpListener, TcpStream};
use futures::FutureExt;
use std::error::Error;
use tokio::io::{split, AsyncWriteExt};
use socket2::{Socket, Domain, Type};
use tokio_rustls::rustls::Certificate;
use base64;

use crate::opts::AppConfig;
use crate::tls::Tls;

async fn transfer(inbound: tokio_rustls::server::TlsStream<TcpStream>, proxy_addr: String, mtls: bool, whole_chain: bool) -> Result<(), Box<dyn Error>> {
    let mut outbound = TcpStream::connect(proxy_addr).await?; // TODO: reuse connection ?
    let (_, server_conn) = inbound.get_ref();
    let certs_opt: Option<Vec<Certificate>> = server_conn.peer_certificates().map(|c| c.to_vec());
    let (mut ri, mut wi) = split(inbound);
    let (mut ro, mut wo) = outbound.split();
    let client_to_server = async {
        if mtls && certs_opt.is_some() {
            let certs = certs_opt.unwrap();
            let encoded_certs: Vec<String> = certs.iter().map(|c| base64::encode(c)).collect();
            if whole_chain {
                let encoded_certs_str = encoded_certs.join("|");
                crate::io::copy(&mut ri, &mut wo, &encoded_certs_str[..]).await?;
            } else {
                let encoded_certs_str = encoded_certs.get(0).unwrap();
                crate::io::copy(&mut ri, &mut wo, &encoded_certs_str[..]).await?;
            }
        } else {
            io::copy(&mut ri, &mut wo).await?;
        }
        wo.shutdown().await
    };
    let server_to_client = async {
        io::copy(&mut ro, &mut wi).await?;
        wi.shutdown().await
    };
    tokio::try_join!(client_to_server, server_to_client)?;
    Ok(())
}

async fn tls_proxy(name: String, tls: &Tls, app_config: AppConfig) -> Result<(), Box<dyn Error>> {

    let listen_addr = app_config.listen_addr;
    let server_addr = app_config.server_addr;
    let socket = Socket::new(Domain::IPV4, Type::STREAM, None).unwrap();
    let address: std::net::SocketAddr = listen_addr.parse().unwrap();
    let add = address.into();
    socket.set_reuse_address(true).unwrap();
    socket.set_reuse_port(true).unwrap();
    socket.set_nonblocking(true).unwrap();
    socket.bind(&add).unwrap();
    socket.listen(1024).unwrap();
    let std_listener: std::net::TcpListener = socket.into();

    let listener = TcpListener::from_std(std_listener).unwrap();
    loop {
        match listener.accept().await {
            Err(err) => error!("f[{}] ailed to accept socket; error = {:?}", name, err),
            Ok((inbound, _)) => {
                let acceptor = tls.current_acceptor();
                match acceptor.accept(inbound).await {
                    Err(err) => error!("[{}] failed to accept tls handshake; error = {:?}", name, err),
                    Ok(stream) => {
                        trace!("[{}] handling request", name);
                        let named = name.clone();
                        let transfer = transfer(stream, server_addr.clone(), app_config.mtls, app_config.whole_chain).map(move |r| {
                            if let Err(e) = r {
                                error!("[{}] failed to transfer; error= {:?}", named, e);
                            }
                        });
                        tokio::spawn(transfer);
                    }
                };                
            }
        };
    }
}

pub struct Proxy {}

impl Proxy { 
  pub async fn create(name: String, tls: &Tls, app_config: AppConfig) -> Result<(), Box<dyn Error>> {
    if cfg!(debug_assertions) {
        debug!("creating proxy worker '{}'", name);
    }
    tls_proxy(name, tls, app_config).await
  }
}
