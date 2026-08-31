package uz.faceguard.app.core.protection

import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import uz.faceguard.app.core.monitor.ForegroundAppMonitor
import uz.faceguard.app.core.pipeline.FrameEvent
import uz.faceguard.app.core.recognition.RecognitionResult
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.core.scan.ScanScheduler
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.model.ScanMode

/** Stable overlay state; transitions gated by debounce + recovery delay. */
enum class ProtectionState { UNPROTECTED, SOFT_BLOCKED, HARD_BLOCKED, RECOVERING }

data class ProtectionDecision(
    val state: ProtectionState,
    val reason: String,
    val confidence: Double? = null,
)

/**
 * One engine per protection session. Boundary:
 *   monitor.current --+
 *   recognizer frame --+--> evaluate() --> overlay + audio effect
 *   settings policy --+'
 *
 * Resilience: multi-frame confirmation + hysteresis prevent flicker;
 * CameraPossiblyObstructed while a protected app is active is treated as a
 * risk state and follows the unknown-user policy (fail-safe toward blocking).
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

    /** debounce window before a state switch is allowed */
    private val debounceMs = 1_200L
    /** Recovery window after a recognized face is lost; set from settings. */
    private var recoveryDelayMs = 3_000L
    fun setRecoveryDelay(ms: Long) { recoveryDelayMs = ms }

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

    /** Activity-log hook; set by the caller (VM) to persist events. */
    var onEvent: (ActivityEventType, String?) -> Unit = { _, _ -> }

    fun start(scope: CoroutineScope, policy: () -> BlockPolicy, scanMode: () -> ScanMode) {
        scanScheduler?.attach(scope)
        scanScheduler?.setMode(scanMode())
        scope.launch {
            combine(monitor.current, recognizer.frames, settingsFlow(policy)) { fg, frame, pol ->
                if (fg != null && fg in protectedPackages) {
                    if (!lastForegroundProtected) onEvent(ActivityEventType.PROTECTED_APP_ENTERED, fg)
                    scanScheduler?.onProtectedAppOpened()
                }
                evaluate(fg, frame, pol)
            }.collect { }
        }
    }

    private var scope: CoroutineScope? = null
    fun attach(scope: CoroutineScope) { this.scope = scope }

    fun evaluate(foreground: String?, frame: FrameEvent?, policy: BlockPolicy) {
        val protectedNow = foreground != null && foreground in protectedPackages
        val now = System.currentTimeMillis()
        if (!protectedNow) {
            if (_state.value != ProtectionState.UNPROTECTED) {
                transition(ProtectionState.UNPROTECTED, "no protected app in foreground", now)
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
        if (scanScheduler?.scanning?.value == false) return

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
                    transition(ProtectionState.UNPROTECTED, "parent recognized (confidence=${result.confidence})", now, result.confidence)
                    clearBlock()
                }
            }
            is RecognitionResult.ChildRecognized -> {
                onEvent(ActivityEventType.CHILD_RECOGNIZED, result.childName)
                if (_state.value == ProtectionState.UNPROTECTED) {
                    onEvent(ActivityEventType.CHILD_BLOCKED, result.childName)
                    transition(ProtectionState.HARD_BLOCKED, "child recognized (confidence=${result.confidence})", now, result.confidence)
                    applyHardBlock()
                }
            }
            is RecognitionResult.Unknown -> { onEvent(ActivityEventType.UNKNOWN_USER, null); applyFallback(policy, "unknown user", now, result.confidence) }
            is RecognitionResult.CameraPossiblyObstructed -> applyFallback(policy, "camera possibly obstructed", now, null)
            is RecognitionResult.UnstableRecognition -> {
                // hold current state; hysteresis keeps us from flickering
                transition(_state.value, "unstable recognition; holding state", now)
            }
            RecognitionResult.NoFace -> {
                if (_state.value != ProtectionState.UNPROTECTED && _state.value != ProtectionState.RECOVERING) {
                    transition(ProtectionState.RECOVERING, "face lost; recovery window", now)
                    scope?.launch {
                        delay(recoveryDelayMs)
                        if (_state.value == ProtectionState.RECOVERING) {
                            transition(ProtectionState.UNPROTECTED, "recovery delay elapsed", System.currentTimeMillis())
                            clearBlock()
                        }
                    }
                }
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

    private fun applyFallback(policy: BlockPolicy, reason: String, now: Long, confidence: Double?) {
        when (policy) {
            BlockPolicy.ALLOW -> { /* stay */ }
            BlockPolicy.SOFT_BLOCK -> {
                if (_state.value == ProtectionState.UNPROTECTED) {
                    transition(ProtectionState.SOFT_BLOCKED, "$reason; soft block policy", now, confidence)
                    applySoftBlock()
                }
            }
            BlockPolicy.HARD_BLOCK -> {
                if (_state.value == ProtectionState.UNPROTECTED) {
                    transition(ProtectionState.HARD_BLOCKED, "$reason; hard block policy", now, confidence)
                    applyHardBlock()
                }
            }
        }
    }

    private fun resetTrackers() {
        pending.clear()
        emptyFaceStreak = 0
        recentConfidences.clear()
    }

    private var parent: ParentProfile? = null
    private var children: List<ChildProfile> = emptyList()
    private val protectedPackages = mutableSetOf<String>()

    fun updateContext(parent: ParentProfile?, children: List<ChildProfile>, protected: Set<String>) {
        this.parent = parent
        this.children = children
        this.protectedPackages.clear()
        this.protectedPackages.addAll(protected)
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

    private fun settingsFlow(policy: () -> BlockPolicy) =
        kotlinx.coroutines.flow.flow { emit(policy()) }

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
}
