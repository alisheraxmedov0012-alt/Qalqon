package uz.faceguard.app.core.protection

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 7.1: the pure rule deciding whether the process-scoped camera session
 * should be running.
 *
 * The session must start only in a *legal while-in-use moment*: protection is
 * active, an activity is visible and the camera permission is held. Once running
 * it is **latched** — closing the UI must not destroy it, because the foreground
 * service (which claimed the camera type in that same foreground moment) is what
 * keeps camera access alive in the background.
 *
 * It stops only when the reason it existed is gone: protection deactivated
 * (disabled / signed out) or the camera permission lost.
 */
object CameraSessionPolicy {
    fun shouldRun(
        protectionActive: Boolean,
        uiForeground: Boolean,
        cameraGranted: Boolean,
        sessionRunning: Boolean,
    ): Boolean = protectionActive && cameraGranted && (sessionRunning || uiForeground)
}

/**
 * Phase 7.1: the process-scoped camera session — the single owner of the
 * protection camera.
 *
 * It is app-scoped like [ProtectionRuntime] and is driven by
 * [ProtectionForegroundService]: the service reconciles it from the runtime's
 * active state, the UI-foreground signal and the camera permission, so the camera
 * is started from a legal foreground moment and then survives the UI going away.
 *
 * `start`/`stop` are idempotent, so a duplicate activation can never open a
 * second session and a duplicate stop can never double-release CameraX resources;
 * [active] exposes the single source of truth for that state.
 */
@Singleton
class ProtectionCameraSession @Inject constructor(
    private val binding: CameraSessionBinding,
) {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    val isActive: Boolean get() = _active.value

    /** Idempotent. Returns true when this call actually opened the session. */
    fun start(): Boolean {
        if (_active.value) return false
        binding.start()
        _active.value = true
        return true
    }

    /** Idempotent. Returns true when this call actually closed the session. */
    fun stop(): Boolean {
        if (!_active.value) return false
        binding.stop()
        _active.value = false
        return true
    }

    /**
     * Reconciles the session with the process state. See [CameraSessionPolicy]:
     * it starts only in a legal foreground moment and, once running, keeps running
     * until protection ends or the camera permission is lost.
     */
    fun reconcile(protectionActive: Boolean, uiForeground: Boolean, cameraGranted: Boolean) {
        val shouldRun = CameraSessionPolicy.shouldRun(
            protectionActive = protectionActive,
            uiForeground = uiForeground,
            cameraGranted = cameraGranted,
            sessionRunning = _active.value,
        )
        if (shouldRun) start() else stop()
    }
}
