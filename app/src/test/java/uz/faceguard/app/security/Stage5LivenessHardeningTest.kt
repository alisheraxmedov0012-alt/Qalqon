package uz.faceguard.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.core.liveness.LivenessFrame
import uz.faceguard.app.core.liveness.LivenessSource
import uz.faceguard.app.core.liveness.TemporalLivenessDetector
import uz.faceguard.app.core.policy.DefaultPolicyEvaluator
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.PolicyContext
import uz.faceguard.app.domain.policy.PolicyDecision
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity
import uz.faceguard.app.feature.home.livenessLabelRes

/**
 * Stage 5 (Anti-Spoofing / Liveness) hardening regressions, all JVM-testable.
 *
 * These pin the honest failure behaviour of the liveness decision and the one
 * place the app used to overclaim: the dashboard mapped a passive *motion*
 * heuristic result to "Real face". The heuristic is a weak signal that a
 * hand-shaken photo or a phone-screen video also satisfies, so it must never be
 * surfaced as a liveness verdict; only a real anti-spoofing model's result may be.
 *
 * Real camera / printed-photo / replay testing is NOT possible here and is
 * explicitly out of scope for these tests (see docs/STAGE5_ANTI_SPOOFING_LIVENESS.md).
 */
class Stage5LivenessHardeningTest {

    private val detector = TemporalLivenessDetector()
    private val evaluator = DefaultPolicyEvaluator()

    private fun frame(
        ts: Long,
        facePresent: Boolean = true,
        yaw: Float = 0f,
        pitch: Float = 0f,
        modelScore: Float? = null,
    ) = LivenessFrame(
        facePresent = facePresent,
        yawDegrees = yaw,
        pitchDegrees = pitch,
        modelScore = modelScore,
        timestamp = ts,
    )

    private fun context(identity: UserIdentity, liveness: LivenessState) = PolicyContext(
        identity = IdentityContext(
            identity = identity,
            childId = if (identity == UserIdentity.CHILD) 1L else null,
            childName = if (identity == UserIdentity.CHILD) "Child 1" else null,
        ),
        settings = PolicySettings(
            enabled = true,
            childAction = ProtectionAction.HARD_BLOCK,
            unknownUserAction = ProtectionAction.SOFT_BLOCK,
            noFaceAction = ProtectionAction.ALLOW,
            obstructionAction = ProtectionAction.SOFT_BLOCK,
            spoofAction = ProtectionAction.SOFT_BLOCK,
        ),
        liveness = liveness,
        foregroundPackage = "com.example.app",
        isProtectedApp = true,
    )

    // ---------------------------------------------------- dashboard honesty

    @Test
    fun theHeuristicLiveIsNeverShownAsARealFaceVerdict() {
        assertNull(
            "a motion heuristic must not be presented as 'a real face'",
            livenessLabelRes(LivenessState.LIVE, LivenessSource.HEURISTIC),
        )
        assertNull(livenessLabelRes(LivenessState.LIVE, LivenessSource.NONE))
        assertNull(livenessLabelRes(LivenessState.LIVE, null))
    }

    @Test
    fun onlyAModelBackedLiveIsShownAsARealFaceVerdict() {
        assertEquals(
            R.string.dashboard_liveness_live,
            livenessLabelRes(LivenessState.LIVE, LivenessSource.MODEL),
        )
        // A model is the only thing that can emit SPOOF, so it stays surfaceable.
        assertEquals(
            R.string.dashboard_liveness_spoof,
            livenessLabelRes(LivenessState.SPOOF, LivenessSource.MODEL),
        )
        assertNull(livenessLabelRes(LivenessState.UNKNOWN, LivenessSource.MODEL))
        assertNull(livenessLabelRes(LivenessState.UNSTABLE, LivenessSource.MODEL))
    }

    // ---------------------------------------------------- detector fail-safe

    @Test
    fun nanModelScoresNeverProduceLiveOrSpoof() {
        val result = detector.detect(
            listOf(
                frame(1, modelScore = Float.NaN),
                frame(2, modelScore = Float.NaN),
                frame(3, modelScore = Float.NaN),
            ),
        )
        // A malformed model output must not be trusted as live (and must not be
        // silently downgraded to a confident spoof either) — it is undecided.
        assertEquals(LivenessState.UNSTABLE, result.state)
        assertNull(result.confidence)
    }

    @Test
    fun outOfRangeModelScoresAreClampedDeterministically() {
        val high = detector.detect(
            listOf(frame(1, modelScore = 5f), frame(2, modelScore = 5f), frame(3, modelScore = 5f)),
        )
        assertEquals(LivenessState.LIVE, high.state)
        assertEquals(LivenessSource.MODEL, high.source)

        val low = detector.detect(
            listOf(frame(1, modelScore = -3f), frame(2, modelScore = -3f), frame(3, modelScore = -3f)),
        )
        assertEquals(LivenessState.SPOOF, low.state)
        assertEquals(LivenessSource.MODEL, low.source)
    }

    @Test
    fun theHeuristicNeverEmitsSpoofWithoutAModel() {
        // A perfectly static window (no pose change) is UNKNOWN, never LIVE and never
        // a fabricated SPOOF — without a model, SPOOF is unreachable.
        val still = detector.detect(listOf(frame(1), frame(2), frame(3)))
        assertEquals(LivenessState.UNKNOWN, still.state)
        assertEquals(LivenessSource.HEURISTIC, still.source)

        val moving = detector.detect(listOf(frame(1, yaw = 0f), frame(2, yaw = 8f), frame(3, yaw = 0f)))
        assertEquals(LivenessState.LIVE, moving.state)
        assertEquals(LivenessSource.HEURISTIC, moving.source)
    }

    // ---------------------------------------------------- policy: not trusted

    @Test
    fun anUnknownIdentityWithLiveLivenessIsNotTreatedAsTrusted() {
        // identity fail + liveness pass must still follow the unknown policy, not
        // be promoted to a trusted identity.
        val decision = evaluator.evaluate(context(UserIdentity.UNKNOWN, LivenessState.LIVE))
        assertEquals(ProtectionAction.SOFT_BLOCK, (decision as PolicyDecision.Protect).action)
    }

    @Test
    fun aSpoofedParentNeverUnlocksEvenWhenRepeated() {
        // A recognised-but-spoofed parent must not inherit the parent ALLOW, and the
        // decision is stable across repeats (no latch that opens later).
        repeat(5) {
            val decision = evaluator.evaluate(context(UserIdentity.PARENT, LivenessState.SPOOF))
            assertEquals(ProtectionAction.SOFT_BLOCK, (decision as PolicyDecision.Protect).action)
        }
    }

    @Test
    fun aStaleEmptyWindowIsUnknownNotALatchedLive() {
        // No frames at all is UNKNOWN, so an empty/stale pipeline can never hold a
        // previous LIVE and wrongly vouch for the current presentation.
        val result = detector.detect(emptyList())
        assertEquals(LivenessState.UNKNOWN, result.state)
        assertEquals(LivenessSource.NONE, result.source)
    }
}
