package dev.yks.benchmark

import dev.yks.YDoc
import dev.yks.snapshot
import java.nio.file.Files
import java.nio.file.Path

private data class BenchmarkResult(
    val medianMs: Double,
    val p95Ms: Double,
)

private object BenchmarkSink {
    @Volatile
    var value: Long = 0
}

private fun benchmark(
    warmupIterations: Int,
    sampleIterations: Int,
    operation: () -> Long,
): BenchmarkResult {
    repeat(warmupIterations) { BenchmarkSink.value = operation() }
    // Whole-suite runs can leave C1/C2 compilation queued behind a completed workload. Give the
    // tiered compiler a short quiescence window so the measured samples represent warmed code
    // instead of compiler-thread contention from an earlier scenario.
    Thread.sleep(250)
    val samples = DoubleArray(sampleIterations) {
        System.gc()
        val started = System.nanoTime()
        BenchmarkSink.value = operation()
        (System.nanoTime() - started) / 1_000_000.0
    }.sortedArray()
    return BenchmarkResult(
        medianMs = samples[samples.size / 2],
        p95Ms = samples[((samples.size - 1) * 0.95).toInt()],
    )
}

private fun Double.jsonNumber(): String = "%.6f".format(java.util.Locale.ROOT, this)

fun main(args: Array<String>) {
    require(args.size == 4) { "expected fixture path, warmup count, sample count, and scenarios" }
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
    val warmupIterations = args[1].toInt()
    val sampleIterations = args[2].toInt()
    val selectedScenarios = args[3]
        .takeUnless { value -> value == "all" }
        ?.split(',')
        ?.toSet()

    val lengthDoc = YDoc(clientId = 10)
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

    val arrayReadDoc = YDoc(clientId = 14)
    arrayReadDoc.applyUpdate(arrayFixture)
    val arrayRead = arrayReadDoc.getArray("array")

    val clockRangeDoc = YDoc(clientId = 15)
    val clockRangeText = clockRangeDoc.getText("body")
    clockRangeText.insert(0, "x".repeat(20_000))
    val clockRangeBefore = snapshot(clockRangeDoc)
    clockRangeText.insert(10_000, "y")
    val clockRangeAfter = snapshot(clockRangeDoc)

    val invocationCount = warmupIterations + sampleIterations
    val nestedDeleteDocs = List(invocationCount) {
        YDoc(clientId = 31, gc = false).also { doc -> doc.applyUpdate(nestedFixture) }
    }
    var nestedDeleteIndex = 0
    data class ObservedFragmentedState(val text: dev.yks.YText, val observed: () -> Long)
    val observedFragmentedDocs = List(invocationCount) {
        val doc = YDoc(clientId = 34, gc = false)
        doc.applyUpdate(fragmentedFixture)
        var observed = 0L
        doc.observeAfterTransactions { observed++ }
        ObservedFragmentedState(doc.getText("body")) { observed }
    }
    var observedFragmentedIndex = 0
    val observedMapDoc = YDoc(clientId = 42, gc = false)
    observedMapDoc.applyUpdate(mapFixture)
    var observedMapEvents = 0L
    val observedMap = observedMapDoc.getMap("map")
    observedMap.observe { observedMapEvents++ }
    var observedMapIndex = 0

    val scenarios = linkedMapOf<String, () -> Long>(
        "apply_5000_structs" to {
            val doc = YDoc(clientId = 20)
            doc.applyUpdate(fixture)
            (doc.getText("left").length + doc.getText("right").length).toLong()
        },
        "apply_5000_structs_open_roots" to {
            val doc = YDoc(clientId = 44)
            doc.getText("left").toString()
            doc.getText("right").toString()
            doc.applyUpdate(fixture)
            (doc.getText("left").length + doc.getText("right").length).toLong()
        },
        "append_5000" to {
            val doc = YDoc(clientId = 21)
            val text = doc.getText("body")
            repeat(5_000) { text.insert(text.length, "x") }
            text.length.toLong()
        },
        "middle_edit_1000" to {
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
        "middle_edit_1000_no_gc" to {
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
        "middle_edit_1000_batched" to {
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
        "length_read_200000" to {
            var sum = 0L
            repeat(200_000) { sum += lengthText.length }
            sum
        },
        "string_read_50000" to {
            var sum = 0L
            repeat(50_000) { sum += stringText.toString().length }
            sum
        },
        "encode_5000_structs" to {
            encodeDoc.encodeStateAsUpdate().size.toLong()
        },
        "standard_empty_tx_5000" to {
            repeat(1_000) { standardTransactionDoc.transact { Unit } }
            standardTransactionDoc.getText("left").length.toLong()
        },
        "formatted_apply_5004" to {
            val doc = YDoc(clientId = 30)
            doc.applyUpdate(formattedFixture)
            (doc.getText("left").length + doc.getText("right").length).toLong()
        },
        "nested_delete_3000" to {
            val doc = nestedDeleteDocs[nestedDeleteIndex++]
            val root = doc.getArray("root")
            root.delete(0, root.length)
            root.length.toLong()
        },
        "nested_apply_3000" to {
            val doc = YDoc(clientId = 37, gc = false)
            doc.applyUpdate(nestedFixture)
            doc.getArray("root").length.toLong()
        },
        "map_apply_5000" to {
            val doc = YDoc(clientId = 32)
            doc.applyUpdate(mapFixture)
            doc.getMap("map").size.toLong()
        },
        "map_history_apply_5000" to {
            val doc = YDoc(clientId = 35, gc = false)
            doc.applyUpdate(mapHistoryFixture)
            (doc.getMap("map").get("key") as Number).toLong()
        },
        "array_apply_5000" to {
            val doc = YDoc(clientId = 36, gc = false)
            doc.applyUpdate(arrayFixture)
            (doc.getArray("array").get(2_500) as Number).toLong()
        },
        "array_insert_5000" to {
            val doc = YDoc(clientId = 40, gc = false)
            val array = doc.getArray("array")
            array.insert(0, List(5_000) { index -> index })
            (array.get(2_500) as Number).toLong()
        },
        "map_history_set_5000" to {
            val doc = YDoc(clientId = 41, gc = false)
            val map = doc.getMap("map")
            doc.transact {
                repeat(5_000) { index -> map.set("key", index) }
            }
            (map.get("key") as Number).toLong()
        },
        "fragmented_apply_5000" to {
            val doc = YDoc(clientId = 38, gc = false)
            doc.applyUpdate(fragmentedFixture)
            doc.getText("body").length.toLong()
        },
        "fragmented_apply_5000_open_root" to {
            val doc = YDoc(clientId = 45, gc = false)
            doc.getText("body").toString()
            doc.applyUpdate(fragmentedFixture)
            doc.getText("body").length.toLong()
        },
        "concurrent_insert_apply_1000" to {
            val doc = YDoc(clientId = 39, gc = false)
            doc.applyUpdate(concurrentFixture)
            doc.getText("body").length.toLong()
        },
        "concurrent_insert_apply_1000_open_root" to {
            val doc = YDoc(clientId = 46, gc = false)
            doc.getText("body").toString()
            doc.applyUpdate(concurrentFixture)
            doc.getText("body").length.toLong()
        },
        "roots_create_read_10000" to {
            val doc = YDoc(clientId = 33)
            repeat(10_000) { index -> doc.getText("root-$index") }
            var sum = 0L
            repeat(10_000) { index -> sum += doc.getText("root-$index").length }
            doc.rootNames().size.toLong() + sum
        },
        "edit_1000_with_10000_roots" to {
            val doc = YDoc(clientId = 43)
            repeat(10_000) { index -> doc.getText("root-$index") }
            val text = doc.getText("root-0")
            repeat(1_000) {
                text.insert(0, "x")
                text.delete(0, 1)
            }
            doc.rootNames().size.toLong() + text.length
        },
        "array_index_read_100000" to {
            var sum = 0L
            repeat(100_000) {
                sum += arrayRead.length
                sum += (arrayRead.get(0) as Number).toLong()
            }
            sum
        },
        "observed_fragmented_edit_500" to {
            val state = observedFragmentedDocs[observedFragmentedIndex++]
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
        "observed_map_edit_1_on_5000" to {
            val key = "key-${observedMapIndex % 5_000}"
            observedMapIndex++
            observedMap.set(key, -observedMapIndex)
            observedMapEvents + (observedMap.get(key) as Number).toLong()
        },
        "clock_range_snapshot_delta" to {
            clockRangeText.toDelta(clockRangeAfter, clockRangeBefore).ops.size.toLong()
        },
    )

    val results = scenarios
        .filterKeys { name -> selectedScenarios == null || name in selectedScenarios }
        .mapValues { (_, operation) ->
        benchmark(warmupIterations, sampleIterations, operation)
    }
    val encodedResults = results.entries.joinToString(",") { (name, result) ->
        "\"$name\":{\"medianMs\":${result.medianMs.jsonNumber()},\"p95Ms\":${result.p95Ms.jsonNumber()}}"
    }
    println("YKS_BENCHMARK_JSON={$encodedResults}")
}
