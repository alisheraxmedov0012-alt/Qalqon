package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.faceguard.app.core.liveness.LivenessFrame
import uz.faceguard.app.core.liveness.LivenessSource
import uz.faceguard.app.core.liveness.TemporalLivenessDetector
import uz.faceguard.app.domain.policy.LivenessState

/**
 * Group 9: the pure liveness decision over a temporal window (no Android), so it
 * runs on the JVM. Covers the state mapping (LIVE / SPOOF / NO_FACE / UNSTABLE /
 * UNKNOWN), the confidence semantics and the model-vs-heuristic priority.
 *
 * These are architectural tests: a deterministic [LivenessFrame] stream stands in
 * for a camera. They prove the decision logic, **not** real anti-spoofing
 * efficacy (see the README limitation).
 */
class LivenessDetectorTest {

    private val detector = TemporalLivenessDetector()

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

    @Test
    fun `empty window is unknown with no source`() {
        val result = detector.detect(emptyList())

        assertEquals(LivenessState.UNKNOWN, result.state)
        assertEquals(LivenessSource.NONE, result.source)
        assertNull(result.confidence)
    }

    @Test
    fun `a window without any face is no-face`() {
        val result = detector.detect(
            listOf(frame(1, facePresent = false), frame(2, facePresent = false), frame(3, facePresent = false)),
        )

        assertEquals(LivenessState.NO_FACE, result.state)
        assertNull(result.confidence)
    }

    @Test
    fun `a single face frame is not enough evidence`() {
        val result = detector.detect(listOf(frame(1, facePresent = true)))

        assertEquals(LivenessState.UNKNOWN, result.state)
        assertEquals(LivenessSource.HEURISTIC, result.source)
    }

    @Test
    fun `natural motion is live and carries no invented confidence`() {
        val result = detector.detect(
            listOf(frame(1, yaw = 0f), frame(2, yaw = 6f), frame(3, yaw = -6f)),
        )

        assertEquals(LivenessState.LIVE, result.state)
        assertEquals(LivenessSource.HEURISTIC, result.source)
        assertNull("the passive heuristic yields no calibrated probability", result.confidence)
    }

    @Test
    fun `a perfectly static face is never trusted as live`() {
        val result = detector.detect(
            listOf(frame(1, yaw = 1.0f), frame(2, yaw = 1.2f), frame(3, yaw = 1.1f)),
        )

        assertEquals(LivenessState.UNKNOWN, result.state)
        assertEquals(LivenessSource.HEURISTIC, result.source)
    }

    @Test
    fun `motion inside the undecided band is unstable`() {
        val result = detector.detect(
            listOf(frame(1, yaw = 0f), frame(2, yaw = 1.5f), frame(3, yaw = 2.0f)),
        )

        assertEquals(LivenessState.UNSTABLE, result.state)
    }

    @Test
    fun `a low mean model score is spoof and reports the inverted score`() {
        val result = detector.detect(
            listOf(
                frame(1, modelScore = 0.10f),
                frame(2, modelScore = 0.20f),
                frame(3, modelScore = 0.10f),
            ),
        )

        assertEquals(LivenessState.SPOOF, result.state)
        assertEquals(LivenessSource.MODEL, result.source)
        // confidence is P(spoof) = 1 - mean(live) ; here 1 - 0.1333 = 0.8667
        assertEquals(0.8667f, result.confidence!!, 0.001f)
    }

    @Test
    fun `a high mean model score is live with the mean as confidence`() {
        val result = detector.detect(
            listOf(
                frame(1, modelScore = 0.90f),
                frame(2, modelScore = 0.80f),
                frame(3, modelScore = 0.90f),
            ),
        )

        assertEquals(LivenessState.LIVE, result.state)
        assertEquals(LivenessSource.MODEL, result.source)
        assertEquals(0.8667f, result.confidence!!, 0.001f)
    }

    @Test
    fun `an inconclusive model score band is unstable`() {
        val result = detector.detect(
            listOf(
                frame(1, modelScore = 0.5f),
                frame(2, modelScore = 0.5f),
                frame(3, modelScore = 0.5f),
            ),
        )

        assertEquals(LivenessState.UNSTABLE, result.state)
        assertEquals(LivenessSource.MODEL, result.source)
    }

    @Test
    fun `the model path takes priority over the passive heuristic`() {
        // Enough motion to look live heuristically, but the model says spoof.
        val result = detector.detect(
            listOf(
                frame(1, yaw = 0f, modelScore = 0.05f),
                frame(2, yaw = 8f, modelScore = 0.05f),
                frame(3, yaw = -8f, modelScore = 0.05f),
            ),
        )

        assertEquals(LivenessState.SPOOF, result.state)
        assertEquals(LivenessSource.MODEL, result.source)
    }

    @Test
    fun `fewer scored frames than required falls back to the heuristic`() {
        val result = detector.detect(
            listOf(
                frame(1, yaw = 0f, modelScore = 0.1f),
                frame(2, yaw = 8f),
                frame(3, yaw = -8f),
            ),
        )

        assertEquals(LivenessState.LIVE, result.state)
        assertEquals(LivenessSource.HEURISTIC, result.source)
    }

    @Test
    fun `a mostly faceless window is no-face`() {
        val result = detector.detect(
            listOf(
                frame(1, facePresent = false),
                frame(2, facePresent = false),
                frame(3, facePresent = true, yaw = 10f),
            ),
        )

        assertEquals(LivenessState.NO_FACE, result.state)
    }
}
