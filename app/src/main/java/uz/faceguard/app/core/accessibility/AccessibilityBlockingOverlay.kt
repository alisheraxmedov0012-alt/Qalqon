package uz.faceguard.app.core.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 7.2: the platform seam for the blocking overlay window.
 *
 * It performs the actual window operations (add/remove a full-screen, touchable
 * view) so the ownership rules below — idempotency, at-most-one window, no leak —
 * stay unit-testable without an Android window.
 */
interface BlockingOverlayWindow {
    /** Adds the full-screen touchable window; false when it could not be added. */
    fun attach(): Boolean

    /** Removes the window; safe and idempotent when it is not attached. */
    fun detach()
}

/**
 * Phase 7.2: the owner of the accessibility blocking overlay.
 *
 * Idempotent: a repeated block never opens a second window, and a repeated
 * release never touches an absent one. The single [shown] state is the source of
 * truth the host reports to the runtime, so "already blocked" cannot turn into a
 * duplicate overlay.
 */
class AccessibilityBlockingOverlay(
    private val window: BlockingOverlayWindow,
) {

    private val _shown = MutableStateFlow(false)

    /** Observability for lifecycle tests: true while the blocking window is up. */
    val shown: StateFlow<Boolean> = _shown

    val isShown: Boolean get() = _shown.value

    /**
     * Shows the blocking overlay. Returns true when this call actually attached
     * it; false when it was already shown or the platform rejected it.
     */
    fun attach(): Boolean {
        if (_shown.value) return false
        val attached = runCatching { window.attach() }.getOrDefault(false)
        if (attached) _shown.value = true
        return attached
    }

    /**
     * Removes the blocking overlay. Idempotent and leak-proof: the window's own
     * detach always runs, so a desynchronised state can still not leak a view.
     * Returns true when it was shown before the call.
     */
    fun detach(): Boolean {
        val wasShown = _shown.value
        runCatching { window.detach() }
        _shown.value = false
        return wasShown
    }
}
