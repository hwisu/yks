# Yjs compatibility

YKS is a Kotlin/JVM engine for Yjs wire and document semantics, not a JavaScript object-model clone.

| Target | Tested version | Scope |
|---|---:|---|
| Yjs | `13.6.33` | Stable API, update V1/V2, document model |
| `@y/y` | `14.0.0-rc.29` | V1/V2 wire and opt-in `experimental.v14` facade |
| Yrs | `0.28.1` | Bidirectional wire in UTF-16 mode |
| `y-protocols` | `1.0.7` | Awareness wire |

## Core contracts

- Standard apply, encode, merge, diff, and conversion APIs accept genuine Yjs V1/V2 bytes. V2 APIs never retry as V1.
- `*Lossless` APIs may use a private `YKS` envelope for Kotlin-only state and must remain between YKS peers.
- Yjs wire omits root kinds and root XML element names. Use typed getters or `YRootSchemaRegistry` for ambiguous remote roots.
- `Item` and `Transaction` are safe JVM views, not Yjs mutable-internal APIs.
- `experimental.v14.Node` (a Kotlin alias of the retained JVM `Type` class) covers the RC collection and attribute read helpers, snapshot-aware attributes, nullable renderer detach, and renderer-visible `toArray`; root projection remains explicit on JVM.
- rc.29 update merges retain the first encoding of every clock, including partial GC overlaps; document diffs omit insert/delete churn and accept a null renderer for plain changes.
- rc.28 node schemas check nominal identity and node names; the earlier `Type` and schema-marker spellings remain available.
- The opt-in `$renderer` marker uses `y:renderer`; stable renderer strings and schemas retain `y:r` for compatibility.
- `experimental.v14` is not full RC API parity. Detached `Node.from`, renderer-aware delta/relative position conversion, utility ID schemas, and the full generic lib0 schema surface remain outside the adapter; use the stable delegate for unsupported features.

## Operational boundaries

- Standard V1/V2 update APIs reject zero-length GC and Skip structs with `YksDecodingException`, including decode, apply, merge, diff, conversion, and inspection. This follows Yrs 0.28.1's malformed-input policy and is intentionally stricter than Yjs 13.6.33 / 14 rc.29, whose decoders accept these structs (rc.29's multi-update merge throws). Empty client listings remain accepted; valid nonempty structs and wire formats are unchanged.
- Standard V1/V2 update APIs reject a client, clock, struct end, origin, or delete range above the JavaScript safe integer range (2^53 − 1) with `YksDecodingException`, before any document mutation. Yjs 13.6.33 reads such integers as rounded doubles and Yrs 0.28.1 keeps 64-bit client ids, so this is stricter than both; no standard YKS writer emits them, and accepting one left a document unable to encode its state.
- Default `YUpdateLimits` allow JVM representation limits for compatibility. Configure smaller document-specific limits for untrusted channels.
- Recursive value decoding and conversion use heap-backed traversal. The optional JVM property `dev.yks.maxDecodedNestingDepth` imposes an application policy on otherwise valid values; leave it unset to retain the default depth compatibility.
- `YDoc` and attached types are thread-confined by default. Coroutine/server integrations must serialize access and use `EXTERNALLY_SERIALIZED`.
- WebSocket/WebRTC providers and ProseMirror/Tiptap/CodeMirror bindings are out of scope.

CI checks pinned upstream versions, binary fixtures, differential fuzzing, malformed input, ABI compatibility, and published-package consumption.
