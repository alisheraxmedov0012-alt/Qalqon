package uz.faceguard.app.feature.home

import uz.faceguard.app.R
import uz.faceguard.app.domain.model.ChildProfile

/**
 * Phase 4 Step 1B-8: the dashboard's "which child is this device's screen-time target?"
 * state.
 *
 * This is a *configuration* question — whose usage the background collector attributes the
 * device's screen time to — and deliberately not face-recognition identity ("who is using
 * the phone right now"). The two are different products, and the UI language must keep them
 * apart so a parent never reads this as recognition.
 *
 * There is no default: [activeChildId] stays `null` until the parent chooses, and nothing
 * here falls back to a first or otherwise "obvious" child. That preserves the Step 1B-7
 * contract, where the collector refuses to attribute usage without an explicit target.
 */
data class ScreenTimeTargetUiState(
    val status: ScreenTimeTargetStatus = ScreenTimeTargetStatus.LOADING,
    val children: List<ChildProfile> = emptyList(),
    val activeChildId: Long? = null,
    /** True while a selection is being written, so the control can prevent double taps. */
    val saving: Boolean = false,
    /** Set when reading or persisting the selection failed; no guessed value is stored. */
    val errorMessageRes: Int? = null,
) {

    /** No child profiles exist yet, so there is nothing to choose. */
    val noChildren: Boolean get() = status == ScreenTimeTargetStatus.READY && children.isEmpty()

    /** Children exist but the parent has not chosen a screen-time target yet. */
    val needsSelection: Boolean
        get() = status == ScreenTimeTargetStatus.READY && children.isNotEmpty() && activeChildId == null

    /** The chosen child, or `null` when none is selected or it no longer exists. */
    val activeChild: ChildProfile? get() = children.firstOrNull { it.id == activeChildId }

    /**
     * The stored id no longer resolves to a child of this account (for example it was
     * deleted on another screen). It must read as "nothing selected" rather than silently
     * pointing at a child that is gone.
     */
    val activeChildMissing: Boolean
        get() = activeChildId != null && children.none { it.id == activeChildId }
}

/** Lifecycle of the screen-time target section. */
enum class ScreenTimeTargetStatus { LOADING, READY, NO_ACCOUNT, ERROR }

/** Error shown when the selection could not be persisted. */
val SCREEN_TIME_TARGET_SAVE_ERROR_RES: Int = R.string.screentime_target_error_save
