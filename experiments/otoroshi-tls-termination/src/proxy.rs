use tokio::io;
use tokio::net::{TcpListener, TcpStream};
use futures::FutureExt;
use std::error::Error;
use tokio::io::{split, AsyncWriteExt};
use socket2::{Socket, Domain, Type};

use crate::opts::AppConfig;
use crate::tls::Tls;

// TODO: find a way to pass client cert to oto
async fn transfer(inbound: tokio_rustls::server::TlsStream<TcpStream>, proxy_addr: String) -> Result<(), Box<dyn Error>> {
    let mut outbound = TcpStream::connect(proxy_addr).await?; // TODO: reuse connection ?
    let (mut ri, mut wi) = split(inbound);
    let (mut ro, mut wo) = outbound.split();
    let client_to_server = async {
        io::copy(&mut ri, &mut wo).await?;
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
                        let transfer = transfer(stream, server_addr.clone()).map(move |r| {
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
        info!("creating proxy worker '{}'", name);
    }
    tls_proxy(name, tls, app_config).await
  }
}
