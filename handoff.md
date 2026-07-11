# YKS Yjs interoperability handoff

마지막 업데이트: 2026-07-12 KST

## 저장소 상태

- 저장소: `/Volumes/D/yks`
- 원격: `https://github.com/hwisu/yks.git`
- 브랜치: `main`
- 현재 `origin/main`보다 10개 커밋 앞서 있음
- 아직 push하지 않음
- 네이티브 XML/subdocument V1 작업은 구현과 전체 검증이 끝났으며 이 문서와 함께 커밋함

현재 커밋 이력:

```text
HEAD test: add multi-client Yjs convergence oracle
a46acf5 feat: author native Yjs attributed inserts
2a03ad7 feat: author native Yjs text formatting
5436954 feat: emit standard Yjs V1 delete sets
65ed0f8 feat: add Yjs V1 XML and subdocument parity
68198d8 feat: support native Yjs V1 text formatting
a19f4ac feat: decode and integrate Yjs V1 updates
357db04 feat: expand Yjs V1 update writer
80f8ae9 feat: add Yjs V1 text update codec
5401a0b test: add Yjs V1 interoperability harness
```

## 완료된 작업

### 1. 공식 Yjs V1 상호운용성 하네스

- Yjs `13.6.31` 고정
- deterministic client ID와 커밋된 binary fixture 사용
- JavaScript oracle:
  - `npm run interop:generate`
  - `npm run test:interop`
- Kotlin gate:
  - `./gradlew interopTest`
- Kotlin이 만든 update를 실제 Yjs가 적용하는 verifier와 update-sequence verifier 추가

주요 경로:

- `interop/yjs-v1/`
- `src/test/kotlin/dev/yks/YjsV1InteropTest.kt`

### 2. 표준 Yjs update V1 codec 기반

- 기존 private `YKS\x01` codec은 `LegacyUpdateCodec`으로 유지
- 새 `UpdateCodec`은 다음처럼 dispatch함:
  - `YKS\x01` magic이면 legacy decode
  - 그 외에는 표준 Yjs update V1 decode
  - V1로 손실 없이 표현 가능한 update만 표준 V1으로 encode
  - 아직 표현하지 못하는 update는 legacy envelope로 fallback
- lib0 any/varint/string/binary codec과 golden test 추가
- client ID 생성 규칙을 Yjs의 unsigned 32-bit 범위에 맞춤

### 3. 표준 V1 writer

현재 표준 V1으로 내보낼 수 있는 범위:

- unformatted root `Y.Text`
- `Y.Array`, `Y.Map`
- binary 값
- owner-first nested map/text
- 일부 incremental nested updates
- upstream에서 받은 native `ContentFormat` rich-text marker relay
- owner-first live XML tree
- direct `Y.Array`/`Y.Map` subdocument

표준 writer가 보장할 수 없는 경우에는 기존 `YKS` envelope를 사용함.

### 4. 표준 V1 decoder와 integration

구현된 핵심 동작:

- packed `ContentString`/`ContentAny`를 clock 단위 item으로 확장
- packed struct 내부를 가리키는 `origin`/`rightOrigin` 처리
- inherited parent와 `parentSub` 처리
- map replacement와 delete set 처리
- same-client clock gap pending
- cross-client origin/right/explicit nested parent pending
- pending update deduplication과 stale pending 제거
- out-of-order update 재시도
- GC struct와 Skip struct 구분
  - Skip은 store clock을 소유하지 않음
  - GC는 clock을 소유하고 뒤 struct의 integration을 허용
- GC anchor/parent propagation
- 삭제된 nested owner 뒤에 늦게 도착한 child도 삭제 상태로 통합
- opaque deleted content가 후속 text/map type을 잘못 덮어쓰지 않도록 처리
- pending delete가 나중에 도착한 item에 적용될 때 transaction delete metadata도 갱신

### 5. native Yjs rich-text formatting

Kotlin의 기존 range 기반 `ItemContent.TextFormat`은 유지하고, upstream marker용 타입을 별도로 추가함:

```text
ItemContent.NativeTextFormat(key, value, kind)
```

지원 범위:

- `ContentFormat` ref 6 decode/encode
- `null` marker로 attribute 제거
- 이전 non-null attribute 값 복원
- marker 순서에 따른 `Y.Text.toDelta()` 계산
- marker-only incremental update와 reverse-order delivery
- text embed formatting
- observer retain delta
- snapshot rendering
- legacy transaction update 안에서 marker 보존
- upstream fixture를 Kotlin이 표준 V1으로 다시 relay
- formatted `Y.XmlText` marker relay

Kotlin이 직접 생성하는 range formatting도 native marker pair로 마이그레이션함.

추가 구현:

- `Y.Text.format` / `formatText`와 `Y.XmlText.format`이 native `ContentFormat` marker를 생성
- 겹치는 formatting은 현재 visible attributes를 기준으로 canonical marker sequence로 재작성
- marker 시작/종료 상태와 명시적 embed attributes 사이의 formatting leakage 방지
- native marker cleanup equivalence와 transaction observer retain delta 지원
- undo/redo 시 marker origin/right-origin을 복원된 content ID로 재연결
- deep-delta format attribution이 native marker의 insert/delete attribution을 추적
- Kotlin-authored partial text formatting과 formatted XML text를 upstream Yjs에서 검증

명시적 attributes를 가진 신규 text/embed insert도 native marker pair를 생성함.

- attributed text/list/embed insert의 base attributes를 비우고 시작/복원 `ContentFormat` marker 기록
- text position anchor가 같은 위치의 기존 marker를 포함해 insertion order를 보존
- attribute 없는 insert는 upstream Yjs처럼 현재 위치의 active formatting을 상속
- `ContentEmbed` ref 5의 표준 V1 writer eligibility 추가
- deep-delta sequence rendering에서 non-countable format marker를 retain 길이에 포함하지 않음
- snapshot, clone, undo/redo, update transform/obfuscation 테스트를 native marker clock layout에 맞춤
- Kotlin-authored attributed text와 embed를 upstream Yjs에서 직접 검증

### 6. 표준 V1 delete-set writer와 삭제 parity

- non-empty delete set을 표준 Yjs update V1으로 기록
- full-state deleted struct와 state-vector 기반 delete-only incremental update 지원
- Kotlin-authored text/XML/subdocument 삭제를 upstream Yjs full/sequence verifier로 검증
- standard update 수신 시 XML observer delete metadata와 subdocument removal event 검증
- `decodeUpdate`, `LazyStructReader`, content-ID helper가 delete set을 public struct의 `deleted` metadata에 반영

### 7. multi-client convergence와 표준 V1 relay

- upstream Yjs로 생성한 3-update/3-client deterministic fixture 추가
  - 같은 array 위치의 concurrent `X`/`Y` insert
  - 겹치는 `bold`/`italic` text formatting
- base보다 concurrent update가 먼저 오는 경우를 포함한 6개 delivery permutation 전부 검증
- JavaScript oracle과 Kotlin이 동일한 sequence/attribute 결과로 수렴
- Kotlin에서 합친 multi-client document를 표준 V1으로 relay하고 upstream Yjs가 적용
- 표준 writer eligibility를 single-client 제한에서 client별 clock-continuity 검증으로 확장
- multi-client anchor, nested parent kind, inherited metadata를 전체 update ID 집합에 대해 검증

## 완료된 작업: XML + subdocument V1

다음 구현과 fixture를 XML/subdocument parity 커밋에 포함함:

- `src/main/kotlin/dev/yks/YjsV1UpdateCodec.kt`
- `src/main/kotlin/dev/yks/YDoc.kt`
- `src/test/kotlin/dev/yks/YjsV1InteropTest.kt`
- `interop/yjs-v1/README.md`
- `interop/yjs-v1/generate-fixtures.mjs`
- `interop/yjs-v1/decoder-fixtures.test.mjs`
- `interop/yjs-v1/scenarios.mjs`
- `interop/yjs-v1/scenarios.test.mjs`
- `interop/yjs-v1/verify-update.mjs`
- `interop/yjs-v1/verify-update-sequence.mjs`
- 새 XML/subdocument binary fixtures

### XML 구현 내용

- upstream `ContentType` 기반 live XML tree decode/relay
- `XmlFragment -> XmlElement -> XmlText` 계층 보존
- `XmlElement` node name 보존
- XML attribute는 `parentSub != null`일 때 `XmlNode`가 아니라 `MapEntry`로 decode
- 같은 client와 다른 client의 XML owner/content update를 어느 순서로 적용해도 수렴
- 이미 materialize된 root `XmlElement`의 known kind를 이용해 wire ambiguity 보정
- `XmlText`의 native format marker와 attribute delta 보존
- owner-first Kotlin live XML을 표준 V1으로 작성하고 upstream Yjs에서 검증

추가된 fixture:

```text
xml-owner-v1.bin
xml-content-v1.bin
xml-cross-client-content-v1.bin
xml-basic-full-v1.bin
xml-formatted-full-v1.bin
xml-root-element-v1.bin
```

### subdocument 구현 내용

- direct `Y.Array`/`Y.Map`의 `ContentDoc` ref 9 decode/encode
- upstream과 동일한 option write 순서와 생략 규칙:
  - `gc: false`일 때만 기록
  - `autoLoad: true`일 때만 기록
  - non-null `meta`만 기록
- decoded subdocument instance ID를 GUID가 아니라 struct ID에서 생성
  - 같은 GUID를 가진 서로 다른 ContentDoc struct가 하나의 Kotlin `YDoc`으로 합쳐지지 않음
  - 동일 update를 다시 적용하면 같은 instance를 재사용
- `gc`, `autoLoad`, `shouldLoad`, `meta` 보존
- add/load subdocument event 검증
- safe Kotlin-authored subdocument를 표준 V1으로 출력하고 upstream Yjs에서 검증

추가된 fixture:

```text
subdoc-map-default-v1.bin
subdoc-array-options-v1.bin
subdoc-duplicate-guid-v1.bin
```

표준 V1에서 의도적으로 제외한 경우:

- static compact `ItemContent.XmlNode`
- owner보다 child clock이 먼저 생긴 pre-populated detached XML type
- root `XmlFragment`의 Kotlin-only attributes
- `collectionId` 또는 suggestion-doc 같은 비표준 subdocument 옵션
- `shouldLoad=true`, `autoLoad=false`처럼 upstream wire에서 보존할 수 없는 상태
- text/XML 내부 subdocument
- text/XML 내부 subdocument delete update

이 경우 모두 legacy `YKS` codec으로 fallback하는 테스트가 있음.

## 마지막 검증 결과

마지막 전체 검증:

```text
./gradlew test interopTest --no-daemon
BUILD SUCCESSFUL
```

- 일반 Kotlin tests: 508 passed
- Kotlin Yjs V1 interop tests: 55 passed
- JavaScript/Yjs oracle tests: 74 passed
- `git diff --check`: passed

검증 명령:

```sh
cd /Volumes/D/yks
npm run interop:generate
npm run test:interop
./gradlew test interopTest --no-daemon
git diff --check
```

이 머신의 Codex shell에서는 위 명령 앞에 `rtk`를 붙여 실행해야 함.

## 아직 남은 주요 Yjs parity

우선순위가 높은 잔여 작업:

1. XML surface parity
   - upstream `Y.XmlText.toString()`은 format을 tag로 렌더링하지만 Kotlin은 plain text로 렌더링함
   - empty element 문자열 표현도 upstream과 다름
   - root `XmlElement` node name은 wire에 없으므로 schema/pre-materialization이 필요함
2. subdocument 확장
   - deletion event cancellation/ordering
   - text/XML 내부 ContentDoc
   - upstream에 없는 local options 처리 정책
3. 실제 update V2 codec
   - 현재 여러 V2 API는 V1/legacy 구현에 alias되어 있음
4. codec hardening
   - decoded count/length allocation limit
   - Long overflow/Long-to-Int guard
   - large update에서 anchor lookup indexing
5. GC/pending serialization 완성
   - GC를 unit `StoreItem`으로 근사 중
   - 일부 legacy pending serialization은 `isGc`/clock-continuity metadata를 완전히 표현하지 못함
6. transaction-event update
   - 현재 event update는 로컬 동작 보존을 위해 강제로 legacy envelope를 사용함

## 다음 권장 작업 순서

1. 실제 update V2 codec 분리
2. XML/subdocument surface parity 확장
3. codec hardening과 GC/pending serialization 완성

## 주의 사항

- root shared type kind는 Yjs update wire에 기록되지 않음. 모호한 root XML type은 apply 전에 예상 kind를 materialize해야 함.
- Yjs clock은 UTF-16 code unit 기준임.
- `Skip`은 clock ownership이 아니며 store/state vector에 들어가면 안 됨.
- `GC`는 clock을 소유하며 anchor/parent가 GC이면 dependent item도 GC로 통합해야 함.
- packed struct 내부 ID를 anchor로 사용할 수 있으므로 start-ID lookup만 사용하면 안 됨.
- delete set은 struct integration 뒤에 적용되며 아직 없는 range는 pending으로 유지해야 함.
- standard writer eligibility를 넓힐 때는 손실 없는 경우만 허용하고, 애매하면 legacy fallback을 유지할 것.
