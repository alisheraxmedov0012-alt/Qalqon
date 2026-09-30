package uz.faceguard.app.protection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.CameraSessionPolicy

/**
 * Phase 7.1: the pure rule that decides whether the process-scoped camera session
 * should run. It captures the while-in-use latch: start only in a legal foreground
 * moment, then keep running after the UI goes away, and stop when protection ends
 * or the camera permission is lost.
 */
class CameraSessionPolicyTest {

    @Test
    fun startsOnlyInALegalForegroundMoment() {
        assertTrue(CameraSessionPolicy.shouldRun(true, uiForeground = true, cameraGranted = true, sessionRunning = false))
        assertFalse(CameraSessionPolicy.shouldRun(true, uiForeground = false, cameraGranted = true, sessionRunning = false))
        assertFalse(CameraSessionPolicy.shouldRun(false, uiForeground = true, cameraGranted = true, sessionRunning = false))
        assertFalse(CameraSessionPolicy.shouldRun(true, uiForeground = true, cameraGranted = false, sessionRunning = false))
    }

    @Test
    fun staysLatchedOnceRunning() {
        // The UI going away must not stop an already-running session.
        assertTrue(CameraSessionPolicy.shouldRun(true, uiForeground = false, cameraGranted = true, sessionRunning = true))
    }

    @Test
    fun stopsWhenTheReasonToRunIsGone() {
        assertFalse(CameraSessionPolicy.shouldRun(false, uiForeground = false, cameraGranted = true, sessionRunning = true))
        assertFalse(CameraSessionPolicy.shouldRun(true, uiForeground = true, cameraGranted = false, sessionRunning = true))
    }
}
