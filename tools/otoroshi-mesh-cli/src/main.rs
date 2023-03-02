use clap::Parser;
use hmac::{Hmac, Mac};
use jwt::{AlgorithmType, SignWithKey, Header, Token};
use sha2::Sha512;
use std::time::SystemTime;
use serde::{Serialize, Deserialize};

#[derive(Default, Deserialize, Serialize)]
struct AccessTokenClaims {
    client_id: String,
    iat: u64,
    nbf: u64,
    exp: u64
}

#[derive(Parser)] 
#[command(name = "oto-mesh")]
#[command(bin_name = "oto-mesh")]
enum OtoMeshCli {
    AccessToken(AccessTokenCommand),
}

#[derive(clap::Args)]
#[command(author, version, about = "Generate an access token for control plane synchronization", long_about = None)]
struct AccessTokenCommand {
    #[arg(long)]
    client_id: String,
    #[arg(long)]
    client_secret: String,
}

impl AccessTokenCommand {
    fn run(&self) {
        let now = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_secs();
        let exp = now + (3 * 30 * 24 * 60 * 60); // TODO: from config
        let key: Hmac<Sha512> = Hmac::new_from_slice(self.client_secret.as_bytes()).unwrap();
        let header = Header {
            algorithm: AlgorithmType::Hs512,
            ..Default::default()
        };
        let claims = AccessTokenClaims {
            client_id: self.client_id.clone(),
            iat: now,
            nbf: now,
            exp: exp,
        };
        let token = Token::new(header, claims).sign_with_key(&key).unwrap();
        println!("{}", token.as_str())
    }
}

fn main() {
    match OtoMeshCli::parse() {
      OtoMeshCli::AccessToken(command) => command.run(), 
    }
}

