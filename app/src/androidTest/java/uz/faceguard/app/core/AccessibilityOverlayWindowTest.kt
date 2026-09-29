package uz.faceguard.app.core

import android.content.Context
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.accessibility.AccessibilityOverlayHost
import uz.faceguard.app.core.accessibility.AccessibilityOverlayRegistry
import uz.faceguard.app.core.accessibility.AccessibilityOverlayWindow
import uz.faceguard.app.core.accessibility.ProtectionAccessibilityService
import uz.faceguard.app.core.protection.OverlayControllerImpl

/**
 * Phase 7.2: structural tests for the accessibility input-blocking overlay.
 *
 * These verify the pieces the runtime and the platform depend on — the window is
 * a *touchable* `TYPE_ACCESSIBILITY_OVERLAY`, the service really implements the
 * overlay host, and the controller routes to a connected host. What they cannot
 * verify here is the end-to-end touch behaviour itself (a real device and a
 * user-enabled accessibility service are required); that is reported as
 * NOT VERIFIED, never as a fake pass.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityOverlayWindowTest {

    private val appContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun overlayWindowParams_areATouchableAccessibilityOverlay() {
        val params = AccessibilityOverlayWindow.layoutParams()

        assertEquals(
            "the blocking window must be an accessibility overlay",
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            params.type,
        )
        assertEquals(
            "the overlay must be full-screen",
            WindowManager.LayoutParams.MATCH_PARENT,
            params.width,
        )
        assertEquals(
            "the overlay must be full-screen",
            WindowManager.LayoutParams.MATCH_PARENT,
            params.height,
        )
        assertNotEquals(
            "FLAG_NOT_TOUCHABLE must be gone so the overlay consumes touches",
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        assertTrue(
            "the overlay must not steal input focus (IME/keys/BACK stay with the app)",
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
        )
    }

    @Test
    fun serviceImplementsTheAccessibilityOverlayHost() {
        assertTrue(
            "the accessibility service is the owner of the blocking window",
            AccessibilityOverlayHost::class.java.isAssignableFrom(ProtectionAccessibilityService::class.java),
        )
    }

    @Test
    fun controllerRoutesToTheConnectedHost() {
        val registry = AccessibilityOverlayRegistry()
        val controller = OverlayControllerImpl(appContext, accessibilityOverlays = registry)
        val host = RecordingHost()

        registry.register(host)
        controller.show()
        assertEquals("the blocking window is owned by the connected host", 1, host.showCalls)

        controller.hide()
        assertEquals(1, host.hideCalls)

        registry.unregister(host)
    }

    private class RecordingHost : AccessibilityOverlayHost {
        var showCalls = 0
        var hideCalls = 0
        override fun showBlockingOverlay(): Boolean { showCalls++; return true }
        override fun hideBlockingOverlay(): Boolean { hideCalls++; return true }
    }
}
