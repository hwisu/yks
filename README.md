# YKS

YKS is a Kotlin/JVM implementation of the Yjs document model and update
protocol. It provides shared arrays, maps, text, live XML, subdocuments,
snapshots, relative positions, undo management, and update V1/V2 operations.

The project targets JDK 21 and is published from SemVer tags to GitHub Packages.
Interoperability is tested against Yjs `13.6.31`. YKS targets the genuine Yjs
V1/V2 wire protocols and core CRDT behavior; it is not a line-for-line clone of
the JavaScript object model. See [Yjs compatibility](YJS_COMPATIBILITY.md) for
the audited boundary.

## Install

Release `0.1.0` is published as `dev.yks:yks:0.1.0` in the repository's GitHub
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
    implementation("dev.yks:yks:0.1.0")
}
```

In another GitHub Actions repository, expose its `GITHUB_TOKEN` to Gradle and
grant that repository access under the package's **Manage Actions access**
setting; see [package access control](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages).
Consumers need JDK 21 and a Kotlin 2.2-compatible toolchain.

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

If a lossless-only transaction occurs while a standard update listener is
registered, the mutation remains committed and listener emission reports
`UnsupportedYjsStandardUpdateException`; the standard listener receives no
payload. Lossless listeners are still invoked.

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
`YKS`. The latest envelope is `YKS\x04`; the writer selects the oldest sufficient
version from `YKS\x02` through `YKS\x04`, and readers retain compatibility with
`YKS\x01` through `YKS\x04`. Upstream JavaScript Yjs cannot read this format.
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

## Known limitations

- Root shared-type kind and a root `XmlElement` node name are not encoded by
  Yjs updates. Applied remote roots therefore remain unopened and are omitted
  from `toJSON()` until an explicit typed getter supplies the expected schema.
  Local clone/snapshot helpers preserve schema known when they are created.
- Live XML selector APIs are supported. Browser DOM creation such as `toDOM` is
  outside the JVM surface.
- Kotlin callback/type shapes and Kotlin-specific extensions are not a
  line-for-line port of every JavaScript export.
- The interop gate includes a deterministic 500-seed concurrent array/text/map
  differential test. It is still not a substitute for application-specific or
  adversarial testing.

## Build and test

Requirements:

- JDK 21
- Node.js 22 or newer for the JavaScript oracle

```sh
./gradlew test
npm ci
npm run test:interop
./gradlew check consumerSmokeTest
```

`check` runs both the normal Kotlin tests and the Kotlin/Yjs interoperability
tests. `consumerSmokeTest` publishes the current version to Maven Local, then
builds and runs a standalone project against the produced artifact. To
regenerate committed upstream fixtures and verify that generation is clean:

```sh
npm run interop:generate
git diff --exit-code -- interop/yjs-v1/fixtures
```

See [the interoperability harness documentation](interop/yjs-v1/README.md) for
scenario details and standalone update verifiers.

Pushing a tag such as `v0.1.0` runs the same gates, publishes that immutable
version to GitHub Packages, and verifies it again from a clean remote consumer.
