use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug)]
pub struct Config {
    server: Server,
}

#[derive(Serialize, Deserialize, Debug)]
struct Server {
    port: i64,
    tls: bool,
    cert_file: String,
    key_file: String,
}

impl Config {
    pub fn get_addr(&self) -> String {
        "0.0.0.0:".to_string() + &*self.server.port.to_string()
    }

    pub fn get_cert_file(&self) -> String {
        self.server.cert_file.clone()
    }

    pub fn get_key_file(&self) -> String {
        self.server.key_file.clone()
    }

    pub fn with_tls(&self) -> bool {
        self.server.tls
    }
}
