//! Local checkout product availability. Does not alter login or account verification.

use std::collections::HashSet;
use lazy_static::lazy_static;

lazy_static! {
    static ref PRODUCTS: HashSet<String> = serde_json::from_str(include_str!("local_recharge_products.json"))
        .expect("local recharge product IDs must be valid JSON");
}

pub(super) fn enabled() -> bool {
    std::env::var("LUNAGC_LOCAL_RECHARGE").is_ok_and(|value| value == "1")
        || std::env::temp_dir().join("astaps-local-recharge.txt").exists()
        || std::env::current_exe().ok().and_then(|path|
            std::fs::read(path.with_file_name("astaps-client.json")).ok())
            .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
            .is_some_and(|config| config["localRecharge"].as_bool() == Some(true))
}

pub(super) fn product(id: &str) -> Option<&'static str> {
    PRODUCTS.get(id).map(String::as_str)
}

#[cfg(test)]
mod tests {
    use super::product;

    #[test]
    fn only_exact_supported_product_ids_are_available() {
        assert_eq!(product("ys_chn_blessofmoon_tier5"),Some("ys_chn_blessofmoon_tier5"));
        assert_eq!(product("201"),Some("201"));
        assert_eq!(product("1"),Some("1"));
        assert!(product("not-a-product").is_none());
        assert!(product("ys_chn_blessofmoon_tier5\nother").is_none());
    }
}
