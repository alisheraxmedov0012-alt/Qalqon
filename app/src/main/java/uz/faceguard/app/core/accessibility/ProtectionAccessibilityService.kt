package uz.faceguard.app.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uz.faceguard.app.core.protection.ProtectionRuntime

/**
 * Group 7 / Phase 7.2: system interaction layer for system-wide enforcement.
 *
 * Responsibility is deliberately narrow: it turns Android accessibility window
 * transitions into a foreground package signal for the app-scoped
 * [ProtectionRuntime], and — since Phase 7.2 — owns the touchable
 * `TYPE_ACCESSIBILITY_OVERLAY` blocking window that actually *consumes* the
 * child's touches over a protected app:
 *
 * ```
 * Android system → ProtectionAccessibilityService → ProtectionRuntime
 *                → ProtectionEngine → PolicyEvaluator → ProtectionActionExecutor
 *                → OverlayController → AccessibilityOverlayHost (this service)
 *                → TYPE_ACCESSIBILITY_OVERLAY (touch blocking)
 * ```
 *
 * It contains no policy logic, no blocking decision, no database access and no
 * camera/recognition code, and creates no runtime of its own: the Hilt-injected
 * runtime singleton is the single source of truth (the same instance used by the
 * UI and the Group 6 foreground service). It only renders what the executor asks
 * it to render.
 *
 * Privacy: only the package identifier of relevant window transitions is read.
 * Screen content, text, passwords and notifications are never accessed
 * (`canRetrieveWindowContent="false"`), and nothing is written to the database by
 * this layer. The overlay renders a fixed message and an optional button — no
 * window content is read to do so.
 *
 * The user must explicitly enable this service in Android Settings; the app only
 * detects the state and links the user there.
 */
@AndroidEntryPoint
class ProtectionAccessibilityService : AccessibilityService(), AccessibilityOverlayHost {

    @Inject lateinit var runtime: ProtectionRuntime
    @Inject lateinit var overlayRegistry: AccessibilityOverlayRegistry

    /**
     * The blocking overlay, created lazily on first use so the (rare) cost and the
     * platform window are only touched when protection actually blocks. Null until
     * then, and reset on service shutdown so no WindowManager view can leak.
     */
    private var blockingOverlay: AccessibilityBlockingOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Register before reporting connected so a block that starts immediately
        // can already find us.
        overlayRegistry.register(this)
        _connected.value = true
        runtime.onAccessibilityConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        // Privacy boundary: every other event type is ignored, not stored.
        if (!AccessibilityEventFilter.isRelevant(type)) return
        // Metadata only: the package identifier, never window content or text.
        val packageName = event.packageName?.toString()
        // Phase 7.2: never adopt QALQON's own window (the protection UI or the
        // blocking overlay) as the foreground app. Doing so would look like "the
        // protected app left the foreground" and wrongly release the block,
        // making the overlay flicker and re-appear (self-interception).
        if (AccessibilityEventFilter.isOwnPackage(packageName, this.packageName)) return
        runtime.onAccessibilityForegroundApp(packageName)
    }

    // ------------------------------------------------------------ overlay host

    override fun showBlockingOverlay(): Boolean {
        val overlay = blockingOverlay ?: AccessibilityBlockingOverlay(
            AccessibilityOverlayWindow(
                context = this,
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager,
                onRequestExtraTime = { runtime.requestExtraTime() },
            ),
        ).also { blockingOverlay = it }
        overlay.attach()
        // Report "is it up now", so a repeated block never spawns a second overlay
        // and never falls through to the legacy window.
        return overlay.isShown
    }

    override fun hideBlockingOverlay(): Boolean = blockingOverlay?.detach() ?: false

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardownOverlay()
        _connected.value = false
        runtime.onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardownOverlay()
        _connected.value = false
        runtime.onAccessibilityDisconnected()
        super.onDestroy()
    }

    /** Removes the blocking window and stops advertising ourselves, so nothing leaks. */
    private fun teardownOverlay() {
        blockingOverlay?.detach()
        blockingOverlay = null
        overlayRegistry.unregister(this)
    }

    companion object {
        private val _connected = MutableStateFlow(false)

        /**
         * Lifecycle observability: true while the system has bound the service.
         * Used by lifecycle tests, which cannot rely on restricted APIs to read
         * the enabled-service state.
         */
        val connected: StateFlow<Boolean> = _connected
    }
}
