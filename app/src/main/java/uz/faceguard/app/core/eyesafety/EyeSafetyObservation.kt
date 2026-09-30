package uz.faceguard.app.core.eyesafety

import uz.faceguard.app.core.pipeline.FaceQuality
import uz.faceguard.app.domain.eyesafety.EyeSafetyFrame

/**
 * Phase 6 Step 4: the boundary adapter from the camera pipeline's per-frame metrics to the pure
 * eye-safety domain model.
 *
 * It takes the already-computed [FaceQuality] rather than the whole `FrameEvent` on purpose:
 * `FrameEvent` carries an ML Kit `InputImage`, and depending on it would drag Android into the
 * eye-safety runtime state (and make it untestable off-device). Only what the domain needs crosses
 * here — the frame timestamp and the face's normalized width ratio (`FaceQuality.faceWidthRatio`,
 * which the existing pipeline already computes for every analysed frame). No new camera work, no
 * second detector, no ML Kit settings change and no absolute distance are introduced.
 *
 * **A frame with no usable face is reported as [EyeSafetyFrame.noFace], not as a zero ratio.**
 * A genuine zero would be indistinguishable from "the face is extremely far away", which is not
 * what "no face" means — the domain treats no-face as "no measurement", so it can become neither
 * SAFE nor DANGER.
 */
internal fun eyeSafetyFrameOf(quality: FaceQuality?, timestampMs: Long): EyeSafetyFrame =
    if (quality == null || quality.faceCount <= 0) {
        EyeSafetyFrame.noFace(timestampMs)
    } else {
        EyeSafetyFrame.measured(timestampMs, quality.faceWidthRatio)
    }
