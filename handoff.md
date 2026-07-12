# YKS Yjs interoperability handoff

마지막 업데이트: 2026-07-12 KST

## 저장소 상태

- 저장소: `/Volumes/D/yks`
- 원격: `https://github.com/hwisu/yks.git`
- 브랜치: `main`
- 기준 원격 커밋: `origin/main` = `d2e93a6`
- 이번 커밋 반영 후 `origin/main`보다 2개 커밋 앞섬
- 2026-07-12 정밀 감사에서 기존 완료 선언과 달리 추가 parity blocker를 확인하여 개선 작업 진행 중
- 모든 개선 커밋은 구현·회귀 테스트와 이 문서를 함께 갱신함

현재 커밋 이력:

```text
HEAD fix: close audited Yjs interoperability gaps
3b40be1 fix: match Yjs sequence conflict ordering
d2e93a6 docs: finalize Yjs Kotlin implementation handoff
befbff7 feat: emit standard delete transaction updates
50b2b2e feat: preserve pending GC update metadata
828c39d feat: emit standard root transaction updates
a1e550e feat: harden Yjs update decoding
e6a9b59 feat: extend native Yjs subdocument parity
50a6505 feat: match upstream Yjs XML rendering
ffd46dd feat: complete Yjs update V2 operations
1d3d219 feat: write genuine Yjs update V2
4c826bf feat: decode genuine Yjs update V2
d454f42 feat: add lib0 update V2 stream codecs
fbc214f test: add multi-client Yjs convergence oracle
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

- 기존 private `YKS\x01` decode compatibility를 유지하고 writer는 metadata-capable `YKS\x02` 사용
- 새 `UpdateCodec`은 다음처럼 dispatch함:
  - `YKS\x01`/`YKS\x02` magic이면 legacy decode
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

### 8. update V2 stream foundation

- lib0 signed-magnitude varint의 negative zero 지원
- byte RLE, `UintOptRle`, `IntDiffOptRle`, concatenated string stream Kotlin 구현
- upstream lib0가 만든 golden bytes와 byte-exact test 추가
- `UpdateEncoderV2`의 실제 9-stream envelope 구현
  - key clock, client, left/right clock, info, string, parent info, type ref, length
  - non-optimized rest stream append
- `UpdateDecoderV2`가 동일 envelope를 분리하고 primitive field를 decode
- upstream `Y.UpdateEncoderV2` primitive fixture와 전체 byte sequence 일치
- 아직 document update API는 V1 compatibility path를 유지하며 다음 단계에서 V2 struct codec에 연결할 예정

### 9. genuine update V2 decoder

- V2 client struct header/count/clock을 rest stream과 optimized client/info stream에서 decode
- origin/right-origin, parent info, parentSub를 V2 stream 규칙으로 복원
- content ref 1~9를 각 V2 stream에서 decode
  - deleted, JSON, binary, string, embed, format, type, any, subdocument
- V2 delta-compressed delete set decode
- 기존 `DocumentUpdate` integration/pending/delete machinery에 연결
- `applyUpdateV2`, `readUpdateV2`, `createDocFromUpdateV2`, `decodeUpdateV2`를 genuine decoder로 전환
- upstream V2 fixture 검증:
  - hello text, packed any/binary array, formatted text, delete set
  - formatted XML, subdocument options, merged multi-client formatting
- 기존 V1-shaped V2 aliases를 사용하는 API에는 migration 기간 compatibility decode 유지

### 10. genuine update V2 writer와 conversion

- `DocumentUpdate`를 실제 V2 optimized stream으로 작성
- struct counts/start clocks와 Skip은 rest stream, client/info/origin/content metadata는 전용 stream 사용
- content ref 1~9의 V2 writer 구현
- V2 delta-compressed delete set writer 구현
- delete-only update도 강제로 올바른 V2 envelope 생성
- `encodeStateAsUpdateV2`를 genuine writer로 전환
- `convertUpdateFormatV1ToV2` / `V2ToV1` genuine conversion
- `decodeUpdateV2`, `LazyStructReader(UpdateDecoderV2)`, `logUpdateV2` genuine decode 사용
- Kotlin-authored V2를 upstream `Y.applyUpdateV2`로 검증
  - text, formatting, any/binary array, XML, subdocument, delete set, multi-client state
- pending struct/delete가 있는 문서는 아직 migration compatibility path를 유지

### 11. genuine V2 operational APIs와 events

- `mergeUpdatesV2`, `diffUpdateV2`, `encodeStateVectorFromUpdateV2` genuine decode/encode
- `createContentIdsFromUpdateV2`, `intersectUpdateWithContentIdsV2` genuine V2 처리
- `obfuscateUpdateV2` genuine V2 output
- selected range/id-set와 transaction struct V2 writer 전환
- transaction update-message V2 writer 전환
- `writeStateAsUpdateV2` / `writeClientsStructs(UpdateEncoderV2)` genuine payload 반환
- pending delete/struct update를 V2로 변환하고 `mergeUpdatesV2`로 직렬화
- `updateV2` listener와 event channel이 genuine V2 transaction payload 방출
- upstream `Y.applyUpdateV2`로 merged full update, baseline+diff sequence, updateV2 event 검증

### 12. XML 문자열 surface parity

- `YXmlText`와 live `YXmlTextType.toString()`이 text delta의 format attribute를 upstream처럼 tag로 렌더링
- format key는 안정적인 순서로 중첩하고 map-valued format은 tag attribute로 출력
- static, live, remote sync, deep delta, snapshot 문자열 경로에서 같은 결과를 검증
- 빈 `YXmlElement`와 `YXmlElementType`을 upstream처럼 `<p></p>`로 렌더링
- upstream `Y.XmlText`와 동일하게 XML text content를 escape하지 않음
- Kotlin V1 fixture decode 결과가 `<p class="intro"><strong level="1">hi</strong></p>`인지 직접 검증
- Kotlin의 별도 extension인 forced empty fragment 표기는 기존 `<xml />` 계약 유지

### 13. subdocument sequence와 lifecycle parity

- low-level `ContentDoc`를 `Y.Text`와 `Y.XmlText` 내부에서 native content ref 9로 V1/V2 작성
- Kotlin-authored text/XML-text subdocument full update를 upstream Yjs가 적용하고 `doc.subdocs`에 등록하는지 검증
- text/XML-text 내부 subdocument deletion update도 표준 V1 delete set으로 작성하고 upstream sequence로 검증
- 같은 transaction에서 추가 후 삭제된 subdocument는 upstream처럼 added/removed에서 상쇄하고 loaded event만 유지
- subdocument reference 삭제 시 parent subdocs event 후 child destroy, 이어 cleanup removal transaction/event가 발생하는 upstream 순서 구현

### 14. codec allocation/overflow hardening

- decoded collection count를 최대 1,000,000개로 제한하고 모든 V1/V2/legacy count 변환에 checked `Long`→`Int` 적용
- decoded string/buffer payload를 최대 64 MiB로 제한하고 remaining-byte 검사에서 `Int` 덧셈 overflow 제거
- V1/V2 struct clock, skip, GC length, V2 delete delta 누산에 overflow-safe addition 적용
- GC expansion count와 split item clock에도 동일한 checked conversion/arithmetic 적용
- decoded anchor lookup을 client별 정렬 index + binary search로 전환해 large update의 반복 linear scan 제거
- oversized state-vector count, string/buffer length, struct clock overflow regression test 추가

### 15. root transaction-event V1 standardization

- root text/array/map의 lossless insert/update transaction은 `update` event에서 표준 V1 payload 방출
- upstream fixture relay와 Kotlin-authored formatted root text event를 실제 `Y.applyUpdate`로 검증
- 기존 API와 동일한 `isSupportedV1Update` gate를 통과하지 못하면 legacy envelope 유지
- pre-populated detached nested owner와 nested child mutation은 이름 통합 전까지 legacy 유지
- 이 제한으로 기존 bidirectional UndoManager 동기화 의미를 보존

### 16. pending/GC private serialization metadata

- private fallback writer를 `YKS\x02`로 올리고 `requiresClockContinuity`와 `isGc`를 item마다 직렬화
- 기존 `YKS\x01` payload는 metadata 기본값으로 계속 decode
- pending struct view를 decode/re-encode해도 GC와 clock-continuity metadata가 보존되는 regression test 추가
- sparse content intersection은 의도적인 standalone chunk이므로 continuity requirement를 명시적으로 해제
- V1/V2 native GC fixture의 clock ownership/convergence 검증과 함께 pending metadata 손실 제거

### 17. delete transaction-event V1 standardization

- root delete-set transaction의 `update` event도 표준 V1 payload로 전환
- subdocument insert, delete, destroy-cleanup에서 방출된 3개 event payload 모두 표준 V1인지 검증
- 실제 upstream `Y.applyUpdate` sequence가 최종 subdocument deletion state로 수렴하는지 검증
- detached nested owner fallback을 유지한 상태에서 bidirectional UndoManager regression이 다시 통과함을 확인

### 18. Yjs sequence conflict ordering

- 기존 `origin` child DFS + sibling 정렬을 upstream `Item.integrate`와 같은 전역 conflict scan으로 교체
- causal dependency를 Kahn queue로 준비하고 실제 left/right linked sequence에 통합해 대형 sequence에서도 반복 list scan을 피함
- transaction의 parent-before snapshot은 parent/kind별 최초 1회만 캡처하고 monotonic client append는 O(1) store path 사용
- `rightOrigin`이 서로 다른 origin subtree 사이를 제한하는 경우도 전체 연결 순서에서 처리
- anchor와 동일 client의 앞 clock이 먼저 통합된 뒤 항목을 배치하도록 causal replay 보장
- 정밀 감사에서 발견한 5-update 최소 반례를 회귀 테스트로 추가
  - upstream 결과: `[c, base]`
  - Kotlin 결과도 `[c, base]`, pending struct 없음
- 기존 15,000 nested insertion regression과 500-seed differential을 함께 통과하도록 correctness와 성능을 검증

### 19. 대형 range와 lib0 값 domain

- `ContentDeleted`/GC를 clock 단위 item 수십억 개로 펼치지 않고 단일 range `StoreItem`으로 보존
- `2^32`, `2^32+1` 길이의 genuine V1/V2 decode, state vector, relay, partial state-vector slicing 검증
- 대형 pending delete 적용에서 clock 단위 순회를 제거하고 range/struct end overflow를 checked addition으로 통일
- UTF-16 surrogate pair를 표준 `ContentString`으로 묶어 emoji/non-BMP V1/V2 상호운용 지원
- lib0/YValue의 `undefined`, signed zero, BigInt64, NaN, ±Infinity 보존
- JS object key enumeration 순서(정수 index 우선, 나머지는 insertion order) 보존
- 사용자 formatting key `__yks_text_format`이 private payload로 오인되지 않도록 schema 판별 강화
- direct default subdocument는 upstream처럼 standard wire로 내보내고 receiver `shouldLoad=false` 의미 검증
- nested collection 안 subdocument는 표준 ref 9로 표현할 수 없으므로 유실 없이 명시적 private fallback 유지

### 20. merge, pending parent metadata, V2 snapshot

- baseline + incremental `mergeUpdates`/`mergeUpdatesV2`가 union 안의 inherited/nested parent를 해소해 genuine V1/V2 출력
- standalone V1↔V2 conversion은 anchor가 없는 dependency를 보존하고 genuine incremental wire 유지
- synthetic root-name prefix 추론을 `UnresolvedYjsParent` 명시 메타데이터로 교체
- 실제 root 이름이 `__yjs_inherit__:*` 또는 `__yjs_nested__:*`여도 pending으로 오인하지 않음
- private envelope를 `YKS\x03`으로 올려 unresolved parent를 직렬화하고 v1/v2 backward decode 유지
- `snapshotContainsUpdateV2`가 genuine V2 decoder를 사용하도록 수정

### 21. 공개 JSON/text/XML semantics

- `YArray.toJSON()`은 list, `YMap.toJSON()`은 plain map, XML `toJSON()`은 XML string 반환
- `YDoc.toJSON()`도 위 shared-type 결과를 그대로 연결
- `YText.toString()`과 snapshot string에서 embed를 upstream처럼 제외
- text insert의 attributes 생략(ambient formatting 상속)과 명시적 empty map(unformatted)을 sentinel로 구분
- `insertEmbed` attributes 생략은 upstream처럼 unformatted 유지
- XML element tag lowercase, quoted attributes, JavaScript `String(value)` coercion 규칙 적용

### 22. oracle, fuzz, build gate, CI

- subdocument verifier가 GUID/set만 보지 않고 실제 Y.Text/Y.XmlText ContentDoc placement를 검사
- delete verifier가 insert-before-delete sequence를 확인해 empty update false positive 차단
- JS oracle에 verifier regression과 default subdocument scenario 추가
- upstream Yjs가 생성한 concurrent array/text/map update를 Kotlin과 비교하는 deterministic 500-seed differential test 추가
- `Gradle check`가 `interopTest`에 의존하도록 연결
- GitHub Actions에서 Java 21 + Node 22, fixture generation, JS oracle, Gradle check, fixture diff 실행

### 23. update metadata/state export

- `parseUpdateMeta`/`parseUpdateMetaV2`와 `UpdateMeta(from, to)` 추가
- struct section의 client별 시작/끝 clock을 V1/V2에서 반환하고 delete-only update는 빈 bounds 반환
- upstream `getState(store, client)`와 document overload 추가

### 24. PermanentUserData

- upstream과 같은 `users -> user map -> ids/ds arrays` shared-type layout 구현
- `setUserMapping`, `getUserByClientId`, `getUserByDeletedId` 공개 API 추가
- local transaction delete set 기록, filter, user entry replacement migration, subscription cleanup 지원
- 두 client mapping과 delete attribution을 standard update로 동기화한 뒤 Kotlin 및 실제 Yjs 13.6.31에서 복원 검증

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

이 경우 모두 legacy `YKS` codec으로 fallback하는 테스트가 있음.

## 마지막 검증 결과

마지막 전체 검증:

```text
./gradlew clean check --no-daemon
BUILD SUCCESSFUL
```

- 일반 Kotlin tests: 549 passed
- Kotlin Yjs V1/V2 interop tests: 73 passed
- JavaScript/Yjs oracle tests: 78 passed
- deterministic upstream differential: 500 seeds, 0 failures
- 15,000 nested insertion regression: passed
- fixture regeneration diff: clean
- `git diff --check`: passed

검증 명령:

```sh
cd /Volumes/D/yks
npm run interop:generate
npm run test:interop
./gradlew clean check --no-daemon
git diff --check
```

이 머신의 Codex shell에서는 위 명령 앞에 `rtk`를 붙여 실행해야 함.

## 정밀 감사 후 개선 상태

기존의 "semantic/convergence blocker 없음" 선언은 철회함. 이번 커밋에서 핵심 sequence ordering blocker를 수정했으며, 다음 항목은 후속 커밋에서 계속 개선해야 함:

1. 표준 update API에서 남아 있는 static XML/Kotlin-only extension의 private `YKS` fallback을 명시적으로 분리
2. detached/preliminary shared type의 동일 instance integration과 owner-before-child clock 의미 구현
3. map key iteration order, adjacent equal-format delta segmentation 등 남은 공개 API 세부 의미 정렬
4. map형 `YXmlHook`, typed event/deep-observe contract 등 공개 type surface 보강
5. transaction cleanup의 automatic GC와 남은 delete-set/public export 보강

아래 항목은 기존 구현의 compatibility 제약 기록이며, 위 blocker를 모두 해결한 뒤 실제 남은 JVM adaptation만 재분류해야 함:

1. root XML schema
   - root shared type kind와 root `XmlElement` node name은 Yjs update wire에 없음
   - receiver는 apply 전에 예상 root XML kind/node name을 pre-materialize해야 함
2. Kotlin-only subdocument options
   - `collectionId`, suggestion-doc 등 upstream wire에 없는 option은 `YKS\x02` lossless envelope 사용
   - 표준 V1/V2로 조용히 option을 유실시키지 않음
3. GC storage compactness
   - wire/pending metadata와 clock semantics는 완전히 보존함
   - GC range를 내부에서 unit `StoreItem`으로 펼치는 구현은 향후 메모리 최적화 대상으로만 남김
4. detached preliminary nested content
   - Kotlin에서 owner보다 먼저 clock을 할당받은 pre-populated detached child는 표준 Yjs owner-before-child wire로 무손실 변환할 수 없음
   - 해당 transaction만 `YKS\x02`를 사용하며 owner-first nested content와 root insert/update/delete events는 표준 V1/V2 사용

새 기능을 추가할 때는 이 constraint를 깨뜨리기보다 fixture/oracle을 먼저 추가하고 lossless standard eligibility를 넓힐 것.

## 주의 사항

- root shared type kind는 Yjs update wire에 기록되지 않음. 모호한 root XML type은 apply 전에 예상 kind를 materialize해야 함.
- Yjs clock은 UTF-16 code unit 기준임.
- `Skip`은 clock ownership이 아니며 store/state vector에 들어가면 안 됨.
- `GC`는 clock을 소유하며 anchor/parent가 GC이면 dependent item도 GC로 통합해야 함.
- packed struct 내부 ID를 anchor로 사용할 수 있으므로 start-ID lookup만 사용하면 안 됨.
- delete set은 struct integration 뒤에 적용되며 아직 없는 range는 pending으로 유지해야 함.
- standard writer eligibility를 넓힐 때는 손실 없는 경우만 허용하고, 애매하면 legacy fallback을 유지할 것.
