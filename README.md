# YKS

YKS is a Kotlin/JVM implementation of the Yjs document model and update
protocol. It provides shared arrays, maps, text, live XML, subdocuments,
snapshots, relative positions, undo management, and update V1/V2 operations.

The project targets JDK 21 and is currently distributed as source; Maven
publishing is not configured yet. Interoperability is tested against Yjs
`13.6.31`.

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

Create nested shared types through the owning document and attach the owner
before writing child content when standard Yjs wire output is required:

```kotlin
val doc = YDoc(clientId = 1)
val profile = doc.createMap()
doc.getMap("root").set("profile", profile)
profile.set("name", "Ada")
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

Update listeners are available through `observeUpdates`, `onUpdate`, and
`onUpdateV2`. Listener payloads follow the same standard-versus-private policy
as explicit state exports.

## Standard and private update formats

YKS accepts genuine Yjs update V1 and V2. It emits genuine standard updates
when the current document state can be represented without losing Kotlin-only
information. Covered standard paths include:

- root and owner-first nested arrays, maps, and text;
- binary and lib0 values, including `undefined`, negative zero, special
  floating-point values, and signed 64-bit BigInt values;
- native rich-text format markers and embeds supported by the selected wire
  version;
- live XML elements/text, attributes, formatting, and deletion;
- direct subdocuments with upstream-representable options;
- delete sets, GC/Skip structs, large deleted ranges, state-vector diffs, and
  merged baseline/incremental updates.

When lossless standard encoding is not possible, YKS writes a private envelope
beginning with `YKS` (currently `YKS\x03`). Other YKS documents can read this
format, including older `YKS\x01` and `YKS\x02` payloads. Upstream JavaScript
Yjs cannot read a private YKS envelope.

Current private-fallback cases include:

- compact/static XML nodes and attributes on a root XML fragment;
- pre-populated detached shared types whose child clocks precede their owner;
- subdocument options that do not exist on Yjs wire, such as `collectionId`
  and suggestion-document metadata;
- other Kotlin-only metadata or content shapes that fail the lossless standard
  eligibility gate.

Some live transaction updates involving preliminary/detached nested state may
therefore be private even when a later explicit full-state export is standard.
Applications synchronizing directly with JavaScript Yjs should cover their
actual mutation paths with the interop verifier instead of assuming every byte
array is standard.

## Known limitations

- Root shared-type kind and a root `XmlElement` node name are not encoded by
  Yjs updates. Receivers may need to materialize the expected XML root before
  applying an ambiguous update.
- `Y.XmlHook` wire refs are recognized, but there is not yet a separate public
  map-style `YXmlHook` API matching JavaScript Yjs.
- Browser DOM helpers such as `toDOM` and selector APIs are outside the current
  JVM surface.
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
./gradlew check
```

`check` runs both the normal Kotlin tests and the Kotlin/Yjs interoperability
tests. To regenerate committed upstream fixtures and verify that generation is
clean:

```sh
npm run interop:generate
git diff --exit-code -- interop/yjs-v1/fixtures
```

See [the interoperability harness documentation](interop/yjs-v1/README.md) for
scenario details and standalone update verifiers.
