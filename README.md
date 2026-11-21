# YKS

YKS lets Kotlin applications on JDK 21 edit shared documents and synchronize them with
JavaScript Yjs and Rust Yrs peers. A `YDoc` holds shared text, arrays, maps, XML, and
subdocuments. Peers exchange binary updates. Awareness carries ephemeral presence data
such as cursors and online status separately.

## Installation

To install YKS `0.2.20` from GitHub Packages, store a classic
personal access token with `read:packages` in `~/.gradle/gradle.properties`.

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_CLASSIC_PAT
```

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/hwisu/yks")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
            content { includeGroup("dev.yks") }
        }
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
dependencies { implementation("dev.yks:yks:0.2.20") }
```

## Synchronize two documents

```kotlin
import dev.yks.*

val writer = YDoc()
val reader = YDoc()
val body = writer.getText("body")
body.insert(0, "hello")
applyUpdate(reader, encodeStateAsUpdate(writer))

body.insert(body.length, " world")
val missing = encodeStateAsUpdate(writer, reader.encodeStateVector())
applyUpdate(reader, missing)
check(reader.getText("body").toString() == "hello world")
```

The reader's state vector tells the writer which structs the reader already has.
Use `encodeStateAsUpdateV2` and `applyUpdateV2` when both peers use V2.

## Editing and presence

- An `UndoManager` scoped to one shared type undoes edits to that type and its
  descendants. If one transaction edits both a title and a separate body, undoing
  through the title's manager preserves the body edit.
- `UndoManager.clear()` releases GC protection for deletions in the cleared history.
  With document GC enabled, a subsequent GC pass over those items can discard their content.
- If an `observeUpdates` listener edits the document, all listeners for the current
  update receive it before the follow-up update is delivered.
- Concurrent `Awareness.setLocalStateField` and `removeLocalStateField` calls preserve
  edits to distinct fields. Use `setLocalState` to replace the whole local presence state.

## Production notes

- `YDoc` and its attached types are confined to one thread by default. If coroutines
  can resume on different threads, serialize document access and set the runtime
  policy to `YThreadAccessPolicy.EXTERNALLY_SERIALIZED`.
- Configure `YUpdateLimits` to bound incoming update bytes, structs, and delete
  ranges. The defaults use JVM representation limits.
- Use standard V1/V2 APIs for JavaScript and Rust peers. The `*Lossless` APIs can
  produce a private format for YKS peers.

## Compatibility

| Peer | Tested version | Coverage |
| --- | --- | --- |
| Yjs | `13.6.33` | Shared document behavior and V1/V2 updates |
| `@y/y` | `14.0.0-rc.29` | V1/V2 updates and the experimental Kotlin `Node` facade |
| Yrs | `0.28.1` | Bidirectional V1/V2 updates with UTF-16 offsets |
| `y-protocols` | `1.0.7` | Awareness updates, heartbeats, and expiry |

The library also supports snapshots, relative positions, typed schemas, and update
merging and conversion. See the [compatibility details](YJS_COMPATIBILITY.md) for
root-type resolution, wire restrictions, and the optional process-wide decoded
nesting limit. Release history is in the [changelog](CHANGELOG.md).

## Build and verification

The Gradle wrapper uses Gradle 9.8.2 and Kotlin 2.4.21. Run the JVM unit suite with
`./gradlew test` on JDK 21. Cross-language verification also uses Node.js 22+ and
Rust 1.99.0. The standalone consumer checks compile against YKS with Kotlin 2.4.21
and 2.3.21.

```sh
npm ci
npm run test:interop
./gradlew check consumerSmokeTest consumerKotlinCompatibilityTest
npm run test:security
npm audit
cargo audit --file interop/yrs-oracle/Cargo.lock
```

`test:security` checks OSV advisories for locked JVM dependencies and verified
build/consumer artifacts, including the Kotlin Gradle plugin. `cargo audit` requires
[cargo-audit](https://crates.io/crates/cargo-audit). CI runs these advisory checks on
each push and pull request, and the Security workflow repeats them weekly.

## License

YKS is distributed under the [MIT License](LICENSE). Published JARs include the license and [Yjs/lib0 notices](THIRD_PARTY_NOTICES) under `META-INF`.
