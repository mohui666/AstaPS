use std::{net::Ipv4Addr, sync::OnceLock};

pub(super) struct Endpoint {
    pub base: String,
    pub host_w: Vec<u16>,
    pub port: u16,
}

impl Endpoint {
    fn parse(raw: &str) -> Result<Self, String> {
        let authority = raw.trim_end_matches('/').strip_prefix("http://")
            .ok_or("Server URL must use http://IPv4:port")?;
        let (host, port) = authority.split_once(':').ok_or("Server URL needs a port")?;
        let host: Ipv4Addr = host.parse().map_err(|_| "Server URL needs a literal IPv4 address")?;
        let port: u16 = port.parse().map_err(|_| "Invalid server port")?;
        if port == 0 || host.is_unspecified() || host.is_multicast() || host.is_broadcast() {
            return Err("Invalid server address".into());
        }
        Ok(Self {
            base: format!("http://{host}:{port}"),
            host_w: host.to_string().encode_utf16().chain(Some(0)).collect(),
            port,
        })
    }

    fn load() -> Result<Self, String> {
        if let Ok(url) = std::env::var("ASTAPS_SERVER_URL") {
            return Self::parse(&url);
        }
        let path = std::env::current_exe().map_err(|e| e.to_string())?
            .with_file_name("astaps-client.json");
        if path.exists() {
            let value: serde_json::Value = serde_json::from_slice(
                &std::fs::read(path).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
            return Self::parse(value["serverUrl"].as_str().ok_or("Missing serverUrl")?);
        }
        Self::parse("http://127.0.0.1:8088")
    }
}

pub(super) fn server() -> &'static Endpoint {
    static SERVER: OnceLock<Endpoint> = OnceLock::new();
    SERVER.get_or_init(|| Endpoint::load().expect("Invalid AstaPS client connection settings"))
}

#[cfg(test)]
mod tests {
    use super::Endpoint;

    #[test]
    fn lan_url_has_a_stable_native_host_and_port() {
        let server = Endpoint::parse("http://192.0.2.10:8088/").unwrap();
        assert_eq!(server.base, "http://192.0.2.10:8088");
        assert_eq!(server.port, 8088);
        assert_eq!(String::from_utf16(&server.host_w[..server.host_w.len()-1]).unwrap(), "192.0.2.10");
        assert_eq!(server.host_w.last(), Some(&0));
    }

    #[test]
    fn credentials_paths_and_invalid_addresses_are_rejected() {
        for url in ["https://192.0.2.10:8088", "http://user@192.0.2.10:8088",
                    "http://192.0.2.10:8088/path", "http://0.0.0.0:8088",
                    "http://192.0.2.10:0", "http://192.0.2.10:65536"] {
            assert!(Endpoint::parse(url).is_err(), "{url}");
        }
    }
}
