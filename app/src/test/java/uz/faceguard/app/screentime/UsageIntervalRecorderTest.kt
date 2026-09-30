package uz.faceguard.app.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import uz.faceguard.app.domain.screentime.AppCategory
import uz.faceguard.app.domain.screentime.ElapsedTimeSource
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.UsageIntervalRecorder

/**
 * Phase 4 Step 1B-3 (pure JVM): the duration a session reports must come from the
 * monotonic clock, so no wall-clock change can distort it. Both clocks are fakes here;
 * there is no sleep and no real time.
 */
class UsageIntervalRecorderTest {

    private val minute = 60_000L
    private val youtube = "com.google.android.youtube"

    private class FakeElapsed(var now: Long = 0L) : ElapsedTimeSource {
        override fun elapsedRealtimeMs(): Long = now
    }

    private var wallClock = 1_700_000_000_000L
    private val elapsed = FakeElapsed()
    private val recorder = UsageIntervalRecorder(elapsed) { wallClock }

    @Test
    fun measuresDurationFromTheMonotonicSource() {
        val session = recorder.begin(youtube, AppCategory.VIDEO)

        elapsed.now += 5 * minute

        val interval = recorder.stop(session)
        assertNotNull(interval)
        assertEquals(5 * minute, interval!!.elapsedMs)
    }

    @Test
    fun startTimeComesFromTheWallClock() {
        wallClock = 1_700_000_123_456L

        val interval = recorder.begin(youtube, AppCategory.VIDEO).let {
            elapsed.now += minute
            recorder.stop(it)
        }

        assertEquals(1_700_000_123_456L, interval!!.startTimeMs)
        assertEquals(1_700_000_123_456L + minute, interval.endTimeMs)
    }

    @Test
    fun wallClockRollbackDoesNotChangeTheMeasuredDuration() {
        val session = recorder.begin(youtube, AppCategory.VIDEO)

        // The user sets the device clock back by 10 minutes mid-session...
        wallClock -= 10 * minute
        elapsed.now += 3 * minute

        val interval = recorder.stop(session)

        assertEquals("the monotonic clock is unaffected by the change", 3 * minute, interval!!.elapsedMs)
        assertEquals("the day anchor is still where the session began", 1_700_000_000_000L, interval.startTimeMs)
    }

    @Test
    fun wallClockForwardJumpDoesNotInventUsage() {
        val session = recorder.begin(youtube, AppCategory.VIDEO)

        wallClock += 8 * 60 * minute
        elapsed.now += 30_000L

        val interval = recorder.stop(session)

        assertEquals(30_000L, interval!!.elapsedMs)
    }

    @Test
    fun openingAndClosingInTheSameInstantProducesNoInterval() {
        val session = recorder.begin(youtube, AppCategory.VIDEO)

        assertNull("an empty interval is not a valid interval", recorder.stop(session))
    }

    @Test
    fun aSessionLongerThanADayIsRejected() {
        val session = recorder.begin(youtube, AppCategory.VIDEO)

        elapsed.now += ScreenTimeUsageRepository.MAX_DELTA_MS + 1L

        assertThrows(IllegalArgumentException::class.java) { recorder.stop(session) }
    }

    @Test
    fun aFullDaySessionIsStillAccepted() {
        val session = recorder.begin(youtube, AppCategory.VIDEO)

        elapsed.now += ScreenTimeUsageRepository.MAX_DELTA_MS

        assertEquals(ScreenTimeUsageRepository.MAX_DELTA_MS, recorder.stop(session)!!.elapsedMs)
    }
}
