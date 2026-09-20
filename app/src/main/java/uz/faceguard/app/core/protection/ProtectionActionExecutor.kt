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

            ProtectionAction.SOFT_BLOCK -> overlay.show()

            ProtectionAction.HARD_BLOCK -> {
                overlay.show()
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

    override fun clear() {
        overlay.hide()
        unmute()
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
