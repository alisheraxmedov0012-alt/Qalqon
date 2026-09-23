package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.liveness.LivenessFrame
import uz.faceguard.app.core.liveness.LivenessWindow

/**
 * Group 9: the temporal buffer is independent of the recognition engine's
 * debounce / confirmation / frame-TTL. These tests pin its two guarantees:
 * strictly increasing timestamps (duplicates/out-of-order ignored) and time-based
 * decay so stale evidence cannot latch the signal.
 */
class LivenessWindowTest {

    private fun frame(ts: Long) = LivenessFrame(facePresent = true, timestamp = ts)

    @Test
    fun `frames are kept in arrival order`() {
        val window = LivenessWindow()

        assertTrue(window.add(frame(1)))
        assertTrue(window.add(frame(2)))
        assertTrue(window.add(frame(3)))

        assertEquals(3, window.size)
        assertEquals(listOf(1L, 2L, 3L), window.snapshot(3).map { it.timestamp })
    }

    @Test
    fun `duplicate and out-of-order frames are rejected`() {
        val window = LivenessWindow()
        window.add(frame(10))

        assertFalse("same timestamp", window.add(frame(10)))
        assertFalse("older timestamp", window.add(frame(5)))
        assertEquals(1, window.size)
    }

    @Test
    fun `frames older than the window are evicted`() {
        val window = LivenessWindow(maxAgeMs = 1_000L, maxFrames = 24)
        window.add(frame(1_000))
        window.add(frame(1_500))
        window.add(frame(2_500))

        // 1_000 is older than 2_500 - 1_000; the other two remain.
        assertEquals(listOf(1_500L, 2_500L), window.snapshot(2_500).map { it.timestamp })
    }

    @Test
    fun `the window is bounded by its max size`() {
        val window = LivenessWindow(maxAgeMs = 10_000L, maxFrames = 3)
        for (ts in 1L..5L) window.add(frame(ts))

        assertEquals(3, window.size)
        assertEquals(listOf(3L, 4L, 5L), window.snapshot(5).map { it.timestamp })
    }

    @Test
    fun `an idle window decays to empty`() {
        val window = LivenessWindow(maxAgeMs = 1_000L)
        window.add(frame(1_000))

        assertEquals(1, window.size)
        assertTrue("still inside the window", window.snapshot(1_500).isNotEmpty())
        assertTrue("aged out with no new frames", window.snapshot(2_500).isEmpty())
        assertEquals(0, window.size)
    }
}
