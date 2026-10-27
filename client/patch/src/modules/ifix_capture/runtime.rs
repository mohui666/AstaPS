//! Read-only, bounded diagnostics for an IFix wrapper that may already have been initialized.

use super::{i32_at, pointer, u64_at, MemoryReader, ProcessMemory};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, OpenOptions},
    io::{self, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const FUEL_FLAG: usize = 0x5780c6b;
const RUNTIME_ROOT: usize = 0x5783de8;
const STATIC_STORAGE: usize = 0x256d0;
const FUEL_WRAPPER: usize = 0x57fcf8;
const FUEL_ENTRY: usize = 0x7b469f0;
const FUEL_PREFIX: &[u8] = &[
    0x56, 0x48, 0x83, 0xec, 0x60, 0x80, 0x3d, 0x6f, 0xa2, 0xc3, 0xfd, 0x00, 0x75, 0x06, 0x48, 0x83,
    0xc4, 0x60, 0x5e, 0xc3, 0x4c, 0x89, 0xc6, 0x49, 0x89, 0xd0, 0x48, 0x89, 0xca, 0x48, 0x8b, 0x0d,
];
const MAX_ARRAY_LENGTH: u64 = 1_000_000;
const MAX_RECORDS: usize = 32;
const MAX_RECORD_BYTES: usize = 32 * 1024;
const ATTEMPTS: usize = 300;
static RUNNING: AtomicBool = AtomicBool::new(false);
static STOP: AtomicBool = AtomicBool::new(false);

fn address(base: usize, offset: usize) -> Result<usize, &'static str> {
    base.checked_add(offset).ok_or("address overflow")
}

fn exact(
    reader: &impl MemoryReader,
    address: usize,
    count: usize,
) -> Result<Vec<u8>, &'static str> {
    reader
        .read(address, count)
        .filter(|data| data.len() == count)
        .ok_or("unreadable memory")
}

fn nonzero_pointer(reader: &impl MemoryReader, address: usize) -> Result<usize, &'static str> {
    pointer(reader, address)
        .filter(|value| *value != 0)
        .ok_or("runtime publication pending")
}

fn array_length(reader: &impl MemoryReader, array: usize) -> Result<Value, &'static str> {
    if array == 0 {
        return Ok(Value::Null);
    }
    let bytes = exact(reader, array, 0x20)?;
    if u64_at(&bytes, 0) == 0 {
        return Err("missing managed array class");
    }
    let length = u64_at(&bytes, 0x18);
    if length > MAX_ARRAY_LENGTH {
        return Err("managed array exceeds diagnostic bound");
    }
    Ok(json!(length))
}

fn probe(reader: &impl MemoryReader, base: usize) -> Result<Value, &'static str> {
    let flag_address = address(base, FUEL_FLAG)?;
    let flag = exact(reader, flag_address, 1)?[0];
    if flag == 0 {
        return Ok(json!({"status":"inactive","flag":0}));
    }
    let root_address = address(base, RUNTIME_ROOT)?;
    let root = nonzero_pointer(reader, root_address)?;
    let storage_address = address(root, STATIC_STORAGE)?;
    let storage = nonzero_pointer(reader, storage_address)?;
    let wrapper_address = address(storage, FUEL_WRAPPER)?;
    let wrapper = nonzero_pointer(reader, wrapper_address)?;
    let wrapper_data = exact(reader, wrapper, 0x28)?;
    let vm = u64_at(&wrapper_data, 0x10) as usize;
    let method = i32_at(&wrapper_data, 0x20);
    if u64_at(&wrapper_data, 0) == 0 || vm == 0 || method < 0 {
        return Err("invalid IFix wrapper");
    }
    let vm_data = exact(reader, vm, 0x90)?;
    if u64_at(&vm_data, 0) == 0 {
        return Err("missing VM class");
    }
    let mut arrays = serde_json::Map::new();
    for (name, offset) in [
        ("anonymousStoreyInfos", 0x20),
        ("externTypes", 0x28),
        ("cctors", 0x30),
        ("exceptionHandlers", 0x38),
        ("internStrings", 0x48),
        ("externMethods", 0x50),
        ("externInvokers", 0x60),
        ("staticFieldTypes", 0x68),
        ("fieldInfos", 0x70),
    ] {
        arrays.insert(
            name.to_owned(),
            array_length(reader, u64_at(&vm_data, offset) as usize)?,
        );
    }
    // Hot reload can publish another wrapper while the metadata is being read.
    if exact(reader, flag_address, 1)?[0] != flag
        || nonzero_pointer(reader, root_address)? != root
        || nonzero_pointer(reader, storage_address)? != storage
        || nonzero_pointer(reader, wrapper_address)? != wrapper
        || exact(reader, wrapper, 0x28)? != wrapper_data
        || exact(reader, vm, 0x90)? != vm_data
    {
        return Err("runtime changed while reading");
    }
    let codes = u64_at(&vm_data, 0x80);
    Ok(json!({
        "status":if codes == 0 { "waiting-for-code" } else { "active" },
        "flag":flag,"root":format!("{root:#x}"),"storage":format!("{storage:#x}"),
        "wrapper":format!("{wrapper:#x}"),"wrapperClass":format!("{:#x}",u64_at(&wrapper_data,0)),
        "vm":format!("{vm:#x}"),"vmClass":format!("{:#x}",u64_at(&vm_data,0)),
        "methodId":method,"unmanagedCodes":format!("{codes:#x}"),
        "patchedArrayLength":i32_at(&vm_data,0x88),"arrays":arrays,
        "methodBodiesRead":false,"arrayElementsRead":false
    }))
}

fn verified_entry(reader: &impl MemoryReader, base: usize) -> bool {
    address(base, FUEL_ENTRY)
        .ok()
        .and_then(|entry| reader.read(entry, FUEL_PREFIX.len()))
        .as_deref()
        == Some(FUEL_PREFIX)
}

fn store_record(directory: &Path, bytes: &[u8]) -> io::Result<PathBuf> {
    if !directory.is_absolute() || bytes.is_empty() || bytes.len() > MAX_RECORD_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid runtime record",
        ));
    }
    let digest = format!("{:x}", Sha256::digest(bytes));
    let path = directory.join(format!("runtime-{}-{digest}.json", std::process::id()));
    match OpenOptions::new().write(true).create_new(true).open(&path) {
        Ok(mut file) => {
            if let Err(error) = file.write_all(bytes).and_then(|_| file.sync_all()) {
                drop(file);
                let _ = fs::remove_file(&path);
                return Err(error);
            }
        }
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
            if fs::metadata(&path)?.len() != bytes.len() as u64 || fs::read(&path)? != bytes {
                return Err(io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    "runtime record conflict",
                ));
            }
        }
        Err(error) => return Err(error),
    }
    Ok(path)
}

#[derive(Default)]
struct Records {
    previous: Option<Value>,
    written: usize,
}

impl Records {
    fn observe(
        &mut self,
        directory: &Path,
        base: usize,
        state: Value,
    ) -> io::Result<Option<PathBuf>> {
        if self.written >= MAX_RECORDS || self.previous.as_ref() == Some(&state) {
            return Ok(None);
        }
        let record = json!({
            "schema":1,"pid":std::process::id(),"moduleBase":format!("{base:#x}"),
            "observedAtUnixMillis":SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis(),
            "sourceSha256":{"executable":super::EXE_SHA256,"metadata":super::METADATA_SHA256,"startup":super::STARTUP_SHA256},
            "fuelAction":state
        });
        let bytes = serde_json::to_vec_pretty(&record)?;
        let path = store_record(directory, &bytes)?;
        self.previous = Some(state);
        self.written += 1;
        Ok(Some(path))
    }
}

pub(super) fn start(directory: PathBuf, base: usize) -> io::Result<()> {
    if !verified_entry(&ProcessMemory, base) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "unsupported fuel entry signature",
        ));
    }
    if RUNNING
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return Ok(());
    }
    STOP.store(false, Ordering::Release);
    let spawned = std::thread::Builder::new()
        .name("lunagc-ifix-probe".to_owned())
        .spawn(move || {
            let result = std::panic::catch_unwind(|| {
                let mut records = Records::default();
                for _ in 0..ATTEMPTS {
                    if STOP.load(Ordering::Acquire) || records.written >= MAX_RECORDS {
                        break;
                    }
                    let state = probe(&ProcessMemory, base)
                        .unwrap_or_else(|error| json!({"status":"unavailable","error":error}));
                    match records.observe(&directory, base, state) {
                        Ok(Some(path)) => {
                            crate::plog!("IFix runtime state recorded at {}", path.display())
                        }
                        Ok(None) => {}
                        Err(error) => {
                            crate::plog!("IFix runtime record failed: {error}");
                            break;
                        }
                    }
                    std::thread::sleep(Duration::from_secs(1));
                }
            });
            if result.is_err() {
                crate::plog!("IFix runtime diagnostic stopped after an internal error");
            }
            RUNNING.store(false, Ordering::Release);
            crate::plog!("IFix runtime diagnostic finished");
        });
    match spawned {
        Ok(_) => Ok(()),
        Err(error) => {
            RUNNING.store(false, Ordering::Release);
            Err(error)
        }
    }
}

pub(super) fn stop() {
    STOP.store(true, Ordering::Release);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{cell::RefCell, collections::BTreeMap};

    const BASE: usize = 0x140000000;
    const ROOT: usize = 0x200000;
    const STORAGE: usize = 0x400000;
    const WRAPPER: usize = 0xc00000;
    const VM: usize = 0xd00000;
    const ARRAY: usize = 0xe00000;

    struct Image {
        segments: BTreeMap<usize, Vec<u8>>,
        reads: RefCell<Vec<(usize, usize)>>,
    }

    impl MemoryReader for Image {
        fn read(&self, address: usize, size: usize) -> Option<Vec<u8>> {
            self.reads.borrow_mut().push((address, size));
            let (&start, bytes) = self.segments.range(..=address).next_back()?;
            let offset = address.checked_sub(start)?;
            Some(bytes.get(offset..offset.checked_add(size)?)?.to_vec())
        }
    }

    impl Image {
        fn bytes(&mut self, address: usize, bytes: &[u8]) {
            self.segments.insert(address, bytes.to_vec());
        }

        fn pointer(&mut self, address: usize, pointer: usize) {
            self.bytes(address, &(pointer as u64).to_le_bytes());
        }
    }

    fn fixture() -> Image {
        let mut image = Image {
            segments: BTreeMap::new(),
            reads: RefCell::new(Vec::new()),
        };
        image.bytes(BASE + FUEL_FLAG, &[1]);
        image.pointer(BASE + RUNTIME_ROOT, ROOT);
        image.pointer(ROOT + STATIC_STORAGE, STORAGE);
        image.pointer(STORAGE + FUEL_WRAPPER, WRAPPER);
        let mut wrapper = [0; 0x28];
        wrapper[..8].copy_from_slice(&0xa00000u64.to_le_bytes());
        wrapper[0x10..0x18].copy_from_slice(&(VM as u64).to_le_bytes());
        wrapper[0x18..0x20].copy_from_slice(&0xbeef0000u64.to_le_bytes()); // anonObj is not the VM.
        wrapper[0x20..0x24].copy_from_slice(&7i32.to_le_bytes());
        image.bytes(WRAPPER, &wrapper);
        let mut vm = [0; 0x90];
        vm[..8].copy_from_slice(&0xb00000u64.to_le_bytes());
        vm[0x38..0x40].copy_from_slice(&(ARRAY as u64).to_le_bytes());
        vm[0x80..0x88].copy_from_slice(&0xf00000u64.to_le_bytes());
        vm[0x88..0x8c].copy_from_slice(&3i32.to_le_bytes()); // Not assumed to be method count.
        image.bytes(VM, &vm);
        let mut array = [0; 0x20];
        array[..8].copy_from_slice(&0x1100000u64.to_le_bytes());
        array[0x18..0x20].copy_from_slice(&12u64.to_le_bytes());
        image.bytes(ARRAY, &array);
        image
    }

    #[test]
    fn an_inactive_flag_never_dereferences_the_uninitialized_static_storage() {
        let mut image = fixture();
        image.bytes(BASE + FUEL_FLAG, &[0]);
        assert_eq!(
            probe(&image, BASE).unwrap(),
            json!({"status":"inactive","flag":0})
        );
        assert_eq!(*image.reads.borrow(), vec![(BASE + FUEL_FLAG, 1)]);
    }

    #[test]
    fn the_verified_wrapper_layout_reports_vm_metadata_without_reading_code_or_strings() {
        let image = fixture();
        let before = image.segments.clone();
        let result = probe(&image, BASE).unwrap();
        assert_eq!(result["status"], "active");
        assert_eq!(result["vm"], "0xd00000");
        assert_eq!(result["methodId"], 7);
        assert_eq!(result["unmanagedCodes"], "0xf00000");
        assert_eq!(result["patchedArrayLength"], 3);
        assert_eq!(result["arrays"]["exceptionHandlers"], 12);
        assert_eq!(before, image.segments);
        assert!(!image
            .reads
            .borrow()
            .iter()
            .any(|(address, _)| *address == 0xbeef0000 || *address == 0xf00000));
    }

    #[test]
    fn a_nonzero_native_flag_is_not_narrowed_to_one() {
        let mut image = fixture();
        image.bytes(BASE + FUEL_FLAG, &[2]);
        assert_eq!(probe(&image, BASE).unwrap()["flag"], 2);
    }

    #[test]
    fn publication_in_progress_is_a_retryable_error() {
        for address in [
            BASE + RUNTIME_ROOT,
            ROOT + STATIC_STORAGE,
            STORAGE + FUEL_WRAPPER,
        ] {
            let mut image = fixture();
            image.pointer(address, 0);
            assert!(probe(&image, BASE).is_err());
        }
    }

    #[test]
    fn missing_wrapper_and_vm_headers_are_rejected() {
        for address in [WRAPPER, VM, ARRAY] {
            let mut image = fixture();
            image.segments.remove(&address);
            assert!(probe(&image, BASE).is_err());
        }
    }

    #[test]
    fn null_vm_and_negative_method_ids_are_rejected() {
        let mut image = fixture();
        image.segments.get_mut(&WRAPPER).unwrap()[0x10..0x18].fill(0);
        assert!(probe(&image, BASE).is_err());
        let mut image = fixture();
        image.segments.get_mut(&WRAPPER).unwrap()[0x20..0x24]
            .copy_from_slice(&(-1i32).to_le_bytes());
        assert!(probe(&image, BASE).is_err());
    }

    #[test]
    fn null_managed_arrays_and_a_vm_waiting_for_code_are_reported() {
        let mut image = fixture();
        image.segments.get_mut(&VM).unwrap()[0x38..0x40].fill(0);
        image.segments.get_mut(&VM).unwrap()[0x80..0x88].fill(0);
        let result = probe(&image, BASE).unwrap();
        assert_eq!(result["status"], "waiting-for-code");
        assert!(result["arrays"]["exceptionHandlers"].is_null());
    }

    #[test]
    fn excessive_managed_array_lengths_are_rejected() {
        let mut image = fixture();
        image.segments.get_mut(&ARRAY).unwrap()[0x18..0x20]
            .copy_from_slice(&u64::MAX.to_le_bytes());
        assert!(probe(&image, BASE).is_err());
    }

    #[test]
    fn a_short_memory_read_cannot_be_parsed_as_a_complete_header() {
        struct Short;
        impl MemoryReader for Short {
            fn read(&self, _: usize, _: usize) -> Option<Vec<u8>> {
                Some(vec![1])
            }
        }
        assert!(probe(&Short, BASE).is_err());
    }

    #[test]
    fn overflowing_module_addresses_are_rejected() {
        assert!(probe(&fixture(), usize::MAX).is_err());
    }

    #[test]
    fn a_wrapper_changed_during_the_read_is_not_published_as_a_coherent_snapshot() {
        struct Racing {
            image: Image,
            reads: RefCell<usize>,
        }
        impl MemoryReader for Racing {
            fn read(&self, address: usize, size: usize) -> Option<Vec<u8>> {
                if address == STORAGE + FUEL_WRAPPER {
                    *self.reads.borrow_mut() += 1;
                    if *self.reads.borrow() > 1 {
                        return Some(0usize.to_le_bytes().to_vec());
                    }
                }
                self.image.read(address, size)
            }
        }
        assert!(probe(
            &Racing {
                image: fixture(),
                reads: RefCell::new(0)
            },
            BASE
        )
        .is_err());
    }

    #[test]
    fn the_exact_array_bound_is_accepted_without_reading_array_elements() {
        let mut image = fixture();
        image.segments.get_mut(&ARRAY).unwrap()[0x18..0x20]
            .copy_from_slice(&MAX_ARRAY_LENGTH.to_le_bytes());
        assert_eq!(
            probe(&image, BASE).unwrap()["arrays"]["exceptionHandlers"],
            MAX_ARRAY_LENGTH
        );
        assert!(!image
            .reads
            .borrow()
            .iter()
            .any(|(address, _)| *address >= ARRAY + 0x20 && *address < ARRAY + 0x100));
    }

    #[test]
    fn missing_object_classes_are_rejected() {
        for address in [WRAPPER, VM, ARRAY] {
            let mut image = fixture();
            image.segments.get_mut(&address).unwrap()[..8].fill(0);
            assert!(probe(&image, BASE).is_err());
        }
    }

    #[test]
    fn the_live_entry_must_match_the_pinned_signature_before_sampling_starts() {
        let mut image = fixture();
        assert!(!verified_entry(&image, BASE));
        image.bytes(BASE + FUEL_ENTRY, FUEL_PREFIX);
        assert!(verified_entry(&image, BASE));
        image.segments.get_mut(&(BASE + FUEL_ENTRY)).unwrap()[0] ^= 1;
        assert!(!verified_entry(&image, BASE));
        assert!(!verified_entry(&image, usize::MAX));
    }

    fn directory(label: &str) -> PathBuf {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "lunagc-runtime-{label}-{}-{stamp}",
            std::process::id()
        ));
        fs::create_dir(&path).unwrap();
        path
    }

    fn clean(directory: PathBuf) {
        assert_eq!(directory.parent(), Some(std::env::temp_dir().as_path()));
        for entry in fs::read_dir(&directory).unwrap() {
            fs::remove_file(entry.unwrap().path()).unwrap();
        }
        fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn unchanged_states_are_written_once_and_new_states_preserve_the_previous_record() {
        let directory = directory("states");
        let mut records = Records::default();
        let first = records
            .observe(&directory, BASE, json!({"status":"inactive"}))
            .unwrap()
            .unwrap();
        let before = fs::read(&first).unwrap();
        assert!(records
            .observe(&directory, BASE, json!({"status":"inactive"}))
            .unwrap()
            .is_none());
        let second = records
            .observe(&directory, BASE, json!({"status":"active"}))
            .unwrap()
            .unwrap();
        assert_ne!(first, second);
        assert_eq!(fs::read(&first).unwrap(), before);
        assert_eq!(fs::read_dir(&directory).unwrap().count(), 2);
        let record: Value = serde_json::from_slice(&before).unwrap();
        assert_eq!(record["pid"], std::process::id());
        assert_eq!(
            record["sourceSha256"]["executable"],
            super::super::EXE_SHA256
        );
        clean(directory);
    }

    #[test]
    fn the_record_budget_and_size_limit_cannot_expand_with_more_samples() {
        let directory = directory("bounds");
        let mut records = Records::default();
        for id in 0..MAX_RECORDS {
            assert!(records
                .observe(&directory, BASE, json!({"sample":id}))
                .unwrap()
                .is_some());
        }
        assert!(records
            .observe(&directory, BASE, json!({"sample":MAX_RECORDS}))
            .unwrap()
            .is_none());
        assert_eq!(fs::read_dir(&directory).unwrap().count(), MAX_RECORDS);
        assert!(store_record(&directory, &vec![0; MAX_RECORD_BYTES + 1]).is_err());
        assert!(store_record(Path::new("relative-directory"), b"{}").is_err());
        assert!(store_record(&directory, &[]).is_err());
        clean(directory);
    }

    #[test]
    fn an_existing_record_is_reused_only_when_its_bytes_match() {
        let directory = directory("conflict");
        let bytes = b"{\"sample\":1}";
        let path = store_record(&directory, bytes).unwrap();
        assert_eq!(store_record(&directory, bytes).unwrap(), path);
        let changed = vec![b'x'; bytes.len()];
        fs::write(&path, &changed).unwrap();
        assert!(store_record(&directory, bytes).is_err());
        assert_eq!(fs::read(path).unwrap(), changed);
        clean(directory);
    }
}
