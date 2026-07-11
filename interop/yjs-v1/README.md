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
maps/text. The harness also covers an anchor-free incremental nested-map update.

Live transaction-event updates and content that still needs a richer wire
model—formatting, XML, subdocuments, deletes, unsafe numeric coercions, or
pre-populated detached nested types—continue to use the legacy `YKS` envelope.
This preserves existing Kotlin behavior while each remaining content family is
moved behind a cross-language fixture.
