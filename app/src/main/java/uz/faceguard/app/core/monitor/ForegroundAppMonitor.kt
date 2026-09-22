package uz.faceguard.app.core.monitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent

import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Polling foreground monitor. Best-practical MVP: uses UsageStats events
 * (USAGE_ACCESS) and falls back to null when permission is missing. Documented
 * limitation: event delivery can lag on some OEMs.
 *
 * Group 7 adds the accessibility layer as a second, more responsive source:
 * while [setAccessibilityActive] is true the accessibility service's window
 * transitions are authoritative and the usage-stats poll no longer overwrites
 * them (it would otherwise clobber a real package with a stale/null value).
 */
class ForegroundAppMonitor(private val context: Context) {

    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current

    private var job: Job? = null
    private var lastFallbackAt = 0L

    /** True while the accessibility service is bound and feeding transitions. */
    @Volatile
    private var accessibilityActive = false

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun start(scope: CoroutineScope, intervalMs: Long = 2_000L) {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            while (true) {
                // Accessibility, when bound, is the authoritative source.
                if (!accessibilityActive) _current.value = pollForeground()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Marks the accessibility layer as the authoritative foreground source (or
     * hands control back to usage-stats polling when it is unbound).
     */
    fun setAccessibilityActive(active: Boolean) {
        accessibilityActive = active
    }

    /** Foreground package reported by the accessibility service (metadata only). */
    fun updateFromAccessibility(packageName: String) {
        _current.value = packageName
    }

    private fun pollForeground(): String? {
        if (!hasUsageAccess()) return null
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()

        val events = usage.queryEvents(end - EVENT_WINDOW_MS, end)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = event.packageName
            }
        }
        if (last != null) return last

        // An app can stay in the foreground longer than the event window, so
        // fall back to the most recently used app (throttled to keep it cheap).
        if (end - lastFallbackAt < FALLBACK_THROTTLE_MS) return null
        lastFallbackAt = end
        return usage
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - STATS_WINDOW_MS, end)
            .filter { it.lastTimeUsed > 0 }
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    private companion object {
        const val EVENT_WINDOW_MS = 5 * 60_000L
        const val STATS_WINDOW_MS = 24 * 60 * 60_000L
        const val FALLBACK_THROTTLE_MS = 10_000L
    }
}
