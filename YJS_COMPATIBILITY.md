# Yjs compatibility

YKS의 안정 기준은 Yjs `13.6.32`이다. `@y/y` `14.0.0-rc.24`는 정식 API
호환 대상이 아니라 V1/V2 wire 회귀 대상으로 함께 검증한다. YKS는 JavaScript 객체의
복제품이 아니라 Kotlin/JVM 문서 엔진이다.

## 보장 범위

- 표준 이름의 V1/V2 apply·decode·metadata·merge·diff·convert API는 실제 Yjs
  envelope만 처리한다. `applyUpdateV2`는 V1으로 재시도하지 않는다.
- `*Lossless` API만 compact XML, 추가 subdocument 옵션 등 Kotlin 전용 상태를
  보존하기 위해 private `YKS` envelope를 사용할 수 있다. 이 바이트를 Yjs로 보내면
  안 된다.
- array, map, formatted text/embed, live XML, root `XmlText`/`XmlHook`, nested type,
  subdocument, transaction/observer, snapshot, relative position, UndoManager, GC와 update
  transform을 구현한다.
- XML 타입은 JVM W3C DOM 기반 `toDOM`과 hook/binding association을 제공한다.
- `dev.yks.awareness`는 `y-protocols` 1.0.7의 JSON wire, client clock, change/update
  구분, 30초 timeout과 15초 heartbeat를 구현한다.

Yjs V1 decoder가 V2 바이트를 빈 업데이트로 받아들이는 동작, V2의 예약 feature 값을
무시하는 동작, lib0 V2 substream의 잘린 typed-array 동작과 zero-length
`ContentString` Item도 upstream oracle과 동일하게 처리한다.

## 공개 객체 표면

`event.changes.added/deleted`는 `Set<Item>`이며 같은 transaction 안에서 안정적인 Item
view를 돌려준다. compact 범위가 필요한 Kotlin 호출자는 `addedIds/deletedIds`를 쓸 수
있다. Item view에는 `left/right`, 삭제 항목을 건너뛰는 `prev/next`, `lastId`, `redone`,
`keep`, `parentType`이 있다. 활성 `Transaction`은 Yjs 형태의 `changed` map과 subdocument
set을 노출한다.

Yjs 14 RC.24의 `AbstractRenderer`, `AttributionsRenderer`, `DiffRenderer`,
`SnapshotRenderer`와 세 factory 이름을 매핑한다. RC.7 계열에서 쓰였던 attribution-manager
별칭과 기존 `TwosetRenderer`도 호환 확장으로 유지한다.

Kotlin의 `Item`/`Transaction` view는 document store를 안전하게 조회하는 JVM adapter다.
Yjs 내부 객체를 임의로 재연결하거나 decoder 결과를 transaction에 직접 integrate하는
mutable 내부 메서드는 제공하지 않는다. `decodeUpdate*`의 `structs`는 변환에 쓰는 DTO이고
`items`는 Yjs 형태의 read-only Item view다. 이 차이는 wire/CRDT 의미 차이가 아니다.

## Yjs 14 RC

v14는 기존 여러 shared type을 하나의 `Type` API로 합친다. YKS는 기존 typed getter를
유지하되 v14가 XML 요소 아래에 직접 기록하는 `ContentString`도 XML text child로
materialize한다. 자동 매트릭스는 다음 네 경로를 V1과 V2 각각 검사한다.

`dev.yks.experimental.v14`는 opt-in `Type`, immutable `Delta`, single-use
`DeltaBuilder`와 `DeltaValue`를 제공한다. root는 wire에 kind가 없으므로
`doc.getType(name, RootKind)`로 projection을 명시한다. data/shared type/subdocument를
서로 다른 value variant로 받아 가짜 type reference를 막고, incompatible content,
범위 초과와 잘못된 nested modify는 document 변경 전에 검증한다. builder는 인접한
insert/retain/delete run을 합치며 적용은 한 transaction에서 수행한다. text/data/type이
섞인 sequence와 XML direct `ContentString`도 표준 wire로 읽고 쓸 수 있다.

이 facade는 기존 ABI나 wire를 바꾸지 않는다. RC.24의 attribution/mark, renderer
correction 반환, live deep-delta cache와 Text/XmlText 밖의 format patch는 아직 직접
매핑하지 않는다. `Type.delta`는 shallow snapshot이며 기존 renderer/deep delta가
필요하면 `Type.delegate`의 안정 API를 사용한다. 표현할 수 없는 연산은 조용히
변환하지 않고 preflight에서 거절한다.

Yjs 14의 lib0 `Schema<T>` marker는 `YSchema<T>`의 `check`/`validate`/`expect` 계약으로
매핑한다. `ydocSchema`, `ytypeAnySchema`, `rendererSchema`와 opt-in
`Yjs14SchemaMarkers`는 typed narrowing을 제공한다. 기존 `$ydoc`/`$ytypeAny` 함수와
renderer type 문자열은 JVM ABI를 위해 유지되며 같은 schema predicate 또는 이름을
사용한다. 이 marker들은 런타임 메타데이터일 뿐 update wire에는 기록되지 않는다.

1. Yjs 14 → Yjs 13
2. Yjs 13 → Yjs 14
3. Yjs 14 → Kotlin
4. Kotlin → Yjs 14

text formatting/non-BMP 문자열, array, map attribute, nested XML element와 direct XML
text를 결과 의미까지 비교하며 Yjs 14 → Kotlin → Yjs 14 재인코딩도 확인한다. RC API는
변경될 수 있으므로 새 RC를 대상으로 주장하려면 alias pin과 매트릭스를 함께 갱신해야 한다.
text root를 legacy array projection으로 여는 경우도 UTF-16 단위 length/value/embed를
Yjs 13 oracle과 비교한다.

## 자원 한계와 malformed 입력

Yjs에는 transport 정책 제한이 없다. 따라서 기본 `YUpdateLimits`는 JVM 표현 한계인
`Int.MAX_VALUE`까지 허용하며, 16 MiB/50,000 struct 같은 YKS 전용 기본 거절 기준은 없다.
애플리케이션은 신뢰하지 않는 채널에 더 작은 document별 값을 명시할 수 있다.

decoder는 wire count를 collection 용량으로 선할당하지 않는다. 잘린 대형 count는 실제
첫 read에서 실패하므로 정상 대형 업데이트에 임의 상한을 두지 않으면서 OOM 유도도
피한다. byte array/collection index는 JVM `Int`, clock은 non-negative `Long` 범위에서
검증된다. malformed 공개 경계는 `YksDecodingException`, 명시적 정책 초과는
`YksUpdateLimitException`이다.

## 검증

CI와 로컬 gate는 설치된 oracle 버전을 실행 전에 exact pin과 대조한다. 현재 검증에는
다음이 포함된다.

- 500 seed concurrent array/text/map;
- 100 seed XML/subdocument/relative-position/V2/UndoManager;
- 200 seed formatted text/embed, complex nested type, direct/deep event, snapshot, GC;
- V1과 V2 각각 1,000 malformed seed의 accept/reject, 결과 text, state vector 비교;
- root `XmlText`/`XmlHook`, W3C DOM, Awareness, standard/private 경계와 v14 facade 양방향 matrix;
- Yrs `0.27.2` UTF-16 mode 양방향 fixture와 publication consumer smoke test.

## 플랫폼·생태계 경계

Yjs wire에는 root shared-type kind와 root `XmlElement` 이름이 없다. 모호한 원격 root는
typed getter 또는 immutable `YRootSchemaRegistry`가 schema를 줄 때 materialize하며
clone/snapshot은 로컬에서 아는 root metadata를 보존한다. Registry는 wire를 바꾸지 않고
root별 결과를 캐시하며, 기존 concrete type·XML 이름과 충돌하면 적용 전에 거절한다.

Awareness state는 immutable `AwarenessState`와 JSON 가능 `YValue`로 제한한다. WebSocket/
WebRTC provider와 ProseMirror/Tiptap/CodeMirror binding은 여전히 별도 생태계 범위다.
`YDoc`과 attached type은 기본적으로 thread-confined이며, 서버/coroutine 환경은
`EXTERNALLY_SERIALIZED` 정책으로 중첩·동시 접근을 fail-fast하게 검사할 수 있다.
