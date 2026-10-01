package uz.faceguard.app.feature.child

import androidx.annotation.StringRes
import uz.faceguard.app.R
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.ui.qalqon.QalqonAlertSeverity
import uz.faceguard.app.feature.home.HomeProtectionStatus

/**
 * UI/UX redesign, Phase 4: the Child Detail hub's presentation mapping.
 *
 * Pure — the hub's controls, status and attention are computed from values the
 * existing repositories/runtime already produced, never re-derived here. The
 * protection status deliberately reuses the Home redesign's single vocabulary
 * ([HomeProtectionStatus] and its label/tone mapping) instead of defining a parallel
 * status enum; [childProtectionStatus] only adapts the same inputs, and a test pins
 * that it agrees with `homeProtectionStatus` for every combination.
 */

// ------------------------------------------------------------------ status

/**
 * The protection verdict for the child's account, from the same inputs Home uses:
 * a live block outranks everything, then recovery, then a missing prerequisite.
 */
fun childProtectionStatus(
    protectionEnabled: Boolean,
    protectionState: ProtectionState,
    enforcementReady: Boolean,
): HomeProtectionStatus = when {
    !protectionEnabled -> HomeProtectionStatus.OFF
    protectionState == ProtectionState.SOFT_BLOCKED ||
        protectionState == ProtectionState.HARD_BLOCKED -> HomeProtectionStatus.BLOCKING
    protectionState == ProtectionState.RECOVERING -> HomeProtectionStatus.RECOVERING
    !enforcementReady -> HomeProtectionStatus.SETUP_REQUIRED
    else -> HomeProtectionStatus.ACTIVE
}

// --------------------------------------------------------------- controls

/** The child-scoped destinations the Child Detail hub links to, in reading order. */
enum class ChildDetailControl { APPS, SCREEN_TIME, SCHEDULE, EYE_SAFETY, FACE, REQUESTS }

@StringRes
fun childControlLabelRes(control: ChildDetailControl): Int = when (control) {
    ChildDetailControl.APPS -> R.string.child_detail_apps
    ChildDetailControl.SCREEN_TIME -> R.string.child_detail_screen_time
    ChildDetailControl.SCHEDULE -> R.string.child_detail_schedule
    ChildDetailControl.EYE_SAFETY -> R.string.child_detail_eye_safety
    ChildDetailControl.FACE -> R.string.child_detail_face
    ChildDetailControl.REQUESTS -> R.string.child_detail_requests
}

@StringRes
fun childControlDescriptionRes(control: ChildDetailControl): Int = when (control) {
    ChildDetailControl.APPS -> R.string.child_detail_apps_desc
    ChildDetailControl.SCREEN_TIME -> R.string.child_detail_screen_time_desc
    ChildDetailControl.SCHEDULE -> R.string.child_detail_schedule_desc
    ChildDetailControl.EYE_SAFETY -> R.string.child_detail_eye_safety_desc
    ChildDetailControl.FACE -> R.string.child_detail_face_desc
    ChildDetailControl.REQUESTS -> R.string.child_detail_requests_desc
}

/** Every control the hub shows, in order — the single definition of the hub. */
val CHILD_DETAIL_CONTROLS: List<ChildDetailControl> = ChildDetailControl.entries.toList()

// -------------------------------------------------------------- attention

/** The real, actionable conditions Child Detail may surface for one child. */
enum class ChildAttentionKind { PROTECTION_SETUP_REQUIRED, FACE_NOT_ENROLLED }

data class ChildAttentionItem(
    val kind: ChildAttentionKind,
    @StringRes val messageRes: Int,
    val severity: QalqonAlertSeverity,
)

/**
 * Child-scoped attention items, most urgent first, or an empty list when there is
 * nothing to act on (the section is then hidden — never an empty "all good" card).
 * Each condition comes from a real field; nothing is invented.
 */
fun childAttentionItems(
    protectionStatus: HomeProtectionStatus,
    faceEnrolled: Boolean,
): List<ChildAttentionItem> = buildList {
    if (protectionStatus == HomeProtectionStatus.SETUP_REQUIRED) {
        add(
            ChildAttentionItem(
                kind = ChildAttentionKind.PROTECTION_SETUP_REQUIRED,
                messageRes = R.string.home_status_setup_hint,
                severity = QalqonAlertSeverity.WARNING,
            ),
        )
    }
    if (!faceEnrolled) {
        add(
            ChildAttentionItem(
                kind = ChildAttentionKind.FACE_NOT_ENROLLED,
                messageRes = R.string.children_face_off,
                severity = QalqonAlertSeverity.WARNING,
            ),
        )
    }
}
