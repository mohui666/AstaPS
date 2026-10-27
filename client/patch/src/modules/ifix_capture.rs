//! Opt-in capture of the compiled patch stream, without moving its position.

mod runtime;

use super::{MhyContext, MhyModule, ModuleManager, ModuleType};
use anyhow::{bail, Result};
use ilhook::x64::Registers;
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Read, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicUsize, Ordering},
        OnceLock,
    },
};
use windows::{
    core::PCWSTR,
    Win32::System::{
        Diagnostics::Debug::ReadProcessMemory, LibraryLoader::GetModuleHandleW,
        Threading::GetCurrentProcess,
    },
};

const EXE_SHA256: &str = "7f89938da606c1281659607d464702cddb9a09a7d4320a196630f60a811ec38e";
const METADATA_SHA256: &str = "469eccd43aa48fe7a2df4e1caf66fa1268a17f5a4a03fa209e1427e467cb0161";
const STARTUP_SHA256: &str = "90848967d3f9432e7a13caffcec999bf457ac3be1f2ae86336443a63acbaadcf";
const LOAD_RVA: usize = 0x1912a730;
const STREAM_CLASS_RVA: usize = 0x579e6c8;
const BYTE_CLASS_RVA: usize = 0x579e058;
const LOAD_PREFIX: &[u8] = &[
    0x55, 0x41, 0x57, 0x41, 0x56, 0x41, 0x55, 0x41, 0x54, 0x56, 0x57, 0x53, 0x48, 0x81, 0xec, 0xf8,
    0, 0, 0, 0x48, 0x8d, 0xac, 0x24, 0x80, 0, 0, 0, 0x48, 0xc7, 0x45, 0x60, 0xfe,
];
const MAGIC: [u8; 8] = [0x07, 0x3d, 0x63, 0x7c, 0x85, 0x7b, 0xda, 0x66];
const MAX_BYTES: usize = 128 * 1024 * 1024;
const TOTAL_BYTES: usize = 256 * 1024 * 1024;
const MAX_FILES: usize = 16;

struct Capture {
    directory: PathBuf,
    game_base: usize,
}

static CAPTURE: OnceLock<Capture> = OnceLock::new();
static CAPTURE_COUNT: AtomicUsize = AtomicUsize::new(0);
static CAPTURE_BYTES: AtomicUsize = AtomicUsize::new(0);
static REJECT_COUNT: AtomicUsize = AtomicUsize::new(0);

trait MemoryReader {
    fn read(&self, address: usize, size: usize) -> Option<Vec<u8>>;
}

struct ProcessMemory;

impl MemoryReader for ProcessMemory {
    fn read(&self, address: usize, size: usize) -> Option<Vec<u8>> {
        if address == 0 || size > MAX_BYTES || address.checked_add(size).is_none() {
            return None;
        }
        let mut data = Vec::new();
        data.try_reserve_exact(size).ok()?;
        data.resize(size, 0);
        let mut copied = 0;
        let read = unsafe {
            ReadProcessMemory(
                GetCurrentProcess(),
                address as _,
                data.as_mut_ptr() as _,
                size,
                Some(&mut copied),
            )
        };
        (read.is_ok() && copied == size).then_some(data)
    }
}

fn u64_at(data: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(data[offset..offset + 8].try_into().unwrap())
}

fn i32_at(data: &[u8], offset: usize) -> i32 {
    i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
}

fn pointer(reader: &impl MemoryReader, address: usize) -> Option<usize> {
    let data = reader.read(address, 8)?;
    (data.len() == 8).then(|| u64_at(&data, 0) as usize)
}

fn patch_stream(
    reader: &impl MemoryReader,
    stream: usize,
    stream_class: usize,
    byte_class: usize,
) -> std::result::Result<Vec<u8>, &'static str> {
    if stream == 0 || stream_class == 0 || byte_class == 0 {
        return Err("missing stream type");
    }
    let header = reader.read(stream, 0x44).ok_or("unreadable stream")?;
    if header.len() != 0x44 || u64_at(&header, 0) as usize != stream_class {
        return Err("not the verified MemoryStream type");
    }
    if header[0x43] != 1 {
        return Err("closed stream");
    }
    let capacity = i32_at(&header, 0x30);
    let origin = i32_at(&header, 0x34);
    let length = i32_at(&header, 0x38);
    let position = i32_at(&header, 0x3c);
    if origin < 0 || position < origin || length < position || capacity < length {
        return Err("inconsistent stream bounds");
    }
    let size = (length - position) as usize;
    if !(MAGIC.len()..=MAX_BYTES).contains(&size) {
        return Err("invalid patch size");
    }
    let buffer = u64_at(&header, 0x20) as usize;
    let array = reader.read(buffer, 0x20).ok_or("unreadable byte array")?;
    if array.len() != 0x20 || u64_at(&array, 0) as usize != byte_class {
        return Err("not the verified byte array type");
    }
    if capacity as u64 > u64_at(&array, 0x18) {
        return Err("capacity exceeds array");
    }
    let start = buffer
        .checked_add(0x20)
        .and_then(|value| value.checked_add(position as usize))
        .ok_or("byte array address overflow")?;
    if reader.read(start, MAGIC.len()).as_deref() != Some(MAGIC.as_slice()) {
        return Err("unsupported patch magic");
    }
    let bytes = reader.read(start, size).ok_or("unreadable patch data")?;
    if bytes.len() != size {
        return Err("incomplete patch data");
    }
    Ok(bytes)
}

fn file_sha256(path: &Path) -> io::Result<String> {
    let mut file = File::open(path)?;
    let mut hash = Sha256::new();
    let mut buffer = [0; 65536];
    loop {
        let size = file.read(&mut buffer)?;
        if size == 0 {
            break;
        }
        hash.update(&buffer[..size]);
    }
    Ok(format!("{:x}", hash.finalize()))
}

fn wanted_directory() -> Option<PathBuf> {
    let path = std::env::var_os("LUNAGC_IFIX_DUMP_DIR")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .or_else(|| {
            let marker = std::env::temp_dir().join("lunagc-ifix-dump-dir.txt");
            if fs::metadata(&marker).ok()?.len() > 4096 {
                return None;
            }
            Some(PathBuf::from(fs::read_to_string(marker).ok()?.trim()))
        })?;
    path.is_absolute().then_some(path)
}

fn supported_client() -> Result<()> {
    let executable = std::env::current_exe()?;
    if file_sha256(&executable)? != EXE_SHA256 {
        bail!("unsupported executable SHA-256");
    }
    let metadata = executable
        .parent()
        .ok_or_else(|| anyhow::anyhow!("missing client directory"))?
        .join("YuanShen_Data/Native/Data/Metadata/global-metadata.dat");
    if file_sha256(&metadata)? != METADATA_SHA256 {
        bail!("unsupported metadata SHA-256");
    }
    if file_sha256(&metadata.with_file_name("startup-metadata.dat"))? != STARTUP_SHA256 {
        bail!("unsupported startup metadata SHA-256");
    }
    Ok(())
}

fn store_patch(directory: &Path, bytes: &[u8]) -> io::Result<PathBuf> {
    if !directory.is_absolute()
        || !(MAGIC.len()..=MAX_BYTES).contains(&bytes.len())
        || !bytes.starts_with(&MAGIC)
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid patch capture",
        ));
    }
    let digest = format!("{:x}", Sha256::digest(bytes));
    let path = directory.join(format!("ifix-{digest}.bin"));
    match OpenOptions::new().write(true).create_new(true).open(&path) {
        Ok(mut file) => {
            if let Err(error) = file.write_all(bytes).and_then(|_| file.sync_all()) {
                drop(file);
                let _ = fs::remove_file(&path);
                return Err(error);
            }
        }
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
            if fs::metadata(&path)?.len() != bytes.len() as u64 || file_sha256(&path)? != digest {
                return Err(io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    "capture filename contains different data",
                ));
            }
        }
        Err(error) => return Err(error),
    }
    Ok(path)
}

pub struct IfixCapture;

pub unsafe fn enable_when_requested(manager: &mut ModuleManager, executable: &'static str) {
    if wanted_directory().is_some() {
        manager.enable(MhyContext::<IfixCapture>::new(executable));
    }
}

impl MhyModule for MhyContext<IfixCapture> {
    unsafe fn init(&mut self) -> Result<()> {
        let Some(directory) = wanted_directory() else {
            return Ok(());
        };
        if let Err(error) = supported_client() {
            crate::plog!("IFix capture skipped: {error}");
            return Ok(());
        }
        let name = crate::util::wide_str(self.assembly_name);
        let Ok(module) = GetModuleHandleW(PCWSTR::from_raw(name.as_ptr())) else {
            return Ok(());
        };
        let base = module.0 as usize;
        if ProcessMemory
            .read(base + LOAD_RVA, LOAD_PREFIX.len())
            .as_deref()
            != Some(LOAD_PREFIX)
        {
            crate::plog!("IFix capture skipped: unsupported load signature");
            return Ok(());
        }
        if let Err(error) = fs::create_dir_all(&directory) {
            crate::plog!("IFix capture directory failed: {error}");
            return Ok(());
        }
        if CAPTURE
            .set(Capture {
                directory,
                game_base: base,
            })
            .is_err()
        {
            return Ok(());
        }
        if let Err(error) = self.interceptor.attach(base + LOAD_RVA, on_patch_load) {
            crate::plog!("IFix capture hook failed: {error}");
        } else {
            crate::plog!("IFix capture enabled for verified client");
        }
        if let Some(capture) = CAPTURE.get() {
            if let Err(error) = runtime::start(capture.directory.clone(), base) {
                crate::plog!("IFix runtime diagnostic could not start: {error}");
            }
        }
        Ok(())
    }
    unsafe fn de_init(&mut self) -> Result<()> {
        runtime::stop();
        Ok(())
    }
    fn get_module_type(&self) -> ModuleType {
        ModuleType::IfixCapture
    }
}

unsafe extern "win64" fn on_patch_load(registers: *mut Registers, _: usize) {
    let Some(capture) = CAPTURE.get() else {
        return;
    };
    if CAPTURE_COUNT.load(Ordering::Relaxed) >= MAX_FILES {
        return;
    }
    let result = std::panic::catch_unwind(|| {
        let reader = ProcessMemory;
        let stream_class = pointer(&reader, capture.game_base + STREAM_CLASS_RVA).unwrap_or(0);
        let byte_class = pointer(&reader, capture.game_base + BYTE_CLASS_RVA).unwrap_or(0);
        match patch_stream(&reader, (*registers).rcx as usize, stream_class, byte_class) {
            Ok(bytes) => {
                if CAPTURE_COUNT.fetch_add(1, Ordering::Relaxed) >= MAX_FILES {
                    return;
                }
                if CAPTURE_BYTES
                    .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |used| {
                        used.checked_add(bytes.len())
                            .filter(|total| *total <= TOTAL_BYTES)
                    })
                    .is_err()
                {
                    return;
                }
                match store_patch(&capture.directory, &bytes) {
                    Ok(path) => {
                        crate::plog!("IFix captured {} bytes at {}", bytes.len(), path.display())
                    }
                    Err(error) => crate::plog!("IFix capture write failed: {error}"),
                }
            }
            Err(reason) => {
                if REJECT_COUNT.fetch_add(1, Ordering::Relaxed) < MAX_FILES {
                    crate::plog!("IFix capture rejected: {reason}");
                }
            }
        }
    });
    if result.is_err() {
        crate::plog!("IFix capture callback failed");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const BASE: usize = 0x1000;
    const STREAM: usize = BASE + 0x100;
    const BUFFER: usize = BASE + 0x400;
    const STREAM_CLASS: usize = 0xabcdef;
    const BYTE_CLASS: usize = 0x123456;
    const PATCH: [u8; 12] = [0x07, 0x3d, 0x63, 0x7c, 0x85, 0x7b, 0xda, 0x66, 1, 2, 3, 4];

    struct Image(Vec<u8>);

    impl MemoryReader for Image {
        fn read(&self, address: usize, size: usize) -> Option<Vec<u8>> {
            let start = address.checked_sub(BASE)?;
            Some(self.0.get(start..start.checked_add(size)?)?.to_vec())
        }
    }

    impl Image {
        fn u64(&mut self, address: usize, value: u64) {
            self.0[address - BASE..address - BASE + 8].copy_from_slice(&value.to_le_bytes());
        }
        fn i32(&mut self, offset: usize, value: i32) {
            self.0[STREAM - BASE + offset..STREAM - BASE + offset + 4]
                .copy_from_slice(&value.to_le_bytes());
        }
        fn capture(&self) -> Result<Vec<u8>, &'static str> {
            patch_stream(self, STREAM, STREAM_CLASS, BYTE_CLASS)
        }
    }

    fn image(prefix: usize) -> Image {
        let mut image = Image(vec![0; 0x1000]);
        image.u64(STREAM, STREAM_CLASS as u64);
        image.u64(STREAM + 0x20, BUFFER as u64);
        image.u64(BUFFER, BYTE_CLASS as u64);
        image.u64(BUFFER + 0x18, (prefix + PATCH.len()) as u64);
        image.i32(0x30, (prefix + PATCH.len()) as i32); // Native _capacity.
        image.i32(0x34, prefix as i32); // Native _origin.
        image.i32(0x38, (prefix + PATCH.len()) as i32); // Native _length.
        image.i32(0x3c, prefix as i32); // Native _position.
        image.0[STREAM - BASE + 0x43] = 1;
        let start = BUFFER - BASE + 0x20 + prefix;
        image.0[start..start + PATCH.len()].copy_from_slice(&PATCH);
        image
    }

    #[test]
    fn the_current_native_stream_layout_exports_the_exact_bytes() {
        let image = image(0);
        let before = image.0.clone();
        assert_eq!(image.capture().unwrap(), PATCH);
        assert_eq!(image.0, before);
    }

    #[test]
    fn an_origin_and_position_are_respected_without_rewinding() {
        let image = image(7);
        let before = image.0.clone();
        assert_eq!(image.capture().unwrap(), PATCH);
        assert_eq!(image.0, before);
    }

    #[test]
    fn missing_and_wrong_runtime_types_are_rejected() {
        let mut image = image(0);
        assert!(patch_stream(&image, 0, STREAM_CLASS, BYTE_CLASS).is_err());
        assert!(patch_stream(&image, STREAM, 0, BYTE_CLASS).is_err());
        image.u64(STREAM, BYTE_CLASS as u64);
        assert!(image.capture().is_err());
        image.u64(STREAM, STREAM_CLASS as u64);
        image.u64(BUFFER, STREAM_CLASS as u64);
        assert!(image.capture().is_err());
    }

    #[test]
    fn closed_and_inconsistent_stream_ranges_are_rejected() {
        for (offset, value) in [
            (0x34, -1),
            (0x3c, -1),
            (0x3c, 13),
            (0x34, 1),
            (0x38, 13),
            (0x30, -1),
            (0x30, 100),
        ] {
            let mut image = image(0);
            image.i32(offset, value);
            assert!(image.capture().is_err(), "offset {offset:x} value {value}");
        }
        let mut image = image(0);
        image.0[STREAM - BASE + 0x43] = 0;
        assert!(image.capture().is_err());
    }

    #[test]
    fn unrecognized_data_and_unreadable_memory_are_rejected() {
        let mut image = image(0);
        image.0[BUFFER - BASE + 0x20] ^= 1;
        assert!(image.capture().is_err());
        image.u64(STREAM + 0x20, 0);
        assert!(image.capture().is_err());
        assert!(patch_stream(&image, usize::MAX, STREAM_CLASS, BYTE_CLASS).is_err());
    }

    #[test]
    fn declared_large_arrays_do_not_turn_into_unbounded_reads() {
        let mut image = image(0);
        image.u64(BUFFER + 0x18, i32::MAX as u64);
        image.i32(0x30, i32::MAX);
        image.i32(0x38, i32::MAX);
        assert!(image.capture().is_err());
    }

    fn temporary_directory(label: &str) -> PathBuf {
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "lunagc-ifix-test-{label}-{}-{stamp}",
            std::process::id()
        ));
        fs::create_dir(&path).unwrap();
        path
    }

    #[test]
    fn digest_files_are_reusable_without_replacing_conflicting_data() {
        let directory = temporary_directory("files");
        let path = store_patch(&directory, &PATCH).unwrap();
        assert_eq!(fs::read(&path).unwrap(), PATCH);
        assert_eq!(store_patch(&directory, &PATCH).unwrap(), path);
        fs::write(&path, b"keep this file").unwrap();
        assert!(store_patch(&directory, &PATCH).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"keep this file");
        assert!(store_patch(&directory, b"not compiled patch bytes").is_err());
        assert!(store_patch(Path::new("relative-directory"), &PATCH).is_err());
        fs::remove_file(path).unwrap();
        fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn source_hashing_matches_a_known_digest_without_changing_the_file() {
        let directory = temporary_directory("hash");
        let path = directory.join("source.bin");
        fs::write(&path, b"abc").unwrap();
        assert_eq!(
            file_sha256(&path).unwrap(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(fs::read(&path).unwrap(), b"abc");
        fs::remove_file(path).unwrap();
        fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn process_reads_copy_valid_memory_and_reject_bad_addresses() {
        let source = [1u8, 2, 3, 4];
        assert_eq!(
            ProcessMemory.read(source.as_ptr() as usize, 4).unwrap(),
            source
        );
        assert!(ProcessMemory.read(0, 4).is_none());
        assert!(ProcessMemory.read(usize::MAX, 4).is_none());
        assert!(ProcessMemory
            .read(source.as_ptr() as usize, MAX_BYTES + 1)
            .is_none());
    }
}
