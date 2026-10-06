package uz.faceguard.app.oem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.oem.OemDetector
import uz.faceguard.app.domain.oem.OemFamily

/**
 * Stage 6: OEM detection from the standard build strings.
 *
 * Pure and deterministic, so every supported family (and the sub-brand rules that
 * matter in practice) is pinned here. Detection only ever drives compatibility
 * *guidance*; these tests exist so a device is never mis-guidanced and an unknown
 * device is never guessed at.
 */
class OemDetectorTest {

    private fun detect(manufacturer: String?, brand: String? = null, model: String? = null) =
        OemDetector.detect(manufacturer, brand, model)

    // ------------------------------------------------------------- per family

    @Test fun samsung_isDetected() =
        assertEquals(OemFamily.SAMSUNG, detect("samsung", "samsung", "SM-G991B"))

    @Test fun xiaomi_isDetected() =
        assertEquals(OemFamily.XIAOMI, detect("Xiaomi", "Xiaomi", "Mi 11"))

    @Test fun redmi_isDetectedAsItsOwnFamily_beforeXiaomi() =
        assertEquals(OemFamily.REDMI, detect("Xiaomi", "Redmi", "Redmi Note 14"))

    @Test fun poco_isDetectedAsItsOwnFamily() =
        assertEquals(OemFamily.POCO, detect("Xiaomi", "POCO", "POCO X5"))

    @Test fun oppo_isDetected() =
        assertEquals(OemFamily.OPPO, detect("OPPO", "OPPO", "CPH2451"))

    @Test fun oneplus_isDetected() =
        assertEquals(OemFamily.ONEPLUS, detect("OnePlus", "OnePlus", "NE2213"))

    @Test fun vivo_isDetected() =
        assertEquals(OemFamily.VIVO, detect("vivo", "vivo", "V2231"))

    @Test fun iqoo_subBrand_mapsToVivo() =
        assertEquals(OemFamily.VIVO, detect("vivo", "iQOO", "I2220"))

    @Test fun realme_isDetected() =
        assertEquals(OemFamily.REALME, detect("realme", "realme", "RMX3630"))

    @Test fun honor_isDetected() =
        assertEquals(OemFamily.HONOR, detect("HONOR", "HONOR", "ANY-NX1"))

    @Test fun huawei_isDetected() =
        assertEquals(OemFamily.HUAWEI, detect("HUAWEI", "HUAWEI", "ELE-L29"))

    @Test fun motorola_isDetected() =
        assertEquals(OemFamily.MOTOROLA, detect("motorola", "moto", "XT2201"))

    @Test fun googlePixel_isDetected() =
        assertEquals(OemFamily.GOOGLE, detect("Google", "google", "Pixel 7"))

    @Test fun anUnknownManufacturer_isUnknown_neverGuessed() =
        assertEquals(OemFamily.UNKNOWN, detect("SomeVendor", "SomeVendor", "X1"))

    // ------------------------------------------------------- normalisation

    @Test fun detectionIsCaseInsensitive() {
        assertEquals(OemFamily.SAMSUNG, detect("SAMSUNG"))
        assertEquals(OemFamily.SAMSUNG, detect("Samsung"))
        assertEquals(OemFamily.SAMSUNG, detect("samsung"))
    }

    @Test fun detectionTrimsWhitespace() =
        assertEquals(OemFamily.XIAOMI, detect("  Xiaomi  "))

    @Test fun anEmptyOrNullIdentity_isUnknown() {
        assertEquals(OemFamily.UNKNOWN, detect(null, null, null))
        assertEquals(OemFamily.UNKNOWN, detect("", "", ""))
        assertEquals(OemFamily.UNKNOWN, detect("   ", null, "  "))
    }

    @Test fun theSubBrandCanBeCarriedOnlyByTheModelString() =
        // Some Xiaomi builds report manufacturer "Xiaomi" but only the model marks a Redmi.
        assertEquals(OemFamily.REDMI, detect("Xiaomi", null, "Redmi Note 12 Pro"))

    // --------------------------------------------------- background-restriction flag

    @Test fun familiesKnownToRestrictBackground_areFlagged() {
        listOf(
            OemFamily.XIAOMI, OemFamily.REDMI, OemFamily.POCO, OemFamily.OPPO,
            OemFamily.ONEPLUS, OemFamily.VIVO, OemFamily.REALME, OemFamily.HONOR,
            OemFamily.HUAWEI,
        ).forEach { assertTrue("$it should be flagged", it.hasKnownBackgroundRestrictions) }
    }

    @Test fun familiesWithoutKnownRestrictions_areNotFlagged() {
        listOf(OemFamily.SAMSUNG, OemFamily.MOTOROLA, OemFamily.GOOGLE, OemFamily.UNKNOWN)
            .forEach { assertFalse("$it should not be flagged", it.hasKnownBackgroundRestrictions) }
    }
}
