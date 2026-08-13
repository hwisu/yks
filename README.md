# YKS

YKS는 Yjs 문서 모델과 업데이트 프로토콜의 Kotlin/JVM CRDT 라이브러리입니다(JDK 21).

- `YArray`, `YMap`, `YText`, live XML, 서브도큐먼트
- 트랜잭션·옵저버, 스냅샷, 상대 위치, `UndoManager`
- `y-protocols` 호환 Awareness
- typed schema와 opt-in v14 `Type`/`DeltaBuilder`
- Yjs update V1/V2 적용·병합·diff·변환 및 state vector
- 스레드 접근 정책과 외부 업데이트 자원 제한

Yjs `13.6.32`, `@y/y` `14.0.0-rc.24`, UTF-16 모드 Yrs `0.27.2`와 양방향 wire 상호 운용을 검증합니다.

## 설치

classic PAT(`read:packages`)를 `~/.gradle/gradle.properties`에 저장하세요.

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
dependencies { implementation("dev.yks:yks:0.2.9") }
```

## 빠른 시작

```kotlin
import dev.yks.*

val source = YDoc(clientId = 1, gc = false)
source.getText("body").insert(0, "hello")

val target = YDoc(clientId = 2, gc = false)
applyUpdate(target, encodeStateAsUpdate(source))
check(target.getText("body").toString() == "hello")
```

V2 연결에는 `encodeStateAsUpdateV2`와 `applyUpdateV2`를 사용합니다.

## 호환성

일반 API는 실제 Yjs V1/V2 바이트만 반환하며 Kotlin 전용 상태는 거절합니다. `*Lossless` API는 JavaScript Yjs가 읽지 못하는 `YKS` envelope를 쓸 수 있으므로 YKS 피어 사이에서만 사용하세요. 원격 root는 wire에 타입 정보가 없어 typed getter나 `YRootSchemaRegistry`가 필요할 수 있습니다. XML `toDOM`은 W3C DOM이며 provider·editor binding은 별도 범위입니다. 자세한 내용은 [호환성 문서](YJS_COMPATIBILITY.md)를 참고하세요.

## 빌드와 검증

전체 검증에는 JDK 21, Node.js 22+, Rust 1.97.0이 필요합니다.

```sh
npm ci
npm run test:interop
./gradlew check consumerSmokeTest consumerKotlinCompatibilityTest
```

## 라이선스

YKS는 [MIT License](LICENSE)로 배포됩니다. 배포 JAR에는 라이선스와 [Yjs/lib0 고지](THIRD_PARTY_NOTICES)가 `META-INF` 아래 포함됩니다.
