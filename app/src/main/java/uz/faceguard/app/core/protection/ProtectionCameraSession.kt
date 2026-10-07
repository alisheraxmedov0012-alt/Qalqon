package uz.faceguard.app.core.protection

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
 * Stage 5 adds two reliability rules on top of the ownership rules:
 *
 *  - **Screen suspend/resume.** When the screen turns off the *binding* is
 *    released (battery + the platform's screen-off camera behaviour) while the
 *    session and the foreground service's camera type stay latched; when the
 *    screen comes back the binding is re-established. This never restarts the
 *    foreground service, so it stays inside Android's background-camera rules.
 *  - **Bounded recovery.** A failed bind (camera in use by another app, a
 *    platform error) is retried with [CameraRecoveryBackoff] — bounded, growing
 *    delays, reset on success — so one bad camera moment cannot silently disable
 *    recognition, and cannot become an unbounded retry loop either.
 *
 * `start`/`stop`/`suspendBinding`/`resumeBinding` are idempotent, so a duplicate
 * activation can never open a second session and a duplicate stop can never
 * double-release CameraX resources; [active] exposes the single source of truth
 * for that state.
 */
@Singleton
class ProtectionCameraSession @Inject constructor(
    private val binding: CameraSessionBinding,
    /**
     * Q-1 fix: the process-scoped invalidation/rebind hook shared with the transient
     * camera consumers (enrollment / debug). Defaulted so the session can be unit
     * tested without DI; production always injects the singleton.
     */
    private val coordinator: CameraBindingCoordinator = CameraBindingCoordinator(),
) {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /**
     * Stage 5: true while a failed binding is being retried, or the bounded retries
     * are exhausted and recognition is limited until the next legal resume. Drives
     * honest capability reporting; never a blocking decision.
     */
    private val _recovering = MutableStateFlow(false)
    val recovering: StateFlow<Boolean> = _recovering

    val isActive: Boolean get() = _active.value

    /** True between [suspendBinding] and [resumeBinding]: latched, but the camera is released. */
    @Volatile
    private var bindingSuspended = false
    val isBindingSuspended: Boolean get() = bindingSuspended

    /** Bounded rebind policy; replaced by [attachRecoveryScope] when the caller supplies one. */
    private var recoveryBackoff = CameraRecoveryBackoff()
    private var recoveryScope: CoroutineScope? = null
    private var recoveryJob: Job? = null

    private val lock = Any()

    init {
        // The bind outcome is asynchronous; the session reacts to failure here.
        binding.setBindStateListener { bound -> onBindState(bound) }
    }

    /** Idempotent. Returns true when this call actually opened the session. */
    fun start(): Boolean {
        if (_active.value) return false
        // Mark active *before* binding, so a bind that fails (or even reports
        // synchronously) is handled as a live-session failure rather than ignored.
        _active.value = true
        bindingSuspended = false
        // Q-1: while protection holds the camera, publish the rebind hook so a
        // transient consumer that evicts it (enrollment/debug calls unbindAll()) can
        // hand the camera back through the coordinator.
        coordinator.registerProtectionRebind { refresh() }
        binding.start()
        return true
    }

    /** Idempotent. Returns true when this call actually closed the session. */
    fun stop(): Boolean {
        if (!_active.value) return false
        // Protection no longer wants the camera: drop the hook first so a later
        // transient release can never resurrect the session.
        coordinator.clearProtectionRebind()
        if (!bindingSuspended) binding.stop()
        bindingSuspended = false
        _active.value = false
        cancelRecovery()
        return true
    }

    /**
     * Q-1 fix: re-establishes the CameraX binding after a transient consumer released
     * the shared camera and evicted the protection analyzer. A no-op unless the session
     * is still active, so protection is never resurrected by a screen that merely
     * closed. The teardown/rebuild goes through the same [CameraSessionBinding], so it
     * can never create a second analyzer or leak the previous lifecycle owner.
     */
    fun refresh() {
        if (!_active.value) return
        // A screen-off suspension must not be undone by a transient release.
        if (bindingSuspended) return
        binding.stop()
        binding.start()
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

    // ------------------------------------------------------------ Stage 5

    /**
     * Attaches the scope the bounded recovery retries run on. Called by the
     * foreground service with its main-dispatcher scope. [backoff] is injected by
     * tests; production uses the default bounded schedule.
     */
    fun attachRecoveryScope(
        scope: CoroutineScope,
        backoff: CameraRecoveryBackoff = CameraRecoveryBackoff(),
    ) {
        recoveryScope = scope
        recoveryBackoff = backoff
    }

    /**
     * Screen off / screen on. Off releases the camera binding (keeping the session
     * and camera FGS type latched); on re-binds it, or resumes an interrupted
     * recovery. Safe to call when the session is inactive (a no-op).
     */
    fun onScreenStateChanged(screenOn: Boolean) {
        if (!_active.value) return
        if (screenOn) {
            if (bindingSuspended) {
                resumeBinding()
            } else if (_recovering.value) {
                retryNow()
            }
        } else {
            suspendBinding()
        }
    }

    /** Releases the camera binding while keeping the session latched. Idempotent. */
    fun suspendBinding(): Boolean {
        if (!_active.value || bindingSuspended) return false
        binding.stop()
        bindingSuspended = true
        // A screen-off suspension supersedes any in-flight retry.
        cancelRecovery()
        return true
    }

    /** Re-establishes the camera binding after [suspendBinding]. Idempotent. */
    fun resumeBinding(): Boolean {
        if (!_active.value || !bindingSuspended) return false
        bindingSuspended = false
        recoveryBackoff.reset()
        _recovering.value = false
        binding.start()
        return true
    }

    /**
     * Explicitly restarts a failed binding: resets the bounded counter and rebinds.
     * Used after the screen comes back or when the user returns to the app, so a
     * camera that was unavailable for a while gets a fresh, full retry budget.
     */
    fun retryNow() {
        if (!_active.value || bindingSuspended) return
        cancelRecovery()
        refresh()
    }

    private fun onBindState(bound: Boolean) {
        if (!_active.value) return
        if (bound) {
            cancelRecovery()
        } else if (!bindingSuspended) {
            scheduleRecovery()
        }
    }

    /**
     * Arms at most one retry timer. Bounded by [CameraRecoveryBackoff]; once the
     * bound is reached [recovering] stays true (recognition is limited) until a
     * successful rebind or an explicit [retryNow].
     */
    private fun scheduleRecovery() {
        _recovering.value = true
        synchronized(lock) {
            if (recoveryJob != null) return
            val delayMs = recoveryBackoff.nextDelayMs() ?: return
            val scope = recoveryScope ?: return
            recoveryJob = scope.launch {
                delay(delayMs)
                synchronized(lock) { recoveryJob = null }
                // Only the still-live, still-wanted session may rebind.
                if (_active.value && !bindingSuspended) refresh()
            }
        }
    }

    /** Cancels any pending retry and clears the recovering state. */
    private fun cancelRecovery() {
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryBackoff.reset()
        _recovering.value = false
    }
}
