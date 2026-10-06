package uz.faceguard.app.core.protection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uz.faceguard.app.core.eyesafety.EyeSafetyObservationState
import uz.faceguard.app.core.eyesafety.eyeSafetyFrameOf
import uz.faceguard.app.core.liveness.LivenessEvaluator
import uz.faceguard.app.core.liveness.LivenessFrame
import uz.faceguard.app.core.liveness.LivenessResult
import uz.faceguard.app.core.monitor.ForegroundAppSource
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.core.policy.ActivationDelayGate
import uz.faceguard.app.core.recognition.RecognitionResult
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.core.scan.ScanScheduler
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.EyeSafetyState
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicyEvaluator
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleResolution
import uz.faceguard.app.domain.schedule.ScheduleTransitionTracker
import uz.faceguard.app.domain.schedule.scheduleEventDetail
import uz.faceguard.app.domain.eyesafety.ChildEyeSafetyConfig

/** Stable overlay state; transitions gated by debounce + recovery delay. */
enum class ProtectionState { UNPROTECTED, SOFT_BLOCKED, HARD_BLOCKED, RECOVERING }

data class ProtectionDecision(
    val state: ProtectionState,
    val reason: String,
    val confidence: Double? = null,
)

/**
 * One engine per active protection session.
 *
 * It collects recognition + foreground context, asks the Parent Policy Engine
 * for a decision and executes it through [ProtectionActionExecutor]. It owns no
 * policy rules of its own — [PolicyEvaluator] is the single source of truth.
 *
 * Resilience (unchanged from the pre-policy engine): multi-frame confirmation
 * + hysteresis prevent flicker; a recognised-then-lost face holds the block for
 * the recovery delay before releasing.
 */
class ProtectionEngine(
    private val recognizer: Recognizer,
    private val monitor: ForegroundAppSource,
    private val actions: ProtectionActionExecutor,
    private val policyEvaluator: PolicyEvaluator,
    /**
     * Group 9: the liveness decision lives outside the engine (no camera/ML code
     * here). The engine only feeds it frames and passes its state into the policy
     * context, exactly as it does for the identity signal.
     */
    private val livenessEvaluator: LivenessEvaluator = LivenessEvaluator(),
) {

    /** Event-driven scan scheduler; camera only on while a scan window is open. */
    var scanScheduler: ScanScheduler? = null
        private set

    fun attachScheduler(scheduler: ScanScheduler) { scanScheduler = scheduler }

    private val _state = MutableStateFlow(ProtectionState.UNPROTECTED)
    val state: StateFlow<ProtectionState> = _state

    private val _decision = MutableStateFlow<ProtectionDecision?>(null)
    val decision: StateFlow<ProtectionDecision?> = _decision

    /**
     * Group 8: the identity signal ("who is in front of the phone"). Updated only
     * when the confirmed identity actually changes, so it is a state, not a
     * per-frame stream. The foreground app is tracked separately by the monitor.
     */
    private val _identity = MutableStateFlow<IdentitySnapshot?>(null)
    val identity: StateFlow<IdentitySnapshot?> = _identity

    /**
     * Group 9: the liveness signal ("is a real person in front of the camera?").
     * Independent of [identity] and of the foreground app. Published as a state
     * (only when the state/source actually changes), never as a per-frame stream.
     */
    private val _liveness = MutableStateFlow<LivenessResult?>(null)
    val liveness: StateFlow<LivenessResult?> = _liveness

    /**
     * Phase 9: the package the current protection cycle is holding (the app that
     * was in the foreground when the block was applied). It is the best-effort
     * "restore to this context" target: when the block is released we simply stop
     * drawing our overlay, so the (still-foreground) app is usable again. We never
     * force a third-party app back or touch its private state. Cleared whenever the
     * cycle ends (release, cancel, stop, leave-protected-app).
     */
    private val _blockedApp = MutableStateFlow<String?>(null)
    val blockedApp: StateFlow<String?> = _blockedApp

    /**
     * Phase 9: generation token for the recovery timer. Bumped whenever a recovery
     * cycle starts, is replaced by a new block, or is cancelled. A timer only
     * releases protection when the generation it was armed with is still current,
     * so a stale timer can never shorten or end a newer cycle.
     */
    private var recoveryGeneration = 0L
    private var recoveryJob: Job? = null

    /**
     * Phase 9: the engine's logical clock - the last `now` it was evaluated with.
     * Recovery transitions use it so the engine's debounce window stays consistent
     * with the clock that drives evaluation. In production that is the tick's
     * wall-clock time; in tests it is the injected `now`.
     */
    @Volatile
    private var lastEvaluateNow = 0L

    private fun logicalNow(): Long =
        if (lastEvaluateNow > 0L) lastEvaluateNow else System.currentTimeMillis()

    private var settings = ProtectionSettings()
    private var policy = PolicySettings()

    /** Steady-window gate: a decision only applies after its activation delay. */
    private val activationGate = ActivationDelayGate()

    /** debounce window before a state switch is allowed */
    private val debounceMs = 1_200L

    /** consecutive frames of the same class required before a state change */
    private val confirmFrames = 3
    private val pending = mutableListOf<RecognitionResult>()

    /** frames seen during the current scan window without any face: obstruction heuristic */
    private var emptyFaceStreak = 0
    private val obstructionStreakLimit = 6

    /** rolling confidence band for instability detection */
    private val recentConfidences = ArrayDeque<Double>()
    private val confidenceWindow = 8
    private val instabilityBand = 0.25

    private var lastStableAt = 0L
    private var lastLoggedForeground: String? = null

    /**
     * Last identity written to the activity log. Recognition is confirmed over
     * several frames and the engine keeps evaluating while a protected app is
     * foreground, so without this a steady child/unknown state would emit an
     * event every tick (an event storm) instead of on the transition only.
     */
    private var lastLoggedType: ActivityEventType? = null
    private var lastLoggedChildId: Long? = null

    private var scope: CoroutineScope? = null
    private var engineJob: Job? = null
    private var frameJob: Job? = null

    @Volatile
    private var latestFrame: FrameEvent? = null

    /** Activity-log hook; set by the caller to persist events. */
    var onEvent: (ActivityEventType, String?) -> Unit = { _, _ -> }

    /**
     * Reports a failed recognition call or protection side effect (overlay, audio).
     *
     * A blocking-window or platform failure is a runtime condition, not a
     * programming error, so it must never propagate out of [evaluate] into the
     * engine's tick coroutine: an uncaught throw there crashes the process and
     * takes protection down with it. The runtime wires this to diagnostics; the
     * default is a no-op so an un-wired engine still cannot crash.
     */
    var onEngineError: (Throwable) -> Unit = { }

    /** Runs a side effect isolated: a failure is reported, never propagated. */
    private inline fun safeSideEffect(block: () -> Unit) {
        runCatching { block() }.onFailure { reportEngineError(it) }
    }

    /** Error reporting is observability: a failing hook must never break protection. */
    private fun reportEngineError(t: Throwable) {
        runCatching { onEngineError(t) }
    }

    fun updateSettings(settings: ProtectionSettings, policy: PolicySettings) {
        this.settings = settings
        this.policy = policy
        scanScheduler?.setMode(settings.scanMode)
        scanScheduler?.setLowBatteryBehavior(settings.lowBatteryBehaviorEnabled)
    }

    private var parent: ParentProfile? = null
    private var children: List<ChildProfile> = emptyList()
    private val protectedPackages = mutableSetOf<String>()

    /** Resolves the persisted per-child app policy; set by the runtime. */
    var appPolicyLookup: (childId: Long, packageName: String) -> AppPolicy? = { _, _ -> null }

    /**
     * Resolves the recognised child's measured usage today for a package, in whole minutes;
     * set by the runtime. `null` means "no measurement available".
     *
     * Phase 4 Step 4: exactly like [appPolicyLookup], this is a plain in-memory lookup the
     * runtime keeps fresh from the existing usage repository, so the engine performs no I/O on
     * the evaluation path. The default returns "no measurement", so an engine that has not been
     * wired for screen-time enforcement behaves exactly as it did before.
     */
    var appTimeUsedMinutesLookup: (childId: Long, packageName: String) -> Int? = { _, _ -> null }

    /**
     * Phase 5 Step 4: the recognised child's schedule state for the app in the foreground,
     * resolved for the tick's `now`; set by the runtime.
     *
     * Exactly like [appPolicyLookup], this is a plain in-memory lookup kept fresh from the
     * existing schedule repository, so the engine performs no I/O on the evaluation path and
     * never touches Room itself. The caller returns the resolution among the schedules of that
     * child whose affected-app membership includes that package, and
     * [ScheduleResolution.NoActiveSchedule] for any other package — so a schedule can never apply
     * to another child, another account, or an app it does not target. The default returns "no
     * active schedule", so an engine that has not been wired for schedules behaves exactly as it
     * did before.
     */
    var scheduleResolutionLookup: (childId: Long, packageName: String, now: Long) -> ScheduleResolution =
        { _, _, _ -> ScheduleResolution.NoActiveSchedule }

    /**
     * Phase 6 Step 4: the recognised child's eye-safety configuration; set by the runtime.
     *
     * Exactly like [appPolicyLookup] and [scheduleResolutionLookup], this is a plain in-memory
     * lookup the runtime keeps fresh from the eye-safety repository, so the engine never performs
     * I/O on the evaluation path and never touches Room itself. The argument is the recognised
     * child — the same child the app policy and schedule are resolved for — so one child's
     * configuration can never be applied to another, and a parent/unknown/no-face observation
     * (which carries no child id) can never reach a child's configuration at all.
     *
     * `null` means "no active configuration for this child": either the child has none, or the
     * parent disabled it. Either way eye safety is a no-op. The default returns `null`, so an
     * engine that has not been wired for eye safety behaves exactly as it did before.
     */
    var eyeSafetyConfigLookup: (childId: Long) -> ChildEyeSafetyConfig? = { null }

    /**
     * Phase 6 Step 4: the recognised child's active eye-safety session.
     *
     * It lives with the engine because the *observations* do: the engine is the one place where a
     * face frame and the confirmed identity for that same instant are both available, so feeding
     * the evaluator here is race-free and needs no second collector on the camera flow.
     * [EyeSafetyObservationState] owns the "which child is active, and when to reset" rule; the
     * thresholds, hysteresis and confirmation belong to the Phase 6 Step 1 evaluator it delegates
     * to, and are never reimplemented here.
     */
    private val eyeSafety = EyeSafetyObservationState()

    /**
     * Phase 5 Step 6: the schedule state that is *effective* for the current runtime context —
     * the recognised child plus the protected app in the foreground. Published as a state (only
     * when it actually changes), exactly like [identity] and [liveness], so the runtime can expose
     * it without the engine leaking per-tick churn.
     *
     * It is recomputed on the existing tick, so a schedule starting or ending is noticed by the
     * next evaluation; there is no schedule timer and no second loop.
     */
    private val _effectiveSchedule = MutableStateFlow<ScheduleResolution>(ScheduleResolution.NoActiveSchedule)
    val effectiveSchedule: StateFlow<ScheduleResolution> = _effectiveSchedule

    /**
     * Reports the change between consecutive effective schedule states, once per change. It lives
     * here — with the engine that observes the schedule — rather than in a ViewModel or screen, so
     * recreating UI cannot produce a transition and only the engine's own lifecycle resets it.
     */
    private val scheduleTracker = ScheduleTransitionTracker()

    fun updateContext(parent: ParentProfile?, children: List<ChildProfile>, protected: Set<String>) {
        this.parent = parent
        this.children = children
        protectedPackages.clear()
        protectedPackages.addAll(protected)
    }

    /** Starts the continuous evaluation loop; safe to call once per session. */
    fun start(scope: CoroutineScope) {
        if (engineJob != null) return
        this.scope = scope
        scanScheduler?.attach(scope)
        frameJob = scope.launch { recognizer.frames.collect { latestFrame = it } }
        engineJob = scope.launch {
            while (isActive) {
                tick(System.currentTimeMillis())
                delay(TICK_MS)
            }
        }
    }

    /** Stops evaluation and clears any overlay. */
    fun stop() {
        engineJob?.cancel()
        engineJob = null
        frameJob?.cancel()
        frameJob = null
        scope = null
        latestFrame = null
        resetTrackers()
        activationGate.cancel()
        // Phase 9: no pending recovery release may outlive the session.
        invalidateRecoveryTimer()
        lastLoggedForeground = null
        if (_state.value != ProtectionState.UNPROTECTED) {
            transition(ProtectionState.UNPROTECTED, "protection stopped", System.currentTimeMillis())
        }
        clearBlock()
        _decision.value = null
        // Group 8: a stopped session holds no identity state.
        _identity.value = null
        // Group 9: and no liveness state either.
        resetLiveness()
        // Phase 5 Step 6: a stopped session holds no effective schedule.
        resetSchedule()
        // Phase 6 Step 4: and no child eye-safety session.
        resetEyeSafety()
    }

    private fun tick(now: Long) {
        val foreground = monitor.current.value
        val protectedNow = foreground != null && foreground in protectedPackages
        if (protectedNow) {
            if (foreground != lastLoggedForeground) {
                safeLog(ActivityEventType.PROTECTED_APP_ENTERED, foreground)
            }
            // Keep asking for a scan window on every tick while a protected app is in
            // the foreground. `requestScan` itself ignores the call while a window is
            // open or a cooldown is running, so this simply re-arms the scan as soon
            // as the cooldown expires. Requesting only on *entry* left the scheduler
            // idle after the first window+cooldown, and `evaluate` then early-returns
            // for the rest of the time the child stayed inside the protected app —
            // i.e. recognition silently stopped enforcing, with no error and no UI
            // signal. Re-requesting per tick keeps the documented battery model
            // (a bounded scan window followed by a cooldown) while guaranteeing that
            // evaluation always resumes.
            scanScheduler?.onProtectedAppOpened()
        }
        lastLoggedForeground = if (protectedNow) foreground else null
        evaluate(foreground, freshFrame(now), now)
    }

    /** Frames older than the TTL are ignored so a stopped camera counts as "no face". */
    private fun freshFrame(now: Long): FrameEvent? {
        val frame = latestFrame ?: return null
        return if (now - frame.timestamp <= FRAME_TTL_MS) frame else null
    }

    fun evaluate(foreground: String?, frame: FrameEvent?, now: Long = System.currentTimeMillis()) {
        lastEvaluateNow = now
        // Group 9: feed the liveness window from the same frames the identity
        // signal uses and publish the resulting state. This runs before the
        // protected-app / debounce gates and is completely independent of the
        // identity confirmation semantics below.
        if (frame != null) livenessEvaluator.observe(LivenessFrame.from(frame))
        val livenessResult = livenessEvaluator.result(now)
        recordLiveness(livenessResult)

        val protectedNow = foreground != null && foreground in protectedPackages
        if (!protectedNow) {
            activationGate.cancel()
            if (_state.value != ProtectionState.UNPROTECTED) {
                transition(ProtectionState.UNPROTECTED, "no protected app in foreground", now)
                clearBlock()
            }
            resetTrackers()
            // Phase 5 Step 6: with no protected app in the foreground no schedule is effective, so
            // leaving one is a real deactivation rather than a silently held state.
            recordSchedule(ScheduleResolution.NoActiveSchedule, childId = null, isProtectedApp = false)
            return
        }
        // Debounce is measured from the last state switch (see transition()),
        // not from entering the protected app, so the first decision is not
        // delayed by a full debounce window.
        if (now - lastStableAt < debounceMs) return
        if (scanScheduler != null && scanScheduler?.scanning?.value == false) return

        val raw = frame?.let { f ->
            // A recognition failure (ML Kit / TFLite / template decode) must not crash
            // the evaluation loop. The deterministic fallback is NoFace, which then
            // follows the parent's configured no-face policy — so a failure can never
            // silently *allow* a protected app when the policy fails closed.
            runCatching {
                // Stage 2: evaluate every detected face and aggregate them deterministically.
                // The per-child action for the foreground app is passed so that, when several
                // children are recognised at once, the most restrictive one is chosen (and its
                // identity — child id — is preserved for the downstream per-child policy).
                recognizer.evaluateAll(f, parent, children) { childId ->
                    foreground?.let { appPolicyLookup(childId, it) }?.action
                }.result
            }
                .getOrElse { error ->
                    reportEngineError(error)
                    RecognitionResult.NoFace
                }
        } ?: RecognitionResult.NoFace
        val result = classify(raw)

        // multi-frame confirmation: need N consecutive same-class results
        pending += result
        if (pending.size > confirmFrames) pending.removeAt(0)
        if (pending.size < confirmFrames || pending.distinctBy { it::class }.size != 1) return

        // Group 8: publish the confirmed identity signal before deciding, so the
        // runtime always holds the identity that the decision was based on.
        recordIdentity(result, frameAvailable = frame != null, now = now)

        // Phase 6 Step 4: feed this frame into the recognised child's eye-safety session and take
        // the state the policy context should carry. Observations are the same frames recognition
        // already saw — no second camera pass — and the temporal confirmation/hysteresis stay in
        // the Phase 6 Step 1 evaluator, never here.
        val eyeSafetyState = recordEyeSafety(frame, childId = result.recognizedChildId(), now = now)

        val context = policyContext(result, foreground, now, livenessResult, eyeSafetyState)
        // Phase 5 Step 6: the schedule the policy context was just built with is the effective one,
        // so publish/track exactly that — the engine never recomputes it a second way.
        recordSchedule(context.scheduleResolution, childId = context.identity.childId, isProtectedApp = protectedNow)

        applyDecision(
            policyEvaluator.evaluate(context),
            result,
            now,
            foreground,
        )
    }

    /**
     * The child the recognition result identifies, or `null` when it identifies none (parent,
     * unknown user, no face, obstructed, unstable). Used verbatim from the domain mapping so the
     * eye-safety lookup is keyed by exactly the same child the policy decision is about.
     */
    private fun RecognitionResult.recognizedChildId(): Long? = when (this) {
        is RecognitionResult.ChildRecognized -> childId
        else -> null
    }

    /**
     * Phase 5 Step 6: publishes the effective schedule and reports a change exactly once.
     *
     * [ScheduleResolution] is a value type, so the state is only re-published when it really
     * changed; the tracker then decides whether the change is a transition. An unchanged state —
     * the case on almost every tick — publishes nothing and logs nothing, which is what keeps a
     * schedule that stays active for hours from filling the activity log.
     *
     * The transition is written through the existing [onEvent] hook, the same path
     * `CHILD_BLOCKED` and `PROTECTION_RELEASED` already use, so the runtime persists it without the
     * engine knowing anything about the activity log or the database.
     */
    private fun recordSchedule(resolution: ScheduleResolution, childId: Long?, isProtectedApp: Boolean) {
        // The published state is the one that actually applies, so an unprotected app reports
        // "none" rather than a schedule the policy layer must ignore anyway.
        val published = if (isProtectedApp) resolution else ScheduleResolution.NoActiveSchedule
        if (_effectiveSchedule.value != published) _effectiveSchedule.value = published

        val transition = scheduleTracker.onResolution(resolution, childId, isProtectedApp) ?: return
        safeLog(ActivityEventType.SCHEDULE_CHANGED, scheduleEventDetail(transition.to))
    }

    /** Clears the effective-schedule state and its baseline (account change / sign-out / stop). */
    fun resetSchedule() {
        scheduleTracker.reset()
        _effectiveSchedule.value = ScheduleResolution.NoActiveSchedule
    }

    /**
     * Phase 6 Step 4: feeds the current frame into the recognised child's eye-safety session and
     * returns the state the policy context should carry.
     *
     * The lookup is the runtime's in-memory map (never Room), and it is keyed by the recognised
     * child — so a parent, an unknown user or a no-face observation (`childId == null`) can never
     * reach a child's configuration. [EyeSafetyObservationState] applies the rest of the gate
     * (unconfigured / disabled / different child) and owns the reset rules.
     */
    private fun recordEyeSafety(frame: FrameEvent?, childId: Long?, now: Long): EyeSafetyState =
        eyeSafety.observe(
            observation = frame?.let { eyeSafetyFrameOf(it.quality, it.timestamp) },
            childId = childId,
            child = childId?.let { eyeSafetyConfigLookup(it) },
            now = now,
        )

    /**
     * [settings] with the recognised child's configured eye-safety actions applied.
     *
     * Delegates to [EyeSafetyObservationState], which knows whether a child session is active; when
     * none is, the runtime's own settings are passed through untouched.
     */
    private fun effectivePolicySettings(settings: PolicySettings): PolicySettings =
        eyeSafety.policySettings(settings)

    /** Drops the eye-safety session (no child, disabled, config changed, or session stop). */
    fun resetEyeSafety() {
        eyeSafety.reset()
    }

    /**
     * Publishes the liveness signal, collapsing repeats (state + source) so
     * downstream code sees a state, not per-frame churn. [LivenessResult] values
     * with the same state and source are equivalent for the policy decision.
     */
    private fun recordLiveness(result: LivenessResult) {
        val current = _liveness.value
        if (current?.state == result.state && current.source == result.source) return
        _liveness.value = result
    }

    /** Clears the liveness signal (account change / sign-out / session stop). */
    fun resetLiveness() {
        livenessEvaluator.reset()
        _liveness.value = null
    }

    /**
     * Records the confirmed identity, collapsing repeats (StateFlow equality on
     * the identity context + source) so nothing downstream sees per-frame churn.
     */
    private fun recordIdentity(result: RecognitionResult, frameAvailable: Boolean, now: Long) {
        val context = identityContextOf(result)
        val source = if (frameAvailable) IdentitySource.CAMERA else IdentitySource.NONE
        val current = _identity.value
        if (current?.context == context && current.source == source) return
        _identity.value = IdentitySnapshot(context = context, source = source, updatedAt = now)
    }

    /** Clears the identity signal (account change / sign-out / session stop). */
    fun resetIdentity() {
        _identity.value = null
    }

    /** Builds the runtime context the evaluator decides on. */
    private fun policyContext(
        result: RecognitionResult,
        foreground: String?,
        now: Long,
        liveness: LivenessResult,
        eyeSafetyState: EyeSafetyState,
    ): PolicyContext {
        val identity = identityContextOf(result)
        // The usage lookup is keyed by the SAME child the app policy is resolved for — the
        // recognised child — so enforcement can never read a different child's usage. A null
        // measurement (Usage Access unavailable, or a child with no attributed usage) stays
        // null and suppresses only the screen-time restriction, never the app's own policy.
        val appTimeUsedMinutes = identity.childId?.let { childId ->
            foreground?.let { appTimeUsedMinutesLookup(childId, it) }
        }
        return PolicyContext(
            identity = identity,
            // Phase 6 Step 4: when a child eye-safety session is active, the policy settings the
            // evaluator sees carry that child's own configured actions; every other field is the
            // runtime's unchanged value.
            settings = effectivePolicySettings(policy),
            liveness = liveness.state,
            foregroundPackage = foreground,
            appPolicy = identity.childId?.let { childId ->
                foreground?.let { appPolicyLookup(childId, it) }
            },
            isProtectedApp = foreground != null && foreground in protectedPackages,
            appTimeUsedMinutes = appTimeUsedMinutes,
            currentTimeMillis = now,
            // Phase 5 Step 4: the same recognised child and foreground package the app policy and
            // usage were resolved for, so a schedule can never be resolved for a different child
            // or app. "No active schedule" for a child with no schedules, or for a package no
            // schedule targets.
            scheduleResolution = identity.childId?.let { childId ->
                foreground?.let { scheduleResolutionLookup(childId, it, now) }
            } ?: ScheduleResolution.NoActiveSchedule,
            // Phase 6 Step 4: the recognised child's eye-safety state. UNKNOWN whenever there is no
            // active child session (no child, unconfigured, disabled), which the evaluator treats
            // as a no-op — so eye safety never restricts a parent, an unknown user or a child
            // without a configuration.
            eyeSafetyState = eyeSafetyState,
        )
    }


    private fun applyDecision(
        decision: PolicyDecision,
        result: RecognitionResult,
        now: Long,
        foreground: String?,
    ) {
        // Meaningful recognition transitions only: the multi-frame confirmation
        // above suppresses per-frame chatter and logIdentityTransition suppresses
        // repeats while the identity itself does not change.
        logIdentityTransition(result)

        when (decision) {
            is PolicyDecision.Allow -> {
                activationGate.cancel()
                if (_state.value != ProtectionState.UNPROTECTED) {
                    if (result is RecognitionResult.ParentRecognized) {
                        safeLog(ActivityEventType.PARENT_UNLOCKED, null)
                        transition(
                            ProtectionState.UNPROTECTED,
                            "parent recognized (confidence=${result.confidence})",
                            now,
                            result.confidence,
                        )
                        clearBlock()
                    } else if (_state.value != ProtectionState.RECOVERING) {
                        beginRecovery(now, policy.recoveryDelayMs, foreground)
                    }
                }
            }

            is PolicyDecision.Warn -> {
                activationGate.cancel()
                if (_state.value != ProtectionState.UNPROTECTED &&
                    _state.value != ProtectionState.RECOVERING
                ) {
                    beginRecovery(now, policy.recoveryDelayMs, foreground)
                }
            }

            is PolicyDecision.Protect -> {
                // request() arms the steady window; for a positive delay it
                // returns false until the window has elapsed, so only keep
                // holding the current state while the activation is still
                // pending. Once ready, the action is applied below.
                if (!activationGate.request(decision.action, decision.activationDelayMs) &&
                    !activationGate.isReady()
                ) {
                    return
                }

                val target = stateFor(decision.action)
                if (_state.value == target) {
                    // Phase 9: the cycle is already blocked, but the protected app the
                    // block applies to may have changed (A -> B) while the child stays
                    // in front of the device. Re-point the cycle's restoration target at
                    // the app currently being held, so nothing stale (an extra-time
                    // request target, the parent-facing "blocked app", a later release)
                    // is derived from the previous app.
                    if (foreground != _blockedApp.value) _blockedApp.value = foreground
                    // Stage 4: the block is still required, so re-assert its enforcement
                    // surface. This self-heals an overlay lost without a state change (an
                    // accessibility reconnect, a transient WindowManager detach, an
                    // out-of-band removal) within one tick instead of leaving the app
                    // "blocked" with nothing on screen. Idempotent and isolated, so it
                    // neither duplicates the overlay nor risks the evaluation loop.
                    safeSideEffect { actions.reassert(decision.action) }
                    return
                }

                // Phase 9: a (re)block replaces any pending recovery release, so the
                // old timer is invalidated now instead of lingering until it fires.
                invalidateRecoveryTimer()
                // Best-effort restoration target for this protection cycle.
                _blockedApp.value = foreground

                transition(target, decision.reason, now, result.confidenceOrNull())
                // The side effect is isolated: a failing overlay/audio target must not
                // crash the evaluation loop (and with it the whole process).
                safeSideEffect { actions.execute(decision.action) }
                // Recorded after the action was applied, so a block that never
                // reached the executor does not appear in the log. The executor
                // is fire-and-forget, hence this states the outcome rather than
                // claiming an executor "success".
                if (result is RecognitionResult.ChildRecognized &&
                    (target == ProtectionState.SOFT_BLOCKED || target == ProtectionState.HARD_BLOCKED)
                ) {
                    safeLog(ActivityEventType.CHILD_BLOCKED, result.childName)
                }
            }
        }
    }

    /**
     * Writes a recognition transition to the activity log, at most once per
     * distinct identity. Unstable or obstructed recognition produces no identity
     * event rather than being mislabelled as an unknown user.
     */
    private fun logIdentityTransition(result: RecognitionResult) {
        val type = when (result) {
            is RecognitionResult.ParentRecognized -> ActivityEventType.PARENT_RECOGNIZED
            is RecognitionResult.ChildRecognized -> ActivityEventType.CHILD_RECOGNIZED
            is RecognitionResult.Unknown -> ActivityEventType.UNKNOWN_USER
            RecognitionResult.NoFace -> ActivityEventType.NO_FACE
            is RecognitionResult.CameraPossiblyObstructed,
            is RecognitionResult.UnstableRecognition,
            -> null
        } ?: return

        val child = result as? RecognitionResult.ChildRecognized
        if (type == lastLoggedType && child?.childId == lastLoggedChildId) return
        lastLoggedType = type
        lastLoggedChildId = child?.childId

        safeLog(type, child?.childName)
    }

    /** Logging is observability: a failing hook must never break protection. */
    private fun safeLog(type: ActivityEventType, detail: String?) {
        runCatching { onEvent(type, detail) }
    }

    private fun RecognitionResult.confidenceOrNull(): Double? = when (this) {
        is RecognitionResult.ParentRecognized -> confidence
        is RecognitionResult.ChildRecognized -> confidence
        is RecognitionResult.Unknown -> confidence
        else -> null
    }

    private fun stateFor(action: ProtectionAction): ProtectionState = when (action) {
        ProtectionAction.SOFT_BLOCK, ProtectionAction.MUTE -> ProtectionState.SOFT_BLOCKED
        else -> ProtectionState.HARD_BLOCKED
    }

    /** Enrich raw results with obstruction + instability classification. */
    private fun classify(raw: RecognitionResult): RecognitionResult {
        if (raw is RecognitionResult.NoFace) {
            emptyFaceStreak += 1
            if (emptyFaceStreak >= obstructionStreakLimit) return RecognitionResult.CameraPossiblyObstructed
            return raw
        }
        emptyFaceStreak = 0
        val confidence = raw.confidenceOrNull()
        if (confidence != null) {
            recentConfidences += confidence
            if (recentConfidences.size > confidenceWindow) recentConfidences.removeFirst()
            if (recentConfidences.size == confidenceWindow) {
                val spread = recentConfidences.max() - recentConfidences.min()
                if (spread > instabilityBand) return RecognitionResult.UnstableRecognition
            }
        }
        return raw
    }

    /**
     * Phase 9: a recognized face was lost while blocked. Hold the block for the
     * recovery delay, then release exactly once.
     *
     * The timer is armed with a generation token, so only the timer belonging to
     * the *current* recovery cycle may release; a stale timer can never shorten or
     * end a newer cycle. Arming also cancels any previous timer, so there is never
     * more than one recovery timer alive.
     */
    private fun beginRecovery(now: Long, delayMs: Long, foreground: String?) {
        if (_state.value == ProtectionState.UNPROTECTED || _state.value == ProtectionState.RECOVERING) return
        // Replace any previous timer: never a duplicate.
        recoveryJob?.cancel()
        val generation = ++recoveryGeneration
        if (foreground != null) _blockedApp.value = foreground
        transition(ProtectionState.RECOVERING, "restriction lifted; recovery window", now)
        // No running session (a bare evaluate() call) -> no timer, exactly as before.
        val session = scope ?: return
        recoveryJob = session.launch {
            delay(delayMs)
            // Stale-guard: only the cycle that armed this timer may release.
            if (generation == recoveryGeneration && _state.value == ProtectionState.RECOVERING) {
                releaseRecovered(System.currentTimeMillis())
            }
        }
    }

    /** Ends a recovery cycle: releases the block once and logs it exactly once. */
    private fun releaseRecovered(now: Long) {
        recoveryJob = null
        // Invalidate any sibling timer so a second release can never follow.
        recoveryGeneration++
        transition(ProtectionState.UNPROTECTED, "recovery delay elapsed", logicalNow())
        clearBlock()
        safeLog(ActivityEventType.PROTECTION_RELEASED, null)
    }

    /**
     * Cancels the pending timer and invalidates it (generation bump). The current
     * protection state is left alone; callers decide what the boundary means.
     */
    private fun invalidateRecoveryTimer() {
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryGeneration++
    }

    /**
     * Phase 9: drops any pending recovery window at a lifecycle boundary (account
     * change, sign-out, protection disable, runtime stop). No PROTECTION_RELEASED
     * is logged, because the cycle did not end by the recovery rule; the state is
     * reset so a stale timer can never touch a later cycle or another account.
     */
    fun cancelRecovery() {
        invalidateRecoveryTimer()
        if (_state.value == ProtectionState.RECOVERING) {
            transition(ProtectionState.UNPROTECTED, "recovery cancelled", logicalNow())
            clearBlock()
        }
    }

    private fun resetTrackers() {
        pending.clear()
        emptyFaceStreak = 0
        recentConfidences.clear()
        // Leaving the protected-app context clears the identity baseline, so the
        // next session logs its first identity transition again.
        lastLoggedType = null
        lastLoggedChildId = null
    }

    private fun transition(newState: ProtectionState, reason: String, now: Long, confidence: Double? = null) {
        _state.value = newState
        _decision.value = ProtectionDecision(newState, reason, confidence)
        lastStableAt = now
    }

    private fun clearBlock() {
        // Isolated for the same reason as execute(): a failing overlay teardown must
        // not crash the caller (which may be the tick, a recovery timer or unlock).
        safeSideEffect { actions.clear() }
        // Phase 9: the protection cycle is over, so its best-effort restoration
        // target is dropped with it and any pending release is invalidated.
        _blockedApp.value = null
        invalidateRecoveryTimer()
    }

    interface OverlayController {
        fun show()
        fun hide()
    }

    /** PIN-based parent emergency unlock; caller validates against stored PIN. */
    fun emergencyUnlock() {
        safeLog(ActivityEventType.EMERGENCY_UNLOCK, null)
        activationGate.cancel()
        // Phase 9: an emergency unlock supersedes any pending recovery release.
        invalidateRecoveryTimer()
        transition(ProtectionState.UNPROTECTED, "emergency unlock", System.currentTimeMillis())
        clearBlock()
    }

    private companion object {
        const val TICK_MS = 500L
        const val FRAME_TTL_MS = 1_500L
    }
}
