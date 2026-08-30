# YKS

YKS is a Kotlin/JVM CRDT library implementing the Yjs document model and update protocol (JDK 21).

- `YArray`, `YMap`, `YText`, live XML, and subdocuments
- Transactions, observers, snapshots, relative positions, and `UndoManager`
- `y-protocols` compatible Awareness
- Typed schemas and an opt-in v14 `Type`/`DeltaBuilder` facade
- Yjs update V1/V2 apply, merge, diff, conversion, and state vectors
- Thread-access policies and external-update resource limits

Bidirectional wire interoperability is tested against Yjs `13.6.32`, `@y/y` `14.0.0-rc.24`, and Yrs `0.27.2` in UTF-16 mode.

## Installation

Store a classic PAT with `read:packages` in `~/.gradle/gradle.properties`.

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

// build.gradle.kts
dependencies { implementation("dev.yks:yks:0.2.12") }
```

## Quick start

```kotlin
import dev.yks.*

val source = YDoc(clientId = 1, gc = false)
source.getText("body").insert(0, "hello")

val target = YDoc(clientId = 2, gc = false)
val update = encodeStateAsUpdate(source, target.encodeStateVector())
applyUpdate(target, update)
check(target.getText("body").toString() == "hello")
```

Use `encodeStateAsUpdateV2` and `applyUpdateV2` for V2 connections.

## Production notes

- `YDoc` is thread-confined by default. Serialize coroutine/server calls and use `YThreadAccessPolicy.EXTERNALLY_SERIALIZED`.
- Configure `YUpdateLimits` for untrusted updates. Defaults remain unrestricted for compatibility.
- Use only standard APIs with JavaScript Yjs. `*Lossless` bytes are for YKS peers only.

## Compatibility

See [Yjs compatibility](YJS_COMPATIBILITY.md) for supported versions and wire boundaries. See the [changelog](CHANGELOG.md) for release notes.

## Build and verification

Full verification requires JDK 21, Node.js 22+, and Rust 1.97.0.

```sh
npm ci
npm run test:interop
./gradlew check consumerSmokeTest consumerKotlinCompatibilityTest
```

## License

YKS is distributed under the [MIT License](LICENSE). Published JARs include the license and [Yjs/lib0 notices](THIRD_PARTY_NOTICES) under `META-INF`.
