package uz.faceguard.app.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.CameraSessionBinding
import uz.faceguard.app.core.protection.ProtectionCameraSession

/** Records binding interactions so the session's ownership rules can be asserted. */
private class FakeCameraSessionBinding : CameraSessionBinding {
    var startCount = 0
    var stopCount = 0

    override fun start() { startCount++ }
    override fun stop() { stopCount++ }
}

/**
 * Phase 7.1: the process-scoped camera session's ownership contract — idempotent
 * open/close, no duplicate session, and the while-in-use latch that keeps the
 * camera alive after the Protection screen closes.
 */
class ProtectionCameraSessionTest {

    private val binding = FakeCameraSessionBinding()
    private val session = ProtectionCameraSession(binding)

    @Test
    fun start_isIdempotent_andOpensExactlyOneSession() {
        assertTrue(session.start())
        assertFalse("a duplicate start must not open a second session", session.start())
        assertTrue(session.isActive)
        assertTrue(session.active.value)
        assertEquals(1, binding.startCount)
        assertEquals(0, binding.stopCount)
    }

    @Test
    fun stop_isIdempotent_andReleasesExactlyOnce() {
        session.start()
        assertTrue(session.stop())
        assertFalse("a duplicate stop must not release a second time", session.stop())
        assertFalse(session.isActive)
        assertFalse(session.active.value)
        assertEquals(1, binding.stopCount)
    }

    @Test
    fun stopBeforeStart_isANoOp() {
        assertFalse(session.stop())
        assertEquals(0, binding.stopCount)
    }

    @Test
    fun reconcile_doesNotStartOutsideALegalForegroundMoment() {
        session.reconcile(protectionActive = true, uiForeground = false, cameraGranted = true)
        assertFalse("must not start while the UI is not visible", session.isActive)

        session.reconcile(protectionActive = false, uiForeground = true, cameraGranted = true)
        assertFalse("must not start with protection inactive", session.isActive)

        session.reconcile(protectionActive = true, uiForeground = true, cameraGranted = false)
        assertFalse("must not start without the camera permission", session.isActive)

        assertEquals(0, binding.startCount)
    }

    @Test
    fun reconcile_startsInTheForegroundMoment() {
        session.reconcile(protectionActive = true, uiForeground = true, cameraGranted = true)
        assertTrue(session.isActive)
        assertEquals(1, binding.startCount)
    }

    @Test
    fun reconcile_keepsTheSessionLatched_whenTheUiLeaves() {
        session.reconcile(protectionActive = true, uiForeground = true, cameraGranted = true)
        assertTrue(session.isActive)

        // Screen closed / app backgrounded: the foreground service keeps it alive.
        session.reconcile(protectionActive = true, uiForeground = false, cameraGranted = true)

        assertTrue("closing the UI must not destroy the process camera session", session.isActive)
        assertEquals(1, binding.startCount)
        assertEquals(0, binding.stopCount)
    }

    @Test
    fun reconcile_stopsWhenProtectionIsDisabled() {
        session.reconcile(protectionActive = true, uiForeground = true, cameraGranted = true)
        session.reconcile(protectionActive = false, uiForeground = true, cameraGranted = true)
        assertFalse(session.isActive)
        assertEquals(1, binding.stopCount)
    }

    @Test
    fun reconcile_stopsWhenTheAccountIsInvalidated() {
        // Account invalidation surfaces as protection becoming inactive.
        session.reconcile(protectionActive = true, uiForeground = true, cameraGranted = true)
        session.reconcile(protectionActive = false, uiForeground = false, cameraGranted = true)
        assertFalse(session.isActive)
        assertEquals(1, binding.stopCount)
    }

    @Test
    fun reconcile_stopsWhenTheCameraPermissionIsLost() {
        session.reconcile(protectionActive = true, uiForeground = true, cameraGranted = true)
        session.reconcile(protectionActive = true, uiForeground = true, cameraGranted = false)
        assertFalse(session.isActive)
        assertEquals(1, binding.stopCount)
    }

    @Test
    fun repeatedActivationDeactivation_opensAndClosesOncePerCycle() {
        repeat(3) {
            session.reconcile(protectionActive = true, uiForeground = true, cameraGranted = true)
            assertTrue(session.isActive)
            session.reconcile(protectionActive = true, uiForeground = false, cameraGranted = true)
            assertTrue("UI closes, session must stay latched", session.isActive)
            session.reconcile(protectionActive = false, uiForeground = false, cameraGranted = true)
            assertFalse(session.isActive)
        }
        assertEquals("one start per activation cycle", 3, binding.startCount)
        assertEquals("one stop per deactivation cycle", 3, binding.stopCount)
    }

    @Test
    fun duplicateReconcileWhileRunning_neverRebinds() {
        session.reconcile(protectionActive = true, uiForeground = true, cameraGranted = true)
        repeat(10) {
            session.reconcile(protectionActive = true, uiForeground = true, cameraGranted = true)
        }
        assertEquals(1, binding.startCount)
        assertEquals(0, binding.stopCount)
    }
}
