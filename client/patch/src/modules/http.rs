use std::ffi::CString;
use std::collections::HashSet;
use std::sync::{Mutex, atomic::{AtomicBool, Ordering}};
use lazy_static::lazy_static;

use super::{MhyContext, MhyModule, ModuleType};
use crate::marshal;
use anyhow::Result;
use ilhook::x64::Registers;
use crate::util;

const WEB_REQUEST_UTILS_MAKE_INITIAL_URL: &str = "55 41 56 56 57 53 48 81 EC ?? ?? ?? ?? 48 8D AC 24 ?? ?? ?? ?? 48 C7 45 ?? ?? ?? ?? ?? 48 89 D6 48 89 CF 48 8B 0D ?? ?? ?? ??";
const BROWSER_LOAD_URL: &str = "41 B0 01 E9 08 00 00 00 0F 1F 84 00 00 00 00 00 56 57";
const BROWSER_LOAD_URL_OFFSET: usize = 0x10;

// MiHoYo.SDK.NetworkManager, verified against the supplied client profile.
// The price SDK starts its own Unity requests rather than using WebRequestUtils.
const SDK_GET_REQUEST: &str = "41 57 41 56 41 54 56 57 55 53 48 83 EC 50 0F 29 74 24 40 4C 89 CE 4D 89 C6 49 89 D7 49 89 CC 8B AC 24 B8 00 00 00 F3 0F 10 B4 24 B0 00 00 00 80 3D 8E 9D 6D EC 00";
const SDK_POST_REQUEST: &str = "41 57 41 56 41 54 56 57 55 53 48 83 EC 50 0F 29 74 24 40 4C 89 CE 4D 89 C6 49 89 D7 49 89 CC 8B AC 24 B8 00 00 00 F3 0F 10 B4 24 B0 00 00 00 80 3D 7A A4 6D EC 00";
const SDK_INVOKE: &str = "55 41 57 41 56 41 54 56 57 53 48 83 EC 60 48 8D 6C 24 60 48 C7 45 F0 FE FF FF FF 4C 89 CF 4D 89 C7 48 89 D6 49 89 CE";
const SDK_PRODUCT_RESPONSE: &str = "55 41 57 41 56 56 57 53 48 81 EC A8 00 00 00 48 8D AC 24 80 00 00 00 48 C7 45 18 FE FF FF FF 48 89 D7 48 89 4D 10 80";

// Product-ready predicate at client RVA 0x964bb60: instance + SKU -> bool.
// Hook the entry. Its empty-SKU branch targets 0x964bbd2, inside the bytes a
// mid-function hook at 0x964bbc7 overwrote, which caused the battle-pass crash.
const SDK_PRICE_QUERY: &str = "56 57 48 83 EC 58 48 89 D6 48 89 CF 80 3D CD 9A 44 FC 00 0F 84 FF 00 00 00 80 3D 4E FA 12 FC 00";
static SDK_DIAGNOSTICS_ENABLED: AtomicBool = AtomicBool::new(false);
static SDK_LOCAL_RECHARGE: AtomicBool = AtomicBool::new(false);
lazy_static! {
    static ref LOGGED_PRICE_LOOKUPS: Mutex<HashSet<(&'static str, i32)>> = Mutex::new(HashSet::new());
    static ref LOGGED_PRICE_QUERIES: Mutex<HashSet<&'static str>> = Mutex::new(HashSet::new());
}

pub struct Http;

impl MhyModule for MhyContext<Http> {
    unsafe fn init(&mut self) -> Result<()> {

        let web_request_utils_make_initial_url = util::pattern_scan_il2cpp(self.assembly_name, WEB_REQUEST_UTILS_MAKE_INITIAL_URL);
        if let Some(addr) = web_request_utils_make_initial_url {
            crate::plog!("web_request_utils_make_initial_url: {:x}", addr as usize);
            self.interceptor.attach(
                addr as usize,
                on_make_initial_url,
            )?;
        }
        else
        {
            crate::plog!("Failed to find web_request_utils_make_initial_url");
        }

        let browser_load_url = util::pattern_scan_il2cpp(self.assembly_name, BROWSER_LOAD_URL);
        if let Some(addr) = browser_load_url {
            let addr_offset = addr as usize + BROWSER_LOAD_URL_OFFSET;
            crate::plog!("browser_load_url: {:x}", addr_offset);
            self.interceptor.attach(
                addr_offset,
                on_browser_load_url,
            )?;
        }
        else
        {
            crate::plog!("Failed to find browser_load_url");
        }

        for (name, pattern) in [
            ("sdk_get_request", SDK_GET_REQUEST),
            ("sdk_post_request", SDK_POST_REQUEST),
        ] {
            if let Some(addr) = util::pattern_scan_il2cpp(self.assembly_name, pattern) {
                crate::plog!("{name}: {:x}", addr as usize);
                self.interceptor.attach(addr as usize, on_sdk_price_url)?;
            } else {
                crate::plog!("Failed to find {name}; SDK prices may remain unavailable");
            }
        }

        // Trace only product discovery boundaries, without logging SDK payloads.
        if let Some(addr) = util::pattern_scan_il2cpp(self.assembly_name, SDK_INVOKE) {
            self.interceptor.replace(addr as usize, on_sdk_invoke)?;
            crate::plog!("sdk_invoke: {:x}", addr as usize);
        } else {
            crate::plog!("Failed to find sdk_invoke");
        }
        if let Some(addr) = util::pattern_scan_il2cpp(self.assembly_name, SDK_PRODUCT_RESPONSE) {
            self.interceptor.attach(addr as usize, on_sdk_product_response)?;
        }

        let diagnostics = std::env::var("LUNAGC_SDK_DIAGNOSTICS").is_ok_and(|value| value == "1")
            || std::env::temp_dir().join("lunagc-sdk-diagnostics.txt").exists();
        SDK_DIAGNOSTICS_ENABLED.store(diagnostics, Ordering::Relaxed);
        let local_recharge = super::local_recharge::enabled();
        SDK_LOCAL_RECHARGE.store(local_recharge, Ordering::Relaxed);
        if diagnostics || local_recharge {
            if let Some(addr) = util::pattern_scan_il2cpp(self.assembly_name, SDK_PRICE_QUERY) {
                match self.interceptor.replace(addr as usize, on_sdk_product_ready) {
                    Ok(()) => crate::plog!("SDK local product-ready predicate installed at function entry"),
                    Err(error) => crate::plog!("SDK product-ready hook unavailable: {error}"),
                }
            } else {
                crate::plog!("SDK query diagnostics: unsupported client signature");
            }
        }

        Ok(())
    }

    unsafe fn de_init(&mut self) -> Result<()> {
        Ok(())
    }

    fn get_module_type(&self) -> super::ModuleType {
        ModuleType::Http
    }
}

/// http(s) only, file:// urls must not be rewritten
fn is_redirectable(url: &str) -> bool {
    url.starts_with("http://") || url.starts_with("https://")
}

pub(super) fn sdk_price_url(url: &str) -> Option<String> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let (_, path) = rest.split_once('/')?;
    let endpoint = path.split(['?', '#']).next()?;
    let supported = ["hk4e_cn", "hk4e_global"].iter().any(|region| {
        ["listPriceTier", "listPriceTierV2"].iter().any(|api| {
            endpoint == format!("{region}/mdk/shopwindow/shopwindow/{api}")
        })
    });
    supported.then(|| format!("{}/{path}", super::endpoint::server().base))
}

/// SDK instance methods receive their URL in rdx; leave payloads and callbacks intact.
unsafe extern "win64" fn on_sdk_price_url(reg: *mut Registers, _: usize) {
    let Some(url) = managed_string((*reg).rdx) else { return; };
    if let Some(new_url) = sdk_price_url(&url) {
        // Do not log account tokens or query strings.
        let path = new_url.split(['?', '#']).next().unwrap();
        crate::plog!("SDK price request: {path}");
        if let Ok(text) = CString::new(new_url) {
            (*reg).rdx = marshal::ptr_to_string_ansi(&text) as u64;
        }
    }
}

unsafe fn managed_string(ptr: u64) -> Option<String> {
    let ptr = ptr as *const u8;
    if ptr.is_null() { return None; }
    let length = *(ptr.add(16) as *const i32);
    if !(0..=65536).contains(&length) { return None; }
    Some(String::from_utf16_lossy(std::slice::from_raw_parts(ptr.add(20) as *const u16, length as usize)))
}

unsafe extern "win64" fn on_sdk_invoke(reg: *mut Registers, original: usize, _: usize) -> usize {
    if let Some(method) = managed_string((*reg).rdx) {
        if method == "login_pay" && SDK_LOCAL_RECHARGE.load(Ordering::Relaxed) {
            // The game calls this only after the successful RechargeRsp. The local
            // server has already persisted delivery before sending that response.
            // Do not open the external SDK cashier or its identity-entry form.
            if (*reg).r9 != 0 {
                let response = CString::new(r#"{"retcode":0,"message":"OK","data":{}}"#).unwrap();
                let response = marshal::ptr_to_string_ansi(&response) as u64;
                complete_sdk_callback((*reg).r9, response);
            }
            crate::plog!("Local checkout: SDK cashier suppressed after game-server delivery");
            return 0;
        }
        let lower = method.to_ascii_lowercase();
        if method.len() <= 64 && method.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
            && ["product", "pay", "order", "recharge"].iter().any(|word| lower.contains(word)) {
            crate::plog!("SDK commerce method: {method}");
        }
    }
    let original: unsafe extern "win64" fn(u64,u64,u64,u64) -> usize = std::mem::transmute(original);
    original((*reg).rcx,(*reg).rdx,(*reg).r8,(*reg).r9)
}

unsafe fn complete_sdk_callback(callback: u64, response: u64) {
    // SDKInvoke's existing Action<string> dispatch at RVA 0x70aba66 loads exactly
    // these three slots: method +0x18, MethodInfo +0x28, and target +0x40.
    let callback = callback as *const u8;
    let method = *(callback.add(0x18) as *const usize);
    if method == 0 { return; }
    let target = *(callback.add(0x40) as *const u64);
    let info = *(callback.add(0x28) as *const u64);
    let invoke: unsafe extern "win64" fn(u64,u64,u64) = std::mem::transmute(method);
    invoke(target,response,info);
}

unsafe extern "win64" fn on_sdk_product_response(reg: *mut Registers, _: usize) {
    if let Some(response) = managed_string((*reg).rdx) {
        crate::plog!("SDK product response received: chars={}, product identifiers={}",
            response.len(), response.matches("productIdentifier").count());
        if SDK_DIAGNOSTICS_ENABLED.load(Ordering::Relaxed) {
            if let Some(summary) = super::sdk_product_diagnostics::response_summary(&response) {
                crate::plog!("SDK public product summary: {summary}");
            } else {
                crate::plog!("SDK public product summary: invalid or oversized JSON");
            }
        }
    }
}

unsafe extern "win64" fn on_sdk_product_ready(reg: *mut Registers, original: usize, _: usize) -> usize {
    if SDK_LOCAL_RECHARGE.load(Ordering::Relaxed) {
        if let Some(id) = managed_string((*reg).rdx) {
            if let Some(product) = super::local_recharge::product(&id) {
                if let Ok(mut logged) = LOGGED_PRICE_LOOKUPS.lock() {
                    if logged.insert((product,1)) { crate::plog!("Local recharge product ready: {product}"); }
                }
                return 1;
            }
        }
    }
    let original: unsafe extern "win64" fn(u64,u64,u64,u64) -> usize = std::mem::transmute(original);
    original((*reg).rcx, (*reg).rdx, (*reg).r8, (*reg).r9)
}

#[cfg(test)]
unsafe fn sdk_price_lookup(reg: *const Registers) -> Option<(&'static str, i32)> {
    let id = managed_string((*reg).rsi)?;
    let id = super::sdk_product_diagnostics::public_price_query(&id)?;
    Some((id, (*reg).rax as u32 as i32))
}

#[cfg(test)]
mod tests {
    use super::{sdk_price_url, sdk_price_lookup};
    use ilhook::x64::Registers;

    #[test]
    fn sdk_prices_reach_local_server_without_losing_queries() {
        for region in ["hk4e_cn", "hk4e_global"] {
            for api in ["listPriceTier", "listPriceTierV2"] {
                let path = format!("/{region}/mdk/shopwindow/shopwindow/{api}?currency=CNY&game_biz={region}");
                for scheme in ["http", "https"] {
                    let url = format!("{scheme}://sdk-static.mihoyo.com{path}");
                    assert_eq!(sdk_price_url(&url), Some(format!("http://127.0.0.1:8088{path}")));
                }
            }
        }
    }

    #[test]
    fn unrelated_sdk_and_local_file_requests_are_not_rewritten() {
        for url in [
            "file:///hk4e_cn/mdk/shopwindow/shopwindow/listPriceTier",
            "https://sdk-static.mihoyo.com/hk4e_cn/mdk/shield/api/loadConfig",
            "https://sdk-static.mihoyo.com/hk4e_cn/mdk/shopwindow/shopwindow/listPriceTierOther",
        ] {
            assert_eq!(sdk_price_url(url), None);
        }
    }

    #[repr(C)]
    struct ManagedTestString {
        header: [u64; 2],
        length: i32,
        characters: [u16; 80],
    }

    fn managed_test_string(value: &str) -> ManagedTestString {
        let mut result = ManagedTestString { header: [0; 2], length: 0, characters: [0; 80] };
        for (index, character) in value.encode_utf16().enumerate() {
            result.characters[index] = character;
            result.length += 1;
        }
        result
    }

    #[test]
    fn price_snapshot_preserves_registers_and_distinguishes_zero_from_missing() {
        let text = managed_test_string("ys_chn_blessofmoon_tier5");
        let mut reg: Registers = unsafe { std::mem::zeroed() };
        reg.rsi = &text as *const _ as u64;
        reg.rcx = 123;
        reg.rdx = 456;
        for (raw, expected) in [(0, 0), (u64::MAX, -1), (0x100000000, 0)] {
            reg.rax = raw;
            assert_eq!(unsafe { sdk_price_lookup(&reg) }, Some(("ys_chn_blessofmoon_tier5", expected)));
            assert_eq!(reg.rax, raw);
            assert_eq!(reg.rsi, &text as *const _ as u64);
            assert_eq!(reg.rcx, 123);
            assert_eq!(reg.rdx, 456);
        }
    }

    #[test]
    fn price_snapshot_ignores_unknown_ids_and_invalid_managed_lengths() {
        let text = managed_test_string("private-account-token");
        let mut reg: Registers = unsafe { std::mem::zeroed() };
        reg.rsi = &text as *const _ as u64;
        assert_eq!(unsafe { sdk_price_lookup(&reg) }, None);
        for length in [-1, 65_537] {
            let invalid = ManagedTestString { length, ..managed_test_string("ys_chn_blessofmoon_tier5") };
            reg.rsi = &invalid as *const _ as u64;
            assert_eq!(unsafe { sdk_price_lookup(&reg) }, None);
        }
        reg.rsi = 0;
        assert_eq!(unsafe { sdk_price_lookup(&reg) }, None);
    }

    #[test]
    fn local_product_availability_only_overrides_known_skus_and_preserves_original_arguments() {
        use std::sync::atomic::Ordering;
        super::SDK_LOCAL_RECHARGE.store(true,Ordering::Relaxed);
        let known = managed_test_string("ys_chn_blessofmoon_tier5");
        let unknown = managed_test_string("private-account-token");
        let mut reg: Registers = unsafe { std::mem::zeroed() };
        reg.rcx = 123;
        reg.rdx = &known as *const _ as u64;
        reg.r8 = 456;
        reg.r9 = 789;
        assert_eq!(unsafe { super::on_sdk_product_ready(&mut reg,0,0) },1);
        assert_eq!((reg.rcx,reg.r8,reg.r9),(123,456,789));
        unsafe extern "win64" fn original(this:u64, _sku:u64, third:u64, fourth:u64) -> usize {
            assert_eq!((this,third,fourth),(123,456,789));
            42
        }
        reg.rdx = &unknown as *const _ as u64;
        assert_eq!(unsafe { super::on_sdk_product_ready(&mut reg,original as usize,0) },42);
        reg.rdx = 0;
        assert_eq!(unsafe { super::on_sdk_product_ready(&mut reg,original as usize,0) },42);
        super::SDK_LOCAL_RECHARGE.store(false,Ordering::Relaxed);
    }

    #[test]
    fn local_checkout_stops_cashier_and_leaves_login_calls_untouched() {
        use std::sync::atomic::Ordering;
        let pay = managed_test_string("login_pay");
        let login = managed_test_string("login_verify");
        let mut reg: Registers = unsafe { std::mem::zeroed() };
        reg.rcx=123; reg.rdx=&pay as *const _ as u64; reg.r8=456;
        super::SDK_LOCAL_RECHARGE.store(true,Ordering::Relaxed);
        assert_eq!(unsafe { super::on_sdk_invoke(&mut reg,0,0) },0);
        assert_eq!((reg.rcx,reg.r8,reg.r9),(123,456,0));
        unsafe extern "win64" fn original(this:u64,_method:u64,data:u64,callback:u64) -> usize {
            assert_eq!((this,data,callback),(123,456,0)); 73
        }
        reg.rdx=&login as *const _ as u64;
        assert_eq!(unsafe { super::on_sdk_invoke(&mut reg,original as usize,0) },73);
        super::SDK_LOCAL_RECHARGE.store(false,Ordering::Relaxed);
        reg.rdx=&pay as *const _ as u64;
        assert_eq!(unsafe { super::on_sdk_invoke(&mut reg,original as usize,0) },73);
    }

    #[test]
    fn sdk_completion_uses_the_existing_delegate_target_and_method_info() {
        unsafe extern "win64" fn callback(target:u64,response:u64,info:u64) {
            assert_eq!((target,response,info),(17,23,31));
        }
        let mut delegate=[0u64;9];
        delegate[3]=callback as usize as u64; delegate[5]=31; delegate[8]=17;
        unsafe { super::complete_sdk_callback(delegate.as_ptr() as u64,23); }
    }
}

unsafe extern "win64" fn on_make_initial_url(reg: *mut Registers, _: usize) {
    let str_length = *((*reg).rcx.wrapping_add(16) as *const u32);
    let str_ptr = (*reg).rcx.wrapping_add(20) as *const u8;

    let slice = std::slice::from_raw_parts(str_ptr as *const u16, str_length as usize);
    let url = String::from_utf16(slice).unwrap();

    if !is_redirectable(&url) {
        return;
    }

    let mut new_url = super::endpoint::server().base.clone();

    url.split('/').skip(3).for_each(|s| {
        new_url.push_str("/");
        new_url.push_str(s);
    });

    if !url.contains("/query_cur_region") {
        crate::plog!("Redirect: {url} -> {new_url}");
        (*reg).rcx =
            marshal::ptr_to_string_ansi(CString::new(new_url.as_str()).unwrap().as_c_str()) as u64;
    }
}

unsafe extern "win64" fn on_browser_load_url(reg: *mut Registers, _: usize) {
    let str_length = *((*reg).rdx.wrapping_add(16) as *const u32);
    let str_ptr = (*reg).rdx.wrapping_add(20) as *const u8;

    let slice = std::slice::from_raw_parts(str_ptr as *const u16, str_length as usize);
    let url = String::from_utf16(slice).unwrap();

    if !is_redirectable(&url) {
        return;
    }

    let mut new_url = super::endpoint::server().base.clone();
    url.split('/').skip(3).for_each(|s| {
        new_url.push_str("/");
        new_url.push_str(s);
    });

    crate::plog!("Browser::LoadURL: {url} -> {new_url}");

    (*reg).rdx =
        marshal::ptr_to_string_ansi(CString::new(new_url.as_str()).unwrap().as_c_str()) as u64;
}
