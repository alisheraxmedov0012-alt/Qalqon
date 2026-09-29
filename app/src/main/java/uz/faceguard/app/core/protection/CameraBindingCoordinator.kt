package uz.faceguard.app.core.protection

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Q-1 fix: the process-scoped coordination point for the single shared front-camera
 * binding.
 *
 * CameraX's `ProcessCameraProvider` is process-wide and only one `ImageAnalysis` may
 * be bound to a camera at a time. The always-on protection session
 * ([ProtectionCameraSession]) and the transient camera consumers (face enrollment,
 * the developer recognition screen) therefore cannot hold the camera simultaneously:
 * a transient screen's `FaceCaptureController.start(...)` calls
 * `provider.unbindAll()`, which silently evicts the protection analyzer.
 *
 * This coordinator is the explicit *invalidation/rebind hook* that was missing. While
 * protection is holding the camera it registers a rebind action; a transient consumer
 * announces [onTransientCameraReleased] once it has released the shared camera, and the
 * protection binding is re-established if — and only if — protection is still active.
 *
 * It is deliberately tiny and side-effect free: it decides nothing about policy, holds
 * no camera object, and never touches CameraX itself. Both questions it answers
 * ("is protection currently the camera's intended owner?" and "re-establish it") stay
 * owned by [ProtectionCameraSession].
 */
@Singleton
class CameraBindingCoordinator @Inject constructor() {

    private val lock = Any()

    /** The protection session's rebind action while it holds the camera, else null. */
    private var onProtectionRebindNeeded: (() -> Unit)? = null

    /**
     * Registers the protection session's rebind action. Replaces any previous one, so
     * there is never more than one protection owner.
     */
    fun registerProtectionRebind(action: () -> Unit) {
        synchronized(lock) { onProtectionRebindNeeded = action }
    }

    /** Clears the hook (protection no longer holds — or no longer wants — the camera). */
    fun clearProtectionRebind() {
        synchronized(lock) { onProtectionRebindNeeded = null }
    }

    /**
     * A transient consumer (enrollment / debug) has released the shared camera. If the
     * protection session is still the intended owner, its binding is re-established.
     * A no-op when protection is inactive — protection must never be resurrected by a
     * screen that merely closed.
     */
    fun onTransientCameraReleased() {
        val action = synchronized(lock) { onProtectionRebindNeeded }
        action?.invoke()
    }
}
