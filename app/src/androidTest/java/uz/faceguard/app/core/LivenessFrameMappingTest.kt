package uz.faceguard.app.core

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.liveness.LivenessFrame
import uz.faceguard.app.core.pipeline.FaceQuality
import uz.faceguard.app.core.pipeline.FrameEvent

/**
 * Group 9: the recognition frame -> liveness evidence projection. Verifies the
 * pipeline seam (face presence, head pose and the optional model score) without
 * needing a camera.
 */
@RunWith(AndroidJUnit4::class)
class LivenessFrameMappingTest {

    private fun image() = InputImage.fromBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 0)

    @Test
    fun frameWithoutFace_isNotFacePresent() {
        val frame = FrameEvent(image = image(), faceCount = 0, quality = FaceQuality(faceCount = 0), timestamp = 5L)

        val evidence = LivenessFrame.from(frame)

        assertFalse(evidence.facePresent)
        assertNull(evidence.modelScore)
        assertEquals(5L, evidence.timestamp)
    }

    @Test
    fun detectedFace_isFacePresentEvenWithoutQuality() {
        val frame = FrameEvent(image = image(), faceCount = 1, features = FloatArray(4) { 1f }, timestamp = 6L)

        assertTrue(LivenessFrame.from(frame).facePresent)
    }

    @Test
    fun headPose_isCarriedFromTheQualitySnapshot() {
        val frame = FrameEvent(
            image = image(),
            faceCount = 1,
            quality = FaceQuality(faceCount = 1, headEulerAngleX = 1f, headEulerAngleY = 2f, headEulerAngleZ = 3f),
            timestamp = 7L,
        )

        val evidence = LivenessFrame.from(frame)

        assertEquals(1f, evidence.pitchDegrees, 0.0001f)
        assertEquals(2f, evidence.yawDegrees, 0.0001f)
        assertEquals(3f, evidence.rollDegrees, 0.0001f)
    }

    @Test
    fun modelScore_isPassedThroughUnchanged() {
        val frame = FrameEvent(
            image = image(),
            faceCount = 1,
            quality = FaceQuality(faceCount = 1),
            liveProbability = 0.12f,
            timestamp = 8L,
        )

        assertEquals(0.12f, LivenessFrame.from(frame).modelScore!!, 0.0001f)
    }
}
