# Yjs V1/V2 interoperability harness

This directory is the external compatibility oracle for the Kotlin update
codec. It is pinned to Yjs `13.6.31` and uses fixed client IDs so committed
fixture bytes are deterministic.

Install dependencies, regenerate fixtures, and run the JavaScript checks:

```sh
npm ci
npm run interop:generate
npm run test:interop
```

The Node suite currently contains 78 tests. This number includes fixture
semantics, upstream self-roundtrips, and verifier regression tests; it does not
mean that all 78 tests execute Kotlin code.

Run the Kotlin-facing compatibility gate, or the complete Gradle gate:

```sh
./gradlew interopTest
./gradlew check
```

`check` depends on `interopTest`, while the plain `test` task excludes tests
tagged `yjs-interop`.

Validate an update produced by Kotlin:

```sh
npm run interop:verify -- build/interop/kotlin-hello-v1.bin
```

The verifier also accepts a named semantic scenario:

```sh
npm run interop:verify -- update.bin array
npm run interop:verify -- update.bin map
npm run interop:verify -- update.bin nested-map
npm run interop:verify -- update.bin nested-text
npm run interop:verify -- update.bin nested-map-replace-update
npm run interop:verify -- update.bin subdoc-array-default
```

Validate genuine V2 output directly:

```sh
node interop/yjs-v1/verify-update-v2.mjs update-v2.bin array
node interop/yjs-v1/verify-update-sequence-v2.mjs text-delete base-v2.bin diff-v2.bin
```

The V1 sequence verifier has the equivalent interface:

```sh
node interop/yjs-v1/verify-update-sequence.mjs nested-map-update base.bin diff.bin
```

## Covered behavior

The committed fixtures and Kotlin interop tests cover both upstream-to-Kotlin
decoding and Kotlin-to-upstream application for:

- packed text/array content, binary data, maps, and owner-first nested types;
- lib0 values including `undefined`, null, negative zero, special floating
  point values, signed 64-bit BigInt values, and object property order;
- origin/right-origin anchors, inherited parents, out-of-order dependencies,
  duplicate delivery, and multi-client convergence;
- rich-text markers, overlapping formatting, embeds, format removal,
  snapshots, and standard root transaction update events;
- live XML ownership, attributes, formatted `Y.XmlText`, root
  pre-materialization, cross-client content, and deletion;
- direct/default/optioned subdocuments, duplicate GUID instances, lifecycle
  events, text/XML-text placement, and insert-before-delete sequences;
- delete sets, GC versus Skip semantics, and large `ContentDeleted`/GC ranges;
- genuine update V2 streams, format conversion, merge/diff/state-vector
  operations, and V2 event payloads;
- baseline plus nested incremental merges, including explicit and inherited
  parent refs and root names that resemble internal aliases.

Verifier regression tests ensure that empty subdocument-delete sequences and a
matching GUID stored at the wrong location are rejected.

`generate-differential-fuzz.mjs` additionally creates 500 deterministic seeds
of concurrent array, text, and map operations across three Yjs clients. The
Kotlin `YjsDifferentialFuzzTest` applies every shuffled update sequence and
compares the final state with the upstream oracle.

## Fixture policy

`generate-fixtures.mjs` is the source of generated fixtures. Hand-authored
Skip/GC bytes are documented next to their generation because they express
protocol states that normal public Yjs operations do not emit directly. CI
regenerates fixtures and fails if the working tree changes.

## Scope and remaining gaps

The standard writer is lossless-first. Unsupported Kotlin-only shapes use the
private `YKS\x03` envelope instead of silently dropping data. JavaScript Yjs
cannot apply that envelope. Current private cases include compact/static XML,
root-fragment attributes, pre-populated detached child-before-owner types, and
nonstandard subdocument options such as `collectionId` or suggestion metadata.

Root XML kind and a root element node name are absent from Yjs wire, so an
ambiguous receiver must pre-materialize the expected root. `Y.XmlHook` does not
yet have a fully matching public Kotlin map-style surface.

The fixture scenarios and seeded differential test are not a production-scale
benchmark or adversarial fuzzer; applications should still add fixtures for
their own mutation and update-delivery patterns.
