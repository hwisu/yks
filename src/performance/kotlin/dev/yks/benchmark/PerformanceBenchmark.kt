package dev.yks.benchmark

import dev.yks.YDoc
import dev.yks.YDocOptions
import dev.yks.YDocRuntimeOptions
import dev.yks.YThreadAccessPolicy
import dev.yks.UndoManager
import dev.yks.UndoManagerOptions
import dev.yks.createAbsolutePositionFromRelativePosition
import dev.yks.createRelativePositionFromTypeIndex
import dev.yks.diffUpdateV2
import dev.yks.encodeStateAsUpdateV2
import dev.yks.encodeStateVectorFromUpdateV2
import dev.yks.mergeUpdatesV2
import dev.yks.snapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

private data class BenchmarkResult(
    val repeatCount: Int,
    val batchMedianMs: Double,
    val batchP95Ms: Double,
    val medianMs: Double,
    val p95Ms: Double,
)

private object BenchmarkSink {
    @Volatile
    var value: Long = 0
}

private class BenchmarkScenario(
    val warmupBatch: (Int) -> Unit,
    val timedBatch: (Int) -> Long,
)

/**
 * Builds a scenario-specific repeat loop. Keeping the operation inside the generated batch
 * function prevents the shared harness call site from becoming megamorphic in whole-suite runs.
 */
private inline infix fun String.measures(
    crossinline operation: () -> Long,
): Pair<String, BenchmarkScenario> = this to BenchmarkScenario(
    warmupBatch = { repeatCount ->
        repeat(repeatCount) { BenchmarkSink.value = operation() }
    },
    timedBatch = { repeatCount ->
        val started = System.nanoTime()
        repeat(repeatCount) { BenchmarkSink.value = operation() }
        System.nanoTime() - started
    },
)

/**
 * Measures only the operation while preparing a fresh fixture before each repetition.
 */
private inline fun preparedScenario(
    crossinline prepare: () -> Unit,
    crossinline operation: () -> Long,
): BenchmarkScenario = BenchmarkScenario(
    warmupBatch = { repeatCount ->
        repeat(repeatCount) {
            prepare()
            BenchmarkSink.value = operation()
        }
    },
    timedBatch = { repeatCount ->
        var elapsedNanos = 0L
        repeat(repeatCount) {
            prepare()
            val started = System.nanoTime()
            BenchmarkSink.value = operation()
            elapsedNanos += System.nanoTime() - started
        }
        elapsedNanos
    },
)

private fun benchmark(
    warmupIterations: Int,
    sampleIterations: Int,
    repeatCount: Int,
    scenario: BenchmarkScenario,
): BenchmarkResult {
    repeat(warmupIterations) {
        scenario.warmupBatch(repeatCount)
    }
    // Whole-suite runs can leave both warmup garbage and C1/C2 compilation queued behind a
    // completed workload. Collect the warmup fixtures first, then give the tiered compiler a
    // quiescence window so measured samples do not depend on earlier scenario order.
    System.gc()
    Thread.sleep(500)
    val samples = DoubleArray(sampleIterations) {
        System.gc()
        scenario.timedBatch(repeatCount) / 1_000_000.0
    }.sortedArray()
    val batchMedianMs = samples[samples.size / 2]
    val batchP95Ms = samples[((samples.size - 1) * 0.95).toInt()]
    return BenchmarkResult(
        repeatCount = repeatCount,
        batchMedianMs = batchMedianMs,
        batchP95Ms = batchP95Ms,
        medianMs = batchMedianMs / repeatCount,
        p95Ms = batchP95Ms / repeatCount,
    )
}

private fun Double.jsonNumber(): String = "%.6f".format(java.util.Locale.ROOT, this)

fun main(args: Array<String>) {
    require(args.size == 5) {
        "expected fixture path, warmup count, sample count, scenarios, and repeat counts"
    }
    val fixture = Files.readAllBytes(Path.of(args[0]))
    val fixtureDirectory = Path.of(args[0]).parent
    fun fixture(name: String): ByteArray = Files.readAllBytes(fixtureDirectory.resolve(name))
    val formattedFixture = fixture("formatted-text-5000-v1.bin")
    val nestedFixture = fixture("nested-array-3000-v1.bin")
    val mapFixture = fixture("map-5000-v1.bin")
    val mapHistoryFixture = fixture("map-history-5000-v1.bin")
    val arrayFixture = fixture("array-5000-v1.bin")
    val fragmentedFixture = fixture("fragmented-text-5000-v1.bin")
    val concurrentFixture = fixture("concurrent-text-1000-v1.bin")
    val incrementalFixtures = Files.readAllLines(fixtureDirectory.resolve("incremental-text-1000-v1.txt"))
        .filter(String::isNotBlank)
        .map(Base64.getDecoder()::decode)
    val incrementalV2Fixtures = Files.readAllLines(fixtureDirectory.resolve("incremental-text-1000-v2.txt"))
        .filter(String::isNotBlank)
        .map(Base64.getDecoder()::decode)
    val warmupIterations = args[1].toInt()
    val sampleIterations = args[2].toInt()
    val selectedScenarios = args[3]
        .takeUnless { value -> value == "all" }
        ?.split(',')
        ?.toSet()
    val repeatCounts = args[4]
        .takeUnless(String::isBlank)
        ?.split(',')
        ?.associate { encoded ->
            val (name, count) = encoded.split('=', limit = 2)
            name to count.toInt()
        }
        .orEmpty()

    // Yjs has no thread-confinement check on scalar reads. Isolate the
    // maintained-length implementation from YKS's optional safety policy so
    // this ratio compares equivalent semantics; safety is covered separately.
    val lengthDoc = YDoc(
        YDocOptions(clientId = 10),
        YDocRuntimeOptions(threadAccessPolicy = YThreadAccessPolicy.UNCHECKED),
    )
    val lengthText = lengthDoc.getText("body")
    lengthText.insert(0, "x".repeat(5_000))

    val stringDoc = YDoc(clientId = 11)
    val stringText = stringDoc.getText("body")
    stringText.insert(0, "x".repeat(5_000))

    val encodeDoc = YDoc(clientId = 12)
    encodeDoc.applyUpdate(fixture)

    val standardTransactionDoc = YDoc(clientId = 13)
    standardTransactionDoc.applyUpdate(fixture)
    standardTransactionDoc.observeUpdates { _, _ -> Unit }

    val arrayReadDoc = YDoc(
        YDocOptions(clientId = 14),
        YDocRuntimeOptions(threadAccessPolicy = YThreadAccessPolicy.UNCHECKED),
    )
    arrayReadDoc.applyUpdate(arrayFixture)
    val arrayRead = arrayReadDoc.getArray("array")

    val clockRangeDoc = YDoc(clientId = 15)
    val clockRangeText = clockRangeDoc.getText("body")
    clockRangeText.insert(0, "x".repeat(20_000))
    val clockRangeBefore = snapshot(clockRangeDoc)
    clockRangeText.insert(10_000, "y")
    val clockRangeAfter = snapshot(clockRangeDoc)

    val alternatingSnapshotDoc = YDoc(clientId = 16, gc = false)
    val alternatingSnapshotText = alternatingSnapshotDoc.getText("body")
    alternatingSnapshotText.insert(0, "x".repeat(2_000))
    val alternatingSnapshotBefore = snapshot(alternatingSnapshotDoc)
    for (index in 999 downTo 0) alternatingSnapshotText.delete(index * 2, 1)
    val alternatingSnapshotAfter = snapshot(alternatingSnapshotDoc)

    val localFormatDoc = YDoc(clientId = 17, gc = false)
    val localFormatText = localFormatDoc.getText("body")
    repeat(50_000) { localFormatText.insert(0, "x") }
    var localFormatValue = 0

    val unrelatedObserverDoc = YDoc(clientId = 18, gc = false)
    val unrelatedObservedText = unrelatedObserverDoc.getText("observed")
    val unrelatedTargetText = unrelatedObserverDoc.getText("target")
    repeat(50_000) { unrelatedTargetText.insert(0, "x") }
    var unrelatedObserverEvents = 0L
    unrelatedObservedText.observe { unrelatedObserverEvents++ }

    val wideDeepDoc = YDoc(clientId = 19, gc = false)
    val wideDeepRoot = wideDeepDoc.getArray("root")
    val wideDeepChildren = ArrayList<dev.yks.YMap>(10_000)
    wideDeepDoc.transact {
        repeat(10_000) {
            val child = wideDeepDoc.createMap()
            wideDeepRoot.push(child)
            wideDeepChildren.add(child)
        }
    }
    var wideDeepEvents = 0L
    wideDeepRoot.observeDeep { wideDeepEvents++ }
    var wideDeepValue = 0

    var nestedDeleteDoc: YDoc? = null
    data class ObservedFragmentedState(val text: dev.yks.YText, val observed: () -> Long)
    var observedFragmentedState: ObservedFragmentedState? = null
    data class UndoRedoState(val text: dev.yks.YText, val manager: UndoManager)
    var undoRedoState: UndoRedoState? = null
    val scenarioPreparations = mapOf<String, () -> Unit>(
        "nested_delete_3000" to {
            nestedDeleteDoc = YDoc(clientId = 31, gc = false).also { doc -> doc.applyUpdate(nestedFixture) }
        },
        "observed_fragmented_edit_500" to {
            val doc = YDoc(clientId = 34, gc = false)
            doc.applyUpdate(fragmentedFixture)
            var observed = 0L
            doc.observeAfterTransactions { observed++ }
            observedFragmentedState = ObservedFragmentedState(doc.getText("body")) { observed }
        },
        "undo_redo_1000" to {
            val doc = YDoc(clientId = 49, gc = false)
            val text = doc.getText("body")
            val manager = UndoManager(text, UndoManagerOptions(captureTimeoutMillis = 0))
            repeat(1_000) { text.insert(text.length, "x") }
            undoRedoState = UndoRedoState(text, manager)
        },
    )
    val observedMapDoc = YDoc(clientId = 42, gc = false)
    observedMapDoc.applyUpdate(mapFixture)
    var observedMapEvents = 0L
    val observedMap = observedMapDoc.getMap("map")
    observedMap.observe { observedMapEvents++ }
    var observedMapIndex = 0

    val relativePositionDoc = YDoc(clientId = 48, gc = false)
    val relativePositionText = relativePositionDoc.getText("body")
    relativePositionText.insert(0, "x".repeat(5_000))
    val relativePositions = List(1_000) { index ->
        createRelativePositionFromTypeIndex(relativePositionText, index * 5, if (index % 2 == 0) -1 else 0)
    }

    val scenarios = linkedMapOf(
        "apply_5000_structs" measures {
            val doc = YDoc(clientId = 20)
            doc.applyUpdate(fixture)
            (doc.getText("left").length + doc.getText("right").length).toLong()
        },
        "apply_5000_structs_open_roots" measures {
            val doc = YDoc(clientId = 44)
            doc.getText("left").toString()
            doc.getText("right").toString()
            doc.applyUpdate(fixture)
            (doc.getText("left").length + doc.getText("right").length).toLong()
        },
        "append_5000" measures {
            val doc = YDoc(clientId = 21)
            val text = doc.getText("body")
            repeat(5_000) { text.insert(text.length, "x") }
            text.length.toLong()
        },
        "middle_edit_1000" measures {
            val doc = YDoc(clientId = 22)
            val text = doc.getText("body")
            text.insert(0, "x".repeat(5_000))
            repeat(1_000) {
                val middle = text.length / 2
                text.insert(middle, "y")
                text.delete(middle, 1)
            }
            text.length.toLong()
        },
        "middle_edit_1000_no_gc" measures {
            val doc = YDoc(clientId = 23, gc = false)
            val text = doc.getText("body")
            text.insert(0, "x".repeat(5_000))
            repeat(1_000) {
                val middle = text.length / 2
                text.insert(middle, "y")
                text.delete(middle, 1)
            }
            text.length.toLong()
        },
        "middle_edit_1000_batched" measures {
            val doc = YDoc(clientId = 24)
            val text = doc.getText("body")
            text.insert(0, "x".repeat(5_000))
            doc.transact {
                repeat(1_000) {
                    val middle = text.length / 2
                    text.insert(middle, "y")
                    text.delete(middle, 1)
                }
            }
            text.length.toLong()
        },
        "length_read_200000" measures {
            var sum = 0L
            repeat(200_000) { sum += lengthText.length }
            sum
        },
        "string_read_50000" measures {
            var sum = 0L
            repeat(50_000) { sum += stringText.toString().length }
            sum
        },
        "encode_5000_structs" measures {
            encodeDoc.encodeStateAsUpdate().size.toLong()
        },
        "encode_v2_5000_structs" measures {
            encodeStateAsUpdateV2(encodeDoc).size.toLong()
        },
        "standard_empty_tx_5000" measures {
            repeat(1_000) { standardTransactionDoc.transact { Unit } }
            standardTransactionDoc.getText("left").length.toLong()
        },
        "formatted_apply_5004" measures {
            val doc = YDoc(clientId = 30)
            doc.applyUpdate(formattedFixture)
            (doc.getText("left").length + doc.getText("right").length).toLong()
        },
        "nested_delete_3000" to preparedScenario(
            prepare = scenarioPreparations.getValue("nested_delete_3000"),
        ) {
            val doc = checkNotNull(nestedDeleteDoc)
            val root = doc.getArray("root")
            root.delete(0, root.length)
            root.length.toLong()
        },
        "nested_apply_3000" measures {
            val doc = YDoc(clientId = 37, gc = false)
            doc.applyUpdate(nestedFixture)
            doc.getArray("root").length.toLong()
        },
        "map_apply_5000" measures {
            val doc = YDoc(clientId = 32)
            doc.applyUpdate(mapFixture)
            doc.getMap("map").size.toLong()
        },
        "map_history_apply_5000" measures {
            val doc = YDoc(clientId = 35, gc = false)
            doc.applyUpdate(mapHistoryFixture)
            (doc.getMap("map").get("key") as Number).toLong()
        },
        "array_apply_5000" measures {
            val doc = YDoc(clientId = 36, gc = false)
            doc.applyUpdate(arrayFixture)
            (doc.getArray("array").get(2_500) as Number).toLong()
        },
        "array_insert_5000" measures {
            val doc = YDoc(clientId = 40, gc = false)
            val array = doc.getArray("array")
            array.insert(0, List(5_000) { index -> index })
            (array.get(2_500) as Number).toLong()
        },
        "map_history_set_5000" measures {
            val doc = YDoc(clientId = 41, gc = false)
            val map = doc.getMap("map")
            doc.transact {
                repeat(5_000) { index -> map.set("key", index) }
            }
            (map.get("key") as Number).toLong()
        },
        "fragmented_apply_5000" measures {
            val doc = YDoc(clientId = 38, gc = false)
            doc.applyUpdate(fragmentedFixture)
            doc.getText("body").length.toLong()
        },
        "fragmented_apply_5000_open_root" measures {
            val doc = YDoc(clientId = 45, gc = false)
            doc.getText("body").toString()
            doc.applyUpdate(fragmentedFixture)
            doc.getText("body").length.toLong()
        },
        "concurrent_insert_apply_1000" measures {
            val doc = YDoc(clientId = 39, gc = false)
            doc.applyUpdate(concurrentFixture)
            doc.getText("body").length.toLong()
        },
        "concurrent_insert_apply_1000_open_root" measures {
            val doc = YDoc(clientId = 46, gc = false)
            doc.getText("body").toString()
            doc.applyUpdate(concurrentFixture)
            doc.getText("body").length.toLong()
        },
        "incremental_apply_1000" measures {
            val doc = YDoc(clientId = 47, gc = false)
            incrementalFixtures.forEach(doc::applyUpdate)
            doc.getText("body").length.toLong()
        },
        "roots_create_read_10000" measures {
            val doc = YDoc(clientId = 33)
            repeat(10_000) { index -> doc.getText("root-$index") }
            var sum = 0L
            repeat(10_000) { index -> sum += doc.getText("root-$index").length }
            doc.rootNames().size.toLong() + sum
        },
        "edit_1000_with_10000_roots" measures {
            val doc = YDoc(clientId = 43)
            repeat(10_000) { index -> doc.getText("root-$index") }
            val text = doc.getText("root-0")
            repeat(1_000) {
                text.insert(0, "x")
                text.delete(0, 1)
            }
            doc.rootNames().size.toLong() + text.length
        },
        "array_index_read_100000" measures {
            var sum = 0L
            repeat(100_000) {
                sum += arrayRead.length
                sum += (arrayRead.get(0) as Number).toLong()
            }
            sum
        },
        "observed_fragmented_edit_500" to preparedScenario(
            prepare = scenarioPreparations.getValue("observed_fragmented_edit_500"),
        ) {
            val state = checkNotNull(observedFragmentedState)
            val text = state.text
            text.doc.transact {
                repeat(500) {
                    val middle = text.length / 2
                    text.insert(middle, "y")
                    text.delete(middle, 1)
                }
            }
            state.observed() + text.length
        },
        "observed_map_edit_1_on_5000" measures {
            val key = "key-${observedMapIndex % 5_000}"
            observedMapIndex++
            observedMap.set(key, -observedMapIndex)
            observedMapEvents + (observedMap.get(key) as Number).toLong()
        },
        "clock_range_snapshot_delta" measures {
            clockRangeText.toDelta(clockRangeAfter, clockRangeBefore).ops.size.toLong()
        },
        "alternating_delete_snapshot_delta_1000" measures {
            alternatingSnapshotText.toDelta(alternatingSnapshotAfter, alternatingSnapshotBefore).ops.size.toLong()
        },
        "local_format_first_char_on_50000" measures {
            localFormatValue++
            localFormatText.format(0, 1, mapOf("audit" to localFormatValue))
            localFormatText.length.toLong()
        },
        "unrelated_observer_edit_on_50000" measures {
            val middle = unrelatedTargetText.length / 2
            unrelatedTargetText.insert(middle, "y")
            unrelatedTargetText.delete(middle, 1)
            unrelatedObserverEvents + unrelatedTargetText.length
        },
        "deep_first_child_edit_10_on_10000" measures {
            repeat(10) {
                wideDeepValue++
                wideDeepChildren[0].set("value", wideDeepValue)
            }
            wideDeepEvents + wideDeepValue
        },
        "xml_build_render_500" measures {
            val doc = YDoc(clientId = 50, gc = false)
            val fragment = doc.getXmlFragment("xml")
            doc.transact {
                repeat(500) { index ->
                    val element = doc.createXmlElement("p")
                    fragment.push(element)
                    element.setAttr("data-index", index.toString())
                    val text = doc.createXmlText()
                    element.push(text)
                    text.insert(0, "content-$index")
                }
            }
            fragment.toString().length.toLong()
        },
        "relative_position_resolve_10000" measures {
            var sum = 0L
            repeat(10_000) { index ->
                val absolute = checkNotNull(
                    createAbsolutePositionFromRelativePosition(
                        relativePositions[index % relativePositions.size],
                        relativePositionDoc,
                    ),
                )
                sum += absolute.index
            }
            sum
        },
        "v2_merge_diff_1000" measures {
            val merged = mergeUpdatesV2(incrementalV2Fixtures)
            val stateVector = encodeStateVectorFromUpdateV2(merged)
            val diff = diffUpdateV2(merged, stateVector)
            (merged.size + stateVector.size + diff.size).toLong()
        },
        "undo_redo_1000" to preparedScenario(
            prepare = scenarioPreparations.getValue("undo_redo_1000"),
        ) {
            val state = checkNotNull(undoRedoState)
            repeat(1_000) { checkNotNull(state.manager.undo()) }
            repeat(1_000) { checkNotNull(state.manager.redo()) }
            state.text.length.toLong()
        },
    )

    val results = scenarios
        .filterKeys { name -> selectedScenarios == null || name in selectedScenarios }
        .mapValues { (name, scenario) ->
            benchmark(
                warmupIterations,
                sampleIterations,
                requireNotNull(repeatCounts[name]) {
                    "missing noise-amplifying repeat count for performance scenario $name"
                },
                scenario,
            )
        }
    val encodedResults = results.entries.joinToString(",") { (name, result) ->
        "\"$name\":{" +
            "\"repeatCount\":${result.repeatCount}," +
            "\"batchMedianMs\":${result.batchMedianMs.jsonNumber()}," +
            "\"batchP95Ms\":${result.batchP95Ms.jsonNumber()}," +
            "\"medianMs\":${result.medianMs.jsonNumber()}," +
            "\"p95Ms\":${result.p95Ms.jsonNumber()}" +
            "}"
    }
    println("YKS_BENCHMARK_JSON={$encodedResults}")
}
