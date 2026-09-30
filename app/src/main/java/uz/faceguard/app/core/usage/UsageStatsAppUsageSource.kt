package uz.faceguard.app.core.usage

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uz.faceguard.app.domain.screentime.AppUsageQueryResult
import uz.faceguard.app.domain.screentime.AppUsageSample
import uz.faceguard.app.domain.screentime.AppUsageSamples
import uz.faceguard.app.domain.screentime.AppUsageSource
import uz.faceguard.app.domain.screentime.UsageAccessState
import uz.faceguard.app.domain.screentime.UsageRange

/**
 * Phase 4 Step 1B-4: the Android implementation of [AppUsageSource], over
 * `UsageStatsManager`.
 *
 * Scope, deliberately: this reads *historical, aggregate* usage — "how long was each
 * package in the foreground inside this window". It is not the Phase 7/8 foreground
 * monitor (which watches the *current* app for enforcement), and it writes nothing:
 * persisting usage is [uz.faceguard.app.domain.screentime.ScreenTimeUsageAccounting]'s
 * job, so this adapter stays replaceable, replayable and testable.
 *
 * It reports [AppUsageSample] (a total per package), never an interval, because the
 * platform does not provide start/end for `totalTimeInForeground`.
 *
 * Filters nothing by policy: system packages, launchers and QALQON itself are *not*
 * blacklisted here. Which apps count is a policy decision for a later layer; the only
 * rows dropped are ones that carry no usable data (blank package name, negative
 * duration).
 */
class UsageStatsAppUsageSource(private val context: Context) : AppUsageSource {

    override fun usageAccess(): UsageAccessState =
        if (AndroidUsageAccess.isGranted(context)) UsageAccessState.AVAILABLE else UsageAccessState.UNAVAILABLE

    override suspend fun queryUsage(range: UsageRange): AppUsageQueryResult {
        if (usageAccess() != UsageAccessState.AVAILABLE) return AppUsageQueryResult.Unavailable

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return AppUsageQueryResult.Unavailable

        // The caller's exact window is used as given: no "today" is ever assumed here,
        // and INTERVAL_DAILY is only the platform's aggregation bucket, not a date range.
        val stats = withContext(Dispatchers.IO) {
            runCatching {
                manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    range.startTimeMs,
                    range.endTimeMs,
                )
            }.getOrNull()
        } ?: return AppUsageQueryResult.Unavailable

        return AppUsageQueryResult.Available(
            AppUsageSamples.aggregate(stats.mapNotNull { it.toSampleOrNull() }),
        )
    }

    private fun UsageStats.toSampleOrNull(): AppUsageSample? {
        val packageName = packageName.orEmpty().trim()
        if (packageName.isEmpty()) return null
        val foregroundMs = totalTimeInForeground
        if (foregroundMs < 0L) return null
        return AppUsageSample(packageName, foregroundMs)
    }
}
