package uz.faceguard.app.core

import android.content.pm.ServiceInfo
import android.os.Build
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.accessibility.AccessibilityOverlayWindow
import uz.faceguard.app.core.compat.PlatformCompat

/**
 * Stage 7: platform drift guards for the version-compatibility matrix.
 *
 * The pure matrix mirrors a handful of platform constants so it can be asserted on the
 * JVM for every API level. This test runs **on a device/emulator** and asserts the
 * mirrored values equal the real platform values, so a future SDK cannot silently make
 * the matrix wrong. It also checks the real overlay window object picks up a cutout
 * mode, and that the runtime SDK level is inside the supported range.
 *
 * CI runs this on the API 35 emulator. That is an emulator result, not a certification
 * of every Android 15 (or any other) physical device.
 */
@RunWith(AndroidJUnit4::class)
class Stage7VersionCompatInstrumentedTest {

    @Test
    fun theDeviceApiLevelIsWithinTheSupportedRange() {
        assertTrue(
            "supported range is API 26–36, running on ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT in 26..36,
        )
    }

    @Test
    fun theMirroredForegroundServiceTypeBitsMatchThePlatform() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA, PlatformCompat.FGS_TYPE_CAMERA)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, PlatformCompat.FGS_TYPE_SPECIAL_USE)
        assertEquals(0, PlatformCompat.FGS_TYPE_NONE)
    }

    @Test
    fun theMirroredCutoutModesMatchThePlatform() {
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
            PlatformCompat.CUTOUT_MODE_DEFAULT,
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            PlatformCompat.CUTOUT_MODE_SHORT_EDGES,
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER,
            PlatformCompat.CUTOUT_MODE_NEVER,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
                PlatformCompat.CUTOUT_MODE_ALWAYS,
            )
        }
    }

    @Test
    fun theBlockingOverlayRequestsCutoutCoverageOnApi28Plus() {
        val params = AccessibilityOverlayWindow.layoutParams()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertNotEquals(
                "a full-screen block must not leave the cutout strip uncovered",
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER,
                params.layoutInDisplayCutoutMode,
            )
            assertNotEquals(
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
                params.layoutInDisplayCutoutMode,
            )
        }
    }

    @Test
    fun theRuntimeMatrixAgreesWithTheDeviceApiLevel() {
        val api = Build.VERSION.SDK_INT
        assertEquals(api >= 34, PlatformCompat.supportsSpecialUseForegroundType(api))
        assertEquals(api >= 30, PlatformCompat.supportsCameraForegroundType(api))
        assertEquals(api >= 29, PlatformCompat.supportsUnsafeCheckOp(api))
        assertEquals(api >= 28, PlatformCompat.supportsDisplayCutoutMode(api))
        assertEquals(api >= 33, PlatformCompat.notificationRuntimePermissionRequired(api))
    }

    @Test
    fun theNotificationGateIsTheAndroid13BoundaryAcrossEveryApi() {
        // The published level must be exactly the Android 13 boundary, and the gate
        // must agree with it on every supported API.
        assertEquals(33, PlatformCompat.NOTIFICATION_PERMISSION_API)
        (26..36).forEach { api ->
            assertEquals(api >= 33, PlatformCompat.notificationRuntimePermissionRequired(api))
        }
    }
}
