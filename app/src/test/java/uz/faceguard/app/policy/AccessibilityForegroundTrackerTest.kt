package uz.faceguard.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.accessibility.AccessibilityForegroundTracker

/**
 * Group 7: duplicate-protection for the accessibility event stream. Android can
 * deliver the same window transition many times per second; only a genuine
 * foreground change may reach the runtime (which then feeds the existing engine).
 */
class AccessibilityForegroundTrackerTest {

    private val tracker = AccessibilityForegroundTracker()

    @Test
    fun firstForegroundPackageIsAccepted() {
        assertTrue(tracker.onForegroundPackage("com.example.youtube"))
    }

    @Test
    fun blankAndMissingPackagesAreIgnored() {
        assertFalse(tracker.onForegroundPackage(null))
        assertFalse(tracker.onForegroundPackage(""))
        assertFalse(tracker.onForegroundPackage("   "))
    }

    @Test
    fun duplicateTransitionsAreIgnored() {
        assertTrue(tracker.onForegroundPackage("com.example.youtube"))
        assertFalse(tracker.onForegroundPackage("com.example.youtube"))
        assertFalse(tracker.onForegroundPackage("com.example.youtube"))
    }

    @Test
    fun everyRealPackageChangeIsAccepted() {
        assertTrue(tracker.onForegroundPackage("com.example.youtube"))
        assertTrue(tracker.onForegroundPackage("com.example.chrome"))
        assertTrue(tracker.onForegroundPackage("com.example.youtube"))
    }

    @Test
    fun eventBurstCollapsesToOneTransition() {
        val accepted = (1..30).count { tracker.onForegroundPackage("com.example.youtube") }

        assertTrue("burst must collapse to a single transition", accepted == 1)
    }

    @Test
    fun resetClearsTheBaseline() {
        tracker.onForegroundPackage("com.example.youtube")

        tracker.reset()

        assertTrue(tracker.onForegroundPackage("com.example.youtube"))
    }
}
