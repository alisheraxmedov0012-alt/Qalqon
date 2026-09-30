package uz.faceguard.app.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageInterval
import uz.faceguard.app.domain.screentime.UsageRange

/**
 * Phase 4 Step 1B-3 (pure JVM): interval duration and the validation that keeps an
 * unwritable interval from ever existing. No Android, no clock, no database.
 */
class UsageIntervalTest {

    private val second = 1_000L
    private val minute = 60_000L
    private val max = ScreenTimeUsageRepository.MAX_DELTA_MS

    private fun interval(start: Long, end: Long) = UsageInterval(
        packageName = "com.google.android.youtube",
        category = AppCategory.VIDEO,
        startTimeMs = start,
        endTimeMs = end,
    )

    // ---- duration -----------------------------------------------------------

    @Test
    fun oneSecondInterval_returns1000Ms() {
        assertEquals(second, interval(0L, second).elapsedMs)
    }

    @Test
    fun fiveMinuteInterval_returns300000Ms() {
        assertEquals(5 * minute, interval(0L, 5 * minute).elapsedMs)
    }

    @Test
    fun elapsedIsDerivedFromTheBoundsAndNeverRounded() {
        val odd = interval(1_700_000_000_000L, 1_700_000_000_000L + 59_999L)

        assertEquals(59_999L, odd.elapsedMs)
        assertEquals(UsageRange(odd.startTimeMs, odd.endTimeMs), odd.range)
    }

    // ---- validation ---------------------------------------------------------

    @Test
    fun zeroLengthInterval_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { interval(0L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { interval(5 * minute, 5 * minute) }
    }

    @Test
    fun reversedInterval_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { interval(5 * minute, minute) }
    }

    @Test
    fun negativeTime_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { interval(-1L, minute) }
        assertThrows(IllegalArgumentException::class.java) { interval(-5 * minute, -minute) }
        assertThrows(IllegalArgumentException::class.java) { UsageRange(-1L, 10L) }
    }

    @Test
    fun blankPackageName_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UsageInterval("   ", AppCategory.VIDEO, 0L, minute)
        }
    }

    @Test
    fun oneDayInterval_isAccepted() {
        assertEquals(max, interval(0L, max).elapsedMs)
        assertEquals(max, UsageInterval.of("com.a", AppCategory.OTHER, 0L, max).elapsedMs)
    }

    @Test
    fun greaterThanOneDayInterval_isRejected() {
        // Chosen semantics: rejected, not silently truncated or split. A span of a day
        // or more is a monitoring concern, and nothing may be written that the
        // repository would refuse.
        assertThrows(IllegalArgumentException::class.java) { interval(0L, max + 1L) }
        assertThrows(IllegalArgumentException::class.java) {
            UsageInterval.of("com.a", AppCategory.OTHER, 0L, max + 1L)
        }
    }

    // ---- measured durations -------------------------------------------------

    @Test
    fun of_derivesTheEndFromStartAndMeasuredDuration() {
        val built = UsageInterval.of("com.a", AppCategory.GAMES, 10 * minute, 5 * minute)

        assertEquals(10 * minute, built.startTimeMs)
        assertEquals(15 * minute, built.endTimeMs)
        assertEquals(5 * minute, built.elapsedMs)
    }

    @Test
    fun of_rejectsNonPositiveDuration() {
        assertThrows(IllegalArgumentException::class.java) {
            UsageInterval.of("com.a", AppCategory.OTHER, 0L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsageInterval.of("com.a", AppCategory.OTHER, 0L, -1L)
        }
    }

    @Test
    fun of_rejectsAnEndThatWouldOverflowLong() {
        assertThrows(IllegalArgumentException::class.java) {
            UsageInterval.of("com.a", AppCategory.OTHER, Long.MAX_VALUE - 10L, 100L)
        }
    }
}
