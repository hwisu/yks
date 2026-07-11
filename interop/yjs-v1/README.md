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

`interopTest` is deliberately separate from the normal unit-test task. Both
directions currently fail because Kotlin still emits and expects the private
`YKS` envelope. They become the first green acceptance tests for the V1 codec.

Validate an update produced by Kotlin:

```sh
npm run interop:verify -- build/interop/kotlin-hello-v1.bin
```

The first codec milestone is complete when Kotlin can apply
`fixtures/hello-text-v1.bin` and the verifier accepts Kotlin's update for the
same document. The current private `YKS` update codec is expected to fail both
cross-language directions until it is replaced.
