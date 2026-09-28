package uz.faceguard.app.domain.policy

import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.schedule.ScheduleResolution

/**
 * Parent Policy Engine — domain model.
 *
 * This is the single decision layer that parental-control behaviour routes
 * through: identity (parent/child/unknown/no-face/obstructed), app policy
 * (allow/limit/block), and — in later steps — schedule, screen-time and
 * eye-safety.
 *
 * Step 1 provides the model + evaluator foundation. Platform enforcement of
 * every action is intentionally *not* claimed here: see [IMPLEMENTED_ACTIONS]
 * and the `ProtectionActionExecutor` seam.
 */

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

enum class UserIdentity {
    PARENT,
    CHILD,
    UNKNOWN,
    NO_FACE,
    CAMERA_OBSTRUCTED,
}

/**
 * Who the device believes is currently using it, produced from the existing
 * on-device recognition pipeline (`Recognizer` / `RecognitionResult`).
 */
data class IdentityContext(
    val identity: UserIdentity,
    val childId: Long? = null,
    val childName: String? = null,
    val confidence: Float? = null,
)

// ---------------------------------------------------------------------------
// Liveness (Group 9)
// ---------------------------------------------------------------------------

/**
 * Whether the face in front of the camera belongs to a real, live person.
 *
 * Deliberately separate from [UserIdentity] ("who"): identity answers *who* the
 * device believes is using it, liveness answers *whether a real person is
 * there*. A spoofed presentation can still be *recognised* as a parent or child;
 * the policy layer must never trust that recognised identity (see
 * [PolicyContext.liveness] handling in the evaluator).
 */
enum class LivenessState {
    /** No liveness evidence yet (pipeline idle, no frames, or window not filled). */
    UNKNOWN,

    /** The observation window shows a real, live face. */
    LIVE,

    /** The observation window indicates a presentation attack (photo/screen/replay). */
    SPOOF,

    /** No face in the observation window. */
    NO_FACE,

    /** A face is present but the evidence is too inconsistent to decide. */
    UNSTABLE,
}

/** True only for [LivenessState.LIVE]; every other state is not trusted as live. */
fun LivenessState.isTrustedLive(): Boolean = this == LivenessState.LIVE

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

/**
 * What protection may do when a policy denies usage.
 *
 * `ALLOW`, `WARNING`, `SOFT_BLOCK`, `HARD_BLOCK` and `MUTE` are produced and
 * enforced today. `DIM`, `BLUR` and `BLACK_SCREEN` exist in the domain so later
 * steps can add platform implementations without changing the model; this build
 * does **not** pretend to render them.
 */
enum class ProtectionAction {
    ALLOW,
    WARNING,
    DIM,
    BLUR,
    BLACK_SCREEN,
    SOFT_BLOCK,
    HARD_BLOCK,
    MUTE,
}

/** Actions this build can actually enforce on-device (see the executor). */
val IMPLEMENTED_ACTIONS: Set<ProtectionAction> = setOf(
    ProtectionAction.ALLOW,
    ProtectionAction.WARNING,
    ProtectionAction.SOFT_BLOCK,
    ProtectionAction.HARD_BLOCK,
    ProtectionAction.MUTE,
)

/** True when the action actually restricts usage (as opposed to ALLOW/WARNING). */
val ProtectionAction.isRestrictive: Boolean
    get() = this != ProtectionAction.ALLOW && this != ProtectionAction.WARNING

/**
 * Phase 5 Step 4: how restrictive an action is, used to combine a schedule restriction with the
 * ordinary child/app policy **without ever weakening it**.
 *
 * Higher means "restricts more", and combining two actions takes the higher rank. A schedule is a
 * restriction layer, so this makes it structurally impossible for a schedule to unlock something
 * the ordinary policy restricts — a schedule `ALLOW` simply cannot outrank a block.
 *
 * The order follows the approved restriction ordering, extended with the two existing actions
 * that list omits:
 *
 * ```
 * ALLOW (0) < WARNING (1) < MUTE (2) < SOFT_BLOCK (3) < HARD_BLOCK (4)
 * ```
 *
 * `MUTE` keeps its existing, independent meaning (silence the stream — see the action executor).
 * It is not redefined here and no combined "MUTE + X" action is invented (the domain has no such
 * type, and this step does not add one). It simply ranks above a warning, because it actually
 * enforces something, and below a block, because it does not stop usage at all.
 *
 * `DIM`/`BLUR`/`BLACK_SCREEN` have no platform effect in this build (see [IMPLEMENTED_ACTIONS]),
 * so they contribute no restriction and rank with `ALLOW`: a corrupt stored row carrying one can
 * therefore never displace an implemented action.
 */
val ProtectionAction.restrictionRank: Int
    get() = when (this) {
        ProtectionAction.ALLOW -> 0
        ProtectionAction.WARNING -> 1
        ProtectionAction.MUTE -> 2
        ProtectionAction.SOFT_BLOCK -> 3
        ProtectionAction.HARD_BLOCK -> 4
        // Domain-only in this build: no platform effect, so no restriction contributed.
        ProtectionAction.DIM,
        ProtectionAction.BLUR,
        ProtectionAction.BLACK_SCREEN,
        -> 0
    }

// ---------------------------------------------------------------------------
// Triggers
// ---------------------------------------------------------------------------

/** Why a policy was evaluated. Extensible for schedule/eye-safety steps. */
enum class PolicyTrigger {
    CHILD_RECOGNIZED,
    PARENT_RECOGNIZED,
    UNKNOWN_USER,
    NO_FACE,
    CAMERA_OBSTRUCTED,
    PROTECTED_APP_OPENED,
    /** Group 9: the presentation is not a real person (photo/screen/replay). */
    LIVENESS_SPOOF,
    SCREEN_TIME_EXCEEDED,
    SCHEDULE_ACTIVE,
    EYE_SAFETY_WARNING,
    EYE_SAFETY_DANGER,
}

// ---------------------------------------------------------------------------
// App policy (per child, backward compatible with protected_apps)
// ---------------------------------------------------------------------------

/**
 * ALLOW / LIMIT / BLOCK are three distinct concepts. The legacy
 * `ProtectedAppEntity.isProtected` flag only expressed BLOCK vs ALLOW, so this
 * model is keyed by `childId` and adds LIMIT without touching that table.
 */
enum class AppPolicyMode { ALLOW, LIMIT, BLOCK }

data class AppPolicy(
    val packageName: String,
    val mode: AppPolicyMode = AppPolicyMode.BLOCK,
    val action: ProtectionAction = ProtectionAction.HARD_BLOCK,
    val activationDelayMs: Long = 0L,
    val recoveryDelayMs: Long = 0L,
    /** Only meaningful for [AppPolicyMode.LIMIT]; null = unlimited. */
    val dailyLimitMinutes: Int? = null,
    /** null = applies to every child profile. */
    val childId: Long? = null,
)

// ---------------------------------------------------------------------------
// Device & safety contexts
// ---------------------------------------------------------------------------

enum class DeviceOwnerMode { PARENT_DEVICE, CHILD_DEVICE }

enum class EyeSafetyState { UNKNOWN, SAFE, WARNING, DANGER }

// ---------------------------------------------------------------------------
// Settings snapshot consumed by the evaluator
// ---------------------------------------------------------------------------

data class PolicySettings(
    val enabled: Boolean = false,
    /** How long a child must stay recognised before the action applies. */
    val activationDelayMs: Long = 0L,
    val childAction: ProtectionAction = ProtectionAction.HARD_BLOCK,
    val unknownUserAction: ProtectionAction = ProtectionAction.SOFT_BLOCK,
    val noFaceAction: ProtectionAction = ProtectionAction.ALLOW,
    val obstructionAction: ProtectionAction = ProtectionAction.SOFT_BLOCK,
    /**
     * Group 9: action when the presentation is a spoof ([LivenessState.SPOOF]).
     *
     * Not persisted yet — kept as an explicit, documented default. [SOFT_BLOCK]
     * is the safe choice: a recognised-but-spoofed face must not unlock the
     * device, and a soft block is recoverable without the aggressive, parent
     * false-positive-prone behaviour of a hard block.
     */
    val spoofAction: ProtectionAction = ProtectionAction.SOFT_BLOCK,
    val recoveryDelayMs: Long = 30_000L,
    /** When true a parent device also applies the child policy. */
    val parentDeviceChildPolicyEnabled: Boolean = false,
    /**
     * Phase 6 Step 2: the restriction a parent-configured eye-safety **warning** applies
     * (`EyeSafetyState.WARNING`), and the one a **danger** applies (`EyeSafetyState.DANGER`).
     *
     * Both reuse [ProtectionAction] — there is no eye-safety-specific action type — and both are
     * combined with the ordinary child decision by taking the *more restrictive* action
     * ([restrictionRank]), so eye safety can only ever tighten. A value of [ProtectionAction.ALLOW]
     * therefore means "the eye-safety layer does not restrict", which is also the default: with
     * nothing configured, eye safety changes no decision at all. Values are not persisted yet; they
     * arrive with the parent's configuration in a later step.
     */
    val eyeSafetyWarningAction: ProtectionAction = ProtectionAction.ALLOW,
    val eyeSafetyDangerAction: ProtectionAction = ProtectionAction.ALLOW,
)

// ---------------------------------------------------------------------------
// Evaluation context & decision
// ---------------------------------------------------------------------------

data class PolicyContext(
    val identity: IdentityContext,
    val settings: PolicySettings,
    /**
     * Group 9: whether a real person is in front of the camera, kept separate
     * from [identity]. Defaults to [LivenessState.UNKNOWN], i.e. "no liveness
     * evidence", so every existing caller keeps today's behaviour.
     */
    val liveness: LivenessState = LivenessState.UNKNOWN,
    val foregroundPackage: String? = null,
    val deviceOwnerMode: DeviceOwnerMode = DeviceOwnerMode.CHILD_DEVICE,
    /** Resolved policy for [foregroundPackage], if any. */
    val appPolicy: AppPolicy? = null,
    /** True when [foregroundPackage] is in the global protected-apps list. */
    val isProtectedApp: Boolean = false,
    val screenTimeUsedMinutes: Int = 0,
    /**
     * The foreground app's measured usage today, in whole minutes, for the **recognised child
     * in [identity]** — the same child [appPolicy] belongs to.
     *
     * `null` means "no measurement" (typically because Usage Access is not granted, or the
     * device's usage is attributed to a different child), and no screen-time restriction may be
     * derived from it. `0` means a genuine measurement of zero, which is a real value: a
     * `0`-minute limit is therefore reached immediately, exactly as before. The two must never
     * be conflated, which is why this is nullable rather than defaulting to zero.
     *
     * Phase 4 Step 4 supplies this from the existing screen-time usage repository; the policy
     * engine keeps owning the comparison, as it always has.
     */
    val appTimeUsedMinutes: Int? = null,
    val currentTimeMillis: Long = System.currentTimeMillis(),
    /**
     * Phase 5 Step 4: the schedule state that applies to [foregroundPackage] for the recognised
     * child, already resolved by the caller from the child's schedules.
     *
     * This replaces the previous inert `isScheduleActive: Boolean`, which could not distinguish
     * "no schedule", "one schedule" and "a conflict" and was never read. It reuses the schedule
     * domain's own [ScheduleResolution] rather than a duplicate state type, and defaults to
     * [ScheduleResolution.NoActiveSchedule] so every existing construction of this context keeps
     * today's behaviour exactly.
     *
     * The caller is responsible for the two gates that decide whether a schedule may participate
     * at all: the resolution must already be restricted to the schedules of _this_ child, and to
     * those whose affected-app membership contains [foregroundPackage]. Any other package is
     * reported as [ScheduleResolution.NoActiveSchedule]. A schedule therefore never applies to
     * another child, another account, or an app it does not target.
     */
    val scheduleResolution: ScheduleResolution = ScheduleResolution.NoActiveSchedule,
    /**
     * Phase 6 Step 2: how close the child's face is to the screen, as decided by the eye-safety
     * domain engine (`domain/eyesafety`). Defaults to [EyeSafetyState.UNKNOWN], which the evaluator
     * treats as "no measurement" and therefore as a no-op, so every existing caller keeps today's
     * behaviour exactly.
     *
     * Only [EyeSafetyState.WARNING] and [EyeSafetyState.DANGER] can ever restrict, and only for a
     * recognised child in a protected app; [EyeSafetyState.SAFE] is also a no-op. Note that "no
     * face" is reported as `UNKNOWN` by the domain engine, never as `SAFE` — an absent measurement
     * must not be read as "the child is far away". The action applied comes from
     * [PolicySettings.eyeSafetyWarningAction] / [PolicySettings.eyeSafetyDangerAction].
     */
    val eyeSafetyState: EyeSafetyState = EyeSafetyState.UNKNOWN,
)

/** The engine never returns "child = true"; it returns what should happen. */
sealed interface PolicyDecision {

    data object Allow : PolicyDecision

    /** Tell the user, but do not restrict. */
    data class Warn(val message: String? = null) : PolicyDecision

    /** Enforce [action] after an optional steady window; released after recovery. */
    data class Protect(
        val action: ProtectionAction,
        val activationDelayMs: Long,
        val recoveryDelayMs: Long,
        val trigger: PolicyTrigger,
        val reason: String,
    ) : PolicyDecision
}

// ---------------------------------------------------------------------------
// Mapping helpers (existing BlockPolicy -> policy action)
// ---------------------------------------------------------------------------

fun BlockPolicy.toProtectionAction(): ProtectionAction = when (this) {
    BlockPolicy.ALLOW -> ProtectionAction.ALLOW
    BlockPolicy.SOFT_BLOCK -> ProtectionAction.SOFT_BLOCK
    BlockPolicy.HARD_BLOCK -> ProtectionAction.HARD_BLOCK
}
