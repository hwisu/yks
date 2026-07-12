# YKS Yjs interoperability handoff

마지막 업데이트: 2026-07-12 KST

## 저장소 상태

- 저장소: `/Volumes/D/yks`
- 원격: `https://github.com/hwisu/yks.git`
- 브랜치: `main`
- 작업 시작 기준 원격 커밋: `origin/main` = `dd019cf`
- 이 문서를 포함하는 릴리스 커밋에서 공개 API·observer/snapshot/GC 후속 감사를 완료하고 패키지 CI를 추가함
- 2026-07-12 정밀 감사에서 확인한 core wire/convergence blocker와 후속 edge case를 모두 수정하고 전체 gate를 재검증함
- JavaScript API·mutable 내부 객체 모델·browser DOM까지 동일한 완전 복제는 아니며, 차이는 `YJS_COMPATIBILITY.md`에 명시함
- 모든 개선 커밋은 구현·회귀 테스트와 이 문서를 함께 갱신함

현재 커밋 이력:

```text
HEAD ci: update GitHub Actions runtimes
f9b6563 feat: publish audited Yjs Kotlin package
dd019cf fix: complete audited Yjs Kotlin parity
570194c fix: align Yjs cleanup map and XML semantics
cada05a fix: harden Yjs update metadata and range merging
92e2908 fix: close audited Yjs interoperability gaps
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

- private `YKS\x01`~`YKS\x04` decode compatibility 유지
- 새 `UpdateCodec`은 다음처럼 dispatch함:
  - `YKS\x01`~`YKS\x04` magic이면 private/lossless decode
  - 그 외에는 표준 Yjs update V1 decode
  - upstream 이름의 표준 API는 genuine V1/V2만 반환하고 표현 불가능하면 명시적 예외 발생
  - `*Lossless` API만 필요한 최소 버전의 private envelope를 명시적으로 사용
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

표준 writer가 보장할 수 없는 경우 표준 API는 `UnsupportedYjsStandardUpdateException`을 던지고,
명시적 `*Lossless` API만 `YKS` envelope를 사용함.

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

- root text/array/map의 표현 가능한 insert/update transaction은 lossless transaction artifact에서도 표준 V1 payload 방출
- upstream fixture relay와 Kotlin-authored formatted root text event를 실제 `Y.applyUpdate`로 검증
- `observeUpdates`/`onUpdate`/`onUpdateV2`는 standard-only channel이며 private payload를 절대 전달하지 않음
- `observeUpdatesLossless`/`onUpdateLossless`/`onUpdateV2Lossless`와 `YTransactionEvent.update`는 Kotlin-only 상태도 보존
- standard gate를 통과하지 못한 transaction은 mutation과 lossless listener를 유지하되 standard listener에서 명시적 예외로 보고

### 16. pending/GC private serialization metadata

- private writer의 `YKS\x02`부터 `requiresClockContinuity`와 `isGc`를 item마다 직렬화
- 기존 `YKS\x01` payload는 metadata 기본값으로 계속 decode
- pending struct view를 decode/re-encode해도 GC와 clock-continuity metadata가 보존되는 regression test 추가
- sparse content intersection은 의도적인 standalone chunk이므로 continuity requirement를 명시적으로 해제
- V1/V2 native GC fixture의 clock ownership/convergence 검증과 함께 pending metadata 손실 제거
- lossless range/id-set 선택에서 sparse continuity나 XML type attributes를 표준 wire로 잘못 축약하지 않고 필요한 경우 `YKS\x04` 사용

### 17. delete transaction-event V1 standardization

- 표현 가능한 root delete-set transaction의 lossless artifact도 표준 V1 payload 사용
- subdocument insert와 delete에서 방출된 2개 wire update payload가 모두 표준 V1인지 검증
- destroy-cleanup은 별도 lifecycle transaction/event로 유지하되 중복 wire update를 방출하지 않음
- 실제 upstream `Y.applyUpdate` sequence가 최종 subdocument deletion state로 수렴하는지 검증
- standard/lossless channel 분리 상태에서 bidirectional UndoManager regression이 다시 통과함을 확인

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
- nested collection 안 subdocument는 표준 ref 9로 표현할 수 없으므로 explicit `*Lossless` 경로에서 private envelope로 보존

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
- GitHub Actions에서 Java 21 + Node 22, fixture generation, JS oracle, Gradle check,
  Maven Local standalone consumer, fixture diff 실행
- SemVer `v*` tag는 `dev.yks:yks:<version>`을 GitHub Packages에 게시
- publish와 remote consumer를 별도 job으로 분리해 게시 후 소비 재검증만 안전하게 재실행 가능
- 원격 consumer는 Maven Local을 배제한 새 Gradle home으로 GitHub Packages artifact를 직접 적용·실행

### 23. update metadata/state export

- `parseUpdateMeta`/`parseUpdateMetaV2`와 `UpdateMeta(from, to)` 추가
- struct section의 client별 시작/끝 clock을 V1/V2에서 반환하고 delete-only update는 빈 bounds 반환
- upstream `getState(store, client)`와 document overload 추가

### 24. PermanentUserData

- upstream과 같은 `users -> user map -> ids/ds arrays` shared-type layout 구현
- `setUserMapping`, `getUserByClientId`, `getUserByDeletedId` 공개 API 추가
- local transaction delete set 기록, filter, user entry replacement migration, subscription cleanup 지원
- 두 client mapping과 delete attribution을 standard update로 동기화한 뒤 Kotlin 및 실제 Yjs 13.6.31에서 복원 검증

### 25. update metadata, packed-range merge, 수치 안전성

- `parseUpdateMeta`/`parseUpdateMetaV2`가 decoder에서 버려지는 `Skip`도 wire clock 범위에 포함하도록 struct section을 직접 추적
- `mergeUpdates`/`mergeUpdatesV2`가 packed `ContentDeleted`/GC를 시작 ID 하나로 dedupe하지 않고 client별 interval coverage로 정규화
  - `[0,5)` 뒤 `[0,10)`을 합쳐도 tail `5..10`을 보존
  - `[0,10)`과 `[5,10)`을 합쳐도 중복 struct나 private fallback 없이 genuine update 유지
- item/origin/right-origin/unresolved-parent/parent/delete-set의 client, clock, length, end를 JS safe-integer 범위까지 검증
- delete-only update도 안전 범위 검사를 건너뛰지 않으며, 초과 값은 반올림되는 표준 wire 대신 lossless private envelope 사용
- V1 JSON text embed/format의 중첩 `Long`도 safe integer만 표준 writer에 허용
- private zig-zag varint가 `Long.MIN_VALUE..Long.MAX_VALUE` 전체를 무손실 기록하도록 unsigned bit emission 수정
- `PermanentUserData`가 user map 또는 `ids`/`ds` array 교체·삭제 뒤에도 기존 client/delete 귀속을 누적 보존하고 새 shared array로 이관

### 26. automatic GC와 DeleteSet 공개 surface

- upstream transaction lifecycle과 같이 direct/deep/`afterTransaction` observer 뒤, `afterTransactionCleanup` 앞에서 automatic GC 수행
- `gc`, `gcFilter`, `keepItem`을 존중하며 transaction update는 GC 전에 캡처해 relay 가능성을 보존
- `UndoManager`가 delete capture와 undo/redo delete를 GC 전에 keep하도록 보강
- GC가 content를 `ContentDeleted`로 교체해도 Item의 구조적 countable flag를 보존해 relative position과 attributed renderer 의미 유지
- `iterateStructsByIdSet`을 range/store 기반으로 바꿔 대형 range의 clock 단위 순회 제거
- 공개 API 추가/정렬:
  - `createDeleteSet`, `createDeleteSetFromStructStore`
  - `equalDeleteSets`, `mergeDeleteSets`, `isDeleted`
  - `iterateDeletedStructs`
- state vector, IdSet, snapshot V1/V2 writer가 JS safe integer 범위를 넘는 좌표/end를 명시적으로 거부

### 27. map order와 native format boundary

- `YMap` iterator/keys/values/entries/forEach는 각 document의 최초 key integration 순서를 유지
  - update/delete/reinsert는 기존 key slot 유지
  - remote update 적용 순서에 따른 document-local 순서와 `mergeUpdates` canonical wire 순서를 upstream fixture로 각각 검증
- `YMap.toJSON()`/`toJson()`은 plain JavaScript object처럼 array-index key를 먼저 숫자 오름차순으로 열거
- visible native `ContentFormat` marker마다 `YText.toDelta()` segment를 flush하고, 같은 attributes라도 builder가 다시 합치지 않도록 boundary-preserving op 추가
- snapshot delta와 clone에서도 같은 segmentation 보존

### 28. YXmlText coercion과 map-backed YXmlHook

- `YXmlTextType` embed/format 값을 JS String/property enumeration 규칙으로 렌더링
  - object `[object Object]`, array/ByteArray index attributes, null/undefined/Boolean/number coercion 검증
- 실제 `YMap` subclass인 `YXmlHook` 추가
  - `hookName`, map API, `toJSON`, `[object Object]` 문자열 의미
  - type-ref 5 V1/V2 read/write, `YDoc.createXmlHook`, clone/materialization 지원
  - hook 내부 변경은 map snapshot/event/delta 의미 사용
- private envelope는 XML type attribute/base-attribute metadata에 V4, unresolved-parent metadata에 V3,
  나머지 lossless-only metadata에 rolling-upgrade 호환 V2를 선택

### 29. 최종 공개 surface와 lossless parity 감사

- 표준/비표준 update 경계를 API와 event channel에서 완전히 분리
  - upstream 이름의 encode/merge/diff/convert/filter/obfuscate API는 genuine V1/V2만 반환
  - private 입력이나 표현 불가능한 Kotlin-only shape는 조용한 fallback 없이 명시적 예외
  - 대응하는 `*Lossless` API는 표준 wire를 우선하고 필요한 경우에만 `YKS\x02`~`YKS\x04` 사용
  - 단일 입력 `mergeUpdates`/`mergeUpdatesV2`도 입력 byte passthrough 대신 요청한 wire format으로 검증·정규화
  - empty/delete-only V1의 leading zero를 V2 envelope로 오인하지 않고 V2 decode 실패 시 구조적으로 V1 재검증
  - duplicate standard/private struct의 continuity, GC, unresolved-parent, text metadata를 입력 순서와 무관하게 병합
  - formatted Text/TextEmbed/ContentType 부분 선택은 실제 CRDT sequence의 active marker를 재구성해 metadata 유실 여부 판정
- public constructor로 만든 preliminary `YArray`/`YMap`/`YText`/live XML을 동일 instance로 integration
  - child를 먼저 채운 뒤 owner에 넣어도 owner-first clock 순서로 replay해 genuine 표준 update 생성
  - nested preliminary graph의 identity, cycle, cross-document 재사용을 mutation 전에 원자적으로 preflight
  - array/map/text/XML의 generic `ContentType` placement와 Kotlin→Yjs verifier 추가
  - preliminary `YText` pending operation 오류는 upstream처럼 기록하되 owner integration과 queue 정리는 완료
  - sparse lossless nested child는 private V3 unresolved-parent metadata로 owner 도착 전 pending 유지
- upstream event surface에 맞춘 typed event와 deep-observe contract 추가
  - `YArrayEvent`, `YMapEvent`, `YTextEvent`, `YXmlEvent`, `YEventChanges`
  - deep observer는 concrete event list를 path depth 기준 stable order로 전달
  - net-noop mutation도 upstream `changedSubs` 기반 `keysChanged`/`childListChanged`를 유지하고 `changes.keys`/delta는 empty
  - `YTextEvent.changes.added/deleted`는 upstream처럼 항상 empty이고, concurrent losing map write는
    `keysChanged`만 유지한 채 `changes.keys`를 empty로 유지
- remote update로만 존재하는 모호한 root는 `YUnopenedRoot` live placeholder로 유지
  - `YDoc.share`의 기존 `Map<String, AbstractYType>` source shape 보존
  - `get`/`getOrNull`은 guessed `YArray`를 만들지 않고 placeholder 반환
  - explicit typed getter가 호출될 때만 concrete root로 교체·정규화하며 unopened root는 `toJSON()`에서 제외
  - document destroy가 아직 materialize되지 않은 placeholder에도 destroy event를 정확히 한 번 전달
- `cloneDoc`과 in-memory snapshot은 capture 시점에 알려진 root kind와 root `XmlElement` node name을 보존
  - snapshot 뒤 새로 연 root가 과거 snapshot document에 섞이지 않음
  - snapshot 이전의 empty root는 복원됨
- generic XML snapshot array/delta는 live `YXmlElementType`/`YXmlTextType` identity를 반환
  - historical XML string/JSON은 `createDocFromSnapshot` 경로에서 snapshot 시점 formatting/attributes를 유지
- UndoManager redo가 remotely deleted owner 아래 child를 고아로 복원하지 않도록 ancestor owner chain 전체의 restore eligibility를 재귀 검증
- `ContentType.copy()`는 upstream `_copy()`처럼 alias가 아닌 detached empty shared-type copy를 반환
- 두 차례 독립 감사에서 추가로 발견된 총 17개 merge/metadata/event/preliminary/snapshot/root/undo/copy
  edge case를 모두 최소 재현과 regression으로 고정

### 30. Yjs 103-export 감사, transaction edge와 패키지 배포

- Yjs `13.6.31`의 103개 export를 독립적으로 전수 대조해 모두 Kotlin 대응점 또는 명시적 JVM adaptation으로 분류
  - 일반 경로 직접 대응 49개
  - Kotlin/default-codec adaptation 18개
  - public/internal contract가 정확히 같지 않은 대응 32개
  - XML document 동작은 지원하지만 browser DOM을 제외한 타입 4개
- `AbstractConnector`, upstream 이름의 type alias, active `Transaction` alias와 별도 `TransactionEvent`,
  `DeleteSet.clients`, clean-start/end, typed snapshot helper 등 공개 surface 보강
- 7개 live shared type의 detached deep `clone()`과 기존 target-document clone을 함께 지원
- XML selector/`insertAfter`, snapshot attributes, `YText.applyDelta` sanitize,
  snapshot `toDelta`/`computeYChange`, format overflow와 array/XML partial-delete-then-throw 동작을 upstream oracle로 고정
- observer/transaction cleanup 후속 감사:
  - multi-span array/text/XML delta와 same-format text insert 병합
  - remote update의 기본 UndoManager capture
  - 빈 transaction lifecycle은 유지하되 wire update는 억제
  - `beforeObserverCalls`의 same-parent mutation은 첫 event/update에 포함하고 다음 event에서 중복 제거
  - 다른 parent/type mutation은 전역 clock으로 숨기지 않고 자기 queued event에 보존
  - queued event의 add/delete 판정은 upstream `Item.mergeWith`와 같은 virtual merged representative ID 사용
  - append 가능한 Any/String은 cleanup 병합하되 prepend/middle, Binary, ContentType 장벽은 후속 event에 보존
  - new-struct scan의 `beforeClock` 경계와 delete/insertion split candidate의 targeted 재병합을 구분해
    unrelated old range 또는 다른 parent를 과병합하지 않음
  - 빈 outer transaction 안 nested mutation은 update를 한 번만 방출
  - 직접 event payload와 실제 update-listener payload를 upstream cleanup phase에 맞춰 각각 캡처
  - `beforeObserverCalls`가 예외를 던져도 type/deep/afterTransaction만 건너뛰고
    GC, afterTransactionCleanup, update, subdocs는 finally phase에서 모두 실행 후 오류를 재전파
- snapshot/GC 후속 감사:
  - partial snapshot DeleteSet 경계에서 packed deleted struct를 실제 store item으로 분할하고 GC는 분할하지 않음
  - snapshot 경계 split은 transaction 내부에서만 노출하고 cleanup 전에 원래 packed struct로 재병합
  - 공식 `tryGc(DeleteSet, StructStore, filter)`는 synthetic transaction 없이 즉시 GC
  - `tryGc` 뒤 호환 가능한 인접 `ContentDeleted`를 실제 논리 순서 안에서만 병합
  - GC로 packed delete range가 된 뒤에도 interior ID relative position을 range 포함 검색으로 해소
  - `createSnapshot(DeleteSet, StateVector)`는 upstream처럼 전달된 참조를 보존
  - snapshot split cache는 mutable reference에도 안정적인 identity-set 의미 사용
- Maven publication 추가:
  - 좌표 `dev.yks:yks:<SemVer>`
  - main CI에서 Maven Local standalone consumer 실행
  - `v<SemVer>` tag workflow가 main 도달 가능성, 전체 oracle/gate, 게시, clean remote consumer를 순서대로 검증
  - publish와 remote consumer job을 분리하고 registry 지연 재시도를 적용
  - GitHub-hosted runner의 Node 20 폐기 경고를 제거하도록 Node 24 기반 공식 Actions를 commit SHA로 고정
- 정확한 호환성 계약과 남은 Kotlin/JVM 차이는 `YJS_COMPATIBILITY.md`에 기록

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
- root `XmlFragment`의 Kotlin-only attributes
- `collectionId` 또는 suggestion-doc 같은 비표준 subdocument 옵션
- `shouldLoad=true`, `autoLoad=false`처럼 upstream wire에서 보존할 수 없는 상태

이 경우 표준 API는 명시적 예외를 내고, 대응하는 `*Lossless` API만 private `YKS` codec을
사용하는 테스트가 있음. Preliminary live XML은 owner-first replay로 표준 V1/V2를 지원함.

## 마지막 검증 결과

마지막 전체 검증:

```text
./gradlew clean check consumerSmokeTest --no-daemon -PreleaseVersion=0.1.0-test
BUILD SUCCESSFUL
```

- 일반 Kotlin tests: 646 passed
- Kotlin Yjs V1/V2 interop tests: 78 passed
- JavaScript/Yjs oracle tests: 106 passed
- Maven Local standalone consumer: passed
- deterministic upstream differential: 500 seeds, 0 failures
- 15,000 nested insertion regression: passed
- fixture regeneration diff: clean
- `git diff --check`: passed

검증 명령:

```sh
cd /Volumes/D/yks
npm run interop:generate
npm run test:interop
./gradlew clean check consumerSmokeTest --no-daemon -PreleaseVersion=0.1.0-test
git diff --exit-code -- interop/yjs-v1/fixtures
git diff --check
```

이 머신의 Codex shell에서는 위 명령 앞에 `rtk`를 붙여 실행해야 함.

## 정밀 감사 후 개선 상태

2026-07-12 감사에서 확인한 sequence ordering, cleanup/map/XML 의미, 두 차례 후속 독립 감사의
공개 API·metadata·preliminary·snapshot·event edge case, 그리고 최종 observer/snapshot/GC
후속 감사에서 드러난 transaction merge·예외 lifecycle까지 수정하고 regression을 추가함.
현재 알려진 core wire/semantic/convergence blocker는 없음.
다만 YKS는 JavaScript API와 내부 객체 모델까지 완전히 동일한 복제는 아니며, 아래 JVM/wire 경계와
`YJS_COMPATIBILITY.md`의 contract adaptation이 남아 있음.

실제로 남은 경계는 upstream wire 자체 또는 JVM 환경 차이임:

1. root XML schema
   - root shared type kind와 root `XmlElement` node name은 Yjs update wire에 없음
   - remote root는 guessed type으로 열지 않고 `YUnopenedRoot`로 보존하며 explicit typed getter가 schema를 결정
   - local clone/in-memory snapshot은 capture 시점에 알려진 schema를 별도 metadata로 보존
2. Kotlin-only subdocument/options와 static XML
   - upstream wire에 없는 `collectionId`, suggestion metadata, compact/static XML 등은 explicit `*Lossless` API만 private envelope 사용
   - 표준 V1/V2 API와 standard event channel은 조용히 option을 유실하거나 private bytes를 반환하지 않음
3. GC storage compactness
   - wire/pending metadata, large range, clock semantics는 보존됨
   - compatible `ContentDeleted` range는 병합하지만 countable/private metadata가 다른 경우 보수적으로 분리 유지
   - 이 내부 struct 수 차이는 성능/객체 모델 adaptation이며 wire/convergence blocker가 아님
4. browser-only surface
   - selector와 live XML model 동작은 지원
   - browser DOM 생성(`toDOM`)은 JVM에 직접 대응하지 않아 범위 밖
5. Kotlin public/internal contract adaptation
   - Kotlin 표준 `Array`/`Map`과의 이름 충돌, callback type, mutable Yjs internal struct graph는 정확히 동일하지 않음
   - 103개 export의 대응 분류와 구체적 차이는 `YJS_COMPATIBILITY.md` 참고

새 기능을 추가할 때는 fixture/oracle을 먼저 추가하고, 표준 wire eligibility와 explicit lossless 경계를 동시에 검증할 것.

## 주의 사항

- root shared type kind는 Yjs update wire에 기록되지 않음. 모호한 remote root는 apply 후 explicit typed getter로 materialize해야 함.
- Yjs clock은 UTF-16 code unit 기준임.
- `Skip`은 clock ownership이 아니며 store/state vector에 들어가면 안 됨.
- `GC`는 clock을 소유하며 anchor/parent가 GC이면 dependent item도 GC로 통합해야 함.
- packed struct 내부 ID를 anchor로 사용할 수 있으므로 start-ID lookup만 사용하면 안 됨.
- delete set은 struct integration 뒤에 적용되며 아직 없는 range는 pending으로 유지해야 함.
- standard writer eligibility를 넓힐 때는 손실 없는 경우만 허용하고, 애매하면 표준 API는 예외,
  explicit `*Lossless` API만 private envelope를 사용하도록 유지할 것.
