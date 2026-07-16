package dev.yks.benchmark

import dev.yks.YDoc
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

    val scenarios = linkedMapOf<String, () -> Long>(
        "apply_5000_structs" to {
            val doc = YDoc(clientId = 20)
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
        "length_read_20000" to {
            var sum = 0L
            repeat(20_000) { sum += lengthText.length }
            sum
        },
        "string_read_100" to {
            var sum = 0L
            repeat(100) { sum += stringText.toString().length }
            sum
        },
        "encode_5000_structs" to {
            encodeDoc.encodeStateAsUpdate().size.toLong()
        },
        "standard_empty_tx_5000" to {
            standardTransactionDoc.transact { Unit }
            standardTransactionDoc.getText("left").length.toLong()
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
