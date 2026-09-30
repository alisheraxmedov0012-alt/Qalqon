package uz.faceguard.app.core.enrollment

import uz.faceguard.app.core.pipeline.FaceQuality
import uz.faceguard.app.domain.enrollment.EnrollmentFrame

/**
 * Phase 12 (enrollment): the boundary between the live camera pipeline and the
 * pure quality domain.
 *
 * It takes the already-extracted per-frame values rather than the Android
 * `FrameEvent` (which carries ML Kit's `InputImage`), so the mapping — including
 * the sanitising — is verifiable on the JVM.
 *
 * It is the single place raw ML Kit numbers enter the gate, and therefore the
 * single place they are made safe: a NaN or infinite measurement is replaced by
 * the value that fails its own check, so a face with an unusable measurement can
 * never be accepted. A non-finite embedding is passed through unchanged so the
 * embedding validator rejects the frame explicitly instead of it being silently
 * turned into a "valid-looking" zero vector.
 */
object EnrollmentFrameMapper {

    fun map(
        quality: FaceQuality?,
        faceCount: Int,
        embedding: FloatArray?,
        timestampMs: Long,
    ): EnrollmentFrame = EnrollmentFrame(
        timestampMs = timestampMs,
        faceCount = quality?.faceCount ?: faceCount,
        faceWidthRatio = safe(quality?.faceWidthRatio, 0f),
        // An unusable position is likewise carried through as NaN so the gate
        // rejects it as off-centre rather than treating the face as centred.
        centerXRatio = safe(quality?.faceCenterXRatio, Float.NaN),
        centerYRatio = safe(quality?.faceCenterYRatio, Float.NaN),
        // A non-finite pose is unusable: fall back to a value that fails the
        // frontal check rather than one that silently passes it.
        pitchDegrees = safe(quality?.headEulerAngleX, Float.NaN),
        yawDegrees = safe(quality?.headEulerAngleY, Float.NaN),
        rollDegrees = safe(quality?.headEulerAngleZ, Float.NaN),
        brightness = safe(quality?.brightness, 0f),
        sharpness = safe(quality?.sharpness, 0f),
        landmarkVisibility = safe(quality?.landmarkVisibility, 0f),
        embedding = embedding,
    )

    private fun safe(value: Float?, fallback: Float): Float =
        if (value != null && value.isFinite()) value else fallback
}
