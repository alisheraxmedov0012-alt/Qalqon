package uz.faceguard.app.core.protection

import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uz.faceguard.app.core.monitor.ForegroundAppMonitor
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.core.recognition.RecognitionResult
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.core.scan.ScanScheduler
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile

/** Stable overlay state; transitions gated by debounce + recovery delay. */
enum class ProtectionState { UNPROTECTED, SOFT_BLOCKED, HARD_BLOCKED, RECOVERING }

data class ProtectionDecision(
    val state: ProtectionState,
    val reason: String,
    val confidence: Double? = null,
)

/**
 * One engine per active protection session. It ticks continuously while
 * protection is enabled and evaluates:
 *
 *   monitor.current ------+
 *   recognizer frame -----+--> evaluate() --> overlay + audio effect
 *   ProtectionSettings ---+
 *
 * Resilience: multi-frame confirmation + hysteresis prevent flicker. Unknown
 * faces / obstruction follow the unknown-user policy; missing faces follow the
 * no-face policy (a recognized-then-lost face uses the recovery delay instead).
 */
class ProtectionEngine(
    private val recognizer: Recognizer,
    private val monitor: ForegroundAppMonitor,
    private val audio: AudioManager,
    private val overlay: OverlayController,
) {

    /** Event-driven scan scheduler; camera only on while a scan window is open. */
    var scanScheduler: ScanScheduler? = null
        private set

    fun attachScheduler(scheduler: ScanScheduler) { scanScheduler = scheduler }

    private val _state = MutableStateFlow(ProtectionState.UNPROTECTED)
    val state: StateFlow<ProtectionState> = _state

    private val _decision = MutableStateFlow<ProtectionDecision?>(null)
    val decision: StateFlow<ProtectionDecision?> = _decision

    private var settings = ProtectionSettings()

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
    private var lastForegroundProtected = false
    private var lastLoggedForeground: String? = null

    private var scope: CoroutineScope? = null
    private var engineJob: Job? = null
    private var frameJob: Job? = null

    @Volatile
    private var latestFrame: FrameEvent? = null

    /** Activity-log hook; set by the caller to persist events. */
    var onEvent: (ActivityEventType, String?) -> Unit = { _, _ -> }

    fun updateSettings(new: ProtectionSettings) {
        settings = new
        scanScheduler?.setMode(new.scanMode)
        scanScheduler?.setLowBatteryBehavior(new.lowBatteryBehaviorEnabled)
    }

    private var parent: ParentProfile? = null
    private var children: List<ChildProfile> = emptyList()
    private val protectedPackages = mutableSetOf<String>()

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
        lastForegroundProtected = false
        lastLoggedForeground = null
        if (_state.value != ProtectionState.UNPROTECTED) {
            transition(ProtectionState.UNPROTECTED, "protection stopped", System.currentTimeMillis())
        }
        clearBlock()
        _decision.value = null
    }

    private fun tick(now: Long) {
        val foreground = monitor.current.value
        val protectedNow = foreground != null && foreground in protectedPackages
        if (protectedNow) {
            if (foreground != lastLoggedForeground) {
                onEvent(ActivityEventType.PROTECTED_APP_ENTERED, foreground)
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
        val protectedNow = foreground != null && foreground in protectedPackages
        if (!protectedNow) {
            if (_state.value != ProtectionState.UNPROTECTED) {
                transition(ProtectionState.UNPROTECTED, "no protected app in foreground", now)
                clearBlock()
            }
            lastForegroundProtected = false
            resetTrackers()
            return
        }
        if (!lastForegroundProtected) {
            lastForegroundProtected = true
            lastStableAt = now
        }
        if (now - lastStableAt < debounceMs) return
        if (scanScheduler != null && scanScheduler?.scanning?.value == false) return

        val raw = frame?.let { recognizer.evaluate(it, parent, children) } ?: RecognitionResult.NoFace
        val result = classify(raw)

        // multi-frame confirmation: need N consecutive same-class results
        pending += result
        if (pending.size > confirmFrames) pending.removeAt(0)
        if (pending.size < confirmFrames || pending.distinctBy { it::class }.size != 1) return

        when (result) {
            is RecognitionResult.ParentRecognized -> {
                onEvent(ActivityEventType.PARENT_RECOGNIZED, null)
                if (_state.value != ProtectionState.UNPROTECTED) {
                    onEvent(ActivityEventType.PARENT_UNLOCKED, null)
                    transition(
                        ProtectionState.UNPROTECTED,
                        "parent recognized (confidence=${result.confidence})",
                        now,
                        result.confidence,
                    )
                    clearBlock()
                }
            }
            is RecognitionResult.ChildRecognized -> {
                onEvent(ActivityEventType.CHILD_RECOGNIZED, result.childName)
                if (_state.value == ProtectionState.UNPROTECTED) {
                    onEvent(ActivityEventType.CHILD_BLOCKED, result.childName)
                    applyBlock(
                        ProtectionState.HARD_BLOCKED,
                        "child recognized (confidence=${result.confidence})",
                        now,
                        result.confidence,
                    )
                }
            }
            is RecognitionResult.Unknown -> {
                onEvent(ActivityEventType.UNKNOWN_USER, null)
                applyPolicy(settings.unknownUserPolicy, "unknown user", now, result.confidence)
            }
            is RecognitionResult.CameraPossiblyObstructed ->
                applyPolicy(settings.unknownUserPolicy, "camera possibly obstructed", now, null)
            is RecognitionResult.UnstableRecognition ->
                // hold current state; hysteresis keeps us from flickering
                transition(_state.value, "unstable recognition; holding state", now)
            RecognitionResult.NoFace -> when (settings.noFacePolicy) {
                BlockPolicy.ALLOW -> beginRecovery(now)
                BlockPolicy.SOFT_BLOCK -> applyBlock(ProtectionState.SOFT_BLOCKED, "no face; soft block", now, null)
                BlockPolicy.HARD_BLOCK -> applyBlock(ProtectionState.HARD_BLOCKED, "no face; hard block", now, null)
            }
        }
    }

    /** Enrich raw results with obstruction + instability classification. */
    private fun classify(raw: RecognitionResult): RecognitionResult {
        if (raw is RecognitionResult.NoFace) {
            emptyFaceStreak += 1
            if (emptyFaceStreak >= obstructionStreakLimit) return RecognitionResult.CameraPossiblyObstructed
            return raw
        }
        emptyFaceStreak = 0
        val confidence = when (raw) {
            is RecognitionResult.ParentRecognized -> raw.confidence
            is RecognitionResult.ChildRecognized -> raw.confidence
            is RecognitionResult.Unknown -> raw.confidence
            else -> null
        }
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

    private fun applyPolicy(policy: BlockPolicy, reason: String, now: Long, confidence: Double?) {
        when (policy) {
            BlockPolicy.ALLOW -> Unit
            BlockPolicy.SOFT_BLOCK -> applyBlock(ProtectionState.SOFT_BLOCKED, "$reason; soft block policy", now, confidence)
            BlockPolicy.HARD_BLOCK -> applyBlock(ProtectionState.HARD_BLOCKED, "$reason; hard block policy", now, confidence)
        }
    }

    private fun applyBlock(target: ProtectionState, reason: String, now: Long, confidence: Double?) {
        if (_state.value == target) return
        transition(target, reason, now, confidence)
        when (target) {
            ProtectionState.SOFT_BLOCKED -> applySoftBlock()
            ProtectionState.HARD_BLOCKED -> applyHardBlock()
            else -> Unit
        }
    }

    /** A recognized face was lost: hold the block for the recovery delay, then release. */
    private fun beginRecovery(now: Long) {
        if (_state.value == ProtectionState.UNPROTECTED || _state.value == ProtectionState.RECOVERING) return
        transition(ProtectionState.RECOVERING, "face lost; recovery window", now)
        val delayMs = settings.recoveryDelayMs
        scope?.launch {
            delay(delayMs)
            if (_state.value == ProtectionState.RECOVERING) {
                transition(ProtectionState.UNPROTECTED, "recovery delay elapsed", System.currentTimeMillis())
                clearBlock()
            }
        }
    }

    private fun resetTrackers() {
        pending.clear()
        emptyFaceStreak = 0
        recentConfidences.clear()
    }

    private fun transition(newState: ProtectionState, reason: String, now: Long, confidence: Double? = null) {
        _state.value = newState
        _decision.value = ProtectionDecision(newState, reason, confidence)
        lastStableAt = now
    }

    private fun applyHardBlock() {
        overlay.show()
        muteMedia()
    }

    private fun applySoftBlock() {
        overlay.show()
    }

    private fun clearBlock() {
        overlay.hide()
        unmuteMedia()
    }

    /** best-effort volume mute; modern Android routes through policy */
    private fun muteMedia() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        } else {
            @Suppress("DEPRECATION")
            audio.setStreamMute(AudioManager.STREAM_MUSIC, true)
        }
    }

    private fun unmuteMedia() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } else {
            @Suppress("DEPRECATION")
            audio.setStreamMute(AudioManager.STREAM_MUSIC, false)
        }
    }

    interface OverlayController {
        fun show()
        fun hide()
    }

    /** PIN-based parent emergency unlock; caller validates against stored PIN. */
    fun emergencyUnlock() {
        onEvent(ActivityEventType.EMERGENCY_UNLOCK, null)
        transition(ProtectionState.UNPROTECTED, "emergency unlock", System.currentTimeMillis())
        clearBlock()
    }

    private companion object {
        const val TICK_MS = 500L
        const val FRAME_TTL_MS = 1_500L
    }
}
