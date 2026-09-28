package uz.faceguard.app.domain.schedule

/**
 * Phase 5 Step 6: the stable identity of an *effective* schedule state.
 *
 * Transition detection must never depend on object identity, list ordering or database row order,
 * so it compares this rather than a [ScheduleResolution] directly. Two identities are equal exactly
 * when they describe the same effective state, which makes consecutive engine ticks that observe
 * the same schedule produce no transition at all.
 *
 * Identity is by persisted id, per the approved contract:
 *  - [None] — no schedule is effective; there is nothing to identify.
 *  - [Active] — one schedule, identified by its persisted id. A schedule whose display fields were
 *    edited is still the same logical schedule, so editing an active schedule is not a transition.
 *  - [Conflict] — the tied schedules, identified by their sorted id set. Sorting is what makes the
 *    same conflict independent of the order the resolver listed (or the caller supplied) them in.
 *
 * The owning child is part of the identity, so one child's state can never be mistaken for, or
 * suppress an event for, another's.
 */
sealed interface ScheduleStateIdentity {

    data object None : ScheduleStateIdentity

    data class Active(val childId: Long, val scheduleId: Long) : ScheduleStateIdentity

    data class Conflict(val childId: Long, val scheduleIds: List<Long>) : ScheduleStateIdentity

    companion object {
        /**
         * Derives the identity of [resolution] for [childId].
         *
         * A resolution without a child cannot be a child's effective schedule, so it collapses to
         * [None]; that is also what makes a recognised parent (or an unknown/no-face user, which
         * carry no child id) report no effective schedule rather than a child's.
         */
        fun of(resolution: ScheduleResolution, childId: Long?): ScheduleStateIdentity = when (resolution) {
            ScheduleResolution.NoActiveSchedule -> None

            is ScheduleResolution.ActiveSchedule ->
                if (childId == null) None else Active(childId, resolution.schedule.id)

            is ScheduleResolution.ScheduleConflict -> {
                val ids = resolution.schedules.map { it.id }.sorted()
                if (childId == null || ids.isEmpty()) None else Conflict(childId, ids)
            }
        }
    }
}

/** One effective schedule state changing into another. */
data class ScheduleTransition(
    val from: ScheduleStateIdentity,
    val to: ScheduleStateIdentity,
)

/**
 * The effective schedule identity for a policy context.
 *
 * A schedule only ever applies to a recognised child who is currently in a protected app: the
 * approved contract says an unprotected package must never be made protected by a schedule, and a
 * recognised parent (or an unknown/no-face user) has no child scope at all. Both conditions are
 * folded in here, so "is this resolution effective?" has exactly one definition and the engine
 * does not have to spell the gates out at each call site.
 */
fun effectiveScheduleState(
    resolution: ScheduleResolution,
    childId: Long?,
    isProtectedApp: Boolean,
): ScheduleStateIdentity =
    if (!isProtectedApp) ScheduleStateIdentity.None
    else ScheduleStateIdentity.of(resolution, childId)

/**
 * Phase 5 Step 6: reports the change between consecutive effective schedule states, exactly once.
 *
 * The tracker is the whole transition rule: an observation equal to the previous one is not a
 * transition (so the ~500ms engine tick cannot produce duplicate events), and any difference is
 * reported once and becomes the new baseline.
 *
 * It is intentionally stateful and lives with the engine that observes the schedule, not with a
 * ViewModel or a screen, so merely recreating UI cannot fabricate a transition.
 */
class ScheduleTransitionTracker {

    /**
     * The last observed state. Starts at [ScheduleStateIdentity.None] because "no schedule is
     * effective" is a real effective state — so the first observation of an active schedule is a
     * genuine activation rather than an unknown baseline.
     */
    private var previous: ScheduleStateIdentity = ScheduleStateIdentity.None

    val current: ScheduleStateIdentity get() = previous

    /**
     * Records [state] and returns the transition it represents, or `null` when the effective state
     * is unchanged (in which case nothing may be logged).
     */
    fun onState(state: ScheduleStateIdentity): ScheduleTransition? {
        if (state == previous) return null
        val transition = ScheduleTransition(from = previous, to = state)
        previous = state
        return transition
    }

    /**
     * The transition for one observation of [resolution], applying the effectiveness gates in
     * [effectiveScheduleState]. This is the shape the engine observes with, so it is also what the
     * tests drive.
     */
    fun onResolution(
        resolution: ScheduleResolution,
        childId: Long?,
        isProtectedApp: Boolean,
    ): ScheduleTransition? = onState(effectiveScheduleState(resolution, childId, isProtectedApp))

    /** Drops the baseline, so the next observation is evaluated afresh. */
    fun reset() {
        previous = ScheduleStateIdentity.None
    }
}

/**
 * The existing activity-log `detail` for a schedule transition.
 *
 * The activity-event table has no child or schedule column — and adding one is not warranted for a
 * log line — so the scope and identity are carried in the existing detail field, as a short
 * technical string rather than a serialized object. It names the owning child and the schedule
 * id(s) involved, which is what makes two log lines distinguishable.
 */
fun scheduleEventDetail(identity: ScheduleStateIdentity): String = when (identity) {
    ScheduleStateIdentity.None -> "none"
    is ScheduleStateIdentity.Active -> "child ${identity.childId} / schedule ${identity.scheduleId}"
    is ScheduleStateIdentity.Conflict ->
        "child ${identity.childId} / conflict ${identity.scheduleIds.joinToString("+")}"
}
