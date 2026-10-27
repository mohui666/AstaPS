use core::iter::once;
use std::ffi::{c_void, OsStr};

use std::os::windows::ffi::OsStrExt;
use std::time::Instant;
use windows::Win32::System::LibraryLoader::{GetModuleHandleA, GetModuleHandleW, GetProcAddress};
use windows::Win32::System::Memory::{PAGE_EXECUTE_READWRITE, PAGE_PROTECTION_FLAGS, VirtualProtect};
use windows::core::{s, PCSTR, PCWSTR};
use winapi::um::winnt::{IMAGE_DOS_HEADER, IMAGE_NT_HEADERS,IMAGE_SECTION_HEADER};
use std::slice;

pub fn wide_str(value: &str) -> Vec<u16> {
    OsStr::new(value).encode_wide().chain(once(0)).collect()
}

// VMProtect hooks NtProtectVirtualMemory to prevent changing protection of executable segments
// We use this trick to remove hook
pub unsafe fn disable_memprotect_guard() {
    let ntdll = wide_str("ntdll.dll");
    let ntdll = GetModuleHandleW(PCWSTR::from_raw(ntdll.as_ptr())).unwrap();
    let proc_addr = GetProcAddress(
        ntdll,
        PCSTR::from_raw(c"NtProtectVirtualMemory".to_bytes_with_nul().as_ptr()),
    )
    .unwrap();

    let routine = if is_wine() {
        GetProcAddress(ntdll, s!("NtPulseEvent")).unwrap()
    } else {
        GetProcAddress(ntdll, s!("NtQuerySection")).unwrap()
    } as *mut u32;

    let mut old_prot = PAGE_PROTECTION_FLAGS(0);
    VirtualProtect(
        proc_addr as *const usize as *mut c_void,
        1,
        PAGE_EXECUTE_READWRITE,
        &mut old_prot,
    )
    .unwrap();

    let routine_val = *(routine as *const usize);

    let lower_bits_mask = !(0xFFu64 << 32);
    let lower_bits = routine_val & lower_bits_mask as usize;

    let offset_val = *((routine as usize + 4) as *const u32);
    let upper_bits = ((offset_val as usize).wrapping_sub(1) as usize) << 32;

    let result = lower_bits | upper_bits;

    *(proc_addr as *mut usize) = result;

    VirtualProtect(
        proc_addr as *const usize as *mut c_void,
        1,
        old_prot,
        &mut old_prot,
    )
    .unwrap();
}

unsafe fn is_wine() -> bool {
    let module = GetModuleHandleA(s!("ntdll.dll")).unwrap();
    GetProcAddress(module, s!("wine_get_version")).is_some()
}

pub unsafe fn pattern_scan_code(module: &str, pattern: &str) -> Option<*mut u8> {
    let w_module_name = wide_str(module);

    let module_handle = match GetModuleHandleW(PCWSTR::from_raw(w_module_name.as_ptr())) {
        Ok(module) => Some(module.0 as usize),
        Err(_) => panic!("Failed to get module handle"),
    };

    let module_handle_addr = module_handle.unwrap();
    let mod_base = module_handle_addr as *const u8;
    let dos_header = unsafe { &*(mod_base as *const IMAGE_DOS_HEADER) };
    let nt_headers = unsafe { &*((mod_base.offset(dos_header.e_lfanew as isize)) as *const IMAGE_NT_HEADERS) };
    let text_section = nt_headers.OptionalHeader.BaseOfCode as usize;
    let size_of_text = nt_headers.OptionalHeader.SizeOfCode as usize;
    let text_section_offset = mod_base.offset(text_section as isize);
    let text_slice: &[u8] = unsafe { slice::from_raw_parts(text_section_offset, size_of_text) };

    let loc = find_in_slice(text_slice, pattern)?;
    Some(text_section_offset.wrapping_add(loc) as *mut u8)
}

pub unsafe fn pattern_scan_il2cpp(module: &str, pattern: &str) -> Option<*mut u8> {
    let w_module_name = wide_str(module);

    let module_handle = match GetModuleHandleW(PCWSTR::from_raw(w_module_name.as_ptr())) {
        Ok(module) => Some(module.0 as usize),
        Err(_) => panic!("Failed to get module handle"),
    };

    let module_handle_addr = module_handle.unwrap();
    let mod_base = module_handle_addr as *const u8;
    let dos_header = unsafe { &*(mod_base as *const IMAGE_DOS_HEADER) };
    let nt_headers = unsafe { &*((mod_base.offset(dos_header.e_lfanew as isize)) as *const IMAGE_NT_HEADERS) };

    let section_headers = unsafe {
        std::slice::from_raw_parts(
            (mod_base.offset(dos_header.e_lfanew as isize) as *const u8).offset(std::mem::size_of::<IMAGE_NT_HEADERS>() as isize) as *const IMAGE_SECTION_HEADER,
            nt_headers.FileHeader.NumberOfSections as usize,
        )
    };
    let il2cpp_section = section_headers.iter().find(|section| {
    let name = std::ffi::CStr::from_ptr(section.Name.as_ptr() as *const i8).to_str().unwrap_or("");
        name == "il2cpp"
    });

    if il2cpp_section.is_none() {
        crate::plog!("Failed to find il2cpp section");
        return None;
    }

    let il2cpp_base = mod_base.offset(il2cpp_section.unwrap().VirtualAddress as isize);
    let il2cpp_size = il2cpp_section.unwrap().SizeOfRawData as usize;
    let il2cpp_slice: &[u8] = unsafe { slice::from_raw_parts(il2cpp_base, il2cpp_size) };

    let loc = find_in_slice(il2cpp_slice, pattern)?;
    Some(il2cpp_base.wrapping_add(loc) as *mut u8)
}

/// Direct slice search. patternscan reads 2KB at a time and checks every byte
/// through the Read trait; on the ~320MB il2cpp section that misses the login
/// window, so query_region_list leaves for the official dispatch.
fn find_in_slice(haystack: &[u8], pattern: &str) -> Option<usize> {
    let pattern = parse_pattern(pattern)?;
    let started = Instant::now();
    let found = find_pattern(haystack, &pattern);
    crate::plog!(
        "scan {} bytes in {}ms found={}",
        haystack.len(),
        started.elapsed().as_millis(),
        found.is_some()
    );
    found
}

fn parse_pattern(pattern: &str) -> Option<Vec<Option<u8>>> {
    let mut parsed = Vec::new();
    for token in pattern.split_whitespace() {
        if token == "?" || token == "??" {
            parsed.push(None);
        } else {
            parsed.push(Some(u8::from_str_radix(token, 16).ok()?));
        }
    }
    if parsed.is_empty() { None } else { Some(parsed) }
}

fn find_pattern(haystack: &[u8], pattern: &[Option<u8>]) -> Option<usize> {
    if pattern.is_empty() || haystack.len() < pattern.len() {
        return None;
    }
    let anchor = pattern.iter().position(|byte| byte.is_some())?;
    let anchor_byte = pattern[anchor]?;
    let last = haystack.len() - pattern.len();
    let mut start = 0usize;
    while start <= last {
        let search_from = start + anchor;
        let Some(relative) = haystack[search_from..].iter().position(|&byte| byte == anchor_byte) else {
            return None;
        };
        let found = search_from + relative;
        start = found - anchor;
        if start > last {
            return None;
        }
        if pattern_at(haystack, start, pattern) {
            return Some(start);
        }
        start += 1;
    }
    None
}

fn pattern_at(haystack: &[u8], start: usize, pattern: &[Option<u8>]) -> bool {
    pattern.iter().zip(haystack[start..].iter()).all(|(expected, actual)| {
        expected.map(|byte| byte == *actual).unwrap_or(true)
    })
}

#[cfg(test)]
mod pattern_tests {
    use super::{find_pattern, parse_pattern};

    #[test]
    fn wildcard_pattern_finds_the_later_match() {
        let haystack = [0x41, 0x00, 0x41, 0x57, 0x99, 0xC5];
        let pattern = parse_pattern("41 57 ?? C5").unwrap();

        assert_eq!(find_pattern(&haystack, &pattern), Some(2));
    }

    #[test]
    fn missing_or_overlong_patterns_are_absent() {
        let haystack = [0x10, 0x20, 0x30];

        assert_eq!(find_pattern(&haystack, &parse_pattern("10 20 40").unwrap()), None);
        assert_eq!(find_pattern(&haystack, &parse_pattern("10 20 30 40").unwrap()), None);
        assert_eq!(parse_pattern("GG"), None);
    }

    #[test]
    fn client_il2cpp_section_scans_inside_the_login_window() {
        let Ok(path) = std::env::var("ASTAPS_TEST_CLIENT") else { return; };
        let path = std::path::Path::new(&path);
        let Ok(bytes) = std::fs::read(path) else { return; };
        let Some(section) = il2cpp_section(&bytes) else { return; };
        let started = std::time::Instant::now();
        let _ = find_pattern(section, &parse_pattern("55 41 56 56 57 53 48 81 EC ?? ?? ?? ??").unwrap());
        let elapsed = started.elapsed();

        assert!(
            elapsed.as_secs() < 3,
            "il2cpp scan took {}ms",
            elapsed.as_millis()
        );
    }

    fn il2cpp_section(bytes: &[u8]) -> Option<&[u8]> {
        if bytes.len() < 0x40 || &bytes[0..2] != b"MZ" {
            return None;
        }
        let e_lfanew = u32::from_le_bytes(bytes[0x3c..0x40].try_into().ok()?) as usize;
        if bytes.len() < e_lfanew + 24 || &bytes[e_lfanew..e_lfanew + 4] != b"PE\0\0" {
            return None;
        }
        let file_header = e_lfanew + 4;
        let sections = u16::from_le_bytes(bytes[file_header + 2..file_header + 4].try_into().ok()?) as usize;
        let optional_size = u16::from_le_bytes(bytes[file_header + 16..file_header + 18].try_into().ok()?) as usize;
        let mut offset = file_header + 20 + optional_size;
        for _ in 0..sections {
            if bytes.len() < offset + 40 {
                return None;
            }
            let name = &bytes[offset..offset + 8];
            let raw_size = u32::from_le_bytes(bytes[offset + 16..offset + 20].try_into().ok()?) as usize;
            let raw_ptr = u32::from_le_bytes(bytes[offset + 20..offset + 24].try_into().ok()?) as usize;
            if name.starts_with(b"il2cpp") {
                return bytes.get(raw_ptr..raw_ptr + raw_size);
            }
            offset += 40;
        }
        None
    }
}
