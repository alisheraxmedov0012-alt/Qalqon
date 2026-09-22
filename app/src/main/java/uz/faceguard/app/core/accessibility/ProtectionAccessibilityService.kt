package uz.faceguard.app.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uz.faceguard.app.core.protection.ProtectionRuntime

/**
 * Group 7: system interaction layer for system-wide enforcement.
 *
 * Responsibility is deliberately narrow — it turns Android accessibility window
 * transitions into a foreground package signal for the app-scoped
 * [ProtectionRuntime]:
 *
 * ```
 * Android system → ProtectionAccessibilityService → ProtectionRuntime
 *                → ProtectionEngine → PolicyEvaluator → ProtectionActionExecutor
 * ```
 *
 * It contains no policy logic, no blocking decision, no database access, no
 * camera/recognition code and creates no runtime of its own: the Hilt-injected
 * runtime singleton is the single source of truth (the same instance used by the
 * UI and the Group 6 foreground service).
 *
 * Privacy: only the package identifier of relevant window transitions is read.
 * Screen content, text, passwords and notifications are never accessed
 * (`canRetrieveWindowContent="false"`), and nothing is written to the database
 * by this layer — Group 5's transition-based activity logging is untouched.
 *
 * The user must explicitly enable this service in Android Settings; the app only
 * detects the state and links the user there.
 */
@AndroidEntryPoint
class ProtectionAccessibilityService : AccessibilityService() {

    @Inject lateinit var runtime: ProtectionRuntime

    override fun onServiceConnected() {
        super.onServiceConnected()
        _connected.value = true
        runtime.onAccessibilityConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        // Privacy boundary: every other event type is ignored, not stored.
        if (!AccessibilityEventFilter.isRelevant(type)) return
        // Metadata only: the package identifier, never window content or text.
        runtime.onAccessibilityForegroundApp(event.packageName?.toString())
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        _connected.value = false
        runtime.onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _connected.value = false
        runtime.onAccessibilityDisconnected()
        super.onDestroy()
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
