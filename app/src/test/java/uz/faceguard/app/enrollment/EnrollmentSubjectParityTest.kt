package uz.faceguard.app.enrollment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.enrollment.EnrollmentFrame
import uz.faceguard.app.domain.enrollment.EnrollmentQualityConfig
import uz.faceguard.app.domain.enrollment.EnrollmentQualityGate
import uz.faceguard.app.domain.enrollment.EnrollmentVerdict
import uz.faceguard.app.domain.enrollment.FrontalEnrollmentCollector
import uz.faceguard.app.enrollment.EnrollmentFixtures.frame
import uz.faceguard.app.enrollment.EnrollmentFixtures.similar
import uz.faceguard.app.enrollment.EnrollmentFixtures.unit
import uz.faceguard.app.feature.enrollment.FaceEnrollmentViewModel

/**
 * Phase 12 (enrollment): parent and child enrollment must share one quality gate.
 *
 * The guarantee is structural as well as behavioural: the quality gate has no
 * "subject" input at all, and the ViewModel holds exactly one collector/config, so
 * a parent and a child cannot drift onto different quality criteria. Only the
 * identity target and the copy differ.
 */
class EnrollmentSubjectParityTest {

    // 26/27. Both enrollment targets use the same gate.
    @Test
    fun theQualityGateHasNoSubjectInput() {
        val evaluate = EnrollmentQualityGate::class.java.declaredMethods
            .first { it.name == "evaluate" }

        assertEquals(1, evaluate.parameterCount)
        assertEquals(EnrollmentFrame::class.java, evaluate.parameterTypes[0])
    }

    @Test
    fun theViewModelHoldsExactlyOneQualityCollectorAndConfig() {
        val fieldTypes = FaceEnrollmentViewModel::class.java.declaredFields.map { it.type }

        assertEquals(
            "parent and child enrollment must share a single collector",
            1,
            fieldTypes.count { FrontalEnrollmentCollector::class.java.isAssignableFrom(it) },
        )
        assertEquals(
            "parent and child enrollment must share a single quality config",
            1,
            fieldTypes.count { EnrollmentQualityConfig::class.java.isAssignableFrom(it) },
        )
    }

    @Test
    fun noSubjectSpecificQualityConfigurationCanBeInjected() {
        val parameterNames = FaceEnrollmentViewModel::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }
            .map { it.simpleName }

        val subjectScopedQuality = parameterNames.filter { name ->
            val subject = name.contains("Parent") || name.contains("Child")
            val quality = name.contains("Quality") || name.contains("Gate") || name.contains("Collector")
            subject && quality
        }

        assertTrue("subject-specific quality config: $subjectScopedQuality", subjectScopedQuality.isEmpty())
    }

    @Test
    fun identicalFramesProduceIdenticalDecisionsForAnyTarget() {
        val gate = EnrollmentQualityGate(EnrollmentQualityConfig())
        val samples = listOf(
            frame(faceCount = 0),
            frame(faceCount = 1),
            frame(faceCount = 2),
            frame(yawDegrees = 30f),
            frame(brightness = 0.05f),
            frame(embedding = null),
            frame(),
        )

        // One gate, evaluated for the parent scenario and the child scenario.
        val parentDecisions = samples.map { gate.evaluate(it) }
        val childDecisions = samples.map { gate.evaluate(it) }

        assertEquals(parentDecisions, childDecisions)
        assertTrue(parentDecisions.last() is EnrollmentVerdict.Accepted)
    }

    @Test
    fun theSameCollectorCompletesForEitherSubject() {
        val config = EnrollmentQualityConfig(
            framesRequired = 5,
            framesToKeep = 5,
            minSpanMs = 1_000L,
            expectedEmbeddingDimension = 0,
        )
        val base = unit(1f, 0f, 0f, 0f)

        fun run(): Boolean {
            val collector = FrontalEnrollmentCollector(config)
            var complete = false
            repeat(6) { index ->
                complete = collector.onFrame(
                    frame(timestampMs = index * 300L, embedding = similar(base, 0.02f)),
                ).complete || complete
            }
            return complete
        }

        assertTrue("parent enrollment completes", run())
        assertTrue("child enrollment completes identically", run())
    }
}
