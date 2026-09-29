package uz.faceguard.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ScanMode

/**
 * Phase 12: the persistence rules behind [SettingsStore].
 *
 * [SettingsStore] itself needs DataStore/Android, but every value it reads or
 * writes goes through the pure helpers in `SettingsParsing.kt`. Those helpers are
 * the corruption boundary: if they are wrong, a reboot or an upgrade silently
 * changes a parent's settings. This exercises the whole boundary on the JVM so a
 * DataStore round trip has nothing left to corrupt.
 *
 * Corruption policy under test: an unreadable value never becomes a more
 * permissive policy — unknown values fall back to the documented product default
 * (SOFT_BLOCK for the unknown-user policy, not ALLOW).
 */
class SettingsParsingTest {

    // ------------------------------------------------------------- enum parsing

    @Test
    fun everyScanModeRoundTrips() {
        ScanMode.entries.forEach { mode ->
            assertEquals(mode, parseEnumValue(mode.name, ScanMode.BALANCED))
        }
    }

    @Test
    fun everyBlockPolicyRoundTrips() {
        BlockPolicy.entries.forEach { policy ->
            assertEquals(policy, parseEnumValue(policy.name, BlockPolicy.SOFT_BLOCK))
        }
    }

    @Test
    fun missingValueFallsBackRatherThanThrowing() {
        assertEquals(ScanMode.BALANCED, parseEnumValue(null, ScanMode.BALANCED))
        assertEquals(BlockPolicy.ALLOW, parseEnumValue(null, BlockPolicy.ALLOW))
    }

    @Test
    fun unknownValueFallsBackRatherThanThrowing() {
        assertEquals(ScanMode.STRICT, parseEnumValue("NOT_A_MODE", ScanMode.STRICT))
        assertEquals(BlockPolicy.HARD_BLOCK, parseEnumValue("GARBAGE", BlockPolicy.HARD_BLOCK))
    }

    @Test
    fun aCorruptUnknownUserPolicyFallsBackToTheFailClosedDefault() {
        // AppSettings defaults unknownUserPolicy to SOFT_BLOCK (never ALLOW), so a
        // corrupted stored value must not open the device up.
        assertEquals(
            BlockPolicy.SOFT_BLOCK,
            parseEnumValue("corrupt", AppSettings().unknownUserPolicy),
        )
    }

    // --------------------------------------------------------- recovery delay

    @Test
    fun missingRecoveryDelayUsesTheProductDefault() {
        assertEquals(AppSettings.DEFAULT_RECOVERY_DELAY_MS, validRecoveryDelayMs(null))
    }

    @Test
    fun inRangeRecoveryDelaysAreKeptExactly() {
        assertEquals(1L, validRecoveryDelayMs(1L))
        assertEquals(30_000L, validRecoveryDelayMs(30_000L))
        assertEquals(MAX_RECOVERY_DELAY_MS, validRecoveryDelayMs(MAX_RECOVERY_DELAY_MS))
    }

    @Test
    fun outOfRangeRecoveryDelaysAreReplacedByTheDefault() {
        assertEquals(AppSettings.DEFAULT_RECOVERY_DELAY_MS, validRecoveryDelayMs(0L))
        assertEquals(AppSettings.DEFAULT_RECOVERY_DELAY_MS, validRecoveryDelayMs(-1L))
        assertEquals(AppSettings.DEFAULT_RECOVERY_DELAY_MS, validRecoveryDelayMs(MAX_RECOVERY_DELAY_MS + 1))
        assertEquals(AppSettings.DEFAULT_RECOVERY_DELAY_MS, validRecoveryDelayMs(Long.MAX_VALUE))
    }

    @Test
    fun recoveryDelayAcceptsAnExplicitDefault() {
        assertEquals(5_000L, validRecoveryDelayMs(null, default = 5_000L))
        assertEquals(5_000L, validRecoveryDelayMs(-1L, default = 5_000L))
    }

    @Test
    fun theOneHourUpperBoundIsPinned() {
        assertEquals(3_600_000L, MAX_RECOVERY_DELAY_MS)
    }

    // -------------------------------------------------------- account namespace

    @Test
    fun scopedKeysCarryTheAccountPrefixAndBaseName() {
        assertEquals("acc", ACCOUNT_KEY_PREFIX)
        assertEquals("acc_7_scan_mode", scopedSettingsKeyName(7L, "scan_mode"))
    }

    @Test
    fun scopedKeysAreStableAndAccountIsolated() {
        assertEquals(
            scopedSettingsKeyName(1L, "protection_enabled"),
            scopedSettingsKeyName(1L, "protection_enabled"),
        )
        assertTrue(
            scopedSettingsKeyName(1L, "protection_enabled") !=
                scopedSettingsKeyName(2L, "protection_enabled"),
        )
    }

    // ------------------------------------------------------------ app defaults

    @Test
    fun appSettingsDefaultsMatchTheDocumentedProductDefaults() {
        val defaults = AppSettings()
        assertEquals(false, defaults.protectionEnabled)
        assertEquals(ScanMode.BALANCED, defaults.scanMode)
        assertEquals(30_000L, defaults.recoveryDelayMs)
        assertEquals(BlockPolicy.SOFT_BLOCK, defaults.unknownUserPolicy)
        assertEquals(BlockPolicy.ALLOW, defaults.noFacePolicy)
        assertEquals(true, defaults.lowBatteryBehaviorEnabled)
    }

    @Test
    fun copyingSettingsPreservesEveryField() {
        val original = AppSettings(
            protectionEnabled = true,
            scanMode = ScanMode.STRICT,
            recoveryDelayMs = 45_000L,
            unknownUserPolicy = BlockPolicy.HARD_BLOCK,
            noFacePolicy = BlockPolicy.SOFT_BLOCK,
            lowBatteryBehaviorEnabled = false,
        )

        val copy = original.copy()

        assertEquals(original, copy)
    }
}
