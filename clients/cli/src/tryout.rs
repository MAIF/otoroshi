extern crate futures;
extern crate hyper;
extern crate rand;

use hyper::mime;
use hyper::header::ContentType;
use hyper::server::{Http, Request, Response, Service};

use tryout::futures::future::Future;
use tryout::rand::thread_rng;
use tryout::rand::Rng;

struct HelloFrom {
    name: String,
}

impl Service for HelloFrom {
    type Request = Request;
    type Response = Response;
    type Error = hyper::Error;
    type Future = Box<Future<Item = Self::Response, Error = Self::Error>>;

    fn call(&self, req: Request) -> Self::Future {
        Box::new(futures::future::ok(
            Response::new()
                .with_header(ContentType(mime::APPLICATION_JSON))
                .with_body(format!(r#"{{"messge":"👋 , I am {} !"}}"#, self.name)),
        ))
    }
}

pub fn server_tryout_service(port: &str) {
    let names: Vec<&str> = vec![
        "Sherwood Stokes",
        "Dawn Jacobs",
        "Claire Wiegand",
        "Audreanne Dooley",
        "Jessica Ratke",
        "Frederick Kessler",
        "Jenifer Towne Jr.",
        "Ethyl Kuhn",
        "Richie McLaughlin IV",
        "Douglas Hermiston",
        "Charlene Murray",
        "Jamey Lubowitz",
        "Alanis Reichert",
        "Pete Fisher",
        "Rachael Rogahn",
        "Jaiden Von",
        "Dr. Odessa Johnson",
        "Viviane Welch",
        "Paula Rippin MD",
        "Hoyt Ruecker",
        "Miss Abigayle Spinka",
        "Miss Pinkie Rowe",
        "Clyde Mayert",
        "Kimberly Parisian",
        "Jed Bergstrom MD",
        "Dino Wintheiser",
        "Deontae Lind",
        "Adolph Hauck",
        "Torrance Hills",
        "Emmalee Keebler DVM",
        "Luz Sporer",
        "Gage Breitenberg",
        "Mrs. Name Deckow",
        "Martine Connelly",
        "Julius Ziemann V",
        "Gonzalo Spinka",
        "Leta Sporer",
        "Julianne Rodriguez",
        "Tyra Tillman",
        "Mariah Weimann",
        "Mr. Paula Zemlak",
        "Ahmad Bartell",
        "Tyshawn Heller",
        "Felton Kessler",
        "Sedrick Dach",
        "Soledad Stokes",
        "Edythe Wehner",
        "Pearline Ziemann",
        "Kip Yost",
        "Alexandro Koch",
        "Mr. Abbie Feeney",
        "Joan Romaguera",
        "Frances Roberts",
        "Logan Bartoletti",
        "Loma Vandervort",
        "Josephine Von",
        "Jaquelin Stark III",
        "Columbus Kris MD",
        "Isaiah Wilkinson",
        "Dr. Meggie Steuber",
        "Tyra Greenfelder",
        "Domenica Smith",
        "Layne Hilll",
        "Leonora Bogisich DDS",
        "Flo Pacocha",
    ];
    let addr = format!("0.0.0.0:{}", port).parse().unwrap();
    let mut rng = thread_rng();
    let name: &str = rng.choose(&names).unwrap();
    println!("Will respond with name: {}", name);
    let server = Http::new()
        .bind(&addr, move || {
            Ok(HelloFrom {
                name: name.to_string(),
            })
        })
        .unwrap();
    server.run().unwrap();
}
