package uz.faceguard.app.domain.screentime

/**
 * Phase 4 Step 1B-3: pure span geometry (merge / union).
 *
 * Foreground monitoring reports spans that can repeat, overlap or nest (a restart, a
 * re-platform, a retry). Adding them up naively over-counts. Unioning them first is
 * what makes accounting idempotent: the union of spans does not depend on how many
 * times each span was reported, and it never exceeds the real time that passed.
 *
 * No Android, no Room, no clock. Touching semantics are deliberate and tested: a span
 * that starts exactly where another ends (`[0,10]` and `[10,20]`) is treated as one
 * continuous stretch of usage, `[0,20]`.
 */
object UsageIntervals {

    /**
     * Sort by start, then coalesce anything that overlaps or touches. The result is
     * ordered and internally disjoint, so its durations can simply be added.
     */
    fun merge(ranges: List<UsageRange>): List<UsageRange> {
        if (ranges.isEmpty()) return emptyList()

        val sorted = ranges.sortedWith(compareBy({ it.startTimeMs }, { it.endTimeMs }))
        val merged = ArrayList<UsageRange>(sorted.size)
        var currentStart = sorted.first().startTimeMs
        var currentEnd = sorted.first().endTimeMs

        for (index in 1 until sorted.size) {
            val next = sorted[index]
            if (next.startTimeMs <= currentEnd) {
                // overlap, nesting or touching: extend; never shorten.
                if (next.endTimeMs > currentEnd) currentEnd = next.endTimeMs
            } else {
                merged += UsageRange(currentStart, currentEnd)
                currentStart = next.startTimeMs
                currentEnd = next.endTimeMs
            }
        }
        merged += UsageRange(currentStart, currentEnd)
        return merged
    }

    /**
     * The real elapsed time covered by [ranges], counted once. Empty input is `0`.
     *
     * Guarded against `Long` overflow so absurd input fails loudly instead of
     * wrapping to a negative duration.
     */
    fun unionDurationMs(ranges: List<UsageRange>): Long {
        var total = 0L
        for (range in merge(ranges)) {
            total = try {
                Math.addExact(total, range.durationMs)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException(
                    "union duration of ${ranges.size} ranges overflows Long",
                )
            }
        }
        return total
    }
}
