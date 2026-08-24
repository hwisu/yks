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
dependencies { implementation("dev.yks:yks:0.2.11") }
```

## 빠른 시작

```kotlin
import dev.yks.*

val source = YDoc(clientId = 1, gc = false)
source.getText("body").insert(0, "hello")

val target = YDoc(clientId = 2, gc = false)
val update = encodeStateAsUpdate(source, target.encodeStateVector())
applyUpdate(target, update)
check(target.getText("body").toString() == "hello")
```

V2 연결에는 `encodeStateAsUpdateV2`와 `applyUpdateV2`를 사용합니다.

## 운영 시 주의사항

- `YDoc`은 기본적으로 단일 스레드에 귀속됩니다. coroutine/server에서는 호출을 직렬화하고 `YThreadAccessPolicy.EXTERNALLY_SERIALIZED`를 사용하세요.
- 신뢰하지 않는 업데이트에는 애플리케이션 크기에 맞는 `YUpdateLimits`를 설정하세요. 기본값은 호환성을 위해 별도 상한을 두지 않습니다.
- JavaScript Yjs와 통신할 때는 표준 API만 사용하세요. `*Lossless` 바이트는 YKS 피어 전용입니다.

## 호환성

지원 버전과 wire 경계는 [호환성 문서](YJS_COMPATIBILITY.md)를 참고하세요. 변경 내역은 [CHANGELOG](CHANGELOG.md)에 기록합니다.

## 빌드와 검증

전체 검증에는 JDK 21, Node.js 22+, Rust 1.97.0이 필요합니다.

```sh
npm ci
npm run test:interop
./gradlew check consumerSmokeTest consumerKotlinCompatibilityTest
```

## 라이선스

YKS는 [MIT License](LICENSE)로 배포됩니다. 배포 JAR에는 라이선스와 [Yjs/lib0 고지](THIRD_PARTY_NOTICES)가 `META-INF` 아래 포함됩니다.
