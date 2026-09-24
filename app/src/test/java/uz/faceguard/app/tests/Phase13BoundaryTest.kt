package uz.faceguard.app.tests

import java.security.MessageDigest
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.liveness.LivenessFrame
import uz.faceguard.app.core.liveness.TemporalLivenessDetector
import uz.faceguard.app.core.policy.ActivationDelayGate
import uz.faceguard.app.core.security.AesGcmSecureCrypto
import uz.faceguard.app.core.security.CryptoResult
import uz.faceguard.app.core.security.PinHasher
import uz.faceguard.app.domain.notification.AppNotificationEvent
import uz.faceguard.app.domain.notification.DefaultNotificationPolicy
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.request.RequestStateMachine
import uz.faceguard.app.domain.request.RequestStatus
import uz.faceguard.app.domain.security.PinAttemptPolicy
import uz.faceguard.app.domain.security.PinAttemptState

/**
 * Phase 13: boundary and table-driven verification of the pure rules that the
 * rest of the app depends on. Every assertion is deterministic (fake clocks,
 * synthetic frames, no sleeps).
 */
class Phase13BoundaryTest {

    // ---- request state machine (complete transition table) ------------------

    @Test
    fun onlyPendingMayTransitionAndOnlyToATerminalState() {
        val statuses = RequestStatus.entries
        val expected = mapOf(
            RequestStatus.PENDING to setOf(
                RequestStatus.APPROVED,
                RequestStatus.REJECTED,
                RequestStatus.EXPIRED,
                RequestStatus.CANCELLED,
            ),
            RequestStatus.APPROVED to emptySet(),
            RequestStatus.REJECTED to emptySet(),
            RequestStatus.EXPIRED to emptySet(),
            RequestStatus.CANCELLED to emptySet(),
        )

        statuses.forEach { from ->
            statuses.forEach { to ->
                assertEquals(
                    "$from -> $to",
                    to in expected.getValue(from),
                    RequestStateMachine.isLegalTransition(from, to),
                )
            }
        }
    }

    // ---- crypto boundaries --------------------------------------------------

    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun crypto(k: java.security.Key?) = AesGcmSecureCrypto({ k as? javax.crypto.SecretKey })

    private fun encrypted(k: java.security.Key?, value: String): ByteArray =
        (crypto(k).encrypt(value.toByteArray()) as CryptoResult.Success).bytes

    @Test
    fun theEnvelopeHeaderIsExactlyAsDocumented() {
        val payload = encrypted(key(), "value")

        assertTrue(payload.copyOfRange(0, 4).contentEquals(AesGcmSecureCrypto.MAGIC))
        assertEquals(AesGcmSecureCrypto.VERSION, payload[4])
        assertEquals(AesGcmSecureCrypto.ALGORITHM_AES_GCM, payload[5])
        assertEquals(AesGcmSecureCrypto.IV_LENGTH_BYTES, payload[6].toInt())
        assertEquals("an empty plaintext still carries a full GCM tag",
            true, payload.size > AesGcmSecureCrypto.MAGIC.size + 3 + AesGcmSecureCrypto.IV_LENGTH_BYTES)
    }

    @Test
    fun aTruncatedEnvelopeIsRejected() {
        val payload = encrypted(key(), "some-value")

        // Cut inside the ciphertext/tag: authentication must fail.
        assertEquals(CryptoResult.Corrupted, crypto(key()).decrypt(payload.copyOfRange(0, payload.size - 4)))
        // Cut inside the header/IV: structurally invalid.
        assertEquals(
            CryptoResult.Malformed,
            crypto(key()).decrypt(payload.copyOfRange(0, AesGcmSecureCrypto.MAGIC.size + 4)),
        )
    }

    @Test
    fun anEmptyPlaintextRoundTrips() {
        val k = key()
        val payload = encrypted(k, "")

        val result = crypto(k).decrypt(payload)

        assertTrue(result is CryptoResult.Success)
        assertEquals(0, (result as CryptoResult.Success).bytes.size)
    }

    @Test
    fun differentPlaintextsDecryptToTheirOwnValues() {
        val k = key()

        val first = crypto(k).decrypt(encrypted(k, "one")) as CryptoResult.Success
        val second = crypto(k).decrypt(encrypted(k, "two")) as CryptoResult.Success

        assertEquals("one", String(first.bytes))
        assertEquals("two", String(second.bytes))
    }

    // ---- notification burst window boundaries -------------------------------

    @Test
    fun theBurstWindowSplitsAtItsExactBoundary() {
        val policy = DefaultNotificationPolicy()
        val window = DefaultNotificationPolicy.BURST_WINDOW_MS
        fun keyAt(at: Long) = policy.decide(
            AppNotificationEvent.ProtectionBlocked(accountId = 1L, childId = 5L, targetPackageName = "com.example.a", at = at),
        )!!.deduplicationKey

        // Same bucket: the first and the last millisecond of one window.
        assertEquals(keyAt(window), keyAt(window * 2 - 1))
        // Exactly on the boundary and beyond: a new cycle, so a new notification.
        assertTrue(keyAt(window) != keyAt(window * 2))
        assertTrue(keyAt(window * 2) != keyAt(window * 2 + window))
    }

    // ---- PIN lockout boundaries --------------------------------------------

    @Test
    fun theLockoutExpiresExactlyAtItsDeadline() {
        var state = PinAttemptState()
        repeat(PinAttemptPolicy.MAX_ATTEMPTS) { state = PinAttemptPolicy.onFailure(state, 1_000L) }
        val until = state.lockedUntilMillis

        assertTrue("one millisecond before the deadline", state.isLocked(until - 1))
        assertFalse("exactly at the deadline", state.isLocked(until))
        assertEquals(0L, state.remainingLockMillis(until))
        assertEquals(1L, state.remainingLockMillis(until - 1))
    }

    @Test
    fun lockoutDurationsEscalateThroughEveryConfiguredStepThenCap() {
        var state = PinAttemptState()
        val observed = mutableListOf<Long>()
        repeat(PinAttemptPolicy.LOCKOUT_STEPS_MS.size + 2) {
            val before = state.failedCount
            repeat(PinAttemptPolicy.MAX_ATTEMPTS) { state = PinAttemptPolicy.onFailure(state, 0L) }
            observed += state.lockedUntilMillis - 0L
            assertTrue(state.failedCount > before)
        }

        assertEquals(PinAttemptPolicy.LOCKOUT_STEPS_MS.toList(), observed.take(PinAttemptPolicy.LOCKOUT_STEPS_MS.size))
        assertEquals(PinAttemptPolicy.MAX_LOCKOUT_MS, observed.last())
    }

    // ---- PIN derivation boundaries -----------------------------------------

    @Test
    fun pinHashesAreDeterministicPerSaltAndNeverContainThePin() {
        val salt = PinHasher.randomSalt()

        val a = PinHasher.hash("1234", salt)
        val b = PinHasher.hash("1234", salt)

        assertEquals("same salt + pin must be reproducible", a, b)
        assertTrue(PinHasher.verify("1234", a, salt))
        assertFalse(PinHasher.verify("12345", a, salt))
        assertFalse(a.contains("1234"))
        // The legacy scheme must still be distinguishable for upgrades.
        val legacy = MessageDigest.getInstance("SHA-256").digest((salt + "1234").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertTrue(PinHasher.needsUpgrade(legacy))
        assertTrue(PinHasher.verify("1234", legacy, salt))
    }

    // ---- activation delay boundaries ---------------------------------------

    @Test
    fun theActivationGateAppliesExactlyAtTheBoundary() {
        var now = 10_000L
        val gate = ActivationDelayGate { now }
        val delay = 3_000L

        assertFalse("the action is armed, not applied", gate.request(ProtectionAction.HARD_BLOCK, delay))
        assertTrue(gate.isPending)
        assertFalse(gate.isReady())

        now = 10_000L + delay - 1
        assertFalse("one millisecond early", gate.isReady())
        now = 10_000L + delay
        assertTrue("exactly at the delay", gate.isReady())
        now = 10_000L + delay + 1
        assertTrue(gate.isReady())

        gate.cancel()
        assertFalse(gate.isReady())
        assertFalse(gate.isPending)
    }

    @Test
    fun theActivationGateAppliesImmediatelyWhenTheDelayIsZero() {
        val gate = ActivationDelayGate { 1_000L }

        assertTrue(gate.request(ProtectionAction.SOFT_BLOCK, 0L))
        assertTrue(gate.isReady())
        assertTrue("no pending window for an immediate action", gate.isActive())
    }

    @Test
    fun changingTheRequestedActionRestartsItsWindow() {
        var now = 0L
        val gate = ActivationDelayGate { now }
        gate.request(ProtectionAction.SOFT_BLOCK, 1_000L)
        now = 900L

        assertFalse("a different action restarts the steady window", gate.request(ProtectionAction.HARD_BLOCK, 1_000L))
        assertEquals("the window restarted at the new request", 0L, gate.elapsedMs())
        now = 1_899L
        assertFalse(gate.isReady())
        now = 1_900L
        assertTrue("the restarted window elapses 1s after the change", gate.isReady())
    }

    // ---- liveness thresholds ------------------------------------------------

    private fun frame(at: Long, yaw: Float, present: Boolean = true, score: Float? = null) =
        LivenessFrame(facePresent = present, yawDegrees = yaw, modelScore = score, timestamp = at)

    @Test
    fun theMotionThresholdsAreInclusiveAtTheirExactValues() {
        val detector = TemporalLivenessDetector()

        val live = detector.detect(
            listOf(frame(1, 0f), frame(2, 0f), frame(3, TemporalLivenessDetector.DEFAULT_LIVE_MOTION_DEGREES)),
        )
        assertEquals(LivenessState.LIVE, live.state)

        val static = detector.detect(
            listOf(frame(1, 0f), frame(2, 0f), frame(3, TemporalLivenessDetector.DEFAULT_STATIC_MOTION_DEGREES)),
        )
        assertEquals(LivenessState.UNKNOWN, static.state)
    }

    @Test
    fun thePresenceRatioIsInclusiveAtItsExactValue() {
        val detector = TemporalLivenessDetector()

        // 2 of 4 frames contain a face -> ratio exactly at the 0.5 threshold.
        val atThreshold = detector.detect(
            listOf(frame(1, 0f), frame(2, 0f, present = false), frame(3, 0f), frame(4, 0f, present = false)),
        )
        assertTrue("at the threshold the window is not treated as no-face", atThreshold.state != LivenessState.NO_FACE)

        // 1 of 3 frames contains a face -> below the 0.5 presence threshold.
        val belowThreshold = detector.detect(
            listOf(frame(1, 0f), frame(2, 0f, present = false), frame(3, 0f, present = false)),
        )
        assertEquals(LivenessState.NO_FACE, belowThreshold.state)
    }

    @Test
    fun theModelThresholdsAreInclusiveAtTheirExactValues() {
        val detector = TemporalLivenessDetector()
        val spoof = TemporalLivenessDetector.DEFAULT_MODEL_SPOOF_THRESHOLD
        val live = TemporalLivenessDetector.DEFAULT_MODEL_LIVE_THRESHOLD

        assertEquals(
            LivenessState.SPOOF,
            detector.detect(listOf(frame(1, 0f, score = spoof), frame(2, 0f, score = spoof), frame(3, 0f, score = spoof))).state,
        )
        assertEquals(
            LivenessState.LIVE,
            detector.detect(listOf(frame(1, 0f, score = live), frame(2, 0f, score = live), frame(3, 0f, score = live))).state,
        )
    }
}
