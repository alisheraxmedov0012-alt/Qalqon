package uz.faceguard.app.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.oem.AndroidBatteryOptimization
import uz.faceguard.app.core.oem.AndroidOemDetector
import uz.faceguard.app.core.oem.AndroidOemSettings
import uz.faceguard.app.core.permission.openFirstSettingsOrFallback
import uz.faceguard.app.domain.oem.OemSettingsTarget

/**
 * Stage 6: instrumented checks for the OEM/battery compatibility layer.
 *
 * These assert the real Android behaviour the pure tests cannot: that the settings
 * intent lists are always actionable (ending in a universal fallback), that the
 * battery probe never throws, and that launching an unresolvable list is a clean
 * false. CI runs this on the emulator (which reports manufacturer "Google"); it is
 * **not** an OEM device validation.
 */
@RunWith(AndroidJUnit4::class)
class Stage6OemCompatibilityInstrumentedTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private val oemSettings = AndroidOemSettings(context, AndroidOemDetector())

    @Test
    fun detectionIsNonCrashing_andCached() {
        val detector = AndroidOemDetector()
        assertNotNull(detector.family)
        assertEquals("the family must be stable for the process", detector.family, detector.family)
        // The emulator reports Google, but any recognised family is acceptable here.
        assertEquals(detector.family, oemSettings.family)
    }

    @Test
    fun autostartCandidates_alwaysEndInTheAppDetailsFallback() {
        val candidates = oemSettings.settingsIntentCandidates(OemSettingsTarget.AUTOSTART)
        assertTrue("the list must never be empty", candidates.isNotEmpty())
        assertEquals(
            "the last candidate must be the universal app-details fallback",
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            candidates.last().action,
        )
    }

    @Test
    fun batteryOptimizationCandidates_startWithThePlatformPage() {
        val candidates = oemSettings.batteryOptimizationIntentCandidates()
        assertTrue(candidates.isNotEmpty())
        assertEquals(
            "the platform battery page is the primary, permission-free action",
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            candidates.first().action,
        )
        assertEquals(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            candidates.last().action,
        )
    }

    @Test
    fun batteryProbe_neverThrows_returnsBooleanOrNull() {
        val result = AndroidBatteryOptimization.isIgnoringOptimizations(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            assertEquals(null, result)
        } else {
            assertNotNull("API 23+ must yield a real boolean", result)
        }
    }

    @Test
    fun launchingAnUnresolvableList_isACleanFalse_neverACrash() {
        val bogus = listOf(
            Intent().setClassName("com.nonexistent.oem.package", "com.nonexistent.oem.Page"),
        )
        assertFalse(context.openFirstSettingsOrFallback(bogus))
    }

    @Test
    fun theAppDetailsFallbackAlwaysResolves() {
        // Resolve (do not launch) so the test never opens a system screen.
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
        val resolved = context.packageManager.resolveActivity(fallback, 0)
        assertNotNull("the app-details fallback must resolve on every device", resolved)
    }
}
