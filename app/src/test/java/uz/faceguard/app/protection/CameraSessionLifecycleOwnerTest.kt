package uz.faceguard.app.protection

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.pipeline.CameraSessionLifecycleOwner

/**
 * Phase 7.1: the process-owned lifecycle that replaces the Activity lifecycle as
 * CameraX's owner. It must be a real lifecycle (CameraX binds on RESUME and
 * releases on DESTROY), and it must never be reused after destroy.
 */
class CameraSessionLifecycleOwnerTest {

    @Test
    fun start_drivesCreateStartResume_andRunning() {
        val owner = CameraSessionLifecycleOwner()
        val events = mutableListOf<Lifecycle.Event>()
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event -> events += event })

        owner.start()

        assertEquals(
            listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME),
            events,
        )
        assertTrue(owner.isRunning)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
    }

    @Test
    fun stop_drivesPauseStopDestroy_andStopsRunning() {
        val owner = CameraSessionLifecycleOwner()
        val events = mutableListOf<Lifecycle.Event>()
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event -> events += event })
        owner.start()
        events.clear()

        owner.stop()

        assertEquals(
            listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY),
            events,
        )
        assertFalse(owner.isRunning)
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test
    fun stop_isIdempotent_andNeverThrows() {
        val owner = CameraSessionLifecycleOwner()
        owner.start()
        owner.stop()
        owner.stop()
        assertFalse(owner.isRunning)
    }

    @Test
    fun aDestroyedOwnerIsNeverRevived() {
        val owner = CameraSessionLifecycleOwner()
        owner.start()
        owner.stop()

        owner.start()

        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
        assertFalse(owner.isRunning)
    }
}
