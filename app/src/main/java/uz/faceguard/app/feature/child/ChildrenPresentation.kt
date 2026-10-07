package uz.faceguard.app.feature.child

import androidx.annotation.StringRes
import uz.faceguard.app.R
import uz.faceguard.app.core.ui.qalqon.QalqonStatusTone
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.RestrictionLevel

/**
 * UI/UX redesign, Phase 4: the Children list's presentation mapping.
 *
 * Pure (no Android framework), so the overview each card renders is unit-testable on
 * the JVM. It is a view of the existing profile data only — no repository access, no
 * business logic and no fabricated value: a field the profile does not carry is
 * simply absent.
 */

/** One row of the Children list. Every field is real profile data. */
data class ChildOverview(
    val childId: Long,
    val name: String,
    val initial: String,
    val faceEnrolled: Boolean,
    val level: RestrictionLevel?,
    /**
     * Today's screen-time facts for this child, or `null` when they could not be read —
     * an unknown value is left absent rather than shown as `0 min`.
     */
    val screenTime: ChildScreenTime? = null,
) {
    /** True when the child still needs face setup before protection can work. */
    val needsSetup: Boolean get() = !faceEnrolled

    /** The card's status badge, derived from real fields only. */
    val status: ChildCardStatus get() = childCardStatus(faceEnrolled, screenTime)
}

/**
 * Today's screen-time facts for one child, copied verbatim from the existing
 * [uz.faceguard.app.domain.screentime.ScreenTimeLimitEvaluation].
 *
 * The arithmetic is not repeated here: [progress] only rescales the limit/usage the
 * evaluator already compared, and [exceeded] is what the evaluator decided (the
 * `used >= limit` boundary lives in one place, the domain).
 */
data class ChildScreenTime(
    val usedMs: Long,
    /** The configured daily limit in minutes, or `null` when unlimited/invalid. */
    val limitMinutes: Int?,
    /** True when the evaluator reported the daily limit reached. */
    val exceeded: Boolean,
    /** True when the stored limit was not a valid configuration; shown, never hidden. */
    val invalidLimit: Boolean = false,
) {
    /** True when there is a valid limit to draw progress against. */
    val hasLimit: Boolean get() = limitMinutes != null && !invalidLimit

    /** Fraction of the daily limit used, clamped to `0f..1f`; `null` when there is no limit. */
    val progress: Float?
        get() {
            if (invalidLimit) return null
            val limit = limitMinutes ?: return null
            // A zero-minute limit is a real configuration meaning "already used up".
            if (limit <= 0) return 1f
            return (usedMs.toFloat() / (limit.toLong() * MS_PER_MINUTE).toFloat()).coerceIn(0f, 1f)
        }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}

/** The three states the Children list badge can show. */
enum class ChildCardStatus { ACTIVE, TIME_UP, OFFLINE }

/**
 * Maps real child facts to the card's badge.
 *
 * A reached screen-time limit outranks everything — the child is out of time even when
 * enrollment is complete. Otherwise an enrolled child is [ChildCardStatus.ACTIVE], and a
 * child without enrollment is [ChildCardStatus.OFFLINE] because protection cannot run
 * for them yet. Nothing here is invented: both inputs are real profile/screen-time data.
 */
fun childCardStatus(faceEnrolled: Boolean, screenTime: ChildScreenTime?): ChildCardStatus = when {
    screenTime?.exceeded == true -> ChildCardStatus.TIME_UP
    faceEnrolled -> ChildCardStatus.ACTIVE
    else -> ChildCardStatus.OFFLINE
}

/** The localized label for a card status. */
@StringRes
fun childCardStatusLabelRes(status: ChildCardStatus): Int = when (status) {
    ChildCardStatus.ACTIVE -> R.string.children_status_active
    ChildCardStatus.TIME_UP -> R.string.children_status_time_up
    ChildCardStatus.OFFLINE -> R.string.children_status_offline
}

/** The status tone from the shared design-system vocabulary, so no color is invented. */
fun childCardStatusTone(status: ChildCardStatus): QalqonStatusTone = when (status) {
    ChildCardStatus.ACTIVE -> QalqonStatusTone.GRANTED
    ChildCardStatus.TIME_UP -> QalqonStatusTone.WARNING
    ChildCardStatus.OFFLINE -> QalqonStatusTone.INACTIVE
}

/**
 * Maps the account's children to their overview rows, preserving the order the
 * repository already returned. The avatar initial falls back to `?` for a blank name
 * rather than rendering an empty circle.
 *
 * [screenTime] carries today's real facts per child id; a child absent from it simply has
 * no screen-time section.
 */
fun childOverviews(
    children: List<ChildProfile>,
    screenTime: Map<Long, ChildScreenTime> = emptyMap(),
): List<ChildOverview> = children.map { child ->
    ChildOverview(
        childId = child.id,
        name = child.childName,
        initial = child.childName.trim().firstOrNull()?.uppercase() ?: "?",
        faceEnrolled = child.isFaceEnrolled,
        level = child.restrictionLevel,
        screenTime = screenTime[child.id],
    )
}
