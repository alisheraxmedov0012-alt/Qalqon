package uz.faceguard.app.core.protection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uz.faceguard.app.core.liveness.LivenessEvaluator
import uz.faceguard.app.core.liveness.LivenessFrame
import uz.faceguard.app.core.liveness.LivenessResult
import uz.faceguard.app.core.monitor.ForegroundAppMonitor
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.core.policy.ActivationDelayGate
import uz.faceguard.app.core.recognition.RecognitionResult
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.core.scan.ScanScheduler
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicyEvaluator
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction

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
    private val monitor: ForegroundAppMonitor,
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
    }

    private fun tick(now: Long) {
        val foreground = monitor.current.value
        val protectedNow = foreground != null && foreground in protectedPackages
        if (protectedNow) {
            if (foreground != lastLoggedForeground) {
                safeLog(ActivityEventType.PROTECTED_APP_ENTERED, foreground)
            }
            // Re-arms a scan after a cooldown; no-op while scanning or cooling down.
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
            return
        }
        // Debounce is measured from the last state switch (see transition()),
        // not from entering the protected app, so the first decision is not
        // delayed by a full debounce window.
        if (now - lastStableAt < debounceMs) return
        if (scanScheduler != null && scanScheduler?.scanning?.value == false) return

        val raw = frame?.let { recognizer.evaluate(it, parent, children) } ?: RecognitionResult.NoFace
        val result = classify(raw)

        // multi-frame confirmation: need N consecutive same-class results
        pending += result
        if (pending.size > confirmFrames) pending.removeAt(0)
        if (pending.size < confirmFrames || pending.distinctBy { it::class }.size != 1) return

        // Group 8: publish the confirmed identity signal before deciding, so the
        // runtime always holds the identity that the decision was based on.
        recordIdentity(result, frameAvailable = frame != null, now = now)

        applyDecision(
            policyEvaluator.evaluate(policyContext(result, foreground, now, livenessResult)),
            result,
            now,
            foreground,
        )
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
            settings = policy,
            liveness = liveness.state,
            foregroundPackage = foreground,
            appPolicy = identity.childId?.let { childId ->
                foreground?.let { appPolicyLookup(childId, it) }
            },
            isProtectedApp = foreground != null && foreground in protectedPackages,
            appTimeUsedMinutes = appTimeUsedMinutes,
            currentTimeMillis = now,
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
                if (_state.value == target) return

                // Phase 9: a (re)block replaces any pending recovery release, so the
                // old timer is invalidated now instead of lingering until it fires.
                invalidateRecoveryTimer()
                // Best-effort restoration target for this protection cycle.
                _blockedApp.value = foreground

                transition(target, decision.reason, now, result.confidenceOrNull())
                actions.execute(decision.action)
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
        actions.clear()
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
