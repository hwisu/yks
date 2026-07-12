# Yrs interoperability oracle

This directory pins the official Rust Yjs port, Yrs `0.27.2`, as an independent
wire and CRDT oracle for YKS. Yjs remains the normative implementation when the
two upstream implementations differ.

The oracle always creates documents with deterministic client IDs,
`OffsetKind::Utf16`, and `skip_gc = true`. This is intentional: Yrs defaults to
UTF-8 byte offsets, while Yjs and YKS edit strings using UTF-16 code-unit
coordinates. Keeping deleted structs also makes delivery-order and snapshot-like
regressions reproducible.

## Commands

Run from the repository root:

```sh
cargo fmt --manifest-path interop/yrs-oracle/Cargo.toml -- --check
cargo test --manifest-path interop/yrs-oracle/Cargo.toml
cargo run --quiet --manifest-path interop/yrs-oracle/Cargo.toml -- generate
cargo run --quiet --manifest-path interop/yrs-oracle/Cargo.toml -- self-test
```

`generate [directory]` writes the fifteen committed fixtures below. With no
directory it writes `interop/yrs-oracle/fixtures`. Regeneration is byte-exact for
the pinned Yrs version, so CI can run it followed by `git diff --exit-code`.

`verify-kotlin <directory>` reads a bundle produced by Kotlin and verifies it
semantically with Yrs. Generated cross-runtime updates are not required to be
byte-identical: semantically equivalent Yjs-family implementations may use
different struct packing.

## Fixture contracts

`verify-kotlin` requires the ten bidirectional core fixtures:

```text
text-base-v1.bin
text-delete-v1.bin
text-base-v2.bin
text-delete-v2.bin
high-client-v1.bin
array-map-v1.bin
nested-map-v1.bin
concurrent-array-base-v1.bin
concurrent-array-x-v1.bin
concurrent-array-y-v1.bin
```

The generator also commits five Yrs-produced middle-Skip fixtures that Kotlin
consumes directly:

```text
skip-middle-anchor-v1.bin
skip-middle-c0-v1.bin
skip-middle-c1-v1.bin
skip-middle-c2-v1.bin
skip-middle-c3-v1.bin
```

- `body`: client `1` inserts `A😀BC`; the delete update removes UTF-16 index 3,
  length 1. Both base-first and delete-first delivery must result in `A😀C` for
  V1 and V2.
- `body`: client `9_007_199_254_740_000` inserts `high-client`; the client and clock
  must survive in the decoded state vector.
- `items` contains `"a"`, integer `42`, `true`, `null`, and bytes `[1, 2]`;
  `meta` contains `title="hello"` and integer `count=2`.
- `root.profile`: a nested map contains `{name: "Ada", city: "Seoul"}`.
- `letters`: base client `1` inserts `["a", "b"]`; clients `2` and `3`
  concurrently insert `"X"` and `"Y"` at index 1. Every delivery permutation
  must converge to `["a", "X", "Y", "b"]` with state vector `{1:2,2:1,3:1}`.
- `skip-middle-*` reproduces the official deterministic D=`100`, C=`1` middle
  Skip sequence. Causal `[anchor,c0,c1,c2,c3]` and Skip-inducing
  `[anchor,c0,c3,c2,c1]` delivery both produce `PcabQd`.

The Rust tests additionally preserve the official Yrs `0.27.2` pending-delete
resurrection vectors and the upstream partial/middle `Skip` regressions. They do
not depend on generated fixture files.
