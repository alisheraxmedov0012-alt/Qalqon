package uz.faceguard.app.eyesafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import uz.faceguard.app.data.db.ChildEyeSafetyEntity
import uz.faceguard.app.data.repository.toDomainEyeSafety
import uz.faceguard.app.data.repository.toEntity
import uz.faceguard.app.domain.eyesafety.ChildEyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyConfig
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 6 Step 3 (pure JVM): the Room entity <-> domain configuration mapping.
 *
 * Runs without a database, so the normal unit-test task verifies it. Every persisted field is
 * asserted to survive the round trip, including the percent <-> ratio threshold conversion and the
 * whole `ProtectionAction` enum, and a stored row that is not a valid configuration is asserted to
 * fail loudly rather than being silently corrected.
 */
class EyeSafetyConfigMapperTest {

    private fun config(
        enabled: Boolean = true,
        warningEnter: Float = 0.30f,
        warningExit: Float = 0.27f,
        dangerEnter: Float = 0.40f,
        dangerExit: Float = 0.35f,
        confirmFrames: Int = 3,
    ) = EyeSafetyConfig(
        enabled = enabled,
        warningEnterThreshold = warningEnter,
        warningExitThreshold = warningExit,
        dangerEnterThreshold = dangerEnter,
        dangerExitThreshold = dangerExit,
        confirmFrames = confirmFrames,
    )

    private fun domain(
        accountId: Long = 1L,
        childId: Long = 10L,
        config: EyeSafetyConfig = config(),
        warningAction: ProtectionAction = ProtectionAction.WARNING,
        dangerAction: ProtectionAction = ProtectionAction.SOFT_BLOCK,
        updatedAt: Long = 1_700_000_000_000L,
    ) = ChildEyeSafetyConfig(
        accountId = accountId,
        childId = childId,
        config = config,
        warningAction = warningAction,
        dangerAction = dangerAction,
        updatedAt = updatedAt,
    )

    private fun entity(
        accountId: Long = 1L,
        childId: Long = 10L,
        enabled: Boolean = true,
        warningEnterPercent: Int = 30,
        warningExitPercent: Int = 27,
        dangerEnterPercent: Int = 40,
        dangerExitPercent: Int = 35,
        confirmFrames: Int = 3,
        warningAction: String = ProtectionAction.WARNING.name,
        dangerAction: String = ProtectionAction.SOFT_BLOCK.name,
        updatedAt: Long = 1_700_000_000_000L,
    ) = ChildEyeSafetyEntity(
        accountId = accountId,
        childId = childId,
        enabled = enabled,
        warningEnterThresholdPercent = warningEnterPercent,
        warningExitThresholdPercent = warningExitPercent,
        dangerEnterThresholdPercent = dangerEnterPercent,
        dangerExitThresholdPercent = dangerExitPercent,
        confirmFrames = confirmFrames,
        warningAction = warningAction,
        dangerAction = dangerAction,
        updatedAt = updatedAt,
    )

    // ---- A. entity -> domain -------------------------------------------------

    @Test
    fun everyPersistedFieldMapsToTheDomainModel() {
        val mapped = entity().toDomainEyeSafety()

        assertEquals(1L, mapped.accountId)
        assertEquals(10L, mapped.childId)
        assertEquals(true, mapped.config.enabled)
        assertEquals(0.30f, mapped.config.warningEnterThreshold)
        assertEquals(0.27f, mapped.config.warningExitThreshold)
        assertEquals(0.40f, mapped.config.dangerEnterThreshold)
        assertEquals(0.35f, mapped.config.dangerExitThreshold)
        assertEquals(3, mapped.config.confirmFrames)
        assertEquals(ProtectionAction.WARNING, mapped.warningAction)
        assertEquals(ProtectionAction.SOFT_BLOCK, mapped.dangerAction)
        assertEquals(1_700_000_000_000L, mapped.updatedAt)
    }

    @Test
    fun aDisabledConfigurationMapsItsFlag() {
        assertEquals(false, entity(enabled = false).toDomainEyeSafety().config.enabled)
    }

    @Test
    fun thresholdPercentagesConvertToTheNormalizedRatio() {
        val mapped = entity(
            warningEnterPercent = 31,
            warningExitPercent = 28,
            dangerEnterPercent = 47,
            dangerExitPercent = 41,
        ).toDomainEyeSafety()

        assertEquals(0.31f, mapped.config.warningEnterThreshold)
        assertEquals(0.28f, mapped.config.warningExitThreshold)
        assertEquals(0.47f, mapped.config.dangerEnterThreshold)
        assertEquals(0.41f, mapped.config.dangerExitThreshold)
    }

    @Test
    fun aZeroExitPercentageIsAccepted() {
        // Exit thresholds legitimately allow 0 (the boundary has no lower dead band).
        val mapped = entity(warningExitPercent = 0, dangerExitPercent = 0).toDomainEyeSafety()

        assertEquals(0f, mapped.config.warningExitThreshold)
        assertEquals(0f, mapped.config.dangerExitThreshold)
    }

    @Test
    fun theUnpersistedTemporalParametersTakeTheDomainDefaults() {
        // maxWindowAgeMs / maxWindowFrames / minimumPresenceRatio are engine tuning, not parent
        // configuration, so a loaded configuration carries the Step 1 defaults for them.
        val mapped = entity().toDomainEyeSafety()

        assertEquals(EyeSafetyConfig.DEFAULT_MAX_WINDOW_AGE_MS, mapped.config.maxWindowAgeMs)
        assertEquals(EyeSafetyConfig.DEFAULT_MAX_WINDOW_FRAMES, mapped.config.maxWindowFrames)
        assertEquals(EyeSafetyConfig.DEFAULT_MINIMUM_PRESENCE_RATIO, mapped.config.minimumPresenceRatio)
    }

    @Test
    fun everyProtectionActionValueRoundTripsAsItsName() {
        ProtectionAction.entries.forEach { action ->
            val mapped = entity(warningAction = action.name, dangerAction = action.name).toDomainEyeSafety()

            assertEquals(action, mapped.warningAction)
            assertEquals(action, mapped.dangerAction)
        }
    }

    @Test
    fun anUnknownStoredActionFailsLoudly() {
        // Never silently substitute a different action: a corrupt row must surface.
        assertThrows(IllegalArgumentException::class.java) {
            entity(warningAction = "NOT_AN_ACTION").toDomainEyeSafety()
        }
        assertThrows(IllegalArgumentException::class.java) {
            entity(dangerAction = "NOT_AN_ACTION").toDomainEyeSafety()
        }
    }

    @Test
    fun anOutOfRangeStoredEnterPercentageFailsLoudly() {
        // 0 and 100 cannot be enter thresholds; the domain's invariant must reject them, not a clamp.
        assertThrows(IllegalArgumentException::class.java) { entity(warningEnterPercent = 0).toDomainEyeSafety() }
        assertThrows(IllegalArgumentException::class.java) { entity(warningEnterPercent = 100).toDomainEyeSafety() }
        assertThrows(IllegalArgumentException::class.java) { entity(dangerEnterPercent = 0).toDomainEyeSafety() }
        assertThrows(IllegalArgumentException::class.java) { entity(dangerEnterPercent = 100).toDomainEyeSafety() }
    }

    @Test
    fun aCorruptStoredOrderingFailsLoudlyRatherThanBeingSwapped() {
        // warning >= danger must not be "fixed" by reordering the thresholds.
        assertThrows(IllegalArgumentException::class.java) {
            entity(warningEnterPercent = 45, dangerEnterPercent = 40).toDomainEyeSafety()
        }
        // An exit threshold above its enter threshold is equally invalid.
        assertThrows(IllegalArgumentException::class.java) {
            entity(warningEnterPercent = 30, warningExitPercent = 35).toDomainEyeSafety()
        }
    }

    @Test
    fun aCorruptStoredConfirmFramesFailsLoudly() {
        assertThrows(IllegalArgumentException::class.java) { entity(confirmFrames = 0).toDomainEyeSafety() }
        assertThrows(IllegalArgumentException::class.java) { entity(confirmFrames = -3).toDomainEyeSafety() }
    }

    // ---- B. domain -> entity -------------------------------------------------

    @Test
    fun everyDomainFieldMapsToTheEntity() {
        val mapped = domain().toEntity()

        assertEquals(1L, mapped.accountId)
        assertEquals(10L, mapped.childId)
        assertEquals(true, mapped.enabled)
        assertEquals(30, mapped.warningEnterThresholdPercent)
        assertEquals(27, mapped.warningExitThresholdPercent)
        assertEquals(40, mapped.dangerEnterThresholdPercent)
        assertEquals(35, mapped.dangerExitThresholdPercent)
        assertEquals(3, mapped.confirmFrames)
        assertEquals(ProtectionAction.WARNING.name, mapped.warningAction)
        assertEquals(ProtectionAction.SOFT_BLOCK.name, mapped.dangerAction)
        assertEquals(1_700_000_000_000L, mapped.updatedAt)
    }

    @Test
    fun ratioConvertsBackToTheWholePercent() {
        val mapped = domain(
            config = config(warningEnter = 0.31f, warningExit = 0.28f, dangerEnter = 0.47f, dangerExit = 0.41f),
        ).toEntity()

        assertEquals(31, mapped.warningEnterThresholdPercent)
        assertEquals(28, mapped.warningExitThresholdPercent)
        assertEquals(47, mapped.dangerEnterThresholdPercent)
        assertEquals(41, mapped.dangerExitThresholdPercent)
    }

    @Test
    fun everyWholePercentRoundTripsExactly() {
        // 30/100f is the same float as the 0.30f literal, so a whole percent survives both ways.
        // Each iteration builds a *valid* configuration (exit below enter, danger above warning),
        // because the domain rejects anything else — which is exactly the point of the round trip.
        val warningEnterPercents = 1..40
        warningEnterPercents.forEach { percent ->
            val entity = entity(
                warningEnterPercent = percent,
                warningExitPercent = percent - 1,
                dangerEnterPercent = percent + 1,
                dangerExitPercent = percent,
                confirmFrames = 1,
            ).toDomainEyeSafety().toEntity()

            assertEquals("percent $percent must round-trip", percent, entity.warningEnterThresholdPercent)
            assertEquals(percent - 1, entity.warningExitThresholdPercent)
            assertEquals(percent + 1, entity.dangerEnterThresholdPercent)
        }
    }

    @Test
    fun everyProtectionActionValueIsStoredByItsName() {
        ProtectionAction.entries.forEach { action ->
            val mapped = domain(warningAction = action, dangerAction = action).toEntity()

            assertEquals(action.name, mapped.warningAction)
            assertEquals(action.name, mapped.dangerAction)
        }
    }

    // ---- full round trip -----------------------------------------------------

    @Test
    fun aDomainConfigurationSurvivesTheRoundTripUnchanged() {
        val original = domain(
            accountId = 7L,
            childId = 42L,
            config = config(
                enabled = false,
                warningEnter = 0.25f,
                warningExit = 0.20f,
                dangerEnter = 0.50f,
                dangerExit = 0.45f,
                confirmFrames = 5,
            ),
            warningAction = ProtectionAction.MUTE,
            dangerAction = ProtectionAction.HARD_BLOCK,
            updatedAt = 123_456_789L,
        )

        val restored = original.toEntity().toDomainEyeSafety()

        assertEquals(original, restored)
    }

    @Test
    fun aSecondRoundTripIsStable() {
        val once = domain().toEntity().toDomainEyeSafety()

        assertEquals(once, once.toEntity().toDomainEyeSafety())
    }

    @Test
    fun mappingIsDeterministic() {
        val entity = entity()

        assertEquals(entity.toDomainEyeSafety(), entity.toDomainEyeSafety())
        assertNotNull(entity.toDomainEyeSafety())
    }
}
