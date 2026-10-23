package dev.yks

import java.util.TreeMap

/** Keep earlier representations, slicing later ranges only into uncovered clocks. */
internal fun <T> mergeClockRangesFirstWins(
    ranges: List<T>,
    start: (T) -> Long,
    end: (T) -> Long,
    slice: (T, Long, Long) -> T,
): List<T> {
    val merged = TreeMap<Long, T>()
    for (incoming in ranges) {
        var clock = start(incoming)
        val incomingEnd = end(incoming)
        val covering = merged.floorEntry(clock)?.value
        if (covering != null) clock = maxOf(clock, end(covering))
        while (clock < incomingEnd) {
            val next = merged.ceilingEntry(clock)
            val gapEnd = minOf(incomingEnd, next?.key ?: incomingEnd)
            if (clock < gapEnd) {
                merged[clock] = slice(incoming, clock, gapEnd)
            }
            clock = if (next == null) incomingEnd else maxOf(gapEnd, end(next.value))
        }
    }
    return merged.values.toList()
}
