package uz.faceguard.app.oem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.oem.ComponentSpec
import uz.faceguard.app.domain.oem.OemCompatibilityProfiles
import uz.faceguard.app.domain.oem.OemFamily
import uz.faceguard.app.domain.oem.OemSettingsTarget
import uz.faceguard.app.domain.oem.OemSupportLevel

/**
 * Stage 6: the OEM compatibility profiles.
 *
 * Every family must have a profile; a SUPPORTED profile must actually carry
 * autostart candidates; and every candidate must be a well-formed component — the
 * Android layer resolves each one before use, so a stale candidate degrades to the
 * generic fallback instead of a crash.
 */
class OemCompatibilityProfilesTest {

    @Test
    fun everyFamilyHasAProfile() {
        OemFamily.entries.forEach { family ->
            val profile = OemCompatibilityProfiles.forFamily(family)
            assertEquals("profile family must match", family, profile.family)
        }
    }

    @Test
    fun aSupportedProfileCarriesAutostartCandidates() {
        OemFamily.entries
            .map { OemCompatibilityProfiles.forFamily(it) }
            .filter { it.supportLevel == OemSupportLevel.SUPPORTED }
            .forEach { profile ->
                assertTrue(
                    "${profile.family} is SUPPORTED but has no autostart candidates",
                    profile.autostartCandidates.isNotEmpty(),
                )
            }
    }

    @Test
    fun aGenericProfileCarriesNoOemSpecificCandidates() {
        OemFamily.entries
            .map { OemCompatibilityProfiles.forFamily(it) }
            .filter { it.supportLevel == OemSupportLevel.GENERIC }
            .forEach { profile ->
                assertTrue(profile.autostartCandidates.isEmpty())
                assertTrue(profile.backgroundCandidates.isEmpty())
            }
    }

    @Test
    fun everyCandidateIsAWellFormedComponent() {
        OemFamily.entries
            .map { OemCompatibilityProfiles.forFamily(it) }
            .flatMap { it.autostartCandidates + it.backgroundCandidates }
            .forEach { spec ->
                assertTrue("package must be non-blank", spec.packageName.isNotBlank())
                assertTrue("class must be non-blank", spec.className.isNotBlank())
                assertTrue("package must look like a package", spec.packageName.contains("."))
                assertTrue("class must be fully qualified", spec.className.contains("."))
                assertEquals("${spec.packageName}/${spec.className}", spec.flattened)
            }
    }

    @Test
    fun candidatesForAUTOSTARTAreTheAutostartList() {
        val profile = OemCompatibilityProfiles.forFamily(OemFamily.XIAOMI)
        assertEquals(profile.autostartCandidates, profile.candidatesFor(OemSettingsTarget.AUTOSTART))
    }

    @Test
    fun candidatesForBACKGROUNDRestrictionAreTheBackgroundList() {
        val profile = OemCompatibilityProfiles.forFamily(OemFamily.HONOR)
        assertEquals(
            profile.backgroundCandidates,
            profile.candidatesFor(OemSettingsTarget.BACKGROUND_RESTRICTION),
        )
    }

    @Test
    fun candidatesForBatteryOptimizationAreAlwaysEmpty_thePlatformPageIsUsed() {
        // Battery optimization is a platform page, never an OEM component.
        OemFamily.entries.forEach { family ->
            assertTrue(
                OemCompatibilityProfiles.forFamily(family)
                    .candidatesFor(OemSettingsTarget.BATTERY_OPTIMIZATION)
                    .isEmpty(),
            )
        }
    }

    @Test
    fun theXiaomiFamilySharesItsAutostartPage() {
        val xiaomi = OemCompatibilityProfiles.forFamily(OemFamily.XIAOMI).autostartCandidates
        assertEquals(xiaomi, OemCompatibilityProfiles.forFamily(OemFamily.REDMI).autostartCandidates)
        assertEquals(xiaomi, OemCompatibilityProfiles.forFamily(OemFamily.POCO).autostartCandidates)
    }

    @Test
    fun componentSpecFlatteningMatchesAndroidComponentNameFormat() {
        val spec = ComponentSpec("com.example.oem", "com.example.oem.AutostartActivity")
        assertEquals("com.example.oem/com.example.oem.AutostartActivity", spec.flattened)
    }
}
