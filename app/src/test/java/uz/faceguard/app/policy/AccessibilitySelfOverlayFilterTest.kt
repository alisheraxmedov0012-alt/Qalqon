package uz.faceguard.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.accessibility.AccessibilityEventFilter

/**
 * Phase 7.2: QALQON's own windows (including the blocking accessibility overlay)
 * must never be adopted as the foreground app — otherwise the overlay's own window
 * events would look like "the protected app left" and wrongly release the block
 * (self-interception / flicker).
 */
class AccessibilitySelfOverlayFilterTest {

    private val own = "uz.faceguard.app"

    @Test
    fun ownPackageIsRecognised() {
        assertTrue(AccessibilityEventFilter.isOwnPackage(own, own))
    }

    @Test
    fun otherPackagesAreNotOwnPackage() {
        assertFalse(AccessibilityEventFilter.isOwnPackage("com.example.game", own))
        assertFalse(AccessibilityEventFilter.isOwnPackage("uz.faceguard.app.other", own))
    }

    @Test
    fun blanksAreNeverOwnPackage() {
        assertFalse(AccessibilityEventFilter.isOwnPackage(null, own))
        assertFalse(AccessibilityEventFilter.isOwnPackage("", own))
        assertFalse(AccessibilityEventFilter.isOwnPackage("   ", own))
        assertFalse(AccessibilityEventFilter.isOwnPackage(own, null))
        assertFalse(AccessibilityEventFilter.isOwnPackage(null, null))
    }
}
