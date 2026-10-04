package uz.faceguard.app.feature.home

import androidx.annotation.StringRes
import uz.faceguard.app.R
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.ui.qalqon.QalqonAlertSeverity
import uz.faceguard.app.core.ui.qalqon.QalqonStatusTone
import uz.faceguard.app.core.util.Validation
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.schedule.ScheduleResolution

/**
 * UI/UX redesign, Phase 3: the Home dashboard's presentation mapping.
 *
 * This is the layer between the existing data ([DashboardUiState], the screen-time
 * summary and the runtime state the aggregator already produced) and the Compose
 * dashboard. It contains **no business logic**: it never queries Room/DataStore,
 * never reads a permission, never subtracts usage from a limit and never decides
 * whether a child is blocked. It only *selects* which existing value maps to which
 * localized label and semantic tone.
 *
 * Everything here is pure (no Android framework, only resource ids), so the
 * dashboard's information architecture is directly unit-testable on the JVM.
 */

// ------------------------------------------------------------- greeting name

/**
 * The name the Home greeting may use, or `null` when it must fall back to a neutral
 * greeting.
 *
 * A registered name is shown verbatim; a blank name, or a value that is actually a
 * phone number (a data shape that must never be presented as a person's name), yields
 * `null` so the header shows the plain greeting instead of a phone number. Pure.
 */
fun homeGreetingName(displayName: String?): String? {
    val trimmed = displayName?.trim().orEmpty()
    return trimmed.takeIf { it.isNotEmpty() && !Validation.isPhoneLike(it) }
}

// ---------------------------------------------------------------- protection

/**
 * The dashboard's protection verdict. Derived entirely from existing runtime state
 * ([DashboardUiState.protectionEnabled], [DashboardUiState.protectionState],
 * [DashboardUiState.enforcementReady]); no new protection state is invented.
 *
 * Order matters: a live block wins over "capability missing", because a blocked
 * app is the more urgent fact to show.
 */
enum class HomeProtectionStatus { OFF, ACTIVE, BLOCKING, RECOVERING, SETUP_REQUIRED }

fun homeProtectionStatus(state: DashboardUiState): HomeProtectionStatus = when {
    !state.protectionEnabled -> HomeProtectionStatus.OFF
    state.protectionState == ProtectionState.SOFT_BLOCKED ||
        state.protectionState == ProtectionState.HARD_BLOCKED -> HomeProtectionStatus.BLOCKING
    state.protectionState == ProtectionState.RECOVERING -> HomeProtectionStatus.RECOVERING
    !state.enforcementReady -> HomeProtectionStatus.SETUP_REQUIRED
    else -> HomeProtectionStatus.ACTIVE
}

@StringRes
fun homeProtectionLabelRes(status: HomeProtectionStatus): Int = when (status) {
    HomeProtectionStatus.OFF -> R.string.dashboard_protection_off
    HomeProtectionStatus.ACTIVE -> R.string.dashboard_protection_on
    HomeProtectionStatus.BLOCKING -> R.string.home_status_blocking
    HomeProtectionStatus.RECOVERING -> R.string.protection_state_recovering
    HomeProtectionStatus.SETUP_REQUIRED -> R.string.dashboard_capability_missing
}

@StringRes
fun homeProtectionSupportingRes(status: HomeProtectionStatus): Int = when (status) {
    HomeProtectionStatus.OFF -> R.string.home_status_off_hint
    HomeProtectionStatus.ACTIVE -> R.string.home_status_active_hint
    HomeProtectionStatus.BLOCKING -> R.string.home_status_blocking_hint
    HomeProtectionStatus.RECOVERING -> R.string.home_status_recovering_hint
    HomeProtectionStatus.SETUP_REQUIRED -> R.string.home_status_setup_hint
}

/**
 * The label of the protection card's single primary action. The states the parent
 * must act on ("off", "setup required") offer the existing setup destination; a
 * running session instead offers the existing management screen.
 */
@StringRes
fun homeProtectionActionLabelRes(status: HomeProtectionStatus): Int = when (status) {
    HomeProtectionStatus.OFF, HomeProtectionStatus.SETUP_REQUIRED -> R.string.dashboard_capability_open
    else -> R.string.home_protection_manage
}

fun homeProtectionTone(status: HomeProtectionStatus): QalqonStatusTone = when (status) {
    HomeProtectionStatus.ACTIVE -> QalqonStatusTone.ACTIVE
    // OFF is a warning, not a neutral: the device is unprotected and the parent must act.
    HomeProtectionStatus.OFF -> QalqonStatusTone.WARNING
    HomeProtectionStatus.BLOCKING -> QalqonStatusTone.BLOCKING
    HomeProtectionStatus.RECOVERING -> QalqonStatusTone.WARNING
    HomeProtectionStatus.SETUP_REQUIRED -> QalqonStatusTone.WARNING
}

/**
 * The semantic surface treatment of the protection hero (Compose-free).
 *
 * ON is green, OFF/setup/recovering are amber, a live block is red. The Compose layer
 * maps this to theme tokens and animates between them; keeping the decision here makes
 * the state -> colour contract unit-testable without Android.
 */
enum class HomeHeroSurface { SUCCESS, WARNING, BLOCKING }

fun homeProtectionHeroSurface(status: HomeProtectionStatus): HomeHeroSurface = when (status) {
    HomeProtectionStatus.ACTIVE -> HomeHeroSurface.SUCCESS
    HomeProtectionStatus.BLOCKING -> HomeHeroSurface.BLOCKING
    HomeProtectionStatus.OFF,
    HomeProtectionStatus.SETUP_REQUIRED,
    HomeProtectionStatus.RECOVERING,
    -> HomeHeroSurface.WARNING
}

// ----------------------------------------------------------------- attention

/** The real, actionable conditions the attention section may surface. */
enum class HomeAttentionKind { PENDING_REQUESTS, CHILDREN_NEED_SETUP, NOTIFICATIONS_DISABLED }

data class HomeAttentionItem(
    val kind: HomeAttentionKind,
    @StringRes val messageRes: Int,
    val severity: QalqonAlertSeverity,
    /** Only set for counted conditions (pending requests); never fabricated. */
    val count: Int? = null,
)

/** The attention section is compact by design: at most this many items. */
const val HOME_ATTENTION_MAX = 3

/**
 * The attention items to show, in priority order, or an empty list when the
 * dashboard is calm (the section is then hidden entirely — never an empty card).
 *
 * Every condition comes from a real field; nothing is invented, and a count is only
 * attached when the underlying value is a genuine count.
 */
fun homeAttentionItems(state: DashboardUiState): List<HomeAttentionItem> = buildList {
    if (state.pendingRequestCount > 0) {
        add(
            HomeAttentionItem(
                kind = HomeAttentionKind.PENDING_REQUESTS,
                messageRes = R.string.dashboard_requests_pending,
                severity = QalqonAlertSeverity.WARNING,
                count = state.pendingRequestCount,
            ),
        )
    }
    if (state.children.any { !it.isFaceEnrolled }) {
        add(
            HomeAttentionItem(
                kind = HomeAttentionKind.CHILDREN_NEED_SETUP,
                messageRes = R.string.home_attention_children_setup,
                severity = QalqonAlertSeverity.WARNING,
            ),
        )
    }
    if (!state.notificationsEnabled) {
        add(
            HomeAttentionItem(
                kind = HomeAttentionKind.NOTIFICATIONS_DISABLED,
                messageRes = R.string.dashboard_notifications_disabled,
                severity = QalqonAlertSeverity.WARNING,
            ),
        )
    }
}.take(HOME_ATTENTION_MAX)

// ------------------------------------------------------------------ children

/** One row of the Home children overview. Presentation only; all values are real. */
data class HomeChildSummary(
    val childId: Long,
    val name: String,
    val initial: String,
    val faceEnrolled: Boolean,
    val level: RestrictionLevel?,
    val needsSetup: Boolean,
)

/**
 * The children overview, in the order the repository already returned them. It is an
 * overview, not a management surface: it exposes only what the profile itself
 * carries, and never a per-child figure the dashboard does not actually have.
 */
fun homeChildSummaries(state: DashboardUiState): List<HomeChildSummary> =
    state.children.map { child ->
        HomeChildSummary(
            childId = child.id,
            name = child.childName,
            initial = child.childName.trim().firstOrNull()?.uppercase() ?: "?",
            faceEnrolled = child.isFaceEnrolled,
            level = child.restrictionLevel,
            needsSetup = !child.isFaceEnrolled,
        )
    }

// --------------------------------------------------------------------- today

enum class HomeTodayMetricKind { SCREEN_TIME, SCHEDULE, EYE_SAFETY, PROTECTION }

/**
 * A metric's value. [Duration] carries exact milliseconds and is rendered with the
 * one existing duration formatter, so the dashboard can never format a duration
 * differently from the rest of the app. [Text] is a localized resource, optionally
 * formatted with one already-localized/generated argument (a schedule name, a child
 * name — user data, never UI copy).
 */
sealed interface HomeMetricValue {
    data class Text(@StringRes val res: Int, val arg: String? = null) : HomeMetricValue
    data class Duration(val ms: Long) : HomeMetricValue
}

/**
 * One compact "Today" metric. [caption] carries dynamic user data (a child or
 * schedule name) so a child-scoped figure is never shown without saying whose it is.
 */
data class HomeTodayMetric(
    val kind: HomeTodayMetricKind,
    @StringRes val labelRes: Int,
    val value: HomeMetricValue,
    val caption: String? = null,
    val tone: QalqonStatusTone? = null,
)

/**
 * The "Today" overview: screen time, schedule, eye safety and protection, using only
 * data the existing aggregator/evaluator already produced.
 *
 * A metric with no reliable data is rendered as its own honest state ("usage data
 * unavailable", "no screen-time child selected") — never as a fabricated zero.
 */
fun homeTodayMetrics(
    state: DashboardUiState,
    screenTime: ScreenTimeSummaryUiState,
): List<HomeTodayMetric> = buildList {
    screenTimeMetric(screenTime)?.let { add(it) }
    if (state.hasChild) add(scheduleMetric(state.scheduleResolution))
    state.eyeSafety?.let { add(eyeSafetyMetric(it, state.child?.name)) }
    add(protectionMetric(state))
}

private fun screenTimeMetric(summary: ScreenTimeSummaryUiState): HomeTodayMetric? {
    val label = R.string.screentime_summary_today
    return when (summary.status) {
        // Nothing to say before the account/target is known; the section simply omits it.
        ScreenTimeSummaryStatus.LOADING,
        ScreenTimeSummaryStatus.NO_ACCOUNT,
        -> null

        ScreenTimeSummaryStatus.NO_TARGET -> HomeTodayMetric(
            kind = HomeTodayMetricKind.SCREEN_TIME,
            labelRes = label,
            value = HomeMetricValue.Text(R.string.screentime_summary_no_target),
            tone = QalqonStatusTone.INACTIVE,
        )

        ScreenTimeSummaryStatus.USAGE_UNAVAILABLE -> HomeTodayMetric(
            kind = HomeTodayMetricKind.SCREEN_TIME,
            labelRes = label,
            value = HomeMetricValue.Text(R.string.screentime_summary_unavailable),
            tone = QalqonStatusTone.WARNING,
        )

        ScreenTimeSummaryStatus.ERROR -> HomeTodayMetric(
            kind = HomeTodayMetricKind.SCREEN_TIME,
            labelRes = label,
            value = HomeMetricValue.Text(R.string.screentime_summary_error),
            tone = QalqonStatusTone.WARNING,
        )

        ScreenTimeSummaryStatus.READY -> {
            val total = summary.total
            if (total == null) {
                HomeTodayMetric(
                    kind = HomeTodayMetricKind.SCREEN_TIME,
                    labelRes = label,
                    value = HomeMetricValue.Text(R.string.screentime_summary_unavailable),
                    tone = QalqonStatusTone.WARNING,
                )
            } else {
                HomeTodayMetric(
                    kind = HomeTodayMetricKind.SCREEN_TIME,
                    labelRes = label,
                    value = HomeMetricValue.Duration(total.usedMs),
                    caption = summary.childName,
                    tone = if (total.exceeded) QalqonStatusTone.WARNING else QalqonStatusTone.ACTIVE,
                )
            }
        }
    }
}

private fun scheduleMetric(resolution: ScheduleResolution): HomeTodayMetric {
    val label = R.string.schedule_title
    return when (resolution) {
        is ScheduleResolution.NoActiveSchedule -> HomeTodayMetric(
            kind = HomeTodayMetricKind.SCHEDULE,
            labelRes = label,
            value = HomeMetricValue.Text(R.string.dashboard_schedule_none),
            tone = QalqonStatusTone.INACTIVE,
        )

        is ScheduleResolution.ActiveSchedule -> HomeTodayMetric(
            kind = HomeTodayMetricKind.SCHEDULE,
            labelRes = label,
            value = HomeMetricValue.Text(scheduleModeLabelRes(resolution.schedule.mode)),
            caption = resolution.schedule.name,
            tone = QalqonStatusTone.ACTIVE,
        )

        is ScheduleResolution.ScheduleConflict -> HomeTodayMetric(
            kind = HomeTodayMetricKind.SCHEDULE,
            labelRes = label,
            value = HomeMetricValue.Text(
                R.string.dashboard_schedule_conflict,
                resolution.schedules.joinToString(", ") { it.name },
            ),
            tone = QalqonStatusTone.WARNING,
        )
    }
}

private fun eyeSafetyMetric(section: EyeSafetySection, childName: String?): HomeTodayMetric {
    val label = R.string.eye_safety_title
    val value = when {
        !section.configured -> R.string.eye_safety_status_not_configured to QalqonStatusTone.INACTIVE
        !section.enabled -> R.string.eye_safety_status_disabled to QalqonStatusTone.WARNING
        else -> R.string.eye_safety_status_enabled to QalqonStatusTone.ACTIVE
    }
    return HomeTodayMetric(
        kind = HomeTodayMetricKind.EYE_SAFETY,
        labelRes = label,
        value = HomeMetricValue.Text(value.first),
        caption = childName,
        tone = value.second,
    )
}

private fun protectionMetric(state: DashboardUiState): HomeTodayMetric {
    val status = homeProtectionStatus(state)
    return HomeTodayMetric(
        kind = HomeTodayMetricKind.PROTECTION,
        labelRes = R.string.dashboard_protection_title,
        value = HomeMetricValue.Text(homeProtectionLabelRes(status)),
        tone = homeProtectionTone(status),
    )
}

// -------------------------------------------------------------- quick actions

enum class HomeQuickAction { MANAGE_CHILDREN, PROTECTION_SETTINGS, PROTECTED_APPS, REVIEW_REQUESTS }

/** The quick-actions section never shows more than this many actions. */
const val HOME_QUICK_ACTIONS_MAX = 3

@StringRes
fun homeQuickActionLabelRes(action: HomeQuickAction): Int = when (action) {
    HomeQuickAction.MANAGE_CHILDREN -> R.string.home_action_manage_children
    HomeQuickAction.PROTECTION_SETTINGS -> R.string.protection_title
    HomeQuickAction.PROTECTED_APPS -> R.string.home_menu_protected_apps
    HomeQuickAction.REVIEW_REQUESTS -> R.string.requests_title
}

/** The one-line supporting description under each quick action's title. */
@StringRes
fun homeQuickActionDescriptionRes(action: HomeQuickAction): Int = when (action) {
    HomeQuickAction.MANAGE_CHILDREN -> R.string.home_action_manage_children_desc
    HomeQuickAction.PROTECTION_SETTINGS -> R.string.home_action_protection_desc
    HomeQuickAction.PROTECTED_APPS -> R.string.home_action_protected_apps_desc
    HomeQuickAction.REVIEW_REQUESTS -> R.string.home_action_requests_desc
}

/**
 * The contextual quick actions: at most [HOME_QUICK_ACTIONS_MAX], chosen from the
 * existing state rather than always showing every option. Every action maps to an
 * existing, protected route.
 */
fun homeQuickActions(state: DashboardUiState): List<HomeQuickAction> = buildList {
    if (state.pendingRequestCount > 0) add(HomeQuickAction.REVIEW_REQUESTS)
    if (state.children.isEmpty() || state.children.any { !it.isFaceEnrolled }) {
        add(HomeQuickAction.MANAGE_CHILDREN)
    }
    if (state.protectionEnabled && !state.enforcementReady) add(HomeQuickAction.PROTECTION_SETTINGS)
    if (state.protectedAppsCount == 0) add(HomeQuickAction.PROTECTED_APPS)

    // Nothing needs attention: offer the three everyday management shortcuts.
    if (isEmpty()) {
        add(HomeQuickAction.MANAGE_CHILDREN)
        add(HomeQuickAction.PROTECTION_SETTINGS)
        add(HomeQuickAction.PROTECTED_APPS)
    }
}.distinct().take(HOME_QUICK_ACTIONS_MAX)
