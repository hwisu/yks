# Yjs V1/V2 interoperability harness

This directory is the external compatibility oracle for the Kotlin update
codec. It is pinned to Yjs `13.6.32` and uses fixed client IDs so committed
fixture bytes are deterministic.

Install dependencies, regenerate fixtures, and run the JavaScript checks:

```sh
npm ci
npm run interop:generate
npm run test:interop
```

The Node suite includes fixture semantics, upstream self-roundtrips, and
verifier regressions; Kotlin-facing cases run through `interopTest`.

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

- packed text/array content, binary data, maps, and owner-first/replayed preliminary nested types;
- lib0 values including `undefined`, null, negative zero, special floating
  point values, signed 64-bit BigInt values, and object property order;
- origin/right-origin anchors, inherited parents, out-of-order dependencies,
  duplicate delivery, and multi-client convergence;
- rich-text markers, overlapping formatting, embeds, format removal,
  snapshots, and standard root transaction update events;
- live XML ownership, attributes, formatted `Y.XmlText`, deferred explicit root
  materialization, cross-client content, and deletion;
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

`generate-advanced-differential-fuzz.mjs` adds 100 deterministic upstream
oracle seeds for live XML, subdocument replacement and lifecycle state,
relative positions across edits, update V2 merge/diff/format conversion, and
UndoManager undo/redo sequences. A separate corpus mutates 1,000 seeds in both
V1 and V2 and compares upstream/local acceptance, resulting text, and state
vectors. Another 200-seed corpus combines formatted text, embeds, complex
nested types, direct/deep events, snapshots, and GC.

## Fixture policy

`generate-fixtures.mjs` is the source of generated fixtures. Hand-authored
Skip/GC bytes are documented next to their generation because they express
protocol states that normal public Yjs operations do not emit directly. CI
regenerates fixtures and fails if the working tree changes.

## Scope and remaining gaps

Upstream-named writer APIs are standard-only. Unsupported Kotlin-only shapes
throw `UnsupportedYjsStandardUpdateException`; the corresponding explicit
`*Lossless` APIs may use a private YKS envelope instead of dropping data. The
latest private version is `YKS\x05`; the writer selects `YKS\x02` through
`YKS\x05` according to the metadata required, while the reader accepts
`YKS\x01` through `YKS\x05`. JavaScript Yjs cannot apply those envelopes.
Current lossless-only cases include compact/static XML, root-fragment
attributes, and nonstandard subdocument options such as `collectionId` or
suggestion metadata. Standard apply/decode/metadata APIs reject private input;
only explicitly named `*Lossless` APIs accept it. `applyUpdateV2` parses only
the V2 envelope and does not silently retry V1.

Root XML kind and a root element node name are absent from Yjs wire. An applied
ambiguous root remains unopened until an explicit typed getter supplies that
schema; local clone/snapshot helpers retain root metadata known at capture time.

The fixture scenarios and deterministic property tests are regression gates,
not a coverage-guided production fuzzer. Applications should still add
fixtures for their own mutation and update-delivery patterns.
