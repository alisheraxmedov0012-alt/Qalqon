package uz.faceguard.app.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.compat.PlatformCompat

/**
 * Stage 7: the Android version-compatibility matrix (API 26–36), asserted on the JVM.
 *
 * Every supported API level is enumerated so a boundary regression (a wrong
 * `specialUse`/`camera` bit, a missing cutout mode) is a red test instead of a
 * device-only failure.
 */
class PlatformCompatTest {

    /** Every level the project claims to support, API 26 (Android 8.0) → 36 (Android 16). */
    private val supportedApis = (26..36).toList()

    // ------------------------------------------------- foreground service types

    @Test
    fun baseForegroundServiceType_hasNoSpecialUseBitBelowApi34() {
        // Below API 34 the specialUse type does not exist; sending the bit would hand
        // the platform an undefined type.
        (26..33).forEach { api ->
            assertEquals("api $api base mask", PlatformCompat.FGS_TYPE_NONE, PlatformCompat.foregroundServiceTypes(api, includeCamera = false))
        }
    }

    @Test
    fun baseForegroundServiceType_isSpecialUseFromApi34() {
        (34..36).forEach { api ->
            assertEquals(
                "api $api base mask",
                PlatformCompat.FGS_TYPE_SPECIAL_USE,
                PlatformCompat.foregroundServiceTypes(api, includeCamera = false),
            )
        }
    }

    @Test
    fun cameraForegroundServiceTypeIsAbsentBelowApi30() {
        (26..29).forEach { api ->
            assertEquals(
                "api $api must not claim the camera FGS type",
                PlatformCompat.FGS_TYPE_NONE,
                PlatformCompat.foregroundServiceTypes(api, includeCamera = true),
            )
        }
    }

    @Test
    fun cameraForegroundServiceTypeIsClaimedFromApi30() {
        (30..33).forEach { api ->
            assertEquals(
                "api $api camera-only mask",
                PlatformCompat.FGS_TYPE_CAMERA,
                PlatformCompat.foregroundServiceTypes(api, includeCamera = true),
            )
        }
        (34..36).forEach { api ->
            assertEquals(
                "api $api camera+specialUse mask",
                PlatformCompat.FGS_TYPE_CAMERA or PlatformCompat.FGS_TYPE_SPECIAL_USE,
                PlatformCompat.foregroundServiceTypes(api, includeCamera = true),
            )
        }
    }

    @Test
    fun theCameraBitIsOnlyEverPresentWhenExplicitlyRequested() {
        supportedApis.forEach { api ->
            val withoutCamera = PlatformCompat.foregroundServiceTypes(api, includeCamera = false)
            assertEquals(
                "api $api must never carry the camera bit unprompted",
                0,
                withoutCamera and PlatformCompat.FGS_TYPE_CAMERA,
            )
        }
    }

    @Test
    fun theSpecialUseBitIsNeverSentToAPlatformThatDoesNotDefineIt() {
        supportedApis.forEach { api ->
            val types = PlatformCompat.foregroundServiceTypes(api, includeCamera = true)
            if (api < PlatformCompat.API_SPECIAL_USE_FGS_TYPE) {
                assertEquals("api $api specialUse bit", 0, types and PlatformCompat.FGS_TYPE_SPECIAL_USE)
            }
        }
    }

    @Test
    fun foregroundTypeSupportPredicatesMatchTheMatrix() {
        supportedApis.forEach { api ->
            assertEquals(api >= 30, PlatformCompat.supportsCameraForegroundType(api))
            assertEquals(api >= 34, PlatformCompat.supportsSpecialUseForegroundType(api))
        }
    }

    // --------------------------------------------------------- display cutout

    @Test
    fun theFullscreenOverlayNeverUsesTheNeverCutoutMode() {
        // A blocking overlay must cover as much as possible; NEVER would shrink it.
        supportedApis.forEach { api ->
            assertTrue(
                "api $api cutout mode must not be NEVER",
                PlatformCompat.fullscreenOverlayCutoutMode(api) != PlatformCompat.CUTOUT_MODE_NEVER,
            )
        }
    }

    @Test
    fun cutoutModeIsDefaultBelowApi28() {
        (26..27).forEach { api ->
            assertEquals(
                "api $api has no cutout concept",
                PlatformCompat.CUTOUT_MODE_DEFAULT,
                PlatformCompat.fullscreenOverlayCutoutMode(api),
            )
            assertFalse(PlatformCompat.supportsDisplayCutoutMode(api))
        }
    }

    @Test
    fun cutoutModeCoversShortEdgesFromApi28AndAlwaysFromApi30() {
        assertEquals(PlatformCompat.CUTOUT_MODE_SHORT_EDGES, PlatformCompat.fullscreenOverlayCutoutMode(28))
        assertEquals(PlatformCompat.CUTOUT_MODE_SHORT_EDGES, PlatformCompat.fullscreenOverlayCutoutMode(29))
        (30..36).forEach { api ->
            assertEquals(
                "api $api must cover cutouts in every orientation",
                PlatformCompat.CUTOUT_MODE_ALWAYS,
                PlatformCompat.fullscreenOverlayCutoutMode(api),
            )
        }
        assertTrue(PlatformCompat.supportsDisplayCutoutMode(28))
    }

    // ------------------------------------------------------------- notifications

    @Test
    fun notificationPostPermissionIsRequiredOnlyFromApi33() {
        (26..32).forEach { api ->
            assertFalse("api $api must not require POST_NOTIFICATIONS", PlatformCompat.notificationRuntimePermissionRequired(api))
        }
        (33..36).forEach { api ->
            assertTrue("api $api must require POST_NOTIFICATIONS", PlatformCompat.notificationRuntimePermissionRequired(api))
        }
    }

    @Test
    fun theNotificationGateMatchesTheApisPublishedConstant() {
        assertEquals(33, PlatformCompat.NOTIFICATION_PERMISSION_API)
    }

    // ----------------------------------------------------------------- usage

    @Test
    fun unsafeCheckOpIsAvailableOnlyFromApi29() {
        (26..28).forEach { api -> assertFalse(PlatformCompat.supportsUnsafeCheckOp(api)) }
        (29..36).forEach { api -> assertTrue(PlatformCompat.supportsUnsafeCheckOp(api)) }
    }

    // --------------------------------------------------------------- constants

    @Test
    fun theMirroredPlatformConstantsKeepTheirDocumentedValues() {
        // The values are mirrored in PlatformCompat for JVM testability; the
        // instrumented suite cross-checks them against the real platform constants.
        assertEquals(0, PlatformCompat.FGS_TYPE_NONE)
        assertEquals(64, PlatformCompat.FGS_TYPE_CAMERA)
        assertEquals(1 shl 30, PlatformCompat.FGS_TYPE_SPECIAL_USE)
        assertEquals(0, PlatformCompat.CUTOUT_MODE_DEFAULT)
        assertEquals(1, PlatformCompat.CUTOUT_MODE_SHORT_EDGES)
        assertEquals(2, PlatformCompat.CUTOUT_MODE_NEVER)
        assertEquals(3, PlatformCompat.CUTOUT_MODE_ALWAYS)
    }
}
