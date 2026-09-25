package uz.faceguard.app.screentime

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.UsageDateKey
import uz.faceguard.app.domain.screentime.UsageSnapshotDeltaEngine

/**
 * Phase 4 Step 1B-7 (pure JVM): the collection window.
 *
 * §8 forbids inventing new window logic, so this asserts that the day window reuses the
 * existing date-key semantics and is always exactly one local day — the precondition the
 * accounting contract needs, because a snapshot total cannot be split.
 */
class UsageDateKeyDayRangeTest {

    private val tashkent: ZoneId = ZoneId.of("Asia/Tashkent")
    private val minute = 60_000L

    private val dayStart = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli()
    private val dayEnd = Instant.parse("2026-09-26T00:00:00Z").toEpochMilli()

    @Test
    fun theRangeIsExactlyOneLocalDay() {
        val range = UsageDateKey.dayRange(dayStart + 12 * 60 * minute, ZoneOffset.UTC)

        assertEquals(dayStart, range.startTimeMs)
        assertEquals(dayEnd, range.endTimeMs)
        assertEquals(24 * 60 * minute, range.durationMs)
    }

    @Test
    fun theRangeIsHalfOpenSoTheLastInstantStaysInTheSameDay() {
        val range = UsageDateKey.dayRange(dayStart, ZoneOffset.UTC)

        assertEquals("2026-09-25", UsageDateKey.of(range.endTimeMs - 1L, ZoneOffset.UTC))
        assertEquals("2026-09-26", UsageDateKey.of(range.endTimeMs, ZoneOffset.UTC))
    }

    @Test
    fun theRangeIsAcceptedByTheAccountingEngineAsOneDay() {
        val range = UsageDateKey.dayRange(dayStart + minute, ZoneOffset.UTC)

        // The engine is the single authority on "attributable to one day"; nothing new is
        // asserted here, only that the window builder and that rule agree.
        assertEquals("2026-09-25", UsageSnapshotDeltaEngine(ZoneOffset.UTC).attributableDateKey(range))
    }

    @Test
    fun theDayIsDecidedByTheZoneNotByUtc() {
        // 20:30 UTC is already the 26th in Tashkent (UTC+5).
        val instant = Instant.parse("2026-09-25T20:30:00Z").toEpochMilli()

        val utc = UsageDateKey.dayRange(instant, ZoneOffset.UTC)
        val local = UsageDateKey.dayRange(instant, tashkent)

        assertEquals("2026-09-25", UsageDateKey.of(utc.startTimeMs, ZoneOffset.UTC))
        assertEquals("2026-09-26", UsageDateKey.of(local.startTimeMs, tashkent))
        assertNotEquals("the zone genuinely changes the window", utc, local)
        assertEquals("but both are still a single day", 24 * 60 * minute, local.durationMs)
    }

    @Test
    fun everyInstantInTheDayMapsToTheSameWindow() {
        val firstInstant = dayStart
        val lastInstant = dayEnd - 1L

        assertEquals(
            UsageDateKey.dayRange(firstInstant, ZoneOffset.UTC),
            UsageDateKey.dayRange(lastInstant, ZoneOffset.UTC),
        )
    }

    @Test
    fun theWindowNeverCrossesMidnight() {
        // Sweep a whole day and a bit; no window may span two dates.
        val engine = UsageSnapshotDeltaEngine(ZoneOffset.UTC)
        var instant = dayStart
        while (instant <= dayEnd + minute) {
            val range = UsageDateKey.dayRange(instant, ZoneOffset.UTC)
            assertEquals(
                "the window must be attributable to one day at $instant",
                UsageDateKey.of(range.startTimeMs, ZoneOffset.UTC),
                engine.attributableDateKey(range),
            )
            instant += 37 * minute
        }
    }

    @Test
    fun theWindowIsAlwaysPositiveAndOrdered() {
        val range = UsageDateKey.dayRange(dayStart, ZoneOffset.UTC)

        assertTrue(range.startTimeMs < range.endTimeMs)
        assertTrue(range.durationMs > 0L)
    }
}
