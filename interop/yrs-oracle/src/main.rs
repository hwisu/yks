use std::env;
use std::error::Error;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use yrs::updates::decoder::Decode;
use yrs::{
    Any, Array, ClientID, Doc, GetString, Map, MapPrelim, MapRef, OffsetKind, Options, Out,
    ReadTxn, StateVector, Text, Transact, Update,
};

type OracleResult<T> = Result<T, Box<dyn Error>>;

const TEXT_BASE_V1: &str = "text-base-v1.bin";
const TEXT_DELETE_V1: &str = "text-delete-v1.bin";
const TEXT_BASE_V2: &str = "text-base-v2.bin";
const TEXT_DELETE_V2: &str = "text-delete-v2.bin";
const HIGH_CLIENT_V1: &str = "high-client-v1.bin";
const ARRAY_MAP_V1: &str = "array-map-v1.bin";
const NESTED_MAP_V1: &str = "nested-map-v1.bin";
const CONCURRENT_ARRAY_BASE_V1: &str = "concurrent-array-base-v1.bin";
const CONCURRENT_ARRAY_X_V1: &str = "concurrent-array-x-v1.bin";
const CONCURRENT_ARRAY_Y_V1: &str = "concurrent-array-y-v1.bin";
const SKIP_MIDDLE_ANCHOR_V1: &str = "skip-middle-anchor-v1.bin";
const SKIP_MIDDLE_C0_V1: &str = "skip-middle-c0-v1.bin";
const SKIP_MIDDLE_C1_V1: &str = "skip-middle-c1-v1.bin";
const SKIP_MIDDLE_C2_V1: &str = "skip-middle-c2-v1.bin";
const SKIP_MIDDLE_C3_V1: &str = "skip-middle-c3-v1.bin";

const TEXT_ROOT: &str = "body";
const TEXT_BASE: &str = "A😀BC";
const TEXT_AFTER_DELETE: &str = "A😀C";
const HIGH_CLIENT: u64 = 9_007_199_254_740_000;
const HIGH_ROOT: &str = "body";
const HIGH_VALUE: &str = "high-client";

#[derive(Clone, Copy, Debug)]
enum WireVersion {
    V1,
    V2,
}

#[derive(Debug)]
struct FixtureBundle {
    text_base_v1: Vec<u8>,
    text_delete_v1: Vec<u8>,
    text_base_v2: Vec<u8>,
    text_delete_v2: Vec<u8>,
    high_client_v1: Vec<u8>,
    array_map_v1: Vec<u8>,
    nested_map_v1: Vec<u8>,
    concurrent_array_base_v1: Vec<u8>,
    concurrent_array_x_v1: Vec<u8>,
    concurrent_array_y_v1: Vec<u8>,
}

#[derive(Debug)]
struct MiddleSkipBundle {
    anchor: Vec<u8>,
    c0: Vec<u8>,
    c1: Vec<u8>,
    c2: Vec<u8>,
    c3: Vec<u8>,
}

#[derive(Debug)]
struct AppliedTextState {
    value: String,
    state: StateVector,
    has_missing_updates: bool,
}

impl MiddleSkipBundle {
    fn entries(&self) -> [(&'static str, &[u8]); 5] {
        [
            (SKIP_MIDDLE_ANCHOR_V1, &self.anchor),
            (SKIP_MIDDLE_C0_V1, &self.c0),
            (SKIP_MIDDLE_C1_V1, &self.c1),
            (SKIP_MIDDLE_C2_V1, &self.c2),
            (SKIP_MIDDLE_C3_V1, &self.c3),
        ]
    }

    fn write_to(&self, directory: &Path) -> OracleResult<()> {
        fs::create_dir_all(directory)?;
        for (name, bytes) in self.entries() {
            fs::write(directory.join(name), bytes)?;
        }
        Ok(())
    }
}

impl FixtureBundle {
    fn entries(&self) -> [(&'static str, &[u8]); 10] {
        [
            (TEXT_BASE_V1, &self.text_base_v1),
            (TEXT_DELETE_V1, &self.text_delete_v1),
            (TEXT_BASE_V2, &self.text_base_v2),
            (TEXT_DELETE_V2, &self.text_delete_v2),
            (HIGH_CLIENT_V1, &self.high_client_v1),
            (ARRAY_MAP_V1, &self.array_map_v1),
            (NESTED_MAP_V1, &self.nested_map_v1),
            (CONCURRENT_ARRAY_BASE_V1, &self.concurrent_array_base_v1),
            (CONCURRENT_ARRAY_X_V1, &self.concurrent_array_x_v1),
            (CONCURRENT_ARRAY_Y_V1, &self.concurrent_array_y_v1),
        ]
    }

    fn write_to(&self, directory: &Path) -> OracleResult<()> {
        fs::create_dir_all(directory)?;
        for (name, bytes) in self.entries() {
            fs::write(directory.join(name), bytes)?;
        }
        Ok(())
    }

    fn read_from(directory: &Path) -> OracleResult<Self> {
        let read = |name: &str| -> OracleResult<Vec<u8>> {
            let path = directory.join(name);
            fs::read(&path).map_err(|error| {
                io::Error::new(
                    error.kind(),
                    format!(
                        "failed to read required fixture {}: {error}",
                        path.display()
                    ),
                )
                .into()
            })
        };

        Ok(Self {
            text_base_v1: read(TEXT_BASE_V1)?,
            text_delete_v1: read(TEXT_DELETE_V1)?,
            text_base_v2: read(TEXT_BASE_V2)?,
            text_delete_v2: read(TEXT_DELETE_V2)?,
            high_client_v1: read(HIGH_CLIENT_V1)?,
            array_map_v1: read(ARRAY_MAP_V1)?,
            nested_map_v1: read(NESTED_MAP_V1)?,
            concurrent_array_base_v1: read(CONCURRENT_ARRAY_BASE_V1)?,
            concurrent_array_x_v1: read(CONCURRENT_ARRAY_X_V1)?,
            concurrent_array_y_v1: read(CONCURRENT_ARRAY_Y_V1)?,
        })
    }
}

fn oracle_doc(client_id: u64) -> Doc {
    let mut options = Options::with_client_id(ClientID::new(client_id));
    options.offset_kind = OffsetKind::Utf16;
    options.skip_gc = true;
    options.cleanup_formatting = true;
    Doc::with_options(options)
}

fn failure<T>(message: impl Into<String>) -> OracleResult<T> {
    Err(io::Error::other(message.into()).into())
}

fn require(condition: bool, message: impl Into<String>) -> OracleResult<()> {
    if condition {
        Ok(())
    } else {
        failure(message)
    }
}

fn apply(doc: &Doc, bytes: &[u8], version: WireVersion) -> OracleResult<()> {
    let update = match version {
        WireVersion::V1 => Update::decode_v1(bytes),
        WireVersion::V2 => Update::decode_v2(bytes),
    }
    .map_err(|error| io::Error::other(format!("failed to decode {version:?} update: {error}")))?;
    doc.transact_mut()
        .apply_update(update)
        .map_err(|error| io::Error::other(format!("failed to apply {version:?} update: {error}")))
        .map_err(Into::into)
}

fn generate_bundle() -> OracleResult<FixtureBundle> {
    let text_doc = oracle_doc(1);
    let text = text_doc.get_or_insert_text(TEXT_ROOT);
    let (text_base_v1, text_base_v2) = {
        let mut txn = text_doc.transact_mut();
        text.insert(&mut txn, 0, TEXT_BASE);
        (txn.encode_update_v1(), txn.encode_update_v2())
    };
    let (text_delete_v1, text_delete_v2) = {
        let mut txn = text_doc.transact_mut();
        text.remove_range(&mut txn, 3, 1);
        (txn.encode_update_v1(), txn.encode_update_v2())
    };

    let high_doc = oracle_doc(HIGH_CLIENT);
    let high_text = high_doc.get_or_insert_text(HIGH_ROOT);
    let high_client_v1 = {
        let mut txn = high_doc.transact_mut();
        high_text.insert(&mut txn, 0, HIGH_VALUE);
        txn.encode_update_v1()
    };

    let array_map_doc = oracle_doc(1);
    let items = array_map_doc.get_or_insert_array("items");
    let meta = array_map_doc.get_or_insert_map("meta");
    let array_map_v1 = {
        let mut txn = array_map_doc.transact_mut();
        items.insert(&mut txn, 0, Any::String("a".into()));
        items.insert(&mut txn, 1, Any::Number(42.0));
        items.insert(&mut txn, 2, Any::Bool(true));
        items.insert(&mut txn, 3, Any::Null);
        items.insert(&mut txn, 4, Any::Buffer(vec![1, 2].into()));
        meta.insert(&mut txn, "title", "hello");
        meta.insert(&mut txn, "count", Any::Number(2.0));
        txn.encode_update_v1()
    };

    let nested_map_doc = oracle_doc(1);
    let root = nested_map_doc.get_or_insert_map("root");
    let nested_map_v1 = {
        let mut txn = nested_map_doc.transact_mut();
        let profile = MapPrelim::from([
            ("name".to_owned(), "Ada".to_owned()),
            ("city".to_owned(), "Seoul".to_owned()),
        ]);
        root.insert(&mut txn, "profile", profile);
        txn.encode_update_v1()
    };

    let base_doc = oracle_doc(1);
    let base_array = base_doc.get_or_insert_array("letters");
    let concurrent_array_base_v1 = {
        let mut txn = base_doc.transact_mut();
        base_array.insert_range(&mut txn, 0, ["a", "b"]);
        txn.encode_update_v1()
    };

    let x_doc = oracle_doc(2);
    let x_array = x_doc.get_or_insert_array("letters");
    apply(&x_doc, &concurrent_array_base_v1, WireVersion::V1)?;
    let concurrent_array_x_v1 = {
        let mut txn = x_doc.transact_mut();
        x_array.insert(&mut txn, 1, "X");
        txn.encode_update_v1()
    };

    let y_doc = oracle_doc(3);
    let y_array = y_doc.get_or_insert_array("letters");
    apply(&y_doc, &concurrent_array_base_v1, WireVersion::V1)?;
    let concurrent_array_y_v1 = {
        let mut txn = y_doc.transact_mut();
        y_array.insert(&mut txn, 1, "Y");
        txn.encode_update_v1()
    };

    Ok(FixtureBundle {
        text_base_v1,
        text_delete_v1,
        text_base_v2,
        text_delete_v2,
        high_client_v1,
        array_map_v1,
        nested_map_v1,
        concurrent_array_base_v1,
        concurrent_array_x_v1,
        concurrent_array_y_v1,
    })
}

fn verify_text_pair(base: &[u8], delete: &[u8], version: WireVersion) -> OracleResult<()> {
    let causal = oracle_doc(500);
    let causal_text = causal.get_or_insert_text(TEXT_ROOT);
    apply(&causal, base, version)?;
    require(
        causal_text.get_string(&causal.transact()) == TEXT_BASE,
        format!("{version:?} text base update did not produce {TEXT_BASE:?}"),
    )?;
    apply(&causal, delete, version)?;
    require(
        causal_text.get_string(&causal.transact()) == TEXT_AFTER_DELETE,
        format!("{version:?} causal delete did not produce {TEXT_AFTER_DELETE:?}"),
    )?;

    let delete_first = oracle_doc(501);
    let delete_first_text = delete_first.get_or_insert_text(TEXT_ROOT);
    apply(&delete_first, delete, version)?;
    require(
        delete_first_text
            .get_string(&delete_first.transact())
            .is_empty(),
        format!("{version:?} delete-only state unexpectedly has visible content"),
    )?;
    apply(&delete_first, base, version)?;
    require(
        delete_first_text.get_string(&delete_first.transact()) == TEXT_AFTER_DELETE,
        format!("{version:?} delete-first delivery did not produce {TEXT_AFTER_DELETE:?}"),
    )?;

    for (label, doc) in [("causal", causal), ("delete-first", delete_first)] {
        let state = doc.transact().state_vector();
        require(
            state.len() == 1 && state.get(&ClientID::new(1)) == 5,
            format!("{version:?} {label} text state vector is {state:?}, expected {{1: 5}}"),
        )?;
    }
    Ok(())
}

fn expect_string(value: Option<Out>, expected: &str, context: &str) -> OracleResult<()> {
    match value {
        Some(Out::Any(Any::String(actual))) if actual.as_ref() == expected => Ok(()),
        other => failure(format!(
            "{context} produced {other:?}; expected string {expected:?}"
        )),
    }
}

fn verify_high_client(update: &[u8]) -> OracleResult<()> {
    let doc = oracle_doc(501);
    let text = doc.get_or_insert_text(HIGH_ROOT);
    apply(&doc, update, WireVersion::V1)?;
    let txn = doc.transact();
    require(
        text.get_string(&txn) == HIGH_VALUE,
        "53-bit client fixture produced unexpected text",
    )?;
    let expected_clock = HIGH_VALUE.encode_utf16().count() as u32;
    require(
        txn.state_vector().len() == 1
            && txn.state_vector().get(&ClientID::new(HIGH_CLIENT)) == expected_clock,
        "53-bit client ID or clock was not preserved in the state vector",
    )
}

fn verify_array_map(update: &[u8]) -> OracleResult<()> {
    let doc = oracle_doc(502);
    let items = doc.get_or_insert_array("items");
    let meta = doc.get_or_insert_map("meta");
    apply(&doc, update, WireVersion::V1)?;
    let txn = doc.transact();
    let actual: Vec<Out> = items.iter(&txn).collect();
    let expected = [
        Out::Any(Any::String("a".into())),
        Out::Any(Any::Number(42.0)),
        Out::Any(Any::Bool(true)),
        Out::Any(Any::Null),
        Out::Any(Any::Buffer(vec![1, 2].into())),
    ];
    require(
        actual == expected,
        format!("items produced {actual:?}; expected {expected:?}"),
    )?;
    expect_string(meta.get(&txn, "title"), "hello", "meta.title")?;
    require(
        meta.get(&txn, "count") == Some(Out::Any(Any::Number(2.0))),
        "meta.count is not integer 2",
    )
}

fn verify_nested_map(update: &[u8]) -> OracleResult<()> {
    let doc = oracle_doc(503);
    let root = doc.get_or_insert_map("root");
    apply(&doc, update, WireVersion::V1)?;
    let txn = doc.transact();
    let profile = root
        .get(&txn, "profile")
        .and_then(|value| value.cast::<MapRef>().ok())
        .ok_or_else(|| io::Error::other("root.profile is not a nested map"))?;
    expect_string(profile.get(&txn, "name"), "Ada", "profile.name")?;
    expect_string(profile.get(&txn, "city"), "Seoul", "profile.city")
}

fn array_strings(doc: &Doc, root: &str) -> OracleResult<Vec<String>> {
    let array = doc.get_or_insert_array(root);
    let txn = doc.transact();
    array
        .iter(&txn)
        .map(|value| match value {
            Out::Any(Any::String(value)) => Ok(value.to_string()),
            other => failure(format!("{root} contains non-string value {other:?}")),
        })
        .collect()
}

fn verify_concurrent_array(base: &[u8], x: &[u8], y: &[u8]) -> OracleResult<()> {
    let updates = [base, x, y];
    let permutations = [
        [0, 1, 2],
        [0, 2, 1],
        [1, 0, 2],
        [1, 2, 0],
        [2, 0, 1],
        [2, 1, 0],
    ];
    let expected = ["a", "X", "Y", "b"];
    for order in permutations {
        let doc = oracle_doc(504);
        doc.get_or_insert_array("letters");
        for index in order {
            apply(&doc, updates[index], WireVersion::V1)?;
        }
        let actual = array_strings(&doc, "letters")?;
        require(
            actual.iter().map(String::as_str).eq(expected),
            format!("concurrent array order {order:?} produced {actual:?}"),
        )?;
        let txn = doc.transact();
        let state = txn.state_vector();
        require(
            state.len() == 3
                && state.get(&ClientID::new(1)) == 2
                && state.get(&ClientID::new(2)) == 1
                && state.get(&ClientID::new(3)) == 1,
            format!("concurrent array order {order:?} produced state vector {state:?}"),
        )?;
    }
    Ok(())
}

fn verify_bundle(bundle: &FixtureBundle) -> OracleResult<()> {
    verify_text_pair(
        &bundle.text_base_v1,
        &bundle.text_delete_v1,
        WireVersion::V1,
    )?;
    verify_text_pair(
        &bundle.text_base_v2,
        &bundle.text_delete_v2,
        WireVersion::V2,
    )?;
    verify_high_client(&bundle.high_client_v1)?;
    verify_array_map(&bundle.array_map_v1)?;
    verify_nested_map(&bundle.nested_map_v1)?;
    verify_concurrent_array(
        &bundle.concurrent_array_base_v1,
        &bundle.concurrent_array_x_v1,
        &bundle.concurrent_array_y_v1,
    )
}

fn apply_v1_sequence(updates: &[&[u8]], root: &str) -> OracleResult<AppliedTextState> {
    let doc = oracle_doc(600);
    let text = doc.get_or_insert_text(root);
    for update in updates {
        apply(&doc, update, WireVersion::V1)?;
    }
    let txn = doc.transact();
    Ok(AppliedTextState {
        value: text.get_string(&txn),
        state: txn.state_vector(),
        has_missing_updates: txn.has_missing_updates(),
    })
}

fn verify_pending_delete_regression() -> OracleResult<()> {
    // Yrs v0.27.2 regression: dependency-last delivery must not resurrect "G".
    const A: &[u8] = &[1, 1, 174, 156, 239, 251, 3, 0, 4, 1, 1, 116, 1, 124, 0];
    const B: &[u8] = &[
        1, 1, 174, 156, 239, 251, 3, 1, 68, 174, 156, 239, 251, 3, 0, 1, 71, 0,
    ];
    const D: &[u8] = &[0, 1, 174, 156, 239, 251, 3, 1, 1, 1];
    const E: &[u8] = &[0, 1, 174, 156, 239, 251, 3, 1, 0, 1];

    for (label, order) in [
        ("causal", [A, B, D, E]),
        ("dependency-last-1", [B, D, E, A]),
        ("dependency-last-2", [D, B, E, A]),
    ] {
        let actual = apply_v1_sequence(&order, "t")?;
        require(
            actual.value.is_empty() && !actual.has_missing_updates,
            format!("pending-delete {label} left unexpected state {actual:?}"),
        )?;
    }
    Ok(())
}

fn verify_partial_skip_regression() -> OracleResult<()> {
    const UPDATES: &[&[u8]] = &[
        &[1, 1, 182, 144, 197, 137, 4, 0, 4, 1, 1, 116, 1, 109, 0],
        &[
            1, 1, 152, 176, 234, 156, 14, 3, 132, 152, 176, 234, 156, 14, 0, 1, 99, 0,
        ],
        &[0, 1, 152, 176, 234, 156, 14, 1, 2, 1],
        &[1, 1, 152, 176, 234, 156, 14, 0, 4, 1, 1, 116, 1, 112, 0],
        &[
            1, 1, 152, 176, 234, 156, 14, 1, 68, 152, 176, 234, 156, 14, 0, 1, 100, 0,
        ],
        &[
            1, 1, 152, 176, 234, 156, 14, 2, 196, 152, 176, 234, 156, 14, 1, 152, 176, 234, 156,
            14, 0, 1, 110, 0,
        ],
        &[
            1, 1, 182, 144, 197, 137, 4, 1, 132, 182, 144, 197, 137, 4, 0, 1, 100, 0,
        ],
    ];
    let actual = apply_v1_sequence(UPDATES, "t")?;
    require(
        actual.value == "mddpc"
            && !actual.has_missing_updates
            && actual.state.len() == 2
            && actual.state.get(&ClientID::new(1_093_748_790)) == 2
            && actual.state.get(&ClientID::new(3_818_559_512)) == 4,
        format!("partial Skip regression produced {actual:?}"),
    )
}

fn text_insert_update(doc: &Doc, index: u32, value: &str) -> Vec<u8> {
    let text = doc.get_or_insert_text("t");
    let mut txn = doc.transact_mut();
    text.insert(&mut txn, index, value);
    txn.encode_update_v1()
}

fn generate_middle_skip_bundle() -> OracleResult<MiddleSkipBundle> {
    let anchor = oracle_doc(100);
    let anchor_text = anchor.get_or_insert_text("t");
    {
        let mut txn = anchor.transact_mut();
        anchor_text.insert(&mut txn, 0, "P");
        anchor_text.insert(&mut txn, 1, "Q");
    }
    let anchor = anchor
        .transact()
        .encode_state_as_update_v1(&StateVector::default());

    let client = oracle_doc(1);
    client.get_or_insert_text("t");
    apply(&client, &anchor, WireVersion::V1)?;
    let c0 = text_insert_update(&client, 1, "a");
    let c1 = text_insert_update(&client, 2, "b");
    let c2 = text_insert_update(&client, 1, "c");
    let c3 = text_insert_update(&client, 5, "d");

    Ok(MiddleSkipBundle {
        anchor,
        c0,
        c1,
        c2,
        c3,
    })
}

fn verify_middle_skip_bundle(bundle: &MiddleSkipBundle) -> OracleResult<()> {
    let updates = [
        &bundle.anchor[..],
        &bundle.c0,
        &bundle.c1,
        &bundle.c2,
        &bundle.c3,
    ];

    let causal = apply_v1_sequence(
        &[updates[0], updates[1], updates[2], updates[3], updates[4]],
        "t",
    )?;
    require(
        causal.value == "PcabQd"
            && !causal.has_missing_updates
            && causal.state.len() == 2
            && causal.state.get(&ClientID::new(1)) == 4
            && causal.state.get(&ClientID::new(100)) == 2,
        format!("middle Skip causal order produced {causal:?}"),
    )?;
    let skipped = apply_v1_sequence(
        &[updates[0], updates[1], updates[4], updates[3], updates[2]],
        "t",
    )?;
    require(
        skipped.value == causal.value
            && skipped.state == causal.state
            && !skipped.has_missing_updates,
        format!("middle Skip delivery produced {skipped:?}, expected {causal:?}"),
    )
}

fn verify_middle_skip_regression() -> OracleResult<()> {
    verify_middle_skip_bundle(&generate_middle_skip_bundle()?)
}

fn verify_official_regressions() -> OracleResult<()> {
    verify_pending_delete_regression()?;
    verify_partial_skip_regression()?;
    verify_middle_skip_regression()
}

fn default_fixture_directory() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures")
}

fn usage() -> &'static str {
    "usage:\n  yks-yrs-oracle generate [directory]\n  yks-yrs-oracle verify-kotlin <directory>\n  yks-yrs-oracle self-test"
}

fn run() -> OracleResult<()> {
    let mut args = env::args().skip(1);
    match args.next().as_deref() {
        Some("generate") => {
            let directory = args
                .next()
                .map(PathBuf::from)
                .unwrap_or_else(default_fixture_directory);
            require(args.next().is_none(), usage())?;
            let bundle = generate_bundle()?;
            let middle_skip = generate_middle_skip_bundle()?;
            verify_bundle(&bundle)?;
            verify_official_regressions()?;
            bundle.write_to(&directory)?;
            middle_skip.write_to(&directory)?;
            println!(
                "generated and verified {} Yrs 0.27.2 fixtures in {}",
                bundle.entries().len() + middle_skip.entries().len(),
                directory.display()
            );
            Ok(())
        }
        Some("verify-kotlin") => {
            let directory = args
                .next()
                .map(PathBuf::from)
                .ok_or_else(|| io::Error::other(usage()))?;
            require(args.next().is_none(), usage())?;
            let bundle = FixtureBundle::read_from(&directory)?;
            verify_bundle(&bundle)?;
            verify_official_regressions()?;
            println!(
                "verified Kotlin fixture bundle with Yrs 0.27.2: {}",
                directory.display()
            );
            Ok(())
        }
        Some("self-test") => {
            require(args.next().is_none(), usage())?;
            verify_bundle(&generate_bundle()?)?;
            verify_official_regressions()?;
            println!("Yrs 0.27.2 oracle self-test passed");
            Ok(())
        }
        _ => failure(usage()),
    }
}

fn main() {
    if let Err(error) = run() {
        eprintln!("Yrs oracle failed: {error}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_fixture_contract_is_self_consistent() {
        verify_bundle(&generate_bundle().unwrap()).unwrap();
    }

    #[test]
    fn pending_delete_permutations_do_not_resurrect_content() {
        verify_pending_delete_regression().unwrap();
    }

    #[test]
    fn partial_skip_accepts_interior_block() {
        verify_partial_skip_regression().unwrap();
    }

    #[test]
    fn middle_skip_delivery_converges() {
        verify_middle_skip_regression().unwrap();
    }
}
