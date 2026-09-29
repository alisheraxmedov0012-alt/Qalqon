package uz.faceguard.app.core.accessibility

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 7.2: a window that can actually block input, owned by the accessibility
 * service.
 *
 * Only an [android.accessibilityservice.AccessibilityService] may add a
 * `TYPE_ACCESSIBILITY_OVERLAY` window, and such a window is the only mechanism a
 * third-party app has to *consume* another app's touches. This interface is the
 * seam the runtime's overlay controller talks to; the platform-specific view
 * lives in [ProtectionAccessibilityService].
 */
interface AccessibilityOverlayHost {

    /**
     * Shows the touchable blocking overlay. Returns true when the overlay is up
     * after the call — including when it was *already* up, so a repeated block
     * never falls through to a second overlay. False only when it could not be
     * shown (e.g. the window reject was rejected).
     */
    fun showBlockingOverlay(): Boolean

    /** Removes the blocking overlay. Idempotent; false when nothing was shown. */
    fun hideBlockingOverlay(): Boolean
}

/**
 * Phase 7.2: the single process-wide pointer to the currently connected
 * accessibility overlay host.
 *
 * The runtime's overlay controller (which decides *that* something must be
 * blocked) must reach the accessibility service (which knows *how*), without
 * either holding the other: the runtime depends on this registry, and the
 * service registers itself into it when the system binds it. That keeps the
 * dependency one-way and avoids an AccessibilityService -> Runtime -> Service
 * cycle.
 *
 * The reference is cleared on unbind/destroy so a dead service can never be used
 * (and never leaks).
 */
@Singleton
class AccessibilityOverlayRegistry @Inject constructor() {

    @Volatile
    private var host: AccessibilityOverlayHost? = null

    fun register(value: AccessibilityOverlayHost) {
        host = value
    }

    fun unregister(value: AccessibilityOverlayHost) {
        // Only clear our own registration: a late unbind of an old instance must
        // not remove a newer, live one.
        if (host === value) host = null
    }

    fun current(): AccessibilityOverlayHost? = host
}
