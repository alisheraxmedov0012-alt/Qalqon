package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.faceguard.app.core.liveness.LivenessEvaluator
import uz.faceguard.app.core.liveness.LivenessFrame
import uz.faceguard.app.core.liveness.LivenessSource
import uz.faceguard.app.domain.policy.LivenessState

/**
 * Group 9: the evaluator glues the window to the detector. Pure JVM.
 */
class LivenessEvaluatorTest {

    private fun frame(ts: Long, yaw: Float = 0f) =
        LivenessFrame(facePresent = true, yawDegrees = yaw, timestamp = ts)

    @Test
    fun `an unevaluated pipeline reports unknown`() {
        val evaluator = LivenessEvaluator()

        val result = evaluator.result(1_000L)

        assertEquals(LivenessState.UNKNOWN, result.state)
        assertEquals(LivenessSource.NONE, result.source)
        assertNull(result.confidence)
        assertEquals(0, evaluator.frameCount)
    }

    @Test
    fun `observed motion produces live`() {
        val evaluator = LivenessEvaluator()
        evaluator.observe(frame(1_000, yaw = 0f))
        evaluator.observe(frame(1_050, yaw = 6f))
        evaluator.observe(frame(1_100, yaw = -6f))

        val result = evaluator.result(1_100L)

        assertEquals(LivenessState.LIVE, result.state)
        assertEquals(LivenessSource.HEURISTIC, result.source)
        assertEquals(3, evaluator.frameCount)
    }

    @Test
    fun `stale evidence decays once the window is idle`() {
        val evaluator = LivenessEvaluator()
        evaluator.observe(frame(1_000, yaw = 0f))
        evaluator.observe(frame(1_050, yaw = 6f))
        evaluator.observe(frame(1_100, yaw = -6f))
        assertEquals(LivenessState.LIVE, evaluator.result(1_100L).state)

        // 10s later, no new frames: the window has aged out.
        val decayed = evaluator.result(11_100L)

        assertEquals(LivenessState.UNKNOWN, decayed.state)
        assertEquals(LivenessSource.NONE, decayed.source)
    }

    @Test
    fun `reset drops all observations`() {
        val evaluator = LivenessEvaluator()
        evaluator.observe(frame(1_000, yaw = 0f))
        evaluator.observe(frame(1_050, yaw = 6f))
        evaluator.observe(frame(1_100, yaw = -6f))

        evaluator.reset()

        assertEquals(0, evaluator.frameCount)
        assertEquals(LivenessState.UNKNOWN, evaluator.result(1_100L).state)
    }
}
