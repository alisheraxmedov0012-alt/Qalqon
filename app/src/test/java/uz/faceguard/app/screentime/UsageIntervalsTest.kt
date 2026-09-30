package uz.faceguard.app.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.UsageIntervals
import uz.faceguard.app.domain.screentime.UsageRange

/**
 * Phase 4 Step 1B-3 (pure JVM): span merge and union.
 *
 * These are the functions that stop a re-reported or overlapping span from being
 * counted twice, so the properties matter as much as the examples: order independence,
 * idempotence, and "never more than the naive sum".
 */
class UsageIntervalsTest {

    private fun range(start: Long, end: Long) = UsageRange(start, end)

    private fun merge(vararg ranges: UsageRange) = UsageIntervals.merge(ranges.toList())

    private fun union(vararg ranges: UsageRange) = UsageIntervals.unionDurationMs(ranges.toList())

    // ---- merge --------------------------------------------------------------

    @Test
    fun mergeIntervals_emptyInput() {
        assertEquals(emptyList<UsageRange>(), UsageIntervals.merge(emptyList()))
    }

    @Test
    fun mergeIntervals_singleInput() {
        assertEquals(listOf(range(0L, 10L)), merge(range(0L, 10L)))
    }

    @Test
    fun mergeIntervals_mergesOverlappingRanges() {
        assertEquals(
            listOf(range(0L, 15L), range(20L, 30L)),
            merge(range(0L, 10L), range(5L, 15L), range(20L, 30L)),
        )
    }

    @Test
    fun mergeIntervals_preservesSeparateRanges() {
        assertEquals(
            listOf(range(0L, 10L), range(20L, 30L), range(40L, 50L)),
            merge(range(0L, 10L), range(20L, 30L), range(40L, 50L)),
        )
    }

    @Test
    fun mergeIntervals_handlesUnorderedInput() {
        val expected = listOf(range(0L, 15L), range(20L, 30L), range(40L, 50L))

        assertEquals(expected, merge(range(40L, 50L), range(5L, 15L), range(20L, 30L), range(0L, 10L)))
        assertEquals(expected, merge(range(20L, 30L), range(0L, 10L), range(40L, 50L), range(5L, 15L)))
    }

    @Test
    fun touchingIntervals_followDocumentedSemantics() {
        // Documented choice: a span that starts exactly where another ends is one
        // continuous stretch of usage, so [0,10] + [10,20] -> [0,20].
        assertEquals(listOf(range(0L, 20L)), merge(range(0L, 10L), range(10L, 20L)))
        // One millisecond of separation is a real gap and stays two spans.
        assertEquals(listOf(range(0L, 10L), range(10L + 1L, 20L)), merge(range(0L, 10L), range(11L, 20L)))
    }

    @Test
    fun mergeIsIdempotentAndDuplicateRangesCollapse() {
        val once = merge(range(0L, 10L), range(5L, 15L), range(5L, 15L))

        assertEquals(listOf(range(0L, 15L)), once)
        assertEquals(once, UsageIntervals.merge(once))
    }

    // ---- union --------------------------------------------------------------

    @Test
    fun unionDurationMs_isZeroForEmptyInput() {
        assertEquals(0L, UsageIntervals.unionDurationMs(emptyList()))
    }

    @Test
    fun nonOverlappingIntervals_sumNormally() {
        assertEquals(30L, union(range(0L, 10L), range(20L, 40L)))
    }

    @Test
    fun overlappingIntervals_areUnioned() {
        // A = [0, 600000], B = [300000, 900000]: real time is 15 minutes, not 20.
        assertEquals(900_000L, union(range(0L, 600_000L), range(300_000L, 900_000L)))
    }

    @Test
    fun nestedInterval_doesNotDoubleCount() {
        assertEquals(900_000L, union(range(0L, 900_000L), range(100_000L, 200_000L)))
    }

    @Test
    fun unionDurationMs_neverExceedsTheNaiveSum() {
        val ranges = listOf(
            range(0L, 10L),
            range(3L, 7L),
            range(5L, 25L),
            range(25L, 30L),
            range(100L, 140L),
        )
        val naive = ranges.sumOf { it.durationMs }

        val unioned = UsageIntervals.unionDurationMs(ranges)

        assertEquals(70L, unioned)
        assertTrue("union $unioned must not exceed naive $naive", unioned <= naive)
    }

    @Test
    fun unionDurationMs_handlesRangesNearLongMax() {
        val nearlyMax = Long.MAX_VALUE

        // Disjoint spans near the top of the range: the difference must stay exact and
        // must not be mistaken for an overflow.
        assertEquals(
            70L,
            union(range(nearlyMax - 100L, nearlyMax - 80L), range(nearlyMax - 50L, nearlyMax)),
        )
    }
}
