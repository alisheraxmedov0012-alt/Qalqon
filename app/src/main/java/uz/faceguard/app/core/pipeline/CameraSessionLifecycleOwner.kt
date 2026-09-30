package uz.faceguard.app.core.pipeline

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Phase 7.1: a process-owned [LifecycleOwner] for the protection camera session.
 *
 * CameraX binds use cases to a [LifecycleOwner]. The protection pipeline used to
 * borrow the Activity's lifecycle (through whichever screen happened to be
 * showing), which is exactly what made recognition stop when the Protection
 * screen closed. This owner belongs to the process-scoped session instead, so the
 * camera lives for as long as the session does.
 *
 * It is a real lifecycle, not a stub: [start] moves it to RESUMED (dispatching
 * CREATE -> START -> RESUME, which is what CameraX needs to bind) and [stop]
 * moves it to DESTROYED (dispatching PAUSE -> STOP -> DESTROY, which is what
 * releases the use cases). A stopped owner is never reused; the session creates a
 * fresh one per session, the same way an Activity is not restarted after destroy.
 *
 * [LifecycleRegistry.createUnsafe] is used because CameraX does not require the
 * transitions to happen on the main thread and the session is exercised off the
 * main looper by the unit tests. Production callers still drive it from the main
 * thread (the foreground service's main-dispatcher scope).
 */
class CameraSessionLifecycleOwner : LifecycleOwner {

    private val registry = LifecycleRegistry.createUnsafe(this)

    override val lifecycle: Lifecycle get() = registry

    /** True between [start] and [stop]; matches what CameraX needs to stay bound. */
    val isRunning: Boolean get() = registry.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private var destroyed = false

    /** Moves the process-owned lifecycle to RESUMED so CameraX can bind. */
    fun start() {
        if (destroyed) return
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** Moves the process-owned lifecycle to DESTROYED so CameraX releases its use cases. */
    fun stop() {
        if (destroyed) return
        destroyed = true
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
