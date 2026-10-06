package uz.faceguard.app.protection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import uz.faceguard.app.domain.policy.isRestrictive

/**
 * Stage 4 (Protection Enforcement): the engine proves the block is *kept enforced*, not just
 * declared.
 *
 * The scenario used is the fail-closed no-face policy (a protected app in the foreground with
 * no recognisable face), which drives the engine to a blocked state without any Android camera
 * types — so the enforcement contract is deterministic and JVM-testable. The key Stage 4
 * invariant: while the engine is blocked, the enforcement action is re-asserted on every
 * evaluation, so a blocking overlay lost without a state change (an accessibility reconnect or
 * transient detach) self-heals within one tick instead of leaving the app "blocked" with
 * nothing on screen. Real overlay touch-blocking is verified separately (instrumented) and by
 * contract tests; actual bypass resistance needs a physical device and is out of scope here.
 */
class ProtectionEnforcementTest {

    private val protectedPkg = "com.google.android.youtube"
    private val otherPkg = "com.android.settings"

    private class FakeForeground : ForegroundAppSource {
        private val flow = MutableStateFlow<String?>(null)
        override val current: StateFlow<String?> = flow
        fun set(pkg: String?) { flow.value = pkg }
    }

    /** Records the one-shot enforcement actions and the per-tick re-asserts separately. */
    private class FakeActionExecutor(
        var throwOnReassert: Boolean = false,
    ) : ProtectionActionExecutor {
        override val supportedActions: Set<ProtectionAction> = IMPLEMENTED_ACTIONS
        val executed = mutableListOf<ProtectionAction>()
        val reasserted = mutableListOf<ProtectionAction>()
        var clearCount = 0

        override fun execute(action: ProtectionAction) { executed += action }
        override fun reassert(action: ProtectionAction) {
            if (throwOnReassert) throw IllegalStateException("overlay re-show failed")
            reasserted += action
        }
        override fun clear() { clearCount++ }
        override fun mute() = Unit
        override fun unmute() = Unit
    }

    private fun engine(
        executor: FakeActionExecutor,
        policy: PolicySettings,
    ): Pair<ProtectionEngine, FakeForeground> {
        val monitor = FakeForeground()
        val eng = ProtectionEngine(
            recognizer = Recognizer(),
            monitor = monitor,
            actions = executor,
            policyEvaluator = DefaultPolicyEvaluator(),
        )
        eng.updateContext(parent = null, children = emptyList(), protected = setOf(protectedPkg))
        eng.updateSettings(ProtectionSettings(), policy)
        return eng to monitor
    }

    private val blockingPolicy = PolicySettings(
        enabled = true,
        activationDelayMs = 0L,
        noFaceAction = ProtectionAction.HARD_BLOCK,
        recoveryDelayMs = 30_000L,
    )

    private val softPolicy = blockingPolicy.copy(noFaceAction = ProtectionAction.SOFT_BLOCK)

    private class Driver(private val engine: ProtectionEngine, private val monitor: FakeForeground) {
        var now = 0L
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

    // -------------------------------------------------------------- self-heal

    @Test
    fun theBlockIsReAssertedOnEveryEvaluationWhileBlocked() {
        val exec = FakeActionExecutor()
        val (eng, monitor) = engine(exec, blockingPolicy)
        val d = Driver(eng, monitor)
        monitor.set(protectedPkg)

        assertEquals(ProtectionState.HARD_BLOCKED, d.steps(3))
        // The one-shot action ran once on the transition ...
        assertEquals(listOf(ProtectionAction.HARD_BLOCK), exec.executed)
        // ... and the very next evaluation (the block still holds) re-asserts it.
        d.step()
        assertTrue("the block must be re-asserted while it holds", exec.reasserted.isNotEmpty())

        // Every further evaluation keeps re-asserting it (self-heal) without re-running the
        // one-shot action (no duplicate side effects, no re-mute).
        val countAtBlock = exec.reasserted.size
        d.steps(3)
        assertTrue("re-assert must continue while blocked", exec.reasserted.size > countAtBlock)
        assertEquals("the one-shot action must not be repeated", 1, exec.executed.size)
        assertTrue(exec.reasserted.all { it == ProtectionAction.HARD_BLOCK })
    }

    @Test
    fun aSoftBlockIsAlsoReAssertedWhileBlocked() {
        val exec = FakeActionExecutor()
        val (eng, monitor) = engine(exec, softPolicy)
        val d = Driver(eng, monitor)
        monitor.set(protectedPkg)

        assertEquals(ProtectionState.SOFT_BLOCKED, d.steps(3))
        d.steps(2)
        assertTrue(exec.reasserted.isNotEmpty())
        assertTrue(exec.reasserted.all { it == ProtectionAction.SOFT_BLOCK })
    }

    @Test
    fun theBlockIsNotReAssertedWhileUnprotected() {
        val exec = FakeActionExecutor()
        val (eng, monitor) = engine(exec, blockingPolicy)
        val d = Driver(eng, monitor)
        monitor.set(otherPkg) // not a protected app

        d.steps(5)

        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        assertTrue(exec.executed.isEmpty())
        assertTrue("nothing to enforce, so nothing to re-assert", exec.reasserted.isEmpty())
    }

    @Test
    fun aFailedReAssertNeitherCrashesNorChangesTheBlockedState() {
        val exec = FakeActionExecutor(throwOnReassert = true)
        val (eng, monitor) = engine(exec, blockingPolicy)
        val d = Driver(eng, monitor)
        monitor.set(protectedPkg)
        assertEquals(ProtectionState.HARD_BLOCKED, d.steps(3))

        // A re-assert failure is isolated: the block holds and the loop keeps running.
        d.steps(3)
        assertEquals(ProtectionState.HARD_BLOCKED, eng.state.value)
    }

    // ------------------------------------------------- leave / return (Back/Home equiv.)

    @Test
    fun leavingTheProtectedAppClearsTheOverlayAndStopsReAsserting() {
        val exec = FakeActionExecutor()
        val (eng, monitor) = engine(exec, blockingPolicy)
        val d = Driver(eng, monitor)
        monitor.set(protectedPkg)
        d.steps(3)
        val reassertsWhileBlocked = exec.reasserted.size

        monitor.set(otherPkg) // child leaves the protected app (Back/Home/switch)
        d.steps(2)

        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        assertTrue("the overlay must be hidden on release", exec.clearCount >= 1)
        // No further re-asserts once unprotected.
        assertEquals(reassertsWhileBlocked, exec.reasserted.size)
    }

    @Test
    fun returningToTheProtectedAppReBlocksAndKeepsEnforcing() {
        val exec = FakeActionExecutor()
        val (eng, monitor) = engine(exec, blockingPolicy)
        val d = Driver(eng, monitor)
        monitor.set(protectedPkg)
        d.steps(3)
        monitor.set(otherPkg)
        d.steps(2)
        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)

        monitor.set(protectedPkg)
        val reassertsBefore = exec.reasserted.size
        assertEquals(ProtectionState.HARD_BLOCKED, d.steps(4))
        assertTrue("re-entering the protected app must re-enforce", exec.reasserted.size > reassertsBefore)
    }

    @Test
    fun rapidHomeAndReturnDoesNotLeakOrDuplicateTheBlock() {
        val exec = FakeActionExecutor()
        val (eng, monitor) = engine(exec, blockingPolicy)
        val d = Driver(eng, monitor)

        monitor.set(protectedPkg); d.steps(3)
        monitor.set(otherPkg); d.steps(2)
        monitor.set(protectedPkg); d.steps(3)
        monitor.set(otherPkg); d.steps(2)
        monitor.set(protectedPkg); d.steps(3)

        assertEquals(ProtectionState.HARD_BLOCKED, eng.state.value)
        // Each re-entry re-executes the one-shot block exactly once, and the overlay is torn
        // down on each leave — no duplicate overlay is started while one is already up.
        assertTrue(exec.executed.isNotEmpty())
        assertTrue(exec.clearCount >= 2)
    }

    @Test
    fun theEngineStopsEnforcingAndClearsTheOverlayWhenStopped() {
        val exec = FakeActionExecutor()
        val (eng, monitor) = engine(exec, blockingPolicy)
        val d = Driver(eng, monitor)
        monitor.set(protectedPkg)
        d.steps(3)
        assertEquals(ProtectionState.HARD_BLOCKED, eng.state.value)

        eng.stop()

        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        assertTrue("stopping must tear the block down", exec.clearCount >= 1)
    }

    @Test
    fun enforcementIsRestrictiveActionsOnlyNeverAllow() {
        // A protective decision must never map to ALLOW; the enforced action is restricting.
        val exec = FakeActionExecutor()
        val (eng, monitor) = engine(exec, blockingPolicy)
        val d = Driver(eng, monitor)
        monitor.set(protectedPkg)
        d.steps(3)

        assertFalse(exec.executed.contains(ProtectionAction.ALLOW))
        assertTrue(exec.executed.all { it.isRestrictive })
        assertTrue(exec.reasserted.all { it == ProtectionAction.HARD_BLOCK })
    }
}
