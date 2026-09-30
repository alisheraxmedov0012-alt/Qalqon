package uz.faceguard.app.protection

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import uz.faceguard.app.core.protection.CameraBindingCoordinator

/**
 * Q-1: the process-scoped invalidation/rebind hook.
 *
 * It answers one question — "a transient camera consumer has released the shared
 * camera; is protection still the intended owner, and if so re-establish it?" — and
 * must never invoke a rebind that protection has not registered (which would let a
 * screen closing resurrect protection after it was disabled).
 */
class CameraBindingCoordinatorTest {

    private val coordinator = CameraBindingCoordinator()

    @Test
    fun anUnregisteredCoordinator_ignoresReleases() {
        // No protection owner: a transient release must be a harmless no-op.
        coordinator.onTransientCameraReleased()
        coordinator.onTransientCameraReleased()
    }

    @Test
    fun aRegisteredRebind_isInvokedOnEveryRelease() {
        val calls = AtomicInteger(0)
        coordinator.registerProtectionRebind { calls.incrementAndGet() }

        coordinator.onTransientCameraReleased()
        coordinator.onTransientCameraReleased()
        coordinator.onTransientCameraReleased()

        assertEquals(3, calls.get())
    }

    @Test
    fun clearingTheRebind_stopsFurtherInvocations() {
        val calls = AtomicInteger(0)
        coordinator.registerProtectionRebind { calls.incrementAndGet() }
        coordinator.onTransientCameraReleased()
        assertEquals(1, calls.get())

        coordinator.clearProtectionRebind()
        coordinator.onTransientCameraReleased()
        coordinator.onTransientCameraReleased()

        assertEquals("a cleared hook must never be invoked again", 1, calls.get())
    }

    @Test
    fun reRegistering_replacesThePreviousAction_soThereIsOneOwner() {
        val first = AtomicInteger(0)
        val second = AtomicInteger(0)
        coordinator.registerProtectionRebind { first.incrementAndGet() }
        coordinator.registerProtectionRebind { second.incrementAndGet() }

        coordinator.onTransientCameraReleased()

        assertEquals("the replaced action must no longer run", 0, first.get())
        assertEquals(1, second.get())
    }

    @Test
    fun clearingWithoutRegistering_isSafe() {
        coordinator.clearProtectionRebind()
        coordinator.clearProtectionRebind()
        coordinator.onTransientCameraReleased()
    }
}
