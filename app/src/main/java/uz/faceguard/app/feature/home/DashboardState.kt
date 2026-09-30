package uz.faceguard.app.feature.home

import uz.faceguard.app.R
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.domain.model.ActivityEvent
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleResolution

/**
 * Phase 10: the Parent Dashboard state and its pure domain -> presentation
 * mapping.
 *
 * Every value here comes from real application data (profiles, policies,
 * protected apps, activity events, runtime state). Nothing is estimated: usage
 * accounting does not exist yet, so the dashboard reports it as unavailable
 * instead of inventing numbers. The mapping functions are pure (no Android
 * framework), so the presentation decisions are unit-testable on the JVM.
 */

/** Top-level dashboard lifecycle. Empty states are modeled per section. */
enum class DashboardStatus { LOADING, READY, NO_ACCOUNT, ERROR }

/** Configured per-mode policy counts for the selected child. */
data class PolicyCounts(val allow: Int = 0, val limit: Int = 0, val block: Int = 0) {
    val total: Int get() = allow + limit + block
}

/**
 * A configured daily limit (real policy data). [dailyLimitMinutes] is the value
 * the parent configured; there is no usage counter in this phase, so no
 * remaining/used time is derived from it.
 */
data class ConfiguredLimit(val packageName: String, val dailyLimitMinutes: Int?)

/** One historical activity-log entry, as shown in the summary. */
data class EventSummary(val type: ActivityEventType, val detail: String?, val at: Long)

/**
 * Phase 6/10: the selected child's eye-safety **configuration**, presented as its
 * child-specific status. It is the parent's own configuration (not a live
 * per-frame observation), so the dashboard never claims a measurement it does not
 * have: [configured] false means "no configuration", and a configured-but-disabled
 * configuration is a distinct state.
 */
data class EyeSafetySection(
    val childId: Long,
    val configured: Boolean,
    val enabled: Boolean,
    val warningAction: ProtectionAction?,
    val dangerAction: ProtectionAction?,
)

/** Settings/identity of the selected child: the policy view of the dashboard. */
data class ChildSection(
    val childId: Long?,
    val name: String?,
    val faceEnrolled: Boolean,
    val level: RestrictionLevel?,
    val policies: PolicyCounts,
    val limits: List<ConfiguredLimit>,
    val policiesLoading: Boolean,
) {
    val exists: Boolean get() = childId != null
}

/** Immutable snapshot rendered by [HomeScreen]. */
data class DashboardUiState(
    val status: DashboardStatus = DashboardStatus.LOADING,
    val errorMessageRes: Int? = null,
    val selectedChildId: Long? = null,
    val children: List<ChildProfile> = emptyList(),
    val child: ChildSection? = null,
    val protectionEnabled: Boolean = false,
    val runtimeActive: Boolean = false,
    val protectionState: ProtectionState = ProtectionState.UNPROTECTED,
    val identity: UserIdentity? = null,
    val liveness: LivenessState? = null,
    val blockedApp: String? = null,
    /**
     * Phase 5/10: the *effective* schedule for the current protection context — the
     * recognised child plus the protected app in the foreground — exactly as the
     * runtime's engine already resolved it. The dashboard only renders it; it never
     * re-resolves a schedule of its own. [ScheduleResolution.NoActiveSchedule] means
     * no schedule currently applies (so protection is not schedule-affected).
     */
    val scheduleResolution: ScheduleResolution = ScheduleResolution.NoActiveSchedule,
    /**
     * Phase 6/10: the selected child's eye-safety configuration, or `null` while it
     * has not loaded. Never fabricated.
     */
    val eyeSafety: EyeSafetySection? = null,
    val overlayGranted: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val parentFaceEnrolled: Boolean = false,
    val protectedAppsCount: Int = 0,
    /**
     * Phase 11: durable pending requests for this account (real request rows, never
     * a count of protection events).
     */
    val pendingRequestCount: Int = 0,
    /** Phase 11: whether the OS would actually show our notifications. */
    val notificationsEnabled: Boolean = true,
    val recentEvents: List<EventSummary> = emptyList(),
) {
    val hasChild: Boolean get() = child?.exists == true
    val hasMultipleChildren: Boolean get() = children.size > 1

    /** True when every prerequisite for enforcement is currently satisfied. */
    val enforcementReady: Boolean
        get() = overlayGranted && usageAccessGranted && accessibilityEnabled

    /**
     * Phase 4 (screen time) is not implemented, so no genuine usage data can
     * exist. The dashboard must say so rather than show a made-up figure.
     */
    val usageAvailable: Boolean get() = false

    val hasPendingRequests: Boolean get() = pendingRequestCount > 0
}

// ---------------------------------------------------------------------------
// Pure presentation mapping (domain -> string resource)
// ---------------------------------------------------------------------------

fun identityLabelRes(identity: UserIdentity?): Int = when (identity) {
    UserIdentity.PARENT -> R.string.dashboard_identity_parent
    UserIdentity.CHILD -> R.string.dashboard_identity_child
    UserIdentity.UNKNOWN -> R.string.dashboard_identity_unknown
    UserIdentity.NO_FACE -> R.string.dashboard_identity_no_face
    UserIdentity.CAMERA_OBSTRUCTED -> R.string.dashboard_identity_obstructed
    null -> R.string.dashboard_identity_none
}

fun protectionStateLabelRes(state: ProtectionState): Int = when (state) {
    ProtectionState.UNPROTECTED -> R.string.protection_state_unprotected
    ProtectionState.SOFT_BLOCKED -> R.string.protection_state_soft
    ProtectionState.HARD_BLOCKED -> R.string.protection_state_hard
    ProtectionState.RECOVERING -> R.string.protection_state_recovering
}

/**
 * Only states the parent can act on are surfaced. `UNKNOWN`/`NO_FACE`/`UNSTABLE`
 * are not shown as a "liveness" verdict (they are already represented by the
 * identity line), and no internal probability is ever exposed.
 */
fun livenessLabelRes(liveness: LivenessState?): Int? = when (liveness) {
    LivenessState.LIVE -> R.string.dashboard_liveness_live
    LivenessState.SPOOF -> R.string.dashboard_liveness_spoof
    else -> null
}

fun policyModeLabelRes(mode: AppPolicyMode): Int = when (mode) {
    AppPolicyMode.ALLOW -> R.string.policy_allow
    AppPolicyMode.LIMIT -> R.string.child_policy_mode_limit
    AppPolicyMode.BLOCK -> R.string.policy_hard_block
}

fun restrictionLevelLabelRes(level: RestrictionLevel?): Int = when (level) {
    RestrictionLevel.LOW -> R.string.level_low
    RestrictionLevel.MEDIUM -> R.string.level_medium
    RestrictionLevel.HIGH -> R.string.level_high
    null -> R.string.dashboard_value_unknown
}

/** Phase 5/10: the descriptive mode of a schedule, reusing the existing labels. */
fun scheduleModeLabelRes(mode: ScheduleMode): Int = when (mode) {
    ScheduleMode.NORMAL -> R.string.schedule_mode_normal
    ScheduleMode.STUDY -> R.string.schedule_mode_study
    ScheduleMode.SLEEP -> R.string.schedule_mode_sleep
    ScheduleMode.SCHOOL -> R.string.schedule_mode_school
    ScheduleMode.CUSTOM -> R.string.schedule_mode_custom
}

/**
 * Phase 6/10: an eye-safety action label, reusing the eye-safety editor's own
 * action labels so the dashboard and the editor can never describe the same action
 * differently. `DIM`/`BLUR`/`BLACK_SCREEN` have no eye-safety meaning in this build
 * and are reported as unknown rather than mislabelled.
 */
fun eyeSafetyActionLabelRes(action: ProtectionAction): Int = when (action) {
    ProtectionAction.ALLOW -> R.string.eye_safety_action_allow
    ProtectionAction.WARNING -> R.string.eye_safety_action_warning
    ProtectionAction.SOFT_BLOCK -> R.string.eye_safety_action_soft_block
    ProtectionAction.HARD_BLOCK -> R.string.eye_safety_action_hard_block
    ProtectionAction.MUTE -> R.string.eye_safety_action_mute
    ProtectionAction.DIM,
    ProtectionAction.BLUR,
    ProtectionAction.BLACK_SCREEN,
    -> R.string.dashboard_value_unknown
}

fun eventLabelRes(type: ActivityEventType): Int = when (type) {
    ActivityEventType.CHILD_RECOGNIZED -> R.string.activity_child_recognized
    ActivityEventType.PARENT_RECOGNIZED -> R.string.activity_parent_recognized
    ActivityEventType.UNKNOWN_USER -> R.string.activity_unknown_user
    ActivityEventType.NO_FACE -> R.string.activity_no_face
    ActivityEventType.PROTECTED_APP_ENTERED -> R.string.activity_protected_app_entered
    ActivityEventType.CHILD_BLOCKED -> R.string.activity_child_blocked
    ActivityEventType.PROTECTION_RELEASED -> R.string.activity_protection_released
    ActivityEventType.PARENT_UNLOCKED -> R.string.activity_parent_unlocked
    ActivityEventType.EMERGENCY_UNLOCK -> R.string.activity_emergency_unlock
    ActivityEventType.SCHEDULE_CHANGED -> R.string.activity_schedule_changed
}

// ---------------------------------------------------------------------------
// Pure aggregation helpers
// ---------------------------------------------------------------------------

fun policyCounts(policies: List<AppPolicy>): PolicyCounts = PolicyCounts(
    allow = policies.count { it.mode == AppPolicyMode.ALLOW },
    limit = policies.count { it.mode == AppPolicyMode.LIMIT },
    block = policies.count { it.mode == AppPolicyMode.BLOCK },
)

fun configuredLimits(policies: List<AppPolicy>): List<ConfiguredLimit> =
    policies.filter { it.mode == AppPolicyMode.LIMIT }
        .map { ConfiguredLimit(it.packageName, it.dailyLimitMinutes) }
        .sortedBy { it.packageName }

/** Bounded, newest-first summary (the repository already returns newest first). */
fun recentEventSummaries(events: List<ActivityEvent>, limit: Int = RECENT_EVENT_LIMIT): List<EventSummary> =
    events.take(limit).map { EventSummary(type = it.type, detail = it.detail, at = it.at) }

/**
 * Which child the dashboard shows.
 *
 * The current selection wins while it still exists; otherwise the preferred
 * (e.g. navigation) child; otherwise the first child. A selection that belongs
 * to another account can never match, so it degrades safely.
 */
fun resolveSelectedChild(
    children: List<ChildProfile>,
    current: Long?,
    preferred: Long? = null,
): Long? = when {
    current != null && children.any { it.id == current } -> current
    preferred != null && children.any { it.id == preferred } -> preferred
    else -> children.firstOrNull()?.id
}

/**
 * True when a child-scoped snapshot belongs to the currently selected child.
 * Used to keep a previous child's policies off screen while the new child's data
 * is still loading.
 */
fun isChildScopedCurrent(loadedChildId: Long?, selectedChildId: Long?): Boolean =
    loadedChildId != null && loadedChildId == selectedChildId

/** Recent events requested for the summary. */
const val RECENT_EVENT_LIMIT = 5
