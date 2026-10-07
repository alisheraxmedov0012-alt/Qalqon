package uz.faceguard.app.protection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.monitor.ForegroundAppSource
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.core.protection.ProtectionActionExecutor
import uz.faceguard.app.core.protection.ProtectionEngine
import uz.faceguard.app.core.protection.ProtectionSettings
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Stage 5: a camera interruption must not turn stale recognition into a decision,
 * and must not silently release an active block (that would be fail-open).
 *
 * The engine is driven frame-lessly — the engine defines a missing frame as "no
 * face", which is exactly the path a covered/interrupted camera takes — so the
 * invariant is asserted deterministically without a real camera.
 */
class ProtectionEngineCameraInterruptionTest {

    private class FakeForeground : ForegroundAppSource {
        private val flow = MutableStateFlow<String?>(null)
        override val current: StateFlow<String?> = flow
        fun set(pkg: String?) { flow.value = pkg }
    }

    private class FakeExecutor : ProtectionActionExecutor {
        override val supportedActions: Set<ProtectionAction> = IMPLEMENTED_ACTIONS
        val executed = mutableListOf<ProtectionAction>()
        var clearCount = 0

        override fun execute(action: ProtectionAction) { executed += action }
        override fun clear() { clearCount++ }
        override fun mute() = Unit
        override fun unmute() = Unit
    }

    private val protectedPkg = "com.google.android.youtube"

    private fun engine(
        executor: FakeExecutor = FakeExecutor(),
        noFaceAction: ProtectionAction = ProtectionAction.SOFT_BLOCK,
    ): Triple<ProtectionEngine, FakeForeground, FakeExecutor> {
        val monitor = FakeForeground()
        val eng = ProtectionEngine(
            recognizer = Recognizer(),
            monitor = monitor,
            actions = executor,
            policyEvaluator = DefaultPolicyEvaluator(),
        )
        eng.updateContext(parent = null, children = emptyList(), protected = setOf(protectedPkg))
        eng.updateSettings(
            ProtectionSettings(),
            PolicySettings(
                enabled = true,
                activationDelayMs = 0L,
                noFaceAction = noFaceAction,
                recoveryDelayMs = 30_000L,
            ),
        )
        return Triple(eng, monitor, executor)
    }

    private class Driver(private val engine: ProtectionEngine, private val monitor: FakeForeground) {
        var now = 0L

        /** One frame-less tick (a missing frame = "no face"); advances past the debounce. */
        fun step(): ProtectionState {
            now += 2_000L
            engine.evaluate(monitor.current.value, frame = null, now = now)
            return engine.state.value
        }

        fun steps(count: Int): ProtectionState {
            repeat(count) { step() }
            return engine.state.value
        }
    }

    @Test
    fun anInterruptionDoesNotReleaseAnActiveBlock() {
        val executor = FakeExecutor()
        val (eng, monitor, _) = engine(executor)
        monitor.set(protectedPkg)
        val driver = Driver(eng, monitor)
        assertEquals(ProtectionState.SOFT_BLOCKED, driver.steps(3))
        val clearsBefore = executor.clearCount

        eng.onCameraInterrupted()

        assertEquals(
            "a lost camera must never release the block (fail-open)",
            ProtectionState.SOFT_BLOCKED,
            eng.state.value,
        )
        assertEquals("no overlay teardown on interruption", clearsBefore, executor.clearCount)
    }

    @Test
    fun anInterruptionResetsTheInProgressRecognitionConfirmation() {
        val (eng, monitor, _) = engine()
        monitor.set(protectedPkg)
        val driver = Driver(eng, monitor)

        // Two frame-less ticks: two confirmations queued, no decision yet.
        assertEquals(ProtectionState.UNPROTECTED, driver.steps(2))

        eng.onCameraInterrupted()

        // The third tick would have completed the confirmation without the reset.
        assertEquals(
            "stale confirmations must be dropped by an interruption",
            ProtectionState.UNPROTECTED,
            driver.step(),
        )
        // A fresh run of confirmations still reaches the decision.
        assertEquals(ProtectionState.SOFT_BLOCKED, driver.steps(2))
    }

    @Test
    fun anInterruptionClearsThePublishedIdentityAndLivenessSignals() {
        val (eng, _, _) = engine()
        eng.onCameraInterrupted()
        assertNull("a stale identity must not survive an interruption", eng.identity.value)
        assertNull("a stale liveness reading must not survive an interruption", eng.liveness.value)
    }

    @Test
    fun anInterruptionKeepsTheBlockedAppRestorationContext() {
        val (eng, monitor, _) = engine()
        monitor.set(protectedPkg)
        val driver = Driver(eng, monitor)
        driver.steps(3)
        assertEquals(protectedPkg, eng.blockedApp.value)

        eng.onCameraInterrupted()

        assertEquals(
            "the protection cycle's restoration target must stay valid",
            protectedPkg,
            eng.blockedApp.value,
        )
    }

    @Test
    fun anInterruptionIsSafeOnAnIdleEngine() {
        val (eng, _, executor) = engine()
        eng.onCameraInterrupted()
        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        assertEquals(0, executor.clearCount)
    }

    @Test
    fun aHardBlockKeepsFailingClosedAcrossAnInterruption() {
        val executor = FakeExecutor()
        val (eng, monitor, _) = engine(executor, noFaceAction = ProtectionAction.HARD_BLOCK)
        monitor.set(protectedPkg)
        val driver = Driver(eng, monitor)
        assertEquals(ProtectionState.HARD_BLOCKED, driver.steps(3))

        eng.onCameraInterrupted()

        assertEquals(
            "the fail-closed policy must keep holding with no camera",
            ProtectionState.HARD_BLOCKED,
            driver.steps(3),
        )
        assertSame(ProtectionAction.HARD_BLOCK, executor.executed.first())
        assertTrue("the block was never cleared", executor.clearCount == 0)
    }
}
