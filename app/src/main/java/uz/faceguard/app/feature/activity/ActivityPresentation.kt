package uz.faceguard.app.feature.activity

import androidx.annotation.StringRes
import uz.faceguard.app.R
import uz.faceguard.app.domain.model.ActivityEvent
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.feature.child.ChildOverview
import uz.faceguard.app.feature.requests.RequestRow

/**
 * UI/UX redesign, Phase 5: the Activity center's presentation mapping.
 *
 * Pure (no Android framework, no repository access), so the activity centre's
 * information architecture is unit-testable on the JVM. It only *selects* which real
 * value becomes which localized label — no business logic, no fabricated data, and
 * no calculation the existing engine already owns.
 */

// ---------------------------------------------------------------- events

/**
 * One recent event as the UI renders it. [childName] is `null` because the activity
 * log is account-scoped and an event carries no child id — the centre never invents a
 * child association the data does not have.
 */
data class ActivityEventItem(
    val type: ActivityEventType,
    val labelRes: Int,
    val detail: String?,
    val at: Long,
)

// -------------------------------------------------------------- overview

/** The three compact overview metrics the centre leads with. */
enum class ActivityMetricKind { SCREEN_TIME, PENDING_REQUESTS, EVENTS_TODAY }

/**
 * A metric's value. [DurationMs] carries exact milliseconds (rendered by the one
 * existing duration formatter); [Unavailable] is a real state meaning the value
 * could not be read — deliberately distinct from a genuine `0`.
 */
sealed interface ActivityMetricValue {
    data class Count(val count: Int) : ActivityMetricValue
    data class DurationMs(val ms: Long) : ActivityMetricValue
    data object Unavailable : ActivityMetricValue
}

data class ActivityMetric(
    val kind: ActivityMetricKind,
    @StringRes val labelRes: Int,
    val value: ActivityMetricValue,
)

/**
 * The overview metrics, built only from values the caller already has.
 *
 * Screen time is [ActivityMetricValue.Unavailable] when Usage Access is not granted
 * (it genuinely cannot be read) — never rendered as `0`. A granted-but-unused day is
 * a real `0` and is shown as such. Request and event counts are always real.
 */
fun activityMetrics(
    screenTimeTodayMs: Long?,
    pendingRequestCount: Int,
    eventsToday: Int,
): List<ActivityMetric> = listOf(
    ActivityMetric(
        kind = ActivityMetricKind.SCREEN_TIME,
        labelRes = R.string.activity_metric_screen_time,
        value = screenTimeTodayMs?.let { ActivityMetricValue.DurationMs(it) }
            ?: ActivityMetricValue.Unavailable,
    ),
    ActivityMetric(
        kind = ActivityMetricKind.PENDING_REQUESTS,
        labelRes = R.string.activity_metric_requests,
        value = ActivityMetricValue.Count(pendingRequestCount),
    ),
    ActivityMetric(
        kind = ActivityMetricKind.EVENTS_TODAY,
        labelRes = R.string.activity_metric_events,
        value = ActivityMetricValue.Count(eventsToday),
    ),
)

/** Counts the events that fall on [todayKey] in the device zone, using [dateKeyOf]. */
fun eventsTodayCount(events: List<ActivityEvent>, todayKey: String, dateKeyOf: (Long) -> String): Int =
    events.count { dateKeyOf(it.at) == todayKey }

// ----------------------------------------------------------- screen time

/** One child's real usage for one day. */
data class ActivityDayUsage(val dateKey: String, val usedMs: Long)

/**
 * One child's usage as the centre presents it: [todayMs] is `null` when Usage Access
 * is unavailable (unknown, not zero), and [pastDays] then holds nothing.
 */
data class ActivityChildUsage(
    val childId: Long,
    val name: String,
    val initial: String,
    val todayMs: Long?,
    val pastDays: List<ActivityDayUsage>,
)

/** How a usage day is labelled: today/yesterday by name, otherwise its ISO date. */
enum class ActivityDayKind { TODAY, YESTERDAY, EARLIER }

fun activityDayKind(dateKey: String, todayKey: String, yesterdayKey: String): ActivityDayKind = when (dateKey) {
    todayKey -> ActivityDayKind.TODAY
    yesterdayKey -> ActivityDayKind.YESTERDAY
    else -> ActivityDayKind.EARLIER
}

/** The label resource for a day kind, or `null` for an earlier day (its ISO date is shown). */
@StringRes
fun activityDayLabelRes(kind: ActivityDayKind): Int? = when (kind) {
    ActivityDayKind.TODAY -> R.string.activity_day_today
    ActivityDayKind.YESTERDAY -> R.string.activity_day_yesterday
    ActivityDayKind.EARLIER -> null
}

/** One child's line inside a day group. */
data class ActivityUsageRow(val childId: Long, val name: String, val initial: String, val usedMs: Long)

/** One day group: its kind plus the per-child lines that actually have usage. */
data class ActivityUsageDay(
    val dateKey: String,
    val kind: ActivityDayKind,
    val rows: List<ActivityUsageRow>,
)

/**
 * Groups the per-child usage into day groups, newest first, honouring the child filter
 * ([selectedChildId] `null` = all children).
 *
 * A day is included only when at least one visible child has *recorded* usage on it,
 * so a day the child did not use a controlled app is omitted rather than padded with a
 * misleading `0` row. `todayMs`/`pastDays` must already be non-null (this is only
 * called when usage is readable).
 */
fun activityUsageDays(
    usages: List<ActivityChildUsage>,
    selectedChildId: Long?,
    todayKey: String,
    yesterdayKey: String,
): List<ActivityUsageDay> {
    val visible = usages.filter { selectedChildId == null || it.childId == selectedChildId }
    if (visible.isEmpty()) return emptyList()

    val dateKeys = buildList {
        if (visible.any { (it.todayMs ?: 0L) > 0L }) add(todayKey)
        visible.flatMap { it.pastDays }.map { it.dateKey }.distinct().sortedDescending().forEach { add(it) }
    }

    return dateKeys.map { dateKey ->
        val rows = visible.mapNotNull { usage ->
            val usedMs = if (dateKey == todayKey) {
                usage.todayMs ?: 0L
            } else {
                usage.pastDays.firstOrNull { it.dateKey == dateKey }?.usedMs ?: 0L
            }
            if (usedMs <= 0L) {
                null
            } else {
                ActivityUsageRow(usage.childId, usage.name, usage.initial, usedMs)
            }
        }
        ActivityUsageDay(dateKey, activityDayKind(dateKey, todayKey, yesterdayKey), rows)
    }.filter { it.rows.isNotEmpty() }
}

/** The sum of every visible child's today usage, or `null` when usage is unavailable. */
fun activityScreenTimeTodayMs(usages: List<ActivityChildUsage>): Long? =
    if (usages.isEmpty() || usages.any { it.todayMs == null }) null else usages.sumOf { it.todayMs ?: 0L }

// ------------------------------------------------------------------ state

/** The Activity centre's lifecycle. */
enum class ActivityStatus { LOADING, READY, NO_ACCOUNT, ERROR }

/**
 * The whole Activity centre, as one immutable snapshot of real data.
 *
 * `events`/`requests` are account-scoped lists (the activity log and the request
 * store carry no per-child grouping for events), while `usages` is the child-scoped
 * screen-time history the child filter narrows.
 */
data class ActivityUiState(
    val status: ActivityStatus = ActivityStatus.LOADING,
    val children: List<ChildOverview> = emptyList(),
    val events: List<ActivityEventItem> = emptyList(),
    val requests: List<RequestRow> = emptyList(),
    val usages: List<ActivityChildUsage> = emptyList(),
    val metrics: List<ActivityMetric> = emptyList(),
    val usageAvailable: Boolean = false,
    val selectedChildId: Long? = null,
    val todayKey: String = "",
    val yesterdayKey: String = "",
) {
    val hasChildren: Boolean get() = children.isNotEmpty()
    val hasMultipleChildren: Boolean get() = children.size > 1
    val hasRequests: Boolean get() = requests.isNotEmpty()
    val hasEvents: Boolean get() = events.isNotEmpty()

    /** The day groups the screen-time history shows, honouring the child filter. */
    val usageDays: List<ActivityUsageDay>
        get() = activityUsageDays(usages, selectedChildId, todayKey, yesterdayKey)
}
