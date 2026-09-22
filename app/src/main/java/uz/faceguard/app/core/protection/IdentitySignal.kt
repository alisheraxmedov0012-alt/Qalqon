package uz.faceguard.app.core.protection

import uz.faceguard.app.core.recognition.RecognitionResult
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.UserIdentity

/**
 * Where an identity observation came from.
 *
 * Group 8 keeps the two protection signals explicit and independent:
 * - [CAMERA]  — a live recognition frame produced this identity,
 * - [NONE]    — no camera signal was available, so the engine fell back to no-face.
 */
enum class IdentitySource { CAMERA, NONE }

/**
 * Group 8: "who is in front of the phone", the identity signal.
 *
 * This is deliberately *not* a second identity model: it wraps the existing
 * domain [IdentityContext] (the same representation the policy evaluator
 * consumes) and only adds the observation source and timestamp needed by the
 * runtime's state. The foreground app is a separate signal (Group 7).
 */
data class IdentitySnapshot(
    val context: IdentityContext,
    val source: IdentitySource,
    /** When this identity was first observed (unchanged while it stays the same). */
    val updatedAt: Long,
) {
    val identity: UserIdentity get() = context.identity
    val childId: Long? get() = context.childId
    val childName: String? get() = context.childName
    val confidence: Float? get() = context.confidence
}

/**
 * Recognition result -> policy identity. The canonical mapping used by the
 * protection engine; no-face is never treated as an unknown user.
 */
internal fun identityContextOf(result: RecognitionResult): IdentityContext = when (result) {
    is RecognitionResult.ParentRecognized -> IdentityContext(
        identity = UserIdentity.PARENT,
        confidence = result.confidence.toFloat(),
    )

    is RecognitionResult.ChildRecognized -> IdentityContext(
        identity = UserIdentity.CHILD,
        childId = result.childId,
        childName = result.childName,
        confidence = result.confidence.toFloat(),
    )

    is RecognitionResult.Unknown -> IdentityContext(
        identity = UserIdentity.UNKNOWN,
        confidence = result.confidence.toFloat(),
    )

    RecognitionResult.NoFace -> IdentityContext(identity = UserIdentity.NO_FACE)

    is RecognitionResult.CameraPossiblyObstructed ->
        IdentityContext(identity = UserIdentity.CAMERA_OBSTRUCTED)

    is RecognitionResult.UnstableRecognition -> IdentityContext(identity = UserIdentity.UNKNOWN)
}
