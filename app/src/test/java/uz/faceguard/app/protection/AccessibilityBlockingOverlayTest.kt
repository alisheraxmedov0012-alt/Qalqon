package uz.faceguard.app.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.accessibility.AccessibilityBlockingOverlay
import uz.faceguard.app.core.accessibility.BlockingOverlayWindow

/** Fake window operations, so the overlay owner's rules are asserted without Android. */
private class FakeOverlayWindow : BlockingOverlayWindow {
    var attachCalls = 0
    var detachCalls = 0
    var attachSucceeds = true
    var attachThrows = false
    var detachThrows = false
    var attached = false

    override fun attach(): Boolean {
        attachCalls++
        if (attachThrows) throw IllegalStateException("addView failed")
        if (!attachSucceeds) return false
        attached = true
        return true
    }

    override fun detach() {
        detachCalls++
        if (detachThrows) throw IllegalStateException("removeView failed")
        attached = false
    }
}

/**
 * Phase 7.2: the accessibility blocking overlay's ownership contract — idempotent
 * show/hide, at most one window, honest failure handling and leak-proof cleanup.
 */
class AccessibilityBlockingOverlayTest {

    private val window = FakeOverlayWindow()
    private val overlay = AccessibilityBlockingOverlay(window)

    @Test
    fun attach_showsExactlyOneWindow_andIsIdempotent() {
        assertTrue(overlay.attach())
        assertTrue(overlay.isShown)
        assertTrue(overlay.shown.value)

        assertFalse("a duplicate block must not open a second window", overlay.attach())
        assertEquals(1, window.attachCalls)
        assertEquals(0, window.detachCalls)
    }

    @Test
    fun detach_removesTheWindow_andIsIdempotent() {
        overlay.attach()

        assertTrue(overlay.detach())
        assertFalse(overlay.isShown)
        assertFalse(window.attached)

        assertFalse("a duplicate release must not report a live overlay", overlay.detach())
        // The window's own detach is always invoked (leak-proof), and it is safe.
        assertEquals(2, window.detachCalls)
    }

    @Test
    fun detachBeforeAttach_isSafe() {
        assertFalse(overlay.detach())
        assertFalse(overlay.shown.value)
        assertFalse(overlay.isShown)
    }

    @Test
    fun attachThatFails_doesNotReportShown_andCanBeRetried() {
        window.attachSucceeds = false

        assertFalse(overlay.attach())
        assertFalse(overlay.isShown)

        window.attachSucceeds = true
        assertTrue(overlay.attach())
        assertTrue(overlay.isShown)
        assertEquals(2, window.attachCalls)
    }

    @Test
    fun attachThatThrows_isSwallowed_andNotShown() {
        window.attachThrows = true

        assertFalse(overlay.attach())
        assertFalse(overlay.isShown)
    }

    @Test
    fun detachThatThrows_stillClearsState_neverLeaks() {
        overlay.attach()
        window.detachThrows = true

        // The window's detach attempt is made and the state is cleared regardless,
        // so a failed removal cannot leave the overlay permanently "shown".
        assertTrue(overlay.detach())
        assertFalse(overlay.isShown)
    }

    @Test
    fun repeatedBlockAndRelease_opensAndClosesOncePerCycle() {
        repeat(4) {
            overlay.attach()
            assertTrue(overlay.isShown)
            overlay.detach()
            assertFalse(overlay.isShown)
        }
        assertEquals("one window per block cycle", 4, window.attachCalls)
        assertEquals("one removal per release cycle", 4, window.detachCalls)
        assertFalse(window.attached)
    }

    @Test
    fun duplicateBlockWhileShown_neverRebinds() {
        overlay.attach()
        repeat(10) { overlay.attach() }
        assertEquals(1, window.attachCalls)
        assertEquals(0, window.detachCalls)
    }
}
