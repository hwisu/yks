# Yjs 호환성

YKS는 JavaScript 객체의 복제품이 아니라 Yjs wire와 문서 의미를 구현한 Kotlin/JVM 엔진입니다.

| 대상 | 검증 버전 | 범위 |
|---|---:|---|
| Yjs | `13.6.32` | 안정 API, update V1/V2, 문서 모델 |
| `@y/y` | `14.0.0-rc.24` | V1/V2 wire와 opt-in `experimental.v14` facade |
| Yrs | `0.27.2` | UTF-16 모드 양방향 wire |
| `y-protocols` | `1.0.7` | Awareness wire |

## 핵심 계약

- 표준 apply·encode·merge·diff·convert API는 실제 Yjs V1/V2 바이트만 처리합니다. V2 API는 V1로 재시도하지 않습니다.
- `*Lossless` API는 Kotlin 전용 상태를 위해 private `YKS` envelope를 사용할 수 있으므로 YKS 피어 사이에서만 사용해야 합니다.
- Yjs wire에는 root 종류와 root XML 요소 이름이 없습니다. 모호한 원격 root에는 typed getter 또는 `YRootSchemaRegistry`를 사용하세요.
- `Item`과 `Transaction`은 안전한 JVM view이며 Yjs의 mutable 내부 객체 API를 그대로 노출하지 않습니다.
- `experimental.v14`는 RC 전체 API가 아닙니다. `Type.delta`는 shallow snapshot이며 지원하지 않는 기능은 안정 API의 delegate를 사용해야 합니다.

## 운영 경계

- 기본 `YUpdateLimits`는 호환성을 위해 JVM 표현 한계까지 허용합니다. 신뢰하지 않는 채널에는 더 작은 문서별 제한을 설정하세요.
- `YDoc`과 attached type은 기본적으로 thread-confined입니다. coroutine/server 환경은 외부 직렬화와 `EXTERNALLY_SERIALIZED` 정책을 함께 사용하세요.
- WebSocket/WebRTC provider와 ProseMirror/Tiptap/CodeMirror binding은 포함하지 않습니다.

CI는 고정된 upstream 버전과 binary fixture, differential fuzz, malformed 입력, ABI 및 게시 패키지 소비 테스트를 실행합니다.
