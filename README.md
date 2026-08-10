# YKS

YKS is a Kotlin/JVM implementation of the Yjs document model and update
protocol. It provides shared arrays, maps, text, live XML, subdocuments,
snapshots, relative positions, undo management, and update V1/V2 operations.

The project targets JDK 21 and is published from SemVer tags to GitHub Packages.
Interoperability is tested against Yjs `13.6.31` and independently against Yrs
`0.27.2` in its Yjs-compatible UTF-16 mode. YKS targets the genuine Yjs V1/V2
wire protocols and core CRDT behavior; it is not a line-for-line clone of either
runtime's object model. See [Yjs compatibility](YJS_COMPATIBILITY.md) for the
audited boundary.

## Install

Release `0.2.7` is published as `dev.yks:yks:0.2.7` in the repository's GitHub
Packages Maven registry. GitHub requires authentication when downloading Maven
packages, including packages attached to public repositories.

Add the registry to `settings.gradle.kts`:

```kotlin
val githubUser = providers.gradleProperty("gpr.user")
    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
val githubToken = providers.gradleProperty("gpr.key")
    .orElse(providers.environmentVariable("GITHUB_TOKEN"))

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/hwisu/yks")
            credentials {
                username = githubUser.orNull
                password = githubToken.orNull
            }
            content { includeGroup("dev.yks") }
        }
        mavenCentral()
    }
}
```

For local development, put a classic personal access token with
`read:packages` in `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_CLASSIC_PAT
```

See GitHub's [Gradle registry authentication documentation](https://docs.github.com/en/enterprise-cloud@latest/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry)
for token creation and permission details.

Then add the dependency:

```kotlin
dependencies {
    implementation("dev.yks:yks:0.2.7")
}
```

In another GitHub Actions repository, expose its `GITHUB_TOKEN` to Gradle and
grant that repository access under the package's **Manage Actions access**
setting; see [package access control](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages).
Consumers need JDK 21. CI compiles standalone consumers with Kotlin 2.2.20 and
Norric's Kotlin 2.3.21 baseline.

## Quick start

```kotlin
import dev.yks.*

val source = YDoc(clientId = 1, gc = false)
source.getText("body").insert(0, "hello", mapOf("bold" to true))

val update = encodeStateAsUpdate(source)
val target = YDoc(clientId = 2, gc = false)
applyUpdate(target, update)

check(target.getText("body").toString() == "hello")
```

Use the V2 APIs when both ends expect update V2:

```kotlin
val updateV2 = encodeStateAsUpdateV2(source)
applyUpdateV2(target, updateV2)
```

Shared types created with public constructors are preliminary values. They can
be populated before insertion; YKS integrates that same instance and replays
its content in owner-first clock order so standard Yjs wire remains available:

```kotlin
val doc = YDoc(clientId = 1)
val profile = YMap(mapOf("name" to "Ada"))
doc.getMap("root").set("profile", profile)

check(doc.getMap("root").get("profile") === profile)
```

Live XML uses document-owned node types:

```kotlin
val paragraph = doc.createXmlElement("p")
val text = doc.createXmlText()
doc.getXmlFragment("xml").push(paragraph)
paragraph.push(text)
text.insert(0, "hello")
```

`YXmlElement` and `YXmlText` are also available as compact, detached Kotlin
values. They are not the same model as the live document-owned XML types; see
the compatibility policy below.

## Main API areas

- Documents and shared types: `YDoc`, `YArray`, `YMap`, `YText`,
  `YXmlFragment`, `YXmlElementType`, and `YXmlTextType`
- Synchronization: `encodeStateAsUpdate`, `encodeStateAsUpdateV2`,
  `applyUpdate`, `applyUpdateV2`, and state vectors
- Update operations: `mergeUpdates`, `diffUpdate`, V1/V2 conversion,
  decoding, metadata parsing, content-id filtering, and obfuscation
- Editing support: transactions, direct/deep observers, snapshots,
  relative positions, `UndoManager`, and `PermanentUserData`
- Kotlin extensions: deep deltas, renderers, shared-type attributes, and
  compact XML values

`observeUpdates`, `onUpdate`, and `onUpdateV2` are standard-wire channels. They
never deliver a private YKS envelope. Use `observeUpdatesLossless`,
`onUpdateLossless`, or `onUpdateV2Lossless` for an explicitly lossless channel
that may carry private bytes. `YTransactionEvent.update` remains the lossless
transaction artifact so internal relay and Kotlin-only tooling can preserve all
state; use `encodeUpdateMessageFromTransaction` when genuine V1 bytes are
required.

If a transaction cannot be represented on standard Yjs wire while a standard
update listener is registered, YKS rejects it before observer delivery and
atomically restores the document. Neither standard nor lossless listeners see
the rejected transaction. Server integrations can enforce this even before a
listener is installed with
`YStandardUpdatePolicy.REQUIRE_STANDARD`.

## Standard and private update formats

YKS accepts genuine Yjs update V1/V2 and private YKS updates on all apply/decode
paths. Upstream-named writer APIs are stricter: `encodeStateAsUpdate`,
`encodeStateAsUpdateV2`, `mergeUpdates`, `diffUpdate`, conversion, filtering,
and obfuscation return genuine Yjs bytes only. If the requested state cannot be
represented, they throw `UnsupportedYjsStandardUpdateException` instead of
silently returning private bytes.

Use the corresponding explicit `*Lossless` API when a YKS-only peer is allowed,
for example `encodeStateAsUpdateLossless`, `encodeStateAsUpdateV2Lossless`,
`mergeUpdatesLossless`, `diffUpdateLossless`, or
`convertUpdateFormatV1ToV2Lossless`. Lossless APIs still emit genuine standard
wire whenever possible, and otherwise emit the private envelope beginning with
`YKS`. The latest envelope is `YKS\x05`; the writer selects the oldest sufficient
version from `YKS\x02` through `YKS\x05`, and readers retain compatibility with
`YKS\x01` through `YKS\x05`. Upstream JavaScript Yjs cannot read this format.
The `V2Lossless` APIs emit genuine V2 when representable; their fallback is the
versioned private YKS envelope, not a Yjs V2 frame.

Covered standard paths include:

- root, owner-first, and preliminary nested arrays, maps, and text;
- binary and lib0 values, including `undefined`, negative zero, special
  floating-point values, and signed 64-bit BigInt values;
- native rich-text format markers and embeds supported by the selected wire
  version;
- live XML elements/text, attributes, formatting, and deletion;
- direct subdocuments with upstream-representable options;
- delete sets, GC/Skip structs, large deleted ranges, state-vector diffs, and
  merged baseline/incremental updates.

Current lossless-only cases include:

- compact/static XML nodes and attributes on a root XML fragment;
- subdocument options that do not exist on Yjs wire, such as `collectionId`
  and suggestion-document metadata;
- other Kotlin-only metadata or content shapes that fail the lossless standard
  eligibility gate.

Applications synchronizing directly with JavaScript Yjs should use only the
standard APIs/channels. Pending and private internal relay state uses the
lossless path so it is never discarded; exporting that state through a standard
API fails explicitly until it becomes representable.

## Concurrency and untrusted input

`YDoc` and document-owned shared types are mutable and thread-confined, as they
are in Yjs. By default, the first CRDT operation lazily binds a document to the
current thread; later access from another thread throws
`YksThreadConfinementException`. Encoded update/state-vector byte arrays and
copied value snapshots are the supported hand-off boundaries between threads.
Coroutine/server integrations that already serialize a document should use
`YThreadAccessPolicy.EXTERNALLY_SERIALIZED`. It permits sequential dispatcher
handoff and rejects overlapping operations with
`YksConcurrentAccessException`; it does not block a request thread or add an
internal scheduler. `UNCHECKED` remains a check-free escape hatch.

```kotlin
val serverDoc = YDoc(
    YDocOptions(),
    YDocRuntimeOptions(
        threadAccessPolicy = YThreadAccessPolicy.EXTERNALLY_SERIALIZED,
        standardUpdatePolicy = YStandardUpdatePolicy.REQUIRE_STANDARD,
    ),
)
```

External update application has immutable, per-document resource limits:

```kotlin
val doc = YDoc(
    YDocOptions(),
    YDocRuntimeOptions(
        updateLimits = YUpdateLimits(
            maxEncodedBytes = 8 * 1024 * 1024,
            maxStructs = 50_000,
            maxDeleteRanges = 50_000,
        ),
    ),
)
```

The defaults are 16 MiB of encoded bytes, 50,000 decoded structs, and 50,000
delete ranges. Limits are checked before document mutation and report
`YksUpdateLimitException`.
Malformed update/state-vector input reports `YksDecodingException` with the
original decoder failure retained as its cause. Applications should still set
limits appropriate to their protocol and add request deadlines, concurrency
limits, and rate limiting at the transport boundary.

Public `DeleteSet`, `IdSet`, and `IdMap` collection properties return defensive
snapshots; use their mutation methods instead of editing returned collections.
Binary decoders reject oversized collections, individual payloads, excessive
aggregate payload, excessive value-node counts, and nesting deeper than 256.
Malformed UTF-8 and legacy JSON are rejected rather than repaired silently.

## Known limitations

- Root shared-type kind and a root `XmlElement` node name are not encoded by
  Yjs updates. Applied remote roots therefore remain unopened and are omitted
  from `toJSON()` until an explicit typed getter supplies the expected schema.
  Local clone/snapshot helpers preserve schema known when they are created.
- Live XML selector APIs are supported. Browser DOM creation such as `toDOM` is
  outside the JVM surface.
- Kotlin callback/type shapes and Kotlin-specific extensions are not a
  line-for-line port of every JavaScript export.
- The interop gate includes 500 deterministic concurrent array/text/map seeds,
  100 advanced XML/subdocument/relative-position/V2/UndoManager seeds, and
  1,000 deterministic malformed V1/V2 seeds. It is still not a substitute for
  application-specific or coverage-guided production fuzzing.

## Build and test

Requirements:

- JDK 21
- Node.js 22 or newer for the JavaScript oracle
- Rust 1.97.0 for the pinned Yrs oracle

```sh
./gradlew test
npm ci
npm run test:interop
npm run benchmark:performance:check
npm run benchmark:performance:advanced
./gradlew check consumerSmokeTest consumerKotlinCompatibilityTest
./gradlew jmh
./scripts/verify-reproducible-artifacts.sh
```

`check` runs both the normal Kotlin tests and the Kotlin/Yjs interoperability
tests. `consumerSmokeTest` publishes the current version to Maven Local, then
builds and runs a standalone Kotlin 2.2.20 project against the produced
artifact. `consumerKotlinCompatibilityTest` repeats that published-artifact
check with Kotlin 2.3.21. To
regenerate committed upstream fixtures and verify that generation is clean:

```sh
npm run interop:generate
npm run interop:check-clean
```

See the [Yjs harness](interop/yjs-v1/README.md) and
[Yrs harness](interop/yrs-oracle/README.md) documentation for scenario details
and standalone update verifiers. The Yrs oracle always uses UTF-16 offsets,
deterministic client IDs, and retained deleted content so that it measures the
shared Yjs wire contract instead of Yrs-only defaults.

`jmh` records forked latency plus the GC profiler's allocation metrics for
insert, middle edit, standard update apply/encode, standard-listener
transactions, adversarial pre-decode rejection, formatted/nested/map updates,
packed array updates/inserts, same-key map history, root and indexed-array
access, already-open fragmented/concurrent roots, observed fragmented/map
edits, packed undo, snapshot clock ranges, alternating delete-set snapshots,
fragmented-prefix formatting, observer-isolation edits, and sequential
incremental standard-update application. Results are written to
`build/reports/jmh/results.json`.

The strict cross-runtime gate uses 50 warmup batches and 30 measured batches
for 37 workloads. Every workload must satisfy strict ratio parity
(`YKS/Yjs <= 1.5x`), and micro workloads must independently remain within the
process-level latency budget (`YKS <= 6 ms`). Neither condition is a fallback
for the other. Each warmup uses the same explicit repeat count as a measured
sample, so the JVM is not measured in interpreted/C1 code while V8 is already
optimized. Destructive fixtures are prepared before each invocation, outside
the measured interval, and the JVM runner waits outside measurement after each
workload warmup so queued tiered compilation cannot contaminate the next one.
The advanced command is a checked subset covering XML construction/rendering,
relative-position resolution, V2 merge/diff, and 1,000 undo plus 1,000 redo
steps. All four are also included in the default release gate.
Kotlin explicit API mode, warnings-as-errors, and the committed `api/yks.api`
baseline make accidental public or binary API drift a build failure.
Release JARs disable file timestamps, use reproducible entry order, embed the
exact `YKS-Revision`, and are built twice and byte-compared before publication.

Pushing a tag such as `v0.2.7` runs the same gates, publishes that immutable
version to GitHub Packages, and verifies it again from a clean remote consumer.

## License

YKS is distributed under the [MIT License](LICENSE). Published binary and source
JARs include the license and the preserved [upstream Yjs and lib0 attributions](THIRD_PARTY_NOTICES)
under `META-INF`.
