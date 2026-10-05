package uz.faceguard.app.domain.policy

/**
 * How a liveness observation bears on authorizing a recognised identity, expressed as an
 * explicit, testable classification (Stage 3).
 *
 * - [LIVE]       — the presentation is positively trusted as a real, live person.
 * - [SPOOF]      — a positive presentation-attack determination; the recognised identity
 *                  must not be trusted (fail-closed).
 * - [UNDECIDED]  — no positive conclusion: `UNKNOWN`/`UNSTABLE`/`NO_FACE`. The passive
 *                  heuristic reads a perfectly still *real* face this way, so an undecided
 *                  observation must never be silently promoted to live.
 */
enum class LivenessTrust { LIVE, SPOOF, UNDECIDED }

/**
 * Stage 3: the single, explicit identity + liveness authorization policy.
 *
 * QALQON keeps identity ("who is this?") and liveness ("is a real person there?") as two
 * independent signals, and combines them here — never inside the recogniser — so the
 * decision rule is one readable, unit-tested place.
 *
 * The combination rule:
 *
 * | identity        | liveness            | outcome |
 * |-----------------|---------------------|---------|
 * | any             | `SPOOF`             | the configured **spoof** action (fail-closed) |
 * | `PARENT`        | `LIVE`              | the parent's normal access |
 * | `CHILD`         | `LIVE`              | the child's configured policy |
 * | any             | `UNDECIDED`/`NO_FACE` | the identity's own policy, unchanged |
 *
 * A recognised-but-spoofed face (of *any* identity, parent included) never inherits that
 * identity's policy — `SPOOF` is checked before the identity switch. An *undecided*
 * observation does **not** override the identity: no anti-spoofing model is bundled, and
 * the passive heuristic is weak and uncalibrated (a still real face reads `UNDECIDED`), so
 * turning `UNDECIDED` into a hard block would be an uncalibrated false rejection of real
 * users. Strict fail-closed-on-undecided is deliberately deferred until a validated
 * liveness model exists — see the Stage 3 report.
 */
object LivenessPolicy {

    /** Classifies a raw liveness state into the explicit trust model. */
    fun trustOf(state: LivenessState): LivenessTrust = when (state) {
        LivenessState.LIVE -> LivenessTrust.LIVE
        LivenessState.SPOOF -> LivenessTrust.SPOOF
        LivenessState.UNKNOWN,
        LivenessState.UNSTABLE,
        LivenessState.NO_FACE,
        -> LivenessTrust.UNDECIDED
    }

    /**
     * Whether this liveness observation overrides a recognised identity (fail-closed).
     * Only a positive [LivenessState.SPOOF] determination does.
     */
    fun overridesIdentity(state: LivenessState): Boolean = trustOf(state) == LivenessTrust.SPOOF

    /** True when identity may authorize normally (a positive live determination). */
    fun isTrustedLive(state: LivenessState): Boolean = trustOf(state) == LivenessTrust.LIVE
}
