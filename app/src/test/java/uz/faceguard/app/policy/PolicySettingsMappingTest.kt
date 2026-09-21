package uz.faceguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import uz.faceguard.app.data.prefs.MAX_RECOVERY_DELAY_MS
import uz.faceguard.app.data.prefs.parseEnumValue
import uz.faceguard.app.data.prefs.scopedSettingsKeyName
import uz.faceguard.app.data.prefs.validRecoveryDelayMs
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.asBlockPolicy
import uz.faceguard.app.domain.policy.toPolicySettings

/**
 * JVM tests for the persisted-settings parsing/validation and the
 * AppSettings -> PolicySettings mapping. These call the exact production
 * helpers, no mocks.
 */
class PolicySettingsMappingTest {

    // ---------------------------------------------------------------- defaults

    @Test
    fun `fresh settings defaults match the documented contract`() {
        val defaults = AppSettings()
        assertEquals(false, defaults.protectionEnabled)
        assertEquals(ScanMode.BALANCED, defaults.scanMode)
        assertEquals(AppSettings.DEFAULT_RECOVERY_DELAY_MS, defaults.recoveryDelayMs)
        assertEquals(BlockPolicy.SOFT_BLOCK, defaults.unknownUserPolicy)
        assertEquals(BlockPolicy.ALLOW, defaults.noFacePolicy)
        assertEquals(true, defaults.lowBatteryBehaviorEnabled)
    }

    // ------------------------------------------------------------ enum parsing

    @Test
    fun `known enum names parse`() {
        assertEquals(BlockPolicy.HARD_BLOCK, parseEnumValue("HARD_BLOCK", BlockPolicy.SOFT_BLOCK))
        assertEquals(ScanMode.STRICT, parseEnumValue("STRICT", ScanMode.BALANCED))
        assertEquals(ProtectionAction.MUTE, parseEnumValue("MUTE", ProtectionAction.ALLOW))
    }

    @Test
    fun `unknown enum name falls back instead of crashing`() {
        assertEquals(
            BlockPolicy.SOFT_BLOCK,
            parseEnumValue("NOT_A_POLICY", BlockPolicy.SOFT_BLOCK),
        )
        assertEquals(BlockPolicy.SOFT_BLOCK, parseEnumValue("not_a_policy", BlockPolicy.SOFT_BLOCK))
        assertEquals(BlockPolicy.SOFT_BLOCK, parseEnumValue("", BlockPolicy.SOFT_BLOCK))
    }

    @Test
    fun `missing enum value falls back`() {
        assertEquals(BlockPolicy.ALLOW, parseEnumValue(null, BlockPolicy.ALLOW))
    }

    @Test
    fun `corrupt unknown-user policy never becomes ALLOW`() {
        // The documented default for unknown users is SOFT_BLOCK, so a corrupt
        // stored value must not turn into a permissive policy.
        val parsed = parseEnumValue("garbage", BlockPolicy.SOFT_BLOCK)
        assertEquals(BlockPolicy.SOFT_BLOCK, parsed)
        assertEquals(false, parsed == BlockPolicy.ALLOW)
    }

    // --------------------------------------------------------- numeric parsing

    @Test
    fun `valid recovery delay is preserved in milliseconds`() {
        assertEquals(5_000L, validRecoveryDelayMs(5_000L))
        assertEquals(30_000L, validRecoveryDelayMs(30_000L))
        assertEquals(MAX_RECOVERY_DELAY_MS, validRecoveryDelayMs(MAX_RECOVERY_DELAY_MS))
    }

    @Test
    fun `invalid recovery delay falls back to the default`() {
        val default = AppSettings.DEFAULT_RECOVERY_DELAY_MS
        assertEquals(default, validRecoveryDelayMs(null))
        assertEquals(default, validRecoveryDelayMs(-1L))
        assertEquals(default, validRecoveryDelayMs(0L))
        assertEquals(default, validRecoveryDelayMs(Long.MIN_VALUE))
        assertEquals(default, validRecoveryDelayMs(MAX_RECOVERY_DELAY_MS + 1))
        assertEquals(default, validRecoveryDelayMs(Long.MAX_VALUE))
    }

    // ------------------------------------------------------------ key scoping

    @Test
    fun `account scoped key names are namespaced by account id`() {
        assertEquals("acc_7_scan_mode", scopedSettingsKeyName(7L, "scan_mode"))
        assertEquals("acc_42_no_face_policy", scopedSettingsKeyName(42L, "no_face_policy"))
        // Different accounts never share a key.
        assertEquals(false, scopedSettingsKeyName(1L, "x") == scopedSettingsKeyName(2L, "x"))
    }

    // ---------------------------------------------------------------- mapping

    @Test
    fun `persisted settings map onto policy settings`() {
        val settings = AppSettings(
            protectionEnabled = true,
            scanMode = ScanMode.STRICT,
            recoveryDelayMs = 12_000L,
            unknownUserPolicy = BlockPolicy.HARD_BLOCK,
            noFacePolicy = BlockPolicy.SOFT_BLOCK,
            lowBatteryBehaviorEnabled = false,
        )

        val policy = settings.toPolicySettings()

        assertEquals(true, policy.enabled)
        assertEquals(ProtectionAction.HARD_BLOCK, policy.unknownUserAction)
        assertEquals(ProtectionAction.SOFT_BLOCK, policy.noFaceAction)
        assertEquals(12_000L, policy.recoveryDelayMs)
        // Not persisted yet -> documented defaults.
        assertEquals(0L, policy.activationDelayMs)
        assertEquals(ProtectionAction.HARD_BLOCK, policy.childAction)
        assertEquals(false, policy.parentDeviceChildPolicyEnabled)
        // Obstruction follows the unknown-user policy (existing engine rule).
        assertEquals(policy.unknownUserAction, policy.obstructionAction)
    }

    @Test
    fun `disabled protection maps onto a disabled policy`() {
        val policy = AppSettings(protectionEnabled = false).toPolicySettings()
        assertEquals(false, policy.enabled)
    }

    @Test
    fun `storable actions map and non-storable ones fail fast`() {
        assertEquals(BlockPolicy.ALLOW, ProtectionAction.ALLOW.asBlockPolicy())
        assertEquals(BlockPolicy.SOFT_BLOCK, ProtectionAction.SOFT_BLOCK.asBlockPolicy())
        assertEquals(BlockPolicy.HARD_BLOCK, ProtectionAction.HARD_BLOCK.asBlockPolicy())

        assertThrows(IllegalArgumentException::class.java) {
            ProtectionAction.WARNING.asBlockPolicy()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtectionAction.BLUR.asBlockPolicy()
        }
    }
}
