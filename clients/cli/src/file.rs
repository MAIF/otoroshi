extern crate toml;

use std::io::Result;
use std::fs::File;
use std::io::{Error, ErrorKind, Read};
use toml::Value as ToMlValue;

pub fn load_file(path: &str) -> Result<String> {
    let mut f = try!(File::open(path));
    let mut data = String::new();
    try!(f.read_to_string(&mut data));
    Ok(data)
}

pub fn load_from_path(path: &str) -> Result<ToMlValue> {
    let data = try!(load_file(path));
    match toml::from_str(&data) {
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
