package uz.faceguard.app.domain.policy

import uz.faceguard.app.domain.model.BlockPolicy

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
    val isScheduleActive: Boolean = false,
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
