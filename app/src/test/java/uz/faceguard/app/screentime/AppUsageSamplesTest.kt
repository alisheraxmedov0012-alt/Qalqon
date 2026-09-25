package uz.faceguard.app.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import uz.faceguard.app.domain.screentime.AppUsageSample
import uz.faceguard.app.domain.screentime.AppUsageSamples

/**
 * Phase 4 Step 1B-4 (pure JVM): collapsing raw platform rows into one figure per package.
 *
 * A platform query can hand back several rows for the same package, in any order, and
 * sometimes a zero row. These are the deterministic rules that stand between that and the
 * accounting layer.
 */
class AppUsageSamplesTest {

    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"

    private fun sample(packageName: String, foregroundMs: Long) = AppUsageSample(packageName, foregroundMs)

    // ---- aggregation --------------------------------------------------------

    @Test
    fun multipleRecords_samePackage_areAggregated() {
        val aggregated = AppUsageSamples.aggregate(
            listOf(sample(youtube, 1_000L), sample(youtube, 2_000L), sample(youtube, 3_000L)),
        )

        assertEquals(listOf(sample(youtube, 6_000L)), aggregated)
    }

    @Test
    fun differentPackages_remainSeparate() {
        val aggregated = AppUsageSamples.aggregate(
            listOf(sample(youtube, 1_000L), sample(tiktok, 2_000L)),
        )

        assertEquals(
            listOf(sample(youtube, 1_000L), sample(tiktok, 2_000L)),
            aggregated,
        )
    }

    @Test
    fun inputOrderDoesNotChangeResult() {
        val rows = listOf(sample(youtube, 1_000L), sample(tiktok, 2_000L), sample(youtube, 500L))

        val expected = AppUsageSamples.aggregate(rows)

        assertEquals(expected, AppUsageSamples.aggregate(rows.reversed()))
        assertEquals(expected, AppUsageSamples.aggregate(listOf(rows[2], rows[0], rows[1])))
        assertEquals(
            "output is ordered by package name",
            listOf(youtube, tiktok),
            expected.map { it.packageName },
        )
    }

    @Test
    fun zeroDurationRowsAreIgnored() {
        val aggregated = AppUsageSamples.aggregate(
            listOf(sample(youtube, 1_000L), sample(tiktok, 0L), sample(youtube, 0L)),
        )

        assertEquals(
            "a zero row is not usage and must not create a package entry",
            listOf(sample(youtube, 1_000L)),
            aggregated,
        )
    }

    @Test
    fun allZeroRowsAggregateToNothing() {
        assertEquals(
            emptyList<AppUsageSample>(),
            AppUsageSamples.aggregate(listOf(sample(youtube, 0L), sample(tiktok, 0L))),
        )
    }

    @Test
    fun emptyInputAggregatesToNothing() {
        assertEquals(emptyList<AppUsageSample>(), AppUsageSamples.aggregate(emptyList()))
    }

    @Test
    fun aSingleRowIsReturnedUnchanged() {
        assertEquals(listOf(sample(youtube, 4_321L)), AppUsageSamples.aggregate(listOf(sample(youtube, 4_321L))))
    }

    @Test
    fun paddingIsNormalizedSoTheSameAppCannotSplitInTwo() {
        val aggregated = AppUsageSamples.aggregate(
            listOf(sample(" $youtube ", 1_000L), sample(youtube, 2_000L)),
        )

        assertEquals(listOf(sample(youtube, 3_000L)), aggregated)
    }

    // ---- overflow -----------------------------------------------------------

    @Test
    fun aggregationOverflow_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AppUsageSamples.aggregate(
                listOf(sample(youtube, Long.MAX_VALUE), sample(youtube, 1L)),
            )
        }
    }

    @Test
    fun summingToExactlyLongMaxIsStillAllowed() {
        assertEquals(
            listOf(sample(youtube, Long.MAX_VALUE)),
            AppUsageSamples.aggregate(
                listOf(sample(youtube, Long.MAX_VALUE - 1L), sample(youtube, 1L)),
            ),
        )
    }

    // ---- model validation ---------------------------------------------------

    @Test
    fun blankPackage_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { sample("   ", 1_000L) }
        assertThrows(IllegalArgumentException::class.java) { sample("", 1_000L) }
    }

    @Test
    fun negativeDuration_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { sample(youtube, -1L) }
    }

    @Test
    fun zeroDurationIsAValidSampleUntilAggregation() {
        // The model allows 0 (the platform reports it); aggregation is what drops it, so
        // the "no phantom row" rule lives in exactly one place.
        assertEquals(0L, sample(youtube, 0L).foregroundMs)
    }
}
