# Yjs V1 interoperability fixtures

This harness is the external compatibility oracle for the Kotlin codec. It is
pinned to stable Yjs 13.6.31 and uses fixed client IDs so committed update bytes
are deterministic.

Generate the fixtures and run the JavaScript checks:

```sh
npm install
npm run interop:generate
npm run test:interop
```

Run the Kotlin-facing compatibility gate:

```sh
./gradlew interopTest
```

`interopTest` is deliberately separate from the normal unit-test task. The
canonical `hello` fixture now passes in both directions.

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
```

Kotlin currently emits standard V1 bytes for compatible explicit state exports
covering unformatted text, arrays, maps, binary values, and owner-first nested
maps/text. It also relays upstream native `Y.Text` format-marker documents and
marker-only incremental updates back as standard V1 bytes. Owner-first live XML
trees and safe direct `Y.Array`/`Y.Map` subdocuments are also emitted as standard
V1. The harness covers anchor-free incremental nested-map, rich-text, and XML
updates.

The standard V1 decoder accepts packed text/array content, binary values,
root and nested maps, inherited map keys, interior origin/right-origin anchors,
delete sets, GC structs, and out-of-order dependency updates. Dedicated fixtures
also lock down the protocol distinction between Skip (an unavailable clock gap)
and GC (stored clock ownership), deleted nested parents, and replacement content
whose type must not be inferred from an opaque deleted struct. Rich-text fixtures
cover marker removal, reverse-order delivery, embeds, snapshots, event deltas,
and restoration to a previous non-null attribute value.

XML fixtures cover element attributes, nested `Y.XmlText`, native text-format
markers, pre-materialized root elements, and same/cross-client parent delivery in
either order. Subdocument fixtures cover default and explicit standard options,
add/load events, relay, and distinct instances that share one GUID.

Live transaction-event updates and content that still needs a richer wire
model—Kotlin-authored range formatting, static compact XML nodes, pre-populated
detached XML types, subdocument deletions/nonstandard options, deletes, unsafe
numeric coercions, or pre-populated detached collection types—continue to use
the legacy `YKS` envelope. This preserves existing Kotlin behavior while each
remaining content family is moved behind a cross-language fixture.
