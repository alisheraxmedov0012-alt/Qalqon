package uz.faceguard.app.core.protection

import android.media.AudioManager
import android.os.Build
import android.util.Log
import uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Executes a domain [ProtectionAction] on the platform.
 *
 * Separation:
 *   PolicyDecision -> ProtectionActionExecutor -> Overlay / Audio / (later Accessibility)
 *
 * Only [IMPLEMENTED_ACTIONS] are enforced. Domain-only actions
 * (DIM/BLUR/BLACK_SCREEN) are deliberately **not** faked — they are logged and
 * treated as "no platform effect yet" until a later step implements them.
 */
interface ProtectionActionExecutor {
    /** Actions this build can actually enforce. */
    val supportedActions: Set<ProtectionAction>

    fun execute(action: ProtectionAction)

    /**
     * Stage 4: re-assert the *enforcement surface* of [action] while the engine stays in a
     * blocked state, without repeating the one-shot side effects of [execute].
     *
     * This is the self-heal path: if the blocking overlay is lost without the engine's
     * state changing — an accessibility-service reconnect (new window owner), a transient
     * WindowManager detach, or the overlay removed out-of-band — neither [execute] nor
     * [clear] runs again, so without this the child would sit in front of an "enforced"
     * app with nothing actually blocking it. Re-asserting the overlay every evaluation
     * makes the block self-healing within one tick. It must be idempotent and cheap (no
     * re-mute, no re-log); the default is a no-op so an executor that owns no re-assertable
     * surface (e.g. a test double) is unaffected.
     */
    fun reassert(action: ProtectionAction) = Unit

    fun clear()

    fun mute()

    fun unmute()
}

/** Overlay + audio based executor built on the existing overlay controller. */
class OverlayProtectionActionExecutor(
    private val overlay: ProtectionEngine.OverlayController,
    private val audio: AudioManager,
) : ProtectionActionExecutor {

    override val supportedActions: Set<ProtectionAction> = IMPLEMENTED_ACTIONS

    override fun execute(action: ProtectionAction) {
        when (action) {
            ProtectionAction.ALLOW -> clear()

            ProtectionAction.SOFT_BLOCK -> safeOverlay { overlay.show() }

            ProtectionAction.HARD_BLOCK -> {
                safeOverlay { overlay.show() }
                mute()
            }

            ProtectionAction.MUTE -> mute()

            ProtectionAction.WARNING -> Unit // surfaced by the UI, nothing to render

            // No platform implementation yet — never pretend otherwise.
            ProtectionAction.DIM,
            ProtectionAction.BLUR,
            ProtectionAction.BLACK_SCREEN,
            -> Log.i(TAG, "action $action is domain-only in this build; no platform effect")
        }
    }

    /**
     * Stage 4 self-heal: re-show the blocking overlay for a restricting action. Idempotent
     * (the controller and the accessibility overlay are both idempotent) and deliberately
     * does **not** re-mute, so the per-evaluation call is cheap and never spams the audio
     * system or the log. A mute that drifted would be re-applied by the next real block.
     */
    override fun reassert(action: ProtectionAction) {
        when (action) {
            ProtectionAction.SOFT_BLOCK,
            ProtectionAction.HARD_BLOCK,
            -> safeOverlay { overlay.show() }

            else -> Unit
        }
    }

    override fun clear() {
        // Same isolation as mute/unmute below: a failing overlay teardown must not
        // crash the evaluation loop that calls this.
        safeOverlay { overlay.hide() }
        unmute()
    }

    /**
     * A blocking-window failure is a runtime condition (an invalid window token, a
     * window already gone), not a programming error: it is isolated and reported,
     * so it can never take the protection engine — and therefore the whole app —
     * down with it.
     */
    private inline fun safeOverlay(block: () -> Unit) {
        runCatching { block() }.onFailure { Log.w(TAG, "overlay action failed", it) }
    }

    override fun mute() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audio.setStreamMute(AudioManager.STREAM_MUSIC, true)
            }
        }.onFailure { Log.w(TAG, "mute failed", it) }
    }

    override fun unmute() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audio.setStreamMute(AudioManager.STREAM_MUSIC, false)
            }
        }.onFailure { Log.w(TAG, "unmute failed", it) }
    }

    private companion object {
        const val TAG = "ActionExecutor"
    }
}
