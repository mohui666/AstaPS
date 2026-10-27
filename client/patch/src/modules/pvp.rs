//! Native targeting for the server's explicitly paired avatars.
//!
//! This module never creates hits or changes HP. The normal client collision,
//! attack calculation and BeingHit reporting continue after these predicates.
//! All offsets below were checked against the exact CN 7.1 executable, not the
//! global metadata CSV (whose object layout differs).

use super::{MhyContext, MhyModule, ModuleType};
use anyhow::{bail, Result};
use ilhook::x64::Registers;
use sha2::{Digest, Sha256};
use std::{fs::File, io::Read, sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering}};
use windows::{core::PCWSTR, Win32::System::{
    Diagnostics::Debug::ReadProcessMemory,
    LibraryLoader::GetModuleHandleW,
    Threading::GetCurrentProcess,
}};

const EXE_SHA256: &str = "7f89938da606c1281659607d464702cddb9a09a7d4320a196630f60a811ec38e";
const PAIR_KEY: i32 = 237_768_612; // Utils.abilityHash("SGV_AstaPvpPair")
const ENEMY: u32 = 2;
const AVATAR_CLASS_SLOT: usize = 0x057b1570;
const CONTEXT_SLOT: usize = 0x05783de8;
const GLOBAL_FLOAT_GETTER: usize = 0x0a7dc890;
const ENTITY_LOOKUP: usize = 0x0e983110;
const CAMP_TARGET: usize = 0x0fe2f7f0;
const CAMP_IDS_TARGET: usize = 0x0fe2d990;
const MP_HIT: usize = 0x12e90540;
const MP_ENV_HIT: usize = 0x12ea52c0;
const BULLET_TARGET: usize = 0x12ea27f0;
const SGV_APPLY: usize = 0x137d8070;

// Prefixes include the original instructions overwritten by ilhook. Checking
// these before installing any hook also detects another patch at the entries.
const ENTRIES: &[(usize, &[u8])] = &[
    (CAMP_TARGET, &[0x41,0x57,0x41,0x56,0x56,0x57,0x55,0x53,0x48,0x83,0xec,0x48,0x45,0x89,0xc7,0x48,0x89,0xd7,0x48,0x89,0xcb]),
    (CAMP_IDS_TARGET, &[0x56,0x57,0x55,0x53,0x48,0x83,0xec,0x38,0x44,0x89,0xcb,0x44,0x89,0xc7,0x89,0xd5,0x89,0xca]),
    (MP_HIT, &[0x41,0x57,0x41,0x56,0x41,0x54,0x56,0x57,0x53,0x48,0x83,0xec,0x28,0x89,0xd3,0x89,0xcf]),
    (MP_ENV_HIT, &[0x56,0x57,0x48,0x83,0xec,0x28,0x89,0xd7,0x41,0x89,0xc8,0x80,0x3d,0xee,0x74,0x8d,0xf2,0]),
    (BULLET_TARGET, &[0x56,0x57,0x55,0x53,0x48,0x83,0xec,0x28,0x44,0x89,0xcd,0x4d,0x89,0xc1,0x48,0x89,0xd3,0x48,0x89,0xcf]),
    (SGV_APPLY, &[0x56,0x57,0x53,0x48,0x83,0xec,0x40,0x0f,0x29,0x74,0x24,0x30,0x0f,0x28,0xf3,0x44,0x89,0xc6,0x48,0x89,0xd7]),
    (GLOBAL_FLOAT_GETTER, &[0x56,0x48,0x83,0xec,0x40,0x41,0x89,0xd0,0x48,0x89,0xca,0x80,0x3d,0xb9,0x98,0xf5,0xfa,0]),
    (ENTITY_LOOKUP, &[0x56,0x57,0x55,0x53,0x48,0x83,0xec,0x28,0x44,0x89,0xc5,0x89,0xd6,0x48,0x89,0xcf]),
    // GetComponent: BaseEntity + 0x2d0 -> component array + 0x68.
    (0x1433bb30, &[0x56,0x57,0x48,0x83,0xec,0x28,0x89,0xd6,0x48,0x89,0xcf,0x49,0x8b,0x40,0x10]),
    // AvatarIsHost: exact native AvatarEntity klass and avatar GUID accessor.
    (0x12e89930, &[0x56,0x48,0x83,0xec,0x20,0x48,0x89,0xca,0x80,0x3d,0x99,0x2d,0x8f,0xf2,0]),
];

static BASE: AtomicUsize = AtomicUsize::new(0);
static ENABLED: AtomicBool = AtomicBool::new(false);
static LOGGED: AtomicU32 = AtomicU32::new(0);

pub struct Pvp;

impl MhyModule for MhyContext<Pvp> {
    unsafe fn init(&mut self) -> Result<()> {
        let install = (|| -> Result<()> {
            let mut file = File::open(std::env::current_exe()?)?;
            let mut hash = Sha256::new();
            let mut buffer = [0; 65536];
            loop {
                let count = file.read(&mut buffer)?;
                if count == 0 { break; }
                hash.update(&buffer[..count]);
            }
            if format!("{:x}", hash.finalize()) != EXE_SHA256 {
                bail!("unsupported executable; requires the verified CN 7.1 profile");
            }
            let base = GetModuleHandleW(PCWSTR::null())?.0 as usize;
            for &(rva, expected) in ENTRIES {
                let mut actual = vec![0; expected.len()];
                if !read_bytes(base + rva, &mut actual) || actual != expected {
                    bail!("native entry mismatch at RVA 0x{rva:x}");
                }
            }
            BASE.store(base, Ordering::Relaxed);
            self.interceptor.replace(base + CAMP_TARGET, camp_target)?;
            self.interceptor.replace(base + CAMP_IDS_TARGET, camp_ids_target)?;
            self.interceptor.replace(base + MP_HIT, multiplayer_hit)?;
            self.interceptor.replace(base + MP_ENV_HIT, multiplayer_hit)?;
            self.interceptor.replace(base + BULLET_TARGET, bullet_target)?;
            self.interceptor.replace(base + SGV_APPLY, sgv_apply)?;
            ENABLED.store(true, Ordering::Release);
            crate::plog!("PvP native targeting enabled: verified CN 7.1; awaiting server pair SGV");
            Ok(())
        })();
        if let Err(error) = install {
            ENABLED.store(false, Ordering::Release);
            self.interceptor.hooks.clear();
            BASE.store(0, Ordering::Relaxed);
            crate::plog!("PvP native targeting disabled: {error}");
        }
        Ok(())
    }

    unsafe fn de_init(&mut self) -> Result<()> {
        ENABLED.store(false, Ordering::Release);
        self.interceptor.hooks.clear();
        Ok(())
    }

    fn get_module_type(&self) -> ModuleType { ModuleType::Pvp }
}

fn read_bytes(address: usize, data: &mut [u8]) -> bool {
    if address == 0 || address.checked_add(data.len()).is_none() { return false; }
    let mut copied = 0;
    unsafe {
        ReadProcessMemory(GetCurrentProcess(), address as _, data.as_mut_ptr() as _, data.len(), Some(&mut copied)).is_ok()
            && copied == data.len()
    }
}

fn read<const N: usize>(address: usize) -> Option<[u8; N]> {
    let mut bytes = [0; N];
    read_bytes(address, &mut bytes).then_some(bytes)
}

fn pointer(address: usize) -> Option<usize> {
    let value = usize::from_le_bytes(read(address)?);
    (value != 0).then_some(value)
}

fn uint(address: usize) -> Option<u32> { Some(u32::from_le_bytes(read(address)?)) }

fn component(entity: usize, index: usize) -> Option<usize> {
    let table = pointer(entity.checked_add(0x2d0)?)?;
    let array = pointer(table.checked_add(0x68)?)?;
    if uint(array.checked_add(0x18)?)? as usize <= index { return None; }
    pointer(array.checked_add(0x20 + index * 8)?)
}

#[derive(Clone, Copy)]
struct Avatar {
    entity: usize,
    id: u32,
    owner: u32,
}

fn avatar(entity: usize) -> Option<Avatar> {
    if entity == 0 { return None; }
    let base = BASE.load(Ordering::Relaxed);
    if base == 0 { return None; }
    let class = pointer(base + AVATAR_CLASS_SLOT)?;
    if pointer(entity)? != class { return None; }
    // The same active/native-object guards used by the original hit predicates.
    if read::<1>(entity.checked_add(0x482)?)?[0] == 0 { return None; }
    pointer(entity.checked_add(0x1a0)?)?;
    let id = uint(entity.checked_add(0x3a8)?)?;
    let guid = u64::from_le_bytes(read(entity.checked_add(0x558)?)?);
    // AstaPS Player.getNextGameGuid = (uid << 32) + per-player sequence.
    let owner = (guid >> 32) as u32;
    (id != 0 && owner != 0).then_some(Avatar { entity, id, owner })
}

unsafe fn marker(avatar: Avatar) -> Option<f32> {
    // GetComponent<LCAbility>'s native mask is 0x1001000, table index 1.
    // SGV apply at RVA 0x137d8070 writes LCAbility + 0x198 using this getter's
    // native store; querying it directly makes clear/switch/remove immediate.
    let ability = component(avatar.entity, 1)?;
    let globals = pointer(ability.checked_add(0x198)?)?;
    pointer(globals.checked_add(0xc0)?)?; // getter's dictionary must exist
    let get: unsafe extern "win64" fn(usize, i32, usize) -> f32 =
        std::mem::transmute(BASE.load(Ordering::Relaxed) + GLOBAL_FLOAT_GETTER);
    Some(get(globals, PAIR_KEY, 0))
}

fn valid_marker(value: f32) -> bool {
    value.is_finite() && value != 0.0 && value.abs() < 16_777_216.0 && value.fract() == 0.0
}

fn paired_values(first_owner: u32, second_owner: u32, first: f32, second: f32) -> bool {
    first_owner != 0 && second_owner != 0 && first_owner != second_owner
        && valid_marker(first) && valid_marker(second) && first == -second
}

unsafe fn paired(first: usize, second: usize) -> bool {
    if !ENABLED.load(Ordering::Acquire) || first == second { return false; }
    let Some(first_avatar) = avatar(first) else {
        if claim_log(1 << 12) {
            crate::plog!("PvP: source avatar guard rejected: {}", avatar_state(first));
        }
        return false;
    };
    let Some(second_avatar) = avatar(second) else {
        if claim_log(1 << 13) {
            crate::plog!("PvP: target avatar guard rejected: {}", avatar_state(second));
        }
        return false;
    };
    let (first, second) = (first_avatar, second_avatar);
    if first.id == second.id || first.owner == second.owner {
        if claim_log(1 << 14) {
            crate::plog!("PvP: identity guard rejected: ids={}/{}, owners={}/{}", first.id, second.id, first.owner, second.owner);
        }
        return false;
    }
    let (Some(a), Some(b)) = (marker(first), marker(second)) else {
        if claim_log(1 << 15) {
            crate::plog!("PvP: native SGV store unavailable: first={}, second={}", marker_store_state(first), marker_store_state(second));
        }
        return false;
    };
    if !paired_values(first.owner, second.owner, a, b) {
        if claim_log(1 << 16) {
            crate::plog!("PvP: native pair markers rejected: ids={}/{}, owners={}/{}, values={}/{}", first.id, second.id, first.owner, second.owner, a, b);
        }
        return false;
    }
    once(1, "server pair SGV verified on two native avatars");
    true
}

fn once(bit: u32, message: &str) {
    if claim_log(bit) {
        crate::plog!("PvP: {message}");
    }
}

fn claim_log(bit: u32) -> bool { LOGGED.fetch_or(bit, Ordering::Relaxed) & bit == 0 }

fn avatar_state(entity: usize) -> String {
    let base = BASE.load(Ordering::Relaxed);
    let class = pointer(base + AVATAR_CLASS_SLOT);
    let actual = pointer(entity);
    let is_avatar = class.is_some() && actual == class;
    let id = entity.checked_add(0x3a8).and_then(uint);
    let active = entity.checked_add(0x482).and_then(read::<1>).map(|b| b[0]);
    let owner = is_avatar.then(|| entity.checked_add(0x558).and_then(read::<8>)
        .map(|b| (u64::from_le_bytes(b) >> 32) as u32)).flatten();
    let native_object = entity.checked_add(0x1a0).and_then(pointer).is_some();
    format!("id={id:?}, nativeAvatar={is_avatar}, active={active:?}, owner={owner:?}, nativeObject={native_object}")
}

fn marker_store_state(avatar: Avatar) -> String {
    let ability = component(avatar.entity, 1);
    let globals = ability.and_then(|p| p.checked_add(0x198)).and_then(pointer);
    let dictionary = globals.and_then(|p| p.checked_add(0xc0)).and_then(pointer);
    format!("id={}, ability={}, globals={}, dictionary={}", avatar.id, ability.is_some(), globals.is_some(), dictionary.is_some())
}

unsafe fn entity_by_id(id: u32) -> Option<usize> {
    if id == 0 { return None; }
    let context = pointer(BASE.load(Ordering::Relaxed) + CONTEXT_SLOT)?;
    let manager = pointer(context.checked_add(0x9d50)?)?;
    pointer(manager.checked_add(0x1e8)?)?; // original GetEntity's dictionary
    let get: unsafe extern "win64" fn(usize, u32, u8, usize) -> usize =
        std::mem::transmute(BASE.load(Ordering::Relaxed) + ENTITY_LOOKUP);
    let entity = get(manager, id, 0, 0);
    (entity != 0).then_some(entity)
}

unsafe extern "win64" fn camp_target(reg: *mut Registers, original: usize, _: usize) -> usize {
    let regs = &*reg;
    let native: unsafe extern "win64" fn(u64, u64, u64, u64) -> u8 = std::mem::transmute(original);
    let result = native(regs.rcx, regs.rdx, regs.r8, regs.r9);
    once(1 << 7, "native Combat target predicate reached");
    if result != 0 || regs.r8 as u32 != ENEMY { return result as usize; }
    // Combat + 0x28 is its BaseEntity; +0x14 is the component's entity ID.
    let source = pointer(regs.rcx as usize + 0x28);
    let target = pointer(regs.rdx as usize + 0x28);
    if let (Some(source), Some(target)) = (source, target) {
        if paired(source, target) {
            once(2, "paired enemy targeting allowed");
            return 1;
        }
    }
    result as usize
}

unsafe extern "win64" fn camp_ids_target(reg: *mut Registers, original: usize, _: usize) -> usize {
    let regs = &*reg;
    let target_type = regs.get_stack(5);
    let native: unsafe extern "win64" fn(u64, u64, u64, u64, u64, u64) -> u8 = std::mem::transmute(original);
    let result = native(regs.rcx, regs.rdx, regs.r8, regs.r9, target_type, regs.get_stack(6));
    once(1 << 10, "native entity-ID target predicate reached");
    if result != 0 || target_type as u32 != ENEMY || !ENABLED.load(Ordering::Acquire) { return result as usize; }
    let (source, target) = (entity_by_id(regs.r8 as u32), entity_by_id(regs.r9 as u32));
    if claim_log(1 << 17) {
        crate::plog!("PvP: native enemy entity-ID query: ids={}/{}, resolved={}/{}", regs.r8 as u32, regs.r9 as u32, source.is_some(), target.is_some());
    }
    if let (Some(source), Some(target)) = (source, target) {
        if paired(source, target) {
            once(2, "paired enemy targeting allowed");
            return 1;
        }
    }
    result as usize
}

unsafe extern "win64" fn multiplayer_hit(reg: *mut Registers, original: usize, _: usize) -> usize {
    let regs = &*reg;
    let native: unsafe extern "win64" fn(u64, u64, u64, u64) -> u8 = std::mem::transmute(original);
    let result = native(regs.rcx, regs.rdx, regs.r8, regs.r9);
    once(1 << 8, "native multiplayer hit predicate reached");
    if result != 0 || !ENABLED.load(Ordering::Acquire) { return result as usize; }
    if let (Some(source), Some(target)) = (entity_by_id(regs.rcx as u32), entity_by_id(regs.rdx as u32)) {
        if paired(source, target) {
            once(4, "paired multiplayer hit eligibility allowed");
            return 1;
        }
    }
    result as usize
}

unsafe extern "win64" fn bullet_target(reg: *mut Registers, original: usize, _: usize) -> usize {
    let regs = &*reg;
    let native: unsafe extern "win64" fn(u64, u64, u64, u64, u64) -> u8 = std::mem::transmute(original);
    let result = native(regs.rcx, regs.rdx, regs.r8, regs.r9, regs.get_stack(5));
    once(1 << 9, "native projectile target predicate reached");
    if result != 0 || regs.r9 as u32 != ENEMY { return result as usize; }
    // The original function also rejects avatar combat configs after CampHelper.
    // Permit this exact, active server pair; actual projectile collision and
    // CheckTargetAttackable's native life/state checks still run normally.
    if regs.r8 != 0 && paired(regs.rcx as usize, regs.rdx as usize) {
        once(8, "paired projectile target eligibility allowed");
        return 1;
    }
    result as usize
}

unsafe extern "win64" fn sgv_apply(reg: *mut Registers, original: usize, _: usize) -> usize {
    let regs = &*reg;
    let value = f32::from_bits(regs.xmm3 as u32);
    let native: unsafe extern "win64" fn(u64, u64, u32, f32, u64) = std::mem::transmute(original);
    native(regs.rcx, regs.rdx, regs.r8 as u32, value, regs.get_stack(5));
    once(1 << 11, "native SGV apply predicate reached");
    if regs.r8 as i32 == PAIR_KEY {
        let bit = if value == 0.0 { 1 << 6 } else if value < 0.0 { 1 << 5 } else { 1 << 4 };
        if claim_log(bit) {
            let entity = regs.rdx as usize;
            let current = avatar(entity).and_then(|entity| marker(entity));
            crate::plog!("PvP: server pair SGV applied: input={value}, current={current:?}, {}", avatar_state(entity));
        }
    }
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trampolines_preserve_native_registers_and_fifth_stack_argument() {
        unsafe extern "win64" fn four(a: u64, b: u64, c: u64, d: u64) -> u8 {
            u8::from((a, b, c, d) == (0x123, 0x456, 0x789, 0xabc))
        }
        unsafe extern "win64" fn five(a: u64, b: u64, c: u64, d: u64, info: u64) -> u8 {
            u8::from((a, b, c, d, info) == (0x123, 0x456, 0x789, 0xabc, 0xdef))
        }
        unsafe extern "win64" fn six(a: u64, b: u64, c: u64, d: u64, target_type: u64, info: u64) -> u8 {
            u8::from((a, b, c, d, target_type, info) == (0x123, 0x456, 0x789, 0xabc, 0xdef, 0x987))
        }
        static SGV_FLOAT: AtomicU32 = AtomicU32::new(0);
        unsafe extern "win64" fn apply(a: u64, b: u64, key: u32, value: f32, info: u64) {
            if (a, b, key, info) == (0x123, 0x456, 0x789, 0xdef) {
                SGV_FLOAT.store(value.to_bits(), Ordering::Relaxed);
            }
        }
        let mut stack = [0u64; 7];
        stack[5] = 0xdef;
        stack[6] = 0x987;
        let mut regs: Registers = unsafe { std::mem::zeroed() };
        regs.rcx = 0x123;
        regs.rdx = 0x456;
        regs.r8 = 0x789;
        regs.r9 = 0xabc;
        regs.rsp = stack.as_mut_ptr() as u64;
        unsafe {
            assert_eq!(camp_target(&mut regs, four as *const () as usize, 0), 1);
            assert_eq!(multiplayer_hit(&mut regs, four as *const () as usize, 0), 1);
            assert_eq!(bullet_target(&mut regs, five as *const () as usize, 0), 1);
            assert_eq!(camp_ids_target(&mut regs, six as *const () as usize, 0), 1);
            regs.xmm3 = 12.5_f32.to_bits() as u128;
            sgv_apply(&mut regs, apply as *const () as usize, 0);
            assert_eq!(SGV_FLOAT.load(Ordering::Relaxed), 12.5_f32.to_bits());
            stack[5] = 0;
            assert_eq!(bullet_target(&mut regs, five as *const () as usize, 0), 0);
            assert_eq!(camp_ids_target(&mut regs, six as *const () as usize, 0), 0);
        }
    }

    #[test]
    fn pair_tokens_reject_stale_unrelated_and_non_finite_values() {
        assert!(paired_values(1, 98, 16_777_215.0, -16_777_215.0));
        assert!(paired_values(2, 1, -19.0, 19.0));
        for (a, b) in [(0.0, -0.0), (19.0, 19.0), (19.0, -18.0), (0.5, -0.5),
            (f32::NAN, f32::NAN), (f32::INFINITY, f32::NEG_INFINITY), (16_777_216.0, -16_777_216.0)] {
            assert!(!paired_values(1, 98, a, b));
        }
        assert!(!paired_values(1, 1, 19.0, -19.0));
        assert!(!paired_values(0, 98, 19.0, -19.0));
        assert!(!paired_values(1, 98, 19.0, 0.0));
    }

    #[test]
    fn verified_cn_entries_match_current_sample_when_provided() {
        let Some(path) = std::env::var_os("ASTAPS_TEST_CLIENT") else { return; };
        let data = std::fs::read(path).expect("read supplied native sample");
        assert_eq!(format!("{:x}", Sha256::digest(&data)), EXE_SHA256);
        let word = |at: usize| u16::from_le_bytes(data[at..at+2].try_into().unwrap()) as usize;
        let dword = |at: usize| u32::from_le_bytes(data[at..at+4].try_into().unwrap()) as usize;
        let pe = dword(0x3c);
        assert_eq!(&data[pe..pe+4], b"PE\0\0");
        let sections = pe + 24 + word(pe + 20);
        for &(rva, prefix) in ENTRIES {
            let offset = (0..word(pe + 6)).find_map(|i| {
                let s = sections + i * 40;
                let va = dword(s + 12);
                let size = dword(s + 16);
                (rva >= va && rva - va < size).then(|| dword(s + 20) + rva - va)
            }).expect("entry belongs to an on-disk PE section");
            assert_eq!(&data[offset..offset+prefix.len()], prefix, "entry RVA {rva:x}");
        }
    }
}
