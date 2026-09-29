package uz.faceguard.app.enrollment

import org.junit.Assert.assertEquals
import org.junit.Test
import uz.faceguard.app.domain.similarity.CosineSimilarity

/**
 * Phase 12 (enrollment): the shared cosine used by both recognition and the
 * enrollment consistency check.
 *
 * Extracting it must not have changed the maths the Recognizer matches with, so
 * its semantics are pinned here: unit-range output, 1 for identical direction,
 * 0 for orthogonal, -1 for opposite, 0 for a degenerate vector, and comparison
 * over the shorter of the two vectors.
 */
class CosineSimilarityTest {

    @Test
    fun identicalDirectionIsOne() {
        assertEquals(1.0, CosineSimilarity.of(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 4f, 6f)), 1e-9)
    }

    @Test
    fun orthogonalIsZero() {
        assertEquals(0.0, CosineSimilarity.of(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
    }

    @Test
    fun oppositeIsMinusOne() {
        assertEquals(-1.0, CosineSimilarity.of(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-9)
    }

    @Test
    fun aDegenerateVectorScoresZeroRatherThanDividingByZero() {
        assertEquals(0.0, CosineSimilarity.of(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 1e-9)
    }

    @Test
    fun comparisonUsesTheShorterVector() {
        // The extra trailing component of the longer vector is ignored.
        assertEquals(1.0, CosineSimilarity.of(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f, 9f)), 1e-9)
    }

    @Test
    fun nearIdenticalVectorsScoreAboveTheConsistencyThreshold() {
        val base = EnrollmentFixtures.unit(1f, 0f, 0f, 0f)
        val nudged = EnrollmentFixtures.similar(base, 0.02f)
        val score = CosineSimilarity.of(base, nudged)

        assertEquals(1.0, score, 0.01)
        org.junit.Assert.assertTrue(score > 0.45)
    }
}
