package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.absolutePathString
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Tag("yjs-interop")
class YjsDifferentialFuzzTest {
    private val projectDirectory = Path.of(System.getProperty("user.dir"))
    private val base64Decoder = Base64.getDecoder()

    private fun generateCorpus(script: String, seedCount: Int): Path {
        val corpus = Files.createTempFile("yks-differential-fuzz-", ".tsv")
        val process = ProcessBuilder(
            "node",
            "interop/yjs-v1/$script",
            corpus.absolutePathString(),
            seedCount.toString(),
        )
            .directory(projectDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor() == 0, "upstream fuzz generator failed:\n$output")
        return corpus
    }

    private fun decodeUpdates(encoded: String): List<ByteArray> = encoded
        .split(',')
        .filter(String::isNotEmpty)
        .map(base64Decoder::decode)

    private fun decodeText(encoded: String): String =
        base64Decoder.decode(encoded).toString(Charsets.UTF_8)

    @Test
    fun concurrentArrayTextAndMapOperationsMatchUpstreamAcross500Seeds() {
        val corpus = generateCorpus("generate-differential-fuzz.mjs", 500)
        try {
            Files.readAllLines(corpus).forEach { line ->
                val columns = line.split('\t')
                assertEquals(5, columns.size, "invalid fuzz corpus row: $line")
                val seed = columns[0]
                val doc = YDoc(clientId = 99, gc = false)
                doc.getArray("a")
                doc.getText("t")
                doc.getMap("m")
                decodeUpdates(columns[1]).forEach(doc::applyUpdate)

                assertEquals(columns[2], doc.getArray("a").toArray().toString(), "array diverged at seed $seed")
                assertEquals(columns[3], doc.getText("t").toString(), "text diverged at seed $seed")
                assertEquals(columns[4], doc.getMap("m").toMap().toSortedMap().toString(), "map diverged at seed $seed")
                assertEquals(null, doc.store.pendingStructs, "pending structs remained at seed $seed")
            }
        } finally {
            Files.deleteIfExists(corpus)
        }
    }

    @Test
    fun xmlSubdocsRelativePositionsV2AndUndoMatchUpstreamAcross100Seeds() {
        val corpus = generateCorpus("generate-advanced-differential-fuzz.mjs", 100)
        try {
            Files.readAllLines(corpus).forEach { line ->
                val columns = line.split('\t')
                assertEquals(16, columns.size, "invalid advanced fuzz corpus row: $line")
                val seed = columns[0]

                val xmlDoc = YDoc(clientId = 900_001, gc = false)
                val xml = xmlDoc.getXmlFragment("xml")
                val xmlUpdates = decodeUpdates(columns[1])
                val mergedXml = try {
                    mergeUpdates(xmlUpdates)
                } catch (cause: UnsupportedYjsStandardUpdateException) {
                    throw AssertionError("XML merge rejected upstream updates at seed $seed", cause)
                }
                xmlDoc.applyUpdate(mergedXml)
                assertEquals(decodeText(columns[2]), xml.toString(), "XML diverged at seed $seed")
                assertEquals(null, xmlDoc.store.pendingStructs, "XML pending structs remained at seed $seed")

                val subdocDoc = YDoc(clientId = 900_002, gc = false)
                val subdocMap = subdocDoc.getMap("subdocs")
                val subdocArray = subdocDoc.getArray("subdocArray")
                decodeUpdates(columns[3]).forEach(subdocDoc::applyUpdate)
                val primaryGuid = (subdocMap.get("primary") as? YDoc)?.guid ?: "-"
                val arrayGuids = subdocArray.toList().map { child -> (child as YDoc).guid }
                val allGuids = subdocDoc.getSubdocs().map(YDoc::guid).sorted()
                assertEquals(
                    columns[4],
                    "$primaryGuid|${arrayGuids.joinToString(",")}|${allGuids.joinToString(",")}",
                    "subdocuments diverged at seed $seed",
                )
                assertEquals(null, subdocDoc.store.pendingStructs, "subdoc pending structs remained at seed $seed")

                val relativeDoc = YDoc(clientId = 900_003, gc = false)
                relativeDoc.getText("relative")
                relativeDoc.applyUpdate(base64Decoder.decode(columns[5]))
                val relative = decodeRelativePosition(base64Decoder.decode(columns[6]))
                val absolute = createAbsolutePositionFromRelativePosition(relative, relativeDoc)
                assertEquals(columns[7].toInt(), absolute?.index, "relative index diverged at seed $seed")
                assertEquals(columns[8].toInt(), absolute?.assoc, "relative assoc diverged at seed $seed")

                val baseV2 = base64Decoder.decode(columns[9])
                val v2Updates = listOf(baseV2) + decodeUpdates(columns[10])
                val mergedV2 = mergeUpdatesV2(v2Updates)
                val expectedV2Text = decodeText(columns[11])
                val mergedDoc = YDoc(clientId = 900_004, gc = false)
                mergedDoc.getText("body")
                mergedDoc.applyUpdateV2(mergedV2)
                assertEquals(expectedV2Text, mergedDoc.getText("body").toString(), "V2 merge diverged at seed $seed")

                val convertedV1 = convertUpdateFormatV2ToV1(mergedV2)
                val convertedDoc = YDoc(clientId = 900_005, gc = false)
                convertedDoc.getText("body")
                convertedDoc.applyUpdate(convertedV1)
                assertEquals(expectedV2Text, convertedDoc.getText("body").toString(), "V2→V1 diverged at seed $seed")

                val roundTrippedV2 = convertUpdateFormatV1ToV2(convertedV1)
                val roundTripDoc = YDoc(clientId = 900_006, gc = false)
                roundTripDoc.getText("body")
                roundTripDoc.applyUpdateV2(roundTrippedV2)
                assertEquals(expectedV2Text, roundTripDoc.getText("body").toString(), "V1→V2 diverged at seed $seed")

                val diffV2 = diffUpdateV2(mergedV2, encodeStateVectorFromUpdateV2(baseV2))
                val diffDoc = YDoc(clientId = 900_007, gc = false)
                diffDoc.getText("body")
                diffDoc.applyUpdateV2(baseV2)
                diffDoc.applyUpdateV2(diffV2)
                assertEquals(expectedV2Text, diffDoc.getText("body").toString(), "V2 diff diverged at seed $seed")

                val undoDoc = YDoc(clientId = 900_008, gc = false)
                val undoText = undoDoc.getText("undo")
                val undoManager = UndoManager(undoText, UndoManagerOptions(captureTimeoutMillis = 0))
                columns[12].split(';').filter(String::isNotEmpty).forEach { encodedOperation ->
                    val operation = encodedOperation.split(',')
                    when (operation[0]) {
                        "I" -> undoText.insert(operation[1].toInt(), operation[2])
                        "D" -> undoText.delete(operation[1].toInt(), operation[2].toInt())
                        else -> error("unknown undo fuzz operation ${operation[0]}")
                    }
                }
                assertEquals(decodeText(columns[13]), undoText.toString(), "undo input diverged at seed $seed")
                while (undoManager.canUndo) undoManager.undo()
                assertEquals(decodeText(columns[14]), undoText.toString(), "undo result diverged at seed $seed")
                while (undoManager.canRedo) undoManager.redo()
                assertEquals(decodeText(columns[15]), undoText.toString(), "redo result diverged at seed $seed")
                undoManager.close()
            }
        } finally {
            Files.deleteIfExists(corpus)
        }
    }

    @Test
    @Timeout(30)
    fun richTextNestedEventsSnapshotsAndGcMatchUpstreamAcross200Seeds() {
        val corpus = generateCorpus("generate-rich-event-fuzz.mjs", 200)
        try {
            Files.readAllLines(corpus).forEach { line ->
                val columns = line.split('\t')
                assertEquals(17, columns.size, "invalid rich corpus row: $line")
                val seed = columns[0]
                val doc = YDoc(clientId = 800_000, gc = false)
                val body = doc.getText("body")
                val root = doc.getMap("root")
                doc.applyUpdate(base64Decoder.decode(columns[1]))
                val section = assertIs<YMap>(root.get("section"))
                val list = assertIs<YArray>(section.get("list"))
                val note = assertIs<YText>(section.get("note"))
                var bodyEventDelta = ""
                var bodyAdded = -1
                var bodyDeleted = -1
                var sectionChanges = ""
                val deepPaths = mutableListOf<String>()
                body.observeTyped { event ->
                    bodyEventDelta = deltaSignature(event.delta)
                    bodyAdded = event.changes.added.size
                    bodyDeleted = event.changes.deleted.size
                }
                section.observeTyped { event ->
                    sectionChanges = event.changes.keys.entries
                        .map { (key, change) ->
                            "$key:${change.action.name.lowercase()}:${canonical(change.oldValue)}"
                        }
                        .sorted()
                        .joinToString("|")
                }
                root.observeDeepEvents { events, _ ->
                    events.forEach { event -> deepPaths.add(canonical(event.path)) }
                }

                doc.applyUpdate(base64Decoder.decode(columns[2]))

                assertEquals(decodeText(columns[3]), body.toString(), "body diverged at seed $seed")
                assertEquals(decodeText(columns[4]), deltaSignature(body.toDelta()), "body delta diverged at seed $seed")
                assertEquals(decodeText(columns[5]), bodyEventDelta, "body event diverged at seed $seed")
                assertEquals(columns[6].toInt(), bodyAdded, "body additions diverged at seed $seed")
                assertEquals(columns[7].toInt(), bodyDeleted, "body deletions diverged at seed $seed")
                assertEquals(decodeText(columns[8]), sectionChanges, "map event diverged at seed $seed")
                assertEquals(
                    decodeText(columns[9]),
                    deepPaths.sorted().joinToString("|"),
                    "deep paths diverged at seed $seed",
                )
                assertEquals(decodeText(columns[10]), note.toString(), "nested text diverged at seed $seed")
                assertEquals(decodeText(columns[11]), deltaSignature(note.toDelta()), "nested delta diverged at seed $seed")
                assertEquals(decodeText(columns[12]), canonical(list.toArray()), "nested array diverged at seed $seed")

                val baseSnapshot = decodeSnapshot(base64Decoder.decode(columns[13]))
                val snapshotDoc = createDocFromSnapshot(doc, baseSnapshot)
                assertEquals(decodeText(columns[14]), snapshotDoc.getText("body").toString(), "snapshot diverged at seed $seed")

                val gcDoc = YDoc(clientId = 800_001)
                val gcMap = gcDoc.getMap("gc")
                gcDoc.applyUpdate(base64Decoder.decode(columns[15]))
                assertEquals(decodeText(columns[16]), canonical(gcMap.toMap()), "GC map diverged at seed $seed")
            }
        } finally {
            Files.deleteIfExists(corpus)
        }
    }

    @Test
    @Timeout(30)
    fun malformedV1AndV2AcceptanceAndResultMatchUpstreamAcross1000Seeds() {
        val corpus = generateCorpus("generate-malformed-differential-fuzz.mjs", 1_000)
        try {
            Files.readAllLines(corpus).forEach { line ->
                val columns = line.split('\t')
                assertEquals(6, columns.size, "invalid malformed corpus row: $line")
                val seed = columns[0]
                val format = columns[1].toInt()
                val payload = base64Decoder.decode(columns[2])
                val target = YDoc(clientId = 10_000L + seed.toLong() * 2L + format, gc = false)
                val text = target.getText("body")
                val failure = runCatching {
                    if (format == 0) target.applyUpdate(payload) else target.applyUpdateV2(payload)
                }.exceptionOrNull()
                val upstreamAccepted = columns[3] == "1"

                assertEquals(
                    upstreamAccepted,
                    failure == null,
                    "acceptance diverged at seed=$seed format=$format localFailure=${failure?.stackTraceToString()}",
                )
                if (failure != null) {
                    assertIs<YksException>(
                        failure,
                        "unstable exception at seed=$seed format=$format: ${failure.stackTraceToString()}",
                    )
                } else {
                    assertEquals(decodeText(columns[4]), text.toString(), "text diverged at seed=$seed format=$format")
                    assertEquals(
                        columns[5],
                        Base64.getEncoder().encodeToString(target.encodeStateVector()),
                        "state vector diverged at seed=$seed format=$format",
                    )
                }
            }
        } finally {
            Files.deleteIfExists(corpus)
        }
    }

    private fun deltaSignature(delta: YTextDelta): String = delta.ops.joinToString("|") { operation ->
        val attributes = canonical(operation.attributes)
        when {
            operation.insert != null -> "I:${canonical(operation.insert)}:$attributes"
            operation.retain != null -> "R:${operation.retain}:$attributes"
            else -> "D:${operation.delete}"
        }
    }

    private fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is ByteArray -> canonical(value.map(Byte::toInt))
        is List<*> -> value.joinToString(",", "[", "]", transform = ::canonical)
        is Array<*> -> value.joinToString(",", "[", "]", transform = ::canonical)
        is Map<*, *> -> value.entries
            .sortedBy { (key, _) -> key.toString() }
            .joinToString(",", "{", "}") { (key, nested) ->
                "${toJsonLiteral(key.toString())}:${canonical(nested)}"
            }
        is String -> toJsonLiteral(value)
        is Boolean -> value.toString()
        is Number -> if (value.toDouble() % 1.0 == 0.0) value.toLong().toString() else value.toString()
        else -> error("unsupported canonical value: ${value::class.qualifiedName}")
    }
}
