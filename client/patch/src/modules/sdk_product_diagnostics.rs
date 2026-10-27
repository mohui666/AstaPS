//! Bounded diagnostics for public monthly-card and battle-pass SKUs only.
//! Account payloads, arbitrary identifiers and display strings are never logged.

use serde_json::Value;

const PUBLIC_PRODUCTS: &[&str] = &[
    "cloudys_chn_blessofmoon_tier5",
    "cloudys_chn_bp_extra_tier20",
    "cloudys_chn_bp_normal_tier10",
    "cloudys_chn_bp_upgrade_tier12",
    "cloudysglbblessofmoontier5",
    "ys_chn_blessofmoon_tier5",
    "ys_chn_bp_extra_tier20",
    "ys_chn_bp_normal_tier10",
    "ys_chn_bp_upgrade_tier12",
    "ys_glb_blessofmoon_tier5",
    "ys_glb_bp_extra_tier20",
    "ys_glb_bp_normal_tier10",
    "ys_glb_bp_upgrade_tier12",
];

pub(super) fn public_product(id: &str) -> Option<&'static str> {
    PUBLIC_PRODUCTS.iter().copied().find(|known| *known == id)
}

pub(super) fn public_price_query(id: &str) -> Option<&'static str> {
    public_product(id).or_else(|| match id {
        // Recognize a tier accidentally used as a SKU without copying arbitrary input.
        "Tier_5" => Some("Tier_5"),
        "Tier_10" => Some("Tier_10"),
        "Tier_12" => Some("Tier_12"),
        "Tier_20" => Some("Tier_20"),
        _ => None,
    })
}

pub(super) fn response_summary(response: &str) -> Option<String> {
    if response.len() > 65_536 { return None; }
    let value: Value = serde_json::from_str(response).ok()?;
    let code = value.get("retcode").and_then(Value::as_i64);
    let mut products = Vec::new();
    let mut count = 0;
    collect_products(&value, 0, &mut count, &mut products);
    products.sort();
    products.dedup();
    Some(format!("retcode={code:?}, products={count}, card/pass=[{}]", products.join("; ")))
}

fn collect_products(value: &Value, depth: usize, count: &mut usize, products: &mut Vec<String>) {
    if depth > 12 { return; }
    match value {
        Value::Object(object) => {
            if let Some(id) = object.get("productIdentifier").and_then(Value::as_str) {
                *count += 1;
                if let Some(id) = public_product(id) {
                    let price = object.get("price").and_then(Value::as_i64);
                    let currency_present = object.get("currencyCode").is_some_and(Value::is_string);
                    let display_present = object.get("showPrice").is_some_and(|value| value.as_str().is_some_and(|s| !s.is_empty()));
                    products.push(format!("{id}: price={price:?}, currency_present={currency_present}, display_present={display_present}"));
                }
            }
            for child in object.values() {
                collect_products(child, depth + 1, count, products);
            }
        }
        Value::Array(values) => {
            for child in values { collect_products(child, depth + 1, count, products); }
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_price_is_preserved_in_the_native_response_summary() {
        let result = response_summary(r#"{"retcode":0,"data":[{"productIdentifier":"ys_chn_blessofmoon_tier5","price":0,"currencyCode":"CNY"}]}"#).unwrap();
        assert!(result.contains("price=Some(0)"));
        assert!(result.contains("products=1"));
        assert!(result.contains("retcode=Some(0)"));
    }

    #[test]
    fn summary_never_copies_account_or_arbitrary_product_fields() {
        let result = response_summary(r#"{"retcode":0,"token":"private-account-token","data":[{"productIdentifier":"private-player-id","price":99},{"productIdentifier":"ys_chn_bp_extra_tier20","price":0,"currencyCode":"private-currency-value","description":"private-display-text"}]}"#).unwrap();
        assert!(result.contains("products=2"));
        assert!(result.contains("ys_chn_bp_extra_tier20"));
        assert!(!result.contains("private"));
        assert!(!result.contains("99"));
    }

    #[test]
    fn missing_or_non_integer_price_is_distinct_from_free_price() {
        for price in ["", ",\"price\":\"0\"", ",\"price\":0.5"] {
            let json = format!(r#"{{"data":{{"products":[{{"productIdentifier":"ys_chn_blessofmoon_tier5"{price}}}]}}}}"#);
            assert!(response_summary(&json).unwrap().contains("price=None"));
        }
        assert!(public_product("ys_chn_blessofmoon_tier5\nsecret").is_none());
        assert_eq!(public_price_query("Tier_5"), Some("Tier_5"));
        assert!(public_product("Tier_5").is_none());
        assert!(public_price_query("private-account-token").is_none());
    }

    #[test]
    fn malformed_and_oversized_callbacks_do_not_escape_to_the_hook() {
        assert!(response_summary("not json").is_none());
        assert!(response_summary(&" ".repeat(65_537)).is_none());
        assert!(response_summary(r#"{"retcode":-1,"data":null}"#).unwrap().contains("products=0"));
    }
}
