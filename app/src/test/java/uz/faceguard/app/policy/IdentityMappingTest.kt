package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.faceguard.app.core.protection.IdentitySnapshot
import uz.faceguard.app.core.protection.IdentitySource
import uz.faceguard.app.core.protection.identityContextOf
import uz.faceguard.app.core.recognition.RecognitionResult
import uz.faceguard.app.domain.policy.UserIdentity

/**
 * Group 8: the identity mapping that turns a recognition result into the
 * canonical policy identity. Pure Kotlin (no Android), so it runs on the JVM.
 */
class IdentityMappingTest {

    @Test
    fun parentIsMappedToParentWithItsConfidence() {
        val identity = identityContextOf(RecognitionResult.ParentRecognized(0.83))

        assertEquals(UserIdentity.PARENT, identity.identity)
        assertNull(identity.childId)
        assertEquals(0.83f, identity.confidence!!, 0.0001f)
    }

    @Test
    fun childKeepsItsProfileIdentifier() {
        val identity = identityContextOf(RecognitionResult.ChildRecognized(7L, "Vali", 0.71))

        assertEquals(UserIdentity.CHILD, identity.identity)
        assertEquals(7L, identity.childId)
        assertEquals("Vali", identity.childName)
        assertEquals(0.71f, identity.confidence!!, 0.0001f)
    }

    @Test
    fun unknownIsMappedToUnknown() {
        val identity = identityContextOf(RecognitionResult.Unknown(0.4))

        assertEquals(UserIdentity.UNKNOWN, identity.identity)
        assertEquals(0.4f, identity.confidence!!, 0.0001f)
    }

    @Test
    fun noFaceIsMappedToNoFaceAndNeverToUnknown() {
        val noFace = identityContextOf(RecognitionResult.NoFace)

        assertEquals(UserIdentity.NO_FACE, noFace.identity)
        assertNotEquals("no-face must never be treated as an unknown user", UserIdentity.UNKNOWN, noFace.identity)
        assertNull(noFace.childId)
    }

    @Test
    fun obstructedAndUnstableKeepTheirOwnSemantics() {
        assertEquals(
            UserIdentity.CAMERA_OBSTRUCTED,
            identityContextOf(RecognitionResult.CameraPossiblyObstructed).identity,
        )
        assertEquals(
            UserIdentity.UNKNOWN,
            identityContextOf(RecognitionResult.UnstableRecognition).identity,
        )
    }

    @Test
    fun snapshotCarriesSourceAndTimestampWithoutDuplicatingTheModel() {
        val snapshot = IdentitySnapshot(
            context = identityContextOf(RecognitionResult.ChildRecognized(7L, "Vali", 0.71)),
            source = IdentitySource.CAMERA,
            updatedAt = 1234L,
        )

        assertEquals(UserIdentity.CHILD, snapshot.identity)
        assertEquals(7L, snapshot.childId)
        assertEquals("Vali", snapshot.childName)
        assertEquals(IdentitySource.CAMERA, snapshot.source)
        assertEquals(1234L, snapshot.updatedAt)
    }
}
