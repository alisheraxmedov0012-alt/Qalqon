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
 * limitation: event delivery can lag on some OEMs; accessibility-based
 * monitoring lands in the protection phase.
 */
class ForegroundAppMonitor(private val context: Context) {

    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current

    private var job: Job? = null

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
                _current.value = pollForeground()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun pollForeground(): String? {
        if (!hasUsageAccess()) return null
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 10_000L
        val events = usage.queryEvents(begin, end)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = event.packageName
            }
        }
        return last
    }
}
