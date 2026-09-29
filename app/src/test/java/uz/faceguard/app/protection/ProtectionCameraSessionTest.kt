package uz.faceguard.app.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.CameraBindingCoordinator
import uz.faceguard.app.core.protection.CameraSessionBinding
import uz.faceguard.app.core.protection.ProtectionCameraSession

/** Records binding interactions so the session's ownership rules can be asserted. */
private class FakeCameraSessionBinding : CameraSessionBinding {
    var startCount = 0
    var stopCount = 0

    /** Number of bindings assumed live at once; must never exceed 1. */
    var liveBindings = 0
        private set

    override fun start() {
        startCount++
        liveBindings++
    }

    override fun stop() {
        stopCount++
        if (liveBindings > 0) liveBindings--
    }
}

/**
 * Phase 7.1: the process-scoped camera session's ownership contract — idempotent
 * open/close, no duplicate session, and the while-in-use latch that keeps the
 * camera alive after the Protection screen closes.
 *
 * Q-1: the same file also covers the transient-consumer rebind contract (see the
 * dedicated section at the end), since both are properties of this session.
 */
class ProtectionCameraSessionTest {

    private val binding = FakeCameraSessionBinding()
    private val coordinator = CameraBindingCoordinator()
    private val session = ProtectionCameraSession(binding, coordinator)

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

    // ---------------------------------------------------------------------
    // Q-1: transient-consumer (enrollment/debug) camera rebind contract
    //
    // The shared process-wide CameraX provider is evicted by a transient screen's
    // `unbindAll()`. When that screen releases the camera it announces it through
    // the coordinator; protection must re-establish its binding if — and only if —
    // it is still active.
    // ---------------------------------------------------------------------

    /** Test 1: active session regains its binding after a transient release. */
    @Test
    fun activeSession_rebindsAfterATransientConsumerReleasesTheCamera() {
        session.start()
        assertTrue(session.isActive)
        assertEquals(1, binding.startCount)

        // Enrollment opened (evicted us) and has now closed.
        coordinator.onTransientCameraReleased()

        assertTrue("session must stay active across the eviction", session.isActive)
        assertEquals("the binding must be re-established", 1, binding.stopCount)
        assertEquals("exactly one rebuild", 2, binding.startCount)
        assertEquals("never more than one live binding", 1, binding.liveBindings)
    }

    /** Test 2: no transient screen => a stale release must not start protection. */
    @Test
    fun inactiveSession_ignoresATransientRelease() {
        assertFalse(session.isActive)

        coordinator.onTransientCameraReleased()

        assertFalse(session.isActive)
        assertEquals(0, binding.startCount)
    }

    /** Test 3: repeated enrollment open/close never leaves two live bindings. */
    @Test
    fun repeatedTransientReleases_neverCreateDuplicateBindings() {
        session.start()

        repeat(5) { coordinator.onTransientCameraReleased() }

        assertTrue(session.isActive)
        assertEquals("one live binding at all times", 1, binding.liveBindings)
        assertEquals("one stop per eviction", 5, binding.stopCount)
        assertEquals("initial bind plus one rebuild per eviction", 6, binding.startCount)
    }

    /** Test 4: repeated start() must not bind twice. */
    @Test
    fun repeatedStart_neverCreatesASecondBinding() {
        repeat(10) { session.start() }

        assertTrue(session.isActive)
        assertEquals(1, binding.startCount)
        assertEquals(1, binding.liveBindings)
    }

    /** Test 5: stop releases the binding and the session. */
    @Test
    fun stop_releasesTheBindingAndDeactivatesTheSession() {
        session.start()
        session.stop()

        assertFalse(session.isActive)
        assertEquals(1, binding.stopCount)
        assertEquals(0, binding.liveBindings)
    }

    /**
     * Test 6: a transient screen that closes *after* protection was disabled must not
     * resurrect protection. This is the ordering that matters in practice: protection
     * is turned off while enrollment is open, then enrollment closes.
     */
    @Test
    fun transientReleaseAfterProtectionDisabled_neverResurrectsProtection() {
        session.start()
        session.stop() // protection disabled while the transient screen was open

        coordinator.onTransientCameraReleased()

        assertFalse(session.isActive)
        assertEquals("no rebind may happen after stop()", 1, binding.startCount)
        assertEquals(0, binding.liveBindings)
    }

    /** refresh() outside an active session is a no-op (defensive). */
    @Test
    fun refreshWhileInactive_doesNothing() {
        session.refresh()

        assertFalse(session.isActive)
        assertEquals(0, binding.startCount)
        assertEquals(0, binding.stopCount)
    }

    /**
     * Stop must *first* make protection no longer the intended camera owner, so a
     * queued transient release can never race a fresh activation back to life.
     */
    @Test
    fun stopClearsTheRebindHook_soALaterReleaseCannotReviveIt() {
        session.start()
        session.stop()
        coordinator.onTransientCameraReleased()
        coordinator.onTransientCameraReleased()

        assertEquals(1, binding.startCount)
        assertEquals(1, binding.stopCount)
    }

    /** A later activation re-registers the hook, so the rebind contract resumes. */
    @Test
    fun reactivationAfterStop_restoresTheRebindContract() {
        session.start()
        session.stop()
        coordinator.onTransientCameraReleased() // ignored

        session.start()
        coordinator.onTransientCameraReleased() // honoured again

        assertTrue(session.isActive)
        assertEquals(3, binding.startCount) // first start, second start, rebuild
        assertEquals(1, binding.liveBindings)
    }
}
