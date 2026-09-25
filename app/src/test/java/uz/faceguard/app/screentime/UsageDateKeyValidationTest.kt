package uz.faceguard.app.screentime

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.ScreenTimeLimits
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageDateKey

/**
 * Phase 4 Step 1B-2 (pure JVM): the day-key and increment-bound contract the usage
 * repository enforces, checked without a device.
 */
class UsageDateKeyValidationTest {

    @Test
    fun acceptsOnlyTheCanonicalDayKey() {
        assertTrue(UsageDateKey.isValid("2026-09-24"))
        assertTrue(UsageDateKey.isValid("2026-01-01"))
        assertTrue("a leap day is a real day", UsageDateKey.isValid("2024-02-29"))
    }

    @Test
    fun rejectsAnythingNotProducedByOf() {
        assertFalse("zero padding is required", UsageDateKey.isValid("2026-1-1"))
        assertFalse("a day that does not exist", UsageDateKey.isValid("2026-02-30"))
        assertFalse("not a date at all", UsageDateKey.isValid("not-a-date"))
        assertFalse(UsageDateKey.isValid(""))
        assertFalse("no time component", UsageDateKey.isValid("2026-09-24T00:00"))
    }

    @Test
    fun ofAndIsValidAgree() {
        val key = UsageDateKey.of(LocalDate.of(2026, 9, 24).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), ZoneOffset.UTC)

        assertEquals("2026-09-24", key)
        assertTrue(UsageDateKey.isValid(key))
    }

    @Test
    fun aSingleIncrementIsBoundedByOneDay() {
        assertEquals(
            ScreenTimeLimits.MINUTES_PER_DAY * ScreenTimeLimits.MS_PER_MINUTE,
            ScreenTimeUsageRepository.MAX_DELTA_MS,
        )
    }
}
