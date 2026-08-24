# Yjs compatibility

YKS is a Kotlin/JVM engine for Yjs wire and document semantics, not a JavaScript object-model clone.

| Target | Tested version | Scope |
|---|---:|---|
| Yjs | `13.6.32` | Stable API, update V1/V2, document model |
| `@y/y` | `14.0.0-rc.24` | V1/V2 wire and opt-in `experimental.v14` facade |
| Yrs | `0.27.2` | Bidirectional wire in UTF-16 mode |
| `y-protocols` | `1.0.7` | Awareness wire |

## Core contracts

- Standard apply, encode, merge, diff, and conversion APIs accept genuine Yjs V1/V2 bytes. V2 APIs never retry as V1.
- `*Lossless` APIs may use a private `YKS` envelope for Kotlin-only state and must remain between YKS peers.
- Yjs wire omits root kinds and root XML element names. Use typed getters or `YRootSchemaRegistry` for ambiguous remote roots.
- `Item` and `Transaction` are safe JVM views, not Yjs mutable-internal APIs.
- `experimental.v14` is not full RC API parity. `Type.delta` is a shallow snapshot; use the stable delegate for unsupported features.

## Operational boundaries

- Default `YUpdateLimits` allow JVM representation limits for compatibility. Configure smaller document-specific limits for untrusted channels.
- `YDoc` and attached types are thread-confined by default. Coroutine/server integrations must serialize access and use `EXTERNALLY_SERIALIZED`.
- WebSocket/WebRTC providers and ProseMirror/Tiptap/CodeMirror bindings are out of scope.

CI checks pinned upstream versions, binary fixtures, differential fuzzing, malformed input, ABI compatibility, and published-package consumption.
