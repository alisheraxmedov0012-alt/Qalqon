package uz.faceguard.app.feature.child

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
) {
    /** True when the child still needs face setup before protection can work. */
    val needsSetup: Boolean get() = !faceEnrolled
}

/**
 * Maps the account's children to their overview rows, preserving the order the
 * repository already returned. The avatar initial falls back to `?` for a blank name
 * rather than rendering an empty circle.
 */
fun childOverviews(children: List<ChildProfile>): List<ChildOverview> = children.map { child ->
    ChildOverview(
        childId = child.id,
        name = child.childName,
        initial = child.childName.trim().firstOrNull()?.uppercase() ?: "?",
        faceEnrolled = child.isFaceEnrolled,
        level = child.restrictionLevel,
    )
}
