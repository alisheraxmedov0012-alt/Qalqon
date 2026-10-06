package uz.faceguard.app.protection

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.CameraBindingCoordinator
import uz.faceguard.app.core.protection.CameraRecoveryBackoff
import uz.faceguard.app.core.protection.CameraSessionBinding
import uz.faceguard.app.core.protection.ProtectionCameraSession

/**
 * A dispatcher that never runs a coroutine until the test drains it, so the
 * bounded recovery is exercised step by step with **no** wall-clock wait. Combined
 * with a zero-delay backoff schedule, each drain advances exactly one retry.
 */
private class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    val pending: Int get() = queue.size

    /** Runs queued continuations (bounded) until none remain or [max] steps are taken. */
    fun drain(max: Int = 100) {
        var steps = 0
        while (steps < max) {
            val next = queue.removeFirstOrNull() ?: return
            next.run()
            steps++
        }
    }
}

/**
 * A camera binding whose bind outcome can be forced, so the session's bounded
 * recovery and screen suspend/resume rules are asserted without a real camera.
 */
private class FakeBinding : CameraSessionBinding {
    var startCount = 0
        private set
    var stopCount = 0
        private set

    /** Number of upcoming [start] calls that fail (camera unavailable) instead of binding. */
    var failNextStarts = 0

    /** When false, [start] records the bind but does not report success. */
    var autoNotify = true

    /** Live bindings assumed at once; the session must never exceed one. */
    var liveBindings = 0
        private set
    var maxLiveBindings = 0
        private set

    private var listener: ((Boolean) -> Unit)? = null

    override fun start() {
        startCount++
        if (failNextStarts > 0) {
            failNextStarts--
            if (autoNotify) listener?.invoke(false)
            return
        }
        liveBindings++
        if (liveBindings > maxLiveBindings) maxLiveBindings = liveBindings
        if (autoNotify) listener?.invoke(true)
    }

    override fun stop() {
        stopCount++
        if (liveBindings > 0) liveBindings--
    }

    override fun setBindStateListener(listener: (Boolean) -> Unit) {
        this.listener = listener
    }

    /** Simulates an asynchronous bind-result report. */
    fun reportBound(bound: Boolean) {
        listener?.invoke(bound)
    }
}

/**
 * Stage 5: the process-scoped camera session's recovery and screen-state rules.
 *
 * Complements [ProtectionCameraSessionTest] (ownership/latch) with the reliability
 * behaviour: a failed bind is retried a bounded number of times, one retry at a
 * time, reset on success; the screen turning off releases the camera without
 * ending the session; the screen coming back rebinds it. The retry schedule runs on
 * a [ManualDispatcher] with zero delays, so every step is deterministic — no sleeps,
 * no flakiness.
 */
class ProtectionCameraSessionRecoveryTest {

    private val binding = FakeBinding()
    private val coordinator = CameraBindingCoordinator()
    private val session = ProtectionCameraSession(binding, coordinator)
    private val dispatcher = ManualDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Zero-delay schedule: each [ManualDispatcher.drain] advances exactly one retry. */
    private fun attach(delays: List<Long> = listOf(0L, 0L, 0L, 0L, 0L)) {
        session.attachRecoveryScope(scope, CameraRecoveryBackoff(delays))
    }

    // ------------------------------------------------------------ recovery

    @Test
    fun failedBind_isRetried_andASuccessfulRebindClearsRecovering() {
        attach()
        binding.failNextStarts = 1

        assertTrue(session.start())
        assertTrue("a failed bind must report recognition as limited", session.recovering.value)
        assertEquals("exactly one retry is armed", 1, dispatcher.pending)

        dispatcher.drain()

        assertFalse("a successful rebind clears the recovering state", session.recovering.value)
        assertEquals(2, binding.startCount)
        assertEquals("exactly one live binding", 1, binding.liveBindings)
    }

    @Test
    fun repeatedFailuresAreBounded_andThenReportLimited() {
        attach(listOf(0L, 0L))
        binding.failNextStarts = 10

        session.start() // start 1 fails -> retry 2 -> retry 3 -> budget exhausted
        dispatcher.drain()

        assertEquals("retries must be bounded", 3, binding.startCount)
        assertTrue("exhausted recovery reports recognition as limited", session.recovering.value)
        assertEquals("no timer may remain after exhaustion", 0, dispatcher.pending)
    }

    @Test
    fun repeatedFailureSignals_neverCreateASecondRetryTimer() {
        attach()
        binding.autoNotify = false
        session.start()

        // Three "bind failed" reports must arm exactly one retry.
        binding.reportBound(false)
        binding.reportBound(false)
        binding.reportBound(false)
        assertTrue(session.recovering.value)
        assertEquals("only one retry timer for a burst of failures", 1, dispatcher.pending)

        binding.autoNotify = true
        dispatcher.drain()
        assertEquals("only one retry ran", 2, binding.startCount)
    }

    @Test
    fun aSuccessfulBindResetsTheBudget_forALaterInterruption() {
        attach(listOf(0L, 0L))
        binding.failNextStarts = 1
        session.start()
        dispatcher.drain() // first failure then recovery success

        // A brand-new, independent failure must start from the shortest delay again.
        binding.failNextStarts = 1
        session.retryNow()
        assertTrue(session.recovering.value)
        dispatcher.drain()
        assertFalse(session.recovering.value)
        assertEquals(1, binding.maxLiveBindings)
    }

    @Test
    fun bindFailureOnAnInactiveSession_isIgnored() {
        attach()
        binding.reportBound(false)
        assertFalse(session.recovering.value)
        assertEquals(0, dispatcher.pending)
        assertEquals(0, binding.startCount)
    }

    @Test
    fun stop_cancelsAPendingRetry() {
        attach()
        binding.failNextStarts = 1
        session.start()
        assertTrue(session.recovering.value)

        session.stop()
        dispatcher.drain()

        assertFalse(session.recovering.value)
        assertEquals("no retry may run after stop", 1, binding.startCount)
    }

    @Test
    fun retryNow_resetsAnExhaustedBudgetAndRebinds() {
        attach(listOf(0L))
        binding.failNextStarts = 10
        session.start()
        dispatcher.drain() // first failure + one bounded retry = exhausted
        assertTrue(session.recovering.value)
        assertEquals(2, binding.startCount)

        binding.failNextStarts = 0
        session.retryNow()
        dispatcher.drain()

        assertFalse("an explicit retry must rebind", session.recovering.value)
        assertEquals(3, binding.startCount)
        assertEquals(1, binding.maxLiveBindings)
    }

    @Test
    fun recoveryNeverLeavesMoreThanOneLiveBinding() {
        attach(listOf(0L, 0L, 0L))
        session.start()
        repeat(3) {
            binding.failNextStarts = 1
            session.retryNow()
            dispatcher.drain()
        }
        assertTrue("a duplicate analyzer must never exist", binding.maxLiveBindings <= 1)
    }

    // ------------------------------------------------------- screen state

    @Test
    fun screenOff_releasesTheCameraButKeepsTheSessionLatched() {
        attach()
        session.start()

        session.onScreenStateChanged(screenOn = false)

        assertTrue("the session (and its camera FGS type) stays latched", session.isActive)
        assertTrue(session.isBindingSuspended)
        assertEquals("the camera is released on screen-off", 1, binding.stopCount)
    }

    @Test
    fun screenOn_rebindsAReleasedCamera() {
        attach()
        session.start()
        session.onScreenStateChanged(screenOn = false)
        val startsAfterSuspend = binding.startCount

        session.onScreenStateChanged(screenOn = true)

        assertFalse(session.isBindingSuspended)
        assertTrue(binding.startCount > startsAfterSuspend)
        assertEquals(1, binding.liveBindings)
    }

    @Test
    fun suspendBinding_isIdempotent() {
        attach()
        session.start()
        assertTrue(session.suspendBinding())
        assertFalse("a duplicate suspend releases nothing more", session.suspendBinding())
        assertEquals(1, binding.stopCount)
    }

    @Test
    fun resumeBinding_isANoOpWhenNotSuspended() {
        attach()
        session.start()
        val starts = binding.startCount
        assertFalse(session.resumeBinding())
        assertEquals(starts, binding.startCount)
    }

    @Test
    fun screenOffDuringRecovery_cancelsTheRetry() {
        attach()
        binding.failNextStarts = 1
        session.start()
        assertTrue(session.recovering.value)

        session.onScreenStateChanged(screenOn = false)
        dispatcher.drain()

        assertFalse(session.recovering.value)
        assertTrue(session.isBindingSuspended)
        assertEquals("a screen-off supersedes the pending retry", 1, binding.startCount)
    }

    @Test
    fun screenOnAfterASuspendedRecovery_resumesWithAFreshBudget() {
        attach(listOf(0L, 0L))
        binding.failNextStarts = 1
        session.start() // start 1 fails and arms a retry
        session.onScreenStateChanged(screenOn = false) // cancels the retry, suspends

        session.onScreenStateChanged(screenOn = true) // resume rebinds (start 2)
        assertFalse(session.isBindingSuspended)
        assertFalse(session.recovering.value)
        assertEquals(2, binding.startCount)

        // The resume also reset the bounded budget, so a new failure still gets both attempts.
        binding.failNextStarts = 10
        session.retryNow()
        dispatcher.drain()
        assertEquals("start 3 + two bounded retries (4, 5)", 5, binding.startCount)
        assertTrue(session.recovering.value)
    }

    @Test
    fun aTransientConsumerRelease_cannotResurrectASuspendedCamera() {
        attach()
        session.start()
        session.onScreenStateChanged(screenOn = false)
        val startsAfterSuspend = binding.startCount

        // Enrollment/debug closes and announces the camera is free.
        coordinator.onTransientCameraReleased()

        assertTrue(session.isBindingSuspended)
        assertEquals("a screen-off camera must not be resurrected", startsAfterSuspend, binding.startCount)
    }

    @Test
    fun reconcile_toInactive_clearsAnySuspension() {
        attach()
        session.start()
        session.suspendBinding()
        assertTrue(session.isBindingSuspended)

        session.reconcile(protectionActive = false, uiForeground = false, cameraGranted = true)
        assertFalse(session.isActive)
        assertFalse(session.isBindingSuspended)
    }

    @Test
    fun inactiveSessionIgnoresScreenChanges() {
        attach()
        session.onScreenStateChanged(screenOn = false)
        session.onScreenStateChanged(screenOn = true)
        assertFalse(session.isActive)
        assertFalse(session.isBindingSuspended)
        assertEquals(0, binding.startCount)
    }
}
