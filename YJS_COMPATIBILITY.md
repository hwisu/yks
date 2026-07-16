# Yjs compatibility

YKS targets Yjs `13.6.31`. It is a Kotlin/JVM implementation of the Yjs
document model and update protocol, not a byte-for-byte or object-model clone
of the JavaScript package.

## Compatibility statement

- Genuine Yjs update V1 and V2 are the interoperability boundary.
- The main shared types, transactions, observers, snapshots, relative
  positions, undo management, subdocuments, XML model operations, update
  transforms, and garbage collection are implemented and tested against
  upstream Yjs.
- All 103 names exported by Yjs `13.6.31` have a Kotlin mapping or a documented
  JVM adaptation.
- There is no known core wire-format or CRDT-convergence blocker in the tested
  surface.
- JavaScript callback shapes, mutable internal structs, and browser DOM APIs are
  not reproduced exactly.

The independent export audit classified the 103 upstream names as follows:

| Classification | Count | Meaning |
| --- | ---: | --- |
| Direct equivalent on the normal path | 49 | No concrete behavior difference was found in the audited path. |
| Kotlin/default-codec adaptation | 18 | The operation exists, with Kotlin signatures or the built-in encoder/decoder path. |
| Non-identical public or internal contract | 32 | A mapping exists, but its object shape or secondary behavior is intentionally different. |
| XML type with browser-only surface omitted | 4 | XML document behavior exists; browser DOM creation does not. |
| **Total** | **103** | Every upstream export was classified exactly once. |

## Tested guarantees

CI regenerates fixtures with upstream Yjs and the pinned Yrs `0.27.2` crate and
verifies:

- update V1 and V2 encode, decode, apply, merge, diff, convert, obfuscate, and
  state-vector operations;
- arrays, maps, rich text, embeds, live XML, subdocuments, delete sets, GC,
  snapshots, relative positions, typed/direct/deep events, and undo/redo;
- out-of-order and concurrent delivery, including a deterministic 500-seed
  array/text/map differential suite;
- Kotlin-produced updates applied by Yjs, and Yjs-produced updates applied by
  Kotlin;
- publication to a Maven repository followed by a clean standalone consumer
  build and cross-document update round trip.

The independent Yrs oracle checks both directions: Yrs-produced V1/V2 updates
are applied by Kotlin, and Kotlin-produced updates are applied by Yrs. Its
deterministic compatibility profile uses `OffsetKind::Utf16`, retained deleted
content, and explicit client IDs. It covers non-BMP text edits and reverse
delivery, nested and binary values, concurrent insertion permutations,
JavaScript-safe 53-bit client IDs, and the out-of-order pending/Skip/delete
regressions fixed through Yrs `0.27.2`.

The upstream-named update APIs return genuine Yjs bytes or fail explicitly when
a Kotlin-only state cannot be represented. APIs ending in `Lossless` may use a
private `YKS` envelope and must not be sent to JavaScript Yjs.

## Intentional differences

1. Kotlin names and callbacks
   - `YArray` and `YMap` avoid collisions with Kotlin's `Array` and `Map`.
   - `Transaction` maps to the active `YTransaction`; completed observer data is
     exposed as `TransactionEvent` / `YTransactionEvent`.
   - Typed and deep event-list callbacks are available through Kotlin-specific
     observer helpers rather than JavaScript's exact callback types.

2. Internal object model
   - `Item`, `GC`, `Skip`, `AbstractStruct`, and `Content*` expose the wire and
     inspection information needed by YKS, but not Yjs's complete mutable linked
     object graph or every internal method.
   - `decodeUpdate*` returns Kotlin decoded-struct DTOs. `logType` and
     `logUpdate*` return strings instead of writing to a JavaScript console.
   - Optional custom V2 encoder/decoder constructor injection is represented by
     the built-in codec path rather than JavaScript constructor parity.
   - Legacy wire `ContentJSON` and modern `ContentAny` both normalize to YKS
     value items. Their values are preserved, but the original constructor
     distinction is unavailable for the rare case where it alone prevents an
     upstream cleanup merge.
   - Adjacent UTF-16 text is retained as packed `ContentString` storage and is
     split only at edit, delete, snapshot, or update-selection boundaries. A
     valid encoder may choose different packing without changing clock or CRDT
     semantics.
   - Packed deleted items merge conservatively when YKS-specific structural
     metadata differs. This may retain more internal store structs than Yjs in
     order to preserve relative-position and private-wire metadata.

3. JVM platform boundary
   - Live XML selectors, insertion, attributes, snapshots, and serialization are
     supported. Browser `toDOM` is not available on the JVM.
   - YKS also provides compact/static XML values that are Kotlin extensions,
     separate from live document-owned XML types.

4. Wire-level ambiguity and extensions
   - Yjs updates do not encode a root shared-type kind or a root `XmlElement`
     node name. Remote ambiguous roots remain unopened until a typed getter
     supplies that schema.
   - Kotlin-only subdocument options and compact XML require explicit lossless
     APIs. Standard APIs never silently place private bytes on a Yjs channel.
   - Text callbacks and attribution are evaluated on logical UTF-16 clocks even
     when the underlying `ContentString` remains packed.

5. Yrs-specific runtime extensions
   - Yrs defaults text indexes to UTF-8 byte offsets, while Yjs and YKS use
     UTF-16 code units. Cross-runtime tests explicitly select Yrs UTF-16 mode.
   - Yrs-only weak links, query/runtime synchronization APIs, and extra encoded
     subdocument options are not part of the Yjs `13.6.31` compatibility claim.
   - Update encoders may choose different valid struct packing. Cross-runtime
     generated updates are compared by state-vector and document semantics;
     byte identity is required only when regenerating a fixture with the same
     pinned producer.

Applications that communicate with JavaScript Yjs should stay on the standard
V1/V2 APIs and use explicit typed getters for remotely created roots. Kotlin-only
peers may additionally opt into the lossless and extension APIs.

`YDoc` and attached shared types are thread-confined mutable objects, matching
the Yjs execution model. The default `ENFORCED` policy lazily binds the document
on its first CRDT operation and rejects other threads with
`YksThreadConfinementException`. `EXTERNALLY_SERIALIZED` is the safe coroutine
integration policy: sequential calls may resume on different JVM threads, while
overlap in transactions, update application/encoding, snapshots, and destroy is
rejected with `YksConcurrentAccessException`. It is fail-fast and does not add
internal locking. `UNCHECKED` remains an explicit no-check escape hatch. JVM
applications without external serialization should use encoded bytes or copied
snapshots as hand-off values.

Registering a standard V1/V2 update listener makes local transactions atomic at
the standard-wire boundary. A Kotlin-only mutation that cannot produce a
genuine Yjs update is rolled back before type, standard, or lossless observer
delivery. `YStandardUpdatePolicy.REQUIRE_STANDARD` applies the same rule even
without a listener and is the intended Hocuspocus/server mode. This policy does
not change remote update semantics or permit private `YKS` envelopes on a
standard channel.

Yjs itself does not impose transport resource limits. YKS adds configurable
`YUpdateLimits` at the document-application boundary as a secure JVM adaptation.
Encoded byte, decoded struct, and delete-range limits are enforced before
mutation, so a rejected V1/V2 update leaves document and pending-delete state
unchanged. This means a valid but over-limit Yjs update must be chunked or
applied to a document configured with a larger budget. Malformed
update/state-vector payloads use the stable
`YksDecodingException` boundary, while limit and thread violations have their
own typed exceptions.
