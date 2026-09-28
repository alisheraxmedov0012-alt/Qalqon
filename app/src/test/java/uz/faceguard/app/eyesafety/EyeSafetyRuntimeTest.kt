package uz.faceguard.app.eyesafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.eyesafety.EyeSafetyObservationState
import uz.faceguard.app.core.eyesafety.eyeSafetyFrameOf
import uz.faceguard.app.core.pipeline.FaceQuality
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.domain.eyesafety.ChildEyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyFrame
import uz.faceguard.app.domain.policy.EyeSafetyState
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.PolicyTrigger
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity

/**
 * Phase 6 Step 4 (pure JVM): the runtime eye-safety observation state and its boundary adapter.
 *
 * These drive the *production* [EyeSafetyObservationState], which delegates the actual decision to
 * the Phase 6 Step 1 evaluator. The rules that matter here are the runtime ones: which child is
 * active, when a session is replaced, and what reaches the policy context — never a reimplementation
 * of the thresholds or hysteresis.
 *
 * No Android, no database, no camera: the observation the pipeline would produce is built directly
 * from the same metrics the pipeline computes.
 */
class EyeSafetyRuntimeTest {

    private val childA = 10L
    private val childB = 11L

    /** The documented thresholds, confirmed after 1 observed frame unless stated otherwise. */
    private fun config(
        confirmFrames: Int = 1,
        enabled: Boolean = true,
        warningEnter: Float = 0.30f,
        warningExit: Float = 0.27f,
        dangerEnter: Float = 0.40f,
        dangerExit: Float = 0.35f,
    ) = EyeSafetyConfig(
        enabled = enabled,
        warningEnterThreshold = warningEnter,
        warningExitThreshold = warningExit,
        dangerEnterThreshold = dangerEnter,
        dangerExitThreshold = dangerExit,
        confirmFrames = confirmFrames,
    )

    private fun child(
        childId: Long = childA,
        config: EyeSafetyConfig = config(),
        warningAction: ProtectionAction = ProtectionAction.WARNING,
        dangerAction: ProtectionAction = ProtectionAction.SOFT_BLOCK,
    ) = ChildEyeSafetyConfig(
        accountId = 1L,
        childId = childId,
        config = config,
        warningAction = warningAction,
        dangerAction = dangerAction,
        updatedAt = 1_000L,
    )

    /** The per-frame metrics the existing pipeline computes for a detected face. */
    private fun quality(faceWidthRatio: Float, faceCount: Int = 1) =
        FaceQuality(faceCount = faceCount, faceWidthRatio = faceWidthRatio)

    /** The observation the runtime would feed for a detected face of [faceWidthRatio]. */
    private fun observation(timestampMs: Long, faceWidthRatio: Float): EyeSafetyFrame =
        eyeSafetyFrameOf(quality(faceWidthRatio), timestampMs)

    /** The observation for a frame in which the detector saw no usable face. */
    private fun noFaceObservation(timestampMs: Long): EyeSafetyFrame =
        eyeSafetyFrameOf(quality(faceWidthRatio = 0f, faceCount = 0), timestampMs)

    private fun state() = EyeSafetyObservationState()

    // ================================================================
    // A. child activation
    // ================================================================

    @Test
    fun a_childWithAnEnabledConfigurationActivatesEyeSafety() {
        val eyeSafety = state()

        val result = eyeSafety.observe(observation(100L, 0.45f), childA, child(childId = childA), now = 100L)

        assertEquals(EyeSafetyState.DANGER, result)
        assertEquals(childA, eyeSafety.activeChildId)
    }

    @Test
    fun a_closeFaceIsSafeWhenItIsFarEnough() {
        val eyeSafety = state()

        assertEquals(
            EyeSafetyState.SAFE,
            eyeSafety.observe(observation(100L, 0.10f), childA, child(childId = childA), now = 100L),
        )
    }

    @Test
    fun a_warningDistanceFaceReachesWarning() {
        val eyeSafety = state()

        assertEquals(
            EyeSafetyState.WARNING,
            eyeSafety.observe(observation(100L, 0.35f), childA, child(childId = childA), now = 100L),
        )
    }

    @Test
    fun theActiveChildCarriesItsConfiguredActions() {
        val eyeSafety = state()
        val child = child(childId = childA, warningAction = ProtectionAction.MUTE, dangerAction = ProtectionAction.HARD_BLOCK)

        eyeSafety.observe(observation(100L, 0.45f), childA, child, now = 100L)

        assertEquals(ProtectionAction.MUTE, eyeSafety.activeChild!!.warningAction)
        assertEquals(ProtectionAction.HARD_BLOCK, eyeSafety.activeChild!!.dangerAction)
    }

    // ================================================================
    // B/C. disabled and missing configuration
    // ================================================================

    @Test
    fun b_aDisabledConfigurationIsANoOp() {
        val eyeSafety = state()

        val result = eyeSafety.observe(observation(100L, 0.90f), childA, child(childId = childA, config = config(enabled = false)), now = 100L)

        assertEquals(EyeSafetyState.UNKNOWN, result)
        assertNull("no session may be active for a disabled configuration", eyeSafety.activeChildId)
    }

    @Test
    fun c_aMissingConfigurationIsANoOp() {
        val eyeSafety = state()

        val result = eyeSafety.observe(observation(100L, 0.90f), childA, child = null, now = 100L)

        assertEquals(EyeSafetyState.UNKNOWN, result)
        assertNull(eyeSafety.activeChildId)
        assertEquals(EyeSafetyState.UNKNOWN, eyeSafety.currentState)
    }

    @Test
    fun c_aMismatchedConfigurationIsRejectedRatherThanApplied() {
        // Defensive: if a caller ever hands over another child's configuration it must not be used.
        val eyeSafety = state()

        val result = eyeSafety.observe(observation(100L, 0.90f), childId = childA, child = child(childId = childB), now = 100L)

        assertEquals(EyeSafetyState.UNKNOWN, result)
    }

    // ================================================================
    // D/E. child-specific configuration and child switching
    // ================================================================

    @Test
    fun d_eachChildUsesItsOwnConfiguration() {
        val eyeSafety = state()
        // Each child's configuration is internally consistent: a permissive child and a strict one.
        val permissive = child(
            childId = childA,
            config = config(warningEnter = 0.10f, warningExit = 0.08f, dangerEnter = 0.20f, dangerExit = 0.15f),
        )
        val strict = child(
            childId = childB,
            config = config(warningEnter = 0.60f, warningExit = 0.55f, dangerEnter = 0.80f, dangerExit = 0.70f),
        )

        // The same 0.45 ratio is DANGER for the permissive child…
        assertEquals(
            EyeSafetyState.DANGER,
            eyeSafety.observe(observation(100L, 0.45f), childA, permissive, now = 100L),
        )
        // …and only SAFE for the strict one, so no threshold is shared between them.
        assertEquals(
            EyeSafetyState.SAFE,
            eyeSafety.observe(observation(200L, 0.45f), childB, strict, now = 200L),
        )
    }

    @Test
    fun e_switchingChildrenDoesNotInheritThePreviousChildsState() {
        val eyeSafety = state()
        val childAConfig = child(childId = childA, config = config(confirmFrames = 3))
        val childBConfig = child(childId = childB, config = config(confirmFrames = 3))

        // Child A reaches DANGER after three confirming frames.
        (1..3).forEach { index -> eyeSafety.observe(observation(index * 100L, 0.45f), childA, childAConfig, now = index * 100L) }
        assertEquals(EyeSafetyState.DANGER, eyeSafety.currentState)

        // Child B's very next frame must not inherit it: one frame is not yet confirmed.
        assertEquals(
            EyeSafetyState.UNKNOWN,
            eyeSafety.observe(observation(400L, 0.45f), childB, childBConfig, now = 400L),
        )
        assertEquals(childB, eyeSafety.activeChildId)
    }

    @Test
    fun e_aChangedConfigurationRestartsConfirmation() {
        val eyeSafety = state()
        val initial = child(childId = childA, config = config(confirmFrames = 3))
        // A stricter danger threshold for the same child: still a valid configuration.
        val changed = child(
            childId = childA,
            config = config(confirmFrames = 3, dangerEnter = 0.50f, dangerExit = 0.45f),
        )

        (1..3).forEach { index -> eyeSafety.observe(observation(index * 100L, 0.35f), childA, initial, now = index * 100L) }
        assertEquals(EyeSafetyState.WARNING, eyeSafety.currentState)

        // A new threshold is re-confirmed from scratch rather than reusing the old verdict.
        assertEquals(
            EyeSafetyState.UNKNOWN,
            eyeSafety.observe(observation(400L, 0.35f), childA, changed, now = 400L),
        )
    }

    // ================================================================
    // F/G. parent and unknown user
    // ================================================================

    @Test
    fun f_aParentIsNeverUnderAChildEyeSafetyState() {
        val eyeSafety = state()
        eyeSafety.observe(observation(100L, 0.90f), childA, child(childId = childA), now = 100L)
        assertEquals(EyeSafetyState.DANGER, eyeSafety.currentState)

        // Parent recognised: the caller passes no child, so the session is dropped entirely.
        val result = eyeSafety.observe(observation(200L, 0.90f), childId = null, child = null, now = 200L)

        assertEquals(EyeSafetyState.UNKNOWN, result)
        assertNull(eyeSafety.activeChildId)
    }

    @Test
    fun g_anUnknownUserIsNeverUnderAChildEyeSafetyState() {
        val eyeSafety = state()
        eyeSafety.observe(observation(100L, 0.90f), childA, child(childId = childA), now = 100L)

        val result = eyeSafety.observe(observation(200L, 0.90f), childId = null, child = null, now = 200L)

        assertEquals(EyeSafetyState.UNKNOWN, result)
        assertEquals(EyeSafetyState.UNKNOWN, eyeSafety.currentState)
    }

    @Test
    fun f_aParentWithNoChildIdCannotEvenLookUpAConfiguration() {
        // The engine passes the recognised child id, so a null child can never select a config.
        val eyeSafety = state()

        assertEquals(
            EyeSafetyState.UNKNOWN,
            eyeSafety.observe(observation(100L, 0.90f), childId = null, child = null, now = 100L),
        )
        assertNull(eyeSafety.activeChildId)
    }

    // ================================================================
    // H. no-face
    // ================================================================

    @Test
    fun h_aNoFaceFrameIsNotAZeroRatio() {
        val mapped = noFaceObservation(100L)

        assertFalse("no face must not be reported as a detected face", mapped.facePresent)
        assertNull("no face carries no usable ratio", mapped.usableRatio)
        assertEquals(0f, mapped.faceWidthRatio)
    }

    @Test
    fun h_aNoFaceObservationIsUnknownNotSafeNotDanger() {
        val eyeSafety = state()

        val result = eyeSafety.observe(noFaceObservation(100L), childA, child(childId = childA, config = config(confirmFrames = 3)), now = 100L)

        assertEquals(EyeSafetyState.UNKNOWN, result)
    }

    @Test
    fun h_aStreamOfNoFaceFramesNeverBecomesDanger() {
        val eyeSafety = state()
        val child = child(childId = childA, config = config(confirmFrames = 3))

        (1..10).forEach { index ->
            val result = eyeSafety.observe(noFaceObservation(index * 100L), childA, child, now = index * 100L)
            assertEquals("no-face frame $index must stay UNKNOWN", EyeSafetyState.UNKNOWN, result)
        }
    }

    @Test
    fun h_aDetectedFaceWithZeroWidthIsTreatedAsNoMeasurement() {
        val eyeSafety = state()

        // A zero-width box is not "very far": it is not a measurement at all.
        val result = eyeSafety.observe(observation(100L, 0f), childA, child(childId = childA, config = config(confirmFrames = 3)), now = 100L)

        assertEquals(EyeSafetyState.UNKNOWN, result)
    }

    @Test
    fun h_aMissingFrameIsNotAnObservationAndTheWindowDecays() {
        val eyeSafety = state()
        // confirmFrames = 1, so a single frame confirms; the window then ages out.
        eyeSafety.observe(observation(100L, 0.45f), childA, child(childId = childA), now = 100L)
        assertEquals(EyeSafetyState.DANGER, eyeSafety.currentState)

        // No frame this evaluation: nothing is observed, and the domain window expires.
        val result = eyeSafety.observe(observation = null, childId = childA, child = child(childId = childA), now = 100_000L)

        assertEquals(EyeSafetyState.UNKNOWN, result)
    }

    @Test
    fun theFrameAdapterCarriesTheQualityRatioAndTimestamp() {
        val mapped = eyeSafetyFrameOf(quality(0.42f), timestampMs = 777L)

        assertTrue(mapped.facePresent)
        assertEquals(0.42f, mapped.usableRatio)
        assertEquals(777L, mapped.timestampMs)
    }

    @Test
    fun theFrameAdapterNeverUsesRawPixelsOrInventsADistance() {
        // Only the normalized ratio crosses; nothing else about the frame is consulted.
        val mapped = eyeSafetyFrameOf(quality(0.42f), timestampMs = 100L)

        assertEquals(0.42f, mapped.faceWidthRatio)
        assertEquals(0.42f, mapped.usableRatio)
    }

    @Test
    fun theFrameAdapterTreatsAMissingQualityAsNoMeasurement() {
        val mapped = eyeSafetyFrameOf(quality = null, timestampMs = 100L)

        assertFalse(mapped.facePresent)
        assertNull(mapped.usableRatio)
    }

    @Test
    fun theFrameAdapterTreatsAFaceCountOfZeroAsNoMeasurementEvenWithARatio() {
        // A stale ratio must not be read as a measurement once the detector reports no face.
        val mapped = eyeSafetyFrameOf(quality(faceWidthRatio = 0.9f, faceCount = 0), timestampMs = 100L)

        assertFalse(mapped.facePresent)
        assertNull(mapped.usableRatio)
    }

    // ================================================================
    // I. confirmation uses the child's confirmFrames
    // ================================================================

    @Test
    fun i_theConfiguredConfirmFramesIsRespectedRatherThanAConstant() {
        val threeFrame = state()
        val oneFrame = state()
        val childThree = child(childId = childA, config = config(confirmFrames = 3))
        val childOne = child(childId = childA, config = config(confirmFrames = 1))

        (1..2).forEach { index -> threeFrame.observe(observation(index * 100L, 0.45f), childA, childThree, now = index * 100L) }
        assertEquals("two frames is not enough at confirmFrames = 3", EyeSafetyState.UNKNOWN, threeFrame.currentState)

        threeFrame.observe(observation(300L, 0.45f), childA, childThree, now = 300L)
        assertEquals(EyeSafetyState.DANGER, threeFrame.currentState)

        oneFrame.observe(observation(100L, 0.45f), childA, childOne, now = 100L)
        assertEquals("one frame is enough at confirmFrames = 1", EyeSafetyState.DANGER, oneFrame.currentState)
    }

    @Test
    fun i_repeatedObservationsOfTheSameFrameDoNotConfirm() {
        // The camera has not produced a new frame, so the same observation must not be counted
        // again — otherwise three ticks (1.5s) would confirm a DANGER from a single frame.
        val eyeSafety = state()
        val child = child(childId = childA, config = config(confirmFrames = 3))
        val sameFrame = observation(100L, 0.45f)

        (1..10).forEach { _ -> eyeSafety.observe(sameFrame, childA, child, now = 100L) }

        assertEquals(EyeSafetyState.UNKNOWN, eyeSafety.currentState)
    }

    // ================================================================
    // J. hysteresis belongs to the domain evaluator
    // ================================================================

    @Test
    fun j_hysteresisIsDelegatedAndNotReimplemented() {
        val eyeSafety = state()
        val child = child(childId = childA)

        // Enter DANGER at 0.45, then hold it at 0.36 (above dangerExit 0.35).
        eyeSafety.observe(observation(100L, 0.45f), childA, child, now = 100L)
        assertEquals(EyeSafetyState.DANGER, eyeSafety.observe(observation(200L, 0.36f), childA, child, now = 200L))

        // Below dangerExit it falls to WARNING (its own exit band holds it above warningExit 0.27).
        assertEquals(EyeSafetyState.WARNING, eyeSafety.observe(observation(300L, 0.34f), childA, child, now = 300L))
        assertEquals(EyeSafetyState.WARNING, eyeSafety.observe(observation(400L, 0.29f), childA, child, now = 400L))

        // Only below warningExit does it reach SAFE.
        assertEquals(EyeSafetyState.SAFE, eyeSafety.observe(observation(500L, 0.20f), childA, child, now = 500L))
    }

    // ================================================================
    // Reset
    // ================================================================

    @Test
    fun resetDropsTheSessionEntirely() {
        val eyeSafety = state()
        eyeSafety.observe(observation(100L, 0.90f), childA, child(childId = childA), now = 100L)
        assertEquals(EyeSafetyState.DANGER, eyeSafety.currentState)

        eyeSafety.reset()

        assertNull(eyeSafety.activeChildId)
        assertNull(eyeSafety.activeChild)
        assertEquals(EyeSafetyState.UNKNOWN, eyeSafety.currentState)
    }

    @Test
    fun afterResetTheNextObservationIsEvaluatedAfresh() {
        val eyeSafety = state()
        val child = child(childId = childA, config = config(confirmFrames = 3))

        (1..3).forEach { index -> eyeSafety.observe(observation(index * 100L, 0.45f), childA, child, now = index * 100L) }
        assertEquals(EyeSafetyState.DANGER, eyeSafety.currentState)

        eyeSafety.reset()

        assertEquals(
            EyeSafetyState.UNKNOWN,
            eyeSafety.observe(observation(400L, 0.45f), childA, child, now = 400L),
        )
    }

    // ================================================================
    // Determinism and no premature restriction
    // ================================================================

    @Test
    fun theSameObservationSequenceProducesTheSameStates() {
        fun run(): List<EyeSafetyState> {
            val eyeSafety = state()
            val child = child(childId = childA, config = config(confirmFrames = 2))
            val frames = listOf(
                observation(100L, 0.10f) to 100L,
                observation(200L, 0.45f) to 200L,
                observation(300L, 0.45f) to 300L,
                observation(400L, 0.20f) to 400L,
                observation(500L, 0.20f) to 500L,
            )
            return frames.map { (f, now) -> eyeSafety.observe(f, childA, child, now) }
        }

        assertEquals(run(), run())
    }

    @Test
    fun nothingIsRestrictedBeforeTheFirstObservation() {
        val eyeSafety = state()

        assertNull(eyeSafety.activeChildId)
        assertEquals(EyeSafetyState.UNKNOWN, eyeSafety.currentState)
    }

    @Test
    fun anInvalidConfigurationThatCannotBeBuiltNeverReachesTheRuntime() {
        // The domain rejects it at construction, so the runtime can never enforce a fabricated
        // DANGER from a corrupt threshold ordering.
        val failure = runCatching {
            child(childId = childA, config = config(warningEnter = 0.50f, dangerEnter = 0.40f))
        }

        assertTrue(failure.isFailure)
    }

    @Test
    fun theRuntimeNeverReportsSomethingOtherThanTheDomainStates() {
        val eyeSafety = state()
        val child = child(childId = childA)

        val states = listOf(0.05f, 0.30f, 0.45f).mapIndexed { index, ratio ->
            eyeSafety.observe(observation((index + 1) * 100L, ratio), childA, child, now = (index + 1) * 100L)
        }

        assertEquals(listOf(EyeSafetyState.SAFE, EyeSafetyState.WARNING, EyeSafetyState.DANGER), states)
    }

    // ================================================================
    // K/L. the child's configured actions reach the existing policy evaluator
    // ================================================================

    private val baseSettings = PolicySettings(
        enabled = true,
        activationDelayMs = 3_000L,
        childAction = ProtectionAction.WARNING,
        unknownUserAction = ProtectionAction.SOFT_BLOCK,
        noFaceAction = ProtectionAction.ALLOW,
        obstructionAction = ProtectionAction.SOFT_BLOCK,
        recoveryDelayMs = 30_000L,
    )

    private fun context(state: EyeSafetyState, settings: PolicySettings, isProtectedApp: Boolean = true) =
        PolicyContext(
            identity = IdentityContext(identity = UserIdentity.CHILD, childId = childA),
            settings = settings,
            foregroundPackage = "com.example.youtube",
            isProtectedApp = isProtectedApp,
            eyeSafetyState = state,
        )

    @Test
    fun k_theChildsConfiguredActionsReachThePolicyEvaluatorThroughTheRuntimeSettings() {
        val eyeSafety = state()
        val child = child(
            childId = childA,
            warningAction = ProtectionAction.MUTE,
            dangerAction = ProtectionAction.HARD_BLOCK,
        )
        val evaluator = DefaultPolicyEvaluator()

        eyeSafety.observe(observation(100L, 0.45f), childA, child, now = 100L)
        val state = eyeSafety.currentState
        assertEquals(EyeSafetyState.DANGER, state)

        // The runtime supplies the child's own actions, and the existing evaluator decides.
        val decision = evaluator.evaluate(context(state, eyeSafety.policySettings(baseSettings)))

        assertTrue("expected a protection decision but was $decision", decision is PolicyDecision.Protect)
        assertEquals(ProtectionAction.HARD_BLOCK, (decision as PolicyDecision.Protect).action)
        assertEquals(PolicyTrigger.EYE_SAFETY_DANGER, decision.trigger)
    }

    @Test
    fun k_theChildsWarningActionIsUsedForAWarningState() {
        val eyeSafety = state()
        val child = child(childId = childA, warningAction = ProtectionAction.MUTE, dangerAction = ProtectionAction.HARD_BLOCK)
        eyeSafety.observe(observation(100L, 0.33f), childA, child, now = 100L)

        val decision = DefaultPolicyEvaluator().evaluate(
            context(eyeSafety.currentState, eyeSafety.policySettings(baseSettings)),
        )

        assertEquals(EyeSafetyState.WARNING, eyeSafety.currentState)
        assertEquals(ProtectionAction.MUTE, (decision as PolicyDecision.Protect).action)
    }

    @Test
    fun k_withNoSessionTheRuntimeSettingsArePassedThroughUnchanged() {
        val eyeSafety = state()

        assertEquals(baseSettings, eyeSafety.policySettings(baseSettings))
    }

    @Test
    fun k_theOverrideTouchesOnlyTheTwoEyeSafetyFields() {
        val eyeSafety = state()
        val child = child(childId = childA, warningAction = ProtectionAction.MUTE, dangerAction = ProtectionAction.HARD_BLOCK)
        eyeSafety.observe(observation(100L, 0.10f), childA, child, now = 100L)

        val effective = eyeSafety.policySettings(baseSettings)

        assertEquals(ProtectionAction.MUTE, effective.eyeSafetyWarningAction)
        assertEquals(ProtectionAction.HARD_BLOCK, effective.eyeSafetyDangerAction)
        // Every other policy input is exactly what the runtime supplied.
        assertEquals(baseSettings.enabled, effective.enabled)
        assertEquals(baseSettings.childAction, effective.childAction)
        assertEquals(baseSettings.activationDelayMs, effective.activationDelayMs)
        assertEquals(baseSettings.recoveryDelayMs, effective.recoveryDelayMs)
        assertEquals(baseSettings.parentDeviceChildPolicyEnabled, effective.parentDeviceChildPolicyEnabled)
    }

    @Test
    fun k_afterResetTheGlobalSettingsApplyAgain() {
        val eyeSafety = state()
        val child = child(childId = childA, warningAction = ProtectionAction.MUTE, dangerAction = ProtectionAction.HARD_BLOCK)
        eyeSafety.observe(observation(100L, 0.45f), childA, child, now = 100L)

        eyeSafety.reset()

        assertEquals(baseSettings, eyeSafety.policySettings(baseSettings))
    }

    @Test
    fun l_aDangerStateStillCannotRestrictAnUnprotectedApp() {
        val eyeSafety = state()
        val child = child(childId = childA, dangerAction = ProtectionAction.HARD_BLOCK)
        eyeSafety.observe(observation(100L, 0.90f), childA, child, now = 100L)

        val decision = DefaultPolicyEvaluator().evaluate(
            context(eyeSafety.currentState, eyeSafety.policySettings(baseSettings), isProtectedApp = false),
        )

        // The existing protected-app boundary is untouched: the runtime may compute the state, but
        // only the policy layer decides whether it applies.
        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun parentAndUnknownObservationsLeaveTheGlobalSettingsAlone() {
        val eyeSafety = state()
        val child = child(childId = childA, dangerAction = ProtectionAction.HARD_BLOCK)
        eyeSafety.observe(observation(100L, 0.90f), childA, child, now = 100L)

        // Parent recognised: no child, so the child's actions must not survive.
        eyeSafety.observe(observation(200L, 0.90f), childId = null, child = null, now = 200L)

        assertEquals(baseSettings, eyeSafety.policySettings(baseSettings))
    }
}
