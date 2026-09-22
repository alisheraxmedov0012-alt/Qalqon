package uz.faceguard.app.core

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.accessibility.ProtectionAccessibilityService
import uz.faceguard.app.core.protection.ProtectionForegroundService

/**
 * Group 8: the background-camera capability boundary.
 *
 * Android restricts background camera use (while-in-use camera permission) and
 * rejects camera-type foreground services started from the background, so QALQON
 * deliberately does **not** claim background camera recognition. These guards
 * fail if someone later adds a camera foreground-service type or the matching
 * permission without the required capability work, which would misrepresent the
 * product's real capability.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundCameraBoundaryTest {

    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val packageManager get() = appContext.packageManager

    private fun services() = packageManager
        .getPackageInfo(appContext.packageName, PackageManager.GET_SERVICES)
        .services
        ?.toList()
        .orEmpty()

    @Test
    fun protectionForegroundService_doesNotClaimTheCameraType() {
        val service = services().firstOrNull { it.name == ProtectionForegroundService::class.java.name }

        assertNotNull("the protection foreground service must be declared", service)
        assertEquals(
            "only the specialUse type may be claimed",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            service!!.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        assertEquals(
            "background camera is not claimed",
            0,
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
    }

    @Test
    fun accessibilityService_doesNotClaimTheCameraType() {
        val service = services().firstOrNull { it.name == ProtectionAccessibilityService::class.java.name }

        assertNotNull("the accessibility service must be declared", service)
        assertEquals(
            "the accessibility layer must not run the camera",
            0,
            service!!.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
    }

    @Test
    fun cameraIsOnlyUsedWhileInUse_neverAsABackgroundForegroundService() {
        val requested = packageManager
            .getPackageInfo(appContext.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()

        assertTrue("foreground capture needs the camera permission", "android.permission.CAMERA" in requested)
        assertFalse(
            "no camera foreground-service permission may be requested while background camera is not implemented",
            "android.permission.FOREGROUND_SERVICE_CAMERA" in requested,
        )
    }
}
