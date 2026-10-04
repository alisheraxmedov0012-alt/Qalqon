package uz.faceguard.app.protection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.monitor.ForegroundAppSource
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.core.protection.ProtectionActionExecutor
import uz.faceguard.app.core.protection.ProtectionEngine
import uz.faceguard.app.core.protection.ProtectionSettings
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.policy.IMPLEMENTED_ACTIONS
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Stage 2 — Core Reliability: the protection engine's invariants, on the JVM.
 *
 * These are the invariants that must hold no matter what the phone is doing. They
 * used to be reachable only from emulator-backed instrumented tests because the
 * engine depended on the Android-coupled `ForegroundAppMonitor`; the engine now
 * depends on the read-only [ForegroundAppSource] contract, so the same state
 * machine can be driven deterministically here.
 *
 * The recognition *frame* path still requires a real `FrameEvent` (ML Kit
 * `InputImage`), so these tests drive the engine through the frame-less path,
 * which the engine defines as "no face" — the exact fail-closed path a covered or
 * unavailable camera takes. The identity matrix for parent/child/unknown stays in
 * the instrumented suite (`ProtectionEngineIdentityTest`), which runs in CI.
 *
 * Every wait here is an explicit virtual clock (`now`), never a wall-clock sleep.
 */
class ProtectionEngineReliabilityTest {

    // --------------------------------------------------------------- test doubles

    /** The narrow, Android-free foreground contract the engine reads. */
    private class FakeForeground : ForegroundAppSource {
        private val flow = MutableStateFlow<String?>(null)
        override val current: StateFlow<String?> = flow
        fun set(pkg: String?) { flow.value = pkg }
    }

    /** Records side effects and can be told to fail, like a real overlay would. */
    private class FakeActionExecutor(
        var throwOnExecute: Boolean = false,
        var throwOnClear: Boolean = false,
    ) : ProtectionActionExecutor {
        override val supportedActions: Set<ProtectionAction> = IMPLEMENTED_ACTIONS
        val executed = mutableListOf<ProtectionAction>()
        var clearCount = 0

        override fun execute(action: ProtectionAction) {
            if (throwOnExecute) throw IllegalStateException("overlay show failed")
            executed += action
        }

        override fun clear() {
            clearCount++
            if (throwOnClear) throw IllegalStateException("overlay hide failed")
        }

        override fun mute() = Unit
        override fun unmute() = Unit
    }

    // ------------------------------------------------------------------- harness

    private val protectedPkg = "com.google.android.youtube"
    private val otherPkg = "com.android.settings"

    private fun engine(
        executor: FakeActionExecutor = FakeActionExecutor(),
        policy: PolicySettings = PolicySettings(
            enabled = true,
            activationDelayMs = 0L,
            noFaceAction = ProtectionAction.SOFT_BLOCK,
            recoveryDelayMs = 30_000L,
        ),
        onError: (Throwable) -> Unit = {},
    ): Triple<ProtectionEngine, FakeForeground, FakeActionExecutor> {
        val monitor = FakeForeground()
        val eng = ProtectionEngine(
            recognizer = Recognizer(),
            monitor = monitor,
            actions = executor,
            policyEvaluator = DefaultPolicyEvaluator(),
        )
        eng.updateContext(parent = null, children = emptyList(), protected = setOf(protectedPkg))
        eng.updateSettings(ProtectionSettings(), policy)
        eng.onEngineError = onError
        return Triple(eng, monitor, executor)
    }

    /**
     * Drives the engine with a monotonic virtual clock. The engine's debounce is
     * 1200 ms, so each step advances well past it; three steps reach the engine's
     * three-frame confirmation.
     */
    private class Driver(
        private val engine: ProtectionEngine,
        private val monitor: FakeForeground,
    ) {
        var now = 0L
        val log = mutableListOf<Pair<ActivityEventType, String?>>()

        /** Evaluate one tick with no frame (= "no face") and advance the clock. */
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

    private fun driver(eng: ProtectionEngine, monitor: FakeForeground): Driver {
        val d = Driver(eng, monitor)
        eng.onEvent = { type, detail -> d.log += type to detail }
        return d
    }

    // ----------------------------------------------------- no-face policy (fail closed)

    @Test
    fun noFaceOnAProtectedAppFailsClosed() {
        val (eng, monitor, executor) = engine()
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)

        // Confirmation needs three consecutive same-class frames; before that the
        // engine must not have applied anything.
        d.steps(2)
        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        assertTrue("no side effect before confirmation", executor.executed.isEmpty())

        val state = d.steps(1)
        assertEquals(ProtectionState.SOFT_BLOCKED, state)
        assertEquals(listOf(ProtectionAction.SOFT_BLOCK), executor.executed)
    }

    @Test
    fun theConfiguredNoFaceActionIsHonoured() {
        // A parent who explicitly relaxes no-face gets the configured behaviour.
        val (eng, monitor, _) = engine(
            policy = PolicySettings(enabled = true, noFaceAction = ProtectionAction.HARD_BLOCK),
        )
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)

        assertEquals(ProtectionState.HARD_BLOCKED, d.steps(3))
    }

    @Test
    fun aNonProtectedAppIsNeverBlocked() {
        val (eng, monitor, executor) = engine()
        val d = driver(eng, monitor)
        monitor.set(otherPkg)

        d.steps(6)
        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        assertTrue("a non-protected app must never trigger a block", executor.executed.isEmpty())
    }

    @Test
    fun protectionDisabledNeverBlocks() {
        val (eng, monitor, executor) = engine(policy = PolicySettings(enabled = false))
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)

        d.steps(6)
        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        assertTrue(executor.executed.isEmpty())
    }

    @Test
    fun aNullForegroundIsNeverBlocked() {
        val (eng, monitor, executor) = engine()
        val d = driver(eng, monitor)
        monitor.set(null)

        d.steps(6)
        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        assertTrue(executor.executed.isEmpty())
    }

    @Test
    fun sixFramelessTicksBecomeAnObstructionAndFollowTheObstructionPolicy() {
        // A sustained "no face" streak (>= 6 frames) is reclassified as a possibly
        // obstructed camera, which then follows the obstruction action rather than the
        // plain no-face action. The reclassification needs three further confirming
        // frames, because the class change must itself pass multi-frame confirmation.
        val (eng, monitor, _) = engine(
            policy = PolicySettings(
                enabled = true,
                noFaceAction = ProtectionAction.SOFT_BLOCK,
                obstructionAction = ProtectionAction.HARD_BLOCK,
            ),
        )
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)

        // 3 frames -> confirmed NoFace -> soft block.
        assertEquals(ProtectionState.SOFT_BLOCKED, d.steps(3))
        // Frames 6..8 reclassify as an obstruction (frames 6 and 7 are a mixed
        // pending window, so the decision lands on the third obstruction frame).
        d.steps(5)
        assertEquals(ProtectionState.HARD_BLOCKED, eng.state.value)
    }

    // ------------------------------------------------------- app switch / stale block

    @Test
    fun leavingTheProtectedAppClearsTheBlockImmediately() {
        val (eng, monitor, executor) = engine()
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)
        assertEquals(ProtectionState.SOFT_BLOCKED, d.steps(3))

        monitor.set(otherPkg)
        assertEquals(ProtectionState.UNPROTECTED, d.step())
        assertEquals("the block must be released exactly once", 1, executor.clearCount)
        assertNull("a stale blocked app must not survive", eng.blockedApp.value)
    }

    @Test
    fun rapidProtectedToNonProtectedSwitchingLeavesNoStaleState() {
        val (eng, monitor, executor) = engine()
        val d = driver(eng, monitor)

        // Enter -> block.
        monitor.set(protectedPkg)
        assertEquals(ProtectionState.SOFT_BLOCKED, d.steps(3))
        // Leave -> unblock.
        monitor.set(otherPkg)
        assertEquals(ProtectionState.UNPROTECTED, d.step())
        // Re-enter -> block again (no stale "already blocked" shortcut).
        monitor.set(protectedPkg)
        assertEquals(ProtectionState.SOFT_BLOCKED, d.steps(3))
        // Leave again.
        monitor.set(otherPkg)
        assertEquals(ProtectionState.UNPROTECTED, d.step())

        assertEquals("each distinct block cycle applies its own side effect", 2, executor.executed.size)
        assertNull(eng.blockedApp.value)
    }

    @Test
    fun repeatedTicksWhileBlockedAreIdempotent() {
        val (eng, monitor, executor) = engine()
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)

        d.steps(12)
        assertEquals(ProtectionState.SOFT_BLOCKED, eng.state.value)
        // The side effect is applied once per block cycle, not once per tick.
        assertEquals(1, executor.executed.size)
        // …and the identity transition is logged once, not per tick.
        assertEquals(1, d.log.count { it.first == ActivityEventType.NO_FACE })
    }

    @Test
    fun duplicateForegroundEventsProduceNoDuplicateSideEffects() {
        val (eng, monitor, executor) = engine()
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)
        d.steps(3)

        // Re-asserting the same foreground value is a no-op, not a new cycle.
        repeat(5) {
            monitor.set(protectedPkg)
            d.step()
        }
        assertEquals(1, executor.executed.size)
        assertEquals(ProtectionState.SOFT_BLOCKED, eng.state.value)
    }

    // ------------------------------------------------------------- recovery window

    @Test
    fun aRelaxedNoFacePolicyStartsRecoveryRatherThanUnblockingInstantly() {
        // Both no-face and obstruction are relaxed, so the test isolates the
        // "relaxed while blocked" transition rather than the obstruction reclass.
        val (eng, monitor, _) = engine(
            policy = PolicySettings(
                enabled = true,
                noFaceAction = ProtectionAction.HARD_BLOCK,
                obstructionAction = ProtectionAction.HARD_BLOCK,
                recoveryDelayMs = 30_000L,
            ),
        )
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)
        assertEquals(ProtectionState.HARD_BLOCKED, d.steps(3))

        // The parent relaxes the policy while the block is up: the engine must not drop
        // the block instantly (a flicker would let the child through for a frame), it
        // must enter the recovery window first.
        eng.updateSettings(
            ProtectionSettings(),
            PolicySettings(
                enabled = true,
                noFaceAction = ProtectionAction.ALLOW,
                obstructionAction = ProtectionAction.ALLOW,
                recoveryDelayMs = 30_000L,
            ),
        )
        assertEquals(ProtectionState.RECOVERING, d.steps(3))

        // A (re)block during recovery must invalidate the pending release.
        eng.updateSettings(
            ProtectionSettings(),
            PolicySettings(
                enabled = true,
                noFaceAction = ProtectionAction.HARD_BLOCK,
                obstructionAction = ProtectionAction.HARD_BLOCK,
                recoveryDelayMs = 30_000L,
            ),
        )
        assertEquals(ProtectionState.HARD_BLOCKED, d.steps(3))
        assertNotNull(eng.blockedApp.value)
    }

    @Test
    fun cancellingRecoveryReturnsToUnprotected() {
        val (eng, monitor, _) = engine(
            policy = PolicySettings(
                enabled = true,
                noFaceAction = ProtectionAction.HARD_BLOCK,
                obstructionAction = ProtectionAction.HARD_BLOCK,
                recoveryDelayMs = 30_000L,
            ),
        )
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)
        d.steps(3)

        eng.updateSettings(
            ProtectionSettings(),
            PolicySettings(
                enabled = true,
                noFaceAction = ProtectionAction.ALLOW,
                obstructionAction = ProtectionAction.ALLOW,
                recoveryDelayMs = 30_000L,
            ),
        )
        assertEquals(ProtectionState.RECOVERING, d.steps(3))

        eng.cancelRecovery()
        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        assertNull(eng.blockedApp.value)
    }

    @Test
    fun emergencyUnlockClearsAnActiveBlock() {
        val (eng, monitor, _) = engine()
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)
        assertEquals(ProtectionState.SOFT_BLOCKED, d.steps(3))

        eng.emergencyUnlock()
        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        assertNull(eng.blockedApp.value)
        assertTrue(d.log.any { it.first == ActivityEventType.EMERGENCY_UNLOCK })
    }

    // ------------------------------------------------- failure injection (side effects)

    @Test
    fun aThrowingActionExecutorDoesNotCrashTheEngineAndKeepsTheBlockIntent() {
        val errors = mutableListOf<Throwable>()
        val executor = FakeActionExecutor(throwOnExecute = true)
        val (eng, monitor, _) = engine(executor = executor, onError = { errors += it })
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)

        // A failing blocking window must never propagate into the evaluation loop —
        // an uncaught throw there would crash the process and take protection down
        // with it. The block intent is kept (best effort) and the failure reported.
        val state = d.steps(6)
        assertEquals(ProtectionState.SOFT_BLOCKED, state)
        assertTrue("the failure must be reported to diagnostics", errors.isNotEmpty())
    }

    @Test
    fun aThrowingClearDoesNotCrashTheEngineOnUnblock() {
        val errors = mutableListOf<Throwable>()
        val executor = FakeActionExecutor(throwOnClear = true)
        val (eng, monitor, _) = engine(executor = executor, onError = { errors += it })
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)
        d.steps(3)

        monitor.set(otherPkg)
        // Must not throw, must still reach UNPROTECTED, and must not keep a stale
        // blocked-app reference (a stale reference is what would permanently break
        // the next block).
        assertEquals(ProtectionState.UNPROTECTED, d.step())
        assertNull(eng.blockedApp.value)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun aFailingErrorHookCannotBreakProtection() {
        // Diagnostics is observability: a throwing reporter must be swallowed too.
        val executor = FakeActionExecutor(throwOnExecute = true)
        val (eng, monitor, _) = engine(executor = executor, onError = { throw RuntimeException("reporter failed") })
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)

        assertEquals(ProtectionState.SOFT_BLOCKED, d.steps(6))
    }

    // -------------------------------------------------------------- lifecycle

    @Test
    fun startAndStopAreIdempotent() {
        val (eng, monitor, _) = engine()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        try {
            // Repeated starts must not stack evaluation loops.
            eng.start(scope)
            eng.start(scope)
            eng.start(scope)
            // Repeated stops must be safe.
            eng.stop()
            eng.stop()
            assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stopResetsEveryPublishedSignal() {
        val (eng, monitor, _) = engine()
        val d = driver(eng, monitor)
        monitor.set(protectedPkg)
        d.steps(3)

        eng.stop()
        assertEquals(ProtectionState.UNPROTECTED, eng.state.value)
        assertNull(eng.blockedApp.value)
        assertNull(eng.identity.value)
        assertNull(eng.decision.value)
    }
}

private fun kotlinx.coroutines.CoroutineScope.cancel() =
    (coroutineContext[kotlinx.coroutines.Job])?.cancel()
