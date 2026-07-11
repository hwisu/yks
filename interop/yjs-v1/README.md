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

Kotlin currently emits standard V1 bytes only for self-contained, unformatted
root-text updates. Complex, nested, formatted, deleted, or incremental updates
still use the legacy `YKS` envelope so existing behavior remains intact while
the standard wire model is expanded. The verifier must be extended with a
fixture before each additional content type is moved off that fallback.
